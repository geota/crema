package coffee.crema.machine

import coffee.crema.core.MachineRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [MachineActions] that records what ran: the verb bodies a test's
 * [LocalMachine] or [RelayMachine] reached.
 */
internal class RecordingMachineActions : MachineActions {
    /** Verb bodies that ran, in order. */
    val applied = CopyOnWriteArrayList<String>()

    /** Config shown (the optimistic half) and undone. */
    val shown = CopyOnWriteArrayList<MachineCommand.Config>()
    val undone = CopyOnWriteArrayList<MachineCommand.Config>()

    var peerActivity = 0

    private fun ran(what: String) {
        applied += what
    }

    override fun applyStartShot() = ran("startShot")
    override fun applyMachineState(request: MachineRequest) = ran("machineState($request)")
    override fun applyCancelMaintenance() = ran("cancelMaintenance")
    override fun applyTareScale() = ran("tareScale")

    override fun showConfig(cmd: MachineCommand.Config): () -> Unit {
        shown += cmd
        return { undone += cmd }
    }

    override fun commitConfig(cmd: MachineCommand.Config) = ran("commit($cmd)")
    override fun notePeerActivity() {
        peerActivity++
    }
}
