package dev.whispr.android

import android.app.Application
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import dev.whispr.android.di.ApplicationScope
import dev.whispr.android.session.SessionKeeper
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class WhisprApp : Application() {
    // Lazy: building SessionKeeper unwraps the database key via the Keystore,
    // which must not happen on the main thread during startup.
    @Inject lateinit var sessionKeeper: Lazy<SessionKeeper>

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        scope.launch { sessionKeeper.get().start() }
    }
}
