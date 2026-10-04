package dev.whispr.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import dev.whispr.android.di.ApplicationScope
import dev.whispr.android.notifications.MessageNotifier
import dev.whispr.android.push.PushManager
import dev.whispr.android.session.SessionKeeper
import dev.whispr.data.messaging.MessagingEngine
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class WhisprApp : Application() {
    // Lazy: building these unwraps the database key via the Keystore, which
    // must not happen on the main thread during startup.
    @Inject lateinit var sessionKeeper: Lazy<SessionKeeper>

    @Inject lateinit var engine: Lazy<MessagingEngine>

    @Inject lateinit var notifier: Lazy<MessageNotifier>

    @Inject lateinit var push: Lazy<PushManager>

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            sessionKeeper.get().start()
            engine.get().start()
        }
        scope.launch { notifier.get().run() }
        scope.launch { push.get().start() }

        // Keep the WebSocket open while the app is visible; in the background
        // the engine disconnects once the outbox is empty and relies on push.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = setForeground(true)

                override fun onStop(owner: LifecycleOwner) = setForeground(false)
            },
        )
    }

    private fun setForeground(value: Boolean) {
        scope.launch { engine.get().setForeground(value) }
    }
}
