package coffee.crema.ui.brewlog

import coffee.crema.ui.LiveReadouts
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.ble.ScaleBleManager
import coffee.crema.brew.brewMethodPickerPresets
import coffee.crema.brew.formatClock
import coffee.crema.brew.hasExpectedDuration
import coffee.crema.brew.methodLabel
import coffee.crema.brew.stepKindLabel
import coffee.crema.brew.stepLabel
import coffee.crema.brew.stepSpec
import coffee.crema.core.BrewRecipe
import coffee.crema.core.BrewSeries
import coffee.crema.core.BrewStep
import coffee.crema.core.StepAdvance
import coffee.crema.ui.GuidedBrewUi
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaPillButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaFilterChip
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono
import kotlin.math.roundToInt

/*
 * The guided brew's shared pieces (issue #10; brew-modes §3): the live model
 * of a session, its weight chart, the progress track, the finished brew's
 * summary and the display clock. Brew's Manual page (ManualBrew.kt) lays
 * them out on both shells; the Scale screen's old Brew segment is gone.
 * State lives above the layout (MainViewModel.guidedSetup, ui.guidedBrew,
 * the core monitor), so a rotation across 840dp keeps the running clock.
 */

/** How long the step card flashes after a visual cue. */
internal const val CUE_FLASH_MS = 900L

@OptIn(ExperimentalLayoutApi::class)
/**
 * A finished session's card: what was brewed, its numbers and curve, and
 * Discard / "Save brew…" ([onSave] opens the Log-brew form where the caller
 * hosts it). Brew's Manual page shows it on both layouts.
 */
@Composable
internal fun GuidedDoneSummary(vm: MainViewModel, onSave: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val setup by vm.guidedSetup.collectAsStateWithLifecycle()
    val session = ui.guidedBrew
    val brewBeanId = GuidedBeanRules.brewBag(ui.beans, ui.activeBeanId)
    val brewBean = brewBeanId?.let { id -> ui.beans.firstOrNull { it.id == id } }
    val summary = session.summary
    if (summary != null) {
        Column(
            Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Eyebrow("Brew finished")
            Text(summary.recipeName, style = MaterialTheme.typography.headlineSmall)
            Text(
                brewBean?.let { b ->
                    listOfNotNull(ui.roasters.firstOrNull { it.id == b.roasterId }?.name, b.name).joinToString(" · ")
                } ?: "No bean",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("guided-done-bean"),
            )
            // The setup they brewed with, read-only — never merged into the brew's notes.
            (session.recipe ?: setup.recipe)?.let { r ->
                RecipeNotesLine(
                    r,
                    Modifier.widthIn(max = 460.dp),
                    label = "Recipe notes",
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                DoneStat(formatClock(summary.durationMs), "time")
                summary.finalWeightG?.let { DoneStat("${it.roundToInt()} g", "water") }
                DoneStat("${summary.series.stageMarks.size}", "steps")
            }
            if (summary.series.samples.size >= 2) {
                BrewSessionCanvas(summary.series, Modifier.fillMaxWidth().height(160.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CremaButton(
                    onClick = vm::brewSessionClearSummary,
                    variant = CremaButtonVariant.Outlined,
                    label = "Discard",
                )
                CremaButton(
                    onClick = onSave,
                    icon = "check",
                    label = "Save brew…",
                    modifier = Modifier.testTag("guided-save"),
                )
            }
        }
    }
}

/**
 * The display clock for a live session: the shell shares elapsedRealtime with
 * the core, so it renders the session clock locally between events. Derived
 * from VM-held timestamps, so a recreated composition resumes, never restarts.
 */
@Composable
internal fun rememberGuidedNow(phase: String): Long {
    var nowRealtime by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(phase) {
        while (phase == "running" || phase == "paused" || phase == "armed") {
            nowRealtime = SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(100)
        }
    }
    return nowRealtime
}

/** Everything the live layouts render, computed once per frame. */
internal class LiveModel(
    val session: GuidedBrewUi,
    val recipe: BrewRecipe?,
    val connected: Boolean,
    val weightG: Float?,
    val flowGPerS: Float?,
    val elapsedMs: Long,
    val stepElapsedMs: Long,
    val flashing: Boolean,
) {
    val steps: List<BrewStep> = recipe?.steps.orEmpty()
    val current: BrewStep? = steps.getOrNull(session.stepIndex)
    val next: BrewStep? = steps.getOrNull(session.stepIndex + 1)
    val armed: Boolean get() = session.phase == "armed"
    val paused: Boolean get() = session.phase == "paused"
}

@Composable
internal fun LiveChart(m: LiveModel, series: BrewSeries, modifier: Modifier) {
    CremaCard(shape = RoundedCornerShape(14.dp), modifier = modifier.testTag("guided-live-chart")) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Eyebrow("Weight")
                if (!m.connected) {
                    Text(
                        "No scale — stages only",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Span: the session so far, floored at the recipe's planned time.
            val planned = m.steps.sumOf { (it.durationS ?: 0L) * 1000 }.coerceAtLeast(60_000L)
            BrewSessionCanvas(
                series = series,
                modifier = Modifier.fillMaxWidth().weight(1f),
                minSpanMs = maxOf(planned, m.elapsedMs + 10_000L),
                nowMs = if (m.armed) null else m.elapsedMs,
                targetG = m.current?.targetWaterG,
            )
        }
    }
}

@Composable
internal fun ProgressTrack(fraction: Float) {
    Box(
        Modifier.fillMaxWidth().height(6.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(999.dp)),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier.fillMaxWidth(fraction).height(6.dp)
                    .background(CremaTheme.telemetry.weight, RoundedCornerShape(999.dp)),
            )
        }
    }
}

@Composable
private fun DoneStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            value,
            style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 21.sp, fontFeatureSettings = "tnum"),
        )
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, letterSpacing = 0.6.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
