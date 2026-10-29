package coffee.crema.machine

import coffee.crema.core.MachineRequest
import coffee.crema.ui.QcBounds
import coffee.crema.ui.QcSteam

/**
 * A user's intent for the machine (DE1 + scale) or for the primary-owned
 * session config. The VM's public verbs build one and hand it to
 * [MachineCommands.execute]; a primary decodes a secondary's relayed one with
 * [MachineCommandCodec] and runs it through the same [LocalMachine], so both
 * paths end in the same verb body.
 *
 * The sub-interface is the relay policy, and a subtype must pick one:
 * - [Control]: machine control. A mirror forwards it and applies nothing itself.
 * - [Config]: the primary's single-owner session config. A mirror shows it at
 *   once, forwards it, and undoes it if the primary refuses it or it never got
 *   there. On an unknown outcome it keeps it until the next config push.
 * - [LocalOnly]: never relayed. A mirror refuses it.
 *
 * Only [Relayable] commands have a wire form. [refusal] is each command's
 * argument check, which a primary runs on whatever a peer sends.
 */
sealed interface MachineCommand {

    /** Why this command's arguments can't be run, or null to run it. */
    fun refusal(): String? = null

    /** A command with a wire form ([MachineCommandCodec]). */
    sealed interface Relayable : MachineCommand

    /** Machine control: a mirror forwards it, authority first. */
    sealed interface Control : Relayable

    /** Primary-owned session config: a mirror applies it optimistically. */
    sealed interface Config : Relayable

    /** Kept to the device holding the machine: a mirror refuses it. */
    sealed interface LocalOnly : MachineCommand

    /** A machine-state request the UI makes, and the DE1 state it asks for. */
    sealed interface StateRequest : Control {
        val request: MachineRequest
    }

    /** Start a shot through the owned start sequence (profile upload, guard,
     *  pre-flush). */
    data object StartShot : Control

    /** The debug panel's raw Espresso request: skips the start sequence. */
    data object StartEspresso : StateRequest {
        override val request get() = MachineRequest.ESPRESSO
    }

    /** End whatever the machine is doing (shot, steam, hot water, flush). */
    data object Stop : StateRequest {
        override val request get() = MachineRequest.IDLE
    }

    /** Wake a sleeping machine. The same DE1 request as [Stop]. */
    data object Wake : StateRequest {
        override val request get() = MachineRequest.IDLE
    }

    data object Sleep : StateRequest {
        override val request get() = MachineRequest.SLEEP
    }

    data object StartSteam : StateRequest {
        override val request get() = MachineRequest.STEAM
    }

    data object StartHotWater : StateRequest {
        override val request get() = MachineRequest.HOT_WATER
    }

    data object StartFlush : StateRequest {
        override val request get() = MachineRequest.FLUSH
    }

    data object StartSteamRinse : StateRequest {
        override val request get() = MachineRequest.STEAM_RINSE
    }

    /** Start descaling (Settings ▸ Water, after its confirm dialog). */
    data object StartDescale : StateRequest {
        override val request get() = MachineRequest.DESCALE
    }

    /** Start the cleaning cycle (Settings ▸ Water, after its confirm dialog). */
    data object StartClean : StateRequest {
        override val request get() = MachineRequest.CLEAN
    }

    /** Cancel the maintenance cycle the core is following. */
    data object CancelMaintenance : Control

    data object TareScale : Control

    /** A mirror asks the primary for the machine (the picker's "Take over").
     *  The primary grants it only from a positively idle machine. */
    data object TakeOver : Control

    /** Select the active profile. A primary refuses an id its library lacks. */
    data class SetActiveProfile(val id: String) : Config {
        override fun refusal(): String? = idRefusal(id)
    }

    /** Select the active bean, or none. A primary refuses an id its library lacks. */
    data class SetActiveBean(val id: String?) : Config {
        override fun refusal(): String? = id?.let(::idRefusal)
    }

    data class SetStopOnWeight(val on: Boolean) : Config

    data class SetAutoTare(val on: Boolean) : Config

    /** One Quick-Controls value, inside its stepper's range. */
    data class SetQc(val field: QcField, val value: Float) : Config {
        override fun refusal(): String? = field.refusal(value)
    }

    /** The live Quick-Controls brew override, baked into the next shot's upload.
     *  Profiles set the defaults, so the ranges are physical limits. */
    data class AdjustBrew(val dose: Double, val yieldOut: Double, val brewTemp: Double, val preinf: Double?) : Config {
        override fun refusal(): String? {
            val inRange = dose.within(0.0..MAX_DOSE_G) && yieldOut.within(0.0..MAX_YIELD_G) &&
                brewTemp.within(0.0..MAX_BREW_TEMP_C) && (preinf == null || preinf.within(0.0..MAX_PREINF_S))
            return if (inRange) null else "brew override out of range"
        }
    }

    /** Drop the brew override: the active profile's recipe applies again. */
    data object ResetBrew : Config
}

/** The Quick-Controls values a mirror may set, each with its stepper's bounds. */
enum class QcField(private val label: String, private val range: ClosedFloatingPointRange<Double>) {
    STEAM_TIME("steam time", QcSteam.MIN_TIME_S..QcSteam.MAX_TIME_S),
    STEAM_FLOW("steam flow", QcSteam.MIN_FLOW_ML_S..QcSteam.MAX_FLOW_ML_S),
    /** 0 turns the steam heater off; otherwise the stepper's range, which
     *  keeps out of the band the firmware snaps to off. */
    STEAM_TEMP("steam temperature", QcSteam.MIN_TEMP_C..QcSteam.MAX_TEMP_C),
    HOT_WATER_TEMP("hot-water temperature", QcBounds.WATER_TEMP_MIN_C..QcBounds.WATER_TEMP_MAX_C),
    HOT_WATER_VOLUME("hot-water volume", QcBounds.WATER_VOL_MIN_ML..QcBounds.WATER_VOL_MAX_ML),
    /** The phone's flush stepper starts at 0 (it shares the pre-infusion minimum). */
    FLUSH_TIME("flush time", 0.0..QcBounds.FLUSH_TIME_MAX_S),
    FLUSH_TEMP("flush temperature", QcBounds.FLUSH_TEMP_MIN_C..QcBounds.FLUSH_TEMP_MAX_C),
    /** Grind also comes from a shot's or bag's recorded setting, not just the stepper. */
    GRIND("grind", 0.0..MAX_GRIND);

    /** Why [value] is out of bounds for this field, or null. */
    fun refusal(value: Float): String? {
        if (this == STEAM_TEMP && value == 0f) return null
        return if (value.toDouble().within(range)) null else "$label out of range: $value"
    }
}

private const val MAX_ID_LENGTH = 128
private const val MAX_DOSE_G = 100.0
private const val MAX_YIELD_G = 2_000.0
private const val MAX_BREW_TEMP_C = 110.0
private const val MAX_PREINF_S = 300.0
private const val MAX_GRIND = 1_000.0

private fun idRefusal(id: String): String? = when {
    id.isEmpty() -> "an id is required"
    id.length > MAX_ID_LENGTH || id.any { it.isISOControl() } -> "malformed id"
    else -> null
}

private fun Double.within(range: ClosedFloatingPointRange<Double>): Boolean = isFinite() && this in range
