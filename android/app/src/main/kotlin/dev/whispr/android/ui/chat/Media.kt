package dev.whispr.android.ui.chat

import android.content.Context
import android.content.Intent
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * Records a voice message to an app-private cache file (AAC in MP4, mono).
 * MediaRecorder writes no location or device metadata. The file is deleted
 * once the message has been encrypted.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    val elapsedMs: Long get() = if (recorder != null) SystemClock.elapsedRealtime() - startedAt else 0L

    /** Input level since the last call, 0..1, on a square-root curve so quiet speech still shows. */
    fun level(): Float {
        val amp = try {
            recorder?.maxAmplitude ?: 0
        } catch (_: IllegalStateException) {
            0
        }
        return kotlin.math.sqrt(amp / MAX_AMPLITUDE).coerceIn(0f, 1f)
    }

    fun start(): Boolean {
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val target = File(dir, UUID.randomUUID().toString() + ".m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else legacyRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(BIT_RATE)
            r.setAudioSamplingRate(SAMPLE_RATE)
            r.setMaxDuration(MAX_DURATION_MS)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = target
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (_: Exception) {
            r.release()
            target.delete()
            false
        }
    }

    /** Stops and returns the recording, or null if it failed or was too short to keep. */
    fun stop(): Recording? {
        val r = recorder ?: return null
        val f = file
        recorder = null
        file = null
        val duration = SystemClock.elapsedRealtime() - startedAt
        val ok = try {
            r.stop()
            true
        } catch (_: RuntimeException) {
            false // stopped before any audio was captured
        } finally {
            r.release()
        }
        if (!ok || f == null || duration < MIN_DURATION_MS) {
            f?.delete()
            return null
        }
        return Recording(f, duration)
    }

    fun cancel() {
        stop()?.file?.delete()
    }

    @Suppress("DEPRECATION")
    private fun legacyRecorder() = MediaRecorder()

    class Recording(val file: File, val durationMs: Long) {
        val uri: String get() = Uri.fromFile(file).toString()
    }

    private companion object {
        const val BIT_RATE = 32_000
        const val SAMPLE_RATE = 44_100
        const val MAX_DURATION_MS = 10 * 60_000
        const val MIN_DURATION_MS = 500L
        const val MAX_AMPLITUDE = 32_767f
    }
}

/** Plays decrypted audio straight from memory: the plaintext never touches disk. */
class VoicePlayer {
    private var player: MediaPlayer? = null

    fun play(bytes: ByteArray, onDone: () -> Unit) {
        stop()
        val p = MediaPlayer()
        try {
            p.setDataSource(ByteSource(bytes))
            p.setOnCompletionListener {
                stop()
                onDone()
            }
            p.prepare()
            p.start()
            player = p
        } catch (_: Exception) {
            p.release()
            onDone()
        }
    }

    fun stop() {
        player?.release()
        player = null
    }

    private class ByteSource(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(size.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = bytes.size.toLong()

        override fun close() = Unit
    }
}

/** Hands a decrypted export (in cache/open, cleared on next start) to another app, read-only. */
fun openExternally(context: Context, path: String, contentType: String): Boolean {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", File(path))
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, contentType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    }
}
