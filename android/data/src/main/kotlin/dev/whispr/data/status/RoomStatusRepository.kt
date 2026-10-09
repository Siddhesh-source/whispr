package dev.whispr.data.status

import dev.whispr.data.db.AttachmentEntity
import dev.whispr.data.db.OutboxEntity
import dev.whispr.data.db.StatusEntity
import dev.whispr.data.db.StatusRow
import dev.whispr.data.db.WhisprDatabase
import dev.whispr.data.media.MediaService
import dev.whispr.data.media.Prepared
import dev.whispr.data.messaging.Attachments
import dev.whispr.data.messaging.IncomingPipeline
import dev.whispr.data.messaging.MessageDeletion
import dev.whispr.data.messaging.MessagingEngine
import dev.whispr.data.messaging.Payload
import dev.whispr.data.messaging.PayloadCodec
import dev.whispr.data.messaging.RoomSettingsRepository
import dev.whispr.domain.model.Attachment
import dev.whispr.domain.model.AttachmentKind
import dev.whispr.domain.model.AttachmentState
import dev.whispr.domain.model.ConversationId
import dev.whispr.domain.model.MediaSource
import dev.whispr.domain.model.SendResult
import dev.whispr.domain.model.StatusAuthor
import dev.whispr.domain.model.StatusFeed
import dev.whispr.domain.model.StatusItem
import dev.whispr.domain.model.StatusKind
import dev.whispr.domain.model.StatusRules
import dev.whispr.domain.model.StatusSendState
import dev.whispr.domain.model.StatusViewer
import dev.whispr.domain.model.UserId
import dev.whispr.domain.repository.AccountRepository
import dev.whispr.domain.repository.StatusRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Statuses (DESIGN: docs/designs/camera-gifs-status-calls.md §3). A post is
 * stored here and queued, in the same transaction, to every accepted contact
 * at status priority, so it never delays a call or a chat message. A photo is
 * encrypted once and uploaded once; contacts get its key in the pairwise
 * payload. Viewing sends a `status_seen` only while read receipts are on
 * (like chat ticks); a like always goes to the author.
 */
