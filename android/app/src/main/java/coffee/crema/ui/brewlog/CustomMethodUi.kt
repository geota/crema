package coffee.crema.ui.brewlog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.brew.BREW_METHOD_STYLES
import coffee.crema.brew.CUSTOM_METHOD_ICONS
import coffee.crema.brew.customMethodIcon
import coffee.crema.brew.styleHint
import coffee.crema.brew.styleIcon
import coffee.crema.brew.styleLabel
import coffee.crema.core.CustomBrewMethod
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaConfirmDialog
import coffee.crema.ui.components.CremaFilterChip
import coffee.crema.ui.components.CremaOverflowMenu
import coffee.crema.ui.components.CremaTextField
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.OverflowItem
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.phone.components.CremaPhoneBackBar

/*
 * Your own brewing methods (issue #10 feedback) — the add / edit dialog and
 * the Profiles "Your methods" list. One body over the VM-held
 * [MainViewModel.methodEdit] draft, two hosts:
 *  • tablet: [MethodEditSheet], a right side sheet over whatever is open
 *    (the Log-brew sheet, the recipe editor, the Brew setup, Profiles);
 *  • phone: [MethodEditScreen], the pushed `method-edit` route.
 * Saving creates the method AND selects it where it was asked for.
 */

private val SHEET_WIDTH = 420.dp
private val FORM_MAX_WIDTH = 560.dp

/** The "+ Add method…" entry every method picker ends with. */
const val ADD_METHOD_KEY = "+add-method"
const val ADD_METHOD_LABEL = "Add method…"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MethodEditBody(vm: MainViewModel, d: MethodEditDraft, modifier: Modifier = Modifier) {
    val update: ((MethodEditDraft) -> MethodEditDraft) -> Unit = vm::updateMethodEdit
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CremaTextField(
            value = d.label,
            onValueChange = { v -> update { it.copy(label = v, error = null) } },
            label = "Name",
            placeholder = "e.g. ORB",
            modifier = Modifier.fillMaxWidth().testTag("method-name"),
        )
        d.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("method-error"))
        }
        Eyebrow("Style")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BREW_METHOD_STYLES.forEach { st ->
                val selected = d.style == st
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .border(
                            BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            shape,
                        )
                        .clickable { vm.setMethodEditStyle(st) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("method-style-${st.string}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PhIcon(
                        styleIcon(st),
                        sizeDp = 20,
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(styleLabel(st), style = MaterialTheme.typography.titleSmall)
                        Text(styleHint(st), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selected) PhIcon("check", sizeDp = 16, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Eyebrow("Defaults — optional")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            CremaTextField(
                value = d.dose,
                onValueChange = { v -> update { MethodEditRules.withField(it, MethodEditDraft.DOSE, v) } },
                label = "Dose (g)",
                keyboardOptions = num,
                modifier = Modifier.weight(1f).testTag("method-dose"),
            )
            CremaTextField(
                value = d.water,
                onValueChange = { v -> update { MethodEditRules.withField(it, MethodEditDraft.WATER, v) } },
                label = "Water (g)",
                keyboardOptions = num,
                modifier = Modifier.weight(1f).testTag("method-water"),
            )
            CremaTextField(
                value = d.temp,
                onValueChange = { v -> update { MethodEditRules.withField(it, MethodEditDraft.TEMP, v) } },
                label = "Temp (°C)",
                placeholder = "—",
                keyboardOptions = num,
                modifier = Modifier.weight(1f).testTag("method-temp"),
            )
        }
        Text(
            "Prefilled from the style. The log form opens on these until you've brewed it once.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Eyebrow("Icon")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconChoice(styleIcon(d.style), "Style default", selected = d.icon == null) { update { it.copy(icon = null) } }
            CUSTOM_METHOD_ICONS.forEach { key ->
                IconChoice(key, key, selected = d.icon == key) { update { it.copy(icon = key) } }
            }
        }
    }
}

@Composable
private fun IconChoice(icon: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(44.dp)
            .clip(shape)
            .border(
                BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                shape,
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        PhIcon(icon, sizeDp = 20, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = null)
    }
}

@Composable
private fun MethodEditFooter(isNew: Boolean, onCancel: () -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CremaButton(onClick = onCancel, variant = CremaButtonVariant.Outlined, label = "Cancel")
        CremaButton(onClick = onSave, icon = "check", label = if (isNew) "Add method" else "Save", modifier = Modifier.testTag("method-save"))
    }
}

/**
 * Tablet host — a side sheet. [parentRoute] names the sheet it's composed
 * inside (the Log-brew form or the recipe editor render it as a child dialog,
 * so it always stacks on top of them); null = the top-level instance, above
 * the nav host, for drafts opened from a tab (Brew setup, Profiles).
 */
