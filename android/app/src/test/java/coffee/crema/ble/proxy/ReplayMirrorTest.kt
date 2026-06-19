package coffee.crema.ble.proxy

import coffee.crema.ble.BleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The full M1 demo data path, in-process: a **replay-backed primary**
 * (`ReplayBleTransport` → `TappingBleTransport` → `RelayHub`) mirrored to a
 * **proxy secondary** (`ProxyTransport`) over an `InMemoryFrameLink`. This is the
 * 2-emulator demo minus the socket (the socket is covered by [LanRelayWsTest]):
 * it proves the replayed DE1 stream reaches the secondary losslessly, in order,
 * with verbatim timestamps, plus the state snapshot.
 */
class ReplayMirrorTest {

    private val service = UUID.fromString("0000a000-0000-1000-8000-00805f9b34fb")
    private val stateChar = UUID.fromString("0000a00e-0000-1000-8000-00805f9b34fb")
    private val sampleChar = UUID.fromString("0000a00d-0000-1000-8000-00805f9b34fb")
    private val de1Addr = "DE1:REPLAY"

    private fun route(src: String): Pair<UUID, UUID>? = when (src) {
        "DE1_STATE" -> service to stateChar
        "DE1_SHOT_SAMPLE" -> service to sampleChar
        else -> null
    }

    @Test
    fun `replay-backed primary mirrors to a proxy secondary losslessly`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val nSamples = 300
            // Synthetic capture: one machine-state line, then a telemetry ramp.
            val capture = buildList {
                add(ReplayBleTransport.CaptureLine(t = 0L, dir = "in", src = "DE1_STATE", hex = "0501"))
                for (i in 0 until nSamples) {
                    add(ReplayBleTransport.CaptureLine(t = 10L + i, dir = "in", src = "DE1_SHOT_SAMPLE", hex = "%02x".format(i and 0xFF)))
                }
            }
            val replay = ReplayBleTransport(capture, scope, "DE1", de1Addr, ::route, speedup = 50.0)

            lateinit var tapping: TappingBleTransport
            val hub = RelayHub(
                primaryId = "primary-1",
                primaryName = "Replay primary",
                roster = { listOf(DeviceInfo(de1Addr, "DE1", "de1", "CONNECTED")) },
                readSource = { a, s, c -> tapping.readByAddress(a, s, c) },
                isSnapshotChar = { _, c -> c == stateChar }, // telemetry stream is NOT snapshotted
            )
            tapping = TappingBleTransport(replay, hub, scope)

            // SECONDARY first: attach before the primary starts replaying, so the
            // mirror sees the whole telemetry stream (a real mirror joining mid-shot
            // would only see from attach-time — here we join before it starts).
            val (clientEnd, serverEnd) = InMemoryFrameLink.pair()
            scope.launch { hub.serve(serverEnd) }
            val proxy = ProxyTransport(clientEnd, scope, clientId = "phone-1", clientName = "Phone")
            val secondaryDe1 = withTimeout(2_000) { proxy.scan { it == "DE1" }.first() }.device
            withTimeout(2_000) { proxy.connect(secondaryDe1) }
            val mirrored = CopyOnWriteArrayList<BleTransport.Notification>()
            val obs = scope.launch {
                merge(
                    proxy.observe(secondaryDe1, service, stateChar),
                    proxy.observe(secondaryDe1, service, sampleChar),
                ).collect { mirrored.add(it) }
            }

            // PRIMARY: the managers' role — scan + connect + observe through the
            // tap, which starts the replay and tees every notification to the hub.
            val primaryDe1 = withTimeout(2_000) { tapping.scan { it == "DE1" }.first() }.device
            withTimeout(2_000) { tapping.connect(primaryDe1) }
            val primarySeen = CopyOnWriteArrayList<BleTransport.Notification>()
            val primaryObs = scope.launch {
                merge(
                    tapping.observe(primaryDe1, service, stateChar),
                    tapping.observe(primaryDe1, service, sampleChar),
                ).collect { primarySeen.add(it) }
            }

            // The secondary mirrors every telemetry sample, in order, verbatim t.
            withTimeout(8_000) { while (mirrored.count { it.characteristic == sampleChar } < nSamples) delay(5) }
            val mirroredSamples = mirrored.filter { it.characteristic == sampleChar }
            assertEquals(nSamples, mirroredSamples.size, "secondary sees every replayed telemetry sample")
            assertEquals((0 until nSamples).map { 10L + it }, mirroredSamples.map { it.atMs }, "order + verbatim timestamps")
            assertTrue(mirrored.any { it.characteristic == stateChar }, "machine-state line reached the secondary")

            // The primary's own core path saw the same stream (the tap is a copy,
            // not a diversion).
            assertEquals(nSamples, primarySeen.count { it.characteristic == sampleChar }, "primary path is unaffected by the tap")

            obs.cancel()
            primaryObs.cancel()
        } finally {
            scope.cancel()
        }
    }
}
