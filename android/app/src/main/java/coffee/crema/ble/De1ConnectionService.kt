package coffee.crema.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import coffee.crema.R

/**
 * A `connectedDevice` foreground service that keeps Crema's process — and thus
 * the DE1 BLE link and its auto-reconnect loop — alive while the screen is off
 * (geota/crema#65).
 *
 * Without it, Android freezes a backgrounded process (Doze / app-standby), so a
 * DE1 that dropped or power-cycled while the screen was off would go unnoticed
 * and never reconnect until the user reopened the app. This service is purely a
 * keep-alive: it holds a partial wake lock and an ongoing notification; the
 * actual BLE work stays in [De1BleManager] / [NordicBleTransport] on the app's
 * own coroutine scopes, which keep running now that the process isn't frozen.
 *
 * [BackgroundConnectionController] owns when it runs — only while a DE1
 * connection is live/pursued, the user has opted in, AND the device is on
 * external power (so a battery device is never drained). It is not sticky and
 * stops itself if the app is swiped away, so "quit means quit".
 */
class De1ConnectionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background connection",
            NotificationManager.IMPORTANCE_LOW, // silent — no sound / vibration
        ).apply {
            description = "Keeps your DE1 connected while the screen is off."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val firmware = intent?.getBooleanExtra(EXTRA_FIRMWARE, false) == true
        // Always go foreground first: a service started with
        // startForegroundService() that stops before calling startForeground()
        // crashes the app on Android 14+ (ForegroundServiceDidNotStartInTime).
        startForeground(
            NOTIF_ID,
            buildNotification(this, firmware),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        inForeground = true
        // The controller may have changed its mind while this start was in
        // flight (e.g. the DE1 link paused right after the setting turned on):
        // it leaves the stop to us, now that the promise is kept.
        if (!wanted) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        // NOT sticky: if the OS reclaims us, the controller re-decides on the next
        // app launch rather than restarting a bare keep-alive on its own.
        return START_NOT_STICKY
    }

    /** The user swiped Crema out of recents → quit means quit: stop keeping the
     *  process alive (and let START_NOT_STICKY prevent any restart). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    override fun onDestroy() {
        inForeground = false
        releaseWakeLock()
        super.onDestroy()
    }

    // No timeout on purpose: the lock lives exactly as long as this service
    // (released in onDestroy), and the controller stops the service whenever
    // its gates drop (setting, DE1 link, external power). A timeout would
    // silently end the keep-alive on a wall tablet mid-day.
    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        // A denied WAKE_LOCK (OEM AppOps quirk) must degrade to "no wake lock",
        // not crash the app (geota/crema#72) — the foreground service alone
        // still keeps the process unfrozen.
        wakeLock = runCatching {
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
            .onFailure { Log.w(TAG, "wake lock unavailable — continuing without it", it) }
            .getOrNull()
        Log.i(TAG, "background-connection service started — wake lock ${if (wakeLock != null) "held" else "unavailable"}")
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            .onFailure { Log.w(TAG, "wake lock release failed", it) }
        wakeLock = null
        Log.i(TAG, "background-connection service stopped")
    }

    companion object {
        /**
         * Whether [BackgroundConnectionController] still wants the service.
         * Main thread only (the controller and these callbacks both run there).
         */
        @Volatile
        internal var wanted = false

        /** The running service has called startForeground() — only then may
         *  the controller stop it directly. */
        @Volatile
        internal var inForeground = false

        /**
         * Swap the RUNNING service's notification text (firmware flash on/off)
         * in place — same id, so it replaces the foreground notification. A
         * no-op when the service isn't foreground (its next start reads the
         * flag from the start intent instead).
         */
        // Crema doesn't request POST_NOTIFICATIONS; without it Android 13+
        // silently drops this post, exactly as it hides the notification
        // startForeground() shows. The service keeps running either way.
        @android.annotation.SuppressLint("NotificationPermission")
        fun updateNotification(context: Context, firmware: Boolean) {
            if (!inForeground) return
            runCatching {
                context.getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotification(context, firmware))
            }.onFailure { Log.w(TAG, "notification update failed", it) }
        }

        internal fun buildNotification(context: Context, firmware: Boolean): Notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_bluetooth)
                .setContentTitle(if (firmware) "Updating DE1 firmware" else "Crema is keeping your DE1 connected")
                .setContentText(
                    if (firmware) {
                        "Keep the DE1 nearby. If it stops, the DE1 keeps its current firmware."
                    } else {
                        "Reconnects the machine even while the screen is off."
                    },
                )
                .setContentIntent(
                    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                        android.app.PendingIntent.getActivity(
                            context,
                            0,
                            launch,
                            android.app.PendingIntent.FLAG_IMMUTABLE,
                        )
                    },
                )
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()

        /** `true` while a firmware flash runs — switches the notification text. */
        const val EXTRA_FIRMWARE = "coffee.crema.extra.FIRMWARE"
        private const val TAG = "De1ConnectionService"
        private const val CHANNEL_ID = "de1_background_connection"
        private const val NOTIF_ID = 4265
        private const val WAKE_TAG = "crema:de1-connection"
    }
}
