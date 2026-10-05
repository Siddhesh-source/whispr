package dev.whispr.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.whispr.data.crypto.AndroidKeystoreKeyWrapper
import dev.whispr.data.crypto.SecretFileStore
import dev.whispr.data.crypto.SignalStore
import dev.whispr.data.db.AccountEntity
import dev.whispr.data.db.DatabaseKey
import dev.whispr.data.db.LazyKeyOpenHelperFactory
import dev.whispr.data.db.MessageEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.identity.LibsignalIdentityRepository
import java.io.File
import java.security.GeneralSecurityException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

@RunWith(AndroidJUnit4::class)
class KeystoreAndDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.noBackupFilesDir, "test-" + UUID.randomUUID()).apply { mkdirs() }
    }

    @Test
    fun keystoreWrapRoundTripAndTamperDetection() {
        val wrapper = AndroidKeystoreKeyWrapper("whispr.test." + UUID.randomUUID())
        val secret = ByteArray(32) { it.toByte() }
        val blob = wrapper.wrap(secret, "aad".toByteArray())
        assertArrayEquals(secret, wrapper.unwrap(blob, "aad".toByteArray()))

        expectFailure { wrapper.unwrap(blob, "other".toByteArray()) }
        val tampered = blob.clone().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }
        expectFailure { wrapper.unwrap(tampered, "aad".toByteArray()) }
    }

    @Test
    fun identityPersistsWithKeystoreWrapper() = runTest {
        val alias = "whispr.test." + UUID.randomUUID()
        val first = LibsignalIdentityRepository(SecretFileStore(dir, AndroidKeystoreKeyWrapper(alias)), Dispatchers.IO)
        val key = first.getOrCreatePublicKey()
        val second = LibsignalIdentityRepository(SecretFileStore(dir, AndroidKeystoreKeyWrapper(alias)), Dispatchers.IO)
        assertArrayEquals(key, second.getOrCreatePublicKey())
    }

    @Test
    fun databaseFileIsEncrypted() = runTest {
        val key = DatabaseKey(SecretFileStore(dir, AndroidKeystoreKeyWrapper("whispr.test." + UUID.randomUUID())))
        val name = "enc-" + UUID.randomUUID() + ".db"
        // Same factory as production (DataModule).
        val db = Room.databaseBuilder(context, WhisprDatabase::class.java, name)
            .openHelperFactory(LazyKeyOpenHelperFactory { key.getOrCreate() })
            .build()
        db.accountDao().upsert(AccountEntity(userId = null, displayName = "Plaintext Marker", avatarPath = null))
        assertEquals("Plaintext Marker", db.accountDao().get()?.displayName)
        db.close()

        val bytes = context.getDatabasePath(name).readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse("SQLite header found: file is not encrypted", text.startsWith("SQLite format 3"))
        assertFalse("plaintext leaked into database file", text.contains("Plaintext Marker"))
        context.deleteDatabase(name)
    }

    @Test
    fun keyMaterialAndMessagesAreEncryptedAtRest() = runTest {
        val key = DatabaseKey(SecretFileStore(dir, AndroidKeystoreKeyWrapper("whispr.test." + UUID.randomUUID())))
        val name = "keys-" + UUID.randomUUID() + ".db"
        val db = Room.databaseBuilder(context, WhisprDatabase::class.java, name)
            .openHelperFactory(LazyKeyOpenHelperFactory { key.getOrCreate() })
            .build()
        val store = SignalStore(db.cryptoDao())
        val identity = IdentityKeyPair.generate()
        val preKey = ECKeyPair.generate()
        val signed = ECKeyPair.generate()
        val marker = "whispr-at-rest-marker-" + UUID.randomUUID()
        val groupName = "whispr-group-name-" + UUID.randomUUID()
        val mediaKey = ByteArray(32) { (it * 7 + 3).toByte() }
        db.runInTransaction {
            store.storePreKey(1, PreKeyRecord(1, preKey))
            store.storeSignedPreKey(
                1,
                SignedPreKeyRecord(1, 0, signed, identity.privateKey.calculateSignature(signed.publicKey.serialize())),
            )
            db.cryptoDao().insertMessage(
                MessageEntity(
                    messageId = "m",
                    conversationId = "c",
                    peerId = "p",
                    outgoing = false,
                    body = marker,
                    timestamp = 0,
                    status = null,
                ),
            )
            db.groupDao().putGroup(
                dev.whispr.data.db.GroupEntity(
                    groupId = "g",
                    name = groupName,
                    avatar = null,
                    revision = 1,
                    revisionAuthor = "p",
                    status = "Active",
                    myDistributionId = UUID.randomUUID().toString(),
                    createdAt = 0,
                ),
            )
            db.groupDao().putAttachment(
                dev.whispr.data.db.AttachmentEntity(
                    messageRow = 1, remoteId = "r", key = mediaKey, digest = ByteArray(32), size = 1,
                    contentType = "image/jpeg", kind = "Image", fileName = null, width = null, height = null,
                    durationMs = null, thumbnail = null, blobPath = null, state = "Remote",
                ),
            )
        }
        db.close()

        // The main file plus any journal or WAL that outlived the close.
        val files = listOf("", "-wal", "-journal").map {
            File(context.getDatabasePath(name).path + it)
        }.filter { it.exists() }
        val bytes = files.fold(ByteArray(0)) { all, f -> all + f.readBytes() }
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse("SQLite header found: file is not encrypted", text.startsWith("SQLite format 3"))
        assertFalse("message text leaked", text.contains(marker))
        assertFalse("group name leaked", text.contains(groupName))
        assertFalse("attachment key leaked", text.contains(String(mediaKey, Charsets.ISO_8859_1)))
        for ((what, secret) in listOf("one-time prekey" to preKey, "signed prekey" to signed)) {
            assertFalse(
                "$what private key leaked",
                text.contains(String(secret.privateKey.serialize(), Charsets.ISO_8859_1)),
            )
        }
        context.deleteDatabase(name)
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("expected GeneralSecurityException")
        } catch (_: GeneralSecurityException) {
        }
    }
}
