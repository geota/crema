package coffee.crema.ble

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.client.RemoteServices
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.native
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.environment.android.NativeAndroidEnvironment
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toKotlinUuid

/**
 * [BleTransport] implemented over the Nordic Kotlin-BLE-Library (central role).
 *
 * This is the ONLY file in the app that imports `no.nordicsemi.*`. Nordic
 * types — [Peripheral], [CentralManager], `ConnectionState`, `kotlin.uuid.Uuid`
 * — do not cross the [BleTransport] boundary; the managers above see only the
 * Crema-owned types in [BleTransport].
 *
 * ## Lifecycle
 *
 * The [NativeAndroidEnvironment] registers a Bluetooth broadcast receiver and
 * MUST be `close()`d. This class is created once (its [environment] is created
 * here) and [close]d from `MainViewModel.onCleared()`. [close] tears down the
 * Nordic [CentralManager] and the environment together.
 *
 * ## Notification losslessness
 *
 * Nordic's `characteristic.subscribe()` returns a cold `Flow<ByteArray>` — not
 * a `StateFlow` — so no notification is conflated at the source. [observe]
 * wraps that flow, stamps each value with an `elapsedRealtime()` timestamp at
 * delivery, and buffers (`SUSPEND` overflow, unbounded) so a briefly-slow
 * consumer back-pressures the producer rather than dropping samples. The Rust
 * core counts every ShotSample / weight sample, so this is load-bearing.
 *
 * @param context an application [Context]; used to build the environment.
 */
@OptIn(ExperimentalUuidApi::class)
class NordicBleTransport(context: Context) : BleTransport {

    /**
     * The transport's own coroutine scope. Connections and the central manager
     * live on it; [close] cancels it. A [SupervisorJob] so one failed
     * connection does not tear the others down.
     */
    private val scope = CoroutineScope(SupervisorJob())

    /**
     * Native Android BLE environment. Owns a broadcast receiver — see [close].
     * `isNeverForLocationFlagSet = true`: Crema's manifest declares
     * `BLUETOOTH_SCAN` with `usesPermissionFlags="neverForLocation"`, so the
     * scan does not derive location and no `ACCESS_FINE_LOCATION` is needed.
     */
    private val environment: NativeAndroidEnvironment =
        NativeAndroidEnvironment.getInstance(
            context.applicationContext,
            isNeverForLocationFlagSet = true,
        )

    /** The Nordic central manager — one per app, created on [environment]. */
    private val centralManager: CentralManager =
        CentralManager.native(environment, scope)

    /**
     * Maps a [BleTransport.DeviceHandle] back to the live Nordic [Peripheral].
     * Populated by [scan] when a device is matched; the managers only ever
     * hand back handles this transport itself produced.
     */
    private val peripherals = ConcurrentHashMap<NordicDeviceHandle, Peripheral>()

    /** Per-device mirror of the coarse [BleTransport.ConnState]. */
    private val states = ConcurrentHashMap<NordicDeviceHandle, MutableStateFlow<BleTransport.ConnState>>()

    /** A [BleTransport.DeviceHandle] backed by a Nordic [Peripheral]. */
    private class NordicDeviceHandle(
        override val name: String?,
        override val address: String,
    ) : BleTransport.DeviceHandle {
        // Identity is the BLE address — the same physical device scanned twice
        // must map to the same handle / Peripheral entry.
        override fun equals(other: Any?): Boolean =
            other is NordicDeviceHandle && other.address == address

        override fun hashCode(): Int = address.hashCode()
    }

    // ---- Scan -------------------------------------------------------------

