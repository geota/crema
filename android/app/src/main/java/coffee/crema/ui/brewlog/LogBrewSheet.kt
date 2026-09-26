package coffee.crema.ui.brewlog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.brew.BREW_METHOD_PRESETS
import coffee.crema.brew.methodIcon
import coffee.crema.ui.MainUiState
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaFilterChip
import coffee.crema.ui.components.CremaFilterDropdown
import coffee.crema.ui.components.CremaStarRating
import coffee.crema.ui.components.CremaStepper
import coffee.crema.ui.components.CremaStepperStyle
import coffee.crema.ui.components.CremaTextField
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.components.SortKey
import coffee.crema.ui.fmt
import coffee.crema.ui.phone.components.CremaPhoneBackBar

/*
 * The Log-brew form (issue #10) — the Brew Log's manual-entry door. One body,
 * two hosts, one VM-held draft ([MainViewModel.logBrew]):
 *  • tablet (≥840dp): a 420dp right side-sheet on the owning tab, a scrolling
 *    body over a sticky Save footer;
 *  • phone shell (<840dp): the pushed full-screen `log-brew` route with Save
 *    pinned above the IME.
 * Because the draft lives above the layout, rotating across 840dp swaps the
 * host (sheet ↔ route) without losing a keystroke. Method chips come first
 * (they re-template the seeds), the active bag is pre-selected with its
 * remaining grams, and the journal (rating / notes / next time) sits behind
 * one disclosure. Saving routes through [MainViewModel.saveLogBrew] — the bag
 * debit + bag-empty notice ride the same rails as a live shot.
 */

/** Forms never stretch past this on a big screen; extra width stays as margin. */
private val FORM_MAX_WIDTH = 640.dp

/** Tablet side-sheet width (the BeanDetailSheet pattern). */
private val SHEET_WIDTH = 420.dp

/** Below this pane height (phone landscape = the tablet rail at ~400dp) the form compacts. */
private val SHORT_PANE = 480.dp

