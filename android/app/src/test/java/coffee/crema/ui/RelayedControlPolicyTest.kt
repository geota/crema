package coffee.crema.ui

import coffee.crema.core.MachineRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** What a primary accepts from a secondary: the verbs, the machine states the
 *  Android UI sends, and in-range finite arguments. */
class RelayedControlPolicyTest {

    private fun ok(method: String, args: String = "") =
        assertNull(RelayedControlPolicy.refusal(method, args), "$method($args) should be accepted")

    private fun refused(method: String, args: String = "") =
        assertNotNull(RelayedControlPolicy.refusal(method, args), "$method($args) should be refused")

    @Test
    fun `only the machine states the UI sends are relayable`() {
        val sent = listOf("SLEEP", "IDLE", "ESPRESSO", "STEAM", "HOT_WATER", "FLUSH", "STEAM_RINSE", "DESCALE", "CLEAN")
        sent.forEach { ok("machineState", it) }
        val notSent = MachineRequest.entries.map { it.name }.filterNot { it in sent }
        assertEquals(notSent.toSet(), MachineRequest.entries.map { it.name }.toSet() - RelayedControlPolicy.RELAYABLE_MACHINE_STATES)
        notSent.forEach { refused("machineState", it) }
        refused("machineState", "")
        refused("machineState", "espresso")
    }

    @Test
    fun `Quick Controls numbers must be finite and in range`() {
        ok("setQcSteamTime", "30.0")
        ok("setQcSteamFlow", "1.2")
        ok("setQcSteamTemp", "148.0")
        ok("setQcSteamTemp", "0.0") // heater off
        ok("setQcHotWaterTemp", "85.0")
        ok("setQcHotWaterVolume", "250.0")
        ok("setQcFlushTime", "0.0")
        ok("setQcFlushTemp", "92.0")
        ok("setQcGrind", "14.5")
        for (bad in listOf("NaN", "Infinity", "-Infinity", "1e9", "-1", "", "abc")) {
            refused("setQcSteamTime", bad)
            refused("setQcSteamTemp", bad)
            refused("setQcHotWaterVolume", bad)
            refused("setQcFlushTemp", bad)
            refused("setQcGrind", bad)
        }
        refused("setQcSteamTemp", "120.0") // the band the firmware snaps to off
        refused("setQcSteamTime", "61.0")
        refused("setQcHotWaterVolume", "501.0")
    }

    @Test
    fun `the brew override takes four finite numbers in physical range`() {
        ok("quickAdjustBrew", "18.0,36.0,93.0,")
        ok("quickAdjustBrew", "18.0,36.0,93.0,8.0")
        ok("quickAdjustBrew", "18.0,36.0,93.0")
        refused("quickAdjustBrew", "18.0,36.0")
        refused("quickAdjustBrew", "NaN,36.0,93.0,")
        refused("quickAdjustBrew", "18.0,36.0,Infinity,")
        refused("quickAdjustBrew", "18.0,36.0,93.0,-1")
        refused("quickAdjustBrew", "18.0,36.0,500.0,")
        refused("quickAdjustBrew", "18,36,93,8,1")
    }

    @Test
    fun `flags, ids and no-argument verbs`() {
        ok("setStopOnWeight", "true"); ok("setAutoTare", "false")
        refused("setStopOnWeight", "yes"); refused("setAutoTare", "")
        ok("setActiveProfile", "builtin:classic"); refused("setActiveProfile", "")
        refused("setActiveProfile", "x".repeat(200)); refused("setActiveProfile", "a\nb")
        ok("setActiveBean", ""); ok("setActiveBean", "bean-1")
        for (v in listOf("startShot", "cancelMaintenance", "tareScale", "resetBrew", "handoff")) {
            ok(v); refused(v, "x")
        }
    }

    @Test
    fun `unknown verbs are refused and every known verb has an accepted form`() {
        refused("writeMmr", "0x803828")
        refused("flashFirmware")
        val sample = mapOf(
            "machineState" to "IDLE", "setQcSteamTemp" to "148", "setActiveProfile" to "p", "setActiveBean" to "",
            "setStopOnWeight" to "true", "setAutoTare" to "true", "quickAdjustBrew" to "18,36,93,",
            "setQcSteamTime" to "30", "setQcSteamFlow" to "1", "setQcHotWaterTemp" to "80", "setQcHotWaterVolume" to "200",
            "setQcFlushTime" to "5", "setQcFlushTemp" to "90", "setQcGrind" to "10",
        )
        for (verb in RelayedControlPolicy.VERBS) ok(verb, sample[verb] ?: "")
    }
}
