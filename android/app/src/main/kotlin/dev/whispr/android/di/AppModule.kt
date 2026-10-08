package dev.whispr.android.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.whispr.android.BuildConfig
import dev.whispr.android.calls.CallManager
import dev.whispr.android.calls.WebRtcEnvironment
import dev.whispr.data.di.InsecureLoopbackAllowed
import dev.whispr.data.network.ServerConfig
import dev.whispr.domain.repository.CallLogRepository
import dev.whispr.domain.repository.CallSignalingRepository
import dev.whispr.domain.repository.ContactsRepository
import dev.whispr.domain.repository.SettingsRepository
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun serverConfig(): ServerConfig {
        // The release build script already refuses a non-HTTPS URL; this guards hand-edited builds.
        check(BuildConfig.DEBUG || BuildConfig.SERVER_URL.startsWith("https://")) { "release builds need HTTPS" }
        return ServerConfig(BuildConfig.SERVER_URL, BuildConfig.CERT_PINS.split(',').filter { it.isNotBlank() })
    }

    @Provides @InsecureLoopbackAllowed
    fun insecureLoopbackAllowed(): Boolean = BuildConfig.DEBUG

    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun webRtc(@ApplicationContext context: Context) = WebRtcEnvironment(context)

    @Provides @Singleton
    fun callManager(
        signaling: CallSignalingRepository,
        log: CallLogRepository,
        contacts: ContactsRepository,
        settings: SettingsRepository,
        webRtc: WebRtcEnvironment,
        @ApplicationScope scope: CoroutineScope,
    ) = CallManager(signaling, log, contacts, settings, webRtc, scope)
}
