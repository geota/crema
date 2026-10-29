package coffee.crema.machine

import coffee.crema.ble.proxy.ControlReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A mirror's [MachineCommands]: every command goes to the primary, by its
 * relay policy (see [MachineCommand]).
 *
 * - [MachineCommand.Control] is forwarded and nothing changes here.
 * - [MachineCommand.Config] is shown at once, then forwarded. A refusal, or
 *   any failure to get an answer, undoes it.
 * - [MachineCommand.LocalOnly] is refused here.
 */
class RelayMachine(
    /** `ProxyTransport.sendControl`. */
    private val send: suspend (method: String, args: String) -> ControlReply,
    /** The VM's optimistic half of a config change; returns its undo. */
    private val showConfig: (MachineCommand.Config) -> (() -> Unit),
    /** Where the round trips run (the VM's scope: Main). */
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
    private val log: (String) -> Unit,
) : MachineCommands {

    override fun execute(cmd: MachineCommand, onOutcome: (CommandOutcome) -> Unit) {
        when (cmd) {
            is MachineCommand.LocalOnly -> {
                notify(NOT_ON_MIRROR)
                onOutcome(CommandOutcome.Refused(NOT_ON_MIRROR))
            }
            is MachineCommand.Control -> {
                scope.launch {
                    val outcome = forward(cmd)
                    report(cmd, outcome, config = false)
                    onOutcome(outcome)
                }
            }
            is MachineCommand.Config -> {
                val undo = showConfig(cmd)
                scope.launch {
                    val outcome = forward(cmd)
                    if (outcome is CommandOutcome.Refused) undo()
                    report(cmd, outcome, config = true)
                    onOutcome(outcome)
                }
            }
        }
    }

    /** Send [cmd] to the primary and map its answer. Reports nothing. */
    suspend fun forward(cmd: MachineCommand.Relayable): CommandOutcome {
        val wire = MachineCommandCodec.encode(cmd)
        return when (val reply = send(wire.method, wire.args)) {
            ControlReply.Ok -> CommandOutcome.Accepted
            is ControlReply.Err -> CommandOutcome.Refused(reply.reason)
            is ControlReply.Failed -> {
                log("Relay ${wire.method}: no answer (${reply.detail})")
                CommandOutcome.Refused(UNREACHABLE)
            }
        }
    }

    private fun report(cmd: MachineCommand.Relayable, outcome: CommandOutcome, config: Boolean) {
        val method = MachineCommandCodec.encode(cmd).method
        when (outcome) {
            CommandOutcome.Accepted -> Unit
            is CommandOutcome.Refused -> {
                log("Relay $method failed: ${outcome.reason}")
                notify(refusedMessage(outcome.reason, config))
            }
        }
    }

    companion object {
        /** The reason for a command the primary didn't answer. */
        const val UNREACHABLE = "Couldn’t reach the machine"
        const val NOT_ON_MIRROR = "Not available while mirroring — use the device holding the machine"

        /** What the user sees for a refusal: the view-only wording for a
         *  mirror-only peer, else the primary's own reason. */
        fun refusedMessage(reason: String, config: Boolean): String = when {
            reason.contains("not authorized") -> "View-only — the host hasn’t allowed this device to control the machine"
            config -> "$reason — change reverted"
            reason == UNREACHABLE -> "$reason — change not applied"
            else -> reason
        }
    }
}
