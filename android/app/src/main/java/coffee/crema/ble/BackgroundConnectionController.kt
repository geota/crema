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
class BackgroundConnectionController(
    private val context: Context,
    /** The service seam — the real [De1ConnectionService] by default; a fake in tests. */
    private val service: KeepAliveService = AndroidKeepAliveService(context),
) {

    private var enabled = false
    private var de1Active = false
    private var brewSessionActive = false
    private var firmwareFlashActive = false
    private var foreground = true
    private var backgroundStartAllowed = false
    private var plugged = isPluggedIn(context)
    private var running = false

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            onPowerChanged(
                when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> true
                    Intent.ACTION_POWER_DISCONNECTED -> false
                    else -> plugged
                },
            )
        }
    }

    /** External power came or went (the receiver above; tests call it directly). */
    internal fun onPowerChanged(value: Boolean) {
        plugged = value
        reconcile()
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

    /**
     * A guided brew session is live (issue #10) — the scale link is the
     * session's telemetry, so it gets the same keep-alive treatment as a
     * DE1 connection: a backgrounded phone must not drop the scale
     * mid-pourover. Unlike the DE1 leg this is NOT gated on the user's
     * keep-connected setting — a running session is explicit intent —
     * but the charging gate still applies (never drain a battery
     * device from the background).
     */
    fun setBrewSessionActive(value: Boolean) {
        if (brewSessionActive != value) { brewSessionActive = value; reconcile() }
    }

    /**
     * A DE1 firmware flash is running (≈15–25 min). Like a brew session it is
     * explicit intent, so it ignores the keep-connected setting — and unlike
     * every other leg it also ignores the charging gate: the flash's own
     * precondition already required charging or ≥ 50 % battery, and losing
     * the process mid-flash wastes the upload. The notification says so.
     */
    fun setFirmwareFlashActive(value: Boolean) {
        if (firmwareFlashActive != value) {
            firmwareFlashActive = value
            // Switch a running service's notification text IN PLACE. Never by
            // restarting it: a flash ends 15–25 min in, often with the app in
            // the background, where a second startForegroundService is refused
            // and the failure path used to forget a service that was still
            // running with its wake lock (cohort review 2026-10-25, P2-2).
            if (running) service.updateNotification(firmware = value)
            reconcile()
        }
    }

    fun setForeground(value: Boolean) {
        if (foreground != value) { foreground = value; reconcile() }
    }

    /**
     * A companion-device association exists (and the manifest declares
     * `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`): Android
     * then exempts the app from the Android 12 background-FGS-start ban, so
     * the keep-alive may start from the background too — still only when its
     * own gates (setting / brew session, DE1 active, charging) hold.
     */
    fun setBackgroundStartAllowed(value: Boolean) {
        if (backgroundStartAllowed != value) { backgroundStartAllowed = value; reconcile() }
    }

    /** A companion device appeared: start the keep-alive now if it is wanted. */
    fun onCompanionPresence() = reconcile()

    private fun reconcile() {
        val desired = firmwareFlashActive || (((enabled && de1Active) || brewSessionActive) && plugged)
        when {
            // Foreground-only start (Android 12+ background-start restriction) —
            // unless a companion association grants the background exemption;
            // in the target case the app is foreground on its screensaver.
            desired && !running && (foreground || backgroundStartAllowed) -> startService()
            !desired && running -> stopService()
        }
    }

    private fun startService() {
        running = true
        service.wanted = true
        if (!service.start(firmware = firmwareFlashActive)) {
            // A refused start only means "not running" when no earlier
            // instance is still foreground; otherwise keep tracking it, so a
            // later gate change still stops it (and releases its wake lock).
            if (!service.inForeground) {
                running = false
                service.wanted = false
            }
        }
    }

    /**
     * Stop the keep-alive. A start still in flight (startForegroundService()
     * called, startForeground() not yet) must not be stopped from here —
     * Android 14+ kills the app for that (ForegroundServiceDidNotStartInTime).
     * The service then stops itself right after going foreground, because
     * [De1ConnectionService.wanted] is false.
     */
    private fun stopService() {
        running = false
        service.wanted = false
        if (!service.inForeground) return
        service.stop()
    }

    internal companion object {
        const val TAG = "BgConnController"

        /** Whether the device is currently on external power (AC / USB / wireless). */
        fun isPluggedIn(context: Context): Boolean {
            val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        }
    }
}

/** What [BackgroundConnectionController] needs from the keep-alive service. */
interface KeepAliveService {
    /** The controller still wants the service (read by it on start). */
    var wanted: Boolean

    /** The running service has called startForeground(). */
    val inForeground: Boolean

    /** Ask for a foreground start; false when the platform refused it. */
    fun start(firmware: Boolean): Boolean

    fun stop()

    /** Swap the running service's notification text in place. */
    fun updateNotification(firmware: Boolean)
}

/** The real [De1ConnectionService]. */
private class AndroidKeepAliveService(private val context: Context) : KeepAliveService {
    override var wanted: Boolean
        get() = De1ConnectionService.wanted
        set(value) { De1ConnectionService.wanted = value }

    override val inForeground: Boolean get() = De1ConnectionService.inForeground

    override fun start(firmware: Boolean): Boolean = runCatching {
        ContextCompat.startForegroundService(
            context,
            Intent(context, De1ConnectionService::class.java)
                .putExtra(De1ConnectionService.EXTRA_FIRMWARE, firmware),
        )
    }.onFailure { Log.w(BackgroundConnectionController.TAG, "could not start background-connection service", it) }
        .isSuccess

    override fun stop() {
        runCatching { context.stopService(Intent(context, De1ConnectionService::class.java)) }
    }

    override fun updateNotification(firmware: Boolean) = De1ConnectionService.updateNotification(context, firmware)
}
