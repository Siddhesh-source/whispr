package dev.whispr.data.messaging

import dev.whispr.data.db.AttachmentEntity
import dev.whispr.data.media.MediaCrypto
import dev.whispr.domain.model.AttachmentKind
import dev.whispr.domain.model.AttachmentState
import dev.whispr.domain.model.SendResult
import java.util.Base64
import java.util.UUID

/** Converting attachment pointers (hostile input from the network) to stored rows and back. */
internal object Attachments {
    private const val MAX_TYPE = 128
    private const val MAX_FILE_NAME = 255
    private const val MAX_THUMB = 32 * 1024
    private const val MAX_DIMENSION = 20_000
    private const val MAX_DURATION_MS = 60 * 60_000L
    private const val DIGEST_BYTES = 32
    private const val BACKSLASH = '\\'

    fun kindName(kind: AttachmentKind) = when (kind) {
        AttachmentKind.Image -> "image"
        AttachmentKind.File -> "file"
        AttachmentKind.Voice -> "voice"
    }

    /** A received pointer as a row (messageRow filled in by the caller), or null if malformed. */
    fun fromPointer(p: AttachmentPointer): AttachmentEntity? {
        val b64 = Base64.getDecoder()
        val key = runCatching { b64.decode(p.key) }.getOrNull()?.takeIf { it.size == MediaCrypto.KEY_BYTES }
            ?: return null
        val digest = runCatching { b64.decode(p.digest) }.getOrNull()?.takeIf { it.size == DIGEST_BYTES }
            ?: return null
        val thumb = p.thumb?.let { runCatching { b64.decode(it) }.getOrNull()?.takeIf { t -> t.size <= MAX_THUMB } }
        val kind = when (p.kind) {
            "image" -> AttachmentKind.Image
            "file" -> AttachmentKind.File
            "voice" -> AttachmentKind.Voice
            else -> return null
        }
        if (runCatching { UUID.fromString(p.id) }.isFailure) return null
        if (p.size !in 1..SendResult.MAX_ATTACHMENT_BYTES || p.type.isBlank() || p.type.length > MAX_TYPE) return null
        if (p.w != null && p.w !in 1..MAX_DIMENSION) return null
        if (p.h != null && p.h !in 1..MAX_DIMENSION) return null
        if (p.dur != null && p.dur !in 0..MAX_DURATION_MS) return null
        return AttachmentEntity(
            messageRow = 0,
            remoteId = p.id,
            key = key,
            digest = digest,
            size = p.size,
            contentType = p.type,
            kind = kind.name,
            fileName = p.name?.let(::safeFileName),
            width = p.w,
            height = p.h,
            durationMs = p.dur,
            thumbnail = thumb,
            blobPath = null,
            state = AttachmentState.Remote.name,
        )
    }

    fun toPointer(a: AttachmentEntity, remoteId: String): AttachmentPointer {
        val b64 = Base64.getEncoder()
        return AttachmentPointer(
            id = remoteId,
            key = b64.encodeToString(a.key),
            digest = b64.encodeToString(a.digest),
            size = a.size,
            type = a.contentType,
            kind = kindName(AttachmentKind.valueOf(a.kind)),
            name = a.fileName,
            w = a.width,
            h = a.height,
            dur = a.durationMs,
            thumb = a.thumbnail?.let(b64::encodeToString),
        )
    }

    /** The name only, never a path; no control characters. */
    fun safeFileName(name: String): String? = name.substringAfterLast('/').substringAfterLast(BACKSLASH)
        .filter { it >= ' ' && it != '\u007f' }
        .take(MAX_FILE_NAME)
        .takeIf { it.isNotBlank() && it != "." && it != ".." }

    fun preview(kind: String): String = when (kind) {
        AttachmentKind.Image.name -> "Photo"
        AttachmentKind.Voice.name -> "Voice message"
        else -> "File"
    }
}
