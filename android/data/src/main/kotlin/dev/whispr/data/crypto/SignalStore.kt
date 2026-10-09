package dev.whispr.data.crypto

import dev.whispr.data.db.ContactEntity
import dev.whispr.data.db.CryptoDao
import dev.whispr.data.db.KyberUsedBaseKeyEntity
import dev.whispr.data.db.SettingEntity
import dev.whispr.data.db.SignalKyberPreKeyEntity
import dev.whispr.data.db.SignalPreKeyEntity
import dev.whispr.data.db.SignalSessionEntity
import dev.whispr.data.db.SignalSignedPreKeyEntity
import dev.whispr.domain.model.TrustState
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.IdentityKeyStore.Direction
import org.signal.libsignal.protocol.state.IdentityKeyStore.IdentityChange
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * libsignal's storage interfaces on the SQLCipher database. libsignal calls
 * these synchronously, so they must only be used on the crypto thread inside
 * a database transaction (see [SessionCrypto]); never on the main thread.
 *
 * Identities are the contact pins from QR scans and lookups (Phase 3):
 * - Sending: only to the pinned key, and not while a key change is pending.
 * - Receiving: only from the pinned key. A message under a different key
 *   throws UntrustedIdentityException and is held until the user
 *   acknowledges the change; it is never shown before that.
 * - No pin yet: trust on first use, as contact lookups already do.
 */
