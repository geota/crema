package coffee.crema.ui

import coffee.crema.ble.BleScanner
import coffee.crema.ble.FakeGrinder
import coffee.crema.ble.GrinderBleManager
import coffee.crema.core.CremaBridge
import coffee.crema.core.GrindScale
import coffee.crema.core.Grinder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The connected-grinder controller over a scripted Motto 80 and the real
 * core: the beta gate, the remembered grinder's direct reconnect, the card's
 * status, the §6.1 link, the "can't see it" timeout, old firmware, and that
 * no log line carries the serial.
 */
class GrinderControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ui = MutableStateFlow(MainUiState())
    private val live = MutableStateFlow(LiveTelemetry())
    private val log = CopyOnWriteArrayList<String>()
    private val notices = CopyOnWriteArrayList<String>()
    private val saves = AtomicInteger()

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun until(what: String, timeoutMs: Long = 5_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!ok()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what: ${ui.value.connectedGrinder}" }
            Thread.sleep(10)
        }
    }

    private lateinit var bridge: CremaBridge
    private val links = CopyOnWriteArrayList<String?>()
    private val applied = CopyOnWriteArrayList<String>()

    /** What Brew has selected, to the core (the VM does this as the state changes). */
    private fun pushSelections() {
        val s = ui.value
        bridge.setShotSelections(
            Json.encodeToString(
                coffee.crema.core.ShotSelections.serializer(),
                coffee.crema.core.ShotSelections(grinderId = s.activeGrinderId, grind = s.qcGrind),
            ),
        )
    }
    private lateinit var lane: CoreLane

    private fun controller(fake: FakeGrinder, scanTimeoutMs: Long = 10_000): Pair<GrinderController, GrinderBleManager> {
        bridge = CremaBridge()
        lateinit var manager: GrinderBleManager
        lateinit var controller: GrinderController
        lane = CoreLane(
            scope = scope,
            json = Json { ignoreUnknownKeys = true },
            log = { log += it },
            writeDe1 = { _, _, _, _ -> false },
            writeScale = {},
            writeGrinder = { manager.writeCommand(it) },
            profileWriteFailed = { "{}" },
        )
        manager = GrinderBleManager(
            transport = fake,
            bridge = bridge,
            onCoreOutput = lane::deliver,
            onStatus = { log += it },
            onIdentified = { m, caps -> controller.onIdentified(m, caps) },
            onMismatch = { controller.onMismatch() },
            nowMs = { fake.clock.get() },
        )
        controller = GrinderController(
            scope = scope,
            lane = lane,
            manager = manager,
            bleScanner = BleScanner(fake) { log += it },
            transport = fake,
            uiState = { ui.value },
            updateUi = { t -> ui.update(t) },
            updateLive = { t -> live.update(t) },
            appendLog = { log += it },
            notify = { notices += it },
            persistPrefs = { saves.incrementAndGet() },
            addFromCatalogue = { id -> Grinder(id = "grinder:motto-80", name = "Motto 80", scale = GrindScale(0f, 999f, 1f, "μm"), presetId = id, updatedAt = 1L) },
            scanTimeoutMs = scanTimeoutMs,
            setCoreLink = { id -> links += id; lane.deliver(bridge.setGrinderLink(id)) },
            setCorePaused = { p -> lane.deliver(bridge.setGrinderFollowPaused(p)) },
            // Brew's grind, as the VM's SetGrind path keeps it, and on to the core.
            applyFollowGrind = { g ->
                applied += g
                ui.update { it.copy(qcGrind = g) }
                pushSelections()
            },
        )
        controller.start()
        return controller to manager
    }

    private fun motto() = Grinder(id = "grinder:motto", name = "My Motto", scale = GrindScale(0f, 999f, 1f, "μm"), presetId = "bookoo-motto-80", updatedAt = 1L)

    @Test
    fun withTheBetaOffNothingConnects() {
        val fake = FakeGrinder()
        val (c, m) = controller(fake)
        ui.update { it.copy(rememberedGrinderAddress = FakeGrinder.Handle.address, rememberedGrinderName = FakeGrinder.NAME) }
        c.hydrate()
        c.connect()
        Thread.sleep(200)
        assertEquals(0, fake.connects)
        assertEquals(GrinderBleManager.State.IDLE, m.state.value)
    }

    @Test
    fun aRememberedGrinderReconnectsGoesLiveAndLinksItsOnlyRecord() {
        val fake = FakeGrinder()
        val (c, _) = controller(fake)
        ui.update {
            it.copy(
                connectedGrinders = true,
                rememberedGrinderAddress = FakeGrinder.Handle.address,
                rememberedGrinderName = FakeGrinder.NAME,
                grinders = rowViewOf(listOf(motto())),
            )
        }
        c.hydrate()
        until("the handshake") { fake.writes.size == 4 }
        until("waiting") { ui.value.connectedGrinder.status == GrinderLinkStatus.WAITING }
        assertEquals("μm", ui.value.connectedGrinder.unit)
        fake.send(FakeGrinder.base(), seq = 1)
        fake.send(FakeGrinder.period("IDLE", 3), seq = 2)
        until("live") { ui.value.connectedGrinder.status == GrinderLinkStatus.LIVE && live.value.grinderReading != null }
        val g = ui.value.connectedGrinder
        assertEquals("1.3.11.1020", g.firmware)
        assertEquals("2355", g.serialTail)
        until("linked") { ui.value.grinderLinkedId == "grinder:motto" }
        assertEquals(FakeGrinder.Handle.address, ui.value.grinderLinkedAddress)
        assertTrue(notices.any { it == "Linked to your Motto 80" }, "$notices")
        assertEquals(motto().id, ui.value.copy(connectedGrinderAddress = FakeGrinder.Handle.address).linkedGrinder()?.id)
        assertTrue(saves.get() > 0)
        assertTrue(log.none { it.contains(FakeGrinder.SERIAL) || it.contains(FakeGrinder.SSID) }, "a log line holds the serial or SSID: $log")
        c.disconnect()
        assertEquals(GrinderLinkStatus.OFF, ui.value.connectedGrinder.status)
        assertNull(live.value.grinderReading)
    }

    @Test
    fun noRecordOffersToAddOneAndAddingLinksIt() {
        val fake = FakeGrinder()
        val (c, _) = controller(fake)
        ui.update { it.copy(connectedGrinders = true, rememberedGrinderAddress = FakeGrinder.Handle.address, rememberedGrinderName = FakeGrinder.NAME) }
        c.hydrate()
        until("the handshake") { fake.writes.size == 4 }
        fake.send(FakeGrinder.period("IDLE", 3), seq = 2)
        until("the offer") { ui.value.connectedGrinder.offerAdd }
        c.addAndLink()
        assertEquals("grinder:motto-80", ui.value.grinderLinkedId)
        assertFalse(ui.value.connectedGrinder.offerAdd)
    }

    @Test
    fun aScanThatFindsNothingSaysCloseTheBookooApp() {
        val fake = FakeGrinder().apply { resolvesByAddress = false }
        val (c, m) = controller(fake, scanTimeoutMs = 100)
        ui.update { it.copy(connectedGrinders = true) }
        c.connect()
        until("not found") { ui.value.connectedGrinder.status == GrinderLinkStatus.NOT_FOUND }
        assertEquals(GrinderMessages.notFound("Motto 80"), ui.value.connectedGrinder.message)
        assertEquals(GrinderBleManager.State.DISCONNECTED, m.state.value)
    }

    @Test
    fun oldFirmwareShowsUpdateAndStops() {
        val fake = FakeGrinder()
        val (c, m) = controller(fake)
        ui.update { it.copy(connectedGrinders = true, rememberedGrinderAddress = FakeGrinder.Handle.address, rememberedGrinderName = FakeGrinder.NAME) }
        c.hydrate()
        until("the handshake") { fake.writes.size == 4 }
        fake.send(FakeGrinder.base(release = 102_040_416))
        until("update firmware") { ui.value.connectedGrinder.status == GrinderLinkStatus.UPDATE_FIRMWARE && m.isUserDisconnected }
        assertEquals(GrinderMessages.updateFirmware("1.2.19.0820"), ui.value.connectedGrinder.message)
    }

    @Test
    fun aRemoteNeverConnects() {
        val fake = FakeGrinder()
        val (c, _) = controller(fake)
        ui.update { it.copy(connectedGrinders = true, proxyRole = "secondary", rememberedGrinderAddress = FakeGrinder.Handle.address, rememberedGrinderName = FakeGrinder.NAME) }
        c.hydrate()
        c.connect()
        Thread.sleep(200)
        assertEquals(0, fake.connects)
    }

    /** Review #552 M1: beta on, connect, beta off, restart, then a DE1 flash must not bring the grinder back. */
    @Test
    fun aFirmwareFlashDoesNotBringBackAGrinderWithTheBetaOff() {
        val fake = FakeGrinder()
        val (c, m) = controller(fake)
        ui.update { it.copy(connectedGrinders = false, rememberedGrinderAddress = FakeGrinder.Handle.address, rememberedGrinderName = FakeGrinder.NAME) }
        c.hydrate()
        c.onFirmwareFlash(true)
        c.onFirmwareFlash(false)
        Thread.sleep(300)
        assertEquals(0, fake.connects)
        assertEquals(0, fake.writes.size)
        assertEquals(GrinderBleManager.State.IDLE, m.state.value)
        assertTrue(log.none { it.startsWith("Grinder:") }, "no pause lines for an unwanted grinder: $log")
        // With the beta on, the same flash pauses and brings it back.
        ui.update { it.copy(connectedGrinders = true) }
        c.hydrate()
        until("connected") { fake.writes.size == 4 }
        c.onFirmwareFlash(true)
        until("paused") { m.state.value == GrinderBleManager.State.DISCONNECTED }
        c.onFirmwareFlash(false)
        until("back") { fake.writes.size == 8 }
    }

    /** Web #551 parity: three reconnects in a row with no reading stop auto-reconnect. */
    @Test
    fun aGrinderThatStopsSendingDataStopsReconnectingAfterThree() {
        val fake = FakeGrinder()
        val (c, m) = controller(fake)
        ui.update { it.copy(connectedGrinders = true, rememberedGrinderAddress = FakeGrinder.Handle.address, rememberedGrinderName = FakeGrinder.NAME) }
        c.hydrate()
        until("the handshake") { fake.writes.size == 4 }
        fake.send(FakeGrinder.period("IDLE", 3), seq = 2)
        until("live") { ui.value.connectedGrinder.status == GrinderLinkStatus.LIVE }
        // It goes quiet: silence, then every reconnect handshakes but never reads.
        fake.clock.addAndGet(6_000)
        lane.deliver(bridge.onTick(fake.clock.get().toULong()))
        for (n in 1..NO_DATA_GIVE_UP) {
            until("handshake ${n + 1}", timeoutMs = 20_000) { fake.writes.size == 4 * (n + 1) }
            until("ready ${n + 1}") { m.state.value == GrinderBleManager.State.READY }
            fake.clock.addAndGet(6_000)
            lane.deliver(bridge.onTick(fake.clock.get().toULong()))
            if (n < NO_DATA_GIVE_UP) until("reconnecting ${n + 1}") { m.state.value != GrinderBleManager.State.READY }
        }
        until("stopped") { ui.value.connectedGrinder.status == GrinderLinkStatus.NO_DATA && m.isUserDisconnected }
        assertEquals(GrinderMessages.NO_DATA, ui.value.connectedGrinder.message)
        val writes = fake.writes.size
        Thread.sleep(3_000)
        assertEquals(writes, fake.writes.size, "no more reconnects")
        // Reconnect starts over.
        c.reconnect()
        until("reconnected") { fake.writes.size == writes + 4 }
    }

    /** A first connect that never answers stops at once (no reconnect loop). */
    @Test
    fun aFirstConnectThatNeverAnswersStops() {
        val fake = FakeGrinder()
        val (c, m) = controller(fake)
        ui.update { it.copy(connectedGrinders = true, rememberedGrinderAddress = FakeGrinder.Handle.address, rememberedGrinderName = FakeGrinder.NAME) }
        c.hydrate()
        until("the handshake") { fake.writes.size == 4 }
        fake.clock.addAndGet(6_000)
        lane.deliver(bridge.onTick(fake.clock.get().toULong()))
        until("stopped") { m.isUserDisconnected && ui.value.connectedGrinder.status == GrinderLinkStatus.NO_ANSWER }
        assertTrue(ui.value.connectedGrinder.message!!.startsWith("The grinder didn't answer."))
    }

    /** G3: a linked grinder is handed to the core; its steady dial then sets
     *  Brew's grind, the stepper locks, Pause gives it back, and a bag pick
     *  onto it says "set the collar to …". */
    @Test
    fun aLinkedGrinderFollowsAndPauses() {
        val fake = FakeGrinder()
        val (c, _) = controller(fake)
        ui.update {
            it.copy(
                connectedGrinders = true,
                rememberedGrinderAddress = FakeGrinder.Handle.address,
                rememberedGrinderName = FakeGrinder.NAME,
                grinderLinkedAddress = FakeGrinder.Handle.address,
                grinderLinkedId = "grinder:motto",
                grinders = rowViewOf(listOf(motto())),
                activeGrinderId = "grinder:motto",
                qcGrind = "500",
            )
        }
        pushSelections()
        c.hydrate()
        assertEquals("grinder:motto", links.last(), "the link is the core's")
        until("the handshake") { fake.writes.size == 4 }
        var t = fake.clock.get()
        repeat(6) {
            t += 200
            fake.send(FakeGrinder.period("IDLE", 3, gap = 480), seq = 2, atMs = t)
        }
        until("Brew follows") { applied.lastOrNull() == "480" && ui.value.qcGrind == "480" }
        until("following") { ui.value.grinderFollow.status == coffee.crema.core.FollowStatus.Following }
        assertEquals(false, ui.value.gate(coffee.crema.settings.Setting.QC_GRIND).editable, "the collar sets it")
        assertEquals("from grinder \u00b7 900 rpm", ui.value.grindFollowNote())
        // Paused: the stepper is back and the collar no longer moves Brew's grind.
        c.pauseFollowing(true)
        until("paused") { ui.value.grinderFollow.status == coffee.crema.core.FollowStatus.Paused }
        assertEquals(true, ui.value.gate(coffee.crema.settings.Setting.QC_GRIND).editable)
        repeat(6) {
            t += 200
            fake.send(FakeGrinder.period("IDLE", 3, gap = 490), seq = 3, atMs = t)
        }
        Thread.sleep(200)
        assertEquals("480", ui.value.qcGrind)
        c.pauseFollowing(false)
        until("resumed") { ui.value.qcGrind == "490" }
        // A switch to remote drops the link from the core; back, it returns (review #555).
        ui.update { it.copy(proxyRole = "secondary") }
        c.syncCoreLink()
        assertEquals(null, links.last())
        ui.update { it.copy(proxyRole = "normal") }
        c.syncCoreLink()
        assertEquals("grinder:motto", links.last())
        // Beta off: the core loses the link.
        ui.update { it.copy(connectedGrinders = false) }
        c.onBetaChanged(false)
        assertEquals(null, links.last())
    }

    /** G3: a connected grinder whose record isn't active is a suggestion only. */
    @Test
    fun anotherActiveGrinderGetsTheUseChip() {
        val fake = FakeGrinder()
        val (c, _) = controller(fake)
        val niche = Grinder(id = "grinder:niche", name = "Niche", scale = GrindScale(0f, 50f, 1f, ""), updatedAt = 1L)
        ui.update {
            it.copy(
                connectedGrinders = true,
                rememberedGrinderAddress = FakeGrinder.Handle.address,
                rememberedGrinderName = FakeGrinder.NAME,
                grinderLinkedAddress = FakeGrinder.Handle.address,
                grinderLinkedId = "grinder:motto",
                grinders = rowViewOf(listOf(motto(), niche)),
                activeGrinderId = "grinder:niche",
                qcGrind = "14",
            )
        }
        pushSelections()
        c.hydrate()
        until("the handshake") { fake.writes.size == 4 }
        var t = fake.clock.get()
        repeat(6) {
            t += 200
            fake.send(FakeGrinder.period("IDLE", 3), seq = 2, atMs = t)
        }
        until("the chip") { ui.value.followSuggestion()?.id == "grinder:motto" }
        assertEquals("14", ui.value.qcGrind, "never switched or followed on its own")
        assertEquals(emptyList(), applied.toList())
    }
}
