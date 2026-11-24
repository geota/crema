package coffee.crema.ble

import coffee.crema.core.CremaBridge
import coffee.crema.core.Event
import coffee.crema.core.GrinderLinkDeadReason
import coffee.crema.core.GrinderState
import coffee.crema.core.RunKind
import coffee.crema.ui.CoreLane
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The grinder manager against a scripted Motto 80 ([FakeGrinder]) and the
 * real core (the host build): the handshake goes out in order as BOOKOO's
 * vector, readings and a grind come back as core events, silence kills the
 * link, old firmware is refused, and a look-alike is refused.
 */
class GrinderBleManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bridge = CremaBridge()
    private val events = CopyOnWriteArrayList<Event>()
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private class Rig(val fake: FakeGrinder, val manager: GrinderBleManager, val lane: CoreLane, var mismatched: Boolean = false)

    private fun rig(fake: FakeGrinder = FakeGrinder(), recorder: BleSessionRecorder? = null, status: (String) -> Unit = {}): Rig {
        lateinit var manager: GrinderBleManager
        val lane = CoreLane(
            scope = scope,
            json = json,
            log = {},
            writeDe1 = { _, _, _, _ -> false },
            writeScale = {},
            writeGrinder = { bytes -> manager.writeCommand(bytes) },
            profileWriteFailed = { "{}" },
        )
        for (type in listOf(
            Event.GrinderReady::class, Event.GrinderReading::class, Event.GrinderInfoChanged::class,
            Event.GrinderLinkDead::class, Event.GrinderUnsupported::class, Event.GrindCompleted::class,
        )) {
            lane.register(type) { events += it }
        }
        var rigRef: Rig? = null
        manager = GrinderBleManager(
            transport = fake,
            bridge = bridge,
            recorder = recorder,
            onCoreOutput = lane::deliver,
            onStatus = status,
            onMismatch = { rigRef?.mismatched = true },
            nowMs = { fake.clock.get() },
        )
        return Rig(fake, manager, lane).also { rigRef = it }
    }

    private fun until(what: String, timeoutMs: Long = 5_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!ok()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    private inline fun <reified E : Event> seen(): List<E> = events.filterIsInstance<E>()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Connect and wait for the handshake. */
    private fun connected(r: Rig) {
        r.manager.connect(FakeGrinder.Handle, FakeGrinder.NAME)
        until("the handshake") { r.fake.writes.size == 4 }
        until("subscribed") { r.manager.state.value == GrinderBleManager.State.READY }
    }

    @Test
    fun theHandshakeGoesOutInOrderAndReadingsAndAGrindComeBack() {
        val r = rig()
        connected(r)
        assertEquals(
            listOf(
                "a50101002b0000007b2272657175657374223a7b",
                "a50101002b000c002261707048656c6c6f223a7b",
                "a50101002b001800226f70223a2268616e647368",
                "a50101002b002400616b65227d7d7d",
            ),
            r.fake.writes.map(::hex),
            "BOOKOO's own encode_fragments output for the handshake",
        )
        assertTrue(r.fake.writeTypes.none { it }, "written with response")
        assertEquals(1, r.fake.mtuRequests, "a large MTU asked for once")

        val t0 = r.fake.clock.get()
        r.fake.send(FakeGrinder.base(), seq = 1, atMs = t0 + 100)
        r.fake.send(FakeGrinder.period("IDLE", 10), seq = 2, atMs = t0 + 200)
        until("ready") { seen<Event.GrinderReady>().isNotEmpty() && seen<Event.GrinderReading>().isNotEmpty() }
        val info = seen<Event.GrinderReady>().single().content.info
        assertEquals("bookoo-motto-80", info.model)
        assertEquals("1.3.11.1020", info.firmware)
        val first = seen<Event.GrinderReading>().first().content.reading
        assertEquals(GrinderState.Idle, first.state)
        assertEquals("480", first.setting?.value)

        // A 9 s grind at 5 Hz, then idle with the count up.
        var t = t0 + 400
        while (t <= t0 + 9_400) {
            r.fake.send(FakeGrinder.period("GRINDING", 10), seq = 3, atMs = t)
            t += 200
        }
        r.fake.send(FakeGrinder.period("IDLE", 11), seq = 4, atMs = t)
        until("the grind") { seen<Event.GrindCompleted>().isNotEmpty() }
        val grind = seen<Event.GrindCompleted>().single().content.session
        assertEquals(RunKind.Grind, grind.kind)
        assertEquals(true, grind.counted)
        assertEquals(900u, grind.grind_rpm)

        // 5 s of silence: the core's tick says the link is dead.
        r.lane.deliver(bridge.onTick((t + 5_000).toULong()))
        until("link dead") { seen<Event.GrinderLinkDead>().isNotEmpty() }
        assertEquals(GrinderLinkDeadReason.Silent, seen<Event.GrinderLinkDead>().single().content.reason)

        r.manager.disconnect()
    }

    @Test
    fun aReconnectHandshakesAgain() {
        val r = rig()
        connected(r)
        r.fake.writes.clear()
        r.fake.drop()
        until("the second handshake", timeoutMs = 15_000) { r.fake.writes.size == 4 }
        assertEquals(2, r.fake.connects)
        r.manager.disconnect()
    }

    @Test
    fun oldFirmwareIsUnsupported() {
        val r = rig()
        connected(r)
        r.fake.send(FakeGrinder.base(release = 102_040_416))
        until("unsupported") { seen<Event.GrinderUnsupported>().isNotEmpty() }
        val e = seen<Event.GrinderUnsupported>().single().content
        assertEquals("1.2.4.0416" to "1.2.19.0820", e.firmware to e.minimum)
        r.manager.giveUp("firmware too old")
        assertTrue(r.manager.isUserDisconnected, "no reconnect after giving up")
    }

    @Test
    fun aLookAlikeWithoutTheServiceIsRefused() {
        val r = rig(FakeGrinder(services = listOf("0000ffff-0000-1000-8000-00805f9b34fb")))
        r.manager.connect(FakeGrinder.Handle, FakeGrinder.NAME)
        until("the mismatch") { r.mismatched }
        until("stopped") { r.manager.isUserDisconnected }
        Thread.sleep(200)
        assertEquals(0, r.fake.writes.size, "nothing written to it")
    }

    /**
     * Android's capture file holds the grinder as web's does (grinder-ble
     * §6.4): the core's redacted, re-serialized frames as `GRINDER_TX`
     * lines, never the raw bytes. Neither the serial nor the Wi-Fi name
     * reaches the file, in clear, in hex or across fragments, nor a status line.
     */
    @Test
    fun theCaptureFileHoldsTheRedactedGrinderAndNoSecret() {
        val dir = kotlin.io.path.createTempDirectory("grinder-capture").toFile()
        val context = object : android.content.ContextWrapper(null) {
            override fun getExternalFilesDir(type: String?): java.io.File = dir
        }
        val statuses = CopyOnWriteArrayList<String>()
        val r = rig(recorder = BleSessionRecorder(context), status = { statuses += it })
        connected(r)
        val t0 = r.fake.clock.get()
        r.fake.send(FakeGrinder.base(), seq = 1, atMs = t0 + 100)
        r.fake.send(FakeGrinder.period("IDLE", 10), seq = 2, atMs = t0 + 200)
        until("ready") { seen<Event.GrinderReady>().isNotEmpty() && seen<Event.GrinderReading>().isNotEmpty() }
        r.manager.disconnect() // the last device: the file closes

        val file = java.io.File(dir, "captures").listFiles().orEmpty().single()
        val text = file.readText()
        val lines = text.lines().filter { it.isNotBlank() }.map { json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject }
        assertEquals(2, lines.size, "the base info and the reading, each one re-serialized frame: $text")
        for (line in lines) {
            assertEquals("GRINDER_TX", line["src"].toString().trim('"'))
            assertEquals("in", line["dir"].toString().trim('"'))
        }
        val frames = lines.map { coffee.crema.ble.proxy.Hex.decode(it["hex"].toString().trim('"')) }
        // The payloads, headers stripped and joined, as replay reassembles them.
        val payloads = frames.joinToString("") { String(it.copyOfRange(8, it.size), Charsets.UTF_8) }
        assertTrue("\"snCode\":\"REDACTED\"" in payloads, payloads)
        assertTrue("\"wifiName\":null" in payloads, payloads)
        for (secret in listOf(FakeGrinder.SERIAL, FakeGrinder.SSID, "MT12355")) {
            assertFalse(secret in payloads, "$secret in the capture")
            assertFalse(secret in text, "$secret in the file")
            assertFalse(hex(secret.toByteArray()) in text, "$secret in the file, as hex")
            assertTrue(statuses.none { secret in it }, "$secret in a status line")
        }
        dir.deleteRecursively()
    }
}
