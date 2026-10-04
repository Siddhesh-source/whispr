package dev.whispr.data.db

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The local user's profile. A single row (id = 0). */
@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val userId: String?,
    val displayName: String,
    val avatarPath: String?,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM account WHERE id = 0")
    fun observe(): Flow<AccountEntity?>

    @Query("SELECT * FROM account WHERE id = 0")
    suspend fun get(): AccountEntity?

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("UPDATE account SET userId = :userId WHERE id = 0")
    suspend fun setUserId(userId: String): Int
}

/**
 * The on-device database, encrypted with SQLCipher. Its passphrase is a
 * random 256-bit key wrapped by a Keystore key (see [DatabaseKey]).
 */
@Database(
    entities = [
        AccountEntity::class,
        ContactEntity::class,
        MessageEntity::class,
        OutboxEntity::class,
        SettingEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class WhisprDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun settingDao(): SettingDao
    abstract fun messagingTransactions(): MessagingTransactions

    companion object {
        const val NAME = "whispr.db"
    }
}
