package coffee.crema.machine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * A primary's entry for a secondary's relayed control: decode, check the
 * arguments, then run it on one ordered lane on [lane] (Main, where the
 * primary's own UI runs its verbs), and answer with a typed outcome.
 *
 * Order: the relay hub hands each session's controls over one at a time, in
 * the order they arrived, and [order] admits them to the lane one at a time,
 * first come first served, so two commands from one peer apply in the order
 * it sent them and two peers never interleave. A Stop takes its turn like the
 * rest: the lane only runs short verb bodies, and the stop write itself still
 * preempts at the BLE layer.
 *
 * Only an accepted command is announced ("<who> started a shot").
 */
class RelayedCommandDispatcher(
    private val machine: LocalMachine,
    /** This primary's name, for a refusal the secondary shows. */
    private val hostName: () -> String,
    /** Whether the machine reads idle or asleep: words a relayed IDLE as a
     *  wake rather than a stop. */
    private val machineIdle: () -> Boolean,
    /** Tell the other mirrors and this primary's user who drove the machine. */
    private val announce: (text: String, origin: CommandOrigin) -> Unit,
    private val log: (String) -> Unit,
    private val lane: CoroutineContext = Dispatchers.Main.immediate,
) {
    private val order = Mutex()

    suspend fun dispatch(method: String, args: String, origin: CommandOrigin): CommandOutcome {
        val cmd = when (val decoded = MachineCommandCodec.decode(method, args)) {
            is MachineCommandCodec.Decoded.Invalid -> return refused(method, decoded.reason)
            is MachineCommandCodec.Decoded.Valid -> decoded.command
        }
        return order.withLock { withContext(lane) { run(method, cmd, origin) } }
    }

    private fun run(method: String, cmd: MachineCommand.Relayable, origin: CommandOrigin): CommandOutcome {
        machine.notePeerActivity()
        // A selection from the peer's own library that this one lacks would
        // leave the primary with no profile (or a dangling bean): refuse it
        // and change nothing here.
        machine.unknownReference(cmd, hostName())?.let { return refused(method, it) }
        // Worded against the state before the command runs.
        val phrase = phrase(cmd)
        val outcome = runCatching { machine.run(cmd, origin) }
            .getOrElse { CommandOutcome.Refused(it.message ?: "control failed") }
        when (outcome) {
            CommandOutcome.Accepted ->
                if (phrase != null && origin.id != null) announce("${origin.name ?: "A mirror"} $phrase", origin)
            is CommandOutcome.Refused -> log("Relayed $method refused: ${outcome.reason}")
        }
        return outcome
    }

    private fun refused(method: String, reason: String): CommandOutcome.Refused {
        log("Relayed $method refused: $reason")
        return CommandOutcome.Refused(reason)
    }

    /** The predicate of "<who> …" for a command worth announcing, else null. */
    private fun phrase(cmd: MachineCommand.Relayable): String? = when (cmd) {
        MachineCommand.StartShot, MachineCommand.StartEspresso -> "started a shot"
        MachineCommand.Stop -> if (machineIdle()) "woke the machine" else "stopped the machine"
        MachineCommand.Wake -> "woke the machine"
        MachineCommand.Sleep -> "put the machine to sleep"
        MachineCommand.StartSteam, MachineCommand.StartSteamRinse -> "started steam"
        MachineCommand.StartHotWater -> "started hot water"
        MachineCommand.StartFlush -> "started a flush"
        MachineCommand.TareScale -> "tared the scale"
        MachineCommand.StartDescale, MachineCommand.StartClean, MachineCommand.CancelMaintenance,
        MachineCommand.TakeOver -> null
        is MachineCommand.Config -> null
    }
}
