package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import coffee.crema.core.Verdict
import coffee.crema.history.StoredShot
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.Stores
import coffee.crema.restore.BackupReadException
import coffee.crema.restore.RestoreKind
import coffee.crema.restore.RestoreRecords
import coffee.crema.restore.prepareBackupFile
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The streamed restore (#310 P2a-b) on the real ViewModel and stores: a
 * backup read from a file a piece at a time, checked before the plan, and
 * holding only the shots the run writes. The twin of the web's
 * `stream.vitest.ts`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class StreamedRestoreVmTest {
    private var store = ViewModelStore()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val fixture = File("../../core/de1-domain/tests/fixtures/backup/android-backup.crema.jsonl")
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

    @Before fun reset() {
        SafeFile.resetForTest()
        Stores.resetForTest()
        SaveFloor.resetForTest()
    }

    @After fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    private fun vm(): MainViewModel = VmHarness.build(store).also { VmHarness.idle(); VmHarness.awaitStartupLoaded(it) }

    private fun <T> onMain(vm: MainViewModel, what: String, block: suspend () -> T): T {
        val job = vm.viewModelScope.async { block() }
        VmHarness.idleUntil(what, 120_000) { job.isCompleted }
        return runBlocking { job.await() }
    }

    /** The fixture with [n] shots cloned from its one, newest first a minute apart, as a zip. */
    private fun backupWithShots(n: Int, newest: Long): File {
        val lines = fixture.readText().split('\n').filter { it.isNotBlank() }
        val at = lines.indexOfFirst { it.contains("\"kind\":\"shot\"") }
        val template = lines[at]
        val id = (Json.parseToJsonElement(template).jsonObject["id"] as JsonPrimitive).content
        val completedAt = (Json.parseToJsonElement(template).jsonObject["completedAt"] as JsonPrimitive).content
        val header = lines[0].replace(Regex("\"shotCount\":\\d+"), "\"shotCount\":$n")
        val shots = (0 until n).map { i ->
            template.replace("\"id\":\"$id\"", "\"id\":\"s$i\"").replace("\"completedAt\":$completedAt", "\"completedAt\":${newest - i * 60_000L}")
        }
        val text = (listOf(header) + lines.subList(1, at) + shots + lines.subList(at + 1, lines.size)).joinToString("\n")
        return File(app.cacheDir, "big.crema.zip").apply {
            ZipOutputStream(outputStream()).use { z ->
                // Stored deflate blocks: the file is megabytes, not kilobytes.
                z.setLevel(0)
                z.putNextEntry(ZipEntry("backup.jsonl")); z.write(text.toByteArray()); z.closeEntry()
            }
        }
    }

    @Test
    fun fiveThousandShotsStreamThroughAndTheRunHoldsOnlyWhatHistoryKeeps() {
        // On the legacy backend (shots.json, with the cap), which runs when the move to shots.db fails (#310 P3a).
        coffee.crema.history.HistoryStore.legacyOnlyForTest = true
        try {
            val vm = vm()
            val file = backupWithShots(5000, 1_790_000_000_000)
            assertTrue(file.length() > 4L * 1024 * 1024, "${file.length()}")
            val stage = File(app.cacheDir, "stage-test")
            val prepared = prepareBackupFile(file, "big", stage)
            val plan = onMain(vm, "the plan") { vm.restoreEngine.plan(prepared, RestoreKind.MERGE) }
            val capacity = plan.shots.add.size
            assertEquals(5000 - capacity, plan.shots.notAdded.toInt())
            // What the run reads: the shots it writes, not the backup's 5,000.
            val records = RestoreRecords.read(prepared, json, 0L, RestoreRecords.shotsTheRunWrites(plan))
            assertEquals(capacity, records.parsed.shots.size)
    
            val before = vm.ui.value.userMessages.size
            vm.startRestoreFile(file, "big", MainViewModel.RestoreMode.MERGE)
            VmHarness.idleUntil("the report", 120_000) { vm.ui.value.restoreReport != null }
            vm.confirmRestore()
            VmHarness.idleUntil("the restore", 120_000) { vm.ui.value.userMessages.size > before }
            assertTrue(vm.ui.value.userMessages.last().startsWith("Restored $capacity shots"), vm.ui.value.userMessages.last())
            val history = VmHarness.ownState(vm).history
            assertEquals(capacity, history.size)
            assertEquals("s0", history.first().id)
        } finally {
            coffee.crema.history.HistoryStore.legacyOnlyForTest = false
        }
    }

    /** #310 P3a: on shots.db nothing caps a merge. All 5,000 rows land in the store; the view shows 300. */
    @Test
    fun fiveThousandShotsMergeWholeIntoTheStore() {
        val vm = vm()
        val file = backupWithShots(5000, 1_790_000_000_000)
        val before = vm.ui.value.userMessages.size
        vm.startRestoreFile(file, "big", MainViewModel.RestoreMode.MERGE)
        VmHarness.idleUntil("the restore", 240_000) { vm.ui.value.userMessages.size > before }
        assertTrue(vm.ui.value.userMessages.last().startsWith("Restored 5000 shots"), vm.ui.value.userMessages.last())
        assertEquals(coffee.crema.history.HistoryStore.MAX_SHOTS, VmHarness.ownState(vm).history.size)
        assertEquals("s0", VmHarness.ownState(vm).history.first().id)
        assertEquals(5000, VmHarness.library(vm).allShots().size)
    }

    @Test
    fun aFullHistoryAllNewerThanTheBackupThenReplaceLeavesExactlyTheBackupsRows() {
        // A full history, every row newer than the backup's: Replace must not
        // fit the backup's rows under the cap first and then remove them all.
        SafeFile.openAllForTest()
        runBlocking {
            val rows = (0 until 300).map { shot("here$it", 1_800_000_000_000 - it * 60_000L) }
            assertTrue(coffee.crema.history.HistoryStore(app, json).save(rows))
        }
        val vm = vm()
        assertEquals(300, VmHarness.ownState(vm).history.size)
        val file = backupWithShots(20, 1_700_000_000_000)
        val before = vm.ui.value.userMessages.size
        vm.startRestoreFile(file, "old", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the report") { vm.ui.value.restoreReport != null }
        vm.confirmRestore()
        VmHarness.idleUntil("the restore", 120_000) { vm.ui.value.userMessages.size > before }
        assertEquals((0 until 20).map { "s$it" }.sorted(), VmHarness.ownState(vm).history.map { it.id }.sorted())
    }

    @Test
    fun aPlanThatGrowsBeforeTheRunReadsTheShotsItNowWrites() {
        // On the legacy backend (shots.json, with the cap), which runs when the move to shots.db fails (#310 P3a).
        coffee.crema.history.HistoryStore.legacyOnlyForTest = true
        try {
            // Merge at the cap: room for 5 of the backup's 10. Rows deleted while the
            // report is open make room for all 10; the run's records must have them.
            SafeFile.openAllForTest()
            runBlocking {
                val rows = (0 until 295).map { shot("here$it", 1_600_000_000_000 - it * 60_000L) }
                assertTrue(coffee.crema.history.HistoryStore(app, json).save(rows))
            }
            val vm = vm()
            val file = backupWithShots(10, 1_790_000_000_000)
            vm.startRestoreFile(file, "ten", MainViewModel.RestoreMode.MERGE)
            VmHarness.idleUntil("the report") { vm.ui.value.restoreReport != null }
            for (i in 0 until 5) vm.deleteShot("here$i")
            VmHarness.idle()
            val before = vm.ui.value.userMessages.size
            vm.confirmRestore()
            VmHarness.idleUntil("the report again", 60_000) { vm.ui.value.restoreReport != null || vm.ui.value.userMessages.size > before }
            assertNotNull(vm.ui.value.restoreReport, "the numbers changed: the report shows them")
            val again = vm.ui.value.userMessages.size
            vm.confirmRestore()
            VmHarness.idleUntil("the restore", 60_000) { vm.ui.value.userMessages.size > again }
            val history = VmHarness.ownState(vm).history.map { it.id }
            assertEquals((0 until 10).map { "s$it" }.toSet(), history.filter { it.startsWith("s") }.toSet(), vm.ui.value.userMessages.last())
        } finally {
            coffee.crema.history.HistoryStore.legacyOnlyForTest = false
        }
    }

    @Test
    fun aDamagedZipIsRefusedBeforeThePlanAndNothingIsWritten() {
        val vm = vm()
        val good = backupWithShots(3, 1_790_000_000_000).readBytes()
        // A byte of the stored JSONL, past the local header and the deflate block header.
        val bad = good.copyOf().also { it[30 + "backup.jsonl".length + 200] = (it[30 + "backup.jsonl".length + 200].toInt() xor 1).toByte() }
        val file = File(app.cacheDir, "bad.crema.zip").apply { writeBytes(bad) }
        assertFailsWith<BackupReadException> { prepareBackupFile(file, "bad", File(app.cacheDir, "s")) }
        val before = vm.ui.value.userMessages.size
        vm.startRestoreFile(file, "bad", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the refusal") { vm.ui.value.userMessages.size > before }
        assertTrue("checksum" in vm.ui.value.userMessages.last(), vm.ui.value.userMessages.last())
        assertTrue(VmHarness.ownState(vm).history.isEmpty())
    }

    @Test
    fun aLongLineRefusesReplaceAndIsReportedByMerge() {
        val vm = vm()
        val lines = fixture.readText().split('\n')
        val long = """{"kind":"bean","id":"long","name":"${"x".repeat(5000)}"}"""
        val file = File(app.cacheDir, "long.jsonl").apply { writeText((lines.take(2) + long + lines.drop(2)).joinToString("\n")) }
        val limits = coffee.crema.restore.BackupReadLimits(maxLineBytes = 4096)
        val prepared = prepareBackupFile(file, "long", File(app.cacheDir, "s"), limits)
        val manifest = Json.parseToJsonElement(prepared.manifestJson).jsonObject
        assertEquals("1", manifest["badLines"].toString())
        assertEquals(Verdict.Refused, onMain(vm, "replace") { vm.restoreEngine.plan(prepared, RestoreKind.REPLACE) }.verdict)
        val merge = onMain(vm, "merge") { vm.restoreEngine.plan(prepared, RestoreKind.MERGE) }
        assertEquals(Verdict.Confirm, merge.verdict)
        assertTrue(merge.warnings.isNotEmpty())
    }

    /** Restore [file], confirming the report if one opens; the message it ends with. */
    private fun restoreAll(vm: MainViewModel, file: File, mode: MainViewModel.RestoreMode): String {
        val before = vm.ui.value.userMessages.size
        vm.startRestoreFile(file, file.name, mode)
        VmHarness.idleUntil("the report or the restore", 60_000) { vm.ui.value.restoreReport != null || vm.ui.value.userMessages.size > before }
        if (vm.ui.value.restoreReport != null) {
            vm.confirmRestore()
            VmHarness.idleUntil("the restore", 60_000) { vm.ui.value.userMessages.size > before }
        }
        return vm.ui.value.userMessages.last()
    }

    @Test
    fun bringThemBackTooRunsFromTheStreamedRecords() {
        // REVIEW-395 nit 2: "Bring them back too" through a file read a piece at a time.
        val vm = vm()
        val file = backupWithShots(3, 1_790_000_000_000)
        restoreAll(vm, file, MainViewModel.RestoreMode.MERGE)
        assertEquals(setOf("s0", "s1", "s2"), VmHarness.ownState(vm).history.map { it.id }.toSet())
        vm.deleteShot("s1")
        VmHarness.idle()

        // The records the first plan reads already hold the shot deleted here,
        // so either answer to the report runs from one read.
        val prepared = prepareBackupFile(file, "three", File(app.cacheDir, "stage-bring-back"))
        val plan = onMain(vm, "the plan") { vm.restoreEngine.plan(prepared, RestoreKind.MERGE) }
        assertEquals(listOf("s1"), plan.shots.deletedHere)
        val revived = onMain(vm, "the bring-back plan") { vm.restoreEngine.plan(prepared, RestoreKind.MERGE, reviveDeleted = true) }
        assertEquals(listOf("s1"), revived.shots.revive)
        val read = RestoreRecords.shotsTheRunWrites(plan)
        assertTrue(read.containsAll(RestoreRecords.shotsTheRunWrites(revived)), "$read")
        assertTrue("s1" in RestoreRecords.read(prepared, json, 0L, read).parsed.shots.map { it.id })

        // And on the real report.
        val before = vm.ui.value.userMessages.size
        vm.startRestoreFile(file, "three", MainViewModel.RestoreMode.MERGE)
        VmHarness.idleUntil("the report") { vm.ui.value.restoreReport != null }
        assertTrue(vm.ui.value.restoreReport!!.offerBringBack)
        vm.restoreBringBack()
        VmHarness.idleUntil("the bring-back report") { vm.ui.value.restoreReport?.offerBringBack == false }
        vm.confirmRestore()
        VmHarness.idleUntil("the restore", 60_000) { vm.ui.value.userMessages.size > before }
        assertEquals(setOf("s0", "s1", "s2"), VmHarness.ownState(vm).history.map { it.id }.toSet(), vm.ui.value.userMessages.last())
    }

    @Test
    fun aSecondPickWhileTheReportIsOpenLeavesTheFirstsStagedPhotos() {
        // REVIEW-395 nit 4: each prepare cleared the one shared stage.
        val vm = vm()
        val fixtureZip = File("../../core/de1-domain/tests/fixtures/backup/android-zipoutputstream.crema.zip")
        val first = File(app.cacheDir, "first.crema.zip").apply { writeBytes(fixtureZip.readBytes()) }
        vm.startRestoreFile(first, "first", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the report") { vm.ui.value.restoreReport != null }

        // A second pick, refused after it began staging: a zip with no backup inside.
        val second = File(app.cacheDir, "second.crema.zip").apply {
            ZipOutputStream(outputStream()).use { z -> z.putNextEntry(ZipEntry("images/bean:other")); z.write(1); z.closeEntry() }
        }
        val before = vm.ui.value.userMessages.size
        vm.startRestoreFile(second, "second", MainViewModel.RestoreMode.MERGE)
        VmHarness.idleUntil("the refusal") { vm.ui.value.userMessages.size > before }
        assertNotNull(vm.ui.value.restoreReport, "the first report is still open")

        val again = vm.ui.value.userMessages.size
        vm.confirmRestore()
        VmHarness.idleUntil("the restore", 60_000) { vm.ui.value.userMessages.size > again }
        val message = vm.ui.value.userMessages.last()
        assertTrue(coffee.crema.beans.BeanImageStore.exists(app, "bean:android-1"), message)
        // Done with, the stages go.
        val stages = File(app.noBackupFilesDir, "restore-photos")
        VmHarness.idleUntil("the stages go") { stages.listFiles().isNullOrEmpty() }
    }

    @Test
    fun aSecondReportOpenedWhileTheFirstRestoreRunsOutlivesIt() {
        // REVIEW-410 should-fix 2: the run holds its own stage, and finishing
        // clears only its own report.
        val vm = vm()
        val fixtureZip = File("../../core/de1-domain/tests/fixtures/backup/android-zipoutputstream.crema.zip")
        val stages = File(app.noBackupFilesDir, "restore-photos")
        val first = File(app.cacheDir, "first.crema.zip").apply { writeBytes(fixtureZip.readBytes()) }
        val second = File(app.cacheDir, "second.crema.zip").apply { writeBytes(fixtureZip.readBytes()) }
        val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
        val go = kotlinx.coroutines.CompletableDeferred<Unit>()
        var gated = true
        vm.afterRestoreSeries = {
            if (gated) {
                gated = false
                reached.complete(Unit)
                go.await()
            }
        }
        vm.startRestoreFile(first, "first", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the first report") { vm.ui.value.restoreReport != null }
        val firstStage = stages.listFiles()!!.single()
        vm.confirmRestore()
        VmHarness.idleUntil("the first run, mid-way", 60_000) { reached.isCompleted }

        vm.startRestoreFile(second, "second", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the second report") { vm.ui.value.restoreReport != null }
        // The second report replaced the first's hold; the run still holds its stage.
        // (Deletes run on IO: give a wrong release time to land.)
        val deleted = (1..20).any { Thread.sleep(100); !firstStage.exists() }
        assertTrue(!deleted, "the running restore's stage was deleted")

        val before = vm.ui.value.userMessages.size
        go.complete(Unit)
        VmHarness.idleUntil("the first restore", 60_000) { vm.ui.value.userMessages.size > before }
        // The first restore's photo landed from its own stage; gone again, for the second's to show.
        assertTrue(coffee.crema.beans.BeanImageStore.exists(app, "bean:android-1"), vm.ui.value.userMessages.last())
        coffee.crema.beans.BeanImageStore.delete(app, "bean:android-1")
        assertNotNull(vm.ui.value.restoreReport, "the second report is still open")
        val again = vm.ui.value.userMessages.size
        // Planned before the first landed, so it first comes back with new numbers.
        repeat(2) {
            if (vm.ui.value.userMessages.size > again) return@repeat
            vm.confirmRestore()
            VmHarness.idleUntil("the second restore", 60_000) {
                vm.ui.value.userMessages.size > again || vm.ui.value.restoreReport != null
            }
        }
        assertTrue(vm.ui.value.userMessages.size > again, "the second restore ran")
        assertTrue(coffee.crema.beans.BeanImageStore.exists(app, "bean:android-1"), vm.ui.value.userMessages.last())
        VmHarness.idleUntil("the stages go") { stages.listFiles().isNullOrEmpty() }
    }

    @Test
    fun twoPreparesInFlightKeepTheirOwnStages() {
        // REVIEW-410 fix check: a stage made on IO and held only back on Main
        // sat on disk unheld, and a second prepare's orphan sweep deleted it.
        val vm = vm()
        val stages = File(app.noBackupFilesDir, "restore-photos")
        val before = stages.listFiles()?.toSet().orEmpty()
        var first: File? = null
        var second: File? = null
        // On Main.immediate each runs on this thread until it first suspends;
        // the looper isn't idled in between, so a first that waits for Main still waits.
        vm.viewModelScope.launch { first = vm.newRestoreStage() }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (stages.listFiles()?.toSet().orEmpty().minus(before).isEmpty()) {
            check(System.nanoTime() < deadline) { "the first stage was never made" }
            Thread.sleep(5)
        }
        vm.viewModelScope.launch { second = vm.newRestoreStage() }
        VmHarness.idleUntil("both stages") { first != null && second != null }
        assertTrue(first != second, "two stages")
        assertTrue(first!!.isDirectory, "the first prepare's stage was deleted by the second's sweep")
        assertTrue(second!!.isDirectory, "the second prepare's stage")
    }

    @Test
    fun aChangedReReportForAnOlderPickNeverReplacesANewerReport() {
        // #413: the newest pick wins. The first pick's run finds the device
        // changed after a second pick opened its report: that report stays,
        // the first's goes with its stage, and a short notice says why.
        val vm = vm()
        val fixtureZip = File("../../core/de1-domain/tests/fixtures/backup/android-zipoutputstream.crema.zip")
        val stages = File(app.noBackupFilesDir, "restore-photos")
        val first = File(app.cacheDir, "first.crema.zip").apply { writeBytes(fixtureZip.readBytes()) }
        val second = File(app.cacheDir, "second.crema.zip").apply { writeBytes(fixtureZip.readBytes()) }
        val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
        val go = kotlinx.coroutines.CompletableDeferred<Unit>()
        vm.beforeRestoreRun = {
            if (!reached.isCompleted) {
                reached.complete(Unit)
                go.await()
            }
        }
        vm.startRestoreFile(first, "first", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the first report") { vm.ui.value.restoreReport != null }
        val firstStage = stages.listFiles()!!.single()
        vm.confirmRestore()
        VmHarness.idleUntil("the first run, before its re-plan") { reached.isCompleted }

        // The device changes under the first run's plan, then a second pick plans against it.
        vm.addRoaster("Changed meanwhile", null, null, null, "")
        VmHarness.idle()
        vm.startRestoreFile(second, "second", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the second report") { vm.ui.value.restoreReport != null }
        val secondReport = vm.ui.value.restoreReport

        val before = vm.ui.value.userMessages.size
        go.complete(Unit)
        VmHarness.idleUntil("the first run's end", 60_000) {
            vm.ui.value.userMessages.size > before || vm.ui.value.restoreReport != secondReport
        }
        assertEquals(secondReport, vm.ui.value.restoreReport, "the second pick's report is still the one open")
        assertEquals(MainViewModel.PICK_AGAIN, vm.ui.value.userMessages.last(), "the first run came back changed, and said so")
        VmHarness.idleUntil("the first pick's stage goes") { !firstStage.exists() }

        // And the report open is the second pick's: it runs.
        val again = vm.ui.value.userMessages.size
        vm.confirmRestore()
        VmHarness.idleUntil("the second restore", 60_000) { vm.ui.value.userMessages.size > again }
        assertTrue(vm.ui.value.userMessages.last().startsWith("Restored"), vm.ui.value.userMessages.last())
        VmHarness.idleUntil("the stages go") { stages.listFiles().isNullOrEmpty() }
    }

    @Test
    fun eachPickReadsItsOwnIncomingCopy() {
        // #413: every pick copied its file to one shared incoming file. A second
        // pick, even one refused, wrote over the first's while its report was
        // open, and the first's run then read the second's bytes when it read
        // the backup again ("changed since it was checked").
        // On the legacy backend, as aPlanThatGrowsBeforeTheRunReadsTheShotsItNowWrites:
        // rows deleted while the report is open make the run read more shots.
        coffee.crema.history.HistoryStore.legacyOnlyForTest = true
        try {
            SafeFile.openAllForTest()
            runBlocking {
                val rows = (0 until 295).map { shot("here$it", 1_600_000_000_000 - it * 60_000L) }
                assertTrue(coffee.crema.history.HistoryStore(app, json).save(rows))
            }
            val vm = vm()
            val stages = File(app.noBackupFilesDir, "restore-photos")
            val first = backupWithShots(10, 1_790_000_000_000)
            vm.restoreBackup(android.net.Uri.fromFile(first), MainViewModel.RestoreMode.MERGE)
            VmHarness.idleUntil("the first report") { vm.ui.value.restoreReport != null }

            // A second pick, refused once copied in: a zip with no backup inside.
            val second = File(app.cacheDir, "second.crema.zip").apply {
                ZipOutputStream(outputStream()).use { z -> z.putNextEntry(ZipEntry("images/bean:other")); z.write(1); z.closeEntry() }
            }
            val before = vm.ui.value.userMessages.size
            vm.restoreBackup(android.net.Uri.fromFile(second), MainViewModel.RestoreMode.MERGE)
            VmHarness.idleUntil("the refusal") { vm.ui.value.userMessages.size > before }
            assertNotNull(vm.ui.value.restoreReport, "the first report is still open")

            // Room for all ten now: the first's run reads its backup again for them.
            for (i in 0 until 5) vm.deleteShot("here$i")
            VmHarness.idle()
            val asked = vm.ui.value.userMessages.size
            vm.confirmRestore()
            VmHarness.idleUntil("the report again", 60_000) { vm.ui.value.restoreReport != null || vm.ui.value.userMessages.size > asked }
            assertNotNull(vm.ui.value.restoreReport, "the numbers changed, and the refused pick is no newer report: the first's shows them")
            val again = vm.ui.value.userMessages.size
            vm.confirmRestore()
            VmHarness.idleUntil("the restore", 60_000) { vm.ui.value.userMessages.size > again }
            val history = VmHarness.ownState(vm).history.map { it.id }
            assertEquals((0 until 10).map { "s$it" }.toSet(), history.filter { it.startsWith("s") }.toSet(), vm.ui.value.userMessages.last())
            // Done with, each pick's copy goes with its stage.
            VmHarness.idleUntil("the stages go") { stages.listFiles().isNullOrEmpty() }
            assertTrue(app.cacheDir.listFiles().orEmpty().none { it.name.endsWith(".crema") }, "no incoming copy left in the cache")
        } finally {
            coffee.crema.history.HistoryStore.legacyOnlyForTest = false
        }
    }

    @Test
    fun aDriveDownloadMovesIntoItsPicksStageAndGoesWithIt() {
        // #413: each Drive download is its own file, which its pick's stage then owns.
        val vm = vm()
        val fixtureZip = File("../../core/de1-domain/tests/fixtures/backup/android-zipoutputstream.crema.zip")
        val stages = File(app.noBackupFilesDir, "restore-photos")
        val download = File.createTempFile("restore-drive-", ".crema", app.cacheDir).apply { writeBytes(fixtureZip.readBytes()) }
        vm.startRestoreFile(download, "drive", MainViewModel.RestoreMode.WIPE, adopt = true)
        VmHarness.idleUntil("the report") { vm.ui.value.restoreReport != null }
        assertTrue(!download.exists(), "the download moved")
        assertContentEquals(fixtureZip.readBytes(), File(stages.listFiles()!!.single(), "incoming.crema").readBytes())
        vm.dismissRestore()
        VmHarness.idleUntil("the stage goes") { stages.listFiles().isNullOrEmpty() }
    }

    /** A gate a test seam waits on once: [reached] when it's hit, then it waits for [go]. */
    private class Gate {
        val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
        val go = kotlinx.coroutines.CompletableDeferred<Unit>()
        val seam: suspend () -> Unit = {
            if (!reached.isCompleted) {
                reached.complete(Unit)
                go.await()
            }
        }
    }

    private val fixtureZip get() = File("../../core/de1-domain/tests/fixtures/backup/android-zipoutputstream.crema.zip")
    private val stages get() = File(app.noBackupFilesDir, "restore-photos")

    @Test
    fun anOlderPicksFirstReportNeverReplacesANewerOne() {
        // #425: the newest pick wins on the first report too. The first pick
        // finishes reading after the second's report is open: that report
        // stays, the first goes with its stage, and a short notice says why.
        val vm = vm()
        val first = File(app.cacheDir, "first.crema.zip").apply { writeBytes(fixtureZip.readBytes()) }
        val second = backupWithShots(3, 1_790_000_000_000)
        val gate = Gate()
        vm.beforeRestorePlan = gate.seam
        vm.startRestoreFile(first, "first", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the first pick, read") { gate.reached.isCompleted }
        val firstStage = stages.listFiles()!!.single()

        vm.startRestoreFile(second, "second", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the second report") { vm.ui.value.restoreReport != null }
        val secondReport = vm.ui.value.restoreReport
        val secondStage = stages.listFiles()!!.single { it != firstStage }

        val before = vm.ui.value.userMessages.size
        gate.go.complete(Unit)
        VmHarness.idleUntil("the first pick's end", 60_000) {
            vm.ui.value.userMessages.size > before || vm.ui.value.restoreReport != secondReport
        }
        assertEquals(secondReport, vm.ui.value.restoreReport, "the second pick's report is still the one open")
        assertEquals(MainViewModel.OLDER_PICK, vm.ui.value.userMessages.last())
        VmHarness.idleUntil("the first pick's stage goes") { !firstStage.exists() }
        assertTrue(secondStage.isDirectory, "the second pick's stage stays")

        // And the report open is the second pick's: its three shots restore.
        val again = vm.ui.value.userMessages.size
        vm.confirmRestore()
        VmHarness.idleUntil("the second restore", 60_000) { vm.ui.value.userMessages.size > again }
        assertTrue(vm.ui.value.userMessages.last().startsWith("Restored"), vm.ui.value.userMessages.last())
        assertEquals(setOf("s0", "s1", "s2"), VmHarness.ownState(vm).history.map { it.id }.toSet())
        VmHarness.idleUntil("the stages go") { stages.listFiles().isNullOrEmpty() }
    }

    @Test
    fun aBringBackReplanNeverReopensAReportANewerPickReplaced() {
        // #425: "Bring them back too" re-plans across a suspension. A newer
        // pick's report that lands meanwhile stays open, and the older pick's
        // stage, released when it was replaced, is not held again.
        val vm = vm()
        val first = File(app.cacheDir, "first.crema.zip").apply { writeBytes(fixtureZip.readBytes()) }
        val second = backupWithShots(3, 1_790_000_000_000)
        vm.startRestoreFile(first, "first", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the first report") { vm.ui.value.restoreReport != null }
        val firstReport = vm.ui.value.restoreReport
        val firstStage = stages.listFiles()!!.single()

        val gate = Gate()
        vm.beforeRestorePlan = gate.seam
        val replan = assertNotNull(vm.bringBack())
        VmHarness.idleUntil("the bring-back re-plan") { gate.reached.isCompleted }

        vm.startRestoreFile(second, "second", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the second report") { vm.ui.value.restoreReport != firstReport }
        val secondReport = vm.ui.value.restoreReport
        VmHarness.idleUntil("the first pick's stage goes") { !firstStage.exists() }

        gate.go.complete(Unit)
        VmHarness.idleUntil("the re-plan's end", 60_000) { replan.isCompleted }
        assertEquals(secondReport, vm.ui.value.restoreReport, "the second pick's report is still the one open")

        val again = vm.ui.value.userMessages.size
        vm.confirmRestore()
        VmHarness.idleUntil("the second restore", 60_000) { vm.ui.value.userMessages.size > again }
        assertTrue(vm.ui.value.userMessages.last().startsWith("Restored"), vm.ui.value.userMessages.last())
        assertEquals(setOf("s0", "s1", "s2"), VmHarness.ownState(vm).history.map { it.id }.toSet())
        VmHarness.idleUntil("the stages go") { stages.listFiles().isNullOrEmpty() }
    }

    @Test
    fun aBringBackReplanNeverReopensADismissedReport() {
        // #425: the report dismissed while bring-back re-plans stays closed,
        // and its stage, released then, is not held again.
        val vm = vm()
        val first = File(app.cacheDir, "first.crema.zip").apply { writeBytes(fixtureZip.readBytes()) }
        vm.startRestoreFile(first, "first", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the report") { vm.ui.value.restoreReport != null }
        val gate = Gate()
        vm.beforeRestorePlan = gate.seam
        val replan = assertNotNull(vm.bringBack())
        VmHarness.idleUntil("the bring-back re-plan") { gate.reached.isCompleted }
        vm.dismissRestore()
        VmHarness.idleUntil("the stage goes") { stages.listFiles().isNullOrEmpty() }

        gate.go.complete(Unit)
        VmHarness.idleUntil("the re-plan's end", 60_000) { replan.isCompleted }
        assertEquals(null, vm.ui.value.restoreReport, "the dismissed report stays closed")
        val before = vm.ui.value.userMessages.size
        vm.confirmRestore()
        VmHarness.idle()
        assertEquals(before, vm.ui.value.userMessages.size, "nothing is pending to run")
    }

    @Test
    fun aNewerPickStillBeingReadAndThenRefusedLeavesAnOlderReReport() {
        // #425: the first pick's run finds the device changed while a second
        // pick is still being read. That one may yet be refused, so it hasn't
        // taken over: the first's new numbers are asked about again, and stay
        // open when the second is refused.
        val vm = vm()
        val first = File(app.cacheDir, "first.crema.zip").apply { writeBytes(fixtureZip.readBytes()) }
        // Replace with nothing in it: read whole, then refused by its plan.
        val second = File(app.cacheDir, "empty.crema.jsonl").apply {
            writeText(
                """{"kind":"crema-backup/v1","createdAt":1790000000000,"appVersion":"test","deviceLabel":"test",""" +
                    """"profileCount":0,"beanCount":0,"roasterCount":0,"shotCount":0,"recipeCount":0,"customMethodCount":0}""" + "\n",
            )
        }
        val run = Gate()
        vm.beforeRestoreRun = run.seam
        vm.startRestoreFile(first, "first", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the first report") { vm.ui.value.restoreReport != null }
        vm.confirmRestore()
        VmHarness.idleUntil("the first run, before its re-plan") { run.reached.isCompleted }

        // The device changes under the first run's plan; a second pick is read meanwhile.
        vm.addRoaster("Changed meanwhile", null, null, null, "")
        VmHarness.idle()
        val read = Gate()
        vm.beforeRestorePlan = read.seam
        vm.startRestoreFile(second, "second", MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the second pick, read") { read.reached.isCompleted }

        val before = vm.ui.value.userMessages.size
        run.go.complete(Unit)
        VmHarness.idleUntil("the first run's end", 60_000) { vm.ui.value.restoreReport != null || vm.ui.value.userMessages.size > before }
        assertNotNull(vm.ui.value.restoreReport, "the first pick's new numbers: ${vm.ui.value.userMessages.drop(before)}")
        assertTrue(MainViewModel.PICK_AGAIN !in vm.ui.value.userMessages.drop(before))

        val refused = vm.ui.value.userMessages.size
        read.go.complete(Unit)
        VmHarness.idleUntil("the second pick's refusal", 60_000) { vm.ui.value.userMessages.size > refused }
        assertTrue(vm.ui.value.userMessages.last().contains("Nothing was changed"), vm.ui.value.userMessages.last())
        assertNotNull(vm.ui.value.restoreReport, "the first pick's report is still open")

        val again = vm.ui.value.userMessages.size
        vm.confirmRestore()
        VmHarness.idleUntil("the first restore", 60_000) { vm.ui.value.userMessages.size > again }
        assertTrue(vm.ui.value.userMessages.last().startsWith("Restored"), vm.ui.value.userMessages.last())
        VmHarness.idleUntil("the stages go") { stages.listFiles().isNullOrEmpty() }
    }

    @Test
    fun aDriveDownloadGoesWhenItsPickHasNoStage() {
        // #425: the download is the pick's to delete. With no room for a stage,
        // the pick ends before the move, and the download goes then.
        val vm = vm()
        stages.deleteRecursively()
        stages.writeText("in the way")
        try {
            val download = File.createTempFile("restore-drive-", ".crema", app.cacheDir).apply { writeBytes(fixtureZip.readBytes()) }
            val before = vm.ui.value.userMessages.size
            vm.startRestoreFile(download, "drive", MainViewModel.RestoreMode.WIPE, adopt = true)
            VmHarness.idleUntil("the failure") { vm.ui.value.userMessages.size > before }
            assertTrue(vm.ui.value.userMessages.last().contains("couldn't make room"), vm.ui.value.userMessages.last())
            assertTrue(!download.exists(), "the download went")
        } finally {
            stages.delete()
        }
    }

    @Test
    fun aDriveDownloadGoesWhenItsCopyIntoTheStageFails() {
        // #425: a download that can't be moved is copied; if the copy fails too, the download still goes.
        val download = File.createTempFile("restore-drive-", ".crema", app.cacheDir).apply { writeBytes(fixtureZip.readBytes()) }
        val notADirectory = File(app.cacheDir, "not-a-stage").apply { writeText("a file") }
        try {
            assertFailsWith<java.io.IOException> { MainViewModel.adoptDownload(download, File(notADirectory, "incoming.crema")) }
            assertTrue(!download.exists(), "the download went")
        } finally {
            notADirectory.delete()
        }
    }

    private fun shot(id: String, at: Long) = StoredShot(id = id, completedAtMs = at, durationMs = 30_000)
}
