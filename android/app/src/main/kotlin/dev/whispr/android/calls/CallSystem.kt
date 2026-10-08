package dev.whispr.android.calls

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.whispr.android.MainActivity
import dev.whispr.android.R
import dev.whispr.domain.model.CallOutcome
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Connects the call state to Android: the ring notification (full screen
 * when allowed), the foreground service that keeps a live call running in
 * the background, sound routing, and a missed-call notification.
 */
@Singleton
class CallSystem @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: CallManager,
) {
    private val audio by lazy { CallAudio(context) }
    private val notifications = NotificationManagerCompat.from(context)
    private var serviceOn = false

    fun start(scope: CoroutineScope) {
        createChannels()
        manager.start()
        scope.launch(Dispatchers.Main) {
            var previous: CallUi? = null
            manager.call.collect { call ->
                audio.apply(call)
                when (call?.phase) {
                    CallPhase.Ringing -> ring(call)
                    CallPhase.Dialing, CallPhase.Connecting, CallPhase.Connected -> {
                        notifications.cancel(RING_ID)
                        if (!serviceOn) serviceOn = CallService.start(context, call.video)
                    }
                    CallPhase.Ended, null -> {
                        notifications.cancel(RING_ID)
                        if (serviceOn) CallService.stop(context)
                        serviceOn = false
                        if (call?.outcome == CallOutcome.Missed && previous?.phase == CallPhase.Ringing) missed(call)
                    }
                }
                previous = call
            }
        }
    }

    private fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val ring = NotificationChannel(
            RING_CHANNEL,
            context.getString(R.string.call_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, VIBRATE_ON, VIBRATE_OFF, VIBRATE_ON)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val ongoing = NotificationChannel(
            ONGOING_CHANNEL,
            context.getString(R.string.call_notification_ongoing_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannels(listOf(ring, ongoing))
    }

    private fun ring(call: CallUi) {
        if (!canNotify()) return
        val caller = Person.Builder().setName(call.peerName.ifEmpty { "Whispr" }).setImportant(true).build()
        val open = activityIntent(MainActivity.ACTION_SHOW_CALL, REQUEST_OPEN)
        val notification = NotificationCompat.Builder(context, RING_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(call.peerName)
            .setContentText(
                context.getString(if (call.video) R.string.call_ringing_video else R.string.call_ringing_voice),
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(open)
            // Full screen over the lock screen where Android allows it; otherwise a heads-up.
            .setFullScreenIntent(open, true)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller,
                    CallActionReceiver.intent(context, CallActionReceiver.ACTION_DECLINE),
                    activityIntent(MainActivity.ACTION_ACCEPT_CALL, REQUEST_ACCEPT),
                ),
            )
            .build()
        @Suppress("MissingPermission") // checked in canNotify
        notifications.notify(RING_ID, notification)
    }

    private fun missed(call: CallUi) {
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(context, RING_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.call_ended_missed))
            .setContentText(call.peerName)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(activityIntent(MainActivity.ACTION_SHOW_CALLS, REQUEST_MISSED))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        @Suppress("MissingPermission")
        notifications.notify(call.callId.hashCode(), notification)
    }

    private fun canNotify() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private fun activityIntent(action: String, request: Int): PendingIntent = PendingIntent.getActivity(
        context,
        request,
        Intent(context, MainActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val RING_CHANNEL = "calls_ring"
        const val ONGOING_CHANNEL = "calls_ongoing"
        const val RING_ID = 0x1CA11
        private const val REQUEST_OPEN = 1
        private const val REQUEST_ACCEPT = 2
        private const val REQUEST_MISSED = 3
        private const val VIBRATE_ON = 800L
        private const val VIBRATE_OFF = 600L
    }
}

/**
 * Keeps a live call (and its microphone and camera) running while the app is
 * in the background. Started only from the foreground: when the user places
 * a call or answers one.
 */
@AndroidEntryPoint
class CallService : Service() {
    @Inject lateinit var manager: CallManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val call = manager.call.value
        if (call == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val person = Person.Builder().setName(call.peerName.ifEmpty { "Whispr" }).build()
        val notification = NotificationCompat.Builder(this, CallSystem.ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(call.peerName)
            .setContentText(getString(R.string.call_notification_ongoing))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_CALL)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    person,
                    CallActionReceiver.intent(this, CallActionReceiver.ACTION_HANG_UP),
                ),
            )
            .build()
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                (if (video) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, ONGOING_ID, notification, types)
        } catch (e: IllegalStateException) {
            // Android refused (e.g. started from the background): the call goes on while the app is open.
            android.util.Log.w("CallService", "foreground refused", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val EXTRA_VIDEO = "video"
        private const val ONGOING_ID = 0x1CA12

        /** False if Android refused (the app is in the background); the call then lives while the app is open. */
        fun start(context: Context, video: Boolean): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallService::class.java).putExtra(EXTRA_VIDEO, video),
            )
            true
        } catch (e: IllegalStateException) {
            android.util.Log.w("CallService", "foreground service refused", e)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}

/** Decline and hang-up from a notification, without opening the app. */
@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {
    @Inject lateinit var manager: CallManager

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DECLINE -> manager.decline()
            ACTION_HANG_UP -> manager.hangup()
        }
    }

    companion object {
        const val ACTION_DECLINE = "dev.whispr.android.call.DECLINE"
        const val ACTION_HANG_UP = "dev.whispr.android.call.HANG_UP"

        fun intent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, CallActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
