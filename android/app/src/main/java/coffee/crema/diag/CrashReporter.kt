package coffee.crema.diag

import android.content.Context
import android.os.Build
import coffee.crema.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.system.exitProcess

/**
 * Captures uncaught JVM/Kotlin exceptions — including Rust panics that UniFFI
 * re-throws as Kotlin exceptions — into a plain-text report the user can copy or
 * share. No auto-upload; sharing is always user-initiated (matches the Terms).
 *
 * A pure native crash (a SIGSEGV in the BLE `.so`) can't be caught here — the
 * process is simply gone; [SafeMode]'s boot marker breaks that loop, and Play
 * Vitals / `adb logcat` carry the native trace.
 */
object CrashReporter {
    private fun dir(ctx: Context) = File(ctx.filesDir, "diag").apply { mkdirs() }
    private fun reportFile(ctx: Context) = File(dir(ctx), "last-crash.txt")
    private fun rustLog(ctx: Context) = File(dir(ctx), "rust-panic.log")

    /** The path the Rust panic hook appends to — see `installPanicLogger`. */
    fun rustPanicLogPath(ctx: Context): String = rustLog(ctx).absolutePath

    /** Install the default uncaught-exception handler. Call once, early. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(app, thread, throwable) } // never throw from the handler
            // Let the OS still crash normally so Android (and Play Vitals) see it.
            if (prev != null) prev.uncaughtException(thread, throwable) else exitProcess(2)
        }
    }

    /** The pending crash report, or null when the last session exited cleanly. */
    fun pending(ctx: Context): String? =
        reportFile(ctx).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    /** Clear the report + the Rust panic log once the user has seen them. */
    fun clear(ctx: Context) {
        runCatching { reportFile(ctx).delete() }
        runCatching { rustLog(ctx).writeText("") }
    }

    /**
     * A user-shareable diagnostics snapshot built on demand — for a bug report
     * when the app *misbehaved* but didn't crash (so there's no pending report).
     * Same shape as a crash report, minus the exception. No personal data.
     */
    fun diagnostics(ctx: Context): String =
        composeReport(ctx, title = "diagnostics", trigger = "manual snapshot", exception = null)

    private fun writeReport(ctx: Context, thread: Thread, e: Throwable) {
        val report = composeReport(
            ctx,
            title = "crash report",
            trigger = "uncaught exception on thread \"${thread.name}\"",
            exception = e.stackTraceToString(),
        )
        // tmp + rename = atomic; a half-written report is never surfaced.
        val tmp = File(dir(ctx), "last-crash.txt.tmp")
        tmp.writeText(report)
        tmp.renameTo(reportFile(ctx))
    }

    /** The shared report body — a crash passes its [exception]; a manual snapshot doesn't. */
    private fun composeReport(ctx: Context, title: String, trigger: String, exception: String?): String {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        val rust = rustLog(ctx)
            .takeIf { it.exists() }
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        val recent = DiagLog.snapshot()
        return buildString {
            appendLine("=== Crema $title ===")
            appendLine("app:     ${BuildConfig.VERSION_NAME} (sha ${BuildConfig.GIT_SHA}, ${BuildConfig.BUILD_TYPE})")
            appendLine(
                "device:  ${Build.MODEL} · ${Build.MANUFACTURER} · " +
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            )
            appendLine("time:    $ts")
            appendLine("trigger: $trigger")
            if (exception != null) {
                appendLine()
                appendLine("-- exception --")
                appendLine(exception.trim())
            }
            if (rust != null) {
                appendLine()
                appendLine("-- rust panic --")
                appendLine(rust.trim())
            }
            appendLine()
            appendLine("-- recent log (last ${recent.size} lines) --")
            recent.forEach { appendLine(it) }
        }
    }
}
