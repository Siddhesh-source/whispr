package dev.whispr.data.messaging

import dev.whispr.data.db.CryptoDao
import dev.whispr.data.db.MessageEntity
import java.io.File
import java.util.UUID

/**
 * Removing message content, shared by "delete for everyone" (both sides),
 * "delete for me" and disappearing messages. Runs inside a crypto
 * transaction; the encrypted blob files it returns are deleted by the
 * caller once the transaction has committed.
 */
internal object MessageDeletion {
    /** Keeps the row as a "deleted" tombstone; content, attachment and reactions go. */
    fun tombstone(dao: CryptoDao, m: MessageEntity, author: String): List<String> {
        val blob = dropExtras(dao, m, author)
        dao.markDeleted(m.localOrder)
        return listOfNotNull(blob)
    }

    /** Removes the row and everything attached to it. */
    fun remove(dao: CryptoDao, m: MessageEntity, author: String): List<String> {
        val blob = dropExtras(dao, m, author)
        dao.deleteRow(m.localOrder)
        return listOfNotNull(blob)
    }

    private fun dropExtras(dao: CryptoDao, m: MessageEntity, author: String): String? {
        val blob = dao.attachmentOf(m.localOrder)?.blobPath
        dao.deleteAttachment(m.localOrder)
        dao.deleteReactionsOn(m.conversationId, author, m.messageId)
        // A placeholder no longer needs recovering.
        if (!m.outgoing) dao.resolveReset(m.peerId, m.messageId)
        return blob
    }

    fun deleteFiles(paths: List<String>) {
        paths.forEach { runCatching { File(it).delete() } }
    }

    /** A centred, never-sent notice in [conversation] (a timer change). */
    fun notice(conversation: String, peerId: String, text: String, now: Long) = MessageEntity(
        messageId = "sys-" + UUID.randomUUID(),
        conversationId = conversation,
        peerId = peerId,
        outgoing = false,
        body = text,
        timestamp = now,
        status = null,
        readByMe = true,
        system = true,
    )
}

/** Notice text for timer changes, stored like the group notices. */
internal object TimerText {
    fun changed(who: String?, seconds: Long): String = when {
        seconds == 0L && who == null -> "You turned off disappearing messages"
        seconds == 0L -> "$who turned off disappearing messages"
        who == null -> "You set disappearing messages to ${duration(seconds)}"
        else -> "$who set disappearing messages to ${duration(seconds)}"
    }

    fun duration(seconds: Long): String = when {
        seconds % WEEK == 0L -> plural(seconds / WEEK, "week")
        seconds % DAY == 0L -> plural(seconds / DAY, "day")
        seconds % HOUR == 0L -> plural(seconds / HOUR, "hour")
        seconds % MINUTE == 0L -> plural(seconds / MINUTE, "minute")
        else -> plural(seconds, "second")
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"

    private const val MINUTE = 60L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY
}
