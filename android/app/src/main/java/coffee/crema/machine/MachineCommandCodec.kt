package coffee.crema.machine

/**
 * The relay's wire vocabulary for [MachineCommand]: today's `Frame.Control`
 * method names and argument strings, byte for byte (the golden fixtures in
 * `proxy-golden/v3/control/` pin them). [encode] is exhaustive over
 * [MachineCommand.Relayable], so a new relayable command without a wire form
 * doesn't compile; [MachineCommand.LocalOnly] has none.
 *
 * One wire form stands for two commands until the protocol can say more:
 * `machineState IDLE` is both [MachineCommand.Stop] and [MachineCommand.Wake].
 * It decodes as [MachineCommand.Stop], which applies the same write.
 */
object MachineCommandCodec {

    /** A command's `Frame.Control` method and args. */
    data class Wire(val method: String, val args: String)

    /** What a primary makes of a relayed control. */
    sealed interface Decoded {
        data class Valid(val command: MachineCommand.Relayable) : Decoded
        data class Invalid(val reason: String) : Decoded
    }

    fun encode(cmd: MachineCommand.Relayable): Wire = when (cmd) {
        MachineCommand.StartShot -> Wire(START_SHOT, "")
        is MachineCommand.StateRequest -> Wire(MACHINE_STATE, cmd.request.name)
        MachineCommand.CancelMaintenance -> Wire(CANCEL_MAINTENANCE, "")
        MachineCommand.TareScale -> Wire(TARE_SCALE, "")
        MachineCommand.TakeOver -> Wire(HANDOFF, "")
        is MachineCommand.SetActiveProfile -> Wire(SET_ACTIVE_PROFILE, cmd.id)
        // No bean travels as an empty argument.
        is MachineCommand.SetActiveBean -> Wire(SET_ACTIVE_BEAN, cmd.id.orEmpty())
        is MachineCommand.SetStopOnWeight -> Wire(SET_STOP_ON_WEIGHT, cmd.on.toString())
        is MachineCommand.SetAutoTare -> Wire(SET_AUTO_TARE, cmd.on.toString())
        // Float.toString is locale-independent.
        is MachineCommand.SetQc -> Wire(qcMethod(cmd.field), cmd.value.toString())
        // `dose,yield,temp,preinf`, preinf empty = none.
        is MachineCommand.AdjustBrew ->
            Wire(QUICK_ADJUST_BREW, "${cmd.dose},${cmd.yieldOut},${cmd.brewTemp},${cmd.preinf ?: ""}")
        MachineCommand.ResetBrew -> Wire(RESET_BREW, "")
    }

    /** Parse a relayed control and check its arguments. Nothing here applies it. */
    fun decode(method: String, args: String): Decoded {
        val command = parse(method, args) ?: return Decoded.Invalid(parseRefusal(method, args))
        return command.refusal()?.let { Decoded.Invalid(it) } ?: Decoded.Valid(command)
    }

    /** Every method a primary accepts. */
    val METHODS: Set<String> by lazy {
        setOf(
            START_SHOT, MACHINE_STATE, CANCEL_MAINTENANCE, TARE_SCALE, HANDOFF, SET_ACTIVE_PROFILE,
            SET_ACTIVE_BEAN, SET_STOP_ON_WEIGHT, SET_AUTO_TARE, QUICK_ADJUST_BREW, RESET_BREW,
        ) + QcField.entries.map(::qcMethod)
    }

    /** The machine states a `machineState` control may carry. */
    val RELAYABLE_STATES: Set<String> get() = stateRequests.keys

    private val stateRequests: Map<String, MachineCommand.Relayable> by lazy {
        buildMap {
            // Wake shares Stop's IDLE and is left out: IDLE decodes as Stop.
            for (cmd in listOf(
                MachineCommand.Stop, MachineCommand.Sleep, MachineCommand.StartEspresso, MachineCommand.StartSteam,
                MachineCommand.StartHotWater, MachineCommand.StartFlush, MachineCommand.StartSteamRinse,
                MachineCommand.StartDescale, MachineCommand.StartClean,
            )) put(cmd.request.name, cmd)
        }
    }

