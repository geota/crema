package coffee.crema.machine

import coffee.crema.settings.OnRemote
import coffee.crema.settings.Setting
import coffee.crema.settings.setting
import coffee.crema.ui.leaves
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a remote sends to its hub (spec §2, §7.3, AC-15). */
class RemotePolicyTest {

    /** One instance of every relayable command, every Quick Controls field included. */
    private val all: List<MachineCommand.Relayable> = listOf(
        MachineCommand.StartShot, MachineCommand.Stop, MachineCommand.Wake, MachineCommand.Sleep,
        MachineCommand.StartSteam, MachineCommand.StartHotWater, MachineCommand.StartFlush, MachineCommand.StartSteamRinse,
        MachineCommand.StartDescale, MachineCommand.StartClean, MachineCommand.CancelMaintenance, MachineCommand.TareScale,
        MachineCommand.SetActiveProfile("p"), MachineCommand.SetActiveBean("b"), MachineCommand.SetStopOnWeight(true),
        MachineCommand.SetAutoTare(true), MachineCommand.AdjustBrew(18.0, 36.0, 93.0, null), MachineCommand.ResetBrew,
    ) + QcField.entries.map { MachineCommand.SetQc(it, 1f) }

    @Test
    fun `sentByRemotes is the spec's table`() {
        val notSent = all.filterNot { it.sentByRemotes() }.toSet()
        assertEquals(
            setOf(QcField.STEAM_TIME, QcField.STEAM_FLOW, QcField.STEAM_TEMP, QcField.HOT_WATER_TEMP, QcField.HOT_WATER_VOLUME, QcField.FLUSH_TIME, QcField.FLUSH_TEMP)
                .map { MachineCommand.SetQc(it, 1f) }.toSet<MachineCommand.Relayable>(),
            notSent,
            "only the seven Quick Controls machine fields stay on the remote",
        )
    }

    @Test
    fun `no relayable command exists beyond the table`() {
        assertEquals(MachineCommand.Relayable::class.leaves(), all.map { it::class }.toSet())
    }

    /** The registry's RELAYED settings are exactly those a sent Config command edits. */
    @Test
    fun `relayed settings are the ones a sent config command edits`() {
        val sent = all.filterIsInstance<MachineCommand.Config>().filter { it.sentByRemotes() }.map { it.setting() }.toSet()
        assertEquals(Setting.entries.filter { it.onRemote == OnRemote.RELAYED }.toSet(), sent)
    }
}
