package coffee.crema.ui

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.newBean
import coffee.crema.core.ShotBean
import coffee.crema.history.HistoryFixtures
import coffee.crema.history.FreeUpBackup
import coffee.crema.history.HistoryHealth
import coffee.crema.history.HistoryStore
import coffee.crema.history.MigrationFault
import coffee.crema.history.SeriesFiles
import coffee.crema.history.StorageLevel
import coffee.crema.history.StorageSpace
import coffee.crema.history.StoredShot
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.Stores
import coffee.crema.ui.components.historyBannerText
import coffee.crema.ui.components.storageBannerText
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #310 P6a on the real ViewModel and `shots.db`: the cap is gone. Row 301
 * survives a save and a reload; a brew backdated at the design size lands in
 * its place; a full disk loses nothing and says so; Free up space deletes only
 * what the user confirmed, never a pinned shot, and leaves bag weights alone;
 * nothing deletes on its own at 20,000 rows; and a 5,000-shot backup written by
 * web restores here with every row and sample count.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HistoryNoCapVmTest {
    private var store = ViewModelStore()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val dbFile get() = app.getDatabasePath(HistoryStore.NEXT_DB_NAME)
    private val day = 86_400_000L
    private val now = 1_790_000_000_000L

    @Before fun reset() {
        SafeFile.resetForTest()
        Stores.resetForTest()
        SaveFloor.resetForTest()
        runBlocking { HistoryStore(app, coreJson).erase() }
        File(app.filesDir, "beanLibrary.json").delete()
    }

    @After fun tearDown() {
        store.clear()
        VmHarness.idle()
        HistoryStore.faults.clear()
        HistoryStore.freeSpaceForTest = null
        SeriesFiles.beforeWrite = {}
    }

    private fun vm(): MainViewModel = VmHarness.build(store).also { VmHarness.idle(); VmHarness.awaitStartupLoaded(it) }

    private fun relaunch(): MainViewModel {
        store.clear()
        VmHarness.idle()
        store = ViewModelStore()
        SafeFile.resetForTest()
        return vm()
    }

    private fun plant(rows: List<StoredShot>) {
        SafeFile.openAllForTest()
        runBlocking { assertTrue(HistoryStore(app, coreJson).save(rows)) }
    }

    private fun dbIds(): Set<String> {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            return db.rawQuery("SELECT id FROM shots", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        } finally {
            db.close()
        }
    }

    private fun light(n: Int, start: Long = now, step: Long = 60_000L, prefix: String = "r") =
        List(n) { i -> StoredShot(id = "$prefix$i", completedAtMs = start - i * step, durationMs = 28_000, brewMethod = "pourover", doseG = 15f) }

    private fun MainViewModel.logBrewAt(at: Long, notes: String? = null) = VmHarness.library(this).addManualBrew(
        LibraryController.ManualBrewInput(
            method = "pourover", completedAtMs = at, beanId = null, doseG = 15f, waterG = 250f,
            yieldG = null, grindSetting = null, brewTempC = null, durationMs = 180_000, rating = null, notes = notes, nextPlan = null,
        ),
    )

    private fun <T> onMain(vm: MainViewModel, what: String, block: suspend () -> T): T {
        val job = vm.viewModelScope.async { block() }
        VmHarness.idleUntil(what, 240_000) { job.isCompleted }
        return runBlocking { job.await() }
    }

    // ── The cap is gone ──────────────────────────────────────────────────────

    @Test
    fun row301SurvivesASaveAndAReload() {
        plant(light(300))
        val vm = vm()
        assertNull(VmHarness.library(vm).historyCap)
        // Row 301, the oldest: what the cap would have cut.
        val made = vm.logBrewAt(now - 400 * 60_000L, notes = "row 301")
        VmHarness.idleUntil("the save") { made.id in dbIds() }
        assertEquals(301, vm.ui.value.history.size)
        assertEquals(made.id, vm.ui.value.history.last().id)
        assertTrue(vm.ui.value.userMessages.none { it.startsWith("History keeps") }, "${vm.ui.value.userMessages}")
        // A star tap on another row: the save is a diff, and row 301 stays.
        vm.updateShot("r0", 5, "tapped")
        VmHarness.idleUntil("the tap") { dbIds().size == 301 }
        val next = relaunch()
        assertEquals(301, next.ui.value.history.size, "every row in view after a reload")
        assertEquals("row 301", next.ui.value.history.last().notes)
    }

    @Test
    fun aBrewBackdatedAtTheDesignSizeLandsInItsPlace() {
        val rows = light(5_000, step = 3_600_000L)
        plant(rows)
        val vm = vm()
        assertEquals(5_000, vm.ui.value.history.size)
        // Older than rank 300 and than every row: #309 refused this; now it lands.
        val oldest = rows.last().completedAtMs
        vm.openLogBrew(coffee.crema.ui.brewlog.BrewLogOwner.HISTORY)
        vm.updateLogBrew { it.copy(method = "pourover", dose = 15.0, completedAtMs = oldest - day) }
        assertNull(vm.logBrew.value?.dateError(vm.ui.value.history, now, HistoryStore.capFor(vm.ui.value.historyHealth)))
        assertTrue(vm.saveLogBrew(now), "a date older than all 5,000 rows is fine")
        // And one into the middle.
        val mid = vm.logBrewAt(rows[2_500].completedAtMs - 1_800_000L, notes = "mid")
        VmHarness.idleUntil("the saves") { dbIds().size == 5_002 }
        val history = vm.ui.value.history
        assertEquals(5_002, history.size)
        assertEquals(oldest - day, history.last().completedAtMs, "the backdated brew is last")
        assertEquals(2_501, history.indexOfFirst { it.id == mid.id }, "between rank 2,500 and 2,501")
        assertEquals(history.sortedWith(HistoryStore.ORDER).map { it.id }, history.map { it.id })
        val next = relaunch()
        assertEquals(history.map { it.id }, next.ui.value.history.map { it.id }, "the same order after a reload")
    }

    // ── A full disk ──────────────────────────────────────────────────────────

    @Test
    fun aFullDiskLosesNoRowAndShowsTheBanner() {
        plant(light(400))
        val vm = vm()
        HistoryStore.faults += MigrationFault.DISK_FULL
        val made = vm.logBrewAt(now + 60_000L, notes = "on a full disk")
        VmHarness.idleUntil("the journal") { made.id in vm.ui.value.historyUnsaved }
        assertEquals(401, vm.ui.value.history.size, "the row stays in view")
        val text = assertNotNull(historyBannerText(vm.ui.value.historyHealth, vm.ui.value.historyLost, vm.ui.value.historyError, vm.ui.value.historyUnsaved.size))
        assertTrue("couldn't be fully saved" in text, text)
        assertFalse(made.id in dbIds())
        assertEquals(400, dbIds().size, "nothing the store held went")
        HistoryStore.faults.clear()
        // The journal merges at the next launch: the row is there, and nothing else moved.
        val next = relaunch()
        assertEquals(401, next.ui.value.history.size)
        assertTrue(made.id in dbIds())
        assertTrue(next.ui.value.historyUnsaved.isEmpty())
    }

    @Test
    fun aCurveThatCantBeWrittenStaysInMemoryShowsTheBannerAndLandsLater() {
        plant(light(320))
        val vm = vm()
        val library = VmHarness.library(vm)
        SeriesFiles.beforeWrite = { throw java.io.IOException("No space left on device") }
        val pulled = StoredShot(id = "curve", completedAtMs = now + 60_000L, durationMs = 30_000, samples = HistoryFixtures.samples(40, 7))
        assertTrue(library.insertPulledShots(listOf(pulled)).isEmpty())
        VmHarness.idleUntil("the failed curve") { "curve" in vm.ui.value.historyUnsaved }
        assertNotNull(historyBannerText(vm.ui.value.historyHealth, null, null, vm.ui.value.historyUnsaved.size))
        // The row is stored, its curve held in memory, and the detail still reads it whole.
        assertTrue("curve" in dbIds())
        assertEquals(pulled.samples, VmHarness.whole(vm, "curve").samples)
        // Space again: the next save writes the curve, and the banner goes.
        SeriesFiles.beforeWrite = {}
        vm.logBrewAt(now + 120_000L)
        VmHarness.idleUntil("the retry") { vm.ui.value.historyUnsaved.isEmpty() }
        val next = relaunch()
        assertEquals(322, next.ui.value.history.size)
        assertEquals(pulled.samples, VmHarness.whole(next, "curve").samples, "the curve reached the disk")
    }

    @Test
    fun lowSpaceShowsOneNoticeThenALastingBannerAndDeletesNothing() {
        plant(light(50))
        var free = 10_000 * StorageSpace.MB
        HistoryStore.freeSpaceForTest = { free }
        val vm = vm()
        val library = VmHarness.library(vm)
        assertEquals(StorageLevel.OK, vm.ui.value.storageLevel)
        assertNull(storageBannerText(vm.ui.value.storageLevel, vm.ui.value.storageNoticeDismissed))
        // Under 500 MB: one notice, dismissible.
        free = 400 * StorageSpace.MB
        vm.logBrewAt(now + 60_000L)
        VmHarness.idleUntil("the notice") { vm.ui.value.storageLevel == StorageLevel.GETTING_FULL }
        assertNotNull(storageBannerText(vm.ui.value.storageLevel, vm.ui.value.storageNoticeDismissed))
        vm.dismissStorageNotice()
        VmHarness.idle()
        assertNull(storageBannerText(vm.ui.value.storageLevel, vm.ui.value.storageNoticeDismissed), "once dismissed, it stays down")
        library.checkStorage()
        assertNull(storageBannerText(vm.ui.value.storageLevel, vm.ui.value.storageNoticeDismissed))
        // Under 100 MB: a lasting banner, dismissed or not.
        free = 50 * StorageSpace.MB
        vm.logBrewAt(now + 120_000L)
        VmHarness.idleUntil("the banner") { vm.ui.value.storageLevel == StorageLevel.NEARLY_FULL }
        assertNotNull(storageBannerText(vm.ui.value.storageLevel, vm.ui.value.storageNoticeDismissed))
        // Space again: no banner, and the next time it runs low the notice comes back.
        free = 10_000 * StorageSpace.MB
        library.checkStorage()
        assertEquals(StorageLevel.OK, vm.ui.value.storageLevel)
        assertFalse(vm.ui.value.storageNoticeDismissed)
        free = 400 * StorageSpace.MB
        library.checkStorage()
        assertNotNull(storageBannerText(vm.ui.value.storageLevel, vm.ui.value.storageNoticeDismissed))
        VmHarness.idleUntil("the saves") { dbIds().size == 52 }
        assertEquals(52, vm.ui.value.history.size, "low space deletes nothing")
    }

    // ── Free up space ────────────────────────────────────────────────────────

    @Test
    fun freeUpSpaceSparesPinnedShotsAndKeepsBagWeights() {
        val bean = newBean("b", null, null, null, 0L).copy(id = "b", remaining = 120f, bagSize = 250f)
        File(app.filesDir, "beanLibrary.json").writeText(coreJson.encodeToString(BeanLibrary.serializer(), BeanLibrary(listOf(bean), emptyList(), "b")))
        val cutoff = now - 365 * day
        // 10 old shots, 2 of them pinned, each having taken 13 g from the bag; 5 recent ones.
        val old = List(10) { i ->
            StoredShot(
                id = "old$i", completedAtMs = cutoff - (i + 1) * day, durationMs = 28_000, doseG = 13f, bagDebitG = 13f, brewMethod = "espresso",
                bean = ShotBean(beanId = "b", name = "b"), pinned = i == 3 || i == 7, samples = HistoryFixtures.samples(30, i),
            )
        }
        val recent = light(5, start = now, prefix = "new")
        plant(old + recent)
        val vm = vm()
        val remaining = vm.ui.value.beans.single().remaining
        assertEquals(120f, remaining)

        val plan = assertNotNull(onMain(vm, "the plan") { vm.planFreeUp(cutoff) })
        assertEquals((0 until 10).filter { it != 3 && it != 7 }.map { "old$it" }.toSet(), plan.ids.toSet())
        assertEquals(2, plan.pinnedKept)
        assertTrue(plan.bytes > 0, "it says about how much space")
        assertEquals(15, dbIds().size, "a preview deletes nothing")

        // A shot pinned after the preview is spared too.
        vm.setShotPinned("old0", true)
        VmHarness.idle()
        vm.freeUpSpace(plan, FreeUpBackup.NONE)
        VmHarness.idleUntil("the delete") { dbIds().size == 8 }
        val kept = setOf("old0", "old3", "old7") + recent.map { it.id }
        assertEquals(kept, dbIds())
        assertEquals(kept, vm.ui.value.history.map { it.id }.toSet())
        assertEquals(remaining, vm.ui.value.beans.single().remaining, "no history removal credits the bag")
        assertTrue(vm.ui.value.userMessages.any { it.startsWith("Deleted 7 shots") }, "${vm.ui.value.userMessages}")
        val gone = SeriesFiles(File(app.filesDir, "shot-series"), coreJson)
        assertFalse(gone.gzFile("old1").exists(), "its curve file went with it")
        assertTrue(gone.gzFile("old3").exists(), "a pinned shot keeps its curve")

        val next = relaunch()
        assertEquals(kept, next.ui.value.history.map { it.id }.toSet())
        assertEquals(remaining, next.ui.value.beans.single().remaining)
    }

    @Test
    fun freeUpSpaceWaitsWhileAnUnreadRowCouldBePinned() {
        plant(light(10, start = now - 800 * day))
        val vm = vm()
        // A damaged move: an unread row may be a pinned one, so nothing may run.
        VmHarness.library(vm).let { lib ->
            val f = LibraryController::class.java.getDeclaredField("historySettled").apply { isAccessible = true }
            f.setBoolean(lib, false)
        }
        assertNull(onMain(vm, "the plan") { vm.planFreeUp(now) })
        val plan = coffee.crema.history.FreeUpPlan(now, light(10, start = now - 800 * day).map { it.id }, 0, 0)
        assertEquals(0, onMain(vm, "the refusal") { VmHarness.library(vm).freeUpSpace(plan) }.deleted)
        assertEquals(10, dbIds().size)
    }

    // ── Back up, then delete (REVIEW-440 B1) ─────────────────────────────────

    private fun oldShots(n: Int, cutoff: Long) = List(n) { i ->
        StoredShot(id = "old$i", completedAtMs = cutoff - (i + 1) * day, durationMs = 28_000, doseG = 18f, brewMethod = "espresso", samples = HistoryFixtures.samples(30, i))
    }

    /** The shots in a built backup file, with how many samples each carries. */
    private fun samplesIn(file: File): Map<String, Int> {
        val out = HashMap<String, Int>()
        java.util.zip.ZipInputStream(file.inputStream()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.name != "backup.jsonl") continue
                zin.readBytes().decodeToString().lines().filter { it.isNotBlank() }.forEach { line ->
                    val o = coreJson.parseToJsonElement(line).jsonObject
                    if (o["kind"]?.jsonPrimitive?.content == "shot") {
                        out[o["id"]!!.jsonPrimitive.content] = ((o["record"] as? JsonObject)?.get("samples") as? JsonArray)?.size ?: 0
                    }
                }
            }
        }
        return out
    }

    private fun awaitMessage(vm: MainViewModel, prefix: String): String {
        VmHarness.idleUntil(prefix, 120_000) { vm.ui.value.userMessages.any { it.startsWith(prefix) } }
        return vm.ui.value.userMessages.last { it.startsWith(prefix) }
    }

    /**
     * REVIEW-440's probe, on the path the app runs: a backup (Drive's, the
     * banner's) has taken its snapshot and is walking when the user confirms.
     * Free up space refuses, deletes nothing, and the backup holds every curve.
     */
    @Test
    fun aBackupRunningDuringFreeUpSpaceKeepsTheShotsAndTheirCurves() {
        val cutoff = now - 365 * day
        val old = oldShots(10, cutoff)
        val recent = List(3) { i -> StoredShot(id = "new$i", completedAtMs = now - i * 60_000L, durationMs = 28_000, brewMethod = "espresso", samples = HistoryFixtures.samples(30, 100 + i)) }
        plant(old + recent)
        val vm = vm()
        val library = VmHarness.library(vm)
        val plan = assertNotNull(onMain(vm, "the plan") { vm.planFreeUp(cutoff) })
        assertEquals(10, plan.ids.size)
        // The backup takes its snapshot, then waits at the start of its walk.
        val walking = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        library.backupWalkStarts = { walking.countDown(); release.await(60, java.util.concurrent.TimeUnit.SECONDS) }
        val backup = vm.viewModelScope.async { library.buildBackupZip(BackupKind.DRIVE) }
        VmHarness.idleUntil("the walk") { walking.count == 0L }
        // The user confirms meanwhile.
        vm.freeUpSpace(plan, FreeUpBackup.NONE)
        val told = awaitMessage(vm, "Free up space waits")
        assertTrue("a backup is being made" in told && "no shots were deleted" in told, told)
        assertEquals(13, vm.ui.value.history.size)
        // And the store refuses too, should anything call it straight.
        assertEquals("a backup is being made", onMain(vm, "the store's refusal") { library.freeUpSpace(plan) }.refused)
        release.countDown()
        library.backupWalkStarts = {}
        VmHarness.idleUntil("the backup", 120_000) { backup.isCompleted }
        val zip = assertNotNull(runBlocking { backup.await() })
        try {
            val samples = samplesIn(zip.file)
            for (s in old + recent) assertEquals(30, samples[s.id], "${s.id}: the backup holds its curve")
        } finally {
            zip.release()
        }
        assertEquals(13, dbIds().size, "nothing was deleted")
    }

    /**
     * Back up to a file, then delete, as one flow in the VM: no dialog holds it,
     * so closing one cancels nothing. Of the shots planned, only those the backup
     * holds whole go: one it left out and one it wrote without its curve stay.
     */
    @Test
    fun backUpToAFileThenDeleteKeepsWhatTheBackupDidntHoldWhole() {
        val cutoff = now - 365 * day
        plant(oldShots(6, cutoff) + light(3, start = now, prefix = "new"))
        // old2's curve is gone from disk: the backup writes it without one.
        SeriesFiles(File(app.filesDir, "shot-series"), coreJson).gzFile("old2").delete()
        val vm = vm()
        val library = VmHarness.library(vm)
        val plan = assertNotNull(onMain(vm, "the plan") { vm.planFreeUp(cutoff) })
        assertEquals(6, plan.ids.size)
        // The core can't write old4's line: the backup leaves it out.
        val write = library.backupShotJson
        library.backupShotJson = { shot -> if (shot.id == "old4") "{ not a shot" else write(shot) }
        vm.freeUpSpace(plan, FreeUpBackup.FILE)
        VmHarness.idleUntil("the save picker", 120_000) { vm.ui.value.freeUpSave != null }
        assertEquals(9, dbIds().size, "nothing goes before the backup is saved")
        val target = File(app.cacheDir, "free-up-backup.crema.zip").apply { delete(); createNewFile() }
        vm.freeUpSaved(android.net.Uri.fromFile(target))
        val told = awaitMessage(vm, "Deleted")
        assertTrue(told.startsWith("Deleted 4 shots"), told)
        assertTrue("2 shots were kept: the backup didn't hold them whole" in told, told)
        assertEquals(setOf("old2", "old4", "new0", "new1", "new2"), dbIds())
        val saved = samplesIn(target)
        for (id in listOf("old0", "old1", "old3", "old5")) assertEquals(30, saved[id], "$id is in the saved backup, whole")
        assertFalse(vm.ui.value.freeUpRunning)
    }

    /** A file backup that's cancelled, or never saved, deletes nothing, and says so (REVIEW-440 B1 (b)). */
    @Test
    fun aFileBackupCancelledOrNotSavedDeletesNothing() {
        val cutoff = now - 365 * day
        plant(oldShots(4, cutoff) + light(2, start = now, prefix = "new"))
        val vm = vm()
        val library = VmHarness.library(vm)
        val plan = assertNotNull(onMain(vm, "the plan") { vm.planFreeUp(cutoff) })

        // The user cancels the build.
        val walking = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        library.backupWalkStarts = { walking.countDown(); release.await(60, java.util.concurrent.TimeUnit.SECONDS) }
        vm.freeUpSpace(plan, FreeUpBackup.FILE)
        VmHarness.idleUntil("the walk") { walking.count == 0L }
        vm.cancelBackup(BackupKind.FILE)
        release.countDown()
        library.backupWalkStarts = {}
        val cancelled = awaitMessage(vm, "The backup didn't finish")
        assertTrue("no shots were deleted" in cancelled, cancelled)
        VmHarness.idleUntil("the flow ends") { !vm.ui.value.freeUpRunning }
        assertEquals(6, dbIds().size)

        // The user dismisses the save picker.
        vm.freeUpSpace(plan, FreeUpBackup.FILE)
        VmHarness.idleUntil("the save picker", 120_000) { vm.ui.value.freeUpSave != null }
        vm.freeUpSaved(null)
        val unsaved = awaitMessage(vm, "The backup wasn't saved")
        assertTrue("no shots were deleted" in unsaved, unsaved)
        VmHarness.idleUntil("the flow ends") { !vm.ui.value.freeUpRunning }
        assertEquals(6, dbIds().size)
    }

    /** REVIEW-440 N1, as on web: a shot waiting to upload, or with an edit not yet sent, stays. */
    @Test
    fun freeUpSpaceKeepsShotsWithSomethingStillToSendToVisualizer() {
        val cutoff = now - 365 * day
        plant(oldShots(4, cutoff))
        runBlocking {
            assertTrue(
                coffee.crema.visualizer.VisualizerStore(app, Json { ignoreUnknownKeys = true }).save(
                    coffee.crema.visualizer.VisualizerState(
                        retryQueue = listOf(coffee.crema.visualizer.QueuedUpload("old1", 0, Long.MAX_VALUE / 2)),
                        pendingEdits = mapOf("old2" to coffee.crema.visualizer.PendingEdit(annotations = true, nextAttemptAt = Long.MAX_VALUE / 2)),
                    ),
                ),
            )
        }
        val vm = vm()
        val plan = assertNotNull(onMain(vm, "the plan") { vm.planFreeUp(cutoff) })
        assertEquals(setOf("old0", "old3"), plan.ids.toSet())
        assertEquals(2, plan.syncKept)
        // A plan that still names them, as one made before the queue would: the delete reads it again.
        vm.freeUpSpace(plan.copy(ids = oldShots(4, cutoff).map { it.id }), FreeUpBackup.NONE)
        val told = awaitMessage(vm, "Deleted")
        assertTrue(told.startsWith("Deleted 2 shots") && "2 shots waiting to reach Visualizer were kept" in told, told)
        assertEquals(setOf("old1", "old2"), dbIds())
    }

    // ── No automatic deletion anywhere ───────────────────────────────────────

    @Test
    fun noAutomaticDeletionAt20000RowsSaving100More() {
        plant(light(20_000, start = now, step = 600_000L))
        val vm = vm()
        assertEquals(20_000, vm.ui.value.history.size)
        assertEquals(HistoryHealth.OK, vm.ui.value.historyHealth)
        // 100 more: half newer than everything, half older than everything.
        repeat(100) { i ->
            val at = if (i % 2 == 0) now + (i + 1) * 60_000L else now - 20_000L * 600_000L - (i + 1) * 60_000L
            vm.logBrewAt(at)
        }
        VmHarness.idleUntil("the saves", 240_000) { dbIds().size == 20_100 }
        assertEquals(20_100, vm.ui.value.history.size)
        assertTrue(vm.ui.value.userMessages.none { "dropped" in it || "wasn’t kept" in it }, "${vm.ui.value.userMessages}")
        val next = relaunch()
        assertEquals(20_100, next.ui.value.history.size)
        assertEquals(20_100, dbIds().size)
        assertTrue(dbIds().containsAll(light(20_000, start = now, step = 600_000L).map { it.id }), "every seeded row is still there")
    }

    // ── A 5,000-shot web backup ──────────────────────────────────────────────

    /** The web's cross-shell fixture with [n] shots cloned from its one, shot i holding `1 + i % 7` samples. */
    private fun webBackupWithShots(n: Int): Pair<File, Map<String, Int>> {
        val fixture = File("../../core/de1-domain/tests/fixtures/backup/web-backup.crema.jsonl")
        val lines = fixture.readText().split('\n').filter { it.isNotBlank() }
        val at = lines.indexOfFirst { it.contains("\"kind\":\"shot\"") }
        val template = Json.parseToJsonElement(lines[at]).jsonObject
        val record = template["record"]!!.jsonObject
        val base = record["samples"]!!.jsonArray
        val completedAt = template["completedAt"]!!.jsonPrimitive.content.toLong()
        val counts = LinkedHashMap<String, Int>()
        val header = lines[0].replace(Regex("\"shotCount\":\\d+"), "\"shotCount\":$n")
        val shots = (0 until n).map { i ->
            val k = 1 + i % 7
            val samples = JsonArray(List(k) { j -> JsonObject(base[j % base.size].jsonObject + ("elapsed" to JsonPrimitive(j * 250))) })
            val id = "web-$i"
            counts[id] = k
            Json.encodeToString(
                JsonObject.serializer(),
                JsonObject(
                    template + mapOf(
                        "id" to JsonPrimitive(id),
                        "completedAt" to JsonPrimitive(completedAt - i * 60_000L),
                        "record" to JsonObject(record + ("samples" to samples)),
                    ),
                ),
            )
        }
        val text = (listOf(header) + lines.subList(1, at) + shots + lines.subList(at + 1, lines.size)).joinToString("\n")
        val file = File(app.cacheDir, "web-5000.crema.zip").apply {
            ZipOutputStream(outputStream()).use { z ->
                z.putNextEntry(ZipEntry("backup.jsonl")); z.write(text.toByteArray()); z.closeEntry()
            }
        }
        return file to counts
    }

    @Test
    fun aFiveThousandShotWebBackupRestoresEveryRowAndSampleCount() {
        val (file, counts) = webBackupWithShots(5_000)
        val vm = vm()
        val before = vm.ui.value.userMessages.size
        vm.startRestoreFile(file, "web", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the report", 240_000) { vm.ui.value.restoreReport != null }
        vm.confirmRestore()
        VmHarness.idleUntil("the restore", 240_000) { vm.ui.value.userMessages.size > before && vm.ui.value.restoreReport == null }
        assertTrue(vm.ui.value.userMessages.any { it.startsWith("Restored 5000 shots") }, "${vm.ui.value.userMessages}")
        fun check(v: MainViewModel, what: String) {
            val history = v.ui.value.history
            assertEquals(5_000, history.size, "$what: every row in view")
            assertEquals(counts.keys, dbIds(), "$what: every row in the store")
            for (row in history) assertEquals(counts[row.id]!!.toUInt(), row.summary.sampleCount, "$what: ${row.id}'s sample count")
            // A few whole, from their files.
            for (id in listOf("web-0", "web-2500", "web-4999")) assertEquals(counts[id], VmHarness.whole(v, id).samples.size, "$what: $id whole")
        }
        check(vm, "after the restore")
        check(relaunch(), "after a reload")
    }
}
