package coffee.crema.diag

import android.content.Context
import java.io.File

/**
 * The event trail of the most recent shot, frozen to disk the moment the shot
 * ends.
 *
 * [DiagLog] is a rolling buffer of the last few hundred lines, which is the
 * wrong shape for the bugs users actually report. "Stop at weight didn't work"
 * is noticed at the machine, reported minutes-to-hours later, and by the time
 * anyone presses **Copy diagnostics** the shot has long since scrolled out —
 * geota/crema#56 arrived as a 124-second window containing nothing but tank
 * readings, hours after the shot in question.
 *
 * So snapshot the buffer at [finish], when the interesting window is still in
 * it, and keep that snapshot in `filesDir` so it survives the app being killed
 * and restarted — which is exactly what a user does when something misbehaves.
 * [CrashReporter] appends it to every diagnostics dump.
 *
 * The [summary] line carries the answer to "why didn't it stop" on its own —
 * the armed targets and the stop reason — so it doesn't depend on anyone
 * reading down a log.
 */
object ShotDiag {
    private const val FILE_NAME = "last-shot.txt"

    /**
     * Freeze the current diagnostics buffer as this shot's record.
     *
     * [summary] is the shot's own verdict (targets, stop reason, weights);
     * best-effort by design — a failed write must never disturb a shot.
     */
    fun finish(ctx: Context, summary: String) {
        runCatching {
            val body = buildString {
                appendLine("-- last shot --")
                appendLine(summary)
                appendLine()
                DiagLog.snapshot().forEach { appendLine(it) }
            }
            // tmp + rename = atomic; a half-written record is never surfaced.
            val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(body)
            tmp.renameTo(File(ctx.filesDir, FILE_NAME))
        }
    }

    /** The stored record, or null when no shot has completed on this install. */
    fun snapshot(ctx: Context): String? =
        runCatching {
            File(ctx.filesDir, FILE_NAME).takeIf { it.exists() }?.readText()
        }.getOrNull()?.takeIf { it.isNotBlank() }
}
