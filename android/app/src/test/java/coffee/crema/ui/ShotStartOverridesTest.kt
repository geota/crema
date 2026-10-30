package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.Command
import coffee.crema.core.CoreOutput
import coffee.crema.core.CremaBridge
import coffee.crema.core.NotificationSource
import coffee.crema.core.WriteTarget
import coffee.crema.settings.AppPrefs
import coffee.crema.ui.RemoteFixture.Companion.writePrefs
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * F6 validation, Android: the shot start uploads the dialled profile (a start
 * that drops the Quick-Controls override fails here), and deleting or hiding
 * the active profile clears the override like any other profile change.
 * A real [MainViewModel] under Robolectric with the host-built core.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ShotStartOverridesTest {
    private val store = ViewModelStore()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    private fun build(): MainViewModel {
        writePrefs(AppPrefs(stopOnWeight = false))
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(vm)
        VmHarness.awaitStartupLoaded(vm)
        return vm
    }

    /** The VM's core, found by type rather than by field name. */
    private fun bridgeOf(vm: MainViewModel): CremaBridge {
        val field = MainViewModel::class.java.declaredFields.single { it.type == CremaBridge::class.java }
        field.isAccessible = true
        return field.get(vm) as CremaBridge
    }

    private fun modelReply(model: Int): ByteArray = ByteArray(20).also {
        it[1] = 0x80.toByte(); it[2] = 0x00; it[3] = 0x0C
        it[4] = model.toByte()
    }

    /** A built-in whose dial can rise 2 °C above its own brew temperature. */
    private fun pickProfile(vm: MainViewModel): BrewDial {
        for (p in vm.ui.value.profiles.filter { it.segments.size in 2..20 }) {
            vm.setActiveProfile(p.id)
            VmHarness.idle()
            val dial = vm.brewDial() ?: continue
            if (dial.max >= dial.anchor + 2) return dial
        }
        error("no suitable built-in profile")
    }

    @Test
    fun `the shot start uploads the dialled brew temperature`() {
        val vm = build()
        val dial = pickProfile(vm)
        val active = assertNotNull(vm.ui.value.activeProfile())
        vm.quickAdjustBrew(active.dose.toDouble(), active.yieldOut.toDouble(), dial.anchor + 2, null)
        VmHarness.idle()
        assertEquals(dial.anchor + 2, vm.ui.value.brewParams?.brewTemp)
        val bridge = bridgeOf(vm)
        vm.startShot()
        // The start's upload waits for the machine model (no DE1 here); the
        // model reply releases it with every frame.
        VmHarness.idleUntil("the start's upload") { bridge.profileUploadInProgress() }
        val out = json.decodeFromString(
            CoreOutput.serializer(),
            bridge.onNotification(NotificationSource.DE1_MMR_READ, modelReply(1), 0uL),
        )
        val first = out.commands.filterIsInstance<Command.WriteCharacteristic>()
            .first { it.content.target == WriteTarget.De1ProfileFrame && it.content.data.first().toInt() == 0 }
        // ShotFrame byte 3: the first step's temperature in half degrees.
        assertEquals(Math.round((dial.anchor + 2) * 2).toInt(), first.content.data[3].toInt())
        assertNotEquals(Math.round(dial.anchor * 2).toInt(), first.content.data[3].toInt())
    }

    @Test
    fun `deleting or hiding the active profile clears the override`() {
        val vm = build()
        val dial = pickProfile(vm)
        val active = assertNotNull(vm.ui.value.activeProfile())
        vm.quickAdjustBrew(active.dose.toDouble(), active.yieldOut.toDouble(), dial.anchor + 2, null)
        VmHarness.idle()
        assertNotNull(vm.ui.value.brewParams)
        vm.archiveBuiltinProfile(active.id)
        VmHarness.idle()
        assertNotEquals(active.id, vm.ui.value.activeProfileId, "another profile is active")
        assertNull(vm.ui.value.brewParams, "the override does not carry over to it")
    }
}
