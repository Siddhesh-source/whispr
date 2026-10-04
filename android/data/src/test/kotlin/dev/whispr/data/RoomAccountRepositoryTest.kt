package dev.whispr.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import dev.whispr.data.account.AvatarImporter
import dev.whispr.data.account.RoomAccountRepository
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.domain.model.Account
import dev.whispr.domain.model.AvatarSource
import dev.whispr.domain.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAO and repository behaviour on an in-memory (unencrypted) Room database.
 * SQLCipher's native library only exists on device; EncryptedDatabaseTest
 * (androidTest) covers encryption.
 */
@RunWith(RobolectricTestRunner::class)
class RoomAccountRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        WhisprDatabase::class.java,
    )
        .allowMainThreadQueries()
        .build()
    private val imported = mutableListOf<String>()
    private val repo = RoomAccountRepository(
        db.accountDao(),
        AvatarImporter { source ->
            imported += source.uri
            "/private/avatar.jpg"
        },
        Dispatchers.Unconfined,
    )

    @After
    fun tearDown() = db.close()

    @Test
    fun profileLifecycle() = runTest {
        repo.observeAccount().test {
            assertEquals(null, awaitItem())

            repo.saveProfile("Ada", AvatarSource("content://picked"))
            assertEquals(Account(null, "Ada", "/private/avatar.jpg"), awaitItem())

            repo.markRegistered(UserId("u-1"))
            assertEquals(Account(UserId("u-1"), "Ada", "/private/avatar.jpg"), awaitItem())

            // Re-saving without a new avatar keeps the existing one and the user ID.
            repo.saveProfile("Ada L", null)
            assertEquals(Account(UserId("u-1"), "Ada L", "/private/avatar.jpg"), awaitItem())
        }
        assertEquals(listOf("content://picked"), imported)
    }

    @Test(expected = IllegalStateException::class)
    fun markRegisteredWithoutProfileFails() = runTest {
        repo.markRegistered(UserId("u-1"))
    }
}
