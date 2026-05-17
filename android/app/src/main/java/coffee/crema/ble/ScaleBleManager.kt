package coffee.crema.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
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
 * Minimal `BluetoothGatt`-based BLE manager for a Bookoo coffee scale.
 *
 * A close sibling of [De1BleManager] — same threading model, same one-
 * outstanding-GATT-op subscribe queue, same API-33-aware notification and
 * write branches. Responsibilities:
 *  1. Connect to a Bookoo scale [BluetoothDevice], discover services, and tell
 *     the core which scale it is via [CremaBridge.connectScale] so the core
 *     selects the right codec.
 *  2. Subscribe to the weight-notify characteristic ([ScaleUuids.WEIGHT_NOTIFY]).
 *  3. On every weight notification, hand the raw bytes to
 *     [CremaBridge.onNotification] and forward the returned JSON `CoreOutput`
 *     string to [onCoreOutput].
 *  4. Write tare / timer commands to [ScaleUuids.COMMAND] via [writeCommand].
 *
 * Device discovery is not this manager's job: the shared [BleScanner] runs the
 * one app-wide scan and hands a matched scale to [connect]. The "is this a
 * Bookoo" name rule still belongs with the device — see [isBookooName].
 *
 * Unlike [De1BleManager] this manager does no session recording — that is a
 * deliberate later follow-up.
 *
 * The scale connection is independent of the DE1: a user can connect a scale
 * with or without a DE1. Two simultaneous `BluetoothGatt` connections are fine,
 * and [CremaBridge] is internally `Mutex`-guarded, so both managers feeding it
 * concurrently is safe.
 *
 * Threading: all callbacks arrive on a binder thread; the bridge call and the
 * [onCoreOutput] dispatch happen there. UI consumers must hop to the main
 * thread themselves.
 */
