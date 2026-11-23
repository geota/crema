package coffee.crema.ui.brewlog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.brew.formatClock
import coffee.crema.brew.hasExpectedDuration
import coffee.crema.brew.methodIcon
import coffee.crema.brew.stepKindLabel
import coffee.crema.brew.stepSpec
import coffee.crema.core.BrewRecipe
import coffee.crema.core.BrewSeries
import coffee.crema.core.BrewStep
import coffee.crema.core.StepAdvance
import coffee.crema.ui.GuidedBrewUi
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono
import kotlin.math.roundToInt

/*
 * Brew's Manual page (brew-modes spec §3): the guided brew, moved from
 * Devices → Scale → Brew onto Brew, for the recipe Brew has selected. The
 * session is the same GuidedBrewController engine; these are the pieces the
 * Brew layouts place (the tablet's steps column, step tile, chart and foot
 * controls; the phone's step card, list and Start brew). The live state and
 * cues are shared with [GuidedBrewPanel] through [LiveModel].
 */

/** The live model of [session], or of [recipe] at rest (its first step, the clock at 0). */
internal fun manualModel(
    session: GuidedBrewUi,
    recipe: BrewRecipe,
    connected: Boolean,
    weightG: Float?,
    flowGPerS: Float?,
    nowRealtime: Long,
): LiveModel {
    val live = coffee.crema.ui.BrewSelectionRules.guidedBrewRunning(session.phase)
    val elapsed = if (live) session.elapsedMs(nowRealtime) else 0L
    val cueAt = session.cueAtRealtime
    return LiveModel(
        session = if (live) session else GuidedBrewUi(),
        recipe = if (live) session.recipe ?: recipe else recipe,
        connected = connected,
        weightG = weightG,
        flowGPerS = flowGPerS,
        elapsedMs = elapsed,
        stepElapsedMs = if (live) (elapsed - session.stepStartedAtMs).coerceAtLeast(0L) else 0L,
        flashing = live && cueAt != null && nowRealtime - cueAt in 0..CUE_FLASH_MS,
    )
}

/** A session is armed, running or paused: the clock and steps are live. */
internal val LiveModel.live: Boolean get() = coffee.crema.ui.BrewSelectionRules.guidedBrewRunning(session.phase)

/** "Bloom to 50 g": the step's kind and water target (a built-in's long instruction is the note). */
internal fun stepTitle(step: BrewStep): String =
    stepKindLabel(step.kind) + (step.targetWaterG?.let { " to ${it.roundToInt()} g" } ?: "")

/** The step's own instruction, when it has one. */
private fun stepNote(step: BrewStep): String? = step.label?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Where the current step stands, for the tile's right side: weight against
 * the target, a countdown, or a drawdown's "about … left" / "+… over".
 * Null at rest and for a tap-only step.
 */
internal fun stepProgress(m: LiveModel): String? {
    if (!m.live || m.armed) return null
    val step = m.current ?: return null
    val target = step.targetWaterG
    val dur = step.durationS
    return when {
        target != null && m.connected && m.weightG != null -> "${m.weightG.coerceAtLeast(0f).roundToInt()} / ${target.roundToInt()} g"
        dur != null && hasExpectedDuration(step) -> {
            val left = dur * 1000 - m.stepElapsedMs
            if (left > 0) "about ${formatClock(left)} left" else "+${formatClock(-left)} over"
        }
        dur != null -> formatClock((dur * 1000 - m.stepElapsedMs).coerceAtLeast(0L)) + " left"
        else -> null
    }
}

/** The fraction of the current step done, for the progress track; null = no track. */
private fun stepFraction(m: LiveModel): Float? {
    if (!m.live || m.armed) return null
    val step = m.current ?: return null
    val target = step.targetWaterG
    val dur = step.durationS
    return when {
        target != null && m.connected && m.weightG != null && target > 0f -> (m.weightG / target).coerceIn(0f, 1f)
        dur != null && dur > 0 -> (m.stepElapsedMs.toFloat() / (dur * 1000)).coerceIn(0f, 1f)
        else -> null
    }
}

private fun advanceTag(step: BrewStep): String = if (step.advance == StepAdvance.Manual) "Tap" else "Auto"

