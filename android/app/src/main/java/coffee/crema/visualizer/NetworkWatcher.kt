package coffee.crema.visualizer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Tells the Visualizer retry queue when a network becomes available (F17).
 * Registered only while the app is in the foreground ([start] / [stop]).
 */
interface NetworkWatcher {
    /** Call [onAvailable] whenever the default network becomes available. */
    fun start(onAvailable: () -> Unit)

    /** Stop listening. */
    fun stop()
}

/** [NetworkWatcher] over `ConnectivityManager`'s default-network callback. */
class DefaultNetworkWatcher(context: Context) : NetworkWatcher {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    override fun start(onAvailable: () -> Unit) {
        if (callback != null || connectivity == null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onAvailable()
        }
        runCatching { connectivity.registerDefaultNetworkCallback(cb) }.onSuccess { callback = cb }
    }

    @Synchronized
    override fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching { connectivity?.unregisterNetworkCallback(cb) }
    }
}
