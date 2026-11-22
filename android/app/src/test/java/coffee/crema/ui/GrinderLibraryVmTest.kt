package coffee.crema.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.activeGrinder
import coffee.crema.beans.newBean
import coffee.crema.beans.savedGrind
import coffee.crema.core.BagGrind
import coffee.crema.core.GrindScale
import coffee.crema.core.Grinder
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.SettingsStore
import coffee.crema.history.toRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Grinder records and bag grinds on Android (#488, issue 03's data-loss
 * items), through the real view model, its stores and the host build of the
 * core: a grinder (with an edited scale) and a bag grind (with its star)
 * survive save, reload, a settings reset, backup, restore and export/import;
 * a removed grinder stays removed; the migration runs once the library and
 * the settings are in; and a bag pick resets Brew's grind to that bag's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class GrinderLibraryVmTest {
    @Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    private var store = ViewModelStore()

    @After fun tearDown() { store.clear(); VmHarness.idle() }

    private val app get() = VmHarness.app()

    private fun bag(id: String, grinds: List<BagGrind>? = null, setting: String = "", grinder: String = "") =
        newBean("Bag $id", null, null, null, 0L).copy(id = id, grinds = grinds, grinderSetting = setting, grinder = grinder, updatedAt = 1L)

    private val niche = Grinder(
        id = "grinder:niche-zero", name = "Niche Zero", scale = GrindScale(0f, 50f, 1f, ""), presetId = "niche-zero", updatedAt = 1L,
    )

    private fun writeLibrary(lib: BeanLibrary) =
        File(app.filesDir, "beanLibrary.json").writeText(coreJson.encodeToString(BeanLibrary.serializer(), lib))

    /** The library [freshDevice] starts with, if any. */
    private var writeLibraryAfter: BeanLibrary? = null

    private fun vm(): MainViewModel {
        writeLibraryAfter?.let { writeLibrary(it); writeLibraryAfter = null }
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(vm)
        VmHarness.awaitStartupLoaded(vm)
        VmHarness.idle()
        return vm
    }

    /** The app closed and opened again: a new view model over the same files. */
    private fun reopen(): MainViewModel {
        VmHarness.idle()
        store.clear()
        VmHarness.idle()
        store = ViewModelStore()
        return vm()
    }

    /** Another device: no files, a new view model. */
    private fun freshDevice(): MainViewModel {
        VmHarness.idle()
        store.clear()
        VmHarness.idle()
        app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        coffee.crema.persist.SafeFile.resetForTest()
        coffee.crema.persist.Stores.resetForTest()
        coffee.crema.persist.SaveFloor.resetForTest()
        coffee.crema.persist.SafeFile.openAllForTest()
        store = ViewModelStore()
        return vm()
    }

    private fun MainViewModel.own() = VmHarness.ownState(this)
    private fun MainViewModel.bean(id: String) = own().beans.single { it.id == id }
    private fun MainViewModel.grinder(id: String) = own().grinders.single { it.id == id }

    private fun awaitLibrarySaved(what: String, done: (BeanLibrary) -> Boolean) = VmHarness.idleUntil(what) {
        runCatching { coreJson.decodeFromString(BeanLibrary.serializer(), File(app.filesDir, "beanLibrary.json").readText()) }
            .getOrNull()?.let(done) ?: false
    }

    @Test
    fun `a grinder with an edited scale survives save, reload, reset, backup and restore`() {
        writeLibrary(BeanLibrary(listOf(bag("a", grinds = emptyList()))))
        val vm = vm()
        val added = assertNotNull(vm.addGrinder("Niche Zero"))
        val scale = GrindScale(min = 2f, max = 60f, step = 0.5f, unit = "clicks")
        assertTrue(vm.saveGrinderEdit(added, added.copy(scale = scale)))
        vm.setActiveGrinder(added.id)
        VmHarness.idle()
        assertEquals(scale, vm.grinder(added.id).scale)
        awaitLibrarySaved("the edited grinder on disk") { lib -> lib.grinders.any { it.id == added.id && it.scale == scale } }

        val again = reopen()
        assertEquals(scale, again.grinder(added.id).scale, "after a reload")
        assertEquals(added.id, again.own().activeGrinderId, "the pointer too")

        again.resetPreferences()
        VmHarness.idle()
        assertEquals(scale, again.grinder(added.id).scale, "a settings reset doesn't touch library records")

        val backup = assertNotNull(runBlocking { again.buildBackupZipBytes() })
        // A used device: its own bag and a setting changed, then the backup merged in.
        writeLibraryAfter = BeanLibrary(listOf(bag("other", grinds = emptyList())))
        val used = freshDevice()
        assertTrue(used.own().grinders.isEmpty())
        used.setMaxShotDuration(41f)
        VmHarness.idle()
        VmHarness.restore(used, backup, MainViewModel.RestoreMode.MERGE)
        assertEquals(scale, used.grinder(added.id).scale, "a Merge onto a used device brings the grinder")
        assertNull(used.own().activeGrinderId, "the pointer is the device's own, not in backups")
    }

    @Test
    fun `a removed grinder stays removed after a reload`() {
        runBlocking { SettingsStore(app, coreJson).save(AppPrefs(grinderModel = "Niche Zero")) }
        writeLibrary(BeanLibrary(listOf(bag("a", setting = "14"))))
        val vm = vm()
        val g = vm.own().grinders.single()
        assertEquals("Niche Zero", g.name, "migrated from the settings grinder")
        vm.archiveGrinder(g.id)
        VmHarness.idle()
        assertNull(vm.own().activeGrinderId, "archiving the active grinder clears it (F5)")
        awaitLibrarySaved("the archive on disk") { lib -> lib.grinders.any { it.archivedAt != null } }

        val again = reopen()
        assertEquals(listOf(g.id), again.own().grinders.map { it.id }, "no second Niche from the settings name")
        assertNotNull(again.grinder(g.id).archivedAt, "still archived")
        assertTrue(again.ui.value.liveGrinders.isEmpty())
        assertEquals("14", again.bean("a").savedGrind(g.id)?.grind, "its bag grind stays")

        again.restoreGrinder(g.id)
        VmHarness.idle()
        assertNull(again.grinder(g.id).archivedAt)
    }

    /** The migration reads the settings' grinder: it runs once they've loaded, not with the library. */
    @Test
    fun `the migration runs after the library and the settings have loaded`() {
        runBlocking { SettingsStore(app, coreJson).save(AppPrefs(grinderModel = "Niche Zero")) }
        writeLibrary(BeanLibrary(listOf(bag("a", setting = "14"), bag("b", setting = "3", grinder = "Comandante C40"))))
        val vm = vm()
        val niche = vm.own().grinders.single { it.name == "Niche Zero" }
        val c40 = vm.own().grinders.single { it.name == "Comandante C40" }
        assertEquals(niche.id, vm.own().activeGrinderId, "the settings grinder is active")
        assertEquals(listOf(BagGrind(niche.id, "Niche Zero", "14", starred = false)), vm.bean("a").grinds)
        assertEquals(listOf(BagGrind(c40.id, "Comandante C40", "3", starred = true)), vm.bean("b").grinds, "the bag's own grinder is starred")
        assertEquals("14", vm.bean("a").grinderSetting, "the old field stays")
        awaitLibrarySaved("the migration on disk") { lib -> lib.beans.all { it.grinds != null } }
        val again = reopen()
        assertEquals(2, again.own().grinders.size, "idempotent")
    }

    @Test
    fun `a bag grind and its star survive reload, backup and restore, and export and import`() {
        val grinds = listOf(BagGrind(niche.id, niche.name, "14", starred = true))
        writeLibrary(BeanLibrary(listOf(bag("a", grinds = grinds)), grinders = listOf(niche)))
        val vm = vm()
        assertEquals(grinds, vm.bean("a").grinds)
        val again = reopen()
        assertEquals(grinds, again.bean("a").grinds, "after a reload")

        val backup = assertNotNull(runBlocking { again.buildBackupZipBytes() })
        val exported = assertNotNull(again.beansLibraryJson())
        val restored = freshDevice()
        VmHarness.restore(restored, backup, MainViewModel.RestoreMode.WIPE)
        assertEquals(grinds, restored.bean("a").grinds, "after a restore")
        assertEquals(niche, restored.grinder(niche.id), "and its grinder")

        val imported = freshDevice()
        val file = File(app.cacheDir, "library.crema.jsonl").apply { writeText(exported) }
        imported.importBeanconquerorUri(Uri.fromFile(file))
        VmHarness.idleUntil("the import") { imported.own().beans.any { it.id == "a" } }
        VmHarness.idle()
        assertEquals(grinds, imported.bean("a").grinds, "after an export and import")
        assertEquals(niche.id, imported.own().grinders.single().id)
    }

    /** A Replace with a backup row from an app before #488 (no grinds) keeps the device's (review F7). */
    @Test
    fun `a replace with an older backup row keeps the bag's grinds`() {
        val grinds = listOf(BagGrind(niche.id, niche.name, "14", starred = true))
        writeLibrary(BeanLibrary(listOf(bag("a", grinds = grinds)), grinders = listOf(niche)))
        val vm = vm()
        val backup = assertNotNull(runBlocking { vm.buildBackupZipBytes() })
        val older = stripGrinds(backup)
        VmHarness.restore(vm, older, MainViewModel.RestoreMode.WIPE)
        assertEquals(grinds, vm.bean("a").grinds)
    }

    /** [zip] with every bean line's `grinds` removed, as an app before #488 wrote it. */
    private fun stripGrinds(zip: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipInputStream(zip.inputStream()).use { zin ->
            java.util.zip.ZipOutputStream(out).use { zout ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    var bytes = zin.readBytes()
                    if (e.name.endsWith(".jsonl")) {
                        bytes = bytes.toString(Charsets.UTF_8).lines().joinToString("\n") { line ->
                            if ("\"kind\":\"bean\"" !in line) line
                            else {
                                val o = coreJson.parseToJsonElement(line) as kotlinx.serialization.json.JsonObject
                                kotlinx.serialization.json.JsonObject(o - "grinds").toString()
                            }
                        }.toByteArray()
                    }
                    zout.putNextEntry(java.util.zip.ZipEntry(e.name))
                    zout.write(bytes)
                    zout.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    /** REVIEW-496 follow-up 1: a dial on bag A never lands on bag B. */
    @Test
    fun `a grind dialled on bag A becomes bag B's on selecting B, and Save never writes A's dial to B`() {
        val a = bag("a", grinds = listOf(BagGrind(niche.id, niche.name, "14", starred = false)))
        val b = bag("b", grinds = listOf(BagGrind(niche.id, niche.name, "18", starred = false)))
        writeLibrary(BeanLibrary(listOf(a, b), activeBeanId = "a", grinders = listOf(niche), activeGrinderId = niche.id))
        val vm = vm()
        vm.setQcGrind(15f)
        VmHarness.idle()
        assertEquals("Bag 14", vm.ui.value.brewGrindView().marker?.label, "A's dial differs from A's saved grind")

        vm.setActiveBean("b")
        VmHarness.idle()
        assertEquals("18", vm.ui.value.qcGrind, "B's saved grind (the pick order)")
        assertNull(vm.ui.value.brewGrindView().marker, "the marker reflects B")
        assertFalse(vm.ui.value.quickSavePlan().enabled, "nothing of A's to save to B")
        vm.quickSave()
        VmHarness.idle()
        assertEquals("18", vm.bean("b").savedGrind(niche.id)?.grind)
        assertEquals("14", vm.bean("a").savedGrind(niche.id)?.grind)
        val undo = assertNotNull(vm.ui.value.bagSelectUndo, "one message with Undo")
        // The core's words (REVIEW-FINAL-2 SF4): the grinder in use is named, as on web.
        assertEquals("Bag b: Niche Zero at 18", undo.message)
        vm.undoBagSelection(undo.seq)
        VmHarness.idle()
        assertEquals("15", vm.ui.value.qcGrind, "Undo brings the dial back")
    }

    @Test
    fun `selecting a starred bag switches to its grinder with one message and one Undo`() {
        val c40 = Grinder(id = "grinder:c40", name = "Comandante C40", scale = GrindScale(0f, 40f, 1f, "clicks"), updatedAt = 1L)
        val a = bag("a", grinds = emptyList())
        val b = bag("b", grinds = listOf(BagGrind(c40.id, c40.name, "24", starred = true)))
        writeLibrary(BeanLibrary(listOf(a, b), activeBeanId = "a", grinders = listOf(niche, c40), activeGrinderId = niche.id))
        val vm = vm()
        vm.setActiveBean("b")
        VmHarness.idle()
        assertEquals(c40.id, vm.own().activeGrinderId)
        assertEquals("24", vm.ui.value.qcGrind)
        val undo = assertNotNull(vm.ui.value.bagSelectUndo)
        assertEquals("Bag b: Comandante C40 at 24 clicks", undo.message)
        vm.undoBagSelection(undo.seq)
        VmHarness.idle()
        assertEquals(niche.id, vm.own().activeGrinderId, "Undo puts the grinder back")
        assertEquals("b", vm.own().activeBeanId, "and keeps the bag")
    }

    @Test
    fun `save writes the bag's row for the active grinder, not the old field`() {
        writeLibrary(BeanLibrary(listOf(bag("a", grinds = emptyList(), setting = "9")), activeBeanId = "a", grinders = listOf(niche), activeGrinderId = niche.id))
        val vm = vm()
        vm.setQcGrind(16f)
        VmHarness.idle()
        assertTrue(vm.ui.value.quickSavePlan().includesGrind)
        vm.quickSave()
        VmHarness.idle()
        assertEquals(BagGrind(niche.id, niche.name, "16", starred = false), vm.bean("a").savedGrind(niche.id))
        assertEquals("9", vm.bean("a").grinderSetting, "the old field is no longer written")
        assertTrue("Grind 16 saved to Bag a · Niche Zero" in vm.ui.value.userMessages, "${vm.ui.value.userMessages}")
        assertNull(vm.ui.value.brewGrindView().marker)
    }

    @Test
    fun `picking a grinder takes the bag's saved grind for it`() {
        val c40 = Grinder(id = "grinder:c40", name = "Comandante C40", scale = GrindScale(0f, 40f, 1f, "clicks"), updatedAt = 1L)
        val a = bag("a", grinds = listOf(BagGrind(niche.id, niche.name, "14", false), BagGrind(c40.id, c40.name, "22", false)))
        writeLibrary(BeanLibrary(listOf(a), activeBeanId = "a", grinders = listOf(niche, c40), activeGrinderId = niche.id))
        val vm = vm()
        vm.setQcGrind(15f)
        VmHarness.idle()
        vm.setActiveGrinder(c40.id)
        VmHarness.idle()
        assertEquals("22", vm.ui.value.qcGrind)
        assertEquals(40f, vm.ui.value.activeGrindScale().max)
        assertEquals(c40.name, vm.ui.value.configSnapshotGrinder())
    }

    private fun MainUiState.configSnapshotGrinder(): String? = activeGrinder()?.name

    /** DESIGN.md pick rule: no saved grind and no shot with the new grinder → the grind stays. */
    @Test
    fun `a grinder switch that finds nothing keeps the grind`() {
        val c40 = Grinder(id = "grinder:c40", name = "Comandante C40", scale = GrindScale(0f, 40f, 1f, "clicks"), updatedAt = 1L)
        val a = bag("a", grinds = listOf(BagGrind(niche.id, niche.name, "14", false)))
        writeLibrary(BeanLibrary(listOf(a), activeBeanId = "a", grinders = listOf(niche, c40), activeGrinderId = niche.id))
        val vm = vm()
        vm.setQcGrind(15f)
        VmHarness.idle()
        vm.setActiveGrinder(c40.id)
        VmHarness.idle()
        assertEquals(c40.id, vm.own().activeGrinderId)
        assertEquals("15", vm.ui.value.qcGrind, "nothing saved or shot with it: the grind stays as it is")
    }

    /** Review 499: adding a grinder while none is active makes it Brew's; with one active, it stays. */
    @Test
    fun `the first grinder added becomes the active one`() {
        writeLibrary(BeanLibrary(listOf(bag("a", grinds = emptyList()))))
        val vm = vm()
        assertNull(vm.own().activeGrinderId)
        val first = assertNotNull(vm.resolveGrinderPick(GrinderPick.Create("Dad's Zassenhaus")))
        VmHarness.idle()
        assertEquals(first.id, vm.own().activeGrinderId)
        val second = assertNotNull(vm.addGrinder("Niche Zero"))
        VmHarness.idle()
        assertTrue(second.id != first.id)
        assertEquals(first.id, vm.own().activeGrinderId, "one already active: it stays")
    }

    @Test
    fun `the grinder stays bound by typed scale values`() {
        assertEquals("Lowest must be below Highest", coffee.crema.beans.scaleRefusal(10f, 10f, 1f))
        assertEquals("Step must be more than 0", coffee.crema.beans.scaleRefusal(0f, 10f, 0f))
        assertEquals("Enter a number in each field", coffee.crema.beans.scaleRefusal(0f, Float.NaN, 1f))
        assertNull(coffee.crema.beans.scaleRefusal(0f, 999f, 1f))
        writeLibrary(BeanLibrary(listOf(bag("a", grinds = emptyList())), grinders = listOf(niche)))
        val vm = vm()
        assertFalse(vm.saveGrinderEdit(niche, niche.copy(scale = GrindScale(5f, 1f, 1f, ""))))
        assertEquals(niche.scale, vm.grinder(niche.id).scale, "refused, unchanged")
    }

    @Test
    fun `shots are stamped with the active grinder's id and name`() {
        val shot = coffee.crema.history.StoredShot(id = "shot:1", completedAtMs = 1L, durationMs = 1L, grinderModel = "Niche Zero", grinderId = niche.id)
        val wire = coffee.crema.visualizer.wireShotJson(shot, forBackup = true)
        assertEquals(niche.id, coffee.crema.visualizer.storedShotFromBackupJson(wire, coreJson)?.grinderId, "the id round-trips")
        assertFalse("grinderId" in coffee.crema.visualizer.wireShotJson(shot.copy(grinderId = null), forBackup = true), "omitted when absent")
        val summary = coffee.crema.history.unknownSeriesSummary(shot)
        assertEquals(niche.id, shot.toRow(summary).grinderId)
    }

    // ── Round 2 batch 3: editors and selection (REVIEW-CORE H4–H6, M3; FINAL S7, S9, N7) ──

    /** H5 / PLAN follow-up 20: unreadable settings would read as no grinder; the migration waits for a load that reads. */
    @Test
    fun `an unreadable settings file skips the migration`() {
        File(app.filesDir, "prefs.json").writeText("{ not json")
        writeLibrary(BeanLibrary(listOf(bag("a", setting = "14"))))
        val vm = vm()
        assertNull(vm.bean("a").grinds, "the old grind isn't filed under \"no grinder\" for good")
        assertTrue(vm.own().grinders.isEmpty())
        assertTrue(VmHarness.log(vm).any { "Grinder migration skipped" in it && "settings" in it }, "${VmHarness.log(vm)}")
    }

    @Test
    fun `the migration gate reads a store as read only when it loaded or was absent`() {
        val h = coffee.crema.persist.StoreHealth.entries
        val read = h.filter { coffee.crema.beans.migrationGate(it, it).let { g -> g.library && g.settings } }
        assertEquals(listOf(coffee.crema.persist.StoreHealth.OK, coffee.crema.persist.StoreHealth.ABSENT), read)
        assertFalse(coffee.crema.beans.migrationGate(coffee.crema.persist.StoreHealth.OK, coffee.crema.persist.StoreHealth.DAMAGED).settings)
        assertFalse(coffee.crema.beans.migrationGate(coffee.crema.persist.StoreHealth.UNAVAILABLE, coffee.crema.persist.StoreHealth.OK).library)
        // PLAN follow-up 40 / REVIEW-514: a kept-aside settings copy with no settings grinder is quarantined,
        // whether the settings read absent or were written again since; never written (nothing kept aside) isn't.
        val ok = coffee.crema.persist.StoreHealth.OK
        val absent = coffee.crema.persist.StoreHealth.ABSENT
        fun q(settings: coffee.crema.persist.StoreHealth, keptAside: Boolean, model: String) =
            coffee.crema.beans.migrationGate(ok, settings, settingsKeptAside = keptAside, grinderModel = model).settingsQuarantined == true
        assertTrue(q(absent, keptAside = true, model = ""))
        assertTrue(q(ok, keptAside = true, model = " "), "a write after the quarantine keeps the gate")
        assertFalse(q(ok, keptAside = true, model = "Niche Zero"), "a known settings grinder opens it")
        assertFalse(q(absent, keptAside = false, model = ""), "never written")
    }

    /** N7: re-picking the bag already on Brew keeps an unsaved dial, with no message. */
    @Test
    fun `re-picking the active bag changes nothing`() {
        val a = bag("a", grinds = listOf(BagGrind(niche.id, niche.name, "14", starred = true)))
        writeLibrary(BeanLibrary(listOf(a), activeBeanId = "a", grinders = listOf(niche), activeGrinderId = niche.id))
        val vm = vm()
        vm.setQcGrind(15f)
        VmHarness.idle()
        vm.setActiveBean("a")
        VmHarness.idle()
        assertEquals("15", vm.ui.value.qcGrind, "the unsaved dial stays")
        assertNull(vm.ui.value.bagSelectUndo, "no message, no Undo")
    }

    /** REVIEW-FINAL-2 SF4: mid-shot the core leaves the linked profile out and
     *  says so; the grind still changes, and the one message is the core's. */
    @Test
    fun `a bag pick during a shot shows the core's notice and leaves the profile`() {
        val a = bag("a", grinds = listOf(BagGrind(niche.id, niche.name, "14", starred = false)))
        val b = bag("b", grinds = listOf(BagGrind(niche.id, niche.name, "18", starred = false)))
        writeLibrary(BeanLibrary(listOf(a, b), activeBeanId = "a", grinders = listOf(niche), activeGrinderId = niche.id))
        val vm = vm()
        val (first, linked) = vm.ui.value.profiles.filter { it.source != "custom" }.take(2)
        vm.setActiveProfile(first.id)
        vm.updateBean("b", "") { it.copy(linkedProfileId = linked.id) }
        VmHarness.idle()
        VmHarness.deliverCoreOutput(vm, """{"events":[{"type":"ShotStarted"}],"commands":[]}""")
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
        assertTrue(vm.ui.value.shotInProgress)
        vm.setActiveBean("b")
        VmHarness.idle()
        assertEquals(first.id, vm.ui.value.activeProfileId, "not loaded mid-shot")
        assertEquals("18", vm.ui.value.qcGrind, "the grind still changes")
        assertTrue("Shot in progress \u2014 \u201c${linked.name}\u201d not loaded" in vm.ui.value.userMessages, "${vm.ui.value.userMessages}")
        assertEquals("Bag b: Niche Zero at 18", assertNotNull(vm.ui.value.bagSelectUndo).message)
    }

    /** F6: a remote's pick is applied on the hub and answered with the core's
     *  selection, for the remote to show with its own Undo; the hub shows none. */
    @Test
    fun `a relayed bag pick is answered with its selection and shows no Undo on the hub`() {
        val a = bag("a", grinds = listOf(BagGrind(niche.id, niche.name, "14", starred = false)))
        val b = bag("b", grinds = listOf(BagGrind(niche.id, niche.name, "18", starred = false)))
        writeLibrary(BeanLibrary(listOf(a, b), activeBeanId = "a", grinders = listOf(niche), activeGrinderId = niche.id))
        val vm = vm()
        vm.setQcGrind(15f)
        VmHarness.idle()
        val outcome = runBlocking { VmHarness.proxyController(vm).handleRelayedControl("setActiveBean", "b", "remote-1", "Phone") }
        VmHarness.idle()
        val picked = kotlin.test.assertIs<coffee.crema.machine.CommandOutcome.BagPicked>(outcome)
        assertEquals("Bag b: Niche Zero at 18", picked.selection.message)
        assertEquals("15", picked.selection.undo.grind?.value, "the Undo the remote runs")
        assertEquals("b", vm.ui.value.activeBeanId)
        assertEquals("18", vm.ui.value.qcGrind, "applied on the hub")
        assertNull(vm.ui.value.bagSelectUndo, "the remote shows it, not the hub")
    }

    /** REVIEW-FINAL-2 SF2: an import never selects a bag, as on web. */
    @Test
    fun `an import never selects a bag`() {
        writeLibrary(BeanLibrary(listOf(bag("a")), activeBeanId = "a"))
        val exported = assertNotNull(vm().beansLibraryJson())
        val imported = freshDevice()
        assertNull(imported.own().activeBeanId)
        val file = File(app.cacheDir, "library.crema.jsonl").apply { writeText(exported) }
        imported.importBeanconquerorUri(Uri.fromFile(file))
        VmHarness.idleUntil("the import") { imported.own().beans.any { it.id == "a" } }
        VmHarness.idle()
        assertNull(imported.own().activeBeanId, "\"No bag\" stays")
        assertNull(imported.ui.value.bagSelectUndo)
    }

    /** H4 / S9: mid-shot, Undo leaves the profile (loading one would upload it to the machine); the grind goes back. */
    @Test
    fun `a bag pick's Undo during a shot leaves the profile`() {
        val a = bag("a", grinds = listOf(BagGrind(niche.id, niche.name, "14", starred = false)))
        val b = bag("b", grinds = listOf(BagGrind(niche.id, niche.name, "18", starred = false)))
        writeLibrary(BeanLibrary(listOf(a, b), activeBeanId = "a", grinders = listOf(niche), activeGrinderId = niche.id))
        val vm = vm()
        val (first, linked) = vm.ui.value.profiles.filter { it.source != "custom" }.take(2)
        vm.setActiveProfile(first.id)
        vm.updateBean("b", "") { it.copy(linkedProfileId = linked.id) }
        vm.setQcGrind(14f)
        VmHarness.idle()
        vm.setActiveBean("b")
        VmHarness.idle()
        assertEquals(linked.id, vm.ui.value.activeProfileId)
        assertEquals("18", vm.ui.value.qcGrind)
        val undo = assertNotNull(vm.ui.value.bagSelectUndo)
        VmHarness.deliverCoreOutput(vm, """{"events":[{"type":"ShotStarted"}],"commands":[]}""")
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
        assertTrue(vm.ui.value.shotInProgress)
        vm.undoBagSelection(undo.seq)
        VmHarness.idle()
        assertEquals(linked.id, vm.ui.value.activeProfileId, "the profile stays mid-shot")
        assertEquals("14", vm.ui.value.qcGrind, "the grind goes back")
    }

    /** M3: a roaster cascade that deletes the active bag leaves Brew with no bag, not another one. */
    @Test
    fun `deleting a roaster with the active bag selects no other bag`() {
        val sey = coffee.crema.beans.newRoaster("Sey", 1).copy(id = "roaster:sey")
        val onyx = coffee.crema.beans.newRoaster("Onyx", 1).copy(id = "roaster:onyx")
        val beans = listOf(bag("a").copy(roasterId = sey.id), bag("b").copy(roasterId = sey.id), bag("c").copy(roasterId = onyx.id))
        writeLibrary(BeanLibrary(beans, listOf(sey, onyx), activeBeanId = "a"))
        val vm = vm()
        vm.deleteRoaster(sey.id, cascade = true)
        VmHarness.idle()
        assertEquals(listOf("c"), vm.own().beans.map { it.id })
        assertNull(vm.own().activeBeanId, "Brew shows \"No bag\"")
    }

    @Test
    fun `a roaster cascade that spares the active bag keeps it`() {
        val sey = coffee.crema.beans.newRoaster("Sey", 1).copy(id = "roaster:sey")
        val onyx = coffee.crema.beans.newRoaster("Onyx", 1).copy(id = "roaster:onyx")
        val beans = listOf(bag("a").copy(roasterId = sey.id), bag("c").copy(roasterId = onyx.id))
        writeLibrary(BeanLibrary(beans, listOf(sey, onyx), activeBeanId = "c"))
        val vm = vm()
        vm.deleteRoaster(sey.id, cascade = true)
        VmHarness.idle()
        assertEquals("c", vm.own().activeBeanId)
    }

    /** S7: the bag editor's "Add a grinder" stores nothing and changes no pointer until Save; Cancel leaves nothing. */
    @Test
    fun `a grinder added in the bag editor is stored only on Save`() {
        writeLibrary(BeanLibrary(listOf(bag("a", grinds = emptyList()))))
        val vm = vm()
        val typo = assertNotNull(vm.stageGrinderPick(GrinderPick.Create("Nich"), emptyList()))
        VmHarness.idle()
        assertTrue(vm.own().grinders.isEmpty(), "a typo never reaches Devices at the pick")
        assertNull(vm.own().activeGrinderId, "nor Brew's grinder")
        // Cancel: the editor drops its staged list; nothing to undo.

        // Again, and Save with a row for it.
        val picked = assertNotNull(vm.stageGrinderPick(GrinderPick.Create("Niche Zero"), emptyList()))
        assertEquals(listOf(picked.grinder), picked.staged)
        vm.updateBean("a", "") { it.copy(grinds = listOf(BagGrind(picked.grinder.id, picked.grinder.name, "14", starred = true))) }
        vm.saveStagedGrinders("a", picked.staged)
        VmHarness.idle()
        assertEquals(listOf(picked.grinder.id), vm.own().grinders.map { it.id })
        assertEquals(picked.grinder.id, vm.own().activeGrinderId, "with none active, the saved one becomes Brew's (review 499, at Save)")
        assertTrue(vm.own().grinders.none { it.name == typo.grinder.name })
    }

    @Test
    fun `a staged grinder whose row was removed before Save writes nothing`() {
        writeLibrary(BeanLibrary(listOf(bag("a", grinds = emptyList())), grinders = listOf(niche), activeGrinderId = niche.id))
        val vm = vm()
        val picked = assertNotNull(vm.stageGrinderPick(GrinderPick.Create("Comandante C40"), emptyList()))
        vm.updateBean("a", "") { it.copy(grinds = emptyList()) }
        vm.saveStagedGrinders("a", picked.staged)
        VmHarness.idle()
        assertEquals(listOf(niche.id), vm.own().grinders.map { it.id })
        assertEquals(niche.id, vm.own().activeGrinderId)
    }

    @Test
    fun `an archived grinder picked in the bag editor comes back only on Save`() {
        val archived = niche.copy(archivedAt = 5L)
        writeLibrary(BeanLibrary(listOf(bag("a", grinds = emptyList())), grinders = listOf(archived)))
        val vm = vm()
        val picked = assertNotNull(vm.stageGrinderPick(GrinderPick.Archived(niche.id), emptyList()))
        assertEquals(coffee.crema.core.GrinderAddKind.Revived, picked.kind)
        assertNotNull(vm.grinder(niche.id).archivedAt, "still archived at the pick")
        vm.updateBean("a", "") { it.copy(grinds = listOf(BagGrind(niche.id, niche.name, "14", starred = false))) }
        vm.saveStagedGrinders("a", picked.staged)
        VmHarness.idle()
        assertNull(vm.grinder(niche.id).archivedAt, "restored on Save")
        assertTrue("Niche Zero is back, with its saved grinds" in vm.ui.value.userMessages, "${vm.ui.value.userMessages}")
    }

    /** #488 follow-up 18: an Add that finds the model already yours offers
     *  "Add another", which makes a second record with its own id. */
    @Test
    fun `adding an owned grinder offers Add another, which makes a second one`() {
        writeLibrary(BeanLibrary(listOf(bag("a", grinds = emptyList()))))
        val vm = vm()
        val first = assertNotNull(vm.addGrinder("Niche Zero"))
        assertNull(vm.ui.value.addAnotherOffer, "a new grinder offers nothing")
        assertEquals(first.id, vm.addGrinder("Niche Zero")?.id, "the same one again")
        val offer = assertNotNull(vm.ui.value.addAnotherOffer, "already yours: Add another is offered")
        assertEquals("Niche Zero is already one of your grinders", offer.message)
        val second = assertNotNull(vm.addAnotherGrinder(offer.seq))
        VmHarness.idle()
        assertEquals("Niche Zero 2", second.name)
        assertNotEquals(first.id, second.id, "its own id")
        assertEquals(listOf(first.id, second.id), vm.own().grinders.filter { it.archivedAt == null && it.deletedAt == null }.map { it.id })
        assertNull(vm.ui.value.addAnotherOffer)
        assertNull(vm.addAnotherGrinder(offer.seq), "the offer is used up")
        assertTrue("Added Niche Zero 2" in vm.ui.value.userMessages, "${vm.ui.value.userMessages}")
    }

    // ── Batch 7 (REVIEW-FINAL-2 N6, N7) ──────────────────────────────────────

    /** N7: History changes a shot's grinder (web's setGrinderModel); the id follows the name through the core's grinder_named. */
    @Test
    fun `History changes a shot's grinder and its id follows the name`() {
        val df64 = Grinder(id = "grinder:df64", name = "DF64", scale = GrindScale(0f, 90f, 1f, ""), updatedAt = 1L)
        writeLibraryAfter = BeanLibrary(emptyList(), emptyList(), null, listOf(niche, df64), niche.id)
        val shot = coffee.crema.history.StoredShot(id = "shot:1", completedAtMs = 1L, durationMs = 1L, grinderModel = "DF64", grinderId = df64.id)
        kotlinx.coroutines.runBlocking { coffee.crema.history.HistoryStore(app, coreJson).save(listOf(shot)) }
        val vm = vm()
        VmHarness.idleUntil("the row") { VmHarness.rows(vm).isNotEmpty() }
        fun row() = VmHarness.rows(vm).single { it.id == "shot:1" }
        vm.setShotGrinder("shot:1", " Niche ")
        VmHarness.idle()
        assertEquals("Niche", row().grinderModel, "the name as typed, trimmed")
        assertEquals(niche.id, row().grinderId, "a catalogue alias names the owned Niche Zero")
        vm.setShotGrinder("shot:1", "EK43")
        VmHarness.idle()
        assertEquals("EK43", row().grinderModel)
        assertNull(row().grinderId, "no grinder of that name: no id, never the old one")
        vm.setShotGrinder("shot:1", "  ")
        VmHarness.idle()
        assertNull(row().grinderModel, "blank clears it")
        assertNull(row().grinderId)
    }

    /** N6: an id-less shot's grind scale, and Brew's active grinder, come from the core. */
    @Test
    fun `an old shot's grind scale finds its grinder through the catalogue`() {
        val old = coffee.crema.history.StoredShot(id = "old", completedAtMs = 1L, durationMs = 1L, grinderModel = "Niche")
        val row = old.toRow(coffee.crema.history.unknownSeriesSummary(old))
        assertEquals(niche.scale, shotGrindScale(row, listOf(niche)), "\"Niche\" is the owned Niche Zero")
        assertEquals(coffee.crema.beans.DEFAULT_GRIND_SCALE, shotGrindScale(row, emptyList()))
        val stamped = old.copy(grinderId = "grinder:gone", grinderModel = "Niche Zero")
        assertEquals(coffee.crema.beans.DEFAULT_GRIND_SCALE, shotGrindScale(stamped.toRow(coffee.crema.history.unknownSeriesSummary(stamped)), listOf(niche)), "an id is never re-read by name")
        assertEquals(niche, listOf(niche).activeGrinder(niche.id))
        assertNull(listOf(niche.copy(archivedAt = 2L)).activeGrinder(niche.id), "archived isn't active")
        assertNull(listOf(niche).activeGrinder(""), "no pointer")
    }
}
