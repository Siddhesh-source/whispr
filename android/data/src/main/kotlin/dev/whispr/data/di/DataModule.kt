package dev.whispr.data.di

import android.content.Context
import android.net.ConnectivityManager
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.whispr.data.account.AvatarStore
import dev.whispr.data.account.RoomAccountRepository
import dev.whispr.data.account.asImporter
import dev.whispr.data.auth.DeviceWipe
import dev.whispr.data.auth.SessionAuthRepository
import dev.whispr.data.auth.TokenSource
import dev.whispr.data.backup.BackupPaths
import dev.whispr.data.backup.ChatBackup
import dev.whispr.data.calls.RoomCallLogRepository
import dev.whispr.data.calls.RoomCallSignalingRepository
import dev.whispr.data.connectivity.AndroidConnectivityRepository
import dev.whispr.data.contacts.RoomContactsRepository
import dev.whispr.data.crypto.AndroidKeystoreKeyWrapper
import dev.whispr.data.crypto.PreKeyMaintainer
import dev.whispr.data.crypto.SecretFileStore
import dev.whispr.data.crypto.SessionCrypto
import dev.whispr.data.crypto.SignalStore
import dev.whispr.data.db.AccountDao
import dev.whispr.data.db.DatabaseKey
import dev.whispr.data.db.LazyKeyOpenHelperFactory
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.identity.LibsignalIdentityRepository
import dev.whispr.data.media.AndroidMediaPreparer
import dev.whispr.data.media.MediaFiles
import dev.whispr.data.media.MediaPreparer
import dev.whispr.data.media.MediaService
import dev.whispr.data.messaging.ContactLink
import dev.whispr.data.messaging.MessagingEngine
import dev.whispr.data.messaging.RoomGroupsRepository
import dev.whispr.data.messaging.RoomMessagingRepository
import dev.whispr.data.messaging.RoomSettingsRepository
import dev.whispr.data.network.AuthApi
import dev.whispr.data.network.KeysApi
import dev.whispr.data.network.MediaApi
import dev.whispr.data.network.ServerConfig
import dev.whispr.data.network.TlsPolicy
import dev.whispr.data.network.WhisprApi
import dev.whispr.data.profile.RoomProfileRepository
import dev.whispr.data.status.RoomStatusRepository
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.AuthRepository
import dev.whispr.domain.repository.CallLogRepository
import dev.whispr.domain.repository.CallSignalingRepository
import dev.whispr.domain.repository.ConnectivityRepository
import dev.whispr.domain.repository.ContactsRepository
import dev.whispr.domain.repository.EncryptionRepository
import dev.whispr.domain.repository.GroupsRepository
import dev.whispr.domain.repository.IdentityRepository
import dev.whispr.domain.repository.MessagingRepository
import dev.whispr.domain.repository.ProfileRepository
import dev.whispr.domain.repository.SettingsRepository
import dev.whispr.domain.repository.StatusRepository
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IdentityStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DatabaseKeyStore

