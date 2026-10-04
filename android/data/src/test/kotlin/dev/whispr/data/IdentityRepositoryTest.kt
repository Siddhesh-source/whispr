package dev.whispr.data

import dev.whispr.data.crypto.SecretFileStore
import dev.whispr.data.identity.LibsignalIdentityRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.signal.libsignal.protocol.IdentityKey

/** Uses the real libsignal (desktop natives from libsignal-client). */
class IdentityRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val wrapper = SoftwareKeyWrapper()

    private fun repo(dir: File = tmp.root) =
        LibsignalIdentityRepository(SecretFileStore(dir, wrapper), Dispatchers.Unconfined)

    @Test
    fun createsOnceAndPersistsAcrossInstances() = runTest {
        val first = repo()
        assertFalse(first.hasIdentity())
        val key = first.getOrCreatePublicKey()
        assertEquals(33, key.size)
        assertTrue(first.hasIdentity())

        // A new instance (i.e. after an app restart) loads the same identity.
        assertArrayEquals(key, repo().getOrCreatePublicKey())
    }

    @Test
    fun storedFileIsNotPlaintextKeyMaterial() = runTest {
        repo().getOrCreatePublicKey()
        val blob = File(tmp.root, "identity.v1.bin").readBytes()
        val unwrapped = wrapper.unwrap(blob, "whispr-identity-v1".toByteArray())
        assertNotEquals(hex(unwrapped), hex(blob))
        assertFalse("ciphertext must not contain the serialized key pair", hex(blob).contains(hex(unwrapped)))
    }

    @Test
    fun signaturesVerifyWithLibsignal() = runTest {
        val r = repo()
        val pub = IdentityKey(r.getOrCreatePublicKey())
        val msg = "hello".toByteArray()
        val sig = r.sign(msg)
        assertTrue(pub.publicKey.verifySignature(msg, sig))
        assertFalse(pub.publicKey.verifySignature("other".toByteArray(), sig))
    }

    @Test
    fun tamperedIdentityFileFailsInsteadOfSilentlyReplacingTheKey() = runTest {
        repo().getOrCreatePublicKey()
        val file = File(tmp.root, "identity.v1.bin")
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        try {
            repo().getOrCreatePublicKey()
            fail("expected tampered blob to be rejected")
        } catch (_: java.security.GeneralSecurityException) {
        }
    }

    @Test
    fun blobBoundToPurposeCannotBeReusedElsewhere() {
        val store = SecretFileStore(tmp.root, wrapper)
        store.write("x", byteArrayOf(1, 2, 3), "purpose-a".toByteArray())
        try {
            store.read("x", "purpose-b".toByteArray())
            fail("AAD mismatch must fail")
        } catch (_: java.security.GeneralSecurityException) {
        }
    }
}
