package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.newBean
import coffee.crema.beans.savedGrind
import coffee.crema.core.NotificationSource
import coffee.crema.history.toRow
import coffee.crema.history.withHeavy
import coffee.crema.profiles.CremaProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The save-boundary review (2026-11-21), Android phase A, through the real
 * view model and the host build of the core:
 *  - F1: Quick Controls' Save writes each changed part to its owner;
 *  - F5: deleting the selected bag or profile clears the selection;
 *  - F9: a deleted profile's bag links are cleared with a fresh `updatedAt`,
 *    and a shot records (and debits) the bag it started with.
 * (F8, Save on a remote, is in [RemoteSettingsTest].)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SaveBoundariesVmTest {
    @org.junit.Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    private val store = ViewModelStore()

    @After fun tearDown() { store.clear(); VmHarness.idle() }

    private fun bag(id: String, grind: String? = "4", remaining: Float = 200f, linked: String? = null) =
        newBean(id, null, null, null, 0L).copy(
            id = id, name = "Bag $id", grinderSetting = grind, remaining = remaining, bagSize = 250f,
            linkedProfileId = linked, updatedAt = 1L,
        )

    private fun vmWith(beans: List<coffee.crema.core.Bean> = listOf(bag("a")), active: String? = beans.firstOrNull()?.id): MainViewModel {
        val library = BeanLibrary(beans, emptyList(), active)
        File(VmHarness.app().filesDir, "beanLibrary.json").writeText(coreJson.encodeToString(BeanLibrary.serializer(), library))
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(vm)
        VmHarness.awaitStartupLoaded(vm)
        return vm
    }

    /** Add a custom profile copied from the first built-in, and make it active. */
    private fun activeCustom(vm: MainViewModel, id: String = "custom-a"): CremaProfile {
        val full = Json(coreJson) { encodeDefaults = true }
        val base = vm.ui.value.profiles.first { it.source != "custom" }
        val raw = full.encodeToString(CremaProfile.serializer(), base.copy(id = id, name = "Mine", source = "custom"))
        runBlocking { VmHarness.library(vm).adoptCustomProfile(raw) }
        vm.setActiveProfile(id)
        VmHarness.idle()
        return vm.ui.value.profiles.first { it.id == id }
    }

    private fun activeBuiltin(vm: MainViewModel): CremaProfile {
        val p = vm.ui.value.profiles.first { it.source != "custom" }
        vm.setActiveProfile(p.id)
        VmHarness.idle()
        return p
    }

    /** Dial a dose 1 g over the active profile's. */
    private fun dialDose(vm: MainViewModel): Double {
        val p = assertNotNull(vm.ui.value.activeProfile())
        val dose = p.dose + 1.0
        vm.quickAdjustBrew(dose, p.yieldOut.toDouble(), vm.brewDial()?.anchor ?: p.brewTemp.toDouble(), null)
        VmHarness.idle()
        assertNotNull(vm.ui.value.brewParams, "the dial is an override")
        return dose
    }

    private fun MainViewModel.bean(id: String) = VmHarness.ownState(this).beans.single { it.id == id }
    private fun MainViewModel.customs() = ui.value.profiles.filter { it.source == "custom" }
    private fun MainViewModel.messages() = ui.value.userMessages

    // ── F1: Quick Controls Save ──────────────────────────────────────────────

    @Test
    fun `nothing changed - Save is off and writes nothing`() {
        val vm = vmWith()
        activeCustom(vm)
        val plan = vm.ui.value.quickSavePlan()
        assertFalse(plan.enabled)
        assertEquals("Save", plan.label)
        val before = vm.customs()
        vm.quickSave()
        VmHarness.idle()
        assertEquals(before, vm.customs())
        assertEquals("4", vm.bean("a").savedGrind(null)?.grind)
    }

    @Test
    fun `only the grind changed - just the grind goes to the bag`() {
        val vm = vmWith()
        activeBuiltin(vm)
        vm.setQcGrind(5.5f)
        VmHarness.idle()
        val plan = vm.ui.value.quickSavePlan()
        assertTrue(plan.enabled)
        assertFalse(plan.needsName, "no name prompt for a grind")
        val profiles = vm.ui.value.profiles
        val active = vm.ui.value.activeProfileId
        vm.quickSave()
        VmHarness.idle()
        assertEquals("5.5", vm.bean("a").savedGrind(null)?.grind)
        assertEquals(profiles, vm.ui.value.profiles, "no profile written")
        assertEquals(active, vm.ui.value.activeProfileId)
        assertTrue("Grind 5.5 saved to Bag a" in vm.messages(), "${vm.messages()}")
    }

    @Test
    fun `no profile loaded - Save still saves the grind`() {
        val vm = vmWith()
        val custom = activeCustom(vm)
        vm.deleteProfile(custom.id)
        VmHarness.idle()
        assertNull(vm.ui.value.activeProfileId)
        vm.setQcGrind(6f)
        VmHarness.idle()
        assertTrue(vm.ui.value.quickSavePlan().enabled)
        vm.quickSave()
        VmHarness.idle()
        assertEquals("6", vm.bean("a").savedGrind(null)?.grind)
    }

    @Test
    fun `custom profile - updated in place, and Undo puts it back`() {
        val vm = vmWith()
        val custom = activeCustom(vm)
        val dose = dialDose(vm)
        val plan = vm.ui.value.quickSavePlan()
        assertFalse(plan.needsName)
        assertTrue(plan.offersSaveAsNew)
        vm.quickSave()
        VmHarness.idle()
        assertEquals(1, vm.customs().size, "no copy")
        assertEquals(dose.toFloat(), vm.customs().single().dose)
        assertEquals(custom.id, vm.ui.value.activeProfileId)
        assertNull(vm.ui.value.brewParams, "the dial lives in the profile now")
        val undo = assertNotNull(vm.ui.value.quickSaveUndo, "the message offers Undo")
        assertEquals("“Mine” updated", undo.message)

        vm.undoQuickSave(undo.seq)
        VmHarness.idle()
        assertEquals(custom.dose, vm.customs().single().dose, "the previous body")
        assertEquals(dose, vm.ui.value.brewParams?.dose, "and the dial as it was")
        assertNull(vm.ui.value.quickSaveUndo)
    }

    @Test
    fun `custom profile - Save as new makes a named copy and keeps the original`() {
        val vm = vmWith()
        val custom = activeCustom(vm)
        val dose = dialDose(vm)
        vm.quickSave(null, asNew = true)
        VmHarness.idle()
        assertEquals(1, vm.customs().size, "no name, nothing written")
        vm.quickSave("Mine stronger", asNew = true)
        VmHarness.idle()
        val copy = vm.customs().single { it.name == "Mine stronger" }
        assertEquals(dose.toFloat(), copy.dose)
        assertEquals(custom.dose, vm.customs().single { it.id == custom.id }.dose, "the original is untouched")
        assertEquals(copy.id, vm.ui.value.activeProfileId)
    }

    @Test
    fun `built-in profile - a cancelled name prompt writes nothing, a name makes a copy`() {
        val vm = vmWith()
        activeBuiltin(vm)
        val dose = dialDose(vm)
        vm.setQcGrind(7f)
        VmHarness.idle()
        val plan = vm.ui.value.quickSavePlan()
        assertTrue(plan.needsName)
        assertFalse(plan.offersSaveAsNew)
        // The prompt was cancelled: the sheet never calls Save. A Save without
        // the name it needs writes nothing either, the grind included.
        vm.quickSave()
        VmHarness.idle()
        assertTrue(vm.customs().isEmpty())
        assertEquals("4", vm.bean("a").savedGrind(null)?.grind, "the grind waits for the confirm too")

        vm.quickSave("My blend")
        VmHarness.idle()
        val copy = vm.customs().single()
        assertEquals("My blend", copy.name)
        assertEquals(dose.toFloat(), copy.dose)
        assertEquals(copy.id, vm.ui.value.activeProfileId)
        assertEquals("7", vm.bean("a").savedGrind(null)?.grind, "both changed: both written")
        assertTrue("Saved “My blend” · grind 7 saved to Bag a" in vm.messages(), "one message: ${vm.messages()}")
    }

    @Test
    fun `both changed on a custom - both written in one message, Undo reverts both`() {
        val vm = vmWith()
        val custom = activeCustom(vm)
        dialDose(vm)
        vm.setQcGrind(3f)
        VmHarness.idle()
        vm.quickSave()
        VmHarness.idle()
        assertEquals("3", vm.bean("a").savedGrind(null)?.grind)
        val undo = assertNotNull(vm.ui.value.quickSaveUndo)
        assertEquals("“Mine” updated · grind 3 saved to Bag a", undo.message)
        vm.undoQuickSave(undo.seq)
        VmHarness.idle()
        assertEquals("4", vm.bean("a").savedGrind(null)?.grind)
        assertEquals(custom.dose, vm.customs().single().dose)
    }

    /** Review 496 M1: Undo restores only the body the Save wrote. */
    @Test
    fun `undo after a later edit of the profile keeps that edit`() {
        val vm = vmWith()
        val custom = activeCustom(vm)
        dialDose(vm)
        vm.quickSave(); VmHarness.idle()
        val undo = assertNotNull(vm.ui.value.quickSaveUndo)
        val pinned = !vm.customs().single().pinned
        vm.togglePinProfile(custom.id); VmHarness.idle()
        vm.undoQuickSave(undo.seq); VmHarness.idle()
        assertEquals(pinned, vm.customs().single().pinned, "Undo must not revert the later pin")
        assertTrue("“Mine” changed since; not undone" in vm.messages(), "${vm.messages()}")
        assertNull(vm.ui.value.brewParams, "no dial back without its profile body")
    }

    /** PLAN follow-up 3: a newer Save retires the older Save's Undo, even one
     *  with no Undo of its own; the old message's Undo then does nothing. */
    @Test
    fun `a second Save dismisses the older Undo message`() {
        val vm = vmWith()
        activeCustom(vm)
        val dose = dialDose(vm)
        vm.quickSave(); VmHarness.idle()
        val first = assertNotNull(vm.ui.value.quickSaveUndo, "the first Save offers Undo")
        // Then a grind-only Save: no Undo of its own.
        vm.setQcGrind(6f); VmHarness.idle()
        vm.quickSave(); VmHarness.idle()
        assertEquals("6", vm.bean("a").savedGrind(null)?.grind)
        assertNull(vm.ui.value.quickSaveUndo, "the older Undo message goes")
        vm.undoQuickSave(first.seq); VmHarness.idle()
        assertEquals(dose.toFloat(), vm.customs().single().dose, "the older Undo does nothing now")
    }

    /** REVIEW-CORE M2: the core's Undo checks each half; the profile comes back
     *  while the grind changed since stands, and the message says which. */
    @Test
    fun `undo after a later grind edit puts the profile back and keeps the grind`() {
        val vm = vmWith()
        val custom = activeCustom(vm)
        dialDose(vm)
        vm.setQcGrind(3f); VmHarness.idle()
        vm.quickSave(); VmHarness.idle()
        val undo = assertNotNull(vm.ui.value.quickSaveUndo)
        // The bag editor sets another grind for the same row since.
        val opened = vm.bean("a")
        vm.updateBean("a", "", opened = opened) { b -> b.copy(grinds = b.grinds.orEmpty().map { it.copy(grind = "8") }) }
        VmHarness.idle()
        assertEquals("8", vm.bean("a").savedGrind(null)?.grind)
        vm.undoQuickSave(undo.seq); VmHarness.idle()
        assertEquals(custom.dose, vm.customs().single().dose, "the profile is put back")
        assertEquals("8", vm.bean("a").savedGrind(null)?.grind, "the later grind stands")
        assertTrue("“Mine” put back; the grind changed since, not undone" in vm.messages(), "${vm.messages()}")
    }

    /** PLAN follow-up 13: Undo puts a bag with no grind rows back to none, not an empty list. */
    @Test
    fun `undo of a grind saved to a bag with none restores none`() {
        val vm = vmWith(listOf(bag("a", grind = null)))
        // A bag made after the grinder migration has no rows at all (null).
        vm.updateBean("a", "") { it.copy(grinds = null) }
        VmHarness.idle()
        assertNull(vm.bean("a").grinds, "the bag starts with no grind rows")
        val custom = activeCustom(vm)
        dialDose(vm)
        vm.setQcGrind(3f); VmHarness.idle()
        vm.quickSave(); VmHarness.idle()
        assertEquals("3", vm.bean("a").savedGrind(null)?.grind)
        vm.undoQuickSave(assertNotNull(vm.ui.value.quickSaveUndo).seq); VmHarness.idle()
        assertEquals(custom.dose, vm.customs().single().dose)
        assertNull(vm.bean("a").grinds, "none, as before the Save")
        assertTrue("Save undone" in vm.messages(), "${vm.messages()}")
    }

    // ── F5: deleting the selection clears it ─────────────────────────────────

    @Test
    fun `deleting the active profile clears the selection, and it stays clear`() {
        val vm = vmWith()
        val custom = activeCustom(vm)
        vm.deleteProfile(custom.id)
        VmHarness.idle()
        assertNull(vm.ui.value.activeProfileId, "no profile, not the first remaining")
        // A later refresh of the list doesn't pick one either.
        vm.togglePinProfile(vm.ui.value.profiles.first().id)
        VmHarness.idle()
        assertNull(vm.ui.value.activeProfileId)
        // And the shot path says so instead of running something else.
        vm.startShot()
        VmHarness.idle()
        assertEquals("Can’t start shot — no profile selected", vm.ui.value.shotStartError ?: vm.messages().lastOrNull())
    }

    /** PLAN follow-up 5: a cleared profile or bag stays cleared across a
     *  restart; only a fresh install gets the first profile. */
    @Test
    fun `a cleared profile and bag stay cleared across a restart`() {
        val vm = vmWith(listOf(bag("a"), bag("b")), active = "b")
        assertNotNull(vm.ui.value.activeProfileId, "a fresh install picks the first profile")
        val custom = activeCustom(vm)
        vm.deleteProfile(custom.id)
        vm.deleteBean("b")
        VmHarness.idle()
        assertNull(vm.ui.value.activeProfileId)
        VmHarness.idleUntil("the cleared selection saved") { RemoteFixture.savedPrefs().let { it.profileCleared && it.activeProfileId == null } }
        VmHarness.idleUntil("the library saved") { RemoteFixture.savedActiveBean() == null }
        store.clear()
        VmHarness.idle()

        val next = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(next)
        VmHarness.awaitStartupLoaded(next)
        assertNull(next.ui.value.activeProfileId, "still no profile after the restart")
        assertNull(VmHarness.ownState(next).activeBeanId, "still no bag")
        // A pick is a selection again: the next launch keeps it.
        val picked = next.ui.value.profiles.first().id
        next.setActiveProfile(picked)
        VmHarness.idle()
        assertFalse(next.ui.value.profileCleared)
        VmHarness.idleUntil("the pick saved") { RemoteFixture.savedPrefs().let { !it.profileCleared && it.activeProfileId == picked } }
    }

    @Test
    fun `deleting the active bag clears the selection`() {
        val vm = vmWith(listOf(bag("a"), bag("b")), active = "b")
        vm.deleteBean("b")
        VmHarness.idle()
        assertNull(VmHarness.ownState(vm).activeBeanId, "no bag, not the first remaining")
        assertEquals(listOf("a"), VmHarness.ownState(vm).beans.map { it.id })
    }

    /** PLAN follow-up 10: the bag open in the editor deleted elsewhere (a
     *  sync) says so; this device's own delete doesn't. */
    @Test
    fun `a bag deleted elsewhere while its editor is open says so`() {
        val vm = vmWith(listOf(bag("a"), bag("b")), active = "a")
        vm.startEditBean("b")
        VmHarness.idle()
        assertNull(vm.ui.value.editingBeanGone)
        // A Visualizer sync removes it.
        VmHarness.library(vm).editBeanLibrary { lib -> lib.copy(beans = lib.beans.filter { it.id != "b" }) }
        VmHarness.idle()
        assertEquals("Bag b", vm.ui.value.editingBeanGone)
        assertTrue("“Bag b” was deleted elsewhere; your changes weren’t saved" in vm.messages(), "${vm.messages()}")
        assertEquals("“Bag b” was deleted elsewhere (a sync or another device), so your changes weren’t saved.", coffee.crema.ui.screens.beanEditorEmptyText(vm.ui.value.editingBeanGone))

        // Its own Delete is no news.
        vm.startEditBean("a")
        VmHarness.idle()
        assertNull(vm.ui.value.editingBeanGone)
        val before = vm.messages().size
        vm.deleteBean("a")
        VmHarness.idle()
        assertNull(vm.ui.value.editingBeanGone)
        assertEquals(before, vm.messages().size, "${vm.messages()}")
    }

    @Test
    fun `deleting another bag keeps the selection`() {
        val vm = vmWith(listOf(bag("a"), bag("b")), active = "b")
        vm.deleteBean("a")
        VmHarness.idle()
        assertEquals("b", VmHarness.ownState(vm).activeBeanId)
    }

    // ── #488 review S5: a bag deleted here stays deleted ────────────────────

    /** #41: a bag deleted while its first upload runs keeps a tombstone (the
     *  core matches the remote it creates to it), and one whose upload isn't
     *  running goes outright. */
    @Test
    fun `a bag deleted while its first upload runs keeps a tombstone`() {
        val vm = vmWith(listOf(bag("a"), bag("b")), active = "b")
        coffee.crema.visualizer.BagFirstUploads.start("a")
        try {
            vm.deleteBean("a")
            VmHarness.idle()
        } finally {
            coffee.crema.visualizer.BagFirstUploads.done("a")
        }
        val rows = VmHarness.ownState(vm).beans.withTombstones()
        assertNotNull(rows.single { it.id == "a" }.deletedAt, "kept, as a tombstone")
        assertEquals(listOf("b"), VmHarness.ownState(vm).beans.map { it.id }, "hidden from the library")
        vm.deleteBean("b")
        VmHarness.idle()
        assertTrue(VmHarness.ownState(vm).beans.withTombstones().none { it.id == "b" }, "no upload: removed outright")
    }


    /** REVIEW-516 follow-up 1: the editor's Save over a bag tombstoned while
     *  it was open writes nothing to the tombstone, and says so. */
    @Test
    fun `a Save over a tombstoned bag leaves the tombstone as it is`() {
        val vm = vmWith(listOf(bag("a").copy(visualizerId = "b1"), bag("b")), active = "b")
        vm.startEditBean("a")
        VmHarness.idle()
        val opened = vm.bean("a")
        vm.deleteBean("a")
        VmHarness.idle()
        val tomb = VmHarness.ownState(vm).beans.withTombstones().single { it.id == "a" }
        assertNotNull(tomb.deletedAt)
        vm.updateBean("a", "Some roaster", opened = opened) { it.copy(notes = "written after the delete", tags = listOf("late")) }
        VmHarness.idle()
        assertEquals(tomb, VmHarness.ownState(vm).beans.withTombstones().single { it.id == "a" }, "the tombstone stays stripped")
        assertTrue(VmHarness.ownState(vm).roasters.none { it.name == "Some roaster" }, "no roaster made for it either")
        assertEquals("Bag a", vm.ui.value.editingBeanGone)
        assertTrue("“Bag a” was deleted elsewhere; your changes weren’t saved" in vm.messages(), "${vm.messages()}")
    }

    @Test
    fun `a bag on Visualizer deleted here stays as a hidden tombstone`() {
        val vm = vmWith(
            listOf(bag("a", linked = "custom-a").copy(visualizerId = "b1", imageRef = "bean:a", notes = "lovely", tags = listOf("washed")), bag("b")),
            active = "a",
        )
        vm.deleteBean("a")
        VmHarness.idle()
        val s = VmHarness.ownState(vm)
        assertEquals(listOf("b"), s.beans.map { it.id }, "hidden from the library")
        assertNull(s.activeBeanId)
        val tomb = s.beans.withTombstones().single { it.id == "a" }
        assertNotNull(tomb.deletedAt)
        assertEquals("b1", tomb.visualizerId, "the pull matches it by its Visualizer id")
        assertNull(tomb.imageRef)
        // Only what matching needs (REVIEW-512): nothing the user wrote.
        assertEquals("Bag a", tomb.name)
        assertTrue(tomb.notes.isNullOrEmpty())
        assertTrue(tomb.tags.isNullOrEmpty())
        assertNull(tomb.linkedProfileId)
        assertEquals(0f, tomb.remaining ?: 0f)
        assertTrue(s.libraryFile().beans.any { it.id == "a" && it.deletedAt != null }, "saved and synced")
    }

    @Test
    fun `a bag never on Visualizer is removed outright`() {
        val vm = vmWith(listOf(bag("a"), bag("b")))
        vm.deleteBean("a")
        VmHarness.idle()
        assertEquals(listOf("b"), VmHarness.ownState(vm).beans.withTombstones().map { it.id })
    }

    @Test
    fun `a saved tombstone loads hidden`() {
        val vm = vmWith(listOf(bag("a"), bag("gone").copy(visualizerId = "b9", deletedAt = 5)))
        val s = VmHarness.ownState(vm)
        assertEquals(listOf("a"), s.beans.map { it.id })
        assertEquals(2, s.beans.withTombstones().size)
    }

    // ── F9 ───────────────────────────────────────────────────────────────────

    @Test
    fun `deleting a custom profile clears its bag links and stamps the bags`() {
        val vm = vmWith(listOf(bag("a", linked = "custom-a"), bag("b")))
        activeCustom(vm, "custom-a")
        vm.deleteProfile("custom-a")
        VmHarness.idle()
        val a = vm.bean("a")
        assertNull(a.linkedProfileId)
        assertNotEquals(1L, a.updatedAt, "a sync sees the change")
        assertEquals(1L, vm.bean("b").updatedAt, "an unlinked bag is untouched")
    }

    /** The core's own test `ShotSample`; byte 17 is the frame number. */
    private fun sampleIn(frame: Int): ByteArray = intArrayOf(
        0x12, 0x34, 0x90, 0x00, 0x28, 0x00, 0x5C, 0x80, 0x00, 0x58, 0x00, 0x5A, 0x00, 0x5D, 0x00,
        0x60, 0x40, frame, 0x96,
    ).map { it.toByte() }.toByteArray()

    private fun apply(vm: MainViewModel, raw: String) {
        VmHarness.deliverCoreOutput(vm, raw)
        VmHarness.idle()
    }

    @Test
    fun `a shot records and debits the bag it started with`() {
        val vm = vmWith(listOf(bag("a", remaining = 200f).copy(tags = listOf("light")), bag("b", remaining = 200f).copy(tags = listOf("dark"))), active = "a")
        val profile = activeBuiltin(vm)
        val bridge = VmHarness.bridge(vm)
        apply(vm, bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(4, 5), 1_000uL))
        for (i in 1..24) {
            apply(vm, bridge.onNotification(NotificationSource.DE1_SHOT_SAMPLE, sampleIn(listOf(0, 2, 2, 3)[minOf(i - 1, 3)]), (1_000 + 250 * i).toULong()))
            // Mid-shot, the user picks the next bag.
            if (i == 12) {
                VmHarness.drainCoreLane(vm)
                vm.setActiveBean("b")
                VmHarness.idle()
            }
        }
        for (i in 25..48) {
            apply(vm, bridge.onNotification(NotificationSource.DE1_SHOT_SAMPLE, sampleIn(3), (1_000 + 250 * i).toULong()))
        }
        apply(vm, bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(2, 0), (1_000 + 250 * 49).toULong()))
        VmHarness.idleUntil("the shot to be recorded", 5_000) { VmHarness.rows(vm).isNotEmpty() }
        val shot = VmHarness.whole(vm, VmHarness.rows(vm).first().id)
        assertEquals("a", shot.bean?.beanId, "the bag at the start")
        assertEquals("Bag a", shot.bean?.name)
        assertEquals(listOf("light"), shot.tags, "the tags of the start's bag")
        assertNull(shot.bean?.grinderSetting, "never the bag's legacy grind (S2)")
        assertEquals(shot.bagDebitG, 200f - vm.bean("a").remaining!!, "the recorded row's bag is debited")
        assertTrue(vm.bean("a").remaining!! < 200f, "the debit is the start's bag's")
        assertEquals(200f, vm.bean("b").remaining)
        // F4: the profile's library id next to its name, in the store too.
        assertEquals(profile.id, shot.profileId)
        // The row reaches history.db on the store's own save, after the row shows.
        VmHarness.idleUntil("the shot's row in history.db") { VmHarness.truthField(vm, shot.id, "profileId") != null }
        assertEquals(profile.id, VmHarness.truthField(vm, shot.id, "profileId"))
    }

    /** #488 (review F4, F9): a shot is stamped with the grinder active at its
     *  start, id and name, not the settings grinder; a switch mid-shot is the next shot's. */
    @Test
    fun `a shot records the grinder it started with`() {
        val niche = coffee.crema.core.Grinder(id = "grinder:niche", name = "Niche Zero", scale = coffee.crema.core.GrindScale(0f, 50f, 1f, ""), updatedAt = 1L)
        val c40 = coffee.crema.core.Grinder(id = "grinder:c40", name = "Comandante C40", scale = coffee.crema.core.GrindScale(0f, 40f, 1f, "clicks"), updatedAt = 1L)
        val library = BeanLibrary(listOf(bag("a")), emptyList(), "a", grinders = listOf(niche, c40), activeGrinderId = niche.id)
        File(VmHarness.app().filesDir, "beanLibrary.json").writeText(coreJson.encodeToString(BeanLibrary.serializer(), library))
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(vm)
        VmHarness.awaitStartupLoaded(vm)
        activeBuiltin(vm)
        // Auto-save on (#488): the kept shot's grind goes to its bag, for its grinder.
        vm.setGrindAutoSave(true)
        vm.setQcGrind(16f)
        VmHarness.idle()
        val bridge = VmHarness.bridge(vm)
        apply(vm, bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(4, 5), 1_000uL))
        for (i in 1..48) {
            apply(vm, bridge.onNotification(NotificationSource.DE1_SHOT_SAMPLE, sampleIn(listOf(0, 2, 2, 3)[minOf(i - 1, 3)]), (1_000 + 250 * i).toULong()))
            if (i == 12) {
                VmHarness.drainCoreLane(vm)
                vm.setActiveGrinder(c40.id)
                VmHarness.idle()
            }
        }
        apply(vm, bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(2, 0), (1_000 + 250 * 49).toULong()))
        VmHarness.idleUntil("the shot to be recorded", 5_000) { VmHarness.rows(vm).isNotEmpty() }
        val shot = VmHarness.whole(vm, VmHarness.rows(vm).first().id)
        assertEquals(niche.id, shot.grinderId, "the grinder at the start")
        assertEquals("Niche Zero", shot.grinderModel, "its name, not the settings grinder")
        VmHarness.idleUntil("the shot's row in history.db") { VmHarness.truthField(vm, shot.id, "grinderId") != null }
        assertEquals(niche.id, VmHarness.truthField(vm, shot.id, "grinderId"))
        // Auto-save: this device recorded the shot, so its grind is the bag's for that grinder.
        assertEquals("16", vm.bean("a").savedGrind(niche.id)?.grind)
        assertNull(vm.bean("a").savedGrind(c40.id), "not the grinder picked mid-shot")
    }

    /** F4: Android's own shot shape keeps the core's `profileId` through the
     *  store's row JSON, a row, and a backup; an older row reads as none. */
    @Test
    fun `the profile id round-trips and an old row reads as none`() {
        val shot = coffee.crema.history.StoredShot(
            id = "shot:1", completedAtMs = 1_700_000_000_000, durationMs = 28_000,
            profileName = "Blooming", profileId = "profile:blooming",
        )
        val row = coreJson.encodeToString(coffee.crema.history.StoredShot.serializer(), shot)
        assertEquals(shot, coreJson.decodeFromString(coffee.crema.history.StoredShot.serializer(), row))
        val old = coreJson.decodeFromString(coffee.crema.history.StoredShot.serializer(), row.replace(",\"profileId\":\"profile:blooming\"", ""))
        assertNull(old.profileId)
        assertEquals("Blooming", old.profileName)

        val summary = coffee.crema.history.unknownSeriesSummary(shot)
        assertEquals(shot, shot.toRow(summary).withHeavy(shot.samples, shot.profile, shot.brewSeries), "a row and back")

        val backup = coffee.crema.visualizer.wireShotJson(shot, forBackup = true)
        assertEquals("profile:blooming", coffee.crema.visualizer.storedShotFromBackupJson(backup, coreJson)?.profileId)
        val bare = coffee.crema.visualizer.wireShotJson(shot.copy(profileId = null), forBackup = true)
        assertFalse("profileId" in bare, "omitted when absent, as the core writes it")
    }
}
