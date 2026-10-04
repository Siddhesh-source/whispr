package dev.whispr.android.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.whispr.android.BuildConfig
import dev.whispr.data.network.ApiResult
import dev.whispr.data.network.WhisprApi
import dev.whispr.domain.repository.AccountRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/**
 * FCM wake-ups. Firebase is initialized manually from build config values
 * (no google-services plugin), so builds without a Firebase project simply
 * have push disabled. The push token is registered only after the account
 * exists on our server; no Firebase analytics or delivery telemetry is used.
 */
@Singleton
class PushManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: WhisprApi,
    private val accounts: AccountRepository,
) {
    val enabled: Boolean
        get() = BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()

    /** Initializes Firebase and registers the token once the account is registered. */
    suspend fun start() {
        if (!enabled) {
            Log.i(TAG, "push disabled: no Firebase configuration in this build")
            return
        }
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build(),
            )
        }
        accounts.observeAccount().filter { it?.isRegistered == true }.first()
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true
        runCatching { messaging.token.await() }.getOrNull()?.let { register(it) }
    }

    /** Sends the device token to our server (also called when FCM rotates it). */
    suspend fun register(token: String) {
        if (accounts.getAccount()?.isRegistered != true) return
        val result = api.putPushToken(token)
        if (result !is ApiResult.Success) Log.w(TAG, "push token registration failed; will retry on next start")
    }

    private companion object {
        const val TAG = "PushManager"
    }
}