/** The field grid goes two-up from this pane width. */
private val TWO_COLUMN_MIN = 360.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogBrewFormBody(
    vm: MainViewModel,
    draft: BrewLogDraft,
    ui: MainUiState,
    /** A short pane: method chips become one scrolling row, the journal stays closed. */
    short: Boolean,
    modifier: Modifier = Modifier,
) {
    val update: ((BrewLogDraft) -> BrewLogDraft) -> Unit = vm::updateLogBrew
    val bean = draft.beanId?.let { id -> ui.beans.firstOrNull { it.id == id } }
    val doseMissing = draft.doseMissing(bean != null)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Method chips — selection re-templates the numeric seeds. On a short
        // pane they collapse to ONE horizontally scrolling row so the fields
        // and the Save footer keep the height.
        val chips: @Composable () -> Unit = {
            BREW_METHOD_PRESETS.forEach { p ->
                CremaFilterChip(
                    label = p.label,
                    icon = p.icon,
                    selected = draft.method == p.id,
                    onClick = { vm.reseedLogBrew(p.id) },
                )
            }
            CremaFilterChip(label = "Other…", selected = draft.isCustom, onClick = { vm.reseedLogBrew(BrewLogDraft.OTHER) })
        }
        if (short) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("logbrew-chips-row"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) { chips() }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
        }
        if (draft.isCustom) {
            CremaTextField(
                value = draft.customMethod,
                onValueChange = { v -> update { it.copy(customMethod = v) } },
                placeholder = "e.g. Karlsbad Kanne",
                label = "Method name",
            )
            if (draft.attempted && draft.customMethod.isBlank()) {
                Text("Name the method.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        // Bean — the active bag by default, with its remaining grams.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow("Bean")
            CremaFilterDropdown(
                icon = "coffee-bean",
                keys = buildList {
                    add(SortKey("none", "No bean — inventory untouched"))
                    ui.beans.filter { it.archivedAt == null }.forEach { b ->
                        val roaster = ui.roasters.firstOrNull { it.id == b.roasterId }?.name
                        val left = b.remaining?.let { " · ${it.toInt()} g left" }.orEmpty()
                        add(SortKey(b.id, listOfNotNull(roaster, b.name).joinToString(" · ") + left))
                    }
                },
                selectedKey = draft.beanId ?: "none",
                onKeyChange = { k -> update { it.copy(beanId = if (k == "none") null else k) } },
            )
            if (draft.attempted && doseMissing) {
                Text(
                    "Enter the dose so the bag can be debited.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // The numeric facts — a grid sized by the pane it actually gets (the
        // sheet, the phone column), not by the device: two-up from 360dp.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= TWO_COLUMN_MIN) 2 else 1
            val fields: List<@Composable (Modifier) -> Unit> = listOf(
                { m ->
                    CremaStepper(
                        label = "Dose", value = draft.dose, unit = "g",
                        onChange = { v -> update { it.copy(dose = v) } },
                        step = 0.5, min = 0.0, max = 200.0, modifier = m, style = CremaStepperStyle.Boxed,
                    )
                },
                { m ->
                    CremaStepper(
                        label = if (draft.espresso) "Yield" else "Water", value = draft.water, unit = "g",
                        onChange = { v -> update { it.copy(water = v) } },
                        step = if (draft.espresso) 1.0 else 10.0, min = 0.0, max = 2000.0,
                        fmt = { fmt("%.0f", it) }, modifier = m, style = CremaStepperStyle.Boxed,
                    )
                },
                { m ->
                    CremaStepper(
                        label = "Grind", value = draft.grind, unit = null,
                        onChange = { v -> update { it.copy(grind = v) } },
                        step = 0.1, min = 0.0, max = 200.0, modifier = m, style = CremaStepperStyle.Boxed,
                    )
                },
                { m ->
                    CremaStepper(
                        label = "Temp", value = draft.temp, unit = "°C",
                        onChange = { v -> update { it.copy(temp = v) } },
                        step = 1.0, min = 0.0, max = 100.0, fmt = { fmt("%.0f", it) },
                        modifier = m, style = CremaStepperStyle.Boxed,
                    )
                },
                { m ->
                    CremaTextField(
                        value = draft.timeStr,
                        onValueChange = { v -> update { it.copy(timeStr = v) } },
                        label = "Brew time",
                        placeholder = "m:ss",
                        modifier = m,
                    )
                },
                { m ->
                    CremaStepper(
                        label = "Logged", value = draft.minutesAgo, unit = "min ago",
                        onChange = { v -> update { it.copy(minutesAgo = v) } },
                        step = 5.0, min = 0.0, max = 1440.0, fmt = { fmt("%.0f", it) },
                        modifier = m, style = CremaStepperStyle.Boxed,
                    )
                },
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                fields.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                        row.forEach { field -> field(Modifier.weight(1f)) }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        val ratio = coffee.crema.core.brewRatio(draft.dose.toFloat(), draft.water.toFloat())
        Text(
            if (ratio != null) "ratio 1:${fmt("%.1f", ratio)}" else "ratio 1:—",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.primary,
        )

        // Journal — rating, notes, next time (the dial-in trio) behind one
        // disclosure: the form opens with the six facts, nothing to type.
        // On a short pane it stays closed.
        val journalOpen = draft.journalOpen && !short
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !short) { update { it.copy(journalOpen = !it.journalOpen) } }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhIcon(if (journalOpen) "caret-down" else "caret-right", sizeDp = 14, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Eyebrow("Journal — rating, notes, next time")
        }
        if (journalOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Eyebrow("Rating")
                    CremaStarRating(draft.rating, onChange = { r -> update { it.copy(rating = if (r == it.rating) 0 else r) } })
                }
                CremaTextField(
                    value = draft.notes,
                    onValueChange = { v -> update { it.copy(notes = v) } },
                    label = "Tasting notes",
                    singleLine = false,
                    minLines = 2,
                )
                CremaTextField(
                    value = draft.nextPlan,
                    onValueChange = { v -> update { it.copy(nextPlan = v) } },
                    label = "Next time",
                    placeholder = "e.g. grind 1 finer, bloom 45 s",
                    singleLine = false,
                    minLines = 2,
                )
            }
        }
    }
}

/** The sticky footer — Cancel + Save, always on screen. */
@Composable
private fun LogBrewFooter(onCancel: () -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CremaButton(onClick = onCancel, variant = CremaButtonVariant.Outlined, label = "Cancel")
        CremaButton(onClick = onSave, icon = "check", label = "Save brew", modifier = Modifier.testTag("logbrew-save"))
    }
}

