package coffee.crema.machine

import coffee.crema.core.HotWaterDose
import coffee.crema.core.HotWaterMode
import coffee.crema.core.MachineRequest
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
        MachineCommand.SetHotWaterDose(HotWaterDose(HotWaterMode.Ratio, 30f, false, 150f, 3f)),
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
        for (cmd in samples.filterIsInstance<MachineCommand.Relayable>().filter { it.sentByRemotes() }) {
            val wire = assertNotNull(MachineCommandCodec.encode(cmd), "$cmd")
            val back = assertIs<MachineCommandCodec.Decoded.Valid>(MachineCommandCodec.decode(wire.method, wire.args), "$cmd").command
            // Wake shares Stop's IDLE on this wire; the primary applies the same write.
            assertEquals(if (cmd == MachineCommand.Wake) MachineCommand.Stop else cmd, back, "$cmd via $wire")
        }
    }

    @Test
    fun `local-only commands have no wire form`() {
        val localOnly = samples.filterIsInstance<MachineCommand.LocalOnly>()
        assertEquals(setOf(MachineCommand.RawEspresso::class, MachineCommand.SetHotWaterDose::class), localOnly.map { it::class }.toSet())
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
    fun `the relayed grind must be finite and in range`() {
        ok("setQcGrind", "14.5")
        for (bad in listOf("NaN", "Infinity", "-Infinity", "1e9", "-1", "", "abc")) refused("setQcGrind", bad)
    }

    /** AC-43 (relay v4 R4): the steam, hot-water and flush values are machine
     *  settings a remote doesn't send. They have no wire form, and a hub
     *  refuses their old methods as unknown controls. */
    @Test
    fun `machine settings have no wire form and their methods are unknown`() {
        for (field in QcField.entries) {
            assertEquals(field.sentByRemotes(), MachineCommandCodec.encode(MachineCommand.SetQc(field, inRange(field))) != null, "$field")
        }
        val gone = listOf(
            "setQcSteamTime", "setQcSteamFlow", "setQcSteamTemp", "setQcHotWaterTemp",
            "setQcHotWaterVolume", "setQcFlushTime", "setQcFlushTemp",
        )
        for (method in gone) {
            assertEquals(MachineCommandCodec.Decoded.Invalid("unknown relayed control: $method"), MachineCommandCodec.decode(method, "30.0"))
            assertTrue(method !in MachineCommandCodec.METHODS, method)
        }
    }

    /** The hub's own Quick Controls edits still get the range checks. */
    @Test
    fun `machine-setting numbers stay range-checked for the hub's own edits`() {
        assertEquals(null, MachineCommand.SetQc(QcField.STEAM_TEMP, 0f).refusal()) // heater off
        assertEquals(null, MachineCommand.SetQc(QcField.STEAM_TEMP, 148f).refusal())
        assertNotNull(MachineCommand.SetQc(QcField.STEAM_TEMP, 120f).refusal()) // the band the firmware snaps to off
        assertNotNull(MachineCommand.SetQc(QcField.STEAM_TIME, 61f).refusal())
        assertNotNull(MachineCommand.SetQc(QcField.HOT_WATER_VOLUME, 501f).refusal())
        assertNotNull(MachineCommand.SetQc(QcField.FLUSH_TEMP, Float.NaN).refusal())
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

    /** AC-41 (relay v4 R2): `handoff` has no retired entry any more; it is an
     *  unknown method like any other, with or without arguments. */
    @Test
    fun `handoff is an unknown method`() {
        val unknown = MachineCommandCodec.Decoded.Invalid("unknown relayed control: handoff")
        assertEquals(unknown, MachineCommandCodec.decode("handoff", ""))
        assertEquals(unknown, MachineCommandCodec.decode("handoff", "x"))
        assertTrue("handoff" !in MachineCommandCodec.METHODS)
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
