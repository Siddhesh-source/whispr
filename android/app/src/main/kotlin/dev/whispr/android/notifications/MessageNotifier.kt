package dev.whispr.android.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.whispr.android.MainActivity
import dev.whispr.android.R
import dev.whispr.data.messaging.MessagingEngine
import dev.whispr.domain.model.ConversationId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The conversation currently on screen, so we don't notify about it. */
@Singleton
class ActiveConversation @Inject constructor() {
    val current = MutableStateFlow<ConversationId?>(null)
}

/**
 * Posts local notifications for incoming messages. Content is generated on
 * the device from the local database; the push itself never carries it.
 * On the lock screen only "New message" is shown (VISIBILITY_PRIVATE).
 */
@Singleton
class MessageNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: MessagingEngine,
    private val active: ActiveConversation,
) {
    suspend fun run(): Unit = coroutineScope {
        createChannel()
        // A deleted or disappeared message must not live on in the notification shade.
        launch {
            engine.removed.collect { NotificationManagerCompat.from(context).cancel(it.value.hashCode()) }
        }
        engine.incoming.collect { msg ->
            if (msg.conversationId != active.current.value) notify(msg.conversationId, msg.senderName, msg.text)
        }
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_HIGH,
            )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notify(conversation: ConversationId, sender: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context,
            conversation.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_new_message))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(sender)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        @Suppress("MissingPermission") // checked above
        NotificationManagerCompat.from(context).notify(conversation.value.hashCode(), notification)
    }

    private companion object {
        const val CHANNEL = "messages"
    }
}