/** Centre a form column and cap its width on big screens. */
@Composable
private fun CappedColumn(maxWidth: Dp = FORM_MAX_WIDTH, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxWidth()) { content() }
    }
}

/**
 * Tablet host — the form as a 420dp right side-sheet over a scrim (the
 * [coffee.crema.ui.screens.BeanDetailSheet] pattern: a full-screen Dialog,
 * since M3 has no side-sheet primitive). Renders nothing unless a draft is
 * open for [owner]'s tab.
 */
@Composable
fun LogBrewSheet(vm: MainViewModel, owner: String) {
    val draft by vm.logBrew.collectAsStateWithLifecycle()
    val d = draft ?: return
    if (d.owner != owner) return
    val ui by vm.ui.collectAsStateWithLifecycle()
    val dismiss = vm::closeLogBrew
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .clickable(onClick = dismiss),
            contentAlignment = Alignment.CenterEnd,
        ) {
            val sheetWidth = if (maxWidth < SHEET_WIDTH) maxWidth else SHEET_WIDTH
            Surface(
                modifier = Modifier
                    .width(sheetWidth)
                    .fillMaxHeight()
                    .imePadding()
                    .clickable(enabled = false, onClick = {}),
                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                // The sheet sizes itself by the HEIGHT it gets: phone landscape
                // lands here (tablet rail at ~400dp tall).
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val short = maxHeight < SHORT_PANE
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(horizontal = 20.dp, vertical = if (short) 8.dp else 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PhIcon(methodIcon(null), sizeDp = 18, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                if (!short) Eyebrow("Journal")
                                Text("Log a brew", style = if (short) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
                            }
                            CremaButton(onClick = dismiss, variant = CremaButtonVariant.Text, label = "Close")
                        }
                        LogBrewFormBody(
                            vm = vm,
                            draft = d,
                            ui = ui,
                            short = short,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = if (short) 10.dp else 16.dp)
                                .testTag("logbrew-body"),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        LogBrewFooter(onCancel = dismiss, onSave = { vm.saveLogBrew() })
                    }
                }
            }
        }
    }
}

/**
 * Phone host — the pushed full-screen `log-brew` route: [CremaPhoneBackBar]
 * on top, the form scrolling in a centred ≤640dp column, Save pinned at the
 * bottom above the IME. Back / Cancel / Save close the draft and pop.
 */
@Composable
fun LogBrewScreen(vm: MainViewModel, onBack: () -> Unit) {
    val draft by vm.logBrew.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    // Back / Cancel / Save only settle the draft; the route pops ONCE, below,
    // when the draft is gone — so a save never double-pops past History.
    val close = vm::closeLogBrew
    BackHandler { close() }
    val d = draft
    if (d == null) {
        // Saved, discarded, or a stale restore: leave the route.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    Scaffold(
        topBar = { CremaPhoneBackBar(title = "Log a brew", subtitle = "Journal", onBack = close) },
        bottomBar = {
            Column(Modifier.imePadding().navigationBarsPadding()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CappedColumn {
                    LogBrewFooter(onCancel = close, onSave = { vm.saveLogBrew() })
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        BoxWithConstraints(Modifier.padding(inner).fillMaxSize()) {
            val short = maxHeight < SHORT_PANE
            Box(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopCenter,
            ) {
                LogBrewFormBody(
                    vm = vm,
                    draft = d,
                    ui = ui,
                    short = short,
                    modifier = Modifier
                        .widthIn(max = FORM_MAX_WIDTH)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .testTag("logbrew-body"),
                )
            }
        }
    }
}
