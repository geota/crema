package coffee.crema.ui

import coffee.crema.ui.UploadTargetId.Decent
import coffee.crema.ui.UploadTargetId.Visualizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One notice per shot across destinations — mirrors the web's upload-toast.vitest.ts. */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadOutcomesTest {
    private val shown = mutableListOf<Pair<Boolean, String>>()
    private val c = UploadOutcomeCollector(scope = null) { ok, m -> shown += ok to m }

    @Test
    fun `waits for every registered destination, then notifies once`() {
        c.register("s1", listOf(Visualizer, Decent))
        c.report("s1", Visualizer, UploadOutcome(UploadOutcomeKind.Uploaded))
        assertEquals(emptyList<Pair<Boolean, String>>(), shown)
        c.report("s1", Decent, UploadOutcome(UploadOutcomeKind.Uploaded))
        assertEquals(listOf(true to "Uploaded to Visualizer + Decent"), shown)
    }

    @Test
    fun `names the failure next to the success`() {
        c.register("s2", listOf(Visualizer, Decent))
        c.report("s2", Decent, UploadOutcome(UploadOutcomeKind.Failed, "serial not on account"))
        c.report("s2", Visualizer, UploadOutcome(UploadOutcomeKind.Uploaded))
        assertEquals(listOf(false to "Uploaded to Visualizer · Decent failed: serial not on account"), shown)
    }

    @Test
    fun `silent when everything was an expected skip, and alone without a batch`() {
        c.register("s3", listOf(Decent))
        c.report("s3", Decent, UploadOutcome(UploadOutcomeKind.Skipped, "Shorter than 5 s"))
        assertEquals(emptyList<Pair<Boolean, String>>(), shown)
        c.report("s4", Visualizer, UploadOutcome(UploadOutcomeKind.Queued))
        assertEquals(listOf(true to "Visualizer queued to retry"), shown)
        assertNull(summarizeUploadOutcomes(mapOf(Decent to UploadOutcome(UploadOutcomeKind.Skipped))))
    }

    @Test
    fun `a report that lands before the batch is sealed still counts`() {
        c.open("s5")
        // Decent failed synchronously while the destinations were being fired.
        c.report("s5", Decent, UploadOutcome(UploadOutcomeKind.Failed, "no serial"))
        assertEquals(emptyList<Pair<Boolean, String>>(), shown)
        c.seal("s5", listOf(Visualizer, Decent))
        c.report("s5", Visualizer, UploadOutcome(UploadOutcomeKind.Uploaded))
        assertEquals(listOf(false to "Uploaded to Visualizer · Decent failed: no serial"), shown)
    }

    @Test
    fun `only the destinations that fired are awaited`() {
        c.open("s6")
        c.seal("s6", listOf(Visualizer))
        c.report("s6", Visualizer, UploadOutcome(UploadOutcomeKind.Uploaded))
        assertEquals(listOf(true to "Uploaded to Visualizer"), shown)
    }

    @Test
    fun `a destination that never reports cannot hold the notice - the timeout completes the batch`() = runTest {
        val seen = mutableListOf<String>()
        val timed = UploadOutcomeCollector(scope = backgroundScope, timeoutMs = 90_000) { _, m -> seen += m }
        timed.register("s7", listOf(Visualizer, Decent))
        timed.report("s7", Visualizer, UploadOutcome(UploadOutcomeKind.Uploaded))
        advanceTimeBy(89_999)
        runCurrent()
        assertEquals(emptyList<String>(), seen)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("Uploaded to Visualizer"), seen)
        // The late answer no longer belongs to a batch: it notifies on its own, once.
        timed.report("s7", Decent, UploadOutcome(UploadOutcomeKind.Uploaded))
        assertEquals(listOf("Uploaded to Visualizer", "Uploaded to Decent"), seen)
    }

    @Test
    fun `pip counts only the destinations that count for the shot`() {
        fun t(id: UploadTargetId, enabled: Boolean, uploaded: Boolean, counted: Boolean = enabled) =
            UploadTarget(id, id.displayName, enabled, uploaded, null, counted = counted)
        assertEquals(UploadPip.Local, uploadPipFor(listOf(t(Visualizer, false, true))))
        assertEquals(UploadPip.Partial, uploadPipFor(listOf(t(Visualizer, true, true), t(Decent, true, false))))
        assertEquals(UploadPip.Uploaded, uploadPipFor(listOf(t(Visualizer, true, true), t(Decent, false, false))))
        // A sub-5 s flush: Decent could take it by hand but never counts it as missing.
        assertEquals(UploadPip.Uploaded, uploadPipFor(listOf(t(Visualizer, true, true), t(Decent, true, false, counted = false))))
    }
}
