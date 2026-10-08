package dev.whispr.android.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Localized short time for today's messages, short date otherwise. */
fun formatTimestamp(
    instant: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): String {
    val local = instant.atZone(zone)
    val style = if (local.toLocalDate() == today) TIME else DATE
    return style.format(local)
}

/** Localized short time, for message bubbles. */
fun formatTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = TIME.format(instant.atZone(zone))

/** Localized date for a day divider, without the year when it is this year. */
fun formatDay(day: LocalDate, today: LocalDate = LocalDate.now()): String =
    (if (day.year == today.year) DAY else DAY_YEAR).format(day)

private val TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")
private val DAY_YEAR: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
