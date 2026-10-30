package coffee.crema.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.core.HotWaterDose
import coffee.crema.core.HotWaterMode
import coffee.crema.core.HotWaterTarget
import coffee.crema.core.ratioLabel
import coffee.crema.settings.Setting
import coffee.crema.ui.components.CremaSplitLabel
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.components.RowGate
import coffee.crema.ui.components.SplitOption
import coffee.crema.ui.components.StepperExtraChip
import kotlin.math.roundToInt

/*
 * The Hot water card (hot-water modes spec §2.7), shared by the tablet Quick
 * Controls sheet and the phone brew sheet. It keeps its siblings' footprint:
 * the label reads "HOT WATER  TEMP | RATIO ▾", where the second option names
 * the persisted mode and, tapped again, opens a Time / Volume / Ratio menu;
 * the mode's stepper and chips come from the core's bounds; the resolved
 * amount or caveat is the stepper's secondary line (a short form in a narrow
 * card, the full sentence otherwise, and always for accessibility). Each edit
 * hands back a whole new dose for `setHotWaterDose`.
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
    /** The chips' labels (time's stay numbers while the value reads "∞"). */
    val chipFmt: (Double) -> String,
    /** Whether a chip is the current value (none while No limit is on). */
    val chipActive: (Double) -> Boolean,
    /** No limit as a sixth chip, "∞", when this firmware honours it. */
    val extraChip: StepperExtraChip?,
    /** The setting the stepper edits, for its gate. */
    val setting: Setting,
    val onChange: (Double) -> Unit,
)

/** [dose]'s stepper, from the core's bounds; [onDose] gets the edited dose. */
fun hotWaterStepper(dose: HotWaterDose, onDose: (HotWaterDose) -> Unit): HotWaterStepper {
    val b = HotWaterUi.bounds
    fun near(a: Float, c: Double) = kotlin.math.abs(a - c) < 0.05
    val whole: (Double) -> String = { it.roundToInt().toString() }
    return when (dose.mode) {
        HotWaterMode.Time -> {
            val noLimit = dose.timeNoLimit && b.timeNoLimit
            HotWaterStepper(
                value = dose.timeS.toDouble(),
                unit = if (noLimit) null else "s",
                min = b.timeS.min.toDouble(),
                max = b.timeS.max.toDouble(),
                step = b.timeS.step.toDouble(),
                chips = b.timeS.chips.map { it.toDouble() },
                fmt = { if (noLimit) "∞" else whole(it) },
                chipFmt = whole,
                chipActive = { !noLimit && near(dose.timeS, it) },
                extraChip = if (b.timeNoLimit) StepperExtraChip("∞", "No limit", noLimit) { onDose(dose.copy(timeNoLimit = true)) } else null,
                setting = Setting.QC_HOT_WATER_TIME,
                onChange = { onDose(dose.copy(timeS = it.toFloat(), timeNoLimit = false)) },
            )
        }
        HotWaterMode.Volume -> HotWaterStepper(
            value = dose.volumeMl.toDouble(),
            unit = "ml",
            min = b.volumeMl.min.toDouble(),
            max = b.volumeMl.max.toDouble(),
            step = b.volumeMl.step.toDouble(),
            chips = b.volumeMl.chips.map { it.toDouble() },
            fmt = whole,
            chipFmt = whole,
            chipActive = { near(dose.volumeMl, it) },
            extraChip = null,
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
            chipFmt = { ratioLabel(it.toFloat()) },
            chipActive = { near(dose.ratio, it) },
            extraChip = null,
            setting = Setting.QC_HOT_WATER_RATIO,
            onChange = { onDose(dose.copy(ratio = it.toFloat())) },
        )
    }
}

/** A dose mode: the label's word, and the menu's name and one-line hint. */
internal data class HotWaterModeItem(val mode: HotWaterMode, val word: String, val name: String, val hint: String)

internal val hotWaterModes = listOf(
    HotWaterModeItem(HotWaterMode.Time, "time", "Time", "Stops after a set time"),
    HotWaterModeItem(HotWaterMode.Volume, "ml", "Volume", "Stops at a set amount"),
    HotWaterModeItem(HotWaterMode.Ratio, "ratio", "Ratio", "Follows the shot's yield"),
)

/** The label's options: Temp, and the current mode's word (a menu). */
internal fun hotWaterLabelOptions(mode: HotWaterMode): List<SplitOption> =
    listOf(SplitOption("temp", "Temp"), SplitOption("amount", hotWaterModes.first { it.mode == mode }.word, menu = true))

/**
 * The card's label: "HOT WATER  TEMP | ‹MODE› ▾". [waterMode] is the Temp /
 * Amount view; the second option names [mode], and a tap on it while it is
 * active opens the mode menu, whose pick goes to [onMode] (picking the current
 * mode only closes it). The menu follows the dose's gate (a remote can't
 * change the mode); the view toggle is never gated.
 */
@Composable
fun HotWaterHeader(
    waterMode: WaterMode,
    onWaterMode: (WaterMode) -> Unit,
    mode: HotWaterMode,
    onMode: (HotWaterMode) -> Unit,
    gate: RowGate,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        CremaSplitLabel(
            prefix = "Hot water",
            options = hotWaterLabelOptions(mode),
            value = waterMode.value,
            onChange = { onWaterMode(WaterMode.of(it)) },
            onMenu = { if (gate.editable) open = true },
            menuLabel = "Choose how hot water stops",
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 220.dp)) {
            hotWaterModes.forEach { item ->
                val active = item.mode == mode
                Row(
                    Modifier
                        .fillMaxWidth()
                        .semantics { selected = active }
                        .clickable(role = Role.RadioButton) {
                            open = false
                            if (!active) onMode(item.mode)
                        }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(item.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
                    }
                    if (active) PhIcon("check", sizeDp = 16, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** The readout for [target] ("" with none yet): see [hotWaterReadout]. */
fun hotWaterReadoutFor(target: HotWaterTarget?, weightUnit: String, volumeUnit: String, ratio: Float): String =
    target?.let { hotWaterReadout(it, weightUnit, volumeUnit, ratio) }.orEmpty()

/** The caveat amber: web's `--warning` (#DBA764 on dark, #8C5A12 on light). */
@Composable
private fun warningColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFDBA764) else Color(0xFF8C5A12)

/**
 * The stepper's secondary line for [target], or null when there is nothing to
 * add: the short form ([long] false, a narrow tablet card) or the full
 * sentence (the phone, a wide card), one ellipsised line, copper or amber for
 * a caveat. The full sentence is always its accessible description.
 */
fun hotWaterSubLine(
    target: HotWaterTarget?,
    weightUnit: String,
    volumeUnit: String,
    ratio: Float,
    long: Boolean,
    fontSize: TextUnit = 10.sp,
): (@Composable () -> Unit)? {
    val sub = target?.let { hotWaterSub(it, volumeUnit) } ?: return null
    val sentence = hotWaterReadout(target, weightUnit, volumeUnit, ratio)
    return {
        Text(
            if (long) sentence else sub.short,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = fontSize, fontWeight = FontWeight.Normal),
            color = if (sub.warn) warningColor() else MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { contentDescription = sentence },
        )
    }
}
