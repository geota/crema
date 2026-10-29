package coffee.crema.machine

import coffee.crema.core.MachineRequest

/** How a [MachineCommand] ended. */
sealed interface CommandOutcome {
    data object Accepted : CommandOutcome

    /** Not applied, with a reason for the person who asked. */
    data class Refused(val reason: String) : CommandOutcome
}

/** The peer a relayed command came from, as its session identified itself. */
data class CommandOrigin(val id: String?, val name: String?)

/**
 * Where the VM's verbs send their commands: [LocalMachine] on a device that
 * holds (or could hold) the machine, [RelayMachine] on a mirror. The proxy
 * controller picks one with the transport on each mode switch.
 */
interface MachineCommands {
    /**
     * Run [cmd] for this device's user, who is told about a refusal. Locally the
     * command runs before this returns, on the caller's thread; on a mirror the
     * outcome arrives after the round trip. [onOutcome] gets it either way.
     */
    fun execute(cmd: MachineCommand, onOutcome: (CommandOutcome) -> Unit = {})
}

/**
 * What this device does for each command: the VM's verb bodies. [LocalMachine]
 * calls them for this device's UI and for a peer's relayed commands alike.
 * Called on the Main thread.
 */
interface MachineActions {
    /** Start a shot, or say why not (one already starting or running, no
     *  profile, the step cap, a required scale missing). */
    fun applyStartShot(): CommandOutcome

    /** A user's machine-state request: cancels a pending shot start, then writes. */
    fun applyMachineState(request: MachineRequest)

    fun applyCancelMaintenance()

    fun applyTareScale()

    /** The visible half of a config change: update the UI state now and return
     *  what undoes it. A mirror shows its relayed edits with this alone. */
    fun showConfig(cmd: MachineCommand.Config): () -> Unit

    /** The rest of a config change on the device that owns the config: persist
     *  it, write the machine, push it to mirrors. */
    fun commitConfig(cmd: MachineCommand.Config)

    fun hasProfile(id: String): Boolean

    fun hasBean(id: String): Boolean

    /** A peer used Crema to drive this machine: presence at the machine. */
    fun notePeerActivity()
}

/**
 * Runs commands on this device. [run] is the one dispatch table: the VM's verbs
 * and a primary's relayed commands both end there.
 */
class LocalMachine(
    private val actions: MachineActions,
    /** Grant a mirror's take-over (the primary steps down), or refuse it. */
    private val grantTakeOver: (takerId: String?) -> CommandOutcome,
    private val notify: (String) -> Unit,
) : MachineCommands {

    override fun execute(cmd: MachineCommand, onOutcome: (CommandOutcome) -> Unit) {
        val outcome = run(cmd, origin = null)
        if (outcome is CommandOutcome.Refused) notify(outcome.reason)
        onOutcome(outcome)
    }

    /** Run [cmd] for this device's UI ([origin] null) or for a peer. */
    fun run(cmd: MachineCommand, origin: CommandOrigin?): CommandOutcome = when (cmd) {
        MachineCommand.StartShot -> actions.applyStartShot()
        is MachineCommand.StateRequest -> accepted { actions.applyMachineState(cmd.request) }
        MachineCommand.CancelMaintenance -> accepted { actions.applyCancelMaintenance() }
        MachineCommand.TareScale -> accepted { actions.applyTareScale() }
        MachineCommand.TakeOver ->
            if (origin == null) CommandOutcome.Refused("Not mirroring another device") else grantTakeOver(origin.id)
        is MachineCommand.Config -> accepted {
            actions.showConfig(cmd)
            actions.commitConfig(cmd)
        }
    }

    /** Why a peer's [cmd] names something this device doesn't have (a profile
     *  or bean from the peer's own library), or null. [hostName] words it. */
    fun unknownReference(cmd: MachineCommand.Relayable, hostName: String): String? = when (cmd) {
        is MachineCommand.SetActiveProfile ->
            if (actions.hasProfile(cmd.id)) null else "That profile isn’t on $hostName"
        is MachineCommand.SetActiveBean ->
            cmd.id?.takeUnless { actions.hasBean(it) }?.let { "That bean isn’t on $hostName" }
        else -> null
    }

    /** A peer's command is about to run here. */
    fun notePeerActivity() = actions.notePeerActivity()

    private inline fun accepted(block: () -> Unit): CommandOutcome {
        block()
        return CommandOutcome.Accepted
    }
}
