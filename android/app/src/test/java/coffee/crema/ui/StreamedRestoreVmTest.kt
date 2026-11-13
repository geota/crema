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

    private fun shot(id: String, at: Long) = StoredShot(id = id, completedAtMs = at, durationMs = 30_000)
}
