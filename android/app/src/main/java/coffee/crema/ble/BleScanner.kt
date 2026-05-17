package coffee.crema.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log

/**
 * The app's single shared `BluetoothLeScanner`.
 *
 * The DE1 and the Bookoo scale are both discovered by an unfiltered
 * `SCAN_MODE_LOW_LATENCY` scan plus an advertised-name match — neither device
 * reliably advertises its 128-bit GATT service UUID, so a service-UUID
 * `ScanFilter` never matches (this mirrors the legacy de1app discovery rule in
 * `bluetooth.tcl`). Running two concurrent scans — one per manager — is the
 * problem this class fixes: it owns the one scanner, runs at most one scan, and
 * dispatches each result to whichever device currently wants to connect.
 *
 * A "want" is registered with [scanFor] and identified by a [String] label. A
 * scan runs while at least one want is outstanding and is stopped once none
 * remain. On a match the want's `onFound` fires once and the want is dropped;
 * [cancel] drops a want without a match (e.g. on disconnect).
 *
 * Threading: [ScanCallback] arrives on a binder thread, so the wants list is
 * synchronised. `onFound` and `onStatus` dispatch happen on that thread, the
 * same as the managers' GATT callbacks; UI consumers hop to the main thread
 * themselves.
 */
class BleScanner(
    private val context: Context,
    /** Called with human-readable status transitions for the UI log. */
    private val onStatus: (String) -> Unit,
) {
    /** A single registered discovery request. */
    private class Want(
        val label: String,
        val matches: (name: String) -> Boolean,
        val onFound: (device: BluetoothDevice, name: String) -> Unit,
    )

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    /** Outstanding wants, keyed by label. Guarded by `this`. */
    private val wants = LinkedHashMap<String, Want>()
    private var scanning = false

    /**
     * Register a discovery request. The unfiltered scan starts if it is not
     * already running. When a scanned advertisement's name satisfies [matches],
     * [onFound] is invoked once with the device and its advertised name and the
     * request is dropped; if no want remains the scan stops. Re-registering the
     * same [label] replaces the previous request.
     */
    @SuppressLint("MissingPermission") // caller guarantees BLUETOOTH_SCAN is granted
    fun scanFor(
        label: String,
        matches: (name: String) -> Boolean,
        onFound: (device: BluetoothDevice, name: String) -> Unit,
    ) {
        synchronized(this) {
            wants[label] = Want(label, matches, onFound)
        }
        startScan()
    }

    /**
     * Drop the want registered under [label] (e.g. on disconnect). If no want
     * remains the scan stops. A no-op if the label is not registered.
     */
    @SuppressLint("MissingPermission")
    fun cancel(label: String) {
        val empty: Boolean
        synchronized(this) {
            wants.remove(label)
            empty = wants.isEmpty()
        }
        if (empty) stopScan()
    }

    // ---- Scan -------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || scanner == null) {
            onStatus("Bluetooth is off or unavailable")
            return
        }
        synchronized(this) {
            if (scanning) return
            scanning = true
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // The DE1 and Bookoo scale do not reliably advertise their 128-bit GATT
        // service UUIDs, so a service-UUID ScanFilter never matches them. Scan
        // unfiltered and match the advertised name instead — the same discovery
        // rule the legacy de1app uses (bluetooth.tcl `de1_ble_handler`).
        scanner.startScan(null, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        synchronized(this) {
            if (!scanning) return
            scanning = false
        }
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            // Prefer the name carried in the advertisement / scan-response;
            // fall back to the device's cached name.
            val name = result.scanRecord?.deviceName
                ?: runCatching { deviceName(device) }.getOrNull()
            // Debug: log every advertisement seen — including nameless ones and
            // their advertised service UUIDs — so a device that is absent,
            // asleep, or advertising an unexpected name is visible in Logcat.
            val services = result.scanRecord?.serviceUuids?.joinToString() ?: "none"
            Log.d(
                TAG,
                "Scan saw: name=${name ?: "(none)"} addr=${device.address} " +
                    "rssi=${result.rssi} services=[$services]",
            )
            if (name == null) return

            // Collect every outstanding want this advertisement satisfies, then
            // drop them, before firing — so `onFound` runs outside the lock and
            // a re-entrant scanFor/cancel cannot deadlock.
            val matched: List<Want>
            val empty: Boolean
            synchronized(this@BleScanner) {
                matched = wants.values.filter { it.matches(name) }
                matched.forEach { wants.remove(it.label) }
                empty = wants.isEmpty()
            }
            if (matched.isEmpty()) return
            if (empty) stopScan()
            matched.forEach { it.onFound(device, name) }
        }

        override fun onScanFailed(errorCode: Int) {
            synchronized(this@BleScanner) {
                scanning = false
                wants.clear()
            }
            onStatus("Scan failed (code $errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun deviceName(device: BluetoothDevice): String? = device.name

    private companion object {
        const val TAG = "BleScanner"
    }
}