class SignalStore(private val dao: CryptoDao, private val clock: () -> Long = System::currentTimeMillis) :
    IdentityKeyStore,
    SessionStore,
    PreKeyStore,
    SignedPreKeyStore,
    KyberPreKeyStore {

    @Volatile private var identity: IdentityKeyPair? = null

    /** Must be set (from the Keystore-wrapped identity file) before use. */
    fun setIdentityKeyPair(pair: IdentityKeyPair) {
        identity = pair
    }

    // IdentityKeyStore

    override fun getIdentityKeyPair(): IdentityKeyPair = identity ?: error("identity key pair not loaded")

    override fun getLocalRegistrationId(): Int {
        dao.setting(REGISTRATION_ID)?.toIntOrNull()?.let { return it }
        val id = KeyHelper.generateRegistrationId(false)
        dao.putSetting(SettingEntity(REGISTRATION_ID, id.toString()))
        return id
    }

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityChange {
        val key = identityKey.serialize()
        val contact = dao.contact(address.name)
        return when {
            contact == null -> {
                // A stranger's first message: key pinned, but not listed. Only their
                // contact request (accounts are private) makes them a visible request.
                dao.putContact(ContactEntity(address.name, UNKNOWN_CONTACT, key, clock(), hidden = true))
                IdentityChange.NEW_OR_UNCHANGED
            }
            contact.identityKey.isEmpty() -> {
                dao.putContact(contact.copy(identityKey = key))
                IdentityChange.NEW_OR_UNCHANGED
            }
            contact.identityKey.contentEquals(key) -> IdentityChange.NEW_OR_UNCHANGED
            else -> {
                // Never replace a pin silently: hold the new key until acknowledged.
                dao.flagKeyChange(address.name, key)
                IdentityChange.REPLACED_EXISTING
            }
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: Direction,
    ): Boolean {
        val contact = dao.contact(address.name) ?: return true
        if (contact.identityKey.isEmpty()) return true
        if (!contact.identityKey.contentEquals(identityKey.serialize())) return false
        return direction == Direction.RECEIVING || contact.trust != TrustState.KeyChanged.name
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        dao.contact(address.name)?.identityKey?.takeIf { it.isNotEmpty() }?.let(::IdentityKey)

    // SessionStore

    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        dao.session(address.name)?.let(::SessionRecord) ?: SessionRecord()

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { dao.session(it.name)?.let(::SessionRecord) ?: throw NoSessionException("no session") }

    // One device per account: there are never sub-devices.
    override fun getSubDeviceSessions(name: String): List<Int> = emptyList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) =
        dao.putSession(SignalSessionEntity(address.name, record.serialize()))

    // A usable session is one we can send on. (Don't compare against
    // CiphertextMessage.CURRENT_VERSION: it is 3, while PQXDH sessions are 4.)
    override fun containsSession(address: SignalProtocolAddress): Boolean =
        dao.session(address.name)?.let(::SessionRecord)?.hasSenderChain() == true

    override fun deleteSession(address: SignalProtocolAddress) = dao.deleteSession(address.name)

    override fun deleteAllSessions(name: String) = dao.deleteSession(name)

    // PreKeyStore

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        dao.preKey(preKeyId)?.let(::PreKeyRecord) ?: throw InvalidKeyIdException("no prekey $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) =
        dao.putPreKey(SignalPreKeyEntity(preKeyId, record.serialize(), clock()))

    override fun containsPreKey(preKeyId: Int): Boolean = dao.preKey(preKeyId) != null

    override fun removePreKey(preKeyId: Int) = dao.deletePreKey(preKeyId)

    // SignedPreKeyStore

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        dao.signedPreKey(signedPreKeyId)?.let(::SignedPreKeyRecord)
            ?: throw InvalidKeyIdException("no signed prekey $signedPreKeyId")

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = dao.signedPreKeys().map {
        SignedPreKeyRecord(it.record)
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) =
        dao.putSignedPreKey(SignalSignedPreKeyEntity(signedPreKeyId, record.serialize(), clock()))

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = dao.signedPreKey(signedPreKeyId) != null

    override fun removeSignedPreKey(signedPreKeyId: Int) = dao.deleteSignedPreKey(signedPreKeyId)

    // KyberPreKeyStore

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        dao.kyberPreKey(kyberPreKeyId)?.let { KyberPreKeyRecord(it.record) }
            ?: throw InvalidKeyIdException("no kyber prekey $kyberPreKeyId")

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = dao.kyberPreKeys().map { KyberPreKeyRecord(it.record) }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) =
        storeKyberPreKey(record, lastResort = false)

    fun storeKyberPreKey(record: KyberPreKeyRecord, lastResort: Boolean) =
        dao.putKyberPreKey(SignalKyberPreKeyEntity(record.id, record.serialize(), lastResort, clock()))

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = dao.kyberPreKey(kyberPreKeyId) != null

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        val row = dao.kyberPreKey(kyberPreKeyId) ?: return
        if (!row.lastResort) {
            dao.deleteKyberPreKey(kyberPreKeyId)
            return
        }
        // A last-resort key stays, so remember which base keys used it: the
        // same PreKey message replayed under a new envelope ID is rejected.
        val inserted = dao.insertUsedBaseKey(KyberUsedBaseKeyEntity(kyberPreKeyId, signedPreKeyId, baseKey.serialize()))
        if (inserted == -1L) throw ReusedBaseKeyException("base key already used with this last-resort key")
    }

    /** Key IDs are allocated monotonically and never reused. */
    fun allocateKeyIds(kind: KeyKind, count: Int): IntRange {
        val next = dao.setting(kind.counter)?.toIntOrNull() ?: 1
        val end = next + count - 1
        require(end <= MAX_KEY_ID) { "key id space exhausted" }
        dao.putSetting(SettingEntity(kind.counter, (end + 1).toString()))
        return next..end
    }

    /** True if [id] was ever allocated for [kind] (so a missing key was consumed or retired). */
    fun wasAllocated(kind: KeyKind, id: Int): Boolean = id < (dao.setting(kind.counter)?.toIntOrNull() ?: 1)

    enum class KeyKind(val counter: String) {
        OneTime("e2e.nextPreKeyId"),
        Signed("e2e.nextSignedPreKeyId"),
        Kyber("e2e.nextKyberPreKeyId"),
    }

    companion object {
        const val UNKNOWN_CONTACT = "Unknown contact"
        const val DEVICE_ID = 1
        private const val REGISTRATION_ID = "e2e.registrationId"

        // libsignal clients use 24-bit prekey IDs; the server enforces the same.
        const val MAX_KEY_ID = (1 shl 24) - 1
    }
}
