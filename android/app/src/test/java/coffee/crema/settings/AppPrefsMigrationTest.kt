package coffee.crema.settings

import android.app.Application
import coffee.crema.core.HotWaterDose
import coffee.crema.core.HotWaterMode
import coffee.crema.core.StoredHotWater
import coffee.crema.ui.VmHarness
import coffee.crema.ui.coreJson
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hot-water dose's migration in [SettingsStore.load] (hot-water modes
 * spec §2.6, AC-HW25): a `prefs.json` without a mode goes through the core's
 * never-changed rule once, is saved, and the `hot-water.json` marker keeps a
 * downgrade's dose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AppPrefsMigrationTest {

    private val dir get() = VmHarness.app().filesDir
    private val prefsFile get() = File(dir, "prefs.json")
    private val markerFile get() = File(dir, "hot-water.json")

    private fun store() = SettingsStore(VmHarness.app(), coreJson)
    private fun load(): AppPrefs = runBlocking { store().load() }
    private fun AppPrefs.dose(): HotWaterDose = HotWaterDose(qcHotWaterMode!!, qcHotWaterTimeS!!, qcHotWaterTimeNoLimit!!, qcHotWaterVolumeMl, qcHotWaterRatio!!)

    /** What a 0.0.9 build writes: the nested blob, no hot-water mode. */
    private fun writeOldPrefs(volumeMl: Float) = prefsFile.writeText("""{"common":{"qcHotWaterVolumeMl":$volumeMl}}""")

    @Before
    fun clean() {
        prefsFile.delete()
        markerFile.delete()
    }

    @Test
    fun `hot water never-changed`() {
        // No prefs.json: the default dose, ratio 1:3.
        assertEquals(HotWaterMode.Ratio, load().qcHotWaterMode)
        assertEquals(3f, load().qcHotWaterRatio)

        // The old default 150 ml was never changed: ratio. Anything else the
        // user chose: volume, within the core's range.
        for ((stored, mode, ml) in listOf(Triple(150f, HotWaterMode.Ratio, 150f), Triple(200f, HotWaterMode.Volume, 200f), Triple(500f, HotWaterMode.Volume, 250f))) {
            clean()
            writeOldPrefs(stored)
            val first = load()
            assertEquals(mode, first.qcHotWaterMode, "$stored ml")
            assertEquals(ml, first.qcHotWaterVolumeMl, "$stored ml")
            if (mode == HotWaterMode.Ratio) assertEquals(3f, first.qcHotWaterRatio)
            // Saved on the first load, the marker with it.
            assertTrue("\"qcHotWaterMode\"" in prefsFile.readText(), "$stored ml saved")
            assertTrue(markerFile.exists(), "$stored ml marker")
            // A second load reads the stored dose and writes nothing.
            prefsFile.setLastModified(1_000L)
            val second = load()
            assertEquals(first.dose(), second.dose(), "$stored ml again")
            assertEquals(1_000L, prefsFile.lastModified(), "$stored ml: the second load doesn't re-derive or save")
        }
    }

    /** The rule runs once: a dose stored later is read as it is, even
     *  volume 150 (which the never-changed rule would make ratio). */
    @Test
    fun `hot water stored dose is never re-derived`() {
        prefsFile.writeText("""{"common":{"qcHotWaterVolumeMl":150.0,"qcHotWaterMode":"volume","qcHotWaterTimeS":30.0,"qcHotWaterTimeNoLimit":false,"qcHotWaterRatio":3.0}}""")
        val p = load()
        assertEquals(HotWaterMode.Volume to 150f, p.qcHotWaterMode to p.qcHotWaterVolumeMl)
    }

    @Test
    fun `downgrade marker`() {
        // Volume 150 chosen on this build, then a 0.0.9 run rewrote prefs.json
        // without the mode: the marker keeps volume 150 (not the never-changed ratio).
        markerFile.writeText(coreJson.encodeToString(StoredHotWater.serializer(), StoredHotWater(HotWaterMode.Volume, 150f, 30f, false, 3f)))
        writeOldPrefs(150f)
        val p = load()
        assertEquals(HotWaterMode.Volume, p.qcHotWaterMode)
        assertEquals(150f, p.qcHotWaterVolumeMl)

        // A stored mode wins over the marker.
        clean()
        prefsFile.writeText("""{"common":{"qcHotWaterVolumeMl":150.0,"qcHotWaterMode":"ratio","qcHotWaterTimeS":30.0,"qcHotWaterTimeNoLimit":false,"qcHotWaterRatio":2.5}}""")
        markerFile.writeText(coreJson.encodeToString(StoredHotWater.serializer(), StoredHotWater(HotWaterMode.Volume, 150f, 30f, false, 3f)))
        assertEquals(HotWaterMode.Ratio to 2.5f, load().let { it.qcHotWaterMode to it.qcHotWaterRatio })
        runBlocking { store().save(AppPrefs(qcHotWaterMode = HotWaterMode.Time, qcHotWaterTimeS = 45f, qcHotWaterTimeNoLimit = false, qcHotWaterRatio = 3f)) }
        assertEquals(HotWaterMode.Time, load().qcHotWaterMode)
        // Every save of a dose rewrites the marker.
        val marker = coreJson.decodeFromString(StoredHotWater.serializer(), markerFile.readText())
        assertEquals(HotWaterMode.Time, marker.mode)
        assertEquals(45f, marker.timeS)

        // Erase all data takes the marker too.
        runBlocking { store().erase() }
        assertFalse(markerFile.exists(), "erased")
    }
}
