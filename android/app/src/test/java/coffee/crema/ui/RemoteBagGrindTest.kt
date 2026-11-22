package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.DEFAULT_GRIND_SCALE
import coffee.crema.beans.newBean
import coffee.crema.ble.proxy.CodecFrameLink
import coffee.crema.ble.proxy.Frame
import coffee.crema.ble.proxy.Golden
import coffee.crema.ble.proxy.RelayHub
import coffee.crema.ble.proxy.TEST_PEER
import coffee.crema.core.BagGrind
import coffee.crema.core.Bean
import coffee.crema.core.Grinder
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.machine.MachineCommand
import coffee.crema.settings.ConfigSnapshot
import coffee.crema.settings.MirroredSettings
import coffee.crema.settings.v4Snapshot
import coffee.crema.ui.RemoteFixture.Companion.BEAN_P
import coffee.crema.ui.RemoteFixture.Companion.BEAN_S
import coffee.crema.ui.RemoteFixture.Companion.LIBRARY
import coffee.crema.ui.RemoteFixture.Companion.S
import coffee.crema.ui.brewlog.BrewLogOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A remote's grind against the hub's bag (#488 round 2, batch 4): its
 * unsaved-grind marker and Quick Save read the hub's saved grind from the
 * snapshot, never its own copy of the bag (REVIEW-FINAL S8); it relays a text
 * grind as text (follow-up 34); and Log brew stamps only a grinder its own
 * library has (follow-up 39). The hub half: its snapshot carries the bag's
 * saved grind, and sends it again when that changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RemoteBagGrindTest {

    private val store = ViewModelStore()
    private val fixture = RemoteFixture(store)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun tearDown() {
        scope.cancel()
        store.clear()
        VmHarness.idle()
        fixture.kill()
    }

    private val niche = Grinder(id = "grinder:niche-zero", name = "Niche Zero", scale = DEFAULT_GRIND_SCALE, updatedAt = 1L)

    private fun bag(id: String, saved: String?): Bean =
        newBean("S bag", null, null, null, 0L).copy(id = id, grinds = saved?.let { listOf(BagGrind(niche.id, niche.name, it, starred = true)) })

    /** The hub's snapshot: bag S active with the Niche, Brew at [grind], [saved] saved for it. */
    private fun hub(grind: String?, saved: String?): ConfigSnapshot = v4Snapshot().copy(
        activeBeanId = BEAN_S, activeBagName = "S bag",
        activeBagGrind = saved?.let { BagGrind(niche.id, niche.name, it, starred = true) },
        activeGrinderId = niche.id, activeGrinderName = niche.name, activeGrinderScale = niche.scale,
        qcGrind = grind,
    )

    /** What a remote's screens show with [own] as its own bags and [mirror] its slice. */
    private fun remote(own: List<Bean>, mirror: MirroredSettings): MainUiState =
        MainUiState(beans = rowViewOf(own), grinders = rowViewOf(listOf(niche)), activeBeanId = BEAN_P, mirror = mirror).effective()

    private fun remote(own: List<Bean>, snapshot: ConfigSnapshot) = remote(own, MirroredSettings().applied(snapshot, null, null))

    // ── S8: the hub's saved grind ────────────────────────────────────────

    @Test
    fun `a remote's marker and Save compare with the hub's saved grind, not its own copy`() {
        val stale = listOf(bag(BEAN_S, saved = "10"))
        val same = remote(stale, hub(grind = "14", saved = "14"))
        assertNull(same.brewGrindView().marker, "Brew is at the hub's saved 14; this device's copy says 10")
        assertNull(same.quickSavePlan().grind, "nothing to save")

        val moved = remote(stale, hub(grind = "15", saved = "14"))
        assertEquals("Bag 14", moved.brewGrindView().marker?.label)
        val plan = moved.quickSavePlan()
        assertEquals("15", plan.grind)
        assertEquals(BEAN_S, plan.beanId)
        assertTrue(plan.enabled && plan.includesGrind, "$plan")
        assertEquals("S bag has 14 saved for Niche Zero. Brew is at 15 for the next shot. Save in Quick Controls saves it.", moved.unsavedGrindExplanation())
    }

    /** Libraries aren't synced: a remote without a copy of the hub's bag still gets the marker and Save. */
    @Test
    fun `a remote without a copy of the hub's bag still marks and saves the grind`() {
        val none = remote(emptyList(), hub(grind = "15", saved = "14"))
        assertEquals("Bag 14", none.brewGrindView().marker?.label)
        assertEquals("15", none.quickSavePlan().grind)
        assertEquals("S bag", none.quickSavePlan().beanName)

        val nothingSaved = remote(listOf(bag(BEAN_S, saved = "15")), hub(grind = "15", saved = null))
        assertEquals("Bag —", nothingSaved.brewGrindView().marker?.label, "the hub has none saved, whatever this copy says")
        assertEquals("15", nothingSaved.quickSavePlan().grind)

        val noBag = remote(emptyList(), hub(grind = "15", saved = null).copy(activeBeanId = null, activeBagName = null))
        assertNull(noBag.brewGrindView().marker)
        assertNull(noBag.quickSavePlan().grind)
    }

    /** A bag pick on its way to the hub compares with nothing (not this device's copy) until the hub's snapshot names it. */
    @Test
    fun `a pending bag pick never compares with this device's copy`() {
        val (slice, _) = MirroredSettings().applied(hub(grind = "15", saved = "14"), null, null)
            .withPending(MachineCommand.SetActiveBean(BEAN_P))
        val pending = remote(listOf(bag(BEAN_P, saved = "9")), slice)
        assertEquals(BEAN_P, pending.activeBeanId)
        assertNull(pending.grindBag())
        assertNull(pending.brewGrindView().marker)
        assertNull(pending.quickSavePlan().grind)
    }

    /** The hub half of S8: its snapshot carries its bag's saved grind and is sent again after a Save changes it. */
    @Test
    fun `the hub's snapshot carries its bag's saved grind and resends it after a save`() {
        File(VmHarness.app().filesDir, "beanLibrary.json").writeText(
            coreJson.encodeToString(
                BeanLibrary.serializer(),
                BeanLibrary(listOf(bag(BEAN_S, saved = "14")), emptyList(), BEAN_S, grinders = listOf(niche), activeGrinderId = niche.id),
            ),
        )
        VmHarness.persistRole("primary")
        val vm = VmHarness.build(store)
        VmHarness.awaitStartupLoaded(vm)
        val configs = CopyOnWriteArrayList<ConfigSnapshot>()
        val hub: RelayHub = assertNotNull(VmHarness.proxyController(vm).relayHub, "the primary built its relay")
        val (client, server) = CodecFrameLink.pair(scope)
        scope.launch { hub.serve(server, TEST_PEER) }
        var welcomed = false
        scope.launch {
            client.incoming().collect { f ->
                if (f is Frame.Welcome) welcomed = true
                if (f is Frame.Config && f.json.isNotEmpty()) configs += coreJson.decodeFromString(ConfigSnapshot.serializer(), f.json)
            }
        }
        VmHarness.idleUntil("the Welcome") { welcomed }
        vm.setQcGrind(15f)
        VmHarness.idleUntil("the hub's dial in its snapshot") { configs.lastOrNull()?.qcGrind == "15" }
        val before = configs.last()
        assertEquals("S bag", before.activeBagName)
        assertEquals(BagGrind(niche.id, niche.name, "14", starred = true), before.activeBagGrind)

        vm.quickSave()
        VmHarness.idleUntil("the saved grind in a new snapshot") { configs.lastOrNull()?.activeBagGrind?.grind == "15" }
    }

    // ── Follow-up 34: a text grind relays as text ────────────────────────

    @Test
    fun `start from this shot on a remote relays its text grind as text`() {
        coffee.crema.persist.SafeFile.openAllForTest()
        runBlocking {
            assertTrue(HistoryStore(VmHarness.app(), coreJson).save(listOf(StoredShot(id = "shot-text", completedAtMs = 1_000L, durationMs = 30_000, grindSetting = "2.1.3"))))
        }
        fixture.echoEdits = true
        val vm = fixture.start()
        fixture.push()
        assertNotNull(VmHarness.rows(vm).firstOrNull { it.id == "shot-text" }, "the shot is in this remote's history")
        vm.startFromShot("shot-text")
        VmHarness.idleUntil("the grind at the hub") { fixture.handled.any { it.first == "setQcGrind" } }
        assertEquals("setQcGrind" to "2.1.3", fixture.handled.first { it.first == "setQcGrind" })
        val wire = fixture.endpoint.received.first { "\"setQcGrind\"" in it }
        assertEquals(Golden.require("control/setQcGrind.text.json"), wire.replaceFirst(Regex("\"id\":\\d+"), "\"id\":1"))
        VmHarness.idleUntil("the hub's grind shown") { vm.ui.value.qcGrind == "2.1.3" }
        assertEquals("10", VmHarness.ownState(vm).qcGrind, "never written to this remote's own settings")
    }

    /** The stepper's number goes out as text on the hub's grinder's scale. */
    @Test
    fun `a remote's stepped grind goes out as text on the hub's scale`() {
        val vm = fixture.start()
        fixture.push(S.copy(activeGrinderId = "grinder:fine", activeGrinderName = "Fine", activeGrinderScale = coffee.crema.core.GrindScale(0f, 10f, 0.05f, "")))
        vm.setQcGrind(2.25f)
        VmHarness.idleUntil("the grind at the hub") { fixture.handled.any { it.first == "setQcGrind" } }
        assertEquals("setQcGrind" to "2.25", fixture.handled.first { it.first == "setQcGrind" })
    }

    // ── Follow-up 39: Log brew on a remote ───────────────────────────────

    private fun logBrew(vm: MainViewModel): coffee.crema.history.ShotRow {
        vm.openLogBrew(BrewLogOwner.HISTORY)
        vm.updateLogBrew { it.copy(method = "pourover", dose = 15.0, grind = 14.0) }
        assertTrue(vm.saveLogBrew(), "the form saved")
        VmHarness.idle()
        return VmHarness.rows(vm).first { it.brewMethod == "pourover" }
    }

    private val hubNiche = S.copy(activeGrinderId = niche.id, activeGrinderName = niche.name, activeGrinderScale = niche.scale)

    @Test
    fun `log brew on a remote stamps no grinder its own library lacks`() {
        val vm = fixture.start()
        fixture.push(hubNiche)
        assertEquals(niche.id, vm.ui.value.activeGrinder()?.id, "Brew shows the hub's grinder")
        val row = logBrew(vm)
        assertNull(row.grinderId, "no dangling hub id")
        assertNull(row.grinderModel)
        assertEquals("14", row.grindSetting, "the grind is kept")
    }

    @Test
    fun `log brew on a remote stamps its own record of the hub's grinder`() {
        val vm = fixture.start(library = LIBRARY.copy(grinders = listOf(niche)))
        fixture.push(hubNiche)
        val row = logBrew(vm)
        assertEquals(niche.id, row.grinderId)
        assertEquals("Niche Zero", row.grinderModel)
    }

    /** The same record is the same id and name: a renamed copy, or none, is no grinder here. */
    @Test
    fun `own active grinder on a remote is only the same record`() {
        val renamed = niche.copy(name = "Niche (old)")
        val snap = MirroredSettings().applied(hub(grind = "14", saved = null), null, null)
        fun own(grinders: List<Grinder>) =
            MainUiState(grinders = rowViewOf(grinders), mirror = snap).effective().ownActiveGrinder()
        assertEquals(niche, own(listOf(niche)))
        assertEquals(niche.copy(name = " niche zero "), own(listOf(niche.copy(name = " niche zero "))), "trimmed, any case")
        assertNull(own(listOf(renamed)))
        assertNull(own(emptyList()))
        assertNull(own(listOf(niche.copy(archivedAt = 5L))), "archived isn't live")
        val standalone = MainUiState(grinders = rowViewOf(listOf(niche)), activeGrinderId = niche.id)
        assertEquals(niche, standalone.ownActiveGrinder(), "off a remote, the active grinder")
    }
}
