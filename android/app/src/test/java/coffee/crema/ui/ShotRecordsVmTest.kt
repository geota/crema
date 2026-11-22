package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.newBean
import coffee.crema.core.Grinder
import coffee.crema.core.GrindScale
import coffee.crema.core.NotificationSource
import coffee.crema.core.ShotDisposition
import coffee.crema.ui.brewlog.BrewLogDraft
import coffee.crema.ui.brewlog.BrewLogOwner
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a shot records (round 2, batch 2), through the real view model and the
 * host build of the core: the selections the core froze at the shot's start
 * (REVIEW-CORE H3), what the completion does on the device that recorded it
 * and on a remote (M4, owner decision), the bag snapshot (REVIEW-FINAL S2,
 * N6) and a logged brew's grinder and grind (S4, follow-up 34).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ShotRecordsVmTest {
    @org.junit.Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    private val store = ViewModelStore()
    private val fixture = RemoteFixture(store)

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
        fixture.kill()
    }

    private val fine = Grinder(id = "grinder:fine", name = "Fine Step", scale = GrindScale(0f, 30f, 0.05f, ""), updatedAt = 1L)

    private fun bag(id: String, remaining: Float = 200f) = newBean(id, null, null, null, 0L).copy(
        id = id, name = "Bag $id", remaining = remaining, bagSize = 250f, updatedAt = 1L,
        // The legacy fields a new snapshot never copies (S2), and the notes it keeps (N6).
        grinderSetting = "4", grinder = "Old grinder", notes = "Notes of $id",
    )

    private fun vmWith(library: BeanLibrary): MainViewModel {
        File(VmHarness.app().filesDir, "beanLibrary.json").writeText(coreJson.encodeToString(BeanLibrary.serializer(), library))
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(vm)
        VmHarness.awaitStartupLoaded(vm)
        return vm
    }

    private fun MainViewModel.bean(id: String) = VmHarness.ownState(this).beans.single { it.id == id }

    /** The core's own test `ShotSample`; byte 17 is the frame number. */
    private fun sampleIn(frame: Int): ByteArray = intArrayOf(
        0x12, 0x34, 0x90, 0x00, 0x28, 0x00, 0x5C, 0x80, 0x00, 0x58, 0x00, 0x5A, 0x00, 0x5D, 0x00,
        0x60, 0x40, frame, 0x96,
    ).map { it.toByte() }.toByteArray()

    private fun apply(vm: MainViewModel, raw: String) {
        VmHarness.deliverCoreOutput(vm, raw)
        VmHarness.idle()
    }

    /** A 12 s pour through frames 0 → 2 → 3, then Idle: a shot, not an aborted pull. */
    private fun pour(vm: MainViewModel) {
        val bridge = VmHarness.bridge(vm)
        apply(vm, bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(4, 5), 1_000uL))
        for (i in 1..48) {
            apply(vm, bridge.onNotification(NotificationSource.DE1_SHOT_SAMPLE, sampleIn(listOf(0, 2, 2, 3)[minOf(i - 1, 3)]), (1_000 + 250 * i).toULong()))
        }
        apply(vm, bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(2, 0), (1_000 + 250 * 49).toULong()))
    }

    /** The device that records a shot debits its bag and offers it for upload. */
    @Test
    fun `a shot recorded here debits its bag and is offered for upload`() {
        val vm = vmWith(BeanLibrary(listOf(bag("a")), emptyList(), "a"))
        val offered = mutableListOf<String>()
        VmHarness.library(vm).offeredForUpload = { offered += it.id }
        pour(vm)
        VmHarness.idleUntil("the shot to be recorded", 5_000) { VmHarness.rows(vm).isNotEmpty() }
        val shot = VmHarness.whole(vm, VmHarness.rows(vm).first().id)
        assertEquals("a", shot.bean?.beanId)
        assertTrue(vm.bean("a").remaining!! < 200f, "debited")
        assertEquals(listOf(shot.id), offered)
    }

    /** Owner decision (round 2): a remote's mirror copy of the hub's shot is
     *  labelled with the hub's selections, never debits this device's own copy
     *  of the hub's bag and is never uploaded (the hub does both). */
    @Test
    fun `a remote records the hub's bag and neither debits nor uploads it`() {
        val library = BeanLibrary(listOf(bag(RemoteFixture.BEAN_P), bag(RemoteFixture.BEAN_S)), emptyList(), RemoteFixture.BEAN_P)
        val vm = fixture.start(library = library)
        val offered = mutableListOf<String>()
        VmHarness.library(vm).offeredForUpload = { offered += it.id }
        fixture.push() // the hub's active bag is BEAN_S
        assertEquals(RemoteFixture.BEAN_S, vm.ui.value.activeBeanId)
        assertEquals(RemoteFixture.BEAN_P, VmHarness.ownState(vm).activeBeanId, "this device's own pick is its own")
        pour(vm)
        VmHarness.idleUntil("the mirror copy to be recorded", 5_000) { VmHarness.rows(vm).isNotEmpty() }
        val shot = VmHarness.whole(vm, VmHarness.rows(vm).first().id)
        assertEquals(RemoteFixture.BEAN_S, shot.bean?.beanId, "the hub's bag, as the hub's own row says")
        // REVIEW-FINAL-2 MF1: 0, not null, so a later move credits nothing.
        assertEquals(0f, shot.bagDebitG, "a remote's copy takes nothing, and says so")
        assertEquals(200f, vm.bean(RemoteFixture.BEAN_S).remaining, "the hub debits its own bag")
        assertEquals(200f, vm.bean(RemoteFixture.BEAN_P).remaining)
        assertEquals(emptyList(), offered, "the hub uploads its own row")
    }

    /** REVIEW-FINAL-2 MF1 repro: a remote moves its mirror copy of the hub's
     *  18 g shot to another bag in History. The bag it named gets back nothing
     *  it never lost, and the new bag isn't debited on a remote (core
     *  `shot_bag_move`). Both a row written now (0) and one an older build
     *  wrote (null, REVIEW-518 follow-up 1). */
    @Test
    fun `a remote's mirror shot moved in History credits and debits nothing`() {
        fun mirrorRow(id: String, bagDebit: Float?) = coffee.crema.history.StoredShot(
            id = id, completedAtMs = 1_700_000_000_000, durationMs = 28_000, doseG = 18f, bagDebitG = bagDebit,
            bean = coffee.crema.core.ShotBean(beanId = RemoteFixture.BEAN_S, name = "Bag ${RemoteFixture.BEAN_S}"),
        )
        kotlinx.coroutines.runBlocking {
            coffee.crema.history.HistoryStore(VmHarness.app(), coreJson).save(listOf(mirrorRow("now", 0f), mirrorRow("older", null)))
        }
        val library = BeanLibrary(listOf(bag(RemoteFixture.BEAN_P), bag(RemoteFixture.BEAN_S)), emptyList(), RemoteFixture.BEAN_P)
        val vm = fixture.start(library = library)
        fixture.push()
        VmHarness.idleUntil("the rows") { VmHarness.rows(vm).size == 2 }
        for (id in listOf("now", "older")) {
            vm.setShotBean(id, RemoteFixture.BEAN_P)
            VmHarness.idle()
            val row = VmHarness.rows(vm).single { it.id == id }
            assertEquals(RemoteFixture.BEAN_P, row.bean?.beanId, "$id moved")
            assertEquals(0f, row.bagDebitG, "$id took nothing here")
            assertEquals(200f, vm.bean(RemoteFixture.BEAN_S).remaining, "$id: no coffee that doesn't exist")
            assertEquals(200f, vm.bean(RemoteFixture.BEAN_P).remaining, "$id: a remote never debits its own copy")
        }
        vm.setShotBean("older", null)
        VmHarness.idle()
        assertEquals(200f, vm.bean(RemoteFixture.BEAN_P).remaining, "to no bag credits nothing either")
        assertNull(VmHarness.rows(vm).single { it.id == "older" }.bagDebitG, "no bag, no debit")
    }

    /** REVIEW-521 M1 probe: on a remote, a row this device recorded and debited
     *  itself (a logged brew; a shot from when it held the machine) moves as on
     *  any device: the old bag gets its grams back and the new bag is debited. */
    @Test
    fun `on a remote, a row recorded here moves its debit with it`() {
        val hubEra = coffee.crema.history.StoredShot(
            id = "hub-era", completedAtMs = 1_700_000_000_000, durationMs = 28_000, doseG = 18f, bagDebitG = 18f,
            bean = coffee.crema.core.ShotBean(beanId = RemoteFixture.BEAN_S, name = "Bag ${RemoteFixture.BEAN_S}"),
        )
        kotlinx.coroutines.runBlocking { coffee.crema.history.HistoryStore(VmHarness.app(), coreJson).save(listOf(hubEra)) }
        val library = BeanLibrary(listOf(bag(RemoteFixture.BEAN_P), bag(RemoteFixture.BEAN_S)), emptyList(), RemoteFixture.BEAN_P)
        val vm = fixture.start(library = library)
        fixture.push()
        VmHarness.idleUntil("the row") { VmHarness.rows(vm).isNotEmpty() }
        val brew = vm.addManualBrew(
            LibraryController.ManualBrewInput(
                method = "pourover", completedAtMs = 1_700_000_100_000, beanId = RemoteFixture.BEAN_S, doseG = 18f, waterG = 300f,
                yieldG = null, grind = null, brewTempC = null, durationMs = 180_000, rating = null, notes = null, nextPlan = null,
            ),
        )
        VmHarness.idle()
        assertEquals(182f, vm.bean(RemoteFixture.BEAN_S).remaining, "a brew logged here debits its bag")
        vm.setShotBean(brew.id, RemoteFixture.BEAN_P)
        VmHarness.idle()
        assertEquals(200f, vm.bean(RemoteFixture.BEAN_S).remaining, "S gets the 18 g back")
        assertEquals(182f, vm.bean(RemoteFixture.BEAN_P).remaining, "P lost the 18 g the brew used")
        assertEquals(18f, VmHarness.rows(vm).single { it.id == brew.id }.bagDebitG)
        vm.setShotBean(brew.id, RemoteFixture.BEAN_S)
        VmHarness.idle()
        assertEquals(200f, vm.bean(RemoteFixture.BEAN_P).remaining)
        assertEquals(182f, vm.bean(RemoteFixture.BEAN_S).remaining, "and back: no coffee vanishes")
        // A shot this device debited while it held the machine.
        vm.setShotBean("hub-era", RemoteFixture.BEAN_P)
        VmHarness.idle()
        assertEquals(200f, vm.bean(RemoteFixture.BEAN_S).remaining)
        assertEquals(182f, vm.bean(RemoteFixture.BEAN_P).remaining)
        assertEquals(18f, VmHarness.rows(vm).single { it.id == "hub-era" }.bagDebitG)
    }

    /** A completed shot's side effects are the core's rule (REVIEW-CORE M4). */
    @Test
    fun `the completion side effects come from the core`() {
        val here = shotEffects(ShotDisposition.Record, recordedHere = true, replay = false)
        assertTrue(here.record && here.debit && here.autoSave && here.upload)
        val remote = shotEffects(ShotDisposition.Record, recordedHere = false, replay = false)
        assertTrue(remote.record)
        assertFalse(remote.debit || remote.autoSave || remote.upload, "a remote's mirror copy is history only")
        val replay = shotEffects(ShotDisposition.Record, recordedHere = true, replay = true)
        assertTrue(replay.record, "a replay is kept in History")
        assertFalse(replay.debit || replay.autoSave || replay.webhook || replay.upload, "and never uploads (#488 batch 6)")
        val aborted = shotEffects(ShotDisposition.DiscardAborted, recordedHere = true, replay = false)
        assertTrue(aborted.offerUndo && aborted.debit && !aborted.record, "the dose was ground; the row waits for the Undo")
        val cleaning = shotEffects(ShotDisposition.SkipCleaning, recordedHere = true, replay = false)
        assertFalse(cleaning.record || cleaning.offerUndo || cleaning.debit)
        // A bridge that refuses still records the shot, and does nothing else.
        val refused = shotEffects(ShotDisposition.Record, recordedHere = true, replay = false) { error("no core") }
        assertTrue(refused.record)
        assertFalse(refused.debit || refused.upload || refused.autoSave)
    }

    /** S4 / follow-up 34: a logged brew is stamped with the active grinder,
     *  its grind is text on that grinder's scale, and a grind of 0 is kept. */
    @Test
    fun `a logged brew is stamped with the active grinder and keeps a grind of 0`() {
        val vm = vmWith(BeanLibrary(listOf(bag("a")), emptyList(), "a", grinders = listOf(fine), activeGrinderId = fine.id))
        fun log(grind: Double?): coffee.crema.history.StoredShot {
            val before = VmHarness.rows(vm).size
            vm.openLogBrew(BrewLogOwner.HISTORY)
            vm.updateLogBrew { it.edited(BrewLogDraft.GRIND).edited(BrewLogDraft.DOSE).copy(method = "espresso", grind = grind, dose = 18.0) }
            assertTrue(vm.saveLogBrew())
            VmHarness.idleUntil("the brew to be logged") { VmHarness.rows(vm).size > before }
            return VmHarness.whole(vm, VmHarness.rows(vm).first().id)
        }
        val zero = log(0.0)
        assertEquals("0", zero.grindSetting, "0 is a grind")
        assertEquals(fine.id, zero.grinderId)
        assertEquals("Fine Step", zero.grinderModel)
        assertEquals("14.05", log(14.05).grindSetting, "the grinder's own decimals")
        val none = log(null)
        assertNull(none.grindSetting, "not set")
        assertEquals(fine.id, none.grinderId, "the grinder is still the one in use")
    }

    /** S2 / N6: a logged brew's and a moved shot's bag snapshot are the core's:
     *  the bag's notes, never its legacy grind and grinder. */
    @Test
    fun `bag snapshots keep the notes and never the legacy grind`() {
        val vm = vmWith(BeanLibrary(listOf(bag("a"), bag("b")), emptyList(), "a"))
        val logged = vm.addManualBrew(
            LibraryController.ManualBrewInput(
                method = "espresso", completedAtMs = 1_700_000_000_000, beanId = "a", doseG = 18f, waterG = null,
                yieldG = 36f, grind = null, brewTempC = null, durationMs = 28_000, rating = null, notes = null, nextPlan = null,
            ),
        )
        VmHarness.idle()
        val a = assertNotNull(logged.bean)
        assertEquals("Notes of a", a.notes)
        assertNull(a.grinderSetting, "no legacy grind on a new snapshot")
        assertNull(a.grinder, "no legacy grinder")
        vm.setShotBean(logged.id, "b")
        VmHarness.idle()
        val b = assertNotNull(VmHarness.whole(vm, logged.id).bean)
        assertEquals("b", b.beanId)
        assertEquals("Notes of b", b.notes, "the moved shot keeps the new bag's notes")
        assertNull(b.grinderSetting)
        assertNull(b.grinder)
    }
}
