package coffee.crema.ble.proxy

import coffee.crema.ble.BleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A [BleTransport] decorator that **tees** the primary's real link into a
 * [RelayHub] so secondaries can mirror it — without changing anything above the
 * transport. The primary's `MainViewModel` wraps its real transport
 * (`NordicBleTransport`, or [ReplayBleTransport] in the no-Bluetooth demo) in one
 * of these; the `De1BleManager` / `ScaleBleManager` and the Rust core run
 * exactly as before, while every notification, connection-state change, and
 * device handle is copied to the hub on the side.
 *
 * ## Decoupled from the primary's core
 *
 * The observe tee is a non-suspending [RelayHub.onInbound] (which only
 * `trySend`s into per-client buffers), so a slow remote can never back-pressure
 * the primary's own lossless collection — the primary stays authoritative and
 * unthrottled; a lagging secondary is dropped by the hub and resyncs on
 * reconnect. The managers above see the delegate's stream unchanged.
 *
 * The hub reads through [readByAddress] (it deals in addresses; this resolves the
 * address back to the live [BleTransport.DeviceHandle] the delegate needs).
 */
class TappingBleTransport(
    private val delegate: BleTransport,
    private val hub: RelayHub,
    private val scope: CoroutineScope,
) : BleTransport {

    /** Live device handles seen via scan/connect, so [readByAddress] can resolve them. */
    private val handles = ConcurrentHashMap<String, BleTransport.DeviceHandle>()

    /** Addresses whose connection-state is already being forwarded to the hub. */
    private val connTapped = ConcurrentHashMap.newKeySet<String>()

    override fun scan(matches: (name: String) -> Boolean): Flow<BleTransport.ScanMatch> =
        delegate.scan(matches).onEach { handles[it.device.address] = it.device }

    override suspend fun connect(device: BleTransport.DeviceHandle) {
        handles[device.address] = device
        delegate.connect(device)
        // Forward this device's connection-state changes to the hub once. The
        // StateFlow replays its current value, so the hub immediately learns the
        // post-connect state.
        if (connTapped.add(device.address)) {
            delegate.connectionState(device)
                .onEach { hub.onConnState(device.address, it.name) }
                .launchIn(scope)
        }
    }

    override suspend fun disconnect(device: BleTransport.DeviceHandle) = delegate.disconnect(device)

    override fun connectionState(device: BleTransport.DeviceHandle): StateFlow<BleTransport.ConnState> =
        delegate.connectionState(device)

    override fun observe(
        device: BleTransport.DeviceHandle,
        service: UUID,
        characteristic: UUID,
    ): Flow<BleTransport.Notification> =
        delegate.observe(device, service, characteristic).onEach { n ->
            // Non-suspending tee — never blocks the manager's collection.
            hub.onInbound(device.address, service, characteristic, n.data, n.atMs)
        }

    override suspend fun write(
        device: BleTransport.DeviceHandle,
        service: UUID,
        characteristic: UUID,
        data: ByteArray,
    ) = delegate.write(device, service, characteristic, data)

    override suspend fun read(
        device: BleTransport.DeviceHandle,
        service: UUID,
        characteristic: UUID,
    ): ByteArray = delegate.read(device, service, characteristic)

    override fun discoveredServices(device: BleTransport.DeviceHandle): List<String> =
        delegate.discoveredServices(device)

    override suspend fun requestConnectionPriority(device: BleTransport.DeviceHandle, high: Boolean) =
        delegate.requestConnectionPriority(device, high)

    /** Serve a hub read by address: resolve the handle and read through the
     *  delegate. This backs [RelayHub]'s `readSource`. */
    suspend fun readByAddress(address: String, service: UUID, char: UUID): ByteArray {
        val handle = handles[address] ?: error("TappingBleTransport: no handle for $address")
        return delegate.read(handle, service, char)
    }
}
