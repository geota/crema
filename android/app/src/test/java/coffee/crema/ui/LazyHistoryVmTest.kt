package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import coffee.crema.core.ShotPatch
import coffee.crema.history.HistoryFixtures
import coffee.crema.history.HistoryStore
import coffee.crema.history.SeriesFiles
import coffee.crema.history.StoredShot
import coffee.crema.history.isManualLog
import coffee.crema.history.seriesReadsForTest
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.Stores
import coffee.crema.visualizer.PulledParts
import coffee.crema.visualizer.shotsFromCremaJsonl
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
import kotlin.test.assertTrue

/**
 * #310 P4a on the real ViewModel: a cold start loads rows only, and every
 * consumer that needs a curve reads its shot whole first, so none acts on a
 * row's missing samples. Export, compare, a pull's backfill and the restore
 * plan's `hasSamples` each have a case here; uploads are in `DecentSyncTest`
 * and `VisualizerRetryQueueTest`, backups in `SeriesLoadGateTest` and
 * `HistoryViewCapVmTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LazyHistoryVmTest {
    private val store = ViewModelStore()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun reset() {
        SafeFile.resetForTest()
        Stores.resetForTest()
        SaveFloor.resetForTest()
        runBlocking { HistoryStore(app, coreJson).erase() }
    }

    @After fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    /** Rows of every shape, planted as a previous launch left them; none pinned, so the order is by date.
     *  No profile: the fixture's stand-in isn't one the core's exporter reads. */
    private val planted = HistoryFixtures.rows(30, samplesPer = 12).map { it.copy(pinned = false, profile = null) }

    /** A cold start over [planted]: the rows are in, and no series has been read. */
    private fun coldStart(): MainViewModel {
        SafeFile.openAllForTest()
        runBlocking { assertTrue(HistoryStore(app, coreJson).save(planted)) }
        val reads = seriesReadsForTest.get()
        val vm = VmHarness.build(store).also { VmHarness.idle(); VmHarness.awaitStartupLoaded(it) }
        VmHarness.idleUntil("the rows") { VmHarness.library(vm).rowsReady }
        assertEquals(reads, seriesReadsForTest.get(), "a cold start reads no series")
        return vm
    }

    private fun whole(id: String) = planted.first { it.id == id }

    @Test
    fun `an export after a cold start carries every shot's curve`() {
        val vm = coldStart()
        val text = VmHarness.shotsJson(vm)
        val exported = shotsFromCremaJsonl(assertNotNull(text, "export: ${vm.ui.value.userMessages}"), coreJson).associateBy { it.id }
        assertEquals(planted.map { it.id }.toSet(), exported.keys)
        for (shot in planted) assertEquals(shot.samples, exported.getValue(shot.id).samples, "${shot.id}'s curve")
    }

    @Test
    fun `a compare loads every selected shot whole`() {
        val vm = coldStart()
        val rows = vm.ui.value.history.filter { r -> whole(r.id).samples.isNotEmpty() }.take(5)
        val shots = runBlocking { loadWhole(rows) { id -> VmHarness.whole(vm, id) } }
        assertEquals(rows.map { it.id }, shots.map { it.id })
        for (s in shots) assertEquals(whole(s.id).samples, s.samples, "${s.id} draws its own curve")
    }

    @Test
    fun `a pull's backfill at a cold start never replaces a shot's own curve, and fills one that has none`() {
        val vm = coldStart()
        val library = VmHarness.library(vm)
        val curved = vm.ui.value.history.first { whole(it.id).samples.isNotEmpty() && !it.isManualLog }
        val pulled = PulledParts(samples = HistoryFixtures.samples(3, 99), durationMs = 1)
        val patch = ShotPatch(tags = null, barista = null, backfillSamples = true, fillProfile = false, remoteVersion = 1_800_000_000L)
        library.applyRemoteShot(curved.id, patch, pulled)
        VmHarness.idleUntil("the pull's version") { library.findShot(curved.id)?.visualizerUpdatedAt == 1_800_000_000L }
        assertEquals(whole(curved.id).samples, VmHarness.whole(vm, curved.id).samples, "its own curve, not the pulled one")
        // A shot whose curve file is gone has none: the pull may fill it.
        val bare = vm.ui.value.history.first { r -> r.id != curved.id && whole(r.id).samples.isNotEmpty() && !r.isManualLog }
        SeriesFiles(File(app.filesDir, "shot-series"), coreJson).gzFile(bare.id).delete()
        assertFalse(library.hasCurve(bare), "no file, no curve")
        library.applyRemoteShot(bare.id, patch, pulled)
        VmHarness.idleUntil("the backfill") { library.findShot(bare.id)?.summary?.sampleCount == 3u }
        assertEquals(pulled.samples, VmHarness.whole(vm, bare.id).samples)
        // SPEC P4w: the backfill recomputes the summary, the row's chart and peaks with it, on screen and stored.
        val want = coffee.crema.history.summaryOf(whole(bare.id).copy(samples = pulled.samples, brewSeries = null), coreJson)
        val onScreen = assertNotNull(library.findShot(bare.id)).summary
        assertEquals(want.copy(brewSampleCount = onScreen.brewSampleCount), onScreen)
        assertTrue(onScreen.spark != null, "the row draws the pulled curve")
        VmHarness.idleUntil("the stored summary") {
            runBlocking { HistoryStore(app, coreJson).loadRows() }.first { it.id == bare.id }.summary.peakPressure == want.peakPressure
        }
    }

    @Test
    fun `the restore plan's local state says which rows have a curve, from the store`() {
        val vm = coldStart()
        val library = VmHarness.library(vm)
        val deps = LibraryController.RestoreDeps(
            machineBusy = { false },
            totalLitres = { 0.0 },
            restoreRecipes = { _, _, _, _ -> true },
            restoreSettings = { true },
            restoreMaintenance = { true },
        )
        val local = runBlocking { library.restoreSink(deps).localState() }.shots.associateBy { it.id }
        for (shot in planted) {
            assertEquals(shot.samples.isNotEmpty(), local.getValue(shot.id).hasSamples, "${shot.id}: hasSamples")
        }
        // The sync's own question, wired to the same store (VisualizerSync.hasSeries).
        assertTrue(vm.ui.value.history.all { library.hasCurve(it) == whole(it.id).samples.isNotEmpty() })
    }
}
