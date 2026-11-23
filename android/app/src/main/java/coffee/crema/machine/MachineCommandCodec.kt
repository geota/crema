package coffee.crema.machine

import coffee.crema.core.RelayCommand
import coffee.crema.core.RelayDecoded
import coffee.crema.core.RelayQcField
import coffee.crema.core.relayCommandMethods
import coffee.crema.core.relayCommandRefusal
import coffee.crema.core.relayDecodeCommand
import coffee.crema.core.relayEncodeCommand
import coffee.crema.core.relayMachineStates

/**
 * The relay's wire vocabulary for [MachineCommand]: `Frame.Control` method
 * names and argument strings, and the argument checks a primary runs on a
 * relayed control. Both are the core's (`de1_relay_app`, PLAN 65), byte for
 * byte what this shell sent before (the shared golden fixtures in
 * `core/de1-relay-app/tests/golden/v4/control/` pin them); this object maps
 * [MachineCommand] to the core's command and back. [encode] is exhaustive
 * over [MachineCommand.Relayable], so a new relayable command without a
 * mapping doesn't compile; [MachineCommand.LocalOnly] has no wire form, and
 * neither has a Quick Controls machine setting a remote doesn't send
 * ([sentByRemotes]).
 *
 * Two wire forms stand for more than one command until the protocol can say
 * more:
 * - `machineState IDLE` is both [MachineCommand.Stop] and [MachineCommand.Wake].
 *   It decodes as [MachineCommand.Stop], which applies the same write.
 * - `machineState ESPRESSO` (an older build's debug button) decodes as
 *   [MachineCommand.StartShot], so a relayed shot always goes through the start
 *   sequence. Nothing encodes to it any more.
 *
 * Strings cross to the core as UTF-8, which has no lone surrogates: one
 * (only a peer's escaped JSON can hold one) crosses as U+FFFD, the same
 * length in UTF-16 units, so every length and character check is unchanged.
 */
object MachineCommandCodec {

    /** A command's `Frame.Control` method and args. */
    data class Wire(val method: String, val args: String)

    /** What a primary makes of a relayed control. */
    sealed interface Decoded {
        data class Valid(val command: MachineCommand.Relayable) : Decoded
        data class Invalid(val reason: String) : Decoded
    }

    /** [cmd]'s wire form, or null for a machine setting that isn't relayed
     *  (a remote keeps it read-only, so it never reaches the wire). */
    fun encode(cmd: MachineCommand.Relayable): Wire? =
        relayEncodeCommand(cmd.toCore())?.let { Wire(it.method, it.args) }

    /**
     * What an accepted control's reply carries (`Frame.ControlOk.result`): a
     * peer's bag pick answers with the core's selection as JSON (F6), so the
     * remote that picked shows its message, notices and Undo. Null otherwise.
     */
    fun encodeReply(outcome: CommandOutcome): String? = when (outcome) {
        is CommandOutcome.BagPicked -> coffee.crema.ui.coreJson.encodeToString(coffee.crema.core.BagSelection.serializer(), outcome.selection)
        CommandOutcome.Accepted, is CommandOutcome.Refused, is CommandOutcome.Unknown -> null
    }

    /**
     * What the hub's accepted reply to [cmd] means here: a bag pick with its
     * selection ([encodeReply]), else [CommandOutcome.Accepted]. A result that
     * doesn't decode, or comes with another command, is still accepted.
     */
    fun decodeReply(cmd: MachineCommand.Relayable, result: String?): CommandOutcome {
        if (cmd !is MachineCommand.SetActiveBean || result.isNullOrEmpty()) return CommandOutcome.Accepted
        return runCatching { coffee.crema.ui.coreJson.decodeFromString(coffee.crema.core.BagSelection.serializer(), result) }
            .map { CommandOutcome.BagPicked(it) as CommandOutcome }
            .getOrDefault(CommandOutcome.Accepted)
    }

    /** Parse a relayed control and check its arguments (the core's
     *  `decode_command`). Nothing here applies it, and the library lookup
     *  ([LocalMachine.unknownReference]) comes after. */
    fun decode(method: String, args: String): Decoded =
        when (val decoded = relayDecodeCommand(method.wellFormed(), args.wellFormed())) {
            is RelayDecoded.Valid -> Decoded.Valid(decoded.command.toMachineCommand())
            is RelayDecoded.Invalid -> Decoded.Invalid(decoded.reason)
        }

    /** Why [cmd]'s arguments can't be run, or null: [MachineCommand.refusal]. */
    internal fun refusal(cmd: MachineCommand.Relayable): String? = relayCommandRefusal(cmd.toCore())

    /** Every method a primary accepts. */
    val METHODS: Set<String> by lazy { relayCommandMethods().toSet() }

    /** The machine states a `machineState` control may carry. */
    val RELAYABLE_STATES: Set<String> by lazy { relayMachineStates().toSet() }

