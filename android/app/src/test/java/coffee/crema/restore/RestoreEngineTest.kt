package coffee.crema.restore

import android.app.Application
import coffee.crema.core.LocalState
import coffee.crema.core.PlanCode
import coffee.crema.core.ProfileMeta
import coffee.crema.core.RecipeMeta
import coffee.crema.core.RestorePlan
import coffee.crema.core.Verdict
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.toCommonSettings
import coffee.crema.visualizer.DEFAULT_VISUALIZER_SYNC_PREFS
import coffee.crema.ui.parseBackupBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine's steps over a sink that records them: the order, a stop at
 * every step, the marker, step 8's wait and the rules for running at all
 * (restore-safety §2 and §8, PR7a). The plan is the real core's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RestoreEngineTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }
    private val dir = Files.createTempDirectory("restore-engine").toFile()
    private val marker = RestoreMarkerStore(File(dir, RestoreMarkerStore.FILE_NAME), json)

    @After fun reset() = RestoreEngine.resetForTest()

    /** A device with [beans] and [shots], as the plan reads it. */
    private fun local(beans: List<String> = listOf("b1"), shots: List<String> = emptyList(), busy: Boolean = false) = LocalState(
        shell = "android",
        historyCapacity = 300u,
        machineBusy = busy,
        health = emptyList(),
        beans = beans,
        roasters = emptyList(),
        profiles = emptyList(),
        recipes = emptyList(),
        customMethods = emptyList(),
        shots = shots.map { coffee.crema.core.LocalShot(it, hasSamples = true) },
        photos = emptyList(),
        beansMissingPhoto = emptyList(),
        profileMeta = ProfileMeta(emptyList(), emptyList()),
        recipeMeta = RecipeMeta(HashMap(), emptyList()),
        common = AppPrefs().toCommonSettings(),
        visualizerPrefs = DEFAULT_VISUALIZER_SYNC_PREFS,
        totalLitres = 5.0,
        addedBeans = emptyList(),
    )

    /** The core's Android backup fixture: a bean, a roaster, a shot, a profile, settings. */
    private val backup = File("../../core/de1-domain/tests/fixtures/backup/android-backup.crema.jsonl").readBytes()
    private val BEAN = "bean:android-1"

    private class FakeSink(var state: LocalState) : RestoreSink {
        val calls = mutableListOf<String>()
        var busy = false
        var deleted: MarkerDeletes? = null
        var deletesOk = true
        var paused = 0
        val pausedAt = mutableListOf<String>()
        var beforeLibrary: suspend () -> Unit = {}
        override suspend fun localState() = state
        override fun machineBusy() = busy
        override suspend fun cancelTombstones(ids: List<String>) = true.also { calls += "tombstones" }
        override suspend fun putPhotos(plan: RestorePlan, staged: Map<String, File>) = true.also { calls += "photos" }
        override suspend fun applyRecipes(plan: RestorePlan, records: RestoreRecords) = true.also { calls += "recipes" }
        override suspend fun applyLibrary(plan: RestorePlan, records: RestoreRecords): Boolean {
            beforeLibrary()
            calls += "beans"
            return true
        }
        override suspend fun applyProfiles(plan: RestorePlan, records: RestoreRecords) = true.also { calls += "profiles" }
        override suspend fun applyShots(plan: RestorePlan, records: RestoreRecords): Int? = 0.also {
            calls += "shots"
            if (paused > 0) pausedAt += "shots"
        }
        override suspend fun applySingletons(plan: RestorePlan, records: RestoreRecords) = true.also { calls += "settings" }
        override suspend fun runDeletes(deletes: MarkerDeletes): Boolean {
            deleted = deletes
            calls += "deletes"
            return deletesOk
        }
        override fun pauseDeletes() { paused++ }
        override fun resumeDeletes() { paused-- }
        override fun changedDirs(): List<File> = emptyList()
    }

    private fun prepared() = prepareBackup(backup, "test", File(dir, "stage"))
    private fun records(p: PreparedBackup) = RestoreRecords(parseBackupBytes(p.jsonl, json, 0L)!!)

    private fun engine(sink: FakeSink, waitMs: Long = 200) =
        RestoreEngine(sink, marker, json, machineWaitMs = waitMs)

    @Test
    fun aMergeRunsEveryStepInOrderThenClearsItsMarker() = runBlocking<Unit> {
        val sink = FakeSink(local(beans = listOf(BEAN)))
        val e = engine(sink)
        val p = prepared()
        val plan = e.plan(p, RestoreKind.MERGE)
        val out = e.run(p, RestoreKind.MERGE, records(p), expected = plan)
        assertIs<RestoreOutcome.Done>(out)
        assertEquals(listOf("tombstones", "photos", "recipes", "beans", "profiles", "shots", "deletes"), sink.calls.filter { it != "settings" })
        assertNull(marker.read(), "a finished restore leaves no marker")
        assertEquals(RestoreState.IDLE, e.state())
    }

    @Test
    fun aStopAtAnyStepKeepsTheMarkerAndARerunFinishes() = runBlocking<Unit> {
        for (step in RestoreStep.entries) {
            marker.clear()
            val sink = FakeSink(local(beans = emptyList()))
            val e = engine(sink)
            val p = prepared()
            val plan = e.plan(p, RestoreKind.REPLACE)
            e.failAt = step
            val out = e.run(p, RestoreKind.REPLACE, records(p), expected = plan)
            assertIs<RestoreOutcome.Stopped>(out, "$step")
            assertEquals(step, out.at)
            assertEquals(RestoreStep.entries.takeWhile { it != step }.filter { it != RestoreStep.SETTINGS || step == RestoreStep.SETTINGS }, out.landed, "$step")
            if (step != RestoreStep.MARKER) {
                assertEquals(RestoreMarkerStore.PHASE_APPLYING, marker.read()?.phase, "$step")
                assertEquals(RestoreState.LEFTOVER, e.state(), "$step")
            }
            assertTrue("deletes" !in sink.calls, "no delete ran at $step")
            e.failAt = null
            assertIs<RestoreOutcome.Done>(e.run(p, RestoreKind.REPLACE, records(p), expected = e.plan(p, RestoreKind.REPLACE)), "re-run after $step")
            assertNull(marker.read())
        }
    }

    @Test
    fun aPlanThatNeedsAYesDoesNothingWithoutOne() = runBlocking<Unit> {
        val sink = FakeSink(local())
        val e = engine(sink)
        val p = prepared()
        val out = e.run(p, RestoreKind.REPLACE, records(p))
        assertIs<RestoreOutcome.Unconfirmed>(out)
        assertEquals(emptyList(), sink.calls)
        assertNull(marker.read())
    }

    @Test
    fun aDeviceThatChangedRunsNothing() = runBlocking<Unit> {
        val sink = FakeSink(local(beans = emptyList()))
        val e = engine(sink)
        val p = prepared()
        val seen = e.plan(p, RestoreKind.MERGE)
        sink.state = local(beans = listOf(BEAN))
        assertIs<RestoreOutcome.Changed>(e.run(p, RestoreKind.MERGE, records(p), expected = seen))
        assertEquals(emptyList(), sink.calls)
    }

    @Test
    fun aShotOrAFlashRefusesAtStepZero() = runBlocking<Unit> {
        val sink = FakeSink(local()).apply { busy = true }
        val e = engine(sink)
        val p = prepared()
        val out = e.run(p, RestoreKind.MERGE, records(p), expected = e.plan(p, RestoreKind.MERGE))
        assertIs<RestoreOutcome.Refused>(out)
        assertEquals(PlanCode.MachineBusy, out.plan.refusals.first().code)
        assertEquals(emptyList(), sink.calls)
    }

    @Test
    fun aShotThatOutlastsStepEightSkipsSettingsAndSaysSo() = runBlocking<Unit> {
        val sink = FakeSink(local(beans = emptyList()))
        sink.beforeLibrary = { sink.busy = true }
        val e = engine(sink, waitMs = 50)
        val p = prepared()
        val plan = e.plan(p, RestoreKind.REPLACE)
        val out = e.run(p, RestoreKind.REPLACE, records(p), expected = plan)
        assertIs<RestoreOutcome.Done>(out)
        assertTrue(out.singletonsSkipped)
        assertTrue("settings" !in sink.calls)
        assertTrue("settings and maintenance weren't changed" in outcomeMessage(out))
    }

    @Test
    fun oneRestoreAtATime() = runBlocking<Unit> {
        val sink = FakeSink(local(beans = listOf(BEAN)))
        val gate = CompletableDeferred<Unit>()
        sink.beforeLibrary = { gate.await() }
        val e = engine(sink)
        val p = prepared()
        val plan = e.plan(p, RestoreKind.MERGE)
        val first = async { e.run(p, RestoreKind.MERGE, records(p), expected = plan) }
        while (e.state() != RestoreState.RUNNING) kotlinx.coroutines.yield()
        // A second one, bounded from outside its NonCancellable run: without the
        // rule it would wait on the same gate, and this fails rather than hangs.
        val second = async { e.run(p, RestoreKind.MERGE, records(p), expected = plan) }
        val got = kotlinx.coroutines.withTimeoutOrNull(5_000) { second.await() }
        gate.complete(Unit)
        assertEquals(RestoreOutcome.Busy, got)
        assertIs<RestoreOutcome.Done>(first.await())
    }

    @Test
    fun theMarkerKeepsEarlierDeletesAndBootRunsAFinishedOnesDeletes() = runBlocking<Unit> {
        assertTrue(marker.begin(RestoreKind.REPLACE, "Pixel", MarkerDeletes(shots = listOf("a")), 1))
        assertTrue(marker.begin(RestoreKind.REPLACE, "Pixel", MarkerDeletes(shots = listOf("b"), photos = listOf("p")), 2))
        assertEquals(MarkerDeletes(listOf("a", "b"), listOf("p")), marker.read()?.deletes)
        val sink = FakeSink(local())
        val e = engine(sink)
        assertEquals("Pixel", e.checkAtBoot(), "an applying marker is an interrupted restore")
        assertNull(sink.deleted)
        e.dismissLeftover()
        assertEquals(MarkerDeletes(listOf("a", "b"), listOf("p")), sink.deleted)
        assertNull(marker.read())
    }

    @Test
    fun aRerunAfterTheBeansLandedKeepsItsShotsDebit() = runBlocking<Unit> {
        // Merge onto a device without the backup's bean: the shot on it is no zeroDebit.
        val sink = FakeSink(local(beans = emptyList()))
        val e = engine(sink)
        val p = prepared()
        val clean = e.plan(p, RestoreKind.MERGE)
        assertEquals(listOf(BEAN), clean.beans.add)
        assertEquals(emptyList(), clean.shots.zeroDebit)
        e.failAt = RestoreStep.PROFILES
        assertIs<RestoreOutcome.Stopped>(e.run(p, RestoreKind.MERGE, records(p), expected = clean))
        assertEquals(listOf(BEAN), marker.read()?.addedBeans)
        // The bean landed before the stop: the re-run's plan reads it as the restore's own.
        sink.state = local(beans = listOf(BEAN))
        val rerun = e.plan(p, RestoreKind.MERGE)
        assertEquals(emptyList(), rerun.shots.zeroDebit, "the bag a stopped run added isn't the device's own")
    }

    @Test
    fun theSaveDiffDeletesNothingFromStepOneAndResumesHoweverItEnds() = runBlocking<Unit> {
        for (stop in listOf(null, RestoreStep.SHOTS, RestoreStep.SETTINGS)) {
            marker.clear()
            val sink = FakeSink(local(beans = emptyList()))
            val e = engine(sink)
            val p = prepared()
            e.failAt = stop
            e.run(p, RestoreKind.REPLACE, records(p), expected = e.plan(p, RestoreKind.REPLACE))
            if (stop != RestoreStep.SHOTS) assertEquals(listOf("shots"), sink.pausedAt, "$stop")
            assertEquals(0, sink.paused, "resumed after $stop")
        }
    }

    @Test
    fun aFinishingMarkerWhoseDeletesWaitedIsKept() = runBlocking<Unit> {
        assertTrue(marker.begin(RestoreKind.REPLACE, "Pixel", MarkerDeletes(shots = listOf("a")), 1))
        assertTrue(marker.finishing())
        val sink = FakeSink(local()).apply { deletesOk = false }
        val e = engine(sink)
        assertNull(e.checkAtBoot())
        assertEquals(RestoreMarkerStore.PHASE_FINISHING, marker.read()?.phase, "the deletes are still owed")
        sink.deletesOk = true
        e.checkAtBoot()
        assertNull(marker.read())
    }

    @Test
    fun aRefusedBackupChangesNothing() = runBlocking<Unit> {
        val sink = FakeSink(local(beans = listOf(BEAN, "b9")))
        val e = engine(sink)
        val damaged = prepareBackup((String(backup) + "\n{ not json").toByteArray(), "bad", File(dir, "stage"))
        val plan = e.plan(damaged, RestoreKind.REPLACE)
        assertEquals(Verdict.Refused, plan.verdict)
        assertTrue("This backup is damaged" in refusalMessage(plan), refusalMessage(plan))
        assertIs<RestoreOutcome.Refused>(e.run(damaged, RestoreKind.REPLACE, records(damaged), expected = plan))
        assertEquals(emptyList(), sink.calls)
    }
}
