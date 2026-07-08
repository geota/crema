package coffee.crema.ble

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The shared connect → (drop → reconnect)* supervisor for the BLE device
 * managers.
 *
 * [De1BleManager.session] and [ScaleBleManager.session] were structurally
 * byte-identical — the same `while (true) establish → catch-cancellation →
 * READY → await-drop → userInitiated / autoReconnect / backoff` loop with the
 * same `finally` teardown — and their `MAX_RECONNECT_ATTEMPTS` / `backoffMs`
 * were copy-pasted (and the two `finally` blocks had already silently diverged:
 * the scale cleared its UUIDs + called `disconnectScale()`, the DE1 did not).
 * Both now delegate here, passing only their device-specific deltas, so a fix
 * to the reconnect contract lands in both at once.
 *
 * The caller still owns every piece of device state (the handle, the recorder
 * ref-count, its own `State` enum, the status sink); this only drives the
 * control flow and calls back into the deltas:
 *  - [establish] — bring the link up for one attempt; `firstConnect` is false on
 *    a reconnect. Throws on failure so the loop backs off. A
 *    [CancellationException] always propagates (a user disconnect / scope
 *    cancel), never swallowed.
 *  - [awaitDrop] — suspend until the link is gone (`FAILED` / `DISCONNECTED`).
 *  - [onConnected] — after a successful establish: move to READY + emit the
 *    device's ready status (and, for the DE1, fire the connect-time seed reads).
 *  - [onConnecting] — move to CONNECTING before a backoff retry.
 *  - [teardown] — the finally-block cleanup, run ONLY when the session ends on a
 *    non-user path (gave up / auto-reconnect off / a cancel that isn't
 *    `disconnect()`). A user `disconnect()` does its own teardown, guarded by
 *    [isUserInitiated].
 *
 * Status lines are templated from [label] ("DE1" / "Scale") so the two devices
 * read consistently; [logTag] tags the connect-failure warning.
 */
internal suspend fun reconnectingSession(
    label: String,
    logTag: String,
    status: (String) -> Unit,
    isUserInitiated: () -> Boolean,
    isAutoReconnectEnabled: () -> Boolean,
    establish: suspend (firstConnect: Boolean) -> Unit,
    awaitDrop: suspend () -> Unit,
    onConnected: () -> Unit,
    onConnecting: () -> Unit,
    teardown: () -> Unit,
) {
    var attempt = 0
    var everConnected = false
    var lurking = false
    try {
        while (true) {
            val connected = try {
                establish(!everConnected)
                true
            } catch (c: CancellationException) {
                throw c // never swallow cancellation (disconnect / onCleared)
            } catch (e: Exception) {
                Log.w(logTag, "$label connect failed", e)
                status("$label connection failed: ${e.message}")
                false
            }

            if (connected) {
                everConnected = true
                attempt = 0
                lurking = false
                onConnected()
                // Suspend until the link drops. The caller's StateFlow.first()
                // checks the current value first, so a drop during the tiny
                // post-establish gap is not missed.
                awaitDrop()
                if (isUserInitiated()) return
                if (!isAutoReconnectEnabled()) {
                    status("$label disconnected")
                    break
                }
                status("$label connection lost — reconnecting…")
            } else {
                if (isUserInitiated()) return
                if (!isAutoReconnectEnabled()) break
            }

            // Clamped so days of slow retries can't overflow the counter
            // (Decenza clamps its ladder counter the same way).
            attempt = (attempt + 1).coerceAtMost(MAX_RECONNECT_ATTEMPTS + 1)
            if (attempt > MAX_RECONNECT_ATTEMPTS) {
                // The fast burst is exhausted — drop to a persistent slow tier
                // instead of stranding an armed device (de1app retries forever;
                // Decenza's ladders end in a 60 s / 5 min forever-tier). The
                // device coming back in range / powering on reconnects without
                // a user tap; a user disconnect or auto-reconnect OFF still
                // exits above. Announced once — the quiet retries don't spam
                // the status line every cycle.
                if (!lurking) {
                    lurking = true
                    status("$label not found — retrying quietly in the background")
                }
                onConnecting()
                delay(LURK_INTERVAL_MS)
            } else {
                onConnecting()
                status("Reconnecting to $label (attempt $attempt of $MAX_RECONNECT_ATTEMPTS)…")
                delay(backoffMs(attempt))
            }
        }
    } finally {
        if (!isUserInitiated()) teardown()
    }
}

/** Fast-burst reconnect attempts after an unexpected drop (~web's 8), before
 *  the persistent slow tier takes over. */
private const val MAX_RECONNECT_ATTEMPTS = 8

/** Cadence of the persistent slow tier — Decenza's scale ladder ends in the
 *  same 60 s forever-tier (its DE1 uses 5 min; a single shared value keeps
 *  this supervisor device-agnostic, and a pending direct connect between
 *  tries is cheap — it's the same background-scan mechanism as autoConnect). */
private const val LURK_INTERVAL_MS = 60_000L

/**
 * Exponential backoff: 500 ms doubling, capped at 30 s (mirrors the web).
 * [ReconnectingClientLink] keeps its own tighter 5 s cap, intentionally.
 */
private fun backoffMs(attempt: Int): Long =
    (500L shl (attempt - 1).coerceIn(0, 10)).coerceAtMost(30_000L)
