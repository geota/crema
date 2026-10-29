package coffee.crema.ui

import coffee.crema.core.CoreOutput
import coffee.crema.core.CremaBridge
import coffee.crema.core.Event
import coffee.crema.core.EventMaintenanceProgressInner
import coffee.crema.core.MachineRequest
import coffee.crema.core.MachineState
import coffee.crema.core.MaintenanceFailure
import coffee.crema.core.MaintenancePhase
import coffee.crema.core.NotificationSource
import kotlin.test.Test
import kotlin.test.assertEquals

/** The running-maintenance row copy (web `WaterSection` parity). */
class MaintenanceRunTextTest {
    private fun run(
        phase: MaintenancePhase,
        state: MachineState = MachineState.Descale,
        step: Int = 0,
        progress: Float = 0f,
        remaining: Int = 0,
        cycle: Int = 0,
        cold: Boolean = false,
    ) = EventMaintenanceProgressInner(
        state = state,
        phase = phase,
        step_index = step.toUByte(),
        step_count = (if (state == MachineState.Descale) 5 else 0).toUByte(),
        progress = progress,
        seconds_remaining = remaining.toUInt(),
        cycle = cycle.toUByte(),
        cold_workaround = cold,
    )

    @Test
    fun descaleReadsTheScheduleCountdown() {
        val (title, sub) = maintenanceRunText(run(MaintenancePhase.Running, step = 4, progress = 0.42f, remaining = 420, cycle = 1))
        assertEquals("Descale", title)
        assertEquals("42% · Step 4 of 5 · 7 min 0 s left", sub)
    }

    @Test
    fun aSecondPassIsNamed() {
        val (_, sub) = maintenanceRunText(run(MaintenancePhase.Running, step = 2, progress = 0.1f, remaining = 650, cycle = 2))
        assertEquals("10% · Step 2 of 5 · 10 min 50 s left · pass 2", sub)
    }

    @Test
    fun aHeldColdRequestSaysWhy() {
        val (title, sub) = maintenanceRunText(run(MaintenancePhase.WaitingForPreheat, state = MachineState.Clean, cold = true))
        assertEquals("Clean cycle", title)
        assertEquals("Loading the cold-start profile and waiting for heating to stop (older firmware workaround)…", sub)
    }

    @Test
    fun cleanRunningHasNoCountdown() {
        assertEquals("Running…", maintenanceRunText(run(MaintenancePhase.Running, state = MachineState.Clean)).second)
        assertEquals("Starting…", maintenanceRunText(run(MaintenancePhase.Requested)).second)
    }

    @Test
    fun aFailedColdCycleIsTerminalAndSaysWhy() {
        // Round-2 verification V3 #4: the cold profile never loaded, so core
        // did not send the request; the row clears and the user is told.
        assertEquals(true, isMaintenanceTerminal(MaintenancePhase.Failed))
        assertEquals(true, isMaintenanceTerminal(MaintenancePhase.Finished))
        assertEquals(true, isMaintenanceTerminal(MaintenancePhase.Cancelled))
        assertEquals(false, isMaintenanceTerminal(MaintenancePhase.WaitingForPreheat))
        assertEquals(
            "Descale didn't start: the cold-start profile older DE1 firmware needs could not be loaded. Try again.",
            maintenanceFailedText(MachineState.Descale, null),
        )
        assertEquals(
            maintenanceFailedText(MachineState.Descale, null),
            maintenanceFailedText(MachineState.Descale, MaintenanceFailure.ColdProfileNotLoaded),
        )
        assertEquals(true, maintenanceFailedText(MachineState.Clean, null).startsWith("Clean cycle didn't start"))
    }

    /** An MMR read reply carrying [value] for the register at [address]. */
    private fun mmrReply(address: Int, value: Int): ByteArray = ByteArray(20).also {
        it[1] = (address shr 16).toByte(); it[2] = (address shr 8).toByte(); it[3] = address.toByte()
        it[4] = value.toByte(); it[5] = (value shr 8).toByte()
    }

    private val brewProfile = """
        {"id":"","title":"Brew","notes":"","steps":[
          {"name":"a","pump":"pressure","target":9.0,"temperature_c":92.0,"temp_sensor":"coffee",
           "transition":"fast","duration_seconds":20.0,"exit":null,"volume_limit_ml":0,"limiter":null,"weight":null}],
         "preinfuse_step_count":1,"minimum_pressure":0.0,"maximum_flow":6.0,"max_total_volume_ml":0,
         "target_weight":0.0,"dose":0.0,"author":"","beverage_type":"espresso","tank_temperature":0.0,"version":"2"}
    """.trimIndent()

    @Test
    fun aHeldCycleReplacedByTheUsersUploadSaysSo() {
        // Round-3 clean check C1 P3-1, through the host core and the VM's decode:
        // firmware 1355, machine heating, Descale held behind the cold profile;
        // the user then picks a profile. The notice must not blame a load failure.
        val bridge = CremaBridge()
        bridge.onNotification(NotificationSource.DE1_MMR_READ, mmrReply(0x80000C, 1), 0uL) // model
        bridge.onNotification(NotificationSource.DE1_MMR_READ, mmrReply(0x800010, 1355), 0uL) // build
        bridge.onNotification(NotificationSource.DE1_MMR_READ, mmrReply(0x80381C, 3), 0uL) // GHC
        bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(2, 1), 1_000uL) // Idle / Heating
        val held = coreJson.decodeFromString(CoreOutput.serializer(), bridge.requestMachineState(MachineRequest.DESCALE))
        assertEquals(
            MaintenancePhase.WaitingForPreheat,
            held.events.filterIsInstance<Event.MaintenanceProgress>().single().content.phase,
        )
        val raw = bridge.uploadProfile(brewProfile, 1_500uL)
        val failed = coreJson.decodeFromString(CoreOutput.serializer(), raw).events
            .filterIsInstance<Event.MaintenanceProgress>().single().content
        assertEquals(MaintenancePhase.Failed, failed.phase)
        assertEquals(MaintenanceFailure.ProfileReplaced, failed.failure)
        assertEquals(
            "Descale didn't start: another profile was loaded before the cycle could start. Start it again when you're ready.",
            maintenanceFailedText(failed.state, failed.failure),
        )
    }
}
