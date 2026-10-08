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
    /** Our username (e.g. "sam.42"), if claimed. */
    val username: String? = null,
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

    @Query("UPDATE account SET username = :username WHERE id = 0")
    suspend fun setUsername(username: String?)

    @Query("UPDATE account SET displayName = :name WHERE id = 0")
    suspend fun setDisplayName(name: String)

    @Query("UPDATE account SET avatarPath = :path WHERE id = 0")
    suspend fun setAvatarPath(path: String?)
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
        SignalSessionEntity::class,
        SignalPreKeyEntity::class,
        SignalSignedPreKeyEntity::class,
        SignalKyberPreKeyEntity::class,
        KyberUsedBaseKeyEntity::class,
        SeenEnvelopeEntity::class,
        SentEnvelopeEntity::class,
        PendingResetEntity::class,
        ParkedRecipientEntity::class,
        HeldEnvelopeEntity::class,
        DecryptAttemptEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        SenderKeyEntity::class,
        GroupKeyShareEntity::class,
        GroupDistributionEntity::class,
        HeldGroupEnvelopeEntity::class,
        GroupSendEntity::class,
        AttachmentEntity::class,
        ReactionEntity::class,
        ConversationSettingEntity::class,
        StatusEntity::class,
        CallEntity::class,
    ],
    version = 7,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
    ],
)
abstract class WhisprDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun settingDao(): SettingDao
    abstract fun messagingTransactions(): MessagingTransactions
    abstract fun cryptoDao(): CryptoDao
    abstract fun groupDao(): GroupDao
    abstract fun groupQueries(): GroupQueries
    abstract fun statusDao(): StatusDao
    abstract fun callDao(): CallDao

    companion object {
        const val NAME = "whispr.db"
    }
}
