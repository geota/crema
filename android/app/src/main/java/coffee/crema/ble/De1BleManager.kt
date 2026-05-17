package coffee.crema.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import coffee.crema.core.CremaBridge
import coffee.crema.core.NotificationSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Minimal `BluetoothGatt`-based BLE manager for the Crema Android app.
 *
 * Responsibilities — and nothing more:
 *  1. Scan for a DE1 by the [De1Uuids.SERVICE] advertised service UUID.
 *  2. Connect, discover services.
 *  3. Subscribe to the StateInfo and ShotSample notify characteristics.
 *  4. On every notification, hand the raw bytes to [CremaBridge.onNotification]
 *     and forward the returned JSON `CoreOutput` string to [onCoreOutput].
 *
 * This began as Phase-0 proof-of-concept code: it serialises GATT operations
 * through a tiny hand-rolled queue (Android's GATT stack only tolerates one
 * outstanding descriptor/characteristic operation at a time) and does no
 * reconnection, bonding, MTU negotiation, or scale handling yet. Hardening the
 * BLE layer into a proper, tested component is the next step.
 *
 * Threading: all callbacks arrive on a binder thread; the bridge call and the
 * [onCoreOutput] dispatch happen there. [CremaBridge] is internally `Mutex`-
 * guarded, so concurrent calls are safe; UI consumers must hop to the main
 * thread themselves.
 */
