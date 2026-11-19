package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.ui.brewlog.BrewLogOwner
import kotlinx.coroutines.runBlocking
import coffee.crema.visualizer.wireShotJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dating a logged brew through the real controllers (#300): the form saves its
 * date, a saved date moves the row, and a future one is refused in both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ManualBrewDateVmTest {
    /** P3a's `shots.db` read directly: stage 1, a history that hasn't flipped (#310 A5). */
    @get:org.junit.Rule val stage2 = coffee.crema.history.Stage2Rule(enabled = false)

    @org.junit.Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    private val store = ViewModelStore()
    private val now = 1_700_000_000_000
    private val hour = 3_600_000L

    @After fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    private fun vm(): MainViewModel = VmHarness.build(store).also {
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(it)
    }

    private fun history(vm: MainViewModel) = VmHarness.rows(vm)

    /** Log a brew through the form, dated [at] (null = now). */
    private fun log(vm: MainViewModel, at: Long?, dose: Double = 15.0, nowMs: Long = now): coffee.crema.history.ShotRow {
        vm.openLogBrew(BrewLogOwner.HISTORY)
        vm.updateLogBrew { it.copy(method = "pourover", dose = dose, completedAtMs = at) }
        assertTrue(vm.saveLogBrew(nowMs), "the form saved")
        return history(vm).first { it.doseG == dose.toFloat() }
    }

    @Test
    fun `an untouched date is the moment of saving`() {
        val vm = vm()
        assertEquals(now, log(vm, at = null).completedAtMs)
    }

    @Test
    fun `a backdated brew sorts by its date and isn't the newest`() {
        val vm = vm()
        log(vm, at = now, dose = 15.0)
        log(vm, at = now - 24 * hour, dose = 30.0)
        assertEquals(listOf(15f, 30f), history(vm).map { it.doseG })
    }

    @Test
    fun `the next log seeds from the newest brew, not the backdated one`() {
        val vm = vm()
        log(vm, at = now, dose = 15.0)
        log(vm, at = now - 24 * hour, dose = 30.0)
        vm.openLogBrew(BrewLogOwner.HISTORY)
        vm.reseedLogBrew("pourover")
        assertEquals(15.0, assertNotNull(vm.logBrew.value).dose)
    }

    @Test
    fun `a future date keeps the form open and saves nothing`() {
        val vm = vm()
        vm.openLogBrew(BrewLogOwner.HISTORY)
        vm.updateLogBrew { it.copy(method = "pourover", dose = 15.0, completedAtMs = now + 60_000) }
        assertFalse(vm.saveLogBrew(now))
        assertTrue(assertNotNull(vm.logBrew.value).attempted)
        assertEquals(emptyList(), history(vm))
    }

    @Test
    fun `changing a saved date moves the row and survives a reload`() {
        val vm = vm()
        val moved = log(vm, at = now, dose = 15.0)
        val other = log(vm, at = now - hour, dose = 30.0)
        vm.updateManualBrew(moved.id, completedAtMs = now - 2 * hour)
        VmHarness.idle()
        assertEquals(listOf(other.id, moved.id), history(vm).map { it.id })
        assertEquals(now - 2 * hour, history(vm).last().completedAtMs)
        val stored = HistoryStore(VmHarness.app(), Json { ignoreUnknownKeys = true })
        VmHarness.idleUntil("the history saved") {
            runBlocking { stored.loadRows() }.map { it.id } == listOf(other.id, moved.id)
        }
    }

    @Test
    fun `a saved brew can't be dated in the future`() {
        val vm = vm()
        val row = log(vm, at = now)
        vm.updateManualBrew(row.id, completedAtMs = System.currentTimeMillis() + 60_000)
        assertEquals(now, history(vm).single().completedAtMs)
    }

    @Test
    fun `a date edit leaves the bag alone`() {
        val vm = vm()
        val row = log(vm, at = now)
        val before = VmHarness.ownState(vm).beans
        vm.updateManualBrew(row.id, completedAtMs = now - hour)
        assertEquals(before, VmHarness.ownState(vm).beans)
    }

    @Test
    fun `a machine shot's date can't be changed`() {
        val vm = vm()
        val machine = StoredShot(id = "machine", completedAtMs = now, durationMs = 30_000)
        VmHarness.library(vm).insertPulledShots(listOf(machine))
        VmHarness.idle()
        vm.updateManualBrew("machine", completedAtMs = now - hour)
        assertEquals(now, history(vm).single().completedAtMs)
        assertNull(history(vm).single().brewMethod)
    }

    /** The legacy backend's cap refuses a pull rather than evict a row. */
    @Test
    fun `pull_never_evicts_at_cap`() {
        HistoryStore.legacyOnlyForTest = true
        try {
            val brews = List(HistoryStore.LEGACY_MAX_SHOTS) { i -> machine("r$i", now - i * hour) }
            val oldest = brews.last().copy(id = "brew-log", brewMethod = "pourover")
            val rows = brews.dropLast(1) + oldest
            stored(rows)
            val vm = vm()

            val refused = VmHarness.library(vm).insertPulledShots(listOf(machine("new", now + hour)))
            VmHarness.idle()

            assertEquals(setOf("new"), refused)
            assertEquals(rows.map { it.id }, history(vm).map { it.id })
        } finally {
            HistoryStore.legacyOnlyForTest = false
        }
    }

    /** From #310 P6a nothing caps a pull: on 300 rows it adds the new one and keeps every row. */
    @Test
    fun `a pull onto 300 rows adds the new one and keeps them all`() {
        val rows = List(HistoryStore.LEGACY_MAX_SHOTS) { i -> machine("r$i", now - i * hour) }
        stored(rows)
        val vm = vm()
        val refused = VmHarness.library(vm).insertPulledShots(listOf(machine("new", now + hour)))
        VmHarness.idle()
        assertTrue(refused.isEmpty())
        assertEquals(listOf("new") + rows.map { it.id }, history(vm).map { it.id })
    }

    /** A store file already holding [rows], as a VM built next would load it. */
    private fun stored(rows: List<StoredShot>) = runBlocking { HistoryStore(VmHarness.app(), coreJson).save(rows) }

    private fun machine(id: String, at: Long) = StoredShot(id = id, completedAtMs = at, durationMs = 30_000)

    /** A bundle with the header and [shots], the way `backupBundleJson` emits them. */
    private fun bundle(vararg shots: StoredShot): String {
        fun line(body: JsonObject, kind: String) =
            Json.encodeToString(JsonObject.serializer(), JsonObject(body + ("kind" to JsonPrimitive(kind))))
        return (listOf(line(JsonObject(mapOf("createdAt" to JsonPrimitive(1))), "crema-backup/v1")) + shots.map { line(wireShotJson(it, forBackup = true), "shot") })
            .joinToString("\n")
    }

    private fun restore(vm: MainViewModel, text: String): String {
        val before = VmHarness.ownState(vm).userMessages.size
        vm.restoreBackupFromText(text, MainViewModel.RestoreMode.MERGE)
        VmHarness.idleUntil("the restore reported") { VmHarness.ownState(vm).userMessages.size > before }
        return VmHarness.ownState(vm).userMessages.last()
    }

    /** From #310 P6a #309's refusal goes: a date past rank 300 of a long list saves, in its place. */
    @Test
    fun `a date past rank 300 of a long stored list saves in its place`() {
        stored(List(350) { i -> machine("r$i", now - i * hour) })
        val vm = vm()
        assertEquals(350, history(vm).size, "every row in view")
        vm.openLogBrew(BrewLogOwner.HISTORY)
        val at = now - 320 * hour - hour / 2
        vm.updateLogBrew { it.copy(method = "pourover", dose = 15.0, completedAtMs = at) }
        assertTrue(vm.saveLogBrew(now), "older than rank 300: fine now")
        VmHarness.idle()
        assertEquals(351, history(vm).size)
        assertEquals(321, history(vm).indexOfFirst { it.completedAtMs == at }, "between r320 and r321")
    }

    /** The legacy backend keeps #309's refusal: a date older than all it keeps would be dropped. */
    @Test
    fun `on the legacy backend a date older than a full history is refused, not lost`() {
        HistoryStore.legacyOnlyForTest = true
        try {
            stored(List(HistoryStore.LEGACY_MAX_SHOTS) { i -> machine("r$i", now - i * hour) })
            val vm = vm()
            vm.openLogBrew(BrewLogOwner.HISTORY)
            vm.updateLogBrew { it.copy(method = "pourover", dose = 15.0, completedAtMs = now - 400 * hour) }
            assertFalse(vm.saveLogBrew(now))
            assertEquals(HistoryStore.LEGACY_MAX_SHOTS, history(vm).size)
        } finally {
            HistoryStore.legacyOnlyForTest = false
        }
    }

    @Test
    fun `a brew made now stays when the clock is behind a full history`() {
        HistoryStore.legacyOnlyForTest = true
        try {
            stored(List(HistoryStore.LEGACY_MAX_SHOTS) { i -> machine("r$i", now + (1_000 - i) * hour) })
            val vm = vm()
            val made = log(vm, at = null)
            assertEquals(now, made.completedAtMs)
            assertEquals(HistoryStore.LEGACY_MAX_SHOTS, history(vm).size)
            assertEquals(made.id, history(vm).last().id)
            assertNull(history(vm).firstOrNull { it.id == "r${HistoryStore.LEGACY_MAX_SHOTS - 1}" }, "the legacy cap: the oldest other row goes")
        } finally {
            HistoryStore.legacyOnlyForTest = false
        }
    }

    /** From #310 P6a: with the clock behind the history, a brew made now goes last, and every row stays. */
    @Test
    fun `a brew made now with the clock behind keeps every row`() {
        stored(List(HistoryStore.LEGACY_MAX_SHOTS) { i -> machine("r$i", now + (1_000 - i) * hour) })
        val vm = vm()
        val made = log(vm, at = null)
        assertEquals(HistoryStore.LEGACY_MAX_SHOTS + 1, history(vm).size)
        assertEquals(made.id, history(vm).last().id)
    }

    @Test
    fun `a restore with nothing new leaves a long stored list alone`() {
        val rows = List(350) { i -> machine("r$i", now - i * hour) }
        stored(rows)
        val vm = vm()
        val told = restore(vm, bundle(rows.first()))
        assertTrue(told.startsWith("Restored nothing new"), told)
        assertEquals(350, VmHarness.library(vm).allShots().size)
    }

    /** From #310 P6a nothing caps a restore: an old shot restores onto 300 rows, in view. */
    @Test
    fun `a restore onto 300 rows adds the old shot in view`() {
        stored(List(HistoryStore.LEGACY_MAX_SHOTS) { i -> machine("r$i", now - i * hour) })
        val vm = vm()
        val told = restore(vm, bundle(machine("old", now - 1_000 * hour)))
        assertTrue(told.startsWith("Restored 1 shot"), told)
        assertEquals(HistoryStore.LEGACY_MAX_SHOTS + 1, history(vm).size)
        assertEquals("old", history(vm).last().id)
    }

    /** The legacy backend, when the move to shots.db failed, still has its cap. */
    @Test
    fun `a restore on the legacy backend counts the shots it kept and says what the cap dropped`() {
        HistoryStore.legacyOnlyForTest = true
        try {
            stored(List(HistoryStore.LEGACY_MAX_SHOTS) { i -> machine("r$i", now - i * hour) })
            val vm = vm()
            val told = restore(vm, bundle(machine("old", now - 1_000 * hour)))
            assertTrue("History is full at 300 shots, so no shots were added" in told, told)
            assertEquals(HistoryStore.LEGACY_MAX_SHOTS, history(vm).size)
        } finally {
            HistoryStore.legacyOnlyForTest = false
        }
    }
}
