package coffee.crema.ui.brewlog

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
import coffee.crema.brew.BREW_METHOD_PRESETS
import coffee.crema.brew.formatClock
import coffee.crema.brew.methodLabel
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
import coffee.crema.ui.components.CremaFilterChip
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono
import kotlin.math.roundToInt

/*
 * The Scale screen's Brew segment (issue #10 Phase 2) — pick a method +
 * recipe, run the guided session against the core's BrewSessionMonitor,
 * and land the finished brew in the Log-brew form with the measured
 * numbers + weight curve attached. One number hierarchy: the clock
 * largest, the current step's progress second; three controls while
 * running. Twin of the web GuidedBrewPanel.
 *
 * State lives above the layout: the setup picks ([MainViewModel.guidedSetup]),
 * the recipe editor draft ([MainViewModel.recipeEdit]) and the live session
 * ([MainViewModel.ui].guidedBrew + the core monitor) are all VM-held, so a
 * rotation across 840dp — which swaps the phone and tablet nav hosts — keeps
 * every pick, edit and the running clock.
 *
 * Sizing is by the pane this panel actually gets ([LivePane]), and the live
 * session NEVER scrolls: the clock and Finish / Pause / Skip stay on screen.
 * Setup and the summary may scroll.
 */

/** Setup / step lists: readable line length on big panes. */
private val SETUP_MAX_WIDTH = 640.dp

/** The live session column on medium / two-column panes. */
private val SESSION_COLUMN_WIDTH = 440.dp

/** Below this step-row width the AUTO/TAP tag wraps under the step. */
private val STEP_TAG_INLINE_MIN = 360.dp

/** How long the step card flashes after a visual cue. */
private const val CUE_FLASH_MS = 900L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuidedBrewPanel(
    vm: MainViewModel,
    /** Shell navigation — the phone pushes the recipe editor / log form. */
    onNav: (String) -> Unit,
    /** True on the phone shell: the editor and log form are pushed routes;
     *  false on the tablet, where they are side sheets on the Scale tab. */
    phone: Boolean,
    modifier: Modifier = Modifier,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val setup by vm.guidedSetup.collectAsStateWithLifecycle()
    val liveSeries by vm.liveBrewSeries.collectAsStateWithLifecycle()
    val session = ui.guidedBrew
    val connected = ui.scaleState == ScaleBleManager.State.READY

    // The display clock — the shell shares elapsedRealtime with the core,
    // so it renders the session clock locally between events. Derived from
    // VM-held timestamps: a recreated composition resumes, never restarts.
    var nowRealtime by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(session.phase) {
        while (session.phase == "running" || session.phase == "paused" || session.phase == "armed") {
            nowRealtime = SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(100)
        }
    }

    when (session.phase) {
        "armed", "running", "paused" -> LiveSession(
            vm = vm,
            session = session,
            fallbackRecipe = setup.recipe,
            connected = connected,
            weightG = ui.scaleWeightG,
            flowGPerS = ui.scaleFlowGPerS,
            liveSeries = liveSeries,
            nowRealtime = nowRealtime,
            modifier = modifier,
        )
        "done" -> ScrollPane(modifier) {
            val summary = session.summary
            if (summary != null) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Eyebrow("Brew finished")
                    Text(summary.recipeName, style = MaterialTheme.typography.headlineSmall)
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
                            onClick = {
                                vm.openGuidedLogBrew()
                                if (phone) onNav(LOG_BREW_ROUTE)
                            },
                            icon = "check",
                            label = "Save brew…",
                            modifier = Modifier.testTag("guided-save"),
                        )
                    }
                }
            }
        }
        else -> ScrollPane(modifier) {
            val recipe = setup.recipe
            val method = setup.method ?: recipe?.method ?: "pourover"
            // ── Setup ───────────────────────────────────────────
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BREW_METHOD_PRESETS.forEach { p ->
                    CremaFilterChip(
                        label = p.label,
                        icon = p.icon,
                        selected = method == p.id,
                        onClick = { vm.selectGuidedMethod(p.id) },
                    )
                }
            }
            if (recipe != null) {
                SetupCard(
                    vm = vm,
                    recipe = recipe,
                    method = method,
                    siblings = ui.brewRecipes.filter { it.method == method && it.deletedAt == null },
                    connected = connected,
                    startOnPour = setup.startOnPour,
                    soundOn = BrewCueDefaults.soundOn(ui.brewCueSound),
                    onEdit = {
                        vm.openRecipeEdit(RecipeEditOwner.SCALE, recipe)
                        if (phone) onNav(RECIPE_EDIT_ROUTE)
                    },
                )
            }
        }
    }
}

