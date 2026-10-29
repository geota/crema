package coffee.crema.machine

import coffee.crema.ble.proxy.PROXY_PROTOCOL_VERSION
import coffee.crema.core.MachineRequest
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The wire codec: every command round-trips, and a primary accepts only the
 *  verbs, machine states and in-range finite arguments the Android UI sends. */
class MachineCommandCodecTest {

    /** One instance of every concrete command. A new command without a sample
     *  fails [every command has a sample]. */
    private val samples: List<MachineCommand> = listOf(
        MachineCommand.SetActiveProfile("builtin:classic"),
        MachineCommand.SetActiveBean("bean-1"),
        MachineCommand.SetActiveBean(null),
        MachineCommand.SetStopOnWeight(true),
        MachineCommand.SetAutoTare(false),
        MachineCommand.AdjustBrew(18.0, 36.0, 93.0, 8.0),
        MachineCommand.AdjustBrew(18.0, 36.0, 93.0, null),
    ) + QcField.entries.map { MachineCommand.SetQc(it, inRange(it)) } +
        MachineCommand::class.leaves().mapNotNull { it.objectInstance as MachineCommand? }

    private fun inRange(field: QcField): Float = when (field) {
        QcField.STEAM_TIME -> 30f
        QcField.STEAM_FLOW -> 1.2f
        QcField.STEAM_TEMP -> 148f
        QcField.HOT_WATER_TEMP -> 85f
        QcField.HOT_WATER_VOLUME -> 250f
        QcField.FLUSH_TIME -> 5f
        QcField.FLUSH_TEMP -> 92f
        QcField.GRIND -> 14.5f
    }

    private fun ok(method: String, args: String = "") =
        assertIs<MachineCommandCodec.Decoded.Valid>(MachineCommandCodec.decode(method, args), "$method($args) should be accepted")

    private fun refused(method: String, args: String = "") =
        assertIs<MachineCommandCodec.Decoded.Invalid>(MachineCommandCodec.decode(method, args), "$method($args) should be refused")

    @Test
    fun `every command has a sample`() {
        assertEquals(MachineCommand::class.leaves(), samples.map { it::class }.toSet())
    }

    @Test
    fun `every relayable command round-trips through its wire form`() {
        for (cmd in samples.filterIsInstance<MachineCommand.Relayable>()) {
            val wire = MachineCommandCodec.encode(cmd)
            val back = assertIs<MachineCommandCodec.Decoded.Valid>(MachineCommandCodec.decode(wire.method, wire.args), "$cmd").command
            // Wake shares Stop's IDLE on this wire; the primary applies the same write.
            assertEquals(if (cmd == MachineCommand.Wake) MachineCommand.Stop else cmd, back, "$cmd via $wire")
        }
    }

    @Test
    fun `local-only commands have no wire form`() {
        val localOnly = samples.filterIsInstance<MachineCommand.LocalOnly>()
        assertEquals(listOf<MachineCommand>(MachineCommand.RawEspresso), localOnly)
        assertTrue(localOnly.none { it is MachineCommand.Relayable })
    }

    @Test
    fun `machine states are distinct intents, and Espresso only starts a shot`() {
        assertEquals(MachineCommand.StartShot, ok("machineState", "ESPRESSO").command)
        assertEquals(MachineCommand.Stop, ok("machineState", "IDLE").command)
        assertEquals(MachineCommand.Sleep, ok("machineState", "SLEEP").command)
        assertEquals(MachineCommand.StartSteam, ok("machineState", "STEAM").command)
        assertEquals(MachineCommandCodec.Wire("machineState", "IDLE"), MachineCommandCodec.encode(MachineCommand.Wake))
        assertEquals(MachineCommandCodec.Wire("startShot", ""), MachineCommandCodec.encode(MachineCommand.StartShot))
    }

    @Test
    fun `only the machine states the UI sends are relayable`() {
        val sent = listOf("SLEEP", "IDLE", "ESPRESSO", "STEAM", "HOT_WATER", "FLUSH", "STEAM_RINSE", "DESCALE", "CLEAN")
        sent.forEach { ok("machineState", it) }
        assertEquals(sent.toSet(), MachineCommandCodec.RELAYABLE_STATES)
        MachineRequest.entries.map { it.name }.filterNot { it in sent }.forEach { refused("machineState", it) }
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
        refused("quickAdjustBrew", "18,36,93,abc")
    }

    @Test
    fun `flags, ids and no-argument verbs`() {
        ok("setStopOnWeight", "true"); ok("setAutoTare", "false")
        refused("setStopOnWeight", "yes"); refused("setAutoTare", "")
        ok("setActiveProfile", "builtin:classic"); refused("setActiveProfile", "")
        refused("setActiveProfile", "x".repeat(200)); refused("setActiveProfile", "a\nb")
        assertEquals(MachineCommand.SetActiveBean(null), ok("setActiveBean", "").command)
        ok("setActiveBean", "bean-1"); refused("setActiveBean", "a\u0000b")
        for (v in listOf("startShot", "cancelMaintenance", "tareScale", "resetBrew")) {
            ok(v); refused(v, "x")
        }
    }

    /** AC-4: take-over is retired on a v3 hub. `handoff` is no live method;
     *  it is refused with the retired reason, with or without arguments
     *  (checked before parsing, so `handoff x` isn't "unknown"). */
    @Test
    fun `handoff is a retired refusal on protocol v3`() {
        val reason = MachineCommandCodec.RETIRED_METHODS.getValue("handoff")
        assertTrue("hub mode" in reason, reason)
        assertEquals(MachineCommandCodec.Decoded.Invalid(reason), MachineCommandCodec.decode("handoff", ""))
        assertEquals(MachineCommandCodec.Decoded.Invalid(reason), MachineCommandCodec.decode("handoff", "x"))
        assertTrue("handoff" !in MachineCommandCodec.METHODS)
        assertEquals(3, PROXY_PROTOCOL_VERSION, "retiring a verb doesn't bump the protocol")
    }

    @Test
    fun `unknown verbs are refused with a reason`() {
        val reason = refused("writeMmr", "0x803828").reason
        assertTrue("unknown relayed control" in reason, reason)
        refused("flashFirmware")
    }

    private fun KClass<*>.leaves(): Set<KClass<*>> =
        if (isSealed) sealedSubclasses.flatMap { it.leaves() }.toSet() else setOf(this)
}
