package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.Command
import coffee.crema.core.CoreOutput
import coffee.crema.core.CremaBridge
import coffee.crema.core.NotificationSource
import coffee.crema.core.SteamHotWaterTargets
import coffee.crema.core.WriteTarget
import coffee.crema.settings.AppPrefs
import coffee.crema.ui.RemoteFixture.Companion.writePrefs
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * The steam / hot-water packet (cuuid_0B) through the core (core/shell fixes
 * AC-F5.2, AC-F5.3): Android passes the four Quick Controls targets and the
 * core does the read-modify-write, so the fields Android doesn't own reach the
 * DE1 unchanged (the hot-water timeout is Crema's now: the dose's) and the
 * group target is the active profile's temperature.
 * Replaces the shell-side `buildSteamHotWaterSettings` tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SteamHotWaterTargetsTest {

    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    /** The machine's packet: a set flag bit, steam 160 °C / 30 s, water
     *  90 °C / 200 ml / 45 s, espresso 220 ml, group 88.5 °C (U16P8). */
    private val machinePacket = byteArrayOf(0x40, 160.toByte(), 30, 90, 200.toByte(), 45, 220.toByte(), 88, 128.toByte())

    private fun feedMachinePacket(bridge: CremaBridge) {
        bridge.onNotification(NotificationSource.DE1_SHOT_SETTINGS, machinePacket, 0uL)
    }

    /** AC-F5.2 and AC-F5.3: a Quick Controls edit goes through the core,
     *  which keeps the machine's fields and sets the group target to the
     *  active profile's temperature. */
    @Test
    fun `a Quick Controls steam edit keeps the machine's fields and follows the profile`() {
        writePrefs(AppPrefs(qcSteamTempC = 150f, qcSteamTimeS = 20f, qcHotWaterTempC = 80f, qcHotWaterVolumeMl = 150f))
        val vm = VmHarness.build(store)
        VmHarness.awaitPrefsLoaded(vm)
        VmHarness.awaitStartupLoaded(vm)
        val profileTemp = assertNotNull(vm.brewDial()).anchor.toFloat()
        assertNotEquals(88.5f, profileTemp, "the profile's temperature differs from the machine's")
        feedMachinePacket(VmHarness.bridge(vm))

        vm.setQcSteamTemp(148f)
        // The core projects what it wrote back as a ShotSettingsRead.
        VmHarness.idleUntil("the write's read-back") { vm.ui.value.de1ShotSettings?.steam_temp == 148f }
        val s = vm.ui.value.de1ShotSettings!!
        assertEquals(20f, s.steam_timeout, "QC steam time")
        assertEquals(80f, s.hot_water_temp, "QC water temp")
        assertEquals(150f, s.hot_water_volume, "QC water volume")
        assertEquals(60f, s.hot_water_timeout, "Crema's backstop for a volume dose")
        assertEquals(220f, s.espresso_volume, "machine's espresso volume kept")
        assertEquals(profileTemp, s.group_temp, "the active profile's temperature")
    }

    @Test
    fun `the packet keeps the machine's flag byte`() {
        val bridge = CremaBridge()
        // The seed read, Idle, then the on-ready sweep settles the link.
        feedMachinePacket(bridge)
        bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(2, 0), 0uL)
        bridge.connectSweep("{}", 0uL)
        val targets = SteamHotWaterTargets(148f, 20f, 80f, volumeHotWaterDose(150f))
        val raw = bridge.setSteamHotwaterTargets(coreJson.encodeToString(SteamHotWaterTargets.serializer(), targets))
        val packet = coreJson.decodeFromString(CoreOutput.serializer(), raw).commands
            .filterIsInstance<Command.WriteCharacteristic>()
            .single { it.content.target == WriteTarget.De1ShotSettings }
            .content.data.map { it.toInt() }
        assertEquals(listOf(0x40, 148, 20, 80, 150, 60, 220, 88, 128), packet)
    }
}
