package coffee.crema.restore

import coffee.crema.core.ShotPlan
import coffee.crema.history.StoredShot
import coffee.crema.history.row
import coffee.crema.history.rows
import coffee.crema.ui.TelemetrySample
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The history a plan leaves, as step 7 recomputes it over the rows as they stand. */
class RestoreRowsTest {
    private val curve = listOf(
        TelemetrySample(elapsedMs = 0, pressure = 9f, flow = 2f, headTemp = 93f, mixTemp = 92f, weight = null, weightFlow = null, dispensedVolume = 0f, resistance = null, resistanceWeight = null, setHeadTemp = 93f, setGroupPressure = 9f, setGroupFlow = 0f, frameNumber = 1),
    )

    private fun shot(id: String, notes: String, samples: List<TelemetrySample> = emptyList(), debit: Float? = null) =
        StoredShot(id = id, completedAtMs = 1_700_000_000_000, durationMs = 30_000, notes = notes, samples = samples, bagDebitG = debit)

    private fun plan(add: List<String> = emptyList(), overwrite: List<String> = emptyList()) = ShotPlan(
        add = add, overwrite = overwrite, keep = 0u, remove = emptyList(), fill = emptyList(), zeroDebit = emptyList(),
        deletedHere = emptyList(), revive = emptyList(), notAdded = 0u, notRevived = emptyList(), pinnedRemoved = 0u,
    )

    private val backup = listOf(shot("shot:remote:9", "backup", curve, debit = 18f))
    private val stub = shot("shot:remote:9", "stub", debit = 3f)

    /** The plan as LibraryController runs it: rows in, whole shots to write out (#310 P4a). */
    private fun next(local: List<StoredShot>, backup: List<StoredShot>, plan: ShotPlan, replace: Boolean) =
        nextShots(local.rows(), backup, plan, replace, hasCurve = { r -> local.first { it.id == r.id }.samples.isNotEmpty() }, rowOf = { it.row() })

    @Test
    fun aStubLandingInStepSevenOnMergeStaysAndGetsItsCurve() {
        val r = next(listOf(stub), backup, plan(add = listOf("shot:remote:9")), replace = false)
        val row = r.next.single()
        assertEquals("stub", row.notes)
        assertEquals(3f, row.bagDebitG)
        assertEquals(curve.size.toUInt(), row.summary.sampleCount, "the row's summary names the filled curve")
        // The curve goes with the row's own fields, to be written whole.
        val written = r.series.single()
        assertEquals("shot:remote:9", written.id)
        assertEquals(curve, written.samples)
        assertEquals("stub", written.notes)
    }

    @Test
    fun aStubLandingInStepSevenOnReplaceIsOverwritten() {
        val r = next(listOf(stub), backup, plan(add = listOf("shot:remote:9")), replace = true)
        val row = r.next.single()
        assertEquals("backup", row.notes)
        assertEquals(18f, row.bagDebitG)
        assertEquals(curve, r.series.single().samples)
    }

    @Test
    fun anOverwriteWithNoCurveKeepsTheDevicesCurve() {
        val mine = shot("s1", "mine", curve)
        val r = next(listOf(mine), listOf(shot("s1", "backup")), plan(overwrite = listOf("s1")), replace = true)
        assertEquals("backup", r.next.single().notes)
        // Written with no samples: the store keeps the curve it holds (HistorySqliteStoreTest),
        // so memory matches what a reload shows.
        assertTrue(r.series.single().samples.isEmpty())
    }
}