/** True in debug builds: contact codes may name a loopback server over plain HTTP. Provided by the app. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InsecureLoopbackAllowed

/** The app module provides [ServerConfig]; everything else is wired here. */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    // Separate Keystore keys per purpose: compromise or invalidation of one
    // does not affect the other.
    @Provides @Singleton @IdentityStore
    fun identityStore(@ApplicationContext context: Context) =
        SecretFileStore(secretsDir(context), AndroidKeystoreKeyWrapper(IDENTITY_ALIAS))

    @Provides @Singleton @DatabaseKeyStore
    fun databaseKeyStore(@ApplicationContext context: Context) =
        SecretFileStore(secretsDir(context), AndroidKeystoreKeyWrapper(DATABASE_ALIAS))

    @Provides @Singleton
    fun libsignalIdentity(@IdentityStore store: SecretFileStore) = LibsignalIdentityRepository(store, Dispatchers.IO)

    @Provides
    fun identityRepository(impl: LibsignalIdentityRepository): IdentityRepository = impl

    @Provides @Singleton
    fun database(@ApplicationContext context: Context, @DatabaseKeyStore store: SecretFileStore): WhisprDatabase =
        Room.databaseBuilder(context, WhisprDatabase::class.java, WhisprDatabase.NAME)
            .openHelperFactory(LazyKeyOpenHelperFactory { DatabaseKey(store).getOrCreate() })
            .build()

    @Provides
    fun accountDao(db: WhisprDatabase): AccountDao = db.accountDao()

    @Provides @Singleton
    fun accountRepository(@ApplicationContext context: Context, dao: AccountDao): AccountRepository =
        RoomAccountRepository(
            dao,
            AvatarStore(context.contentResolver, File(context.noBackupFilesDir, "avatar")).asImporter(),
            Dispatchers.IO,
        )

    @Provides @Singleton
    fun okHttp(config: ServerConfig): OkHttpClient = TlsPolicy.apply(OkHttpClient.Builder(), config)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun authApi(client: OkHttpClient, config: ServerConfig) = AuthApi(client, config)

    @Provides @Singleton
    fun sessionAuthRepository(
        @ApplicationContext context: Context,
        api: AuthApi,
        identity: IdentityRepository,
        accounts: AccountRepository,
        db: WhisprDatabase,
    ): SessionAuthRepository {
        val wipe = DeviceWipe(context, db, listOf(IDENTITY_ALIAS, DATABASE_ALIAS))
        return SessionAuthRepository(api, identity, accounts, wipe = { withContext(Dispatchers.IO) { wipe.wipe() } })
    }

    @Provides
    fun authRepository(impl: SessionAuthRepository): AuthRepository = impl

    @Provides
    fun tokenSource(impl: SessionAuthRepository): TokenSource = impl

    @Provides @Singleton
    fun connectivity(@ApplicationContext context: Context): ConnectivityRepository =
        AndroidConnectivityRepository(context.getSystemService(ConnectivityManager::class.java))

    @Provides @Singleton
    fun whisprApi(client: OkHttpClient, config: ServerConfig, tokens: TokenSource) = WhisprApi(client, config, tokens)

    @Provides @Singleton
    fun keysApi(client: OkHttpClient, config: ServerConfig, tokens: TokenSource) = KeysApi(client, config, tokens)

    /** All libsignal work runs on this one thread, so ratchet steps never interleave. */
    @Provides @Singleton
    fun signalStore(db: WhisprDatabase) = SignalStore(db.cryptoDao())

    @Provides @Singleton
    fun sessionCrypto(
        db: WhisprDatabase,
        store: SignalStore,
        identity: LibsignalIdentityRepository,
        keys: KeysApi,
        accounts: AccountRepository,
    ) = SessionCrypto(db, store, identity, { keys.bundle(it) }, cryptoDispatcher) {
        accounts.getAccount()?.userId?.value ?: error("not registered")
    }

    @Provides @Singleton
    fun preKeyMaintainer(db: WhisprDatabase, store: SignalStore, identity: LibsignalIdentityRepository, keys: KeysApi) =
        PreKeyMaintainer(db, store, identity, keys, cryptoDispatcher)

    @Provides
    fun encryption(maintainer: PreKeyMaintainer): EncryptionRepository = maintainer

    @Provides @Singleton
    fun messagingEngine(
        db: WhisprDatabase,
        client: OkHttpClient,
        api: WhisprApi,
        tokens: TokenSource,
        accounts: AccountRepository,
        connectivity: ConnectivityRepository,
        crypto: SessionCrypto,
        maintainer: PreKeyMaintainer,
    ) = MessagingEngine(
        db,
        client,
        api,
        tokens,
        accounts,
        connectivity,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        crypto,
        maintainer,
    )

    @Provides @Singleton
    fun settingsRepository(db: WhisprDatabase): SettingsRepository = RoomSettingsRepository(db.settingDao())

    @Provides @Singleton
    fun mediaApi(client: OkHttpClient, config: ServerConfig, tokens: TokenSource) = MediaApi(client, config, tokens)

    @Provides @Singleton
    fun mediaPreparer(@ApplicationContext context: Context): MediaPreparer =
        AndroidMediaPreparer(context.contentResolver)

    /** Encrypted blobs in no-backup storage; decrypted "open with" copies in the cache, cleared on start. */
    @Provides @Singleton
    fun mediaService(
        @ApplicationContext context: Context,
        db: WhisprDatabase,
        crypto: SessionCrypto,
        engine: MessagingEngine,
        api: MediaApi,
        preparer: MediaPreparer,
        accounts: AccountRepository,
    ) = MediaService(
        db,
        crypto,
        engine.groups,
        api,
        preparer,
        MediaFiles(File(context.noBackupFilesDir, "media"), File(context.cacheDir, "open")),
        { accounts.getAccount()?.userId },
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    @Provides @Singleton
    fun messagingRepository(
        db: WhisprDatabase,
        engine: MessagingEngine,
        accounts: AccountRepository,
        settings: SettingsRepository,
        media: MediaService,
    ): MessagingRepository = RoomMessagingRepository(db, engine, accounts, settings, media)

    @Provides @Singleton
    fun groupsRepository(
        db: WhisprDatabase,
        engine: MessagingEngine,
        accounts: AccountRepository,
        identity: IdentityRepository,
        preparer: MediaPreparer,
    ): GroupsRepository = RoomGroupsRepository(db, engine, accounts, identity, preparer)

    @Provides @Singleton
    fun contactsRepository(
        db: WhisprDatabase,
        api: WhisprApi,
        accounts: AccountRepository,
        identity: IdentityRepository,
        @InsecureLoopbackAllowed allowInsecureLoopback: Boolean,
        engine: MessagingEngine,
    ): ContactsRepository = RoomContactsRepository(
        db,
        api,
        accounts,
        identity,
        allowInsecureLoopback,
        onKeyAcknowledged = engine::onKeyChangeAcknowledged,
        transaction = { block -> engine.transaction(block) },
    )

    @Provides @Singleton
    fun profileRepository(
        @ApplicationContext context: Context,
        db: WhisprDatabase,
        api: WhisprApi,
        engine: MessagingEngine,
    ): ProfileRepository = RoomProfileRepository(
        db.accountDao(),
        api,
        AvatarStore(context.contentResolver, File(context.noBackupFilesDir, "avatar")).asImporter(),
        Dispatchers.IO,
        onChanged = { engine.transaction { ContactLink.broadcastProfile(db.cryptoDao(), System::currentTimeMillis) } },
    )

    @Provides @Singleton
    fun chatBackup(
        @ApplicationContext context: Context,
        db: WhisprDatabase,
        @IdentityStore identity: SecretFileStore,
        @DatabaseKeyStore databaseKey: SecretFileStore,
        engine: MessagingEngine,
    ) = ChatBackup(db, identity, databaseKey, BackupPaths.of(context), quiet = { block -> engine.transaction(block) })

    @Provides @Singleton
    fun roomStatusRepository(
        db: WhisprDatabase,
        engine: MessagingEngine,
        accounts: AccountRepository,
        media: MediaService,
    ) = RoomStatusRepository(db, engine, accounts, media, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    @Provides
    fun statusRepository(impl: RoomStatusRepository): StatusRepository = impl

    @Provides @Singleton
    fun callSignaling(
        db: WhisprDatabase,
        engine: MessagingEngine,
        api: WhisprApi,
        accounts: AccountRepository,
    ): CallSignalingRepository = RoomCallSignalingRepository(db, engine, api, accounts)

    @Provides @Singleton
    fun callLog(db: WhisprDatabase): CallLogRepository = RoomCallLogRepository(db)

    private fun secretsDir(context: Context) = File(context.noBackupFilesDir, "secrets")

    private const val TIMEOUT_SECONDS = 15L
    private const val IDENTITY_ALIAS = "whispr.identity.wrap.v1"
    private const val DATABASE_ALIAS = "whispr.database.wrap.v1"

    private val cryptoDispatcher = Executors.newSingleThreadExecutor {
        Thread(it, "whispr-crypto")
    }.asCoroutineDispatcher()
}