class ScaleBleManager(
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

    private var gatt: BluetoothGatt? = null

    /** The scale's command characteristic, resolved after service discovery. */
    private var commandCharacteristic: BluetoothGattCharacteristic? = null

    /** Pending CCCD subscriptions; processed one at a time (see [subscribeNext]). */
    private val subscribeQueue = ArrayDeque<BluetoothGattCharacteristic>()

    /** A clock the core uses to age telemetry. SystemClock-based monotonic ms. */
    private fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

    // ---- Connect ----------------------------------------------------------

    /** The advertised name of the device being connected, for [CremaBridge.connectScale]. */
    private var pendingAdvertisedName: String? = null

    /**
     * Move the manager into [State.SCANNING]. Called by the owner when it hands
     * the discovery request to the shared [BleScanner], so the connection-status
     * UI shows "scanning" while the scanner hunts — the scanner has no per-device
     * [State] of its own.
     */
    fun markScanning() {
        _state.value = State.SCANNING
        onStatus("Scanning for a Bookoo scale…")
    }

    /**
     * Connect to a Bookoo scale the shared [BleScanner] has matched. This is the
     * scanner's `onFound` entry point — discovery happens upstream; this manager
     * owns only the GATT connection from here on. [advertisedName] is the name
     * the scanner resolved from the advertisement / scan-response; it is passed
     * to [CremaBridge.connectScale] to pick the codec. `device.name` is not used
     * — it can be null until the connection completes.
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, advertisedName: String) {
        _state.value = State.CONNECTING
        pendingAdvertisedName = advertisedName
        onStatus("Connecting to scale…")
        gatt = device.connectGatt(
            context,
            /* autoConnect = */ false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
        )
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        commandCharacteristic = null
        pendingAdvertisedName = null
        subscribeQueue.clear()
        _state.value = State.DISCONNECTED
        onStatus("Scale disconnected")
    }

    // ---- GATT callbacks ---------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onStatus("Scale connected — discovering services…")
                    _state.value = State.DISCOVERING
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onStatus("Scale connection lost (status $status)")
                    _state.value = State.DISCONNECTED
                    commandCharacteristic = null
                    g.close()
                    if (gatt === g) gatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatus("Scale service discovery failed (status $status)")
                return
            }
            val service = g.getService(ScaleUuids.SERVICE)
            if (service == null) {
                onStatus("Bookoo GATT service not found on device")
                disconnect()
                return
            }
            // Identify the scale with the core so it selects the right codec.
            val advertisedName = pendingAdvertisedName
            if (advertisedName != null) {
                val label = runCatching { bridge.connectScale(advertisedName) }
                    .getOrNull()
                if (label != null) {
                    onStatus("Core recognised scale: $label")
                } else {
                    onStatus("Core did not recognise scale '$advertisedName'")
                }
            }
            // Resolve the command characteristic for later tare / timer writes.
            commandCharacteristic = service.getCharacteristic(ScaleUuids.COMMAND)
            if (commandCharacteristic == null) {
                onStatus("Scale command characteristic ${ScaleUuids.COMMAND} missing")
            }
            // Queue the weight-notify characteristic the app observes.
            subscribeQueue.clear()
            val notify = service.getCharacteristic(ScaleUuids.WEIGHT_NOTIFY)
            if (notify != null) {
                subscribeQueue.add(notify)
            } else {
                onStatus("Scale weight characteristic ${ScaleUuids.WEIGHT_NOTIFY} missing")
            }
            _state.value = State.SUBSCRIBING
            onStatus("Subscribing to scale weight…")
            subscribeNext(g)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            // One CCCD write completed; advance the queue.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatus("Scale CCCD write failed (status $status)")
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
            onStatus("Ready — receiving scale weight")
            return
        }
        // Enable local notification dispatch, then write the remote CCCD.
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(ScaleUuids.CCCD)
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

    // ---- Command write ----------------------------------------------------

    /**
     * Write [data] to the Bookoo command characteristic ([ScaleUuids.COMMAND]).
     *
     * Used for tare (and, later, timer) commands. The exact bytes come from the
     * Rust core via a `Command.WriteScale`; this manager owns no protocol.
     * Returns true if the write was dispatched, false if the scale is not
     * connected or the characteristic is unavailable.
     */
    @SuppressLint("MissingPermission")
    fun writeCommand(data: ByteArray): Boolean {
        val g = gatt
        val ch = commandCharacteristic
        if (g == null || ch == null) {
            onStatus("Cannot write scale command — scale not connected")
            return false
        }
        // API-33-aware characteristic write, mirroring the descriptor-write
        // branch in [subscribeNext]: the 3-arg form on API 33+, the deprecated
        // value-then-write form on API 31/32.
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                ch,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = data
                g.writeCharacteristic(ch)
            }
        }
        if (!ok) onStatus("Scale command write was rejected")
        return ok
    }

    // ---- Core integration -------------------------------------------------

    /**
     * The FFI/BLE seam: raw GATT bytes -> [CremaBridge] -> JSON `CoreOutput`
     * -> UI.
     */
    private fun handleNotification(charUuid: java.util.UUID, data: ByteArray) {
        if (charUuid != ScaleUuids.WEIGHT_NOTIFY) {
            Log.w(TAG, "Notification from unmapped characteristic $charUuid")
            return
        }
        // CremaBridge.onNotification returns the CoreOutput as a JSON string.
        val json = bridge.onNotification(
            NotificationSource.SCALE_WEIGHT,
            data,
            nowMs().toULong(),
        )
        onCoreOutput(json)
    }

    companion object {
        private const val TAG = "ScaleBleManager"

        /**
         * Whether a scanned advertisement name identifies a Bookoo scale. The
         * scale advertises a name starting "BOOKOO_SC"; the core then identifies
         * the exact model from the full advertised name. The shared [BleScanner]
         * stays device-agnostic, so this — the "is this a Bookoo" rule — lives
         * with the scale manager.
         */
        fun isBookooName(name: String): Boolean =
            name.uppercase().startsWith(ScaleUuids.BOOKOO_NAME_PREFIX)
    }
}
