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

    private fun rig(fake: FakeGrinder = FakeGrinder()): Rig {
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
            onCoreOutput = lane::deliver,
            onStatus = {},
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

    /** M1 (BOOKOO §5.1): a lost notification drops that message, never the
     *  link. The README `periodInfo` is 21 frames at 20 bytes; without frame
     *  0 the other 20 are orphans, which once killed the link. */
    @Test
    fun aLostFirstFragmentDropsTheReadingNotTheLink() {
        val r = rig()
        connected(r)
        val t0 = r.fake.clock.get()
        r.fake.send(FakeGrinder.base(), seq = 1, atMs = t0 + 100)
        r.fake.send(README_PERIOD, seq = 2, atMs = t0 + 200)
        until("ready") { seen<Event.GrinderReading>().size == 1 }
        assertEquals(21, FakeGrinder.frames(README_PERIOD.toByteArray(), 3).size)
        for (i in 0 until 3) r.fake.send(README_PERIOD, seq = 3 + i, atMs = t0 + 400 + i * 200L, lost = setOf(0))
        r.fake.send(README_PERIOD.replace("\"bladeGap\":500", "\"bladeGap\":510"), seq = 9, atMs = t0 + 1_200)
        until("the next reading") { seen<Event.GrinderReading>().size == 2 }
        assertEquals("510", seen<Event.GrinderReading>().last().content.reading.setting?.value)
        assertTrue(seen<Event.GrinderLinkDead>().isEmpty(), "the link stands")
        r.manager.disconnect()
    }

    /** S1 (BOOKOO §4.3): the service is there, but RX can't be written or TX
     *  doesn't notify: the "doesn't look like a Motto 80" mismatch, nothing written. */
    @Test
    fun aMottoServiceWithoutAWritableRxOrNotifyingTxIsRefused() {
        val readOnly = setOf(GattProperty.READ)
        for (chars in listOf(
            mapOf(FakeGrinder.RX to readOnly, FakeGrinder.TX to setOf(GattProperty.NOTIFY)),
            mapOf(FakeGrinder.RX to setOf(GattProperty.WRITE), FakeGrinder.TX to setOf(GattProperty.INDICATE)),
            mapOf(FakeGrinder.TX to setOf(GattProperty.NOTIFY)),
        )) {
            val r = rig(FakeGrinder(characteristics = chars))
            r.manager.connect(FakeGrinder.Handle, FakeGrinder.NAME)
            until("the mismatch for $chars") { r.mismatched }
            until("stopped") { r.manager.isUserDisconnected }
            assertEquals(0, r.fake.writes.size, "nothing written to it")
        }
        // Write without response alone is enough, as BOOKOO's client checks it.
        val r = rig(FakeGrinder(characteristics = mapOf(FakeGrinder.RX to setOf(GattProperty.WRITE_WITHOUT_RESPONSE), FakeGrinder.TX to setOf(GattProperty.NOTIFY))))
        connected(r)
        assertFalse(r.mismatched)
        r.manager.disconnect()
    }

    /** A device with the grinder's name and no services at all is a mismatch too. */
    @Test
    fun aLookAlikeWithNoServicesIsRefused() {
        val r = rig(FakeGrinder(services = emptyList()))
        r.manager.connect(FakeGrinder.Handle, FakeGrinder.NAME)
        until("the mismatch") { r.mismatched }
        assertEquals(0, r.fake.writes.size)
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

    private companion object {
        /** BOOKOO's README §7.3 `periodInfo`: 248 bytes. */
        const val README_PERIOD = """{"broadcast":{"periodInfo":{"feedingRpm":65,"bladeGap":500,"grindRpm":700,"humidity":25,"devState":"IDLE","netState":"CONNECTED","totalGrinds":120,"cupDetect":true,"autoStop":true,"fastClean":true,"brightness":5,"standbySec":60,"selectPreset":-1}}}"""
    }
}
