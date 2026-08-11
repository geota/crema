package coffee.crema.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Decides when the [De1ConnectionService] keep-alive runs (geota/crema#65).
 *
 * The service runs only when ALL of these hold:
 *  - [setEnabled] — the user's "keep connected while the screen is off" setting
 *    is effectively on (form-factor default: tablet on / phone off, overridable);
 *  - [setDe1Active] — a DE1 connection is live or being pursued, so there is
 *    something to keep alive; and
 *  - the device is on external power — a battery device is never drained and
 *    never shows the notification unless it's charging (the "on when connected
 *    to a power source" behaviour).
 *
 * A foreground service may only be STARTED from the foreground on Android 12+, so
 * a start is deferred until [setForeground]`(true)`; a stop is allowed any time.
 * In the target case the app starts the service while its screensaver is up
 * (screen on ⇒ foreground) and it then survives the screen turning off. Power
 * changes are tracked live via ACTION_POWER_CONNECTED / _DISCONNECTED, so
 * unplugging tears the service down even while backgrounded.
 *
 * Single-threaded: every mutator is called on the main thread from
 * `MainViewModel`, and the power receiver is delivered on the main thread too.
 */
class BackgroundConnectionController(private val context: Context) {

    private var enabled = false
    private var de1Active = false
    private var foreground = true
    private var plugged = isPluggedIn(context)
    private var running = false

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            plugged = when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> true
                Intent.ACTION_POWER_DISCONNECTED -> false
                else -> plugged
            }
            reconcile()
        }
    }

    /** Register the power-state watcher and evaluate once. */
    fun start() {
        ContextCompat.registerReceiver(
            context,
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        plugged = isPluggedIn(context)
        reconcile()
    }

    /** Unregister and ensure the service is stopped — from `onCleared`. */
    fun close() {
        runCatching { context.unregisterReceiver(powerReceiver) }
        stopService()
    }

    fun setEnabled(value: Boolean) {
        if (enabled != value) { enabled = value; reconcile() }
    }

    fun setDe1Active(value: Boolean) {
        if (de1Active != value) { de1Active = value; reconcile() }
    }

    fun setForeground(value: Boolean) {
        if (foreground != value) { foreground = value; reconcile() }
    }

    private fun reconcile() {
        val desired = enabled && de1Active && plugged
        when {
            // Foreground-only start (Android 12+ background-start restriction);
            // in the target case the app is foreground on its screensaver.
            desired && !running && foreground -> startService()
            !desired && running -> stopService()
        }
    }

    private fun startService() {
        running = true
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, De1ConnectionService::class.java),
            )
        }.onFailure {
            running = false
            Log.w(TAG, "could not start background-connection service", it)
        }
    }

    private fun stopService() {
        running = false
        runCatching { context.stopService(Intent(context, De1ConnectionService::class.java)) }
    }

    private companion object {
        const val TAG = "BgConnController"

        /** Whether the device is currently on external power (AC / USB / wireless). */
        fun isPluggedIn(context: Context): Boolean {
            val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        }
    }
}
