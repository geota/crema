package coffee.crema.spikeb.ble

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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import coffee.crema.core.CremaBridge
import coffee.crema.core.NotificationSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Minimal `BluetoothGatt`-based BLE manager for Spike B.
 *
 * Responsibilities — and nothing more:
 *  1. Scan for a DE1 by the [De1Uuids.SERVICE] advertised service UUID.
 *  2. Connect, discover services.
 *  3. Subscribe to the StateInfo and ShotSample notify characteristics.
 *  4. On every notification, hand the raw bytes to [CremaBridge.onNotification]
 *     and forward the returned JSON `CoreOutput` string to [onCoreOutput].
 *
 * This is throwaway spike code: it serialises GATT operations through a tiny
 * hand-rolled queue (Android's GATT stack only tolerates one outstanding
 * descriptor/characteristic operation at a time) and does no reconnection,
 * bonding, MTU negotiation, or scale handling. The real app's BLE layer will
 * be a proper, tested component.
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

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(De1Uuids.SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        _state.value = State.SCANNING
        onStatus("Scanning for a DE1…")
        scanner.startScan(listOf(filter), settings, scanCallback)
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
            val name = runCatching { deviceName(device) }.getOrNull() ?: "(unnamed)"
            Log.i(TAG, "Scan hit: $name / ${device.address}")
            // The service-UUID filter already narrows this to DE1s; the name
            // prefix is a belt-and-braces check.
            if (name.startsWith(De1Uuids.ADVERTISED_NAME_PREFIX) || name == "(unnamed)") {
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
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onStatus("Connection lost (status $status)")
                    _state.value = State.DISCONNECTED
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
            // Queue the two notify characteristics Spike B observes.
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

        // API <33 path — read the value off the characteristic.
        @Suppress("DEPRECATION")
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
     * The seam Spike B exists to prove: raw GATT bytes -> [CremaBridge] ->
     * JSON `CoreOutput` -> UI.
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
        // CremaBridge.onNotification returns the CoreOutput as a JSON string.
        val json = bridge.onNotification(source, data, nowMs().toULong())
        onCoreOutput(json)
    }

    companion object {
        private const val TAG = "De1BleManager"
    }
}
