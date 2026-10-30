package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.HotWaterDose
import coffee.crema.core.HotWaterMode
import coffee.crema.core.HotWaterTarget
import coffee.crema.settings.Setting
import coffee.crema.ui.RemoteFixture.Companion.P
import coffee.crema.ui.RemoteFixture.Companion.S
import coffee.crema.ui.RemoteFixture.Companion.savedPrefs
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The hot-water dose at T6 (hot-water modes spec §2.6, AC-HW24): a remote
 * shows the hub's dose, No limit included, and leaving with "use the hub's"
 * adopts all four settings into this device's own and pushes them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AdoptionTest {

    private val store = ViewModelStore()
    private val fixture = RemoteFixture(store)

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
        fixture.kill()
    }

    private fun waitNormal(vm: MainViewModel) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (VmHarness.proxyController(vm).state.value.role != "normal") {
            if (System.nanoTime() > deadline) throw AssertionError("log: ${VmHarness.log(vm).take(25).joinToString(" | ")}")
            VmHarness.advance(50)
            Thread.sleep(5)
        }
    }

    @Test
    fun `T6 adopts all four hot-water settings and pushes them`() {
        val hubDose = HotWaterDose(HotWaterMode.Time, 45f, true, S.qcHotWaterVolumeMl, 2.5f)
        val ownDose = HotWaterDose(HotWaterMode.Volume, 30f, false, P.qcHotWaterVolumeMl, 3f)
        val vm = fixture.start()
        fixture.push(S)
        // The remote shows the hub's dose, No limit included; its own is kept.
        assertEquals(hubDose, vm.ui.value.hotWaterDose())
        assertTrue(vm.ui.value.qcHotWaterTimeNoLimit, "the hub's No limit")
        assertEquals(ownDose, VmHarness.ownState(vm).hotWaterDose())

        vm.switchToNormal()
        VmHarness.idle()
        assertNotNull(vm.ui.value.adoptPrompt)
        val dose = setOf(Setting.QC_HOT_WATER_MODE, Setting.QC_HOT_WATER_TIME, Setting.QC_HOT_WATER_TIME_NO_LIMIT, Setting.QC_HOT_WATER_RATIO)
        val changes = adoptableChanges(VmHarness.ownState(vm), S)
        assertTrue(changes.containsAll(dose), "the four differ and are adoptable: $changes")
        vm.resolveAdoption(useHub = true)
        VmHarness.advance(200)
        waitNormal(vm)
        VmHarness.idle()

        assertEquals(hubDose, VmHarness.ownState(vm).hotWaterDose(), "adopted")
        val saved = savedPrefs()
        assertEquals(listOf(HotWaterMode.Time, 45f, true, 2.5f), listOf(saved.qcHotWaterMode, saved.qcHotWaterTimeS, saved.qcHotWaterTimeNoLimit, saved.qcHotWaterRatio))
        // Pushed: the core's target is the adopted time dose.
        val target = coreJson.decodeFromString(HotWaterTarget.serializer(), VmHarness.bridge(vm).hotWaterTargetJson())
        assertEquals(HotWaterMode.Time, target.mode)
        assertEquals(target, vm.ui.value.hotWaterTarget)
    }
}