/** Setup / summary host: scrolls, content capped at 640dp and centred. */
@Composable
private fun ScrollPane(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = SETUP_MAX_WIDTH).fillMaxWidth().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

@Composable
private fun SetupCard(
    vm: MainViewModel,
    recipe: BrewRecipe,
    method: String,
    siblings: List<BrewRecipe>,
    connected: Boolean,
    startOnPour: Boolean,
    soundOn: Boolean,
    onEdit: () -> Unit,
) {
    CremaCard(shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Eyebrow("Recipe · ${methodLabel(method)}")
                    Text(recipe.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        buildString {
                            append("${recipe.doseG.roundToInt()} g · ${recipe.waterG.roundToInt()} g water")
                            recipe.tempC?.let { append(" · ${it.roundToInt()} °C") }
                        },
                        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (siblings.size > 1) {
                    coffee.crema.ui.components.CremaFilterDropdown(
                        icon = "list-bullets",
                        keys = siblings.map { coffee.crema.ui.components.SortKey(it.id, it.name) },
                        selectedKey = recipe.id,
                        onKeyChange = { id -> siblings.firstOrNull { it.id == id }?.let(vm::selectGuidedRecipe) },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                // Opens the editor IN PLACE (side sheet / pushed screen);
                // Save returns here with the edited recipe selected.
                CremaButton(
                    onClick = onEdit,
                    variant = CremaButtonVariant.Outlined,
                    label = "Edit recipe",
                    modifier = Modifier.testTag("guided-edit-recipe"),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            recipe.steps.orEmpty().forEachIndexed { i, step -> SetupStepRow(i, step) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(8.dp).background(
                        if (connected) CremaTheme.telemetry.success else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
                )
                Text(
                    if (connected) "Scale connected — targets are live" else "No scale — steps run on timers and taps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (connected) {
                    CremaButton(onClick = vm::tareScale, variant = CremaButtonVariant.Outlined, label = "Tare")
                }
            }
            if (connected) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Switch(checked = startOnPour, onCheckedChange = vm::setGuidedStartOnPour)
                    Text("Start on first pour", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                // Cue sound — the same shared setting as Settings → Display.
                // Off by default; haptics + visual cues carry the session.
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
                        sizeDp = 20,
                        tint = if (soundOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = null,
                    )
                }
                CremaButton(
                    onClick = {
                        val onPour = startOnPour && connected
                        vm.brewSessionArm(recipe, onPour)
                        if (!onPour) vm.brewSessionBegin()
                    },
                    icon = "play",
                    label = "Start brew",
                    modifier = Modifier.testTag("guided-start"),
                )
            }
        }
    }
}

/** One setup step line; on narrow rows the AUTO/TAP tag wraps under the step. */
@Composable
private fun SetupStepRow(i: Int, step: BrewStep) {
    val tag: @Composable () -> Unit = {
        Text(
            if (step.advance == StepAdvance.Manual) "TAP" else "AUTO",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, letterSpacing = 0.5.sp),
            color = if (step.advance == StepAdvance.Manual) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
    val label = "${stepKindLabel(step.kind)} — ${stepSpec(step)}"
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inline = maxWidth >= STEP_TAG_INLINE_MIN
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = if (inline) Alignment.CenterVertically else Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "${i + 1}",
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(14.dp),
            )
            if (inline) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                tag()
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    tag()
                }
            }
        }
    }
}

// ── The live session ────────────────────────────────────────────────────

