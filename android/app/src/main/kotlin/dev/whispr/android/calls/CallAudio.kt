package dev.whispr.android.calls

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager

/**
 * The phone side of a call's sound: communication mode while the call is
 * live, earpiece or speaker, a ringback tone while we wait for an answer,
 * and the screen off near your ear on voice calls.
 */
class CallAudio(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var ringback: ToneGenerator? = null
    private var proximity: PowerManager.WakeLock? = null
    private var active = false

    fun apply(call: CallUi?) {
        val live = call != null && call.phase in LIVE
        if (live && !active) {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            active = true
        }
        if (live) setSpeaker(call!!.speaker)
        setRingback(call?.phase == CallPhase.Dialing)
        setProximity(live && call?.video == false && call.speaker.not())
        if (!live && active) {
            setSpeaker(false)
            audio.mode = AudioManager.MODE_NORMAL
            active = false
        }
    }

    private fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                audio.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let(audio::setCommunicationDevice)
            } else {
                audio.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = on
        }
    }

    private fun setRingback(on: Boolean) {
        if (on && ringback == null) {
            ringback = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME) }.getOrNull()
                ?.also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        } else if (!on) {
            ringback?.release()
            ringback = null
        }
    }

    private fun setProximity(on: Boolean) {
        if (on && proximity == null) {
            val power = context.getSystemService(PowerManager::class.java)
            if (power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximity = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "whispr:call")
                    .also { it.acquire(MAX_CALL_MS) }
            }
        } else if (!on) {
            proximity?.takeIf { it.isHeld }?.release()
            proximity = null
        }
    }

    private companion object {
        val LIVE = setOf(CallPhase.Dialing, CallPhase.Connecting, CallPhase.Connected)
        const val RINGBACK_VOLUME = 60
        const val MAX_CALL_MS = 4 * 60 * 60_000L
    }
}
