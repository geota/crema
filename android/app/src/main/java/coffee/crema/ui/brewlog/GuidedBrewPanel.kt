package coffee.crema.ui.brewlog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.ble.ScaleBleManager
import coffee.crema.brew.BREW_METHOD_PRESETS
import coffee.crema.brew.defaultRecipeFor
import coffee.crema.brew.formatClock
import coffee.crema.brew.methodLabel
import coffee.crema.brew.stepKindLabel
import coffee.crema.brew.stepSpec
import coffee.crema.core.BrewRecipe
import coffee.crema.core.StepAdvance
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaFilterChip
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono
import android.os.SystemClock
import kotlin.math.roundToInt

/*
 * The Scale screen's Brew segment (issue #10 Phase 2) — pick a method +
 * recipe, run the guided session against the core's BrewSessionMonitor,
 * and land the finished brew in the Log-brew sheet with the measured
 * numbers + weight curve attached. One number hierarchy: the clock
 * largest, the current step's progress second; three controls while
 * running. Twin of the web GuidedBrewPanel.
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuidedBrewPanel(
    vm: MainViewModel,
    /** Shell navigation — "Edit recipe" deep-links to the Profiles screen,
     *  where recipes are authored (the Brew recipes library section). */
    onNav: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** False when the HOST already scrolls (the phone screen) — nested
     *  same-direction scrollables would crash on the infinite height. */
    scrollable: Boolean = true,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val session = ui.guidedBrew
    val connected = ui.scaleState == ScaleBleManager.State.READY
    val weightG = ui.scaleWeightG

    // Setup state.
    var method by remember { mutableStateOf(ui.lastRecipeByMethod.keys.firstOrNull() ?: "pourover") }
    fun resolveRecipe(m: String): BrewRecipe =
        ui.lastRecipeByMethod[m]?.let { id -> ui.brewRecipes.firstOrNull { it.id == id && it.deletedAt == null } }
            ?: defaultRecipeFor(m, System.currentTimeMillis())
    var recipe by remember { mutableStateOf(resolveRecipe(method)) }
    var startOnPour by remember { mutableStateOf(true) }
    var logOpen by remember { mutableStateOf(false) }

    // The display clock — the shell shares elapsedRealtime with the core,
    // so it can render the session clock locally between events.
    var nowRealtime by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(session.phase) {
        while (session.phase == "running" || session.phase == "paused" || session.phase == "armed") {
            nowRealtime = SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(200)
        }
    }

    val liveRecipe = session.recipe ?: recipe
    val steps = liveRecipe.steps.orEmpty()
    val currentStep = steps.getOrNull(session.stepIndex)
    val nextStep = steps.getOrNull(session.stepIndex + 1)
    val elapsedMs = session.elapsedMs(nowRealtime)
    val stepElapsedMs = (elapsedMs - session.stepStartedAtMs).coerceAtLeast(0L)

    Column(
        modifier.then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (session.phase) {
            "idle" -> {
                // ── Setup ───────────────────────────────────────────
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BREW_METHOD_PRESETS.forEach { p ->
                        CremaFilterChip(
                            label = p.label,
                            icon = p.icon,
                            selected = method == p.id,
                            onClick = {
                                method = p.id
                                recipe = resolveRecipe(p.id)
                            },
                        )
                    }
                }
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
                            val forMethod = ui.brewRecipes.filter { it.method == method && it.deletedAt == null }
                            if (forMethod.size > 1) {
                                coffee.crema.ui.components.CremaFilterDropdown(
                                    icon = "list-bullets",
                                    keys = forMethod.map { coffee.crema.ui.components.SortKey(it.id, it.name) },
                                    selectedKey = recipe.id,
                                    onKeyChange = { id -> forMethod.firstOrNull { it.id == id }?.let { recipe = it } },
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            CremaButton(
                                onClick = {
                                    // Recipes are authored on Profiles — persist the
                                    // (possibly unsaved default) recipe, then deep-link
                                    // into the library's editor.
                                    vm.setDefaultBrewRecipe(recipe)
                                    vm.requestRecipeEdit(recipe.id)
                                    onNav("profiles")
                                },
                                variant = CremaButtonVariant.Outlined,
                                label = "Edit recipe",
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        steps.forEachIndexed { i, step ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    "${i + 1}",
                                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(14.dp),
                                )
                                Text(
                                    buildString {
                                        append(stepKindLabel(step.kind))
                                        append(" — ")
                                        append(stepSpec(step))
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
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
                        }
                    }
                }
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
                        Switch(checked = startOnPour, onCheckedChange = { startOnPour = it })
                        Text("Start on first pour", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                CremaButton(
                    onClick = { vm.brewSessionArm(recipe, startOnPour && connected); if (!(startOnPour && connected)) vm.brewSessionBegin() },
                    icon = "play",
                    label = "Start brew",
                )
            }
            "armed" -> {
                LiveHeader(liveRecipe, connected, weightG)
                BigClock("0:00", paused = false)
                Text(
                    "Pour to start — the clock begins at the first water.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                SessionControls(
                    mainIcon = "play",
                    onMain = vm::brewSessionBegin,
                    leftLabel = "Cancel",
                    onLeft = vm::brewSessionCancel,
                    rightLabel = null,
                    onRight = null,
                )
            }
            "running", "paused" -> {
                LiveHeader(liveRecipe, connected, weightG)
                BigClock(formatClock(elapsedMs), paused = session.phase == "paused")
                // Current step card.
                CremaCard(shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Eyebrow("Step ${session.stepIndex + 1} of ${steps.size} · ${currentStep?.let { stepKindLabel(it.kind) } ?: ""}")
                            Text(
                                currentStep?.let { stepSpec(it) } ?: "",
                                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val target = currentStep?.targetWaterG
                        val durS = currentStep?.durationS
                        when {
                            target != null && connected && weightG != null -> {
                                Text(
                                    buildString {
                                        append(weightG.coerceAtLeast(0f).roundToInt())
                                        append(" / ${target.roundToInt()} g")
                                    },
                                    style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 30.sp, fontFeatureSettings = "tnum"),
                                )
                                ProgressTrack((weightG / target).coerceIn(0f, 1f))
                            }
                            durS != null -> {
                                Text(
                                    formatClock((durS * 1000 - stepElapsedMs).coerceAtLeast(0L)) + " left",
                                    style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 30.sp, fontFeatureSettings = "tnum"),
                                )
                                ProgressTrack((stepElapsedMs.toFloat() / (durS * 1000)).coerceIn(0f, 1f))
                            }
                            else -> Text(
                                "Until you tap — Skip moves on",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().widthIn(max = 460.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        nextStep?.let { "Next · ${stepKindLabel(it.kind)} ${stepSpec(it)}" } ?: "Last step",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val flow = ui.scaleFlowGPerS
                    if (connected && flow != null && flow > 0.05f) {
                        Text(
                            "pour rate ${coffee.crema.ui.fmt("%.1f", flow)} g/s",
                            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SessionControls(
                    mainIcon = if (session.phase == "paused") "play" else "pause",
                    onMain = { if (session.phase == "paused") vm.brewSessionResume() else vm.brewSessionPause() },
                    leftLabel = "Finish",
                    onLeft = vm::brewSessionFinish,
                    rightLabel = "Skip ›",
                    onRight = vm::brewSessionSkip,
                )
            }
            "done" -> {
                val summary = session.summary
                if (summary != null) {
                    Column(
                        Modifier.fillMaxWidth(),
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
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CremaButton(
                                onClick = vm::brewSessionClearSummary,
                                variant = CremaButtonVariant.Outlined,
                                label = "Discard",
                            )
                            CremaButton(onClick = { logOpen = true }, icon = "check", label = "Save brew…")
                        }
                    }
                }
            }
        }
    }

    if (logOpen) {
        val summary = session.summary
        LogBrewSheet(
            vm = vm,
            guidedRecipeName = summary?.recipeName,
            guidedSeries = summary?.series,
            guidedDurationMs = summary?.durationMs,
            guidedWaterG = summary?.finalWeightG ?: liveRecipe.waterG,
            guidedMethod = summary?.method ?: liveRecipe.method,
            guidedDoseG = liveRecipe.doseG,
            guidedTempC = liveRecipe.tempC,
            onDismiss = {
                logOpen = false
                // Saving OR closing the sheet ends the summary card — the
                // sheet's own Save persisted the row already.
                if (session.phase == "done") vm.brewSessionClearSummary()
            },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.LiveHeader(
    recipe: BrewRecipe,
    connected: Boolean,
    weightG: Float?,
) {
    Column(
        Modifier.fillMaxWidth().align(Alignment.CenterHorizontally),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Eyebrow("Guided brew · ${methodLabel(recipe.method)}")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(recipe.name, style = MaterialTheme.typography.titleLarge)
            if (connected && weightG != null) {
                Text(
                    "${coffee.crema.ui.fmt("%.1f", weightG)} g",
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp),
                    color = CremaTheme.telemetry.success,
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.BigClock(text: String, paused: Boolean) {
    Text(
        text,
        style = TextStyle(
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Medium,
            fontSize = 72.sp,
            letterSpacing = (-2).sp,
            fontFeatureSettings = "tnum",
        ),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (paused) 0.45f else 1f),
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
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
private fun androidx.compose.foundation.layout.ColumnScope.SessionControls(
    mainIcon: String,
    onMain: () -> Unit,
    leftLabel: String?,
    onLeft: (() -> Unit)?,
    rightLabel: String?,
    onRight: (() -> Unit)?,
) {
    Row(
        Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        if (leftLabel != null && onLeft != null) {
            CremaButton(onClick = onLeft, variant = CremaButtonVariant.Text, label = leftLabel)
        } else {
            Spacer(Modifier.width(64.dp))
        }
        Surface(
            onClick = onMain,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(62.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                PhIcon(mainIcon, sizeDp = 24, tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        if (rightLabel != null && onRight != null) {
            CremaButton(onClick = onRight, variant = CremaButtonVariant.Text, label = rightLabel)
        } else {
            Spacer(Modifier.width(64.dp))
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
