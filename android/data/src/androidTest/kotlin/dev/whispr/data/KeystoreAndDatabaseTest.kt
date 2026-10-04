package dev.whispr.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.whispr.data.crypto.AndroidKeystoreKeyWrapper
import dev.whispr.data.crypto.SecretFileStore
import dev.whispr.data.db.AccountEntity
import dev.whispr.data.db.DatabaseKey
import dev.whispr.data.db.LazyKeyOpenHelperFactory
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

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("expected GeneralSecurityException")
        } catch (_: GeneralSecurityException) {
        }
    }
}