class De1BleManager(
    private val context: Context,
    private val bridge: CremaBridge,
    /** Called with each raw JSON `CoreOutput` string the core returns. */
    private val onCoreOutput: (String) -> Unit,
    /** Called with human-readable status transitions for the UI log. */
    private val onStatus: (String) -> Unit,
) {
    enum class State { IDLE, SCANNING, CONNECTING, DISCOVERING, SUBSCRIBING, READY, DISCONNECTED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private var scanning = false

    /**
     * Records the live BLE session to a file for offline replay through the
     * Rust core. A debug aid: it never crashes the app if file IO fails.
     */
    private val recorder = BleSessionRecorder(context)

    /** Pending CCCD subscriptions; processed one at a time (see [subscribeNext]). */
    private val subscribeQueue = ArrayDeque<BluetoothGattCharacteristic>()

    /** A clock the core uses to age telemetry. SystemClock-based monotonic ms. */
    private fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

    // ---- Scan -------------------------------------------------------------

    @SuppressLint("MissingPermission") // caller guarantees BLUETOOTH_SCAN is granted
    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || scanner == null) {
            onStatus("Bluetooth is off or unavailable")
            return
        }
        if (scanning) return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        _state.value = State.SCANNING
        onStatus("Scanning for a DE1…")
        // The DE1 does not reliably advertise its 128-bit GATT service UUID, so
        // a service-UUID ScanFilter never matches it. Scan unfiltered and match
        // the advertised name instead — the same discovery rule the legacy
        // de1app uses (bluetooth.tcl `de1_ble_handler`).
        scanner.startScan(null, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
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
            // their advertised service UUIDs — so a DE1 that is absent, asleep,
            // or advertising an unexpected name is visible in Logcat.
            val services = result.scanRecord?.serviceUuids?.joinToString() ?: "none"
            Log.d(
                TAG,
                "Scan saw: name=${name ?: "(none)"} addr=${device.address} " +
                    "rssi=${result.rssi} services=[$services]",
            )
            if (name != null && isDe1Name(name)) {
                onStatus("Found DE1: $name (${device.address})")
                stopScan()
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _state.value = State.IDLE
            onStatus("Scan failed (code $errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun deviceName(device: BluetoothDevice): String? = device.name

    /**
     * Whether a scanned advertisement name identifies a DE1. The DE1 advertises
     * a name starting "DE1"; some units advertise "BENGLE". This mirrors the
     * legacy de1app discovery rule in `bluetooth.tcl` (`de1_ble_handler`).
     */
    private fun isDe1Name(name: String): Boolean {
        val upper = name.uppercase()
        return upper.startsWith(De1Uuids.ADVERTISED_NAME_PREFIX) || upper.startsWith("BENGLE")
    }

    // ---- Connect ----------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        _state.value = State.CONNECTING
        onStatus("Connecting…")
        gatt = device.connectGatt(
            context,
            /* autoConnect = */ false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
        )
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        recorder.stop()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        subscribeQueue.clear()
        bridge.reset()
        _state.value = State.DISCONNECTED
        onStatus("Disconnected")
    }

    // ---- GATT callbacks ---------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onStatus("Connected — discovering services…")
                    _state.value = State.DISCOVERING
                    // Begin recording the session for offline replay; surface
                    // the capture file path once so the user can find it.
                    recorder.start()
                    recorder.filePath?.let { onStatus("Recording session to $it") }
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onStatus("Connection lost (status $status)")
                    _state.value = State.DISCONNECTED
                    recorder.stop()
                    g.close()
                    if (gatt === g) gatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatus("Service discovery failed (status $status)")
                return
            }
            val service = g.getService(De1Uuids.SERVICE)
            if (service == null) {
                onStatus("DE1 GATT service not found on device")
                disconnect()
                return
            }
            // Queue the two notify characteristics the app observes.
            subscribeQueue.clear()
            listOf(De1Uuids.STATE_INFO, De1Uuids.SHOT_SAMPLE).forEach { uuid ->
                val ch = service.getCharacteristic(uuid)
                if (ch != null) {
                    subscribeQueue.add(ch)
                } else {
                    onStatus("Characteristic $uuid missing")
                }
            }
            _state.value = State.SUBSCRIBING
            onStatus("Subscribing to StateInfo + ShotSample…")
            subscribeNext(g)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            // One CCCD write completed; advance the queue.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatus("CCCD write failed (status $status)")
            }
            subscribeNext(g)
        }

        // API 33+ delivers the value as a parameter.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(characteristic.uuid, value)
        }

        // API <33 path — read the value off the characteristic. This overrides
        // the 2-arg callback, deprecated since API 33 — intentional, it is the
        // only callback the framework calls on API 31/32 (the Teclast).
        // DEPRECATION covers `characteristic.value`; OVERRIDE_DEPRECATION covers
        // overriding the deprecated callback itself.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value ?: ByteArray(0)
                handleNotification(characteristic.uuid, value)
            }
        }
    }

    // ---- Subscription queue ----------------------------------------------

    @SuppressLint("MissingPermission")
    private fun subscribeNext(g: BluetoothGatt) {
        val ch = subscribeQueue.poll()
        if (ch == null) {
            _state.value = State.READY
            onStatus("Ready — receiving DE1 notifications")
            return
        }
        // Enable local notification dispatch, then write the remote CCCD.
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(De1Uuids.CCCD)
        if (cccd == null) {
            onStatus("CCCD missing on ${ch.uuid}; skipping")
            subscribeNext(g)
            return
        }
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, enable)
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = enable
                g.writeDescriptor(cccd)
            }
        }
    }

    // ---- Core integration -------------------------------------------------

    /**
     * The FFI/BLE seam: raw GATT bytes -> [CremaBridge] -> JSON `CoreOutput`
     * -> UI.
     */
    private fun handleNotification(charUuid: java.util.UUID, data: ByteArray) {
        val source = when (charUuid) {
            De1Uuids.STATE_INFO -> NotificationSource.DE1_STATE
            De1Uuids.SHOT_SAMPLE -> NotificationSource.DE1_SHOT_SAMPLE
            De1Uuids.WATER_LEVELS -> NotificationSource.DE1_WATER_LEVELS
            else -> {
                Log.w(TAG, "Notification from unmapped characteristic $charUuid")
                return
            }
        }
        // Stamp once: the same value goes to the recorder and the core, so a
        // replayed capture decodes identically to the live session.
        val tMs = nowMs()
        recorder.recordInbound(source, data, tMs)
        // CremaBridge.onNotification returns the CoreOutput as a JSON string.
        val json = bridge.onNotification(source, data, tMs.toULong())
        onCoreOutput(json)
    }

    companion object {
        private const val TAG = "De1BleManager"
    }
}
