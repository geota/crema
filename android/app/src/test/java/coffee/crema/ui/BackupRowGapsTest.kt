package coffee.crema.ui

import android.app.Application
import coffee.crema.history.StoredShot
import coffee.crema.maintenance.defaultMaintenanceState
import coffee.crema.settings.AppPrefs
import coffee.crema.visualizer.DEFAULT_VISUALIZER_SYNC_PREFS
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One unreadable row can't block a backup (#329), and a section that can't be
 * written fails it rather than going out empty: an empty backup with
 * `shotCount: 0` would upload, pass any strict parse, and replace a good copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BackupRowGapsTest {
    private val json = coreJson
    private val now = 1_790_000_000_000

    private fun sample(pressure: Float) = TelemetrySample(
        elapsedMs = 0, pressure = pressure, flow = 2f, headTemp = 92f, mixTemp = 90f,
        weight = null, weightFlow = null, dispensedVolume = 20f, resistance = null, resistanceWeight = null,
        setHeadTemp = 93f, setGroupPressure = 9f, setGroupFlow = 0f, frameNumber = 1,
    )

    private fun shot(id: String, pressure: Float = 9f) =
        StoredShot(id = id, completedAtMs = now, durationMs = 1_000, samples = listOf(sample(pressure)))

    private fun snapshot(state: MainUiState, customs: List<String> = emptyList()) = BackupSnapshot(
        customs = customs,
        state = state,
        pinned = emptySet(),
        hidden = emptySet(),
        prefs = AppPrefs(),
        visualizerPrefs = DEFAULT_VISUALIZER_SYNC_PREFS,
        userRecipes = emptyList(),
    )

    private fun state() = MainUiState(maintenance = defaultMaintenanceState(now))

    private fun build(state: MainUiState, customs: List<String> = emptyList()) =
        backupBundleJsonl(snapshot(state, customs), json, now, "test", "Android test")

    private fun header(jsonl: String) = json.parseToJsonElement(jsonl.lineSequence().first()).jsonObject

    @Test
    fun `a NaN in one shot costs that shot, not the backup`() {
        val built = build(state().copy(history = rowViewOf(listOf(shot("good"), shot("nan", Float.NaN), shot("also")))))
        assertEquals(listOf(OmittedBackupRow("shot", 1, null)), built.omitted)
        assertEquals(2, header(built.jsonl).getValue("shotCount").jsonPrimitive.int)
        assertEquals(1, header(built.jsonl).getValue("omittedCount").jsonPrimitive.int)
        assertEquals(listOf("good", "also"), parseBackupBytes(built.jsonl.toByteArray(), json, now).shots.map { it.id })
    }

    @Test
    fun `a bean that won't encode is left out alone`() {
        val good = coffee.crema.beans.newBean("Good", "r1", 4, null, now).copy(id = "good")
        val built = build(state().copy(beans = rowViewOf(listOf(good, good.copy(id = "nan", bagSize = Float.NaN)))))
        assertEquals(listOf(OmittedBackupRow("bean", 1, null)), built.omitted)
        assertEquals(listOf("good"), parseBackupBytes(built.jsonl.toByteArray(), json, now).beans.map { it.id })
    }

    /** That file would report success, upload, and let retention drop the good
     *  copies: `shotCount: 0`, `omittedCount: 300`. */
    @Test
    fun `a section whose rows are all unreadable fails the backup`() {
        assertFailsWith<Exception> { build(state().copy(history = rowViewOf(List(3) { shot("nan$it", Float.NaN) }))) }
    }

    @Test
    fun `an omitted row is named from its place in the snapshot`() {
        val snap = snapshot(state().copy(history = rowViewOf(listOf(shot("good"), shot("nan", Float.NaN)))))
        val built = backupBundleJsonl(snap, json, now, "test", "Android test")
        assertEquals(listOf("nan"), built.omitted.map { omittedRowId(it, snap) })
    }

    /** It used to throw for the whole file, from `parseToJsonElement`. */
    @Test
    fun `an unreadable custom profile is left out alone`() {
        val built = build(state(), customs = listOf("""{"id":"mine","name":"Mine"}""", "{ not json"))
        assertEquals(listOf(OmittedBackupRow("profile", 1, null)), built.omitted)
        assertEquals(1, header(built.jsonl).getValue("profileCount").jsonPrimitive.int)
    }

    @Test
    fun `a clean backup has no omitted count`() {
        val built = build(state().copy(history = rowViewOf(listOf(shot("good")))))
        assertTrue(built.omitted.isEmpty())
        assertFalse("omittedCount" in built.jsonl)
        assertNull(backupGapsNotice(built))
    }

    /** It used to become `{}`, and the backup went out without the section. */
    @Test
    fun `a section that won't encode fails the backup`() {
        val broken = state().copy(maintenance = defaultMaintenanceState(now).copy(totalLitres = Double.NaN))
        assertFailsWith<Exception> { build(broken) }
    }

    @Test
    fun `the notice counts what went missing`() {
        fun built(vararg kinds: String, degraded: Int = 0) = BuiltBackup(
            "",
            degraded = List(degraded) { "s$it" },
            omitted = kinds.mapIndexed { i, k -> OmittedBackupRow(k, i) },
        )
        assertEquals("1 shot couldn't be read and was left out of this backup.", backupGapsNotice(built("shot")))
        assertEquals("2 shots couldn't be read and were left out of this backup.", backupGapsNotice(built("shot", "shot")))
        assertEquals("2 items couldn't be read and were left out of this backup.", backupGapsNotice(built("shot", "bean")))
        assertEquals("1 method couldn't be read and was left out of this backup.", backupGapsNotice(built("brewMethod")))
        assertEquals("2 shots were backed up without their profile.", backupGapsNotice(built(degraded = 2)))
    }
}