    private fun MachineCommand.Relayable.toCore(): RelayCommand = when (this) {
        MachineCommand.StartShot -> RelayCommand.StartShot
        MachineCommand.Stop -> RelayCommand.Stop
        MachineCommand.Wake -> RelayCommand.Wake
        MachineCommand.Sleep -> RelayCommand.Sleep
        MachineCommand.StartSteam -> RelayCommand.StartSteam
        MachineCommand.StartHotWater -> RelayCommand.StartHotWater
        MachineCommand.StartFlush -> RelayCommand.StartFlush
        MachineCommand.StartSteamRinse -> RelayCommand.StartSteamRinse
        MachineCommand.StartDescale -> RelayCommand.StartDescale
        MachineCommand.StartClean -> RelayCommand.StartClean
        MachineCommand.CancelMaintenance -> RelayCommand.CancelMaintenance
        MachineCommand.TareScale -> RelayCommand.TareScale
        is MachineCommand.SetActiveProfile -> RelayCommand.SetActiveProfile(id.wellFormed())
        is MachineCommand.SetActiveBean -> RelayCommand.SetActiveBean(id?.wellFormed())
        is MachineCommand.SetActiveGrinder -> RelayCommand.SetActiveGrinder(id?.wellFormed(), name?.wellFormed())
        is MachineCommand.SetStopOnWeight -> RelayCommand.SetStopOnWeight(on)
        is MachineCommand.SetAutoTare -> RelayCommand.SetAutoTare(on)
        is MachineCommand.SetQc -> RelayCommand.SetQc(field.toCore(), value)
        is MachineCommand.SetGrind -> RelayCommand.SetGrind(grind.wellFormed())
        is MachineCommand.AdjustBrew -> RelayCommand.AdjustBrew(dose, yieldOut, brewTemp, preinf)
        MachineCommand.ResetBrew -> RelayCommand.ResetBrew
        is MachineCommand.QuickSave -> RelayCommand.QuickSave(copyName?.wellFormed(), asNew)
    }

    private fun RelayCommand.toMachineCommand(): MachineCommand.Relayable = when (this) {
        RelayCommand.StartShot -> MachineCommand.StartShot
        RelayCommand.Stop -> MachineCommand.Stop
        RelayCommand.Wake -> MachineCommand.Wake
        RelayCommand.Sleep -> MachineCommand.Sleep
        RelayCommand.StartSteam -> MachineCommand.StartSteam
        RelayCommand.StartHotWater -> MachineCommand.StartHotWater
        RelayCommand.StartFlush -> MachineCommand.StartFlush
        RelayCommand.StartSteamRinse -> MachineCommand.StartSteamRinse
        RelayCommand.StartDescale -> MachineCommand.StartDescale
        RelayCommand.StartClean -> MachineCommand.StartClean
        RelayCommand.CancelMaintenance -> MachineCommand.CancelMaintenance
        RelayCommand.TareScale -> MachineCommand.TareScale
        is RelayCommand.SetActiveProfile -> MachineCommand.SetActiveProfile(id)
        is RelayCommand.SetActiveBean -> MachineCommand.SetActiveBean(id)
        is RelayCommand.SetActiveGrinder -> MachineCommand.SetActiveGrinder(id, name)
        is RelayCommand.SetStopOnWeight -> MachineCommand.SetStopOnWeight(on)
        is RelayCommand.SetAutoTare -> MachineCommand.SetAutoTare(on)
        is RelayCommand.SetQc -> MachineCommand.SetQc(field.toQcField(), value)
        is RelayCommand.SetGrind -> MachineCommand.SetGrind(grind)
        is RelayCommand.AdjustBrew -> MachineCommand.AdjustBrew(dose, yieldOut, brewTemp, preinf)
        RelayCommand.ResetBrew -> MachineCommand.ResetBrew
        is RelayCommand.QuickSave -> MachineCommand.QuickSave(copyName, asNew)
    }

    private fun QcField.toCore(): RelayQcField = when (this) {
        QcField.STEAM_TIME -> RelayQcField.STEAM_TIME
        QcField.STEAM_FLOW -> RelayQcField.STEAM_FLOW
        QcField.STEAM_TEMP -> RelayQcField.STEAM_TEMP
        QcField.HOT_WATER_TEMP -> RelayQcField.HOT_WATER_TEMP
        QcField.HOT_WATER_VOLUME -> RelayQcField.HOT_WATER_VOLUME
        QcField.FLUSH_TIME -> RelayQcField.FLUSH_TIME
        QcField.FLUSH_TEMP -> RelayQcField.FLUSH_TEMP
    }

    private fun RelayQcField.toQcField(): QcField = when (this) {
        RelayQcField.STEAM_TIME -> QcField.STEAM_TIME
        RelayQcField.STEAM_FLOW -> QcField.STEAM_FLOW
        RelayQcField.STEAM_TEMP -> QcField.STEAM_TEMP
        RelayQcField.HOT_WATER_TEMP -> QcField.HOT_WATER_TEMP
        RelayQcField.HOT_WATER_VOLUME -> QcField.HOT_WATER_VOLUME
        RelayQcField.FLUSH_TIME -> QcField.FLUSH_TIME
        RelayQcField.FLUSH_TEMP -> QcField.FLUSH_TEMP
    }

    /** This text with each lone surrogate as U+FFFD, so it can cross as UTF-8. */
    private fun String.wellFormed(): String {
        if (none { it.isSurrogate() }) return this
        val out = StringBuilder(length)
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
                out.append(c).append(this[i + 1])
                i += 2
                continue
            }
            out.append(if (c.isSurrogate()) '\uFFFD' else c)
            i++
        }
        return out.toString()
    }
}