    private val qcByMethod: Map<String, QcField> by lazy { QcField.entries.associateBy(::qcMethod) }

    private fun parse(method: String, args: String): MachineCommand.Relayable? {
        qcByMethod[method]?.let { field -> return args.toFloatOrNull()?.let { MachineCommand.SetQc(field, it) } }
        return when (method) {
            START_SHOT -> noArgs(args, MachineCommand.StartShot)
            CANCEL_MAINTENANCE -> noArgs(args, MachineCommand.CancelMaintenance)
            TARE_SCALE -> noArgs(args, MachineCommand.TareScale)
            HANDOFF -> noArgs(args, MachineCommand.TakeOver)
            RESET_BREW -> noArgs(args, MachineCommand.ResetBrew)
            MACHINE_STATE -> stateRequests[args]
            SET_ACTIVE_PROFILE -> MachineCommand.SetActiveProfile(args)
            SET_ACTIVE_BEAN -> MachineCommand.SetActiveBean(args.ifEmpty { null })
            SET_STOP_ON_WEIGHT -> flag(args)?.let { MachineCommand.SetStopOnWeight(it) }
            SET_AUTO_TARE -> flag(args)?.let { MachineCommand.SetAutoTare(it) }
            QUICK_ADJUST_BREW -> brew(args)
            else -> null
        }
    }

    /** Why [parse] found nothing to run. */
    private fun parseRefusal(method: String, args: String): String = when {
        method in qcByMethod -> "$method needs a number"
        method == MACHINE_STATE -> "machine state not relayable: ${args.take(32)}"
        method == SET_STOP_ON_WEIGHT || method == SET_AUTO_TARE -> "$method needs true or false"
        method == QUICK_ADJUST_BREW -> "quickAdjustBrew needs dose,yield,temp[,preinf]"
        method in METHODS -> "$method takes no arguments"
        else -> "unknown relayed control: ${method.take(32)}"
    }

    private fun noArgs(args: String, cmd: MachineCommand.Relayable) = cmd.takeIf { args.isEmpty() }

    private fun flag(args: String): Boolean? = when (args) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /** `dose,yield,temp[,preinf]`, preinf empty or absent = none. */
    private fun brew(args: String): MachineCommand.AdjustBrew? {
        val p = args.split(",")
        if (p.size !in 3..4) return null
        val dose = p[0].toDoubleOrNull() ?: return null
        val yieldOut = p[1].toDoubleOrNull() ?: return null
        val brewTemp = p[2].toDoubleOrNull() ?: return null
        val preinfText = p.getOrNull(3)?.takeIf { it.isNotEmpty() }
        val preinf = preinfText?.let { it.toDoubleOrNull() ?: return null }
        return MachineCommand.AdjustBrew(dose, yieldOut, brewTemp, preinf)
    }

    private fun qcMethod(field: QcField): String = when (field) {
        QcField.STEAM_TIME -> "setQcSteamTime"
        QcField.STEAM_FLOW -> "setQcSteamFlow"
        QcField.STEAM_TEMP -> "setQcSteamTemp"
        QcField.HOT_WATER_TEMP -> "setQcHotWaterTemp"
        QcField.HOT_WATER_VOLUME -> "setQcHotWaterVolume"
        QcField.FLUSH_TIME -> "setQcFlushTime"
        QcField.FLUSH_TEMP -> "setQcFlushTemp"
        QcField.GRIND -> "setQcGrind"
    }

    private const val START_SHOT = "startShot"
    private const val MACHINE_STATE = "machineState"
    private const val CANCEL_MAINTENANCE = "cancelMaintenance"
    private const val TARE_SCALE = "tareScale"
    private const val HANDOFF = "handoff"
    private const val SET_ACTIVE_PROFILE = "setActiveProfile"
    private const val SET_ACTIVE_BEAN = "setActiveBean"
    private const val SET_STOP_ON_WEIGHT = "setStopOnWeight"
    private const val SET_AUTO_TARE = "setAutoTare"
    private const val QUICK_ADJUST_BREW = "quickAdjustBrew"
    private const val RESET_BREW = "resetBrew"
}
