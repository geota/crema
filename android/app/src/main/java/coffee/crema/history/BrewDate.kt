package coffee.crema.history

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*
 * When a logged brew may be dated, and the order the history keeps (#300). The
 * web holds the same rules in `brew-date.ts`. The core takes its rows as they
 * come, newest first (`brew_log_seeds`, `brews_remaining_estimate`), so that
 * order is what makes the newest row the last brew.
 */

const val BREW_DATE_INVALID = "Pick a date."
const val BREW_DATE_FUTURE = "A brew can’t be dated in the future."

/** What's wrong with dating a brew [atMs], or null when the date is fine. */
fun brewDateError(atMs: Long, nowMs: Long): String? = when {
    atMs <= 0L -> BREW_DATE_INVALID
    atMs > nowMs -> BREW_DATE_FUTURE
    else -> null
}

/** The message for a new brew dated before everything a full history keeps. */
fun brewDateTooOld(cap: Int = HistoryStore.MAX_SHOTS): String =
    "History keeps the newest $cap brews, and this date is older than all of them."

/**
 * Whether a new row dated [atMs] survives the cap. A full history drops its
 * oldest row, so a brew older than all of them would be gone as it saved.
 */
fun fitsHistory(history: List<StoredShot>, atMs: Long, cap: Int = HistoryStore.MAX_SHOTS): Boolean =
    history.size < cap || atMs >= (history.lastOrNull()?.completedAtMs ?: atMs)

/** Newest first; rows with the same date keep their order, so a new row leads. */
fun List<StoredShot>.newestFirst(): List<StoredShot> = sortedByDescending { it.completedAtMs }

/**
 * [history] with [added] in place by date. The list stays newest first, so the
 * cap drops the oldest rows and the core's last brew is the newest one, however
 * a row was dated. A row older than a full history is the one that goes.
 */
fun historyWith(added: List<StoredShot>, history: List<StoredShot>): List<StoredShot> =
    (added + history).newestFirst().take(HistoryStore.MAX_SHOTS)

/** [atMs]'s local date. */
fun localDateOf(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()

/** [atMs]'s local time. */
fun localTimeOf(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalTime =
    Instant.ofEpochMilli(atMs).atZone(zone).toLocalTime()

/** [atMs] moved to [date], at the same time of day. */
fun withLocalDate(atMs: Long, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
    date.atTime(localTimeOf(atMs, zone)).atZone(zone).toInstant().toEpochMilli()

/** [atMs] moved to [hour]:[minute] on the same day, to the minute (a picker has no seconds). */
fun withLocalTime(atMs: Long, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
    localDateOf(atMs, zone).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
