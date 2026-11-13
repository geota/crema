package coffee.crema.restore

import coffee.crema.core.ShotPlan
import coffee.crema.history.StoredShot
import coffee.crema.ui.TelemetrySample
import org.junit.Test
import kotlin.test.assertEquals

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

    @Test
    fun aStubLandingInStepSevenOnMergeStaysAndGetsItsCurve() {
        val r = nextShots(listOf(stub), backup, plan(add = listOf("shot:remote:9")), replace = false)
        val row = r.next.single()
        assertEquals("stub", row.notes)
        assertEquals(3f, row.bagDebitG)
        assertEquals(curve, row.samples)
        assertEquals(listOf("shot:remote:9"), r.series.map { it.id })
    }

    @Test
    fun aStubLandingInStepSevenOnReplaceIsOverwritten() {
        val r = nextShots(listOf(stub), backup, plan(add = listOf("shot:remote:9")), replace = true)
        val row = r.next.single()
        assertEquals("backup", row.notes)
        assertEquals(18f, row.bagDebitG)
        assertEquals(curve, row.samples)
    }

    @Test
    fun anOverwriteWithNoCurveKeepsTheDevicesCurve() {
        val mine = shot("s1", "mine", curve)
        val row = nextShots(listOf(mine), listOf(shot("s1", "backup")), plan(overwrite = listOf("s1")), replace = true).next.single()
        assertEquals("backup", row.notes)
        assertEquals(curve, row.samples, "the same shot's curve stays, as a reload would show it")
    }
}
