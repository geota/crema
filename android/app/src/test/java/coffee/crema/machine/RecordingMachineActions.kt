package coffee.crema.machine

import coffee.crema.core.MachineRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [MachineActions] that records what ran, and on which thread: the verb bodies
 * a test's [LocalMachine] or [RelayMachine] reached. [startShot] is what a
 * start answers; [profiles] / [beans] are this device's library.
 */
internal class RecordingMachineActions(
    var startShot: CommandOutcome = CommandOutcome.Accepted,
    val profiles: MutableSet<String> = mutableSetOf("p-1"),
    val beans: MutableSet<String> = mutableSetOf("bean-1"),
) : MachineActions {
    /** Verb bodies that ran, in order. */
    val applied = CopyOnWriteArrayList<String>()

    /** The thread each one ran on. */
    val threads = CopyOnWriteArrayList<String>()

    /** Config shown (the optimistic half) and undone. */
    val shown = CopyOnWriteArrayList<MachineCommand.Config>()
    val undone = CopyOnWriteArrayList<MachineCommand.Config>()

    var peerActivity = 0

    private fun ran(what: String) {
        applied += what
        // Coroutine debug mode appends " @coroutine#N" to the thread name.
        threads += Thread.currentThread().name.substringBefore(" @")
    }

    override fun applyStartShot(): CommandOutcome = startShot.also { ran("startShot") }
    override fun applyMachineState(request: MachineRequest) = ran("machineState($request)")
    override fun applyCancelMaintenance() = ran("cancelMaintenance")
    override fun applyTareScale() = ran("tareScale")

    override fun showConfig(cmd: MachineCommand.Config): () -> Unit {
        shown += cmd
        return { undone += cmd }
    }

    override fun commitConfig(cmd: MachineCommand.Config) = ran("commit($cmd)")
    override fun hasProfile(id: String) = id in profiles
    override fun hasBean(id: String) = id in beans
    override fun notePeerActivity() {
        peerActivity++
    }
}
