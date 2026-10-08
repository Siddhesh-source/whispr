package dev.whispr.domain.model

import java.time.Duration
import java.time.Instant

/** A status is text on a palette background, or a photo with an optional caption. */
enum class StatusKind { Text, Image }

/** Our own statuses only: whether every contact's copy has been queued. */
enum class StatusSendState { Sending, Sent, Failed }

/**
 * One status update. It disappears everywhere [StatusRules.LIFETIME] after
 * [createdAt]. [image] is set for [StatusKind.Image].
 */
data class StatusItem(
    val id: String,
    val author: UserId,
    val mine: Boolean,
    val kind: StatusKind,
    /** The text, or a photo's caption (may be empty). */
    val text: String,
    /** Index into the palette's status backgrounds ([StatusRules.BACKGROUNDS]). */
    val background: Int,
    val image: Attachment?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val viewed: Boolean,
    val sendState: StatusSendState = StatusSendState.Sent,
)

/** One contact's current statuses, oldest first (the order they are played). */
data class StatusAuthor(val author: UserId, val name: String, val items: List<StatusItem>) {
    val allViewed: Boolean get() = items.all { it.viewed }
    val latest: Instant get() = items.maxOf { it.createdAt }
}

/** The Status tab: ours, then contacts with something unseen, then the rest. */
data class StatusFeed(
    val mine: List<StatusItem> = emptyList(),
    val recent: List<StatusAuthor> = emptyList(),
    val viewed: List<StatusAuthor> = emptyList(),
)

object StatusRules {
    /** How long a status lives, on every device. */
    val LIFETIME: Duration = Duration.ofHours(24)

    /** Accepted from a sender whose clock runs this far ahead or behind. */
    val CLOCK_SLACK: Duration = Duration.ofHours(1)

    const val MAX_TEXT = 700
    const val MAX_CAPTION = 200

    /** Number of status backgrounds in the palette. */
    const val BACKGROUNDS = 6

    /** Whether a status sent at [sentAt] is still showable at [now]. */
    fun isLive(sentAt: Instant, now: Instant): Boolean =
        sentAt.isAfter(now.minus(LIFETIME).minus(CLOCK_SLACK)) && sentAt.isBefore(now.plus(CLOCK_SLACK))
}
