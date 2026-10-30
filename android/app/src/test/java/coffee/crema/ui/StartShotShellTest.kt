package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.MainSources
import coffee.crema.core.CremaBridge
import coffee.crema.core.NotificationSource
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F16b on Android: the core runs the shot start (`start_shot`), the
 * post-steam purge and the group-head banner; the view model maps the
 * outcome and the events.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class StartShotShellTest {
    private val store = ViewModelStore()

    @After fun tearDown() { store.clear(); VmHarness.idle() }

    private fun build(): Pair<MainViewModel, CremaBridge> {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        val bridge = MainViewModel::class.java.declaredFields
            .single { it.type == CremaBridge::class.java }
            .apply { isAccessible = true }.get(vm) as CremaBridge
        return vm to bridge
    }

    private fun deliver(vm: MainViewModel, json: String) {
        val apply = MainViewModel::class.java.getDeclaredMethod("applyCoreOutputJson", String::class.java)
        apply.isAccessible = true
        apply.invoke(vm, json)
    }

    private fun idle(bridge: CremaBridge) =
        bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(2, 0), 0uL)

    @Test
    fun `require-scale refuses a shot that stops on weight, and nothing starts`() {
        val (vm, bridge) = build()
        vm.setActiveProfile(vm.ui.value.profiles.first { it.yieldOut > 0f && it.segments.size <= 20 }.id)
        vm.setStopOnWeight(true)
        vm.setRequireScale(true)
        VmHarness.idle()
        idle(bridge)
        vm.startShot()
        VmHarness.idle()
        assertTrue(
            vm.ui.value.userMessages.any { "Require scale to start" in it },
            "${vm.ui.value.userMessages}",
        )
        assertFalse(bridge.shotStarting())
        assertFalse(vm.ui.value.shotStarting)
    }

    @Test
    fun `an accepted start shows starting until the core ends it`() {
        val (vm, bridge) = build()
        vm.setActiveProfile(vm.ui.value.profiles.first { it.segments.size <= 20 }.id)
        vm.setStopOnWeight(false)
        VmHarness.idle()
        idle(bridge)
        vm.startShot()
        VmHarness.idleUntil("the start") { vm.ui.value.shotStarting }
        assertTrue(bridge.shotStarting())
        vm.stopShot()
        VmHarness.idleUntil("the cancel") { !vm.ui.value.shotStarting }
        assertFalse(bridge.shotStarting())
    }

    @Test
    fun `a group-head start's skipped checks are told without stopping it`() {
        val (vm, _) = build()
        deliver(
            vm,
            """{"events":[{"type":"GroupHeadStartUnchecked","content":{"notices":[
              {"check":"PreFlushSkipped","message":"Started on the machine, so the pre-shot flush didn’t run"}]}}],
              "commands":[]}""",
        )
        assertTrue(
            vm.ui.value.userMessages.any { "pre-shot flush" in it },
            "${vm.ui.value.userMessages}",
        )
    }

    @Test
    fun `a failed start tells the user why`() {
        val (vm, _) = build()
        deliver(
            vm,
            """{"events":[{"type":"ShotStartEnded","content":{"end":{"kind":"NotEntered"},
              "message":"The machine didn’t start the shot — start it on the machine"}}],"commands":[]}""",
        )
        assertTrue(vm.ui.value.userMessages.any { "start it on the machine" in it })
        assertFalse(vm.ui.value.shotStarting)
    }

    @Test
    fun `f16_8 the shell runs no start sequence or purge of its own`() {
        val sources = MainSources.kotlinFiles()
        val own = sources.filter { (_, text) ->
            listOf("ShotStartSequence", "AutoPurge", "pendingBrew", "PRESHOT_FLUSH_TIMEOUT_MS").any { it in text }
        }.map { it.first }
        assertEquals(emptyList(), own)
        val starters = sources.filter { (_, text) -> "bridge.startShot(" in text }.map { it.first }
        assertEquals(listOf("coffee/crema/ui/MainViewModel.kt"), starters)
    }
}
