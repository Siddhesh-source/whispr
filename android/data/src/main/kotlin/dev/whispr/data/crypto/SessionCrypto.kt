package dev.whispr.data.crypto

import dev.whispr.data.db.CryptoDao
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.identity.IdentityKeyPairSource
import dev.whispr.data.network.BundleResponse
import dev.whispr.data.network.BundleResult
import dev.whispr.domain.model.TrustState
import java.util.Base64
import java.util.concurrent.Callable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.InvalidVersionException
import org.signal.libsignal.protocol.LegacyMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle

/** Fetches (and consumes) a peer's prekey bundle from the server. */
fun interface BundleSource {
    suspend fun bundle(userId: String): BundleResult
}

/** Why a peer's outgoing lane is blocked. */
enum class ParkReason {
    /** The peer has not uploaded keys yet (e.g. hasn't opened the app since updating). */
    NoKeys,
    UnknownUser,
    Network,
    RateLimited,

    /** The server returned a bundle libsignal rejected (bad signature). */
    BadBundle,

    /** The peer's identity key differs from the pin; waits for the user. */
    KeyChanged,
}

sealed interface SessionStatus {
    data object Ready : SessionStatus
    data class Blocked(val reason: ParkReason) : SessionStatus
}

sealed interface EncryptResult<out T> {
    data class Ok<T>(val value: T) : EncryptResult<T>
    data class Blocked(val reason: ParkReason) : EncryptResult<Nothing>
}

sealed interface DecryptResult<out T> {
    /** [usedOneTimeKey]: a PreKey message consumed one of our one-time prekeys. */
    data class Ok<T>(val value: T, val usedOneTimeKey: Boolean) : DecryptResult<T>

    /** Already processed (replayed ciphertext); drop silently. */
    data object Replay : DecryptResult<Nothing>

    /** Sent under an identity key that differs from the pin; hold it. */
    class Untrusted(val identityKey: ByteArray?) : DecryptResult<Nothing>

    /** Not decryptable: malformed, tampered, or our state is missing. */
    data class Failed(val reason: String) : DecryptResult<Nothing>
}

/**
 * All libsignal session work. Every call runs on [dispatcher], which must be
 * single-threaded, so ratchet steps never interleave. State changes happen in
 * one database transaction together with the caller's write, so a crash can
 * never advance a ratchet without storing what it produced or consumed.
 * Network I/O (bundle fetches) never happens inside a transaction.
 */
