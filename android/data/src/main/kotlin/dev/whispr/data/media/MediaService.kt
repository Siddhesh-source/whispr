package dev.whispr.data.media

import dev.whispr.data.crypto.SessionCrypto
import dev.whispr.data.db.AttachmentEntity
import dev.whispr.data.db.MessageEntity
import dev.whispr.data.db.OutboxEntity
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.messaging.AttachmentPointer
import dev.whispr.data.messaging.Attachments
import dev.whispr.data.messaging.GroupManager
import dev.whispr.data.messaging.Payload
import dev.whispr.data.messaging.PayloadCodec
import dev.whispr.data.network.ApiResult
import dev.whispr.data.network.DownloadResult
import dev.whispr.data.network.MediaApi
import dev.whispr.domain.model.AttachmentState
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.MediaSource
import dev.whispr.domain.model.MessageStatus
import dev.whispr.domain.model.SendResult
import dev.whispr.domain.model.UserId
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Encrypted blobs on disk. Plaintext never touches this directory. */
class MediaFiles(private val dir: File, private val exportDir: File) {
    fun write(blob: ByteArray): String {
        dir.mkdirs()
        val target = File(dir, UUID.randomUUID().toString() + ".bin")
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(blob)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return target.absolutePath
    }

    fun read(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

    /** A decrypted copy for another app to open; removed by [clearExports] on the next start. */
    fun export(plaintext: ByteArray, name: String): String {
        exportDir.mkdirs()
        val target = File(File(exportDir, UUID.randomUUID().toString()).apply { mkdirs() }, name)
        target.writeBytes(plaintext)
        return target.absolutePath
    }

    fun clearExports() {
        exportDir.deleteRecursively()
    }
}

/**
 * Encrypted media: each file gets a fresh key ([MediaCrypto]), only the
 * ciphertext is uploaded, and the key and digest go inside an end-to-end
 * encrypted [Payload.Media]. Received blobs are verified against the digest
 * before they are stored, and stay encrypted on disk.
 */
class MediaService(
    private val db: WhisprDatabase,
    private val crypto: SessionCrypto,
    private val groups: GroupManager,
    private val api: MediaApi,
    private val preparer: MediaPreparer,
    private val files: MediaFiles,
    private val me: suspend () -> UserId?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val queries get() = db.groupQueries()

    /** Who a conversation is with: a group, or the 1:1 peer. */
    sealed interface Target {
        data class Direct(val peer: String) : Target
        data class Group(val id: String) : Target
    }

    /** [expiresIn]: the conversation's disappearing-message timer (seconds), if on. */
    suspend fun send(
        conversation: ConversationId,
        target: Target,
        source: MediaSource,
        expiresIn: Long? = null,
    ): SendResult {
        val prepared = when (val p = preparer.prepare(source)) {
            is Prepared.Ok -> p.media
            Prepared.TooLarge -> return SendResult.TooLarge
            Prepared.Unreadable -> return SendResult.Unreadable
        }
        if (prepared.bytes.size > SendResult.MAX_ATTACHMENT_BYTES) return SendResult.TooLarge
        val template = AttachmentEntity(
            messageRow = 0,
            remoteId = null,
            key = ByteArray(0),
            digest = ByteArray(0),
            size = prepared.bytes.size.toLong(),
            contentType = prepared.contentType,
            kind = source.kind.name,
            fileName = prepared.fileName?.let(Attachments::safeFileName),
            width = prepared.width,
            height = prepared.height,
            durationMs = prepared.durationMs,
            thumbnail = prepared.thumbnail,
            blobPath = null,
            state = AttachmentState.Uploading.name,
        )
        sealAndQueue(conversation, target, prepared.bytes, template, forwarded = false, expiresIn = expiresIn)
        return SendResult.Ok
    }

    /**
     * Forwards the attachment of our stored message [sourceRow]: decrypted
     * in memory, then sealed under a fresh key and uploaded as a new blob.
     * Reusing the original blob would let the server link the two
     * conversations, and the copy would expire with the original.
     */
    suspend fun forward(sourceRow: Long, conversation: ConversationId, target: Target, expiresIn: Long?): SendResult {
        val source = queries.attachment(sourceRow) ?: return SendResult.NotAllowed
        if (source.state != AttachmentState.Ready.name) return SendResult.NotAllowed
        val plaintext = bytes(sourceRow) ?: return SendResult.Unreadable
        sealAndQueue(conversation, target, plaintext, source, forwarded = true, expiresIn = expiresIn)
        return SendResult.Ok
    }

    /** Encrypts [plaintext] with a fresh key, stores the message and blob, and starts the upload. */
    private suspend fun sealAndQueue(
        conversation: ConversationId,
        target: Target,
        plaintext: ByteArray,
        template: AttachmentEntity,
        forwarded: Boolean,
        expiresIn: Long?,
    ) {
        val sealed = withContext(Dispatchers.Default) { MediaCrypto.seal(plaintext) }
        val path = withContext(Dispatchers.IO) { files.write(sealed.blob) }
        val mid = UUID.randomUUID().toString()
        val now = clock()
        val row = crypto.transaction {
            val row = db.cryptoDao().insertMessage(
                MessageEntity(
                    messageId = mid,
                    conversationId = conversation.value,
                    peerId = when (target) {
                        is Target.Direct -> target.peer
                        is Target.Group -> target.id
                    },
                    outgoing = true,
                    body = "",
                    timestamp = now,
                    status = MessageStatus.Sending.name,
                    forwarded = forwarded,
                    expiresIn = expiresIn,
                    expireAt = expiresIn?.let { now + it * MS_PER_S },
                ),
            )
            db.groupDao().putAttachment(
                template.copy(
                    messageRow = row,
                    remoteId = null,
                    key = sealed.key,
                    digest = sealed.digest,
                    size = plaintext.size.toLong(),
                    blobPath = path,
                    state = AttachmentState.Uploading.name,
                ),
            )
            row
        }
        scope.launch { upload(row) }
    }

    /** Uploads (or retries) the blob of our message [row], then queues the message itself. */
    suspend fun upload(row: Long) {
        val a = queries.attachment(row) ?: return
        val message = crypto.transaction { db.cryptoDao().messageByRow(row) } ?: return
        val remoteId = a.remoteId ?: run {
            val blob = a.blobPath?.let { withContext(Dispatchers.IO) { files.read(it) } }
            val result = if (blob == null) ApiResult.HttpError(0) else api.upload(blob)
            if (result !is ApiResult.Success) {
                // Visible as a failed bubble with retry; never dropped silently.
                queries.setAttachmentState(row, AttachmentState.Failed.name)
                crypto.transaction { db.groupDao().setStatus(message.messageId, MessageStatus.Failed.name) }
                return
            }
            queries.setUploaded(row, result.body)
            result.body
        }
        val self = me()?.value ?: return
        val pointer = Attachments.toPointer(a, remoteId)
        crypto.transaction {
            val gdao = db.groupDao()
            // A retry after a failed upload: back to Sending.
            gdao.setStatus(message.messageId, MessageStatus.Sending.name)
            if (gdao.group(message.conversationId) != null) {
                val payload = PayloadCodec.encode(message.mediaPayload(pointer, g = message.conversationId))
                when (
                    groups.enqueueGroup(
                        self,
                        message.conversationId,
                        message.messageId,
                        payload,
                        message.timestamp,
                    )
                ) {
                    null -> gdao.setStatus(message.messageId, MessageStatus.Failed.name)
                    emptyList<String>() -> gdao.setStatus(message.messageId, MessageStatus.Sent.name)
                    else -> Unit
                }
            } else {
                db.cryptoDao().enqueue(
                    OutboxEntity(
                        messageId = message.messageId,
                        conversationId = message.conversationId,
                        recipientId = message.peerId,
                        payload = PayloadCodec.encode(message.mediaPayload(pointer, g = null)),
                        clientTs = message.timestamp,
                    ),
                )
            }
        }
    }

    private fun MessageEntity.mediaPayload(pointer: AttachmentPointer, g: String?) = Payload.Media(
        pointer,
        mid = messageId,
        ts = timestamp,
        g = g,
        q = quoteId,
        qa = quoteAuthor,
        fwd = forwarded,
        exp = expiresIn,
    )

    /** Uploads interrupted by the app dying resume on start. */
    suspend fun resumePending() {
        files.clearExports()
        queries.pendingUploads().forEach { upload(it.messageRow) }
    }

    /** Downloads, checks size and digest, and stores the (still encrypted) blob. */
    suspend fun download(row: Long) {
        val a = queries.attachment(row) ?: return
        if (a.state == AttachmentState.Ready.name || a.state == AttachmentState.Downloading.name) return
        val remoteId = a.remoteId ?: return
        queries.setAttachmentState(row, AttachmentState.Downloading.name)
        val (state, path) = fetchVerified(remoteId, a.size, a.key, a.digest)
        if (path != null) {
            queries.setAttachmentBlob(row, state.name, path)
        } else {
            queries.setAttachmentState(row, state.name)
        }
    }

    /** Decrypted bytes of a stored attachment (in memory only). */
    suspend fun bytes(row: Long): ByteArray? {
        val a = queries.attachment(row) ?: return null
        return open(a.blobPath, a.key, a.digest)
    }

    // ---- Blobs that belong to no message (status photos) ----

    /** A prepared image or file, encrypted under a fresh key and written to disk (not yet uploaded). */
    class Detached(val path: String, val key: ByteArray, val digest: ByteArray, val size: Long)

    suspend fun prepare(source: MediaSource): Prepared = preparer.prepare(source)

    suspend fun sealDetached(plaintext: ByteArray): Detached {
        val sealed = withContext(Dispatchers.Default) { MediaCrypto.seal(plaintext) }
        val path = withContext(Dispatchers.IO) { files.write(sealed.blob) }
        return Detached(path, sealed.key, sealed.digest, plaintext.size.toLong())
    }

    /** Uploads the encrypted blob at [path]; the server's blob ID, or null on failure. */
    suspend fun uploadBlob(path: String): String? {
        val blob = withContext(Dispatchers.IO) { files.read(path) } ?: return null
        return (api.upload(blob) as? ApiResult.Success)?.body
    }

    /**
     * Downloads blob [remoteId] and keeps it only if its size and digest match
     * what the encrypted message announced: (Ready, path) or (why not, null).
     */
    suspend fun fetchVerified(
        remoteId: String,
        size: Long,
        key: ByteArray,
        digest: ByteArray,
    ): Pair<AttachmentState, String?> = when (val r = api.download(remoteId, size + MediaCrypto.OVERHEAD)) {
        is DownloadResult.Ok -> {
            val ok = withContext(Dispatchers.Default) {
                r.blob.size.toLong() == size + MediaCrypto.OVERHEAD && MediaCrypto.open(r.blob, key, digest) != null
            }
            if (ok) {
                AttachmentState.Ready to withContext(Dispatchers.IO) { files.write(r.blob) }
            } else {
                AttachmentState.Corrupt to null
            }
        }
        DownloadResult.Gone -> AttachmentState.Expired to null
        DownloadResult.TooLarge -> AttachmentState.Corrupt to null
        DownloadResult.NetworkError -> AttachmentState.Failed to null
    }

    /** Decrypts a stored blob in memory; null if missing or tampered with. */
    suspend fun open(path: String?, key: ByteArray, digest: ByteArray): ByteArray? {
        val blob = path?.let { withContext(Dispatchers.IO) { files.read(it) } } ?: return null
        return withContext(Dispatchers.Default) { MediaCrypto.open(blob, key, digest) }
    }

    suspend fun export(row: Long): String? {
        val a = queries.attachment(row) ?: return null
        val plaintext = bytes(row) ?: return null
        val name = a.fileName ?: ("whispr-" + row + extension(a.contentType))
        return withContext(Dispatchers.IO) { files.export(plaintext, name) }
    }

    private companion object {
        const val MS_PER_S = 1_000L
    }

    private fun extension(type: String) = when (type) {
        "image/jpeg" -> ".jpg"
        "audio/mp4", "audio/aac" -> ".m4a"
        "application/pdf" -> ".pdf"
        else -> ""
    }
}
