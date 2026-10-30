package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.HotWaterMode
import coffee.crema.profiles.CremaProfile
import coffee.crema.settings.AppPrefs
import coffee.crema.ui.RemoteFixture.Companion.writePrefs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The hot-water readout with nothing connected (hot-water modes spec R4,
 * AC-HW23): no DE1 or scale event carries the target, so a profile select
 * re-reads it from the core and the ratio's amount follows the new yield.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HotWaterOfflineReadoutTest {

    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    @Test
    fun `with nothing connected, selecting a 50 g profile shows 150 ml`() {
        writePrefs(AppPrefs(idleSleepMin = 30))
        val vm = VmHarness.build(store)
        VmHarness.awaitStartupLoaded(vm)
        assertEquals(HotWaterMode.Ratio, vm.ui.value.qcHotWaterMode)
        val library = MainViewModel::class.java.getDeclaredField("library").apply { isAccessible = true }.get(vm) as LibraryController
        val base = vm.ui.value.profiles.first { it.yieldOut == 36f }
        val fifty = Json(coreJson) { encodeDefaults = true }
            .encodeToString(CremaProfile.serializer(), base.copy(id = "fifty", name = "Fifty", source = "custom", yieldOut = 50f))
        runBlocking { library.adoptCustomProfile(fifty) }
        VmHarness.idle()

        vm.setActiveProfile(base.id)
        VmHarness.idle()
        fun readout() = hotWaterReadoutFor(vm.ui.value.hotWaterTarget, vm.ui.value.weightUnit, vm.ui.value.volumeUnit, vm.ui.value.qcHotWaterRatio)
        assertEquals("1:3 → 108 ml for a 36 g shot", readout())

        vm.setActiveProfile("fifty")
        VmHarness.idle()
        assertEquals(150f, assertNotNull(vm.ui.value.hotWaterTarget).volumeMl)
        assertEquals("1:3 → 150 ml for a 50 g shot", readout())
    }
}
