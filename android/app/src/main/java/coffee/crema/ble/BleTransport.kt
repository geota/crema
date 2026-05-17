package coffee.crema.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Crema's own abstraction over a BLE central stack.
 *
 * The Crema app talks to exactly two devices — a DE1 espresso machine and a
 * Bookoo scale — over a small, well-understood slice of GATT: an unfiltered
 * scan with a name-prefix match, a single connection per device, a handful of
 * notify characteristics, and the occasional command write. [BleTransport]
 * captures *that* slice and nothing more. It is deliberately not a general
 * BLE facade.
 *
 * ## Why a wrapper
 *
 * The implementation ([NordicBleTransport]) is built on the Nordic
 * Kotlin-BLE-Library, which at the time of writing is a `2.0.0-alphaNN`
 * release with an unstable API. Pinning every manager directly to Nordic
 * types would scatter alpha-API churn across the whole `ble` package. Instead
 * the Nordic surface is confined to one file; everything above it depends only
 * on the types declared here. Swapping BLE stacks — or upgrading across a
 * breaking Nordic alpha — is then a single-file change.
 *
 * ## Types, not Nordic types
 *
 * Nordic 2.0 models UUIDs as `kotlin.uuid.Uuid` and connection state as a
 * sealed `ConnectionState` hierarchy. Crema's existing UUID maps
 * ([De1Uuids], [ScaleUuids]) and managers speak `java.util.UUID`, so this
 * interface speaks `java.util.UUID` too and exposes a flat [ConnState] enum.
 * No Nordic type crosses this boundary.
 *
 * ## Threading
 *
 * All `suspend` functions and `Flow`s here are cold and may be collected on
 * any dispatcher; the implementation hops to its own IO scope as needed. The
 * managers above keep their existing "callbacks arrive off the main thread"
 * contract — UI consumers still hop to the main thread themselves.
 */
interface BleTransport {

    /**
     * Coarse Bluetooth-adapter / connection state, flattened from whatever the
     * underlying stack models. [ConnState] intentionally has no per-step
     * granularity (discovering, subscribing, …): the managers own that finer
     * `State` machine for the UI. This is just connected-or-not plus the
     * failure case.
     */
    enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, FAILED }

    /**
     * An opaque handle to a device the scanner matched. The managers pass it
     * straight back to [connect]; they never inspect it. Carrying [name] and
     * [address] is purely for logging — discovery and the name-match rule
     * already happened in [scan].
     */
    interface DeviceHandle {
        /** Best available advertised / cached name, for logs. */
        val name: String?

        /** The device's Bluetooth address, for logs. */
        val address: String
    }

    /**
     * One result of a [scan]: a matched device and the advertised name the
     * match rule saw. [name] is the name the scan-side predicate was given —
     * the managers forward it to `bridge.connectScale(...)`.
     */
    data class ScanMatch(
        val device: DeviceHandle,
        val name: String,
    )

    /**
     * Run an unfiltered low-latency BLE scan and emit each advertisement whose
     * resolved name satisfies [matches].
     *
     * Neither the DE1 nor the Bookoo scale reliably advertises its 128-bit
     * GATT service UUID, so a service-UUID scan filter never matches — the scan
     * is unfiltered and the predicate runs on the resolved advertised name,
     * mirroring the legacy de1app discovery rule. The returned [Flow] is cold:
     * the scan starts when collection starts and stops when collection is
     * cancelled. The same advertisement (same address) may be emitted more
     * than once; the caller decides when it has seen enough.
     */
    fun scan(matches: (name: String) -> Boolean): Flow<ScanMatch>

    /**
     * Connect to [device] and discover its GATT services. Suspends until the
     * device is connected and services are resolved, or throws on failure /
     * timeout. After this returns, [observe] and [write] may be called for
     * characteristics of [service].
     */
    suspend fun connect(device: DeviceHandle)

    /** Disconnect [device] and release its GATT resources. Idempotent. */
    suspend fun disconnect(device: DeviceHandle)

    /**
     * The live connection state of [device]. A [StateFlow]; safe to use for UI
     * — it is connection *state*, not a notification stream, so latest-wins is
     * correct here.
     */
    fun connectionState(device: DeviceHandle): StateFlow<ConnState>

    /**
     * Subscribe to notifications/indications from the [characteristic] of
     * [service] on [device], enabling the CCCD as a side effect of collection.
     *
     * The returned [Flow] emits one [Notification] per BLE notification, in
     * arrival order, with [Notification.atMs] stamped at delivery. The
     * implementation MUST keep this stream lossless and non-conflating: the
     * Rust core counts every ShotSample / weight sample, so no
     * `StateFlow`/latest-wins semantics and no `conflate()`. If the consumer
     * is briefly slow, samples buffer rather than drop.
     *
     * To preserve per-device ordering across characteristics, callers that
     * observe several characteristics of one device should merge them into a
     * single collected stream rather than collecting each independently.
     */
    fun observe(
        device: DeviceHandle,
        service: UUID,
        characteristic: UUID,
    ): Flow<Notification>

    /**
     * Write [data] to the [characteristic] of [service] on [device]. Suspends
     * until the write completes; throws on failure. Used for scale tare /
     * timer commands.
     */
    suspend fun write(
        device: DeviceHandle,
        service: UUID,
        characteristic: UUID,
        data: ByteArray,
    )

    /**
     * One characteristic-value notification.
     *
     * @property characteristic the source characteristic's UUID, so a manager
     *   observing several characteristics can route by UUID.
     * @property data the raw notification payload.
     * @property atMs an `elapsedRealtime()` millisecond timestamp captured at
     *   delivery — the same monotonic clock `CremaBridge.onNotification`
     *   expects. Captured here, not later, so a replayed capture decodes
     *   identically to the live session.
     */
    data class Notification(
        val characteristic: UUID,
        val data: ByteArray,
        val atMs: Long,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Notification) return false
            return characteristic == other.characteristic &&
                data.contentEquals(other.data) &&
                atMs == other.atMs
        }

        override fun hashCode(): Int {
            var result = characteristic.hashCode()
            result = 31 * result + data.contentHashCode()
            result = 31 * result + atMs.hashCode()
            return result
        }
    }
}
