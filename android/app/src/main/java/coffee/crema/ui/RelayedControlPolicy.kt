package coffee.crema.ui

import coffee.crema.core.MachineRequest

/**
 * What a secondary may relay to this primary, checked before anything runs:
 * the verbs the VM's relay router knows, and for each one the arguments the
 * Android UI can actually send. Numbers must be finite and inside the range
 * the matching control offers; a machine-state request must be one the UI
 * sends. Anything else is refused with a reason (a `ControlErr` to the peer).
 */
internal object RelayedControlPolicy {

    /** The machine states the Android UI requests (Sleep/Wake/Stop, the shot,
     *  steam, hot water, flush, steam rinse, descale, clean). */
    val RELAYABLE_MACHINE_STATES: Set<String> = setOf(
        MachineRequest.SLEEP,
        MachineRequest.IDLE,
        MachineRequest.ESPRESSO,
        MachineRequest.STEAM,
        MachineRequest.HOT_WATER,
        MachineRequest.FLUSH,
        MachineRequest.STEAM_RINSE,
        MachineRequest.DESCALE,
        MachineRequest.CLEAN,
    ).map { it.name }.toSet()

    private val noArgs = setOf("startShot", "cancelMaintenance", "tareScale", "resetBrew", "handoff")

    /** Inclusive float ranges per Quick-Controls verb (the steppers' bounds). */
    private val floatRanges: Map<String, ClosedFloatingPointRange<Double>> = mapOf(
        "setQcSteamTime" to QcSteam.MIN_TIME_S..QcSteam.MAX_TIME_S,
        "setQcSteamFlow" to QcSteam.MIN_FLOW_ML_S..QcSteam.MAX_FLOW_ML_S,
        "setQcHotWaterTemp" to QcBounds.WATER_TEMP_MIN_C..QcBounds.WATER_TEMP_MAX_C,
        "setQcHotWaterVolume" to QcBounds.WATER_VOL_MIN_ML..QcBounds.WATER_VOL_MAX_ML,
        // The phone's flush stepper starts at 0 (it shares the pre-infusion minimum).
        "setQcFlushTime" to 0.0..QcBounds.FLUSH_TIME_MAX_S,
        "setQcFlushTemp" to QcBounds.FLUSH_TEMP_MIN_C..QcBounds.FLUSH_TEMP_MAX_C,
        // Grind also comes from a shot's or bag's recorded setting, not just the stepper.
        "setQcGrind" to 0.0..MAX_GRIND,
    )

    /** Every verb the relay accepts. */
    val VERBS: Set<String> = noArgs + floatRanges.keys + setOf(
        "machineState", "setQcSteamTemp", "setActiveProfile", "setActiveBean",
        "setStopOnWeight", "setAutoTare", "quickAdjustBrew",
    )

    /** Why [method] with [args] must be refused, or null to run it. */
    fun refusal(method: String, args: String): String? {
        floatRanges[method]?.let { range -> return numberRefusal(method, args, range) }
        return when (method) {
            in noArgs -> if (args.isEmpty()) null else "$method takes no arguments"
            "machineState" -> if (args in RELAYABLE_MACHINE_STATES) null else "machine state not relayable: ${args.take(32)}"
            // 0 turns the steam heater off; otherwise the stepper's range.
            "setQcSteamTemp" ->
                if (args.toDoubleOrNull() == 0.0) null else numberRefusal(method, args, QcSteam.MIN_TEMP_C..QcSteam.MAX_TEMP_C)
            "setActiveProfile" -> idRefusal(args, allowEmpty = false)
            "setActiveBean" -> idRefusal(args, allowEmpty = true)
            "setStopOnWeight", "setAutoTare" -> if (args == "true" || args == "false") null else "$method needs true or false"
            "quickAdjustBrew" -> brewRefusal(args)
            else -> "unknown relayed control: ${method.take(32)}"
        }
    }

    private fun numberRefusal(method: String, args: String, range: ClosedFloatingPointRange<Double>): String? {
        val v = args.toDoubleOrNull() ?: return "$method needs a number"
        return if (v.isFinite() && v in range) null else "$method out of range: ${args.take(16)}"
    }

    private fun idRefusal(id: String, allowEmpty: Boolean): String? = when {
        id.isEmpty() -> if (allowEmpty) null else "an id is required"
        id.length > MAX_ID_LENGTH || id.any { it.isISOControl() } -> "malformed id"
        else -> null
    }

    /** `dose,yield,temp[,preinf]`, preinf empty = none. Profiles set the
     *  defaults, so the ranges are physical limits rather than stepper bounds. */
    private fun brewRefusal(args: String): String? {
        val p = args.split(",")
        if (p.size !in 3..4) return "quickAdjustBrew needs dose,yield,temp[,preinf]"
        val limits = listOf(0.0..MAX_DOSE_G, 0.0..MAX_YIELD_G, 0.0..MAX_BREW_TEMP_C)
        for (i in 0..2) {
            val v = p[i].toDoubleOrNull() ?: return "quickAdjustBrew needs numbers"
            if (!v.isFinite() || v !in limits[i]) return "quickAdjustBrew out of range"
        }
        val preinf = p.getOrNull(3)?.takeIf { it.isNotEmpty() } ?: return null
        val v = preinf.toDoubleOrNull() ?: return "quickAdjustBrew needs numbers"
        return if (v.isFinite() && v in 0.0..MAX_PREINF_S) null else "quickAdjustBrew out of range"
    }

    private const val MAX_GRIND = 1_000.0
    private const val MAX_ID_LENGTH = 128
    private const val MAX_DOSE_G = 100.0
    private const val MAX_YIELD_G = 2_000.0
    private const val MAX_BREW_TEMP_C = 110.0
    private const val MAX_PREINF_S = 300.0
}