    /**
     * The single shared raw scan. `centralManager.scan {}` is a COLD flow —
     * every collector would otherwise start its OWN radio scan — so it is
     * shared via `shareIn(WhileSubscribed)`: the one physical scan runs while
     * at least one [scan] caller is collecting and stops when the last drops.
     * Each [scan] is a thin name-filter over this one upstream, so N concurrent
     * "wants" (a DE1 + a scale outstanding together) share ONE radio scan
     * instead of opening two (AND3). `replay = 0`: a late subscriber waits for
     * a fresh advertisement rather than replaying a stale one.
     *
     * Unfiltered (empty Nordic filter block): neither the DE1 nor the Bookoo
     * scale reliably advertises its 128-bit service UUID, so a ServiceUuid scan
     * filter never matches — the name is matched in Kotlin instead, the same
     * rule the legacy de1app uses.
     */
    private val rawScan =
        centralManager.scan { /* no filter — see above */ }
            .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)

    override fun scan(matches: (name: String) -> Boolean): Flow<BleTransport.ScanMatch> =
        rawScan
            .mapNotNull { result ->
                val peripheral = result.peripheral
                val name = result.advertisingData.name ?: peripheral.name
                Log.d(
                    TAG,
                    "Scan saw: name=${name ?: "(none)"} addr=${peripheral.address} " +
                        "rssi=${result.rssi}",
                )
                if (name == null || !matches(name)) return@mapNotNull null
                val handle = NordicDeviceHandle(name, peripheral.address)
                // Remember the peripheral so connect() can resolve the handle.
                peripherals[handle] = peripheral
                states.getOrPut(handle) {
                    MutableStateFlow(BleTransport.ConnState.DISCONNECTED)
                }
                BleTransport.ScanMatch(handle, name)
            }

    // ---- Connect ----------------------------------------------------------

    override suspend fun connect(device: BleTransport.DeviceHandle) {
        val handle = device as NordicDeviceHandle
        val peripheral = peripherals[handle]
            ?: error("connect() for an unknown device handle ${handle.address}")
        val stateFlow = states.getOrPut(handle) {
            MutableStateFlow(BleTransport.ConnState.DISCONNECTED)
        }

        // Mirror the Nordic ConnectionState into the coarse ConnState flow for
        // as long as this transport lives. Launched on the transport scope so
        // it survives the connect() call and tracks later link loss.
        peripheral.state
            .onEach { stateFlow.value = it.toConnState() }
            .launchIn(scope)

        stateFlow.value = BleTransport.ConnState.CONNECTING
        // Direct connection; Nordic applies its own connect timeout + retries.
        centralManager.connect(
            peripheral = peripheral,
            options = CentralManager.ConnectionOptions.Direct(
                timeout = 10.seconds,
                retry = 2,
                retryDelay = 1.seconds,
            ),
        )

        // Wait for service discovery to reach a terminal state before
        // returning, so the caller can observe()/write() characteristics
        // immediately after. services() is a StateFlow that emits Unknown,
        // then Discovering, then Discovered (or Failed).
        when (
            val resolved = peripheral.services()
                .first { it is RemoteServices.Discovered || it is RemoteServices.Failed }
        ) {
            is RemoteServices.Failed -> error("Service discovery failed: ${resolved.reason}")
            is RemoteServices.Discovered -> {
                // Dump the full GATT table to Logcat. This is how Bookoo
                // characteristics beyond the weight-notify one (e.g. a
                // battery/timer/command channel) get discovered without a
                // sniffer — see ScaleBleManager / bookoo.rs.
                logGatt(handle, resolved)
            }
            else -> Unit // Unknown/Discovering can't reach here (see first{} above)
        }
    }

    /**
     * Log every discovered GATT service and, under each, every characteristic
     * with its UUID and decoded properties (READ / WRITE / WRITE_NO_RESPONSE /
     * NOTIFY / INDICATE / …). Called once per connect, after discovery.
     */
    private fun logGatt(handle: NordicDeviceHandle, discovered: RemoteServices.Discovered) {
        val label = handle.name ?: handle.address
        Log.i(TAG, "GATT for $label (${handle.address}): ${discovered.services.size} service(s)")
        for (service in discovered.services) {
            Log.i(TAG, "  service ${service.uuid}")
            for (characteristic in service.characteristics) {
                // CharacteristicProperty has eight values; render the set in a
                // stable, readable form. An empty set prints as "(none)".
                val props = characteristic.properties
                    .map { it.name }
                    .sorted()
                    .joinToString(", ")
                    .ifEmpty { "(none)" }
                Log.i(TAG, "    char ${characteristic.uuid}  [$props]")
            }
        }
    }

    override suspend fun disconnect(device: BleTransport.DeviceHandle) {
        val handle = device as NordicDeviceHandle
        val peripheral = peripherals[handle] ?: return
        states[handle]?.value = BleTransport.ConnState.DISCONNECTING
        runCatching { peripheral.disconnect() }
            .onFailure { Log.w(TAG, "disconnect(${handle.address}) failed", it) }
        states[handle]?.value = BleTransport.ConnState.DISCONNECTED
    }

    override fun connectionState(device: BleTransport.DeviceHandle): StateFlow<BleTransport.ConnState> {
        val handle = device as NordicDeviceHandle
        return states.getOrPut(handle) {
            MutableStateFlow(BleTransport.ConnState.DISCONNECTED)
        }.asStateFlow()
    }

    // ---- Observe ----------------------------------------------------------

    override fun observe(
        device: BleTransport.DeviceHandle,
        service: UUID,
        characteristic: UUID,
    ): Flow<BleTransport.Notification> {
        val handle = device as NordicDeviceHandle
        // callbackFlow with an unbounded SUSPEND buffer: a lossless,
        // non-conflating, ordered stream. If the consumer is briefly slow the
        // producer back-pressures rather than dropping a sample.
        return callbackFlow {
            val peripheral = peripherals[handle]
                ?: error("observe() for an unknown device handle ${handle.address}")
            val charUuid = characteristic.toKotlinUuid()
            val serviceUuid = service.toKotlinUuid()

            // Resolve the characteristic from the discovered services. connect()
            // has already awaited discovery, so Discovered is expected here.
            val remote: RemoteCharacteristic = peripheral.services()
                .mapNotNull { (it as? RemoteServices.Discovered)?.services }
                .first()
                .firstOrNull { it.uuid == serviceUuid }
                ?.characteristics
                ?.firstOrNull { it.uuid == charUuid }
                ?: error("Characteristic $characteristic not found in service $service")

            // subscribe() is cold and returns a Flow<ByteArray>; collecting it
            // enables the CCCD. Stamp at delivery — the same elapsedRealtime()
            // clock CremaBridge.onNotification expects.
            val job = remote.subscribe()
                .onEach { value ->
                    trySend(
                        BleTransport.Notification(
                            characteristic = characteristic,
                            data = value,
                            atMs = SystemClock.elapsedRealtime(),
                        ),
                    )
                }
                .onCompletion { close(it) }
                .launchIn(this@callbackFlow)

            awaitClose { job.cancel() }
        }.buffer(
            capacity = Channel.UNLIMITED,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
    }

    // ---- Write ------------------------------------------------------------

    override suspend fun write(
        device: BleTransport.DeviceHandle,
        service: UUID,
        characteristic: UUID,
        data: ByteArray,
    ) {
        val handle = device as NordicDeviceHandle
        val peripheral = peripherals[handle]
            ?: error("write() for an unknown device handle ${handle.address}")
        val serviceUuid = service.toKotlinUuid()
        val charUuid = characteristic.toKotlinUuid()

        val remote = peripheral.services()
            .mapNotNull { (it as? RemoteServices.Discovered)?.services }
            .first()
            .firstOrNull { it.uuid == serviceUuid }
            ?.characteristics
            ?.firstOrNull { it.uuid == charUuid }
            ?: error("Characteristic $characteristic not found in service $service")

        // Default write type: Nordic picks WITH_RESPONSE / WITHOUT_RESPONSE
        // from the characteristic's declared properties, matching the legacy
        // WRITE_TYPE_DEFAULT behaviour.
        remote.write(data)
    }

    // ---- Read -------------------------------------------------------------

    override suspend fun read(
        device: BleTransport.DeviceHandle,
        service: UUID,
        characteristic: UUID,
    ): ByteArray {
        val handle = device as NordicDeviceHandle
        val peripheral = peripherals[handle]
            ?: error("read() for an unknown device handle ${handle.address}")
        val serviceUuid = service.toKotlinUuid()
        val charUuid = characteristic.toKotlinUuid()

        val remote = peripheral.services()
            .mapNotNull { (it as? RemoteServices.Discovered)?.services }
            .first()
            .firstOrNull { it.uuid == serviceUuid }
            ?.characteristics
            ?.firstOrNull { it.uuid == charUuid }
            ?: error("Characteristic $characteristic not found in service $service")

        return remote.read()
    }

    // ---- Lifecycle --------------------------------------------------------

    /**
     * Tear down the central manager and the [environment] (unregistering its
     * broadcast receiver) and cancel the transport scope. Call once, from
     * `MainViewModel.onCleared()`. Idempotent-ish: after this the transport
     * must not be reused.
     */
    fun close() {
        runCatching { centralManager.close() }
            .onFailure { Log.w(TAG, "centralManager.close() failed", it) }
        runCatching { environment.close() }
            .onFailure { Log.w(TAG, "environment.close() failed", it) }
        scope.cancel()
        peripherals.clear()
        states.clear()
    }

    private companion object {
        const val TAG = "NordicBleTransport"

        /** Flatten Nordic's sealed [ConnectionState] into [BleTransport.ConnState]. */
        fun ConnectionState.toConnState(): BleTransport.ConnState = when (this) {
            is ConnectionState.Connecting -> BleTransport.ConnState.CONNECTING
            is ConnectionState.Connected -> BleTransport.ConnState.CONNECTED
            is ConnectionState.Disconnecting -> BleTransport.ConnState.DISCONNECTING
            is ConnectionState.Disconnected ->
                if (reason == null || reason is ConnectionState.Disconnected.Reason.Success) {
                    BleTransport.ConnState.DISCONNECTED
                } else {
                    BleTransport.ConnState.FAILED
                }
        }
    }
}
