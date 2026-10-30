package coffee.crema.machine

import coffee.crema.core.HotWaterDose
import coffee.crema.core.MachineRequest
import coffee.crema.profiles.ProfileBounds
import coffee.crema.ui.CoreBounds
import coffee.crema.ui.HotWaterUi
import coffee.crema.ui.QcBounds

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
    sealed interface Control : Relayable {
        /** Why a mirror must not send this, judged from its own (possibly
         *  stale) view of the machine state, or null to send it. */
        fun senderRefusal(machineState: String?): String? = null
    }

    /** Primary-owned session config: a mirror applies it optimistically. */
    sealed interface Config : Relayable

    /** Kept to the device holding the machine: a mirror refuses it. */
    sealed interface LocalOnly : MachineCommand

    /** A machine-state request the UI makes, and the DE1 state it asks for. */
    sealed interface StateRequest : Control {
        val request: MachineRequest
    }

    /** Start a shot through the owned start sequence (profile upload, guard,
     *  pre-flush). The only way a relayed command reaches Espresso. */
    data object StartShot : Control

    /** End whatever the machine is doing (shot, steam, hot water, flush). */
    data object Stop : StateRequest {
        override val request get() = MachineRequest.IDLE
    }

    /** Wake a sleeping machine. The same DE1 request as [Stop], so a mirror
     *  sends it only while its view shows the machine asleep. */
    data object Wake : StateRequest {
        override val request get() = MachineRequest.IDLE
        override fun senderRefusal(machineState: String?): String? =
            if (machineState in ASLEEP) null else "The machine isn’t asleep"
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

    /** The live Quick-Controls brew override, applied by the core to the next
     *  shot's upload. Profiles set the defaults, so the ranges are physical
     *  limits; the brew temperature's is the core's own temperature bound. The
     *  device holding the machine then clamps it into the active profile's range
     *  with the core (`normalizeBrewOverrides`), so no step leaves that bound. */
    data class AdjustBrew(val dose: Double, val yieldOut: Double, val brewTemp: Double, val preinf: Double?) : Config {
        override fun refusal(): String? {
            // The ranges the Quick Controls themselves offer (the core's table,
            // F8): the dose stepper's; for the yield, what the yield or ratio
            // stepper reaches (0 = no weight target); the brew temperature's
            // core bound. Pre-infusion's dial widens per profile (a profile's
            // own longer pre-infusion), so it keeps the wire ceiling.
            val q = CoreBounds.qc
            val yieldRange = q.overrideYieldG.toRange()
            val inRange = dose.within(q.doseG.min.toDouble()..q.doseG.max.toDouble()) &&
                (yieldOut == 0.0 || yieldOut.within(yieldRange)) &&
                brewTemp.within(0.0..maxBrewTempC()) && (preinf == null || preinf.within(0.0..MAX_PREINF_S))
            return if (inRange) null else "brew override out of range"
        }
    }

    /** Drop the brew override: the active profile's recipe applies again. */
    data object ResetBrew : Config

    /** The debug panel's raw Espresso request: skips the start sequence, so it
     *  never leaves the device holding the machine. */
    data object RawEspresso : LocalOnly

    /** The hot-water dose: the mode and every mode's value, one edit (spec
     *  §4). A machine setting with no wire form: a remote shows the hub's and
     *  refuses an edit ("Change on <hub>"). */
    data class SetHotWaterDose(val dose: HotWaterDose) : LocalOnly
}

/** The Quick-Controls values a mirror may set, each with its stepper's bounds. */
enum class QcField(val label: String, private val range: () -> ClosedFloatingPointRange<Double>) {
    STEAM_TIME("steam time", { CoreBounds.qc.steamTimeS.toRange() }),
    STEAM_FLOW("steam flow", { CoreBounds.qc.steamFlowMlS.toRange() }),
    /** 0 turns the steam heater off; otherwise the stepper's range, which
     *  keeps out of the band the firmware snaps to off. */
    STEAM_TEMP("steam temperature", { CoreBounds.qc.steamTempC.toRange() }),
    HOT_WATER_TEMP("hot-water temperature", { QcBounds.WATER_TEMP_MIN_C..QcBounds.WATER_TEMP_MAX_C }),
    /** The bare volume edit; its range is the core's hot-water volume range. */
    HOT_WATER_VOLUME("hot-water volume", { HotWaterUi.bounds.volumeMl.let { it.min.toDouble()..it.max.toDouble() } }),
    FLUSH_TIME("flush time", { CoreBounds.qc.flushTimeS.toRange() }),
    FLUSH_TEMP("flush temperature", { CoreBounds.qc.flushTempC.toRange() }),
    /** Grind also comes from a shot's or bag's recorded setting, not just the stepper. */
    GRIND("grind", { 0.0..MAX_GRIND });

    /** Why [value] is out of bounds for this field, or null. The ranges are the
     *  Quick Controls steppers' (the core's table, F8), hot water aside. */
    fun refusal(value: Float): String? {
        if (this == STEAM_TEMP && value == 0f) return null
        return if (value.toDouble().within(range())) null else "$label out of range: $value"
    }
}

private fun coffee.crema.core.StepRange.toRange(): ClosedFloatingPointRange<Double> = min.toDouble()..max.toDouble()

private val ASLEEP = setOf("Sleep", "GoingToSleep")
private const val MAX_ID_LENGTH = 128
private const val MAX_PREINF_S = 300.0
private const val MAX_GRIND = 1_000.0

/** The core's temperature ceiling (`MAX_TEMPERATURE_C`, 100 °C), the one bound
 *  for a brew temperature on every path. */
internal fun maxBrewTempC(): Double = ProfileBounds.INSTANCE.maxTemperatureC.toDouble()

private fun idRefusal(id: String): String? = when {
    id.isEmpty() -> "an id is required"
    id.length > MAX_ID_LENGTH || id.any { it.isISOControl() } -> "malformed id"
    else -> null
}

private fun Double.within(range: ClosedFloatingPointRange<Double>): Boolean = isFinite() && this in range
