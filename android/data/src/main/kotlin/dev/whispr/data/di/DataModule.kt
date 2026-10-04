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
import dev.whispr.data.auth.SessionAuthRepository
import dev.whispr.data.auth.TokenSource
import dev.whispr.data.connectivity.AndroidConnectivityRepository
import dev.whispr.data.crypto.AndroidKeystoreKeyWrapper
import dev.whispr.data.crypto.SecretFileStore
import dev.whispr.data.db.AccountDao
import dev.whispr.data.db.DatabaseKey
import dev.whispr.data.db.LazyKeyOpenHelperFactory
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.identity.LibsignalIdentityRepository
import dev.whispr.data.network.AuthApi
import dev.whispr.data.network.ServerConfig
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.AuthRepository
import dev.whispr.domain.repository.ConnectivityRepository
import dev.whispr.domain.repository.IdentityRepository
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IdentityStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DatabaseKeyStore

/** The app module provides [ServerConfig]; everything else is wired here. */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    // Separate Keystore keys per purpose: compromise or invalidation of one
    // does not affect the other.
    @Provides @Singleton @IdentityStore
    fun identityStore(@ApplicationContext context: Context) =
        SecretFileStore(secretsDir(context), AndroidKeystoreKeyWrapper("whispr.identity.wrap.v1"))

    @Provides @Singleton @DatabaseKeyStore
    fun databaseKeyStore(@ApplicationContext context: Context) =
        SecretFileStore(secretsDir(context), AndroidKeystoreKeyWrapper("whispr.database.wrap.v1"))

    @Provides @Singleton
    fun identityRepository(@IdentityStore store: SecretFileStore): IdentityRepository =
        LibsignalIdentityRepository(store, Dispatchers.IO)

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
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun authApi(client: OkHttpClient, config: ServerConfig) = AuthApi(client, config)

    @Provides @Singleton
    fun sessionAuthRepository(api: AuthApi, identity: IdentityRepository, accounts: AccountRepository) =
        SessionAuthRepository(api, identity, accounts)

    @Provides
    fun authRepository(impl: SessionAuthRepository): AuthRepository = impl

    @Provides
    fun tokenSource(impl: SessionAuthRepository): TokenSource = impl

    @Provides @Singleton
    fun connectivity(@ApplicationContext context: Context): ConnectivityRepository =
        AndroidConnectivityRepository(context.getSystemService(ConnectivityManager::class.java))

    private fun secretsDir(context: Context) = File(context.noBackupFilesDir, "secrets")

    private const val TIMEOUT_SECONDS = 15L
}
