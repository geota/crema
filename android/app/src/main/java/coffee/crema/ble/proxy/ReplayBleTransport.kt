package coffee.crema.ble.proxy

import coffee.crema.ble.BleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A [BleTransport] that replays a recorded `session-*.jsonl` capture (the
 * [coffee.crema.ble.BleSessionRecorder] format) into the transport seam — the
 * Kotlin twin of the Rust `replay.rs` example, but feeding the *transport* layer
 * rather than the core directly.
 *
 * Its purpose is the M1 demo: an emulator has no Bluetooth, so the **primary**
 * runs on a [ReplayBleTransport] (wrapped by [TappingBleTransport]) instead of
 * `NordicBleTransport`, streaming a realistic DE1 session over the LAN to a
 * secondary with zero hardware. Each captured `dir:"in"` line is re-emitted as a
 * [BleTransport.Notification] on its original cadence, **carrying its original
 * timestamp** so the decode is byte- and timing-faithful to the live session.
 *
 * The capture's `src` labels (`DE1_STATE`, `DE1_SHOT_SAMPLE`, …) are mapped to
 * `(service, characteristic)` UUIDs by the injected [route] — passed in rather
 * than read from `De1Uuids` so this class never touches the native FFI and stays
 * unit-testable on a plain JVM. The on-device demo supplies a `De1Uuids`-based
 * route; tests supply a synthetic one.
 */
class ReplayBleTransport(
    private val lines: List<CaptureLine>,
    private val scope: CoroutineScope,
    private val deviceName: String,
    deviceAddress: String,
    private val route: (src: String) -> Pair<UUID, UUID>?,
    /** Replay speed multiplier: `1.0` = real-time, higher = faster (tests use a
     *  large value to run instantly). */
    private val speedup: Double = 1.0,
) : BleTransport {

    private val handle = ReplayHandle(deviceName, deviceAddress)
    private val state = MutableStateFlow(BleTransport.ConnState.DISCONNECTED)

    /** Per-characteristic lossless notification channels, drained by [observe]. */
    private val streams = ConcurrentHashMap<String, Channel<BleTransport.Notification>>()

    /** First captured value per characteristic — what [read] serves (the
     *  connect-time seed reads the secondary issues). */
    private val seeds = HashMap<String, ByteArray>()

    private var replayJob: Job? = null

    init {
        for (line in lines) {
            if (line.dir != DIR_IN) continue
            val char = route(line.src)?.second ?: continue
            seeds.getOrPut(char.toString()) { Hex.decode(line.hex) }
        }
    }

    override fun scan(matches: (name: String) -> Boolean): Flow<BleTransport.ScanMatch> = flow {
        if (matches(deviceName)) emit(BleTransport.ScanMatch(handle, deviceName))
    }

    override suspend fun connect(device: BleTransport.DeviceHandle) {
        state.value = BleTransport.ConnState.CONNECTING
        state.value = BleTransport.ConnState.CONNECTED
        startReplay()
    }

    override suspend fun disconnect(device: BleTransport.DeviceHandle) {
        replayJob?.cancel()
        replayJob = null
        state.value = BleTransport.ConnState.DISCONNECTED
    }

    override fun connectionState(device: BleTransport.DeviceHandle): StateFlow<BleTransport.ConnState> =
        state.asStateFlow()

    override fun observe(
        device: BleTransport.DeviceHandle,
        service: UUID,
        characteristic: UUID,
    ): Flow<BleTransport.Notification> = channelFor(characteristic.toString()).receiveAsFlow()

    override suspend fun write(
        device: BleTransport.DeviceHandle,
        service: UUID,
        characteristic: UUID,
        data: ByteArray,
    ) {
        // A replay has no machine to write to — accept and drop.
    }

    override suspend fun read(
        device: BleTransport.DeviceHandle,
        service: UUID,
        characteristic: UUID,
    ): ByteArray = seeds[characteristic.toString()] ?: ByteArray(0)

    private fun startReplay() {
        replayJob?.cancel()
        replayJob = scope.launch {
            var prevT: Long? = null
            for (line in lines) {
                if (line.dir != DIR_IN) continue
                val (_, char) = route(line.src) ?: continue
                val gap = prevT?.let { ((line.t - it) / speedup).toLong().coerceAtLeast(0L) } ?: 0L
                if (gap > 0L) delay(gap)
                prevT = line.t
                channelFor(char.toString()).trySend(
                    BleTransport.Notification(char, Hex.decode(line.hex), line.t),
                )
            }
        }
    }

    private fun channelFor(char: String): Channel<BleTransport.Notification> =
        streams.computeIfAbsent(char) { Channel(Channel.UNLIMITED) }

    private class ReplayHandle(
        override val name: String,
        override val address: String,
    ) : BleTransport.DeviceHandle {
        override fun equals(other: Any?): Boolean = other is ReplayHandle && other.address == address
        override fun hashCode(): Int = address.hashCode()
    }

    /** One line of a `session-*.jsonl` capture (see [coffee.crema.ble.BleSessionRecorder]). */
    @Serializable
    data class CaptureLine(val t: Long, val dir: String, val src: String, val hex: String)

    companion object {
        private const val DIR_IN = "in"
        private val json = Json { ignoreUnknownKeys = true }

        /** Parse a `session-*.jsonl` capture body (one JSON object per line). */
        fun parse(jsonl: String): List<CaptureLine> =
            jsonl.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { json.decodeFromString(CaptureLine.serializer(), it) }
                .toList()
    }
}
