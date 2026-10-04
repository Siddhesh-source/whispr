package dev.whispr.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.whispr.android.BuildConfig
import dev.whispr.data.di.InsecureLoopbackAllowed
import dev.whispr.data.network.ServerConfig
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
    fun serverConfig() = ServerConfig(BuildConfig.SERVER_URL)

    @Provides @InsecureLoopbackAllowed
    fun insecureLoopbackAllowed(): Boolean = BuildConfig.DEBUG

    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
