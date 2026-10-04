package dev.whispr.android.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import dev.whispr.android.di.ApplicationScope
import dev.whispr.data.messaging.MessagingEngine
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Receives content-free wake-ups. The push says nothing about the message;
 * the app connects, fetches envelopes over the WebSocket, stores them, and
 * MessageNotifier posts a local notification.
 */
@AndroidEntryPoint
class WhisprMessagingService : FirebaseMessagingService() {
    @Inject lateinit var engine: MessagingEngine

    @Inject lateinit var push: PushManager

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["t"] == "wake") engine.wake()
    }

    override fun onNewToken(token: String) {
        scope.launch { push.register(token) }
    }
}