@Composable
fun MethodEditSheet(vm: MainViewModel, parentRoute: String? = null) {
    val draft by vm.methodEdit.collectAsStateWithLifecycle()
    val d = draft ?: return
    if (d.parentRoute != parentRoute) return
    val dismiss = vm::closeMethodEdit
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().clickable(onClick = dismiss), contentAlignment = Alignment.CenterEnd) {
            val w = if (maxWidth < SHEET_WIDTH) maxWidth else SHEET_WIDTH
            Surface(
                modifier = Modifier
                    .width(w)
                    .fillMaxHeight()
                    .imePadding()
                    .clickable(enabled = false, onClick = {})
                    .testTag("method-edit-sheet"),
                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Eyebrow("Brewing method")
                            Text(d.heading, style = MaterialTheme.typography.titleLarge)
                        }
                        IconButton(onClick = dismiss, modifier = Modifier.semantics { contentDescription = "Close" }) {
                            PhIcon("x", sizeDp = 18, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    MethodEditBody(
                        vm,
                        d,
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    MethodEditFooter(d.isNew, onCancel = dismiss, onSave = { vm.saveMethodEdit() })
                }
            }
        }
    }
}

/**
 * Phone host — the pushed `method-edit` route over the form that opened it.
 * Back / Cancel / Save settle the draft; the route pops once, when it's gone.
 */
@Composable
fun MethodEditScreen(vm: MainViewModel, onBack: () -> Unit) {
    val draft by vm.methodEdit.collectAsStateWithLifecycle()
    val close = vm::closeMethodEdit
    BackHandler { close() }
    val d = draft
    if (d == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    Scaffold(
        topBar = { CremaPhoneBackBar(title = d.heading, subtitle = "Brewing method", onBack = close) },
        bottomBar = {
            Column(Modifier.imePadding().navigationBarsPadding()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = FORM_MAX_WIDTH).fillMaxWidth()) {
                        MethodEditFooter(d.isNew, onCancel = close, onSave = { vm.saveMethodEdit() })
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            MethodEditBody(
                vm,
                d,
                Modifier.widthIn(max = FORM_MAX_WIDTH).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).testTag("method-edit-screen"),
            )
        }
    }
}

/**
 * Profiles → Brew recipes → "Your methods": the user's live methods with
 * Rename / Edit defaults / Delete, and "+ Add method". Delete confirms, says
 * past brews keep the name, and keeps the recipes that use the method.
 */
@Composable
fun YourMethodsSection(vm: MainViewModel, methods: List<CustomBrewMethod>, modifier: Modifier = Modifier) {
    var confirmDelete by remember { mutableStateOf<CustomBrewMethod?>(null) }
    val live = methods.filter { it.deletedAt == null }.sortedBy { it.createdAt }
    Column(modifier.fillMaxWidth().testTag("your-methods"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Your methods")
                Text(
                    if (live.isEmpty()) "Brewing on something else? Add it as a method." else "Brewers you added — they appear in every method picker.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CremaButton(
                onClick = { vm.openNewMethod(MethodEditTarget.MANAGE) },
                variant = CremaButtonVariant.Text,
                icon = "plus",
                label = "Add method",
                modifier = Modifier.testTag("your-methods-add"),
            )
        }
        live.forEach { m ->
            CremaCard(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("your-method-${m.label}")) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PhIcon(customMethodIcon(m), sizeDp = 20, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(m.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildString {
                                append(styleLabel(m.style))
                                val seeds = listOfNotNull(
                                    m.seedDoseG?.let { "${MethodEditRules.fmt(it)} g" },
                                    m.seedWaterG?.let { "${MethodEditRules.fmt(it)} g water" },
                                    m.seedTempC?.let { "${MethodEditRules.fmt(it)} °C" },
                                )
                                if (seeds.isNotEmpty()) append(" · " + seeds.joinToString(" · "))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CremaOverflowMenu(
                        items = listOf(
                            OverflowItem("pencil-simple", "Rename", { vm.openEditMethod(m.id) }),
                            OverflowItem("sliders-horizontal", "Edit defaults", { vm.openEditMethod(m.id) }),
                            OverflowItem("trash", "Delete method", { confirmDelete = m }, danger = true),
                        ),
                    )
                }
            }
        }
    }
    confirmDelete?.let { m ->
        CremaConfirmDialog(
            title = "Delete “${m.label}”?",
            body = "It leaves the method pickers. Past brews keep the name, and recipes that use it stay in your library and still run.",
            confirmLabel = "Delete",
            icon = "trash",
            danger = true,
            onConfirm = { vm.deleteCustomMethod(m.id); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
}

/**
 * The one-time "Save 'X' as a method?" card after an "Other…" log — shown at
 * the top of History until answered.
 */
@Composable
fun SaveMethodPromptCard(vm: MainViewModel, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val p = ui.saveMethodPrompt ?: return
    CremaCard(shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth().testTag("save-method-prompt")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PhIcon("plus-circle", sizeDp = 20, tint = MaterialTheme.colorScheme.primary)
            Text("Save “${p.label}” as a method?", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            CremaButton(onClick = vm::dismissSaveMethodPrompt, variant = CremaButtonVariant.Text, label = "Not now")
            CremaButton(onClick = vm::acceptSaveMethodPrompt, variant = CremaButtonVariant.Tonal, label = "Save", modifier = Modifier.testTag("save-method-accept"))
        }
    }
}

/** The "+ Add method…" chip at the end of a chip row. */
@Composable
fun AddMethodChip(onClick: () -> Unit) {
    CremaFilterChip(label = ADD_METHOD_LABEL, icon = "plus", selected = false, onClick = onClick, modifier = Modifier.testTag("add-method-chip"))
}
