package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.MachineState
import coffee.crema.core.NotificationSource
import coffee.crema.core.StopReason
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
 * The machine session's projection into the UI state, through the view model
 * (MainViewModel breakup step 9a): the service-mode clock, faults, profile
 * upload progress, the armed stop targets and the stop that ended a shot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MachineSessionVmTest {
    private val store = ViewModelStore()

    @After fun tearDown() { store.clear(); VmHarness.idle() }

    private var clock = 1_000uL

    private fun build(): MainViewModel {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        return vm
    }

    private fun settle(vm: MainViewModel) {
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
    }

    private fun state(vm: MainViewModel, code: Int) {
        VmHarness.deliverCoreOutput(vm, VmHarness.bridge(vm).onNotification(NotificationSource.DE1_STATE, byteArrayOf(code.toByte(), 0), clock))
        clock += 1_000uL
        settle(vm)
    }

    private fun events(vm: MainViewModel, vararg events: String) {
        VmHarness.deliverCoreOutput(vm, """{"events":[${events.joinToString(",")}],"commands":[]}""")
        settle(vm)
    }

    @Test
    fun `a service mode anchors the mode clock and leaving it clears the clock`() {
        val vm = build()
        state(vm, 2) // Idle
        assertNull(vm.ui.value.modeStartedAtMs)
        state(vm, 6) // HotWater
        assertEquals(MachineState.HotWater, vm.ui.value.machineStateName)
        assertNotNull(vm.ui.value.modeStartedAtMs, "the mode clock runs")
        state(vm, 2)
        assertNull(vm.ui.value.modeStartedAtMs)
        assertEquals(0L, vm.ui.value.modeElapsedMs)
    }

    @Test
    fun `a fault shows and clears`() {
        val vm = build()
        events(vm, """{"type":"MachineErrorChanged","content":{"state":"Idle","substate":"Ready","message":"No water"}}""")
        assertEquals("No water", vm.ui.value.machineError)
        events(vm, """{"type":"MachineErrorChanged","content":{"state":"Idle","substate":"Ready"}}""")
        assertNull(vm.ui.value.machineError)
    }

    @Test
    fun `profile upload progress is shown until the upload completes`() {
        val vm = build()
        events(vm, """{"type":"ProfileUploadStarted","content":{"title":"Best","frame_count":2,"extension_frame_count":0,"upload_id":7}}""")
        assertTrue(vm.ui.value.profileUploading)
        events(vm, """{"type":"ProfileUploadProgress","content":{"frame":1,"extension":false,"total_acks":3,"acks_received":1}}""")
        assertEquals("1/3", vm.ui.value.profileUploadProgress)
        events(vm, """{"type":"ProfileUploadCompleted","content":{"title":"Best"}}""")
        assertFalse(vm.ui.value.profileUploading)
        assertNull(vm.ui.value.profileUploadProgress)
    }

    @Test
    fun `a DE1 disconnect clears the session's machine state and identity`() {
        val vm = build()
        state(vm, 2)
        events(vm, """{"type":"Firmware","content":{"release":1.0,"commits":0,"api_version":4,"firmware_string":"v1352"}}""")
        assertEquals(MachineState.Idle, vm.ui.value.machineStateName)
        assertEquals("v1352", vm.ui.value.de1Firmware)
        vm.disconnect()
        settle(vm)
        assertNull(vm.ui.value.machineStateName)
        assertNull(vm.ui.value.de1Firmware)
        assertFalse(vm.ui.value.shotInProgress)
    }

    @Test
    fun `the armed stop targets show, and the stop that ended a shot is attributed`() {
        val vm = build()
        events(vm, """{"type":"StopTargetsArmed","content":{"weight":36.0,"max_time":60.0,"armed":true,"weight_configured":36.0}}""")
        val targets = assertNotNull(vm.ui.value.stopTargets)
        assertEquals(36f, targets.weightConfiguredG)
        assertTrue(VmHarness.log(vm).any { "Stop targets armed: weight 36.0 g" in it })

        events(vm, """{"type":"StopTriggered","content":{"reason":"Weight"}}""")
        assertNull(vm.ui.value.lastStopReason, "no shot running: not an espresso stop")
        events(vm, """{"type":"ShotStarted"}""", """{"type":"StopTriggered","content":{"reason":"Weight"}}""")
        assertTrue(vm.ui.value.shotInProgress)
        assertEquals(StopReason.Weight, vm.ui.value.lastStopReason)
    }
}