class SessionCrypto(
    private val db: WhisprDatabase,
    private val store: SignalStore,
    private val identity: IdentityKeyPairSource,
    private val bundles: BundleSource,
    private val dispatcher: CoroutineDispatcher,
    /** Our own user ID (libsignal wants the local address too). */
    private val localUser: suspend () -> String,
) {
    private val dao: CryptoDao get() = db.cryptoDao()

    @Volatile private var local: SignalProtocolAddress? = null

    private suspend fun ready() {
        if (local != null) return
        store.setIdentityKeyPair(identity.keyPair())
        local = address(localUser())
    }

    // Note the argument order differs: SessionBuilder takes (remote, local),
    // SessionCipher takes (local, remote) (libsignal 0.104).
    private fun builder(peer: String) = SessionBuilder(store, store, store, store, address(peer), local!!)

    private fun cipher(peer: String) = SessionCipher(store, store, store, store, store, local!!, address(peer))

    private suspend fun <T> onCrypto(block: () -> T): T {
        ready()
        return withContext(dispatcher) { block() }
    }

    /** Runs [block] in a transaction; any exception rolls everything back. */
    private fun <T> tx(block: () -> T): T = db.runInTransaction(Callable { block() })

    suspend fun hasSession(peer: String): Boolean = onCrypto { store.containsSession(address(peer)) }

    /** Runs [block] as one transaction on the crypto thread (serialized with all session work). */
    suspend fun <T> transaction(block: () -> T): T = onCrypto { tx(block) }

    /**
     * Makes sure a sending session with [peer] exists, fetching a bundle if
     * not. With [rebuild] a fresh session replaces the current one (a reset);
     * the old one is archived only once a usable bundle is in hand.
     */
    suspend fun ensureSession(peer: String, rebuild: Boolean = false): SessionStatus {
        if (!rebuild && hasSession(peer)) return SessionStatus.Ready
        // A contact whose key change is unacknowledged gets nothing new.
        if (onCrypto { dao.contact(peer)?.trust == TrustState.KeyChanged.name }) {
            return SessionStatus.Blocked(ParkReason.KeyChanged)
        }
        val bundle = when (val r = bundles.bundle(peer)) {
            is BundleResult.Success -> r.bundle
            BundleResult.NoKeys -> return SessionStatus.Blocked(ParkReason.NoKeys)
            BundleResult.UnknownUser -> return SessionStatus.Blocked(ParkReason.UnknownUser)
            BundleResult.RateLimited -> return SessionStatus.Blocked(ParkReason.RateLimited)
            BundleResult.NetworkError, is BundleResult.Failed -> return SessionStatus.Blocked(ParkReason.Network)
        }
        val preKeyBundle = try {
            bundle.toPreKeyBundle()
        } catch (_: InvalidKeyException) {
            return SessionStatus.Blocked(ParkReason.BadBundle)
        } catch (_: IllegalArgumentException) {
            return SessionStatus.Blocked(ParkReason.BadBundle)
        }
        return onCrypto {
            try {
                tx { processBundle(peer, preKeyBundle, rebuild) }
            } catch (_: InvalidKeyException) {
                SessionStatus.Blocked(ParkReason.BadBundle)
            } catch (_: UntrustedIdentityException) {
                SessionStatus.Blocked(ParkReason.KeyChanged)
            }
        }
    }

    private fun processBundle(peer: String, bundle: PreKeyBundle, rebuild: Boolean): SessionStatus {
        // Another caller may have built a session while we were fetching.
        if (!rebuild && store.containsSession(address(peer))) return SessionStatus.Ready
        val key = bundle.identityKey.serialize()
        val contact = dao.contact(peer)
        if (contact != null && contact.identityKey.isNotEmpty() && !contact.identityKey.contentEquals(key)) {
            // The server says this peer's key changed: flag it, keep the pin,
            // and build nothing until the user acknowledges.
            dao.flagKeyChange(peer, key)
            return SessionStatus.Blocked(ParkReason.KeyChanged)
        }
        if (contact != null && contact.identityKey.isEmpty()) dao.putContact(contact.copy(identityKey = key))
        // Processing a new bundle archives the current session state.
        builder(peer).process(bundle)
        return SessionStatus.Ready
    }

    /**
     * Pads and encrypts [plaintext] for [peer] and, in the same transaction,
     * hands the wire bytes to [persist] (e.g. to store them in the outbox).
     */
    suspend fun <T> encrypt(peer: String, plaintext: ByteArray, persist: (wire: ByteArray) -> T): EncryptResult<T> =
        onCrypto {
            try {
                tx {
                    val message = cipher(peer).encrypt(Padding.pad(plaintext))
                    EncryptResult.Ok(persist(WireFormat.encode(message)))
                }
            } catch (_: UntrustedIdentityException) {
                EncryptResult.Blocked(ParkReason.KeyChanged)
            } catch (_: NoSessionException) {
                EncryptResult.Blocked(ParkReason.Network)
            }
        }

    /** Encrypts a best-effort transient payload, only if a session already exists. */
    suspend fun encryptTransient(peer: String, plaintext: ByteArray): ByteArray? {
        if (!hasSession(peer)) return null
        return (encrypt(peer, plaintext) { it } as? EncryptResult.Ok)?.value
    }

    /**
     * Decrypts [payload] from [sender] and runs [work] on the plaintext in
     * the same transaction. Any libsignal failure leaves state untouched.
     * Exceptions thrown by [work] propagate unchanged (the caller must not
     * acknowledge the envelope then).
     */
    suspend fun <T> decrypt(sender: String, payload: ByteArray, work: (plaintext: ByteArray) -> T): DecryptResult<T> =
        onCrypto {
            val (type, body) = WireFormat.decode(payload) ?: return@onCrypto DecryptResult.Failed("format")
            var preKeyMessage: PreKeySignalMessage? = null
            try {
                tx {
                    val cipher = cipher(sender)
                    val padded = if (type == WireFormat.TYPE_PREKEY) {
                        val m = PreKeySignalMessage(body).also { preKeyMessage = it }
                        cipher.decrypt(m)
                    } else {
                        cipher.decrypt(SignalMessage(body))
                    }
                    val plaintext = Padding.unpad(padded) ?: throw BadPadding()
                    val value = try {
                        work(plaintext)
                    } catch (e: Exception) {
                        throw WorkFailed(e)
                    }
                    DecryptResult.Ok(value, usedOneTimeKey = preKeyMessage?.preKeyId?.isPresent == true)
                }
            } catch (e: WorkFailed) {
                throw e.cause!!
            } catch (_: DuplicateMessageException) {
                DecryptResult.Replay
            } catch (_: ReusedBaseKeyException) {
                DecryptResult.Replay
            } catch (_: UntrustedIdentityException) {
                DecryptResult.Untrusted(preKeyMessage?.identityKey?.serialize())
            } catch (_: InvalidKeyIdException) {
                // Our key for this PreKey message is gone. If we allocated it,
                // it was consumed by the original message (or retired): this is
                // a replay. A key we never issued is a genuine failure.
                if (preKeyMessage?.let(::wasIssued) ==
                    true
                ) {
                    DecryptResult.Replay
                } else {
                    DecryptResult.Failed("unknown key")
                }
            } catch (_: BadPadding) {
                DecryptResult.Failed("padding")
            } catch (e: InvalidMessageException) {
                DecryptResult.Failed(e.javaClass.simpleName)
            } catch (e: InvalidKeyException) {
                DecryptResult.Failed(e.javaClass.simpleName)
            } catch (e: NoSessionException) {
                DecryptResult.Failed(e.javaClass.simpleName)
            } catch (e: InvalidVersionException) {
                DecryptResult.Failed(e.javaClass.simpleName)
            } catch (e: LegacyMessageException) {
                DecryptResult.Failed(e.javaClass.simpleName)
            }
        }

    private fun wasIssued(m: PreKeySignalMessage): Boolean {
        val oneTimeIssued = !m.preKeyId.isPresent || store.wasAllocated(SignalStore.KeyKind.OneTime, m.preKeyId.get())
        return oneTimeIssued && store.wasAllocated(SignalStore.KeyKind.Signed, m.signedPreKeyId)
    }

    /**
     * After the user acknowledged a key change: keep the session if it is
     * already with the newly pinned key (an incoming PreKey message built
     * it), otherwise drop it so the next send builds a fresh one.
     */
    suspend fun onKeyChangeAcknowledged(peer: String) = onCrypto {
        tx {
            val pin = dao.contact(peer)?.identityKey ?: return@tx
            val record = store.loadSession(address(peer))
            val remote = try {
                record.remoteIdentityKey?.serialize()
            } catch (_: NoSessionException) {
                null
            }
            if (remote != null && !remote.contentEquals(pin)) store.deleteSession(address(peer))
        }
    }

    private class BadPadding : Exception()

    private class WorkFailed(cause: Exception) : Exception(cause)

    companion object {
        fun address(peer: String) = SignalProtocolAddress(peer, SignalStore.DEVICE_ID)

        private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

        /** Throws InvalidKeyException or IllegalArgumentException for malformed bundles. */
        fun BundleResponse.toPreKeyBundle(): PreKeyBundle = PreKeyBundle(
            registrationId,
            deviceId,
            oneTime?.keyId ?: PreKeyBundle.NULL_PRE_KEY_ID,
            oneTime?.let { ECPublicKey(b64(it.publicKey)) },
            signedPreKey.keyId,
            ECPublicKey(b64(signedPreKey.publicKey)),
            b64(signedPreKey.signature),
            IdentityKey(b64(identityKey)),
            kyber.keyId,
            KEMPublicKey(b64(kyber.publicKey)),
            b64(kyber.signature),
        )
    }
}
