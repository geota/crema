package coffee.crema.ble.proxy

import coffee.crema.ble.BleTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * A [BleTransport] that forwards to a swappable [delegate] — the seam that lets
 * the app change transport **at runtime, without a restart** (M2). The managers
 * and scanner are constructed once on this facade; switching modes (NORMAL ↔
 * PRIMARY-relay ↔ SECONDARY-proxy) is then just [setDelegate].
 *
 * ## The swap discipline
 *
 * This facade is a transparent pass-through; it does NOT itself orchestrate a
 * safe swap. The owner ([coffee.crema.ui.MainViewModel]) must bracket a
 * [setDelegate] with the managers' own teardown/bring-up so no flow spans the
 * swap:
 *
 * ```
 * ble.disconnect(); scale.disconnect()   // cancels observe jobs, resets the core
 * switchable.setDelegate(next)           // Nordic ↔ TappingBleTransport ↔ ProxyTransport
 * ble.connect(...); scale.connect(...)   // re-scan + re-subscribe on the new delegate
 * ```
 *
 * Because device handles are delegate-specific (a `NordicDeviceHandle` vs a
 * `ProxyDeviceHandle`), the managers must always re-scan on the new delegate
 * after a swap rather than reuse a handle minted by the old one — which the
 * disconnect → swap → reconnect cycle above guarantees. Held between swaps,
 * [delegate] is read `@Volatile` so a manager on a background dispatcher sees
 * the latest.
 */
class SwitchableBleTransport(initial: BleTransport) : BleTransport {

    @Volatile
    private var current: BleTransport = initial

    /** The transport currently in effect. */
    val delegate: BleTransport get() = current

    /** Swap the active transport. The caller MUST have torn down the managers'
     *  connections first and must bring them back up after (see the class doc). */
    fun setDelegate(next: BleTransport) {
        current = next
    }

    override fun scan(matches: (name: String) -> Boolean): Flow<BleTransport.ScanMatch> =
        current.scan(matches)

    override suspend fun connect(device: BleTransport.DeviceHandle) = current.connect(device)

    override suspend fun disconnect(device: BleTransport.DeviceHandle) = current.disconnect(device)

    override fun connectionState(device: BleTransport.DeviceHandle): StateFlow<BleTransport.ConnState> =
        current.connectionState(device)

    override fun observe(
        device: BleTransport.DeviceHandle,
        service: UUID,
        characteristic: UUID,
    ): Flow<BleTransport.Notification> = current.observe(device, service, characteristic)

    override suspend fun write(
        device: BleTransport.DeviceHandle,
        service: UUID,
        characteristic: UUID,
        data: ByteArray,
    ) = current.write(device, service, characteristic, data)

    override suspend fun read(
        device: BleTransport.DeviceHandle,
        service: UUID,
        characteristic: UUID,
    ): ByteArray = current.read(device, service, characteristic)

    override fun discoveredServices(device: BleTransport.DeviceHandle): List<String> =
        current.discoveredServices(device)
}
