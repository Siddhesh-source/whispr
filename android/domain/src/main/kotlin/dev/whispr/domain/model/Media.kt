package dev.whispr.domain.model

enum class AttachmentKind { Image, File, Voice }

enum class AttachmentState {
    /** Encrypted locally, being uploaded. */
    Uploading,

    /** Our copy, or a received blob that was downloaded, verified and stored (still encrypted). */
    Ready,

    /** Received; not downloaded yet. */
    Remote,
    Downloading,

    /** Upload or download failed; can be retried. */
    Failed,

    /** The server already deleted it (retention). */
    Expired,

    /** The download did not match the digest or failed authentication. */
    Corrupt,
}

data class Attachment(
    val kind: AttachmentKind,
    val contentType: String,
    val fileName: String?,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    /** A small JPEG preview (images), shown before download. */
    val thumbnail: ByteArray? = null,
    val state: AttachmentState,
) {
    /**
     * A GIF or animated WebP: sent as an image (so older apps still show its
     * first frame) and animated by apps that know the type.
     */
    val animated: Boolean get() = kind == AttachmentKind.Image && contentType in ANIMATED_TYPES

    override fun equals(other: Any?) = other is Attachment &&
        kind == other.kind &&
        contentType == other.contentType &&
        fileName == other.fileName &&
        size == other.size &&
        width == other.width &&
        height == other.height &&
        durationMs == other.durationMs &&
        thumbnail.contentEqualsNullable(other.thumbnail) &&
        state == other.state

    override fun hashCode() = state.hashCode() * 31 + size.hashCode()
}

private val ANIMATED_TYPES = setOf("image/gif", "image/webp")

/** A file the user picked or recorded, as an opaque platform URI string. */
data class MediaSource(
    val uri: String,
    val kind: AttachmentKind,
    val fileName: String? = null,
    val contentType: String? = null,
    val durationMs: Long? = null,
)

sealed interface SendResult {
    data object Ok : SendResult

    /** Over the size limit ([MAX_ATTACHMENT_BYTES]). */
    data object TooLarge : SendResult

    /** The file could not be read or decoded. */
    data object Unreadable : SendResult

    /** Sending is blocked (key change, not a member, request not accepted). */
    data object NotAllowed : SendResult

    companion object {
        /** Largest file that can be sent; the server enforces the same. */
        const val MAX_ATTACHMENT_BYTES = 25L shl 20
    }
}

data class Reaction(val emoji: String, val count: Int, val mine: Boolean)