/**
 * The tablet's "Step n of N" tile beside Weight: the step, its target, its
 * note and the next step. At rest it previews the first step. Flashes with
 * the session's visual cues, like the Scale segment's step card.
 */
@Composable
internal fun ManualStepTile(m: LiveModel, modifier: Modifier = Modifier) {
    val step = m.current
    val border = if (m.flashing) MaterialTheme.colorScheme.primary else Color.Transparent
    CremaCard(modifier.testTag("manual-step-tile")) {
        Column(
            Modifier.fillMaxSize()
                .background(if (m.flashing) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (step == null) {
                Eyebrow("No steps")
                return@Column
            }
            Eyebrow(
                "Step ${m.session.stepIndex + 1} of ${m.steps.size} · ${advanceTag(step)}",
                color = if (m.flashing) border else MaterialTheme.colorScheme.primary,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stepTitle(step),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 24.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                stepProgress(m)?.let {
                    Text(
                        it,
                        style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 18.sp, fontFeatureSettings = "tnum"),
                        maxLines = 1,
                        modifier = Modifier.testTag("manual-step-progress"),
                    )
                }
            }
            val next = if (m.armed) "Pour to start — the clock begins at the first water"
            else m.next?.let { "next: ${stepTitle(it)}" } ?: "last step"
            Text(
                listOfNotNull(stepNote(step), next).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            stepFraction(m)?.let { ProgressTrack(it) }
        }
    }
}

/**
 * The recipe's steps (the tablet's left column under the timer, the phone's
 * list): the current one highlighted, each tagged Auto or Tap; then the
 * scale's status, "Start on first pour" with a scale, and the cue sound.
 */
@Composable
internal fun ManualStepsCard(
    vm: MainViewModel,
    m: LiveModel,
    startOnPour: Boolean,
    soundOn: Boolean,
    modifier: Modifier = Modifier,
    /** The list scrolls inside the card (the tablet column); false = it grows (the phone's scrolling page). */
    scrollSteps: Boolean = true,
) {
    CremaCard(modifier.testTag("manual-steps")) {
        Column(Modifier.fillMaxWidth().then(if (scrollSteps) Modifier.fillMaxHeight() else Modifier).padding(vertical = 8.dp)) {
            val list = Modifier.fillMaxWidth().let { if (scrollSteps) it.weight(1f).verticalScroll(rememberScrollState()) else it }
            Column(list) {
                m.steps.forEachIndexed { i, step ->
                    ManualStepRow(i, step, current = m.live && !m.armed && i == m.session.stepIndex, done = m.live && i < m.session.stepIndex)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.size(8.dp).background(
                            if (m.connected) CremaTheme.telemetry.success else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        ),
                    )
                    Text(
                        if (m.connected) "Scale connected — targets are live" else "No scale — steps run on timers and taps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).testTag("manual-scale-status"),
                    )
                    IconToggleButton(
                        checked = soundOn,
                        onCheckedChange = vm::setBrewCueSound,
                        modifier = Modifier
                            .testTag("guided-cue-sound")
                            .semantics {
                                contentDescription = "Cue sound"
                                stateDescription = if (soundOn) "On" else "Off"
                            },
                    ) {
                        PhIcon(
                            if (soundOn) "bell" else "bell-slash",
                            sizeDp = 18,
                            tint = if (soundOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            contentDescription = null,
                        )
                    }
                }
                if (m.connected && !m.live) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Switch(checked = startOnPour, onCheckedChange = vm::setGuidedStartOnPour)
                        Text("Start on first pour", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualStepRow(i: Int, step: BrewStep, current: Boolean, done: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (current) primary.copy(alpha = 0.14f) else Color.Transparent)
            .then(if (current) Modifier.testTag("manual-step-current") else Modifier)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "${i + 1}",
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp),
            color = if (current) primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(18.dp),
        )
        Text(
            stepKindLabel(step.kind),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (done) 0.55f else 1f),
        )
        Text(
            stepSpec(step),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (current) "NOW" else advanceTag(step).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, letterSpacing = 0.6.sp),
            color = if (current || step.advance != StepAdvance.Manual) primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The live chart: the scale's weight and flow against the current step's target. */
@Composable
internal fun ManualChart(m: LiveModel, series: BrewSeries, modifier: Modifier = Modifier) = LiveChart(m, series, modifier)

/**
 * The page's main control in place of Coffee (§3): Start brew at rest; Cancel
 * · Start while armed for the first pour; Finish · Pause/Resume · Skip while
 * it runs. Never taller than the Coffee button it replaces (56dp), so the
 * bottom bar keeps its height.
 */
@Composable
internal fun ManualControls(
    vm: MainViewModel,
    m: LiveModel,
    recipe: BrewRecipe,
    startOnPour: Boolean,
    /** A finished session waits on Save or Discard: no new brew until then. */
    summaryOpen: Boolean,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        when {
            !m.live -> StartBrewButton(
                icon = methodIcon(recipe.method),
                enabled = !summaryOpen,
                modifier = if (fill) Modifier.weight(1f) else Modifier,
                onClick = {
                    val onPour = startOnPour && m.connected
                    vm.brewSessionArm(recipe, onPour)
                    if (!onPour) vm.brewSessionBegin()
                },
            )
            m.armed -> {
                CremaButton(onClick = vm::brewSessionCancel, variant = CremaButtonVariant.Text, label = "Cancel")
                PillAction("play", "Start", tag = "guided-main", onClick = vm::brewSessionBegin)
            }
            else -> {
                CremaButton(onClick = vm::brewSessionFinish, variant = CremaButtonVariant.Text, label = "Finish", modifier = Modifier.testTag("guided-finish"))
                PillAction(
                    if (m.paused) "play" else "pause",
                    if (m.paused) "Resume" else "Pause",
                    tag = "guided-main",
                    onClick = if (m.paused) vm::brewSessionResume else vm::brewSessionPause,
                )
                CremaButton(onClick = vm::brewSessionSkip, variant = CremaButtonVariant.Text, label = "Skip ›", modifier = Modifier.testTag("guided-skip"))
            }
        }
    }
}

/** Start brew: the Coffee button's shape and size, in the recipe's method glyph. */
@Composable
private fun StartBrewButton(icon: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        color = if (enabled) bg else bg.copy(alpha = 0.4f),
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.height(56.dp).testTag("manual-start-brew"),
    ) {
        Row(
            Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            PhIcon(icon, sizeDp = 20, tint = MaterialTheme.colorScheme.onPrimary, contentDescription = null)
            Text("Start brew", style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

@Composable
private fun PillAction(icon: String, label: String, tag: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.height(56.dp).clip(RoundedCornerShape(999.dp)).testTag(tag),
    ) {
        Row(
            Modifier.padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhIcon(icon, sizeDp = 20, tint = MaterialTheme.colorScheme.onPrimary, contentDescription = null)
            Text(label, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

/**
 * The phone's current-step card (§3): step n of N, the clock, weight against
 * the step's target, and the step's note.
 */
@Composable
internal fun ManualPhoneStepCard(m: LiveModel, modifier: Modifier = Modifier) {
    val step = m.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().testTag("manual-phone-step"),
    ) {
        Column(
            Modifier
                .background(if (m.flashing) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Eyebrow(
                step?.let { "Step ${m.session.stepIndex + 1} of ${m.steps.size} · ${advanceTag(it)}" } ?: "No steps",
                color = MaterialTheme.colorScheme.primary,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (m.armed || !m.live) "0:00" else formatClock(m.elapsedMs),
                    style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 44.sp, lineHeight = 46.sp, fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (m.paused) 0.45f else 1f),
                    modifier = Modifier.testTag("guided-clock"),
                )
                val target = step?.targetWaterG
                if (target != null) {
                    val w = m.weightG?.takeIf { m.connected }?.coerceAtLeast(0f)
                    Text(
                        "${w?.let { coffee.crema.ui.fmt("%.1f", it) } ?: "—"} / ${target.roundToInt()} g",
                        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 20.sp, fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    stepProgress(m)?.let {
                        Text(it, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 18.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(
                if (m.armed) "Pour to start — the clock begins at the first water"
                else step?.let { listOfNotNull(stepTitle(it), stepNote(it)).joinToString(" · ") } ?: "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            stepFraction(m)?.let { ProgressTrack(it) }
        }
    }
}
