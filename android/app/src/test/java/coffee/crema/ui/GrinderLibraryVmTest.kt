package coffee.crema.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import coffee.crema.beans.BeanLibrary
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
        assertEquals(UnsavedGrind("14"), vm.ui.value.unsavedGrind(), "A's dial differs from A's saved grind")

        vm.setActiveBean("b")
        VmHarness.idle()
        assertEquals(18f, vm.ui.value.qcGrind, "B's saved grind (the pick order)")
        assertNull(vm.ui.value.unsavedGrind(), "the marker reflects B")
        assertFalse(vm.ui.value.quickSavePlan().enabled, "nothing of A's to save to B")
        vm.quickSave()
        VmHarness.idle()
        assertEquals("18", vm.bean("b").savedGrind(niche.id)?.grind)
        assertEquals("14", vm.bean("a").savedGrind(niche.id)?.grind)
        val undo = assertNotNull(vm.ui.value.bagSelectUndo, "one message with Undo")
        assertEquals("Bag b: grind 18", undo.message)
        vm.undoBagSelection(undo.seq)
        VmHarness.idle()
        assertEquals(15f, vm.ui.value.qcGrind, "Undo brings the dial back")
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
        assertEquals(24f, vm.ui.value.qcGrind)
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
        assertNull(vm.ui.value.unsavedGrind())
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
        assertEquals(22f, vm.ui.value.qcGrind)
        assertEquals(40f, vm.ui.value.activeGrindScale().max)
        assertEquals(c40.name, vm.ui.value.configSnapshotGrinder())
    }

    private fun MainUiState.configSnapshotGrinder(): String? = activeGrinder()?.name

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
}
