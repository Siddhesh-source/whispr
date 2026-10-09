package dev.whispr.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import dev.whispr.android.backup.BackupController
import dev.whispr.android.calls.CallSystem
import dev.whispr.android.di.ApplicationScope
import dev.whispr.android.notifications.MessageNotifier
import dev.whispr.android.push.PushManager
import dev.whispr.android.session.SessionKeeper
import dev.whispr.android.update.Updater
import dev.whispr.data.backup.BackupPaths
import dev.whispr.data.backup.ChatBackup
import dev.whispr.data.media.MediaService
import dev.whispr.data.messaging.MessagingEngine
import dev.whispr.data.status.RoomStatusRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class WhisprApp : Application() {
    // Lazy: building these unwraps the database key via the Keystore, which
    // must not happen on the main thread during startup.
    @Inject lateinit var sessionKeeper: Lazy<SessionKeeper>

    @Inject lateinit var engine: Lazy<MessagingEngine>

    @Inject lateinit var media: Lazy<MediaService>

    @Inject lateinit var statuses: Lazy<RoomStatusRepository>

    @Inject lateinit var calls: Lazy<CallSystem>

    @Inject lateinit var updater: Lazy<Updater>

    @Inject lateinit var notifier: Lazy<MessageNotifier>

    @Inject lateinit var push: Lazy<PushManager>

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    @Inject lateinit var backups: Lazy<BackupController>

    override fun onCreate() {
        // A restored backup replaces the database before anything opens it.
        ChatBackup.applyStaged(BackupPaths.of(this))
        super.onCreate()
        scope.launch {
            sessionKeeper.get().start()
            engine.get().start()
            // Uploads cut off by the process dying; also clears decrypted exports.
            media.get().resumePending()
            statuses.get().resumePending()
        }
        scope.launch { notifier.get().run() }
        scope.launch { push.get().start() }
        // Listen for calls from the start, so an offer that arrives with a push wake-up rings.
        calls.get().start(scope)
        scope.launch { updater.get().checkIfDue() }
        scope.launch { backups.get().backUpIfDue() }

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
