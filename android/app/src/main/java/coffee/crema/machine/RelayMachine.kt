package coffee.crema.machine

import coffee.crema.ble.proxy.ControlReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * How a remote shows a relayed config edit until the hub answers: [settle]
 * gets every outcome (spec §7.3). The remote's mirror slice keeps the edit
 * pending; an accepted or refused edit makes way for the hub's value, and an
 * unknown one stays on screen as unconfirmed until the next snapshot.
 */
fun interface ConfigEcho {
    fun settle(outcome: CommandOutcome)

    /** Set when the edit can't be shown now (the remote is leaving its hub):
     *  it is refused with this reason and not sent. */
    val refusedReason: String? get() = null

    companion object {
        /** Shows nothing and settles nothing. */
        val NONE = ConfigEcho {}

        /** Refuses the edit with [reason]. */
        fun refused(reason: String): ConfigEcho = object : ConfigEcho {
            override fun settle(outcome: CommandOutcome) = Unit
            override val refusedReason: String = reason
        }
    }
}

/**
 * A mirror's [MachineCommands]: every command goes to the primary, by its
 * relay policy (see [MachineCommand]).
 *
 * - [MachineCommand.Control] is forwarded and nothing changes here.
 * - [MachineCommand.Config] is shown at once ([showConfig]), then forwarded,
 *   and its [ConfigEcho] settles with the outcome, whatever it is.
 * - [MachineCommand.LocalOnly] is refused here.
 *
 * Nothing is ever resent: an unknown outcome is reported as one.
 */
class RelayMachine(
    /** `ProxyTransport.sendControl`. */
    private val send: suspend (method: String, args: String) -> ControlReply,
    /** Show a config edit until the hub answers (the remote's mirror slice). */
    private val showConfig: (MachineCommand.Config) -> ConfigEcho,
    /** This mirror's view of the machine state (`MachineState.string`). */
    private val machineStateName: () -> String?,
    /** Where the round trips run (the VM's scope: Main). */
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
    private val log: (String) -> Unit,
    /** The hub admitted this remote view-only: it sends nothing at all. */
    private val viewOnly: () -> Boolean = { false },
    /** The hub's name, for "Change on <hub>". */
    private val hubName: () -> String = { "the hub" },
) : MachineCommands {

    override fun execute(cmd: MachineCommand, onOutcome: (CommandOutcome) -> Unit) {
        // A view-only remote refuses every command, Stop included, as the hub
        // would (spec §7.3, review 11).
        if (cmd is MachineCommand.Relayable && viewOnly()) return refuse(cmd, viewOnly(hubName()), onOutcome)
        when (cmd) {
            is MachineCommand.LocalOnly -> {
                notify(NOT_ON_MIRROR)
                onOutcome(CommandOutcome.Refused(NOT_ON_MIRROR))
            }
            is MachineCommand.Control -> {
                if (!cmd.sentByRemotes()) return refuse(cmd, "Use ${hubName()} for this", onOutcome)
                cmd.senderRefusal(machineStateName())?.let { reason ->
                    log("Not relaying $cmd: $reason")
                    notify(reason)
                    onOutcome(CommandOutcome.Refused(reason))
                    return
                }
                scope.launch {
                    val outcome = forward(cmd)
                    report(cmd, outcome, config = false)
                    onOutcome(outcome)
                }
            }
            is MachineCommand.Config -> {
                // A machine setting is read-only here: nothing shown, nothing sent.
                if (!cmd.sentByRemotes()) return refuse(cmd, "Change on ${hubName()}", onOutcome)
                val echo = showConfig(cmd)
                echo.refusedReason?.let { return refuse(cmd, it, onOutcome) }
                scope.launch {
                    val outcome = forward(cmd)
                    echo.settle(outcome)
                    report(cmd, outcome, config = true)
                    onOutcome(outcome)
                }
            }
        }
    }

    /** Refuse [cmd] here with [reason]: nothing is shown or sent. */
    private fun refuse(cmd: MachineCommand, reason: String, onOutcome: (CommandOutcome) -> Unit) {
        log("Not relaying $cmd: $reason")
        notify(reason)
        onOutcome(CommandOutcome.Refused(reason))
    }

    /** Send [cmd] to the primary and map its answer. Reports nothing. */
    suspend fun forward(cmd: MachineCommand.Relayable): CommandOutcome {
        // A machine setting has no wire form; execute() refuses it before this.
        val wire = MachineCommandCodec.encode(cmd) ?: return CommandOutcome.Refused("Change on ${hubName()}")
        return when (val reply = send(wire.method, wire.args)) {
            ControlReply.Ok -> CommandOutcome.Accepted
            is ControlReply.Err -> CommandOutcome.Refused(reply.reason)
            is ControlReply.NotSent -> {
                log("Relay ${wire.method} not sent: ${reply.detail}")
                CommandOutcome.Refused(UNREACHABLE)
            }
            is ControlReply.NoReply -> CommandOutcome.Unknown(reply.detail)
        }
    }

    private fun report(cmd: MachineCommand.Relayable, outcome: CommandOutcome, config: Boolean) {
        val method = MachineCommandCodec.encode(cmd)?.method ?: cmd.toString()
        when (outcome) {
            CommandOutcome.Accepted -> Unit
            is CommandOutcome.Refused -> {
                log("Relay $method failed: ${outcome.reason}")
                notify(refusedMessage(outcome.reason, config, hubName()))
            }
            is CommandOutcome.Unknown -> {
                log("Relay $method: no reply (${outcome.detail}); outcome unknown, not resent")
                notify(if (config) UNKNOWN_CONFIG else UNKNOWN_CONTROL)
            }
        }
    }

    companion object {
        /** The reason for a command that never reached the primary. */
        const val UNREACHABLE = "Couldn’t reach the machine"
        const val NOT_ON_MIRROR = "Not available on a remote — use the hub"
        /** What a view-only remote shows for any command (the same words as
         *  its rows' note, spec §7.6). */
        fun viewOnly(hub: String) = "View only — $hub hasn’t allowed changes from this device"
        const val UNKNOWN_CONTROL = "No reply from the machine — it may not have been applied"
        const val UNKNOWN_CONFIG = "No reply from the machine — the change may not have been applied"

        /** What the user sees for a refusal: the view-only wording for a
         *  mirror-only peer, else the primary's own reason. */
        fun refusedMessage(reason: String, config: Boolean, hub: String): String = when {
            reason.contains("not authorized") -> viewOnly(hub)
            config -> "$reason — change reverted"
            reason == UNREACHABLE -> "$reason — change not applied"
            else -> reason
        }
    }
}
