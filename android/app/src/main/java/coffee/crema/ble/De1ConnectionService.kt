package coffee.crema.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
        startForeground(
            NOTIF_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
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
        releaseWakeLock()
        super.onDestroy()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bluetooth)
            .setContentTitle("Crema is keeping your DE1 connected")
            .setContentText("Reconnects the machine even while the screen is off.")
            .setContentIntent(
                packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                    android.app.PendingIntent.getActivity(
                        this,
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

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        Log.i(TAG, "background-connection service started — wake lock held")
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            .onFailure { Log.w(TAG, "wake lock release failed", it) }
        wakeLock = null
        Log.i(TAG, "background-connection service stopped")
    }

    private companion object {
        const val TAG = "De1ConnectionService"
        const val CHANNEL_ID = "de1_background_connection"
        const val NOTIF_ID = 4265
        const val WAKE_TAG = "crema:de1-connection"
    }
}