/** Everything the live layouts render, computed once per frame. */
private class LiveModel(
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
private fun LiveSession(
    vm: MainViewModel,
    session: GuidedBrewUi,
    fallbackRecipe: BrewRecipe?,
    connected: Boolean,
    weightG: Float?,
    flowGPerS: Float?,
    liveSeries: BrewSeries,
    nowRealtime: Long,
    modifier: Modifier,
) {
    val elapsed = session.elapsedMs(nowRealtime)
    val cueAt = session.cueAtRealtime
    val m = LiveModel(
        session = session,
        recipe = session.recipe ?: fallbackRecipe,
        connected = connected,
        weightG = weightG,
        flowGPerS = flowGPerS,
        elapsedMs = elapsed,
        stepElapsedMs = (elapsed - session.stepStartedAtMs).coerceAtLeast(0L),
        flashing = cueAt != null && nowRealtime - cueAt in 0..CUE_FLASH_MS,
    )
    val controls: @Composable (Modifier) -> Unit = { mod -> SessionControls(vm, m, mod) }
    BoxWithConstraints(modifier.fillMaxSize().testTag("guided-live")) {
        val paneH = maxHeight.value
        val pane = LivePane.of(maxWidth.value, paneH)
        val clockSp = LivePane.clockSp(paneH)
        when (pane) {
            LivePane.COMPACT -> Column(Modifier.fillMaxSize().testTag("live-compact")) {
                LiveHeader(m)
                Spacer(Modifier.height(6.dp))
                BigClock(m, clockSp, Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(10.dp))
                StepCard(m)
                Spacer(Modifier.height(8.dp))
                NextLine(m)
                Spacer(Modifier.weight(1f))
                controls(Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
            LivePane.MEDIUM -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = SESSION_COLUMN_WIDTH + 20.dp).fillMaxWidth().fillMaxHeight().testTag("live-medium")) {
                    LiveHeader(m)
                    Spacer(Modifier.height(6.dp))
                    BigClock(m, clockSp, Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(10.dp))
                    StepCard(m)
                    Spacer(Modifier.height(8.dp))
                    NextLine(m)
                    if (paneH >= LivePane.MEDIUM_CHART_MIN_HEIGHT) {
                        Spacer(Modifier.height(12.dp))
                        LiveChart(m, liveSeries, Modifier.fillMaxWidth().weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    controls(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
            }
            LivePane.TWO_COLUMN -> Row(Modifier.fillMaxSize().testTag("live-two-column"), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(SESSION_COLUMN_WIDTH).fillMaxHeight()) {
                    LiveHeader(m)
                    Spacer(Modifier.height(8.dp))
                    BigClock(m, LivePane.clockSp(paneH * 0.8f), Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(12.dp))
                    StepCard(m)
                    Spacer(Modifier.height(8.dp))
                    NextLine(m)
                    Spacer(Modifier.weight(1f))
                    controls(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                LiveChart(m, liveSeries, Modifier.weight(1f).fillMaxHeight())
            }
            LivePane.COCKPIT -> Row(
                Modifier.fillMaxSize().testTag("live-cockpit"),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(0.9f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    LiveHeader(m)
                    BigClock(m, (paneH * 0.2f).coerceIn(44f, 80f), Modifier)
                    controls(Modifier.fillMaxWidth())
                }
                Column(
                    Modifier.weight(1.1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                ) {
                    StepCard(m)
                    NextLine(m)
                }
            }
        }
    }
}

@Composable
private fun LiveHeader(m: LiveModel) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Eyebrow("Guided brew · ${methodLabel(m.recipe?.method ?: "")}")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                m.recipe?.name ?: "",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (m.connected && m.weightG != null) {
                Text(
                    "${coffee.crema.ui.fmt("%.1f", m.weightG)} g",
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp),
                    color = CremaTheme.telemetry.success,
                )
            }
        }
    }
}

@Composable
private fun BigClock(m: LiveModel, sizeSp: Float, modifier: Modifier) {
    Text(
        if (m.armed) "0:00" else formatClock(m.elapsedMs),
        style = TextStyle(
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Medium,
            fontSize = sizeSp.sp,
            letterSpacing = (-2).sp,
            fontFeatureSettings = "tnum",
        ),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (m.paused) 0.45f else 1f),
        maxLines = 1,
        modifier = modifier.testTag("guided-clock"),
    )
}

/**
 * The current step — live weight vs target, a countdown, or "until you tap".
 * Flashes its border + tint for a moment on every visual cue (step change,
 * pour-target approach, a held boundary) — visual cues are always on.
 */
@Composable
private fun StepCard(m: LiveModel) {
    val flash = m.flashing
    val border by animateColorAsState(
        if (flash) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(180),
        label = "cue-border",
    )
    val tint by animateColorAsState(
        if (flash) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
        animationSpec = tween(180),
        label = "cue-tint",
    )
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (flash) 2.dp else 1.dp, border),
        modifier = Modifier.fillMaxWidth().testTag(if (flash) "guided-step-card-cue" else "guided-step-card"),
    ) {
        Column(
            Modifier.fillMaxWidth().background(tint).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (m.armed) {
                Text(
                    "Pour to start — the clock begins at the first water.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val current = m.current
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Eyebrow("Step ${m.session.stepIndex + 1} of ${m.steps.size} · ${current?.let { stepKindLabel(it.kind) } ?: ""}")
                Text(
                    current?.let { stepSpec(it) } ?: "",
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val target = current?.targetWaterG
            val durS = current?.durationS
            val big = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 30.sp, fontFeatureSettings = "tnum")
            when {
                target != null && m.connected && m.weightG != null -> {
                    Text("${m.weightG.coerceAtLeast(0f).roundToInt()} / ${target.roundToInt()} g", style = big)
                    ProgressTrack((m.weightG / target).coerceIn(0f, 1f))
                }
                durS != null -> {
                    Text(formatClock((durS * 1000 - m.stepElapsedMs).coerceAtLeast(0L)) + " left", style = big)
                    ProgressTrack((m.stepElapsedMs.toFloat() / (durS * 1000)).coerceIn(0f, 1f))
                }
                target != null -> Text(
                    "Pour to ${target.roundToInt()} g — Skip when done",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    "Until you tap — Skip moves on",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NextLine(m: LiveModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            if (m.armed) "First · ${m.current?.let { "${stepKindLabel(it.kind)} ${stepSpec(it)}" } ?: ""}"
            else m.next?.let { "Next · ${stepKindLabel(it.kind)} ${stepSpec(it)}" } ?: "Last step",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        val flow = m.flowGPerS
        if (m.connected && flow != null && flow > 0.05f) {
            Text(
                "pour rate ${coffee.crema.ui.fmt("%.1f", flow)} g/s",
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LiveChart(m: LiveModel, series: BrewSeries, modifier: Modifier) {
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

private val CONTROL_SIDE: Dp = 88.dp

/** Exactly three controls: Finish · Pause/Resume · Skip (armed: Cancel · Start). */
@Composable
private fun SessionControls(vm: MainViewModel, m: LiveModel, modifier: Modifier) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
    ) {
        Box(Modifier.width(CONTROL_SIDE), contentAlignment = Alignment.Center) {
            if (m.armed) {
                CremaButton(onClick = vm::brewSessionCancel, variant = CremaButtonVariant.Text, label = "Cancel")
            } else {
                CremaButton(
                    onClick = vm::brewSessionFinish,
                    variant = CremaButtonVariant.Text,
                    label = "Finish",
                    modifier = Modifier.testTag("guided-finish"),
                )
            }
        }
        val mainIcon = if (m.armed || m.paused) "play" else "pause"
        val mainLabel = when {
            m.armed -> "Start"
            m.paused -> "Resume"
            else -> "Pause"
        }
        Surface(
            onClick = {
                when {
                    m.armed -> vm.brewSessionBegin()
                    m.paused -> vm.brewSessionResume()
                    else -> vm.brewSessionPause()
                }
            },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(62.dp)
                .testTag("guided-main")
                .semantics { contentDescription = mainLabel },
        ) {
            Box(contentAlignment = Alignment.Center) {
                PhIcon(mainIcon, sizeDp = 24, tint = MaterialTheme.colorScheme.onPrimary, contentDescription = null)
            }
        }
        Box(Modifier.width(CONTROL_SIDE), contentAlignment = Alignment.Center) {
            if (!m.armed) {
                CremaButton(
                    onClick = vm::brewSessionSkip,
                    variant = CremaButtonVariant.Text,
                    label = "Skip ›",
                    modifier = Modifier.testTag("guided-skip"),
                )
            }
        }
    }
}

@Composable
private fun ProgressTrack(fraction: Float) {
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
