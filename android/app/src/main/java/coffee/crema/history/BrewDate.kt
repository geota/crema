package coffee.crema.history

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

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
 * Whether a new row dated [atMs] survives the cap, given [history] newest first.
 * Only the newest [cap] stay, and a new row leads its ties, so it has to reach
 * the row at the cut. A stored list can be over the cap, so that isn't the last.
 */
fun fitsHistory(history: List<StoredShot>, atMs: Long, cap: Int = HistoryStore.MAX_SHOTS): Boolean =
    history.getOrNull(cap - 1)?.let { atMs >= it.completedAtMs } ?: true

/** What the cap made of an insert: the new list, the added rows still in it, and rows of any kind dropped. */
data class HistoryMerge(val rows: List<StoredShot>, val kept: List<StoredShot>, val dropped: Int)

/** Newest first; rows with the same date keep their order, so a new row leads. */
fun List<StoredShot>.newestFirst(): List<StoredShot> = sortedByDescending { it.completedAtMs }

/**
 * [history] with [added] in place by date. The list stays newest first, so the
 * cap drops the oldest rows and the core's last brew is the newest one, however
 * a row was dated. A row older than a full history is the one that goes, except
 * [keepId], a row just made here: it stays whatever its date, for a device whose
 * clock is behind its history, and the oldest other row goes instead. With
 * nothing to add this changes nothing, so a list over the cap stays as it is.
 */
fun mergeHistory(added: List<StoredShot>, history: List<StoredShot>, keepId: String? = null): HistoryMerge {
    if (added.isEmpty()) return HistoryMerge(history, emptyList(), 0)
    val room = if (keepId == null) HistoryStore.MAX_SHOTS else HistoryStore.MAX_SHOTS - 1
    var others = 0
    val rows = (added + history).newestFirst().filter { it.id == keepId || ++others <= room }
    val addedIds = added.mapTo(HashSet()) { it.id }
    return HistoryMerge(rows, rows.filter { it.id in addedIds }, added.size + history.size - rows.size)
}

/**
 * [history] with the rows of a Visualizer pull that fit. A pull never removes a
 * row: the oldest ones can be brew logs or shots that never reached Visualizer.
 * So the cap refuses what doesn't fit, in the order given, and [HistoryMerge.dropped]
 * stays 0. Rows already here are left out.
 */
fun mergePulled(added: List<StoredShot>, history: List<StoredShot>, cap: Int = HistoryStore.MAX_SHOTS): HistoryMerge {
    val present = history.mapTo(HashSet()) { it.id }
    val kept = added.filter { it.id !in present }.take((cap - history.size).coerceAtLeast(0))
    if (kept.isEmpty()) return HistoryMerge(history, emptyList(), 0)
    return HistoryMerge((kept + history).newestFirst(), kept, 0)
}

/** The list [mergeHistory] leaves. */
fun historyWith(added: List<StoredShot>, history: List<StoredShot>, keepId: String? = null): List<StoredShot> =
    mergeHistory(added, history, keepId).rows

/** What to say when the cap dropped rows, or nothing when it didn't. */
fun historyDroppedNotice(dropped: Int, cap: Int = HistoryStore.MAX_SHOTS): String =
    if (dropped > 0) ". History keeps the newest $cap, so $dropped older shot${if (dropped == 1) " was" else "s were"} dropped" else ""

/**
 * What to say when a full history refused rows a pull brought. The next Sync
 * won't look for them, because the cursor has moved past them: Re-sync does.
 */
fun historyFullNotice(refused: Int, cap: Int = HistoryStore.MAX_SHOTS): String =
    "History is full at $cap shots, so $refused shot${if (refused == 1) " from Visualizer wasn’t" else "s from Visualizer weren’t"} added. " +
        "After freeing up space, use Re-sync shots to fetch ${if (refused == 1) "it" else "them"}"

/** [atMs]'s local date. */
fun localDateOf(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()

/** [atMs]'s local time. */
fun localTimeOf(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalTime =
    Instant.ofEpochMilli(atMs).atZone(zone).toLocalTime()

/**
 * [atMs] moved to [date], at the same time of day. The same date is [atMs]
 * exactly. In the hour an autumn clock change repeats, it stays on the same pass.
 */
fun withLocalDate(atMs: Long, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long {
    val at = Instant.ofEpochMilli(atMs).atZone(zone)
    if (at.toLocalDate() == date) return atMs
    return ZonedDateTime.ofLocal(date.atTime(at.toLocalTime()), zone, at.offset).toInstant().toEpochMilli()
}

/**
 * [atMs] moved to [hour]:[minute] on the same day, to the minute (a picker has
 * no seconds). The same hour and minute is [atMs] exactly, seconds and all.
 */
fun withLocalTime(atMs: Long, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
    val at = Instant.ofEpochMilli(atMs).atZone(zone)
    if (at.hour == hour && at.minute == minute) return atMs
    return ZonedDateTime.ofLocal(at.toLocalDate().atTime(hour, minute), zone, at.offset).toInstant().toEpochMilli()
}
