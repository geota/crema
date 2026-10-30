package coffee.crema.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import coffee.crema.core.HotWaterDose
import coffee.crema.core.HotWaterMode
import coffee.crema.core.HotWaterTarget
import coffee.crema.core.ratioLabel
import coffee.crema.settings.Setting
import coffee.crema.ui.components.CremaSegmentedButton
import coffee.crema.ui.components.RowGate
import coffee.crema.ui.components.SegOption
import coffee.crema.ui.components.gated
import kotlin.math.roundToInt

/*
 * The Hot water card's Amount view (hot-water modes spec §2.7), shared by the
 * tablet Quick Controls sheet and the phone brew sheet: the Time · ml · Ratio
 * switch, the selected mode's stepper (ranges and chips from the core), and
 * one readout line from the core's target. Each edit hands back a whole new
 * dose for `setHotWaterDose`.
 */

/** The selected mode's stepper: what the card's stepper and chips show. */
data class HotWaterStepper(
    val value: Double,
    val unit: String?,
    val min: Double,
    val max: Double,
    val step: Double,
    val chips: List<Double>,
    val fmt: (Double) -> String,
    /** The setting the stepper edits, for its gate. */
    val setting: Setting,
    val onChange: (Double) -> Unit,
)

/** [dose]'s stepper, from the core's bounds; [onDose] gets the edited dose. */
fun hotWaterStepper(dose: HotWaterDose, onDose: (HotWaterDose) -> Unit): HotWaterStepper {
    val b = HotWaterUi.bounds
    return when (dose.mode) {
        HotWaterMode.Time -> HotWaterStepper(
            value = dose.timeS.toDouble(),
            unit = "s",
            min = b.timeS.min.toDouble(),
            max = b.timeS.max.toDouble(),
            step = b.timeS.step.toDouble(),
            chips = b.timeS.chips.map { it.toDouble() },
            fmt = { if (dose.timeNoLimit && b.timeNoLimit) "no limit" else it.roundToInt().toString() },
            setting = Setting.QC_HOT_WATER_TIME,
            onChange = { onDose(dose.copy(timeS = it.toFloat(), timeNoLimit = false)) },
        )
        HotWaterMode.Volume -> HotWaterStepper(
            value = dose.volumeMl.toDouble(),
            unit = "ml",
            min = b.volumeMl.min.toDouble(),
            max = b.volumeMl.max.toDouble(),
            step = b.volumeMl.step.toDouble(),
            chips = b.volumeMl.chips.map { it.toDouble() },
            fmt = { it.roundToInt().toString() },
            setting = Setting.QC_HOT_WATER_VOLUME,
            onChange = { onDose(dose.copy(volumeMl = it.toFloat())) },
        )
        HotWaterMode.Ratio -> HotWaterStepper(
            value = dose.ratio.toDouble(),
            unit = null,
            min = b.ratio.min.toDouble(),
            max = b.ratio.max.toDouble(),
            step = b.ratio.step.toDouble(),
            chips = b.ratio.chips.map { it.toDouble() },
            fmt = { ratioLabel(it.toFloat()) },
            setting = Setting.QC_HOT_WATER_RATIO,
            onChange = { onDose(dose.copy(ratio = it.toFloat())) },
        )
    }
}

/** The Time · ml · Ratio switch: it sets the persisted mode. */
@Composable
fun HotWaterModeSwitch(mode: HotWaterMode, onMode: (HotWaterMode) -> Unit, gate: RowGate, modifier: Modifier = Modifier) {
    CremaSegmentedButton(
        options = listOf(
            SegOption(HotWaterMode.Time.string, "Time"),
            SegOption(HotWaterMode.Volume.string, "ml"),
            SegOption(HotWaterMode.Ratio.string, "Ratio"),
        ),
        value = mode.string,
        onChange = { id -> HotWaterMode.entries.firstOrNull { it.string == id }?.let(onMode) },
        modifier = modifier.fillMaxWidth().gated(gate),
        compact = true,
    )
}

/** The readout for [target] ("" with none yet): see [hotWaterReadout]. */
fun hotWaterReadoutFor(target: HotWaterTarget?, weightUnit: String, volumeUnit: String, ratio: Float): String =
    target?.let { hotWaterReadout(it, weightUnit, volumeUnit, ratio) }.orEmpty()

/** The one readout line under the stepper, ellipsised; the full text is its
 *  accessible description. */
@Composable
fun HotWaterReadoutLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { contentDescription = text },
    )
}
