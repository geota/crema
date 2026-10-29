package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.settings.OnRemote
import coffee.crema.settings.Setting
import coffee.crema.settings.SettingGroup
import coffee.crema.ui.RemoteFixture.Companion.P
import coffee.crema.ui.RemoteFixture.Companion.savedPrefs
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.valueParameters
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Edits on a remote (spec §7.3): what is relayed, what is read-only, and
 *  what a view-only remote does. A real remote against a real hub. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RemoteEditFlowTest {

    private val store = ViewModelStore()
    private val fixture = RemoteFixture(store)

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
        fixture.kill()
    }

    /** A sample argument for each read-only setter, chosen to pass its own
     *  validation, so a refusal can only come from the remote gate. */
    private val samples: Map<String, List<Any?>> = mapOf(
        "setSteamEco" to listOf(true), "setSteamTwoTap" to listOf(true), "setFanThreshold" to listOf(58f),
        "setWaterRefillPointMm" to listOf(8f), "setHeaterVoltage" to listOf(230), "setFlowMultiplier" to listOf(1.1f),
        "setLineFrequency" to listOf(50f), "setCupWarmerTemp" to listOf(60), "setScaleVolume" to listOf(3),
        "setScaleStandbyMinutes" to listOf(10), "setScaleFlowSmoothing" to listOf(true), "setScaleAntiMistouch" to listOf(true),
        "setScaleMode" to listOf(1), "setScaleAutoStop" to listOf(1), "setMaxShotDuration" to listOf(41f),
        "setVolumeStopWithScale" to listOf(true), "setRequireScale" to listOf(true), "setPreFlush" to listOf(true),
        "setSteamPurge" to listOf(true), "setIdleSleepMin" to listOf(60), "setGrinderModel" to listOf("Niche"),
        "setQcSteamTemp" to listOf(150f), "setQcSteamTime" to listOf(30f), "setQcSteamFlow" to listOf(1.6f),
        "setQcHotWaterTemp" to listOf(85f), "setQcHotWaterVolume" to listOf(200f), "setQcFlushTime" to listOf(6f),
        "setQcFlushTemp" to listOf(93f),
    )

    private fun call(vm: MainViewModel, signature: String) {
        val name = signature.substringAfter('.').substringBefore('(')
        val types = signature.substringAfter('(').substringBefore(')').split(", ").filter { it.isNotEmpty() }
        val f = (MainViewModel::class as KClass<*>).declaredMemberFunctions.single { fn ->
            fn.name == name && fn.valueParameters.map { p -> (p.type.classifier as KClass<*>).simpleName + if (p.type.isMarkedNullable) "?" else "" } == types
        }
        f.call(vm, *samples.getValue(name).toTypedArray())
    }

    /** AC-23: on a remote, every READ_ONLY machine and session setter (from
     *  the registry) changes neither prefs.json nor the screen, sends no
     *  control, and says where to change it. */
    @Test
    fun `read-only settings`() {
        val vm = fixture.start()
        fixture.push()
        val readOnly = Setting.entries.filter { it.group != SettingGroup.C_PERSONAL && it.onRemote == OnRemote.READ_ONLY }
        val prefsBefore = abOf(savedPrefs())
        for (setting in readOnly) for (setter in setting.setters) {
            val shown = vm.ui.value
            val messages = vm.ui.value.userMessages.size
            call(vm, setter)
            VmHarness.idle()
            assertEquals(settingsShown(shown), settingsShown(vm.ui.value), "$setter changed the screen")
            assertTrue(vm.ui.value.userMessages.drop(messages).any { it == "Change on Kitchen tablet" }, "$setter: ${vm.ui.value.userMessages.drop(messages)}")
        }
        vm.tareScale() // a control that is sent, so the wire has caught up
        VmHarness.idleUntil("the tare") { fixture.handled.isNotEmpty() }
        assertEquals(listOf("tareScale" to ""), fixture.handled.toList(), "no control for a read-only setting")
        assertEquals(prefsBefore, abOf(savedPrefs()))
        assertEquals(abOf(P), abOf(savedPrefs()))
    }

    /** Every machine and session setting as the screen shows it. */
    private fun settingsShown(ui: MainUiState): Map<String, Any?> {
        val props = MainUiState::class.memberProperties.associateBy { it.name }
        return Setting.entries.filter { it.group != SettingGroup.C_PERSONAL }.mapNotNull { it.uiField }.plus("lineFreqHz")
            .distinct().associateWith { props.getValue(it).get(ui) }
    }

    /** AC-23: a view-only remote sends nothing at all, Stop included. */
    @Test
    fun `view-only sends nothing`() {
        fixture.viewOnly = true
        val vm = fixture.start()
        VmHarness.idleUntil("view-only") { vm.ui.value.mirrorViewOnly }
        vm.stopShot()
        vm.setStopOnWeight(true)
        vm.setQcGrind(14f)
        vm.startShot()
        VmHarness.idle()
        Thread.sleep(200)
        VmHarness.idle()
        assertTrue(fixture.endpoint.received.none { "\"type\":\"control\"" in it }, "nothing on the wire")
        assertTrue(vm.ui.value.userMessages.count { it.startsWith("View-only") } >= 4, "${vm.ui.value.userMessages}")
    }
}
