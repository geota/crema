package coffee.crema.diag

import android.content.Context
import coffee.crema.settings.SettingsStore
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Breaks the startup crash *loop*. A saved DE1/scale drives an auto-connect on
 * launch; if that crashes — including a native crash [CrashReporter] can't catch
 * — the app re-crashes every launch and the user is bricked. A boot marker
 * written before the risky init and cleared once the app runs stably lets the
 * next launch detect "the last boot didn't stabilise" and pause auto-connect
 * behind the recovery screen so the user can reset the saved device.
 */
object SafeMode {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // Evaluate the marker once per PROCESS, so a config-change re-`onCreate`
    // (rotation) doesn't re-trigger safe mode after the user dismissed it.
    @Volatile private var bootEvaluated = false
    @Volatile private var wasStalled = false

    private fun dir(ctx: Context) = File(ctx.filesDir, "diag").apply { mkdirs() }
    private fun marker(ctx: Context) = File(dir(ctx), "boot-in-progress")

    /**
     * Call once, early in `onCreate`, before any auto-connect. Returns true when
     * the *previous* boot wrote a marker it never cleared — i.e. it crashed (in
     * any way) before stabilising. (Re)writes the marker for this boot.
     */
    fun beginBoot(ctx: Context): Boolean {
        if (bootEvaluated) return wasStalled
        val f = marker(ctx)
        wasStalled = f.exists()
        runCatching { f.writeText(System.currentTimeMillis().toString()) }
        bootEvaluated = true
        return wasStalled
    }

    /** Call once the app has run stably (a few seconds past the first frame). */
    fun bootStable(ctx: Context) {
        runCatching { marker(ctx).delete() }
    }

    /**
     * The "Reset device" unbrick: clear the saved DE1 + scale so the next launch
     * doesn't auto-connect, preserving every other pref. Suspends (file IO) — it
     * must complete before the ViewModel reads prefs on construction.
     */
    suspend fun resetDevices(ctx: Context) {
        val store = SettingsStore(ctx.applicationContext, json)
        val prefs = store.load()
        store.save(prefs.copy(de1Address = null, scaleAddress = null))
    }
}