class RoomStatusRepository(
    private val db: WhisprDatabase,
    private val engine: MessagingEngine,
    private val accounts: AccountRepository,
    private val media: MediaService,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : StatusRepository {
    private val dao get() = db.statusDao()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeFeed(): Flow<StatusFeed> {
        // Re-query each minute so statuses drop off when they expire, even with no writes.
        val ticks = flow {
            while (true) {
                emit(clock())
                delay(TICK_MS)
            }
        }
        return combine(ticks.flatMapLatest { dao.observeLive(it) }, accounts.observeAccount()) { rows, account ->
            val me = account?.userId?.value ?: return@combine StatusFeed()
            val items = rows.map { it.toItem(me) }
            val others = items.filter { !it.mine }
                .groupBy { it.author }
                .map { (author, list) ->
                    val row = rows.first { it.authorId == author.value }
                    StatusAuthor(author, row.authorName.orEmpty(), list, row.authorAvatar)
                }
                .sortedByDescending { it.latest }
            StatusFeed(
                mine = items.filter { it.mine },
                recent = others.filter { !it.allViewed },
                viewed = others.filter { it.allViewed },
            )
        }
    }

    override suspend fun postText(text: String, background: Int): Boolean {
        val body = text.trim()
        if (body.isEmpty() || body.length > StatusRules.MAX_TEXT) return false
        if (background !in 0 until StatusRules.BACKGROUNDS) return false
        val me = accounts.getAccount()?.userId?.value ?: return false
        val sid = UUID.randomUUID().toString()
        val now = clock()
        engine.transaction {
            dao.insert(
                StatusEntity(
                    authorId = me,
                    statusId = sid,
                    kind = IncomingPipeline.STATUS_TEXT,
                    body = body,
                    background = background,
                    createdAt = now,
                    expireAt = now + StatusRules.LIFETIME.toMillis(),
                    viewed = true,
                ),
            )
            fanOut(me, Payload.Status(sid, now, IncomingPipeline.STATUS_TEXT, text = body, bg = background), now)
        }
        return true
    }

    override suspend fun postImage(uri: String, caption: String): SendResult {
        val me = accounts.getAccount()?.userId?.value ?: return SendResult.NotAllowed
        if (caption.length > StatusRules.MAX_CAPTION) return SendResult.NotAllowed
        val prepared = when (val p = media.prepare(MediaSource(uri, AttachmentKind.Image))) {
            is Prepared.Ok -> p.media
            Prepared.TooLarge -> return SendResult.TooLarge
            Prepared.Unreadable -> return SendResult.Unreadable
        }
        val sealed = media.sealDetached(prepared.bytes)
        val sid = UUID.randomUUID().toString()
        val now = clock()
        engine.transaction {
            dao.insert(
                StatusEntity(
                    authorId = me,
                    statusId = sid,
                    kind = IncomingPipeline.STATUS_IMAGE,
                    body = caption.trim(),
                    background = 0,
                    createdAt = now,
                    expireAt = now + StatusRules.LIFETIME.toMillis(),
                    viewed = true,
                    sendState = StatusSendState.Sending.name,
                    key = sealed.key,
                    digest = sealed.digest,
                    size = sealed.size,
                    contentType = prepared.contentType,
                    width = prepared.width,
                    height = prepared.height,
                    thumbnail = prepared.thumbnail,
                    blobPath = sealed.path,
                    mediaState = AttachmentState.Uploading.name,
                ),
            )
        }
        scope.launch { upload(me, sid) }
        return SendResult.Ok
    }

    /** Uploads our photo status once, then queues it to every contact. Failure shows "Not sent" with retry. */
    private suspend fun upload(me: String, sid: String) {
        val row = engine.transaction { dao.get(me, sid) } ?: return
        val path = row.blobPath ?: return
        val remoteId = row.remoteId ?: media.uploadBlob(path)
        if (remoteId == null) {
            engine.transaction {
                dao.setSendState(me, sid, StatusSendState.Failed.name)
                dao.setMediaState(me, sid, AttachmentState.Failed.name)
            }
            return
        }
        engine.transaction {
            val current = dao.get(me, sid) ?: return@transaction // deleted meanwhile
            dao.setRemoteId(me, sid, remoteId)
            dao.setSendState(me, sid, StatusSendState.Sent.name)
            dao.setMediaState(me, sid, AttachmentState.Ready.name)
            val pointer = Attachments.toPointer(current.asAttachment(), remoteId)
            fanOut(
                me,
                Payload.Status(sid, current.createdAt, IncomingPipeline.STATUS_IMAGE, text = current.body, a = pointer),
                current.createdAt,
            )
        }
    }

    override suspend fun retry(statusId: String) {
        val me = accounts.getAccount()?.userId?.value ?: return
        val row = engine.transaction { dao.get(me, statusId) } ?: return
        if (row.sendState != StatusSendState.Failed.name) return
        engine.transaction { dao.setSendState(me, statusId, StatusSendState.Sending.name) }
        upload(me, statusId)
    }

    /** Photo uploads interrupted by the app dying resume on start. */
    suspend fun resumePending() {
        val me = accounts.getAccount()?.userId?.value ?: return
        engine.transaction { dao.pendingUploads(me) }.forEach { upload(me, it.statusId) }
    }

    override suspend fun delete(statusId: String) {
        val me = accounts.getAccount()?.userId?.value ?: return
        val now = clock()
        val file = engine.transaction {
            val row = dao.get(me, statusId) ?: return@transaction null
            dao.delete(me, statusId)
            // Only contacts who were sent it need the delete; a failed upload went to nobody.
            if (row.sendState == StatusSendState.Sent.name) fanOut(me, Payload.StatusDelete(statusId, now), now)
            row.blobPath
        }
        MessageDeletion.deleteFiles(listOfNotNull(file))
    }

    override suspend fun markViewed(author: UserId, statusId: String) {
        val me = accounts.getAccount()?.userId?.value ?: return
        val now = clock()
        engine.transaction {
            val row = dao.get(author.value, statusId) ?: return@transaction
            if (row.viewed || author.value == me) return@transaction
            dao.markViewed(author.value, statusId)
            val receipts = db.cryptoDao().setting(RoomSettingsRepository.READ_RECEIPTS) != "false"
            if (receipts) sendTo(me, author.value, Payload.StatusSeen(statusId), now)
        }
    }

    override suspend fun like(author: UserId, statusId: String, liked: Boolean) {
        val me = accounts.getAccount()?.userId?.value ?: return
        if (author.value == me) return
        val now = clock()
        engine.transaction {
            val row = dao.get(author.value, statusId) ?: return@transaction
            if (row.liked == liked) return@transaction
            dao.setLiked(author.value, statusId, liked)
            dao.markViewed(author.value, statusId)
            sendTo(me, author.value, Payload.StatusLike(statusId, liked, now), now)
        }
    }

    override fun observeViewers(statusId: String): Flow<List<StatusViewer>> = dao.observeViews(statusId).map { rows ->
        rows.map {
            StatusViewer(UserId(it.viewerId), it.viewerName.orEmpty(), Instant.ofEpochMilli(it.viewedAt), it.liked)
        }
    }

    override suspend fun imageBytes(author: UserId, statusId: String): ByteArray? {
        var row = engine.transaction { dao.get(author.value, statusId) } ?: return null
        val key = row.key ?: return null
        val digest = row.digest ?: return null
        if (row.blobPath == null) {
            val remoteId = row.remoteId ?: return null
            engine.transaction { dao.setMediaState(author.value, statusId, AttachmentState.Downloading.name) }
            val (state, path) = media.fetchVerified(remoteId, row.size, key, digest)
            engine.transaction { dao.setBlob(author.value, statusId, state.name, path) }
            row = row.copy(blobPath = path ?: return null)
        }
        return media.open(row.blobPath, key, digest)
    }

    /** Queues [payload] to every contact in the audience; runs inside a transaction. */
    private fun fanOut(me: String, payload: Payload, now: Long) {
        val bytes = PayloadCodec.encode(payload)
        dao.audience(me).forEach { peer -> enqueue(me, peer, bytes, now) }
    }

    private fun sendTo(me: String, peer: String, payload: Payload, now: Long) =
        enqueue(me, peer, PayloadCodec.encode(payload), now)

    private fun enqueue(me: String, peer: String, bytes: ByteArray, now: Long) {
        db.cryptoDao().enqueue(
            OutboxEntity(
                messageId = UUID.randomUUID().toString(),
                conversationId = ConversationId.direct(UserId(me), UserId(peer)).value,
                recipientId = peer,
                payload = bytes,
                clientTs = now,
                priority = OutboxEntity.PRIORITY_STATUS,
            ),
        )
    }

    private fun StatusEntity.asAttachment() = AttachmentEntity(
        messageRow = 0,
        remoteId = remoteId,
        key = key ?: ByteArray(0),
        digest = digest ?: ByteArray(0),
        size = size,
        contentType = contentType ?: "image/jpeg",
        kind = AttachmentKind.Image.name,
        fileName = null,
        width = width,
        height = height,
        durationMs = null,
        thumbnail = thumbnail,
        blobPath = blobPath,
        state = mediaState ?: AttachmentState.Ready.name,
    )

    private fun StatusRow.toItem(me: String): StatusItem {
        val image = kind == IncomingPipeline.STATUS_IMAGE
        return StatusItem(
            id = statusId,
            author = UserId(authorId),
            mine = authorId == me,
            kind = if (image) StatusKind.Image else StatusKind.Text,
            text = body,
            background = background,
            image = if (image) {
                Attachment(
                    kind = AttachmentKind.Image,
                    contentType = contentType ?: "image/jpeg",
                    fileName = null,
                    size = size,
                    width = width,
                    height = height,
                    thumbnail = thumbnail,
                    state = mediaState?.let(AttachmentState::valueOf) ?: AttachmentState.Remote,
                )
            } else {
                null
            },
            createdAt = Instant.ofEpochMilli(createdAt),
            expiresAt = Instant.ofEpochMilli(expireAt),
            viewed = viewed,
            sendState = StatusSendState.valueOf(sendState),
            liked = liked,
            views = views,
            likes = likes,
        )
    }

    private companion object {
        const val TICK_MS = 60_000L
    }
}
