package coffee.crema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coffee.crema.core.BeanMix
import coffee.crema.core.BeanRoastType
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaIconButton
import coffee.crema.ui.components.CremaSegmentedButton
import coffee.crema.ui.components.CremaStepper
import coffee.crema.ui.components.CremaSwitch
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.components.SegOption
import coffee.crema.ui.theme.JetBrainsMono

/*
 * Bean editor (the pushed `bean-edit` route) — a full port of tablet/
 * bean-edit-screen.jsx: a two-pane shell (left rail = photo + numbered TOC +
 * help; right = 8 numbered form blocks: Identity, Roast & mix, Dates, Bag &
 * Grind, Origin, Tasting, Buy again, Notes). Save maps the whole editor state
 * onto the core `Bean` via `vm.updateBean(id, roaster) { it.copy(...) }` — every
 * field already exists on the core type, so this is pure shell wiring (no FFI).
 */
private val BE_TOC = listOf("Identity", "Roast & mix", "Dates", "Bag & Grind", "Origin", "Tasting", "Buy again", "Notes")
private val BE_BAG_PRESETS = listOf(113, 227, 250, 340, 454, 1000)
private val MIX_OPTIONS = listOf(SegOption("single", "Single"), SegOption("blend", "Blend"))
private val ROASTTYPE_OPTIONS = listOf(
    SegOption("", "None"),
    SegOption("espresso", "Espresso"),
    SegOption("filter", "Filter"),
    SegOption("omni", "Omni"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BeanEditScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val bean = ui.beans.firstOrNull { it.id == ui.editingBeanId }
        ?: ui.draftBean?.takeIf { it.id == ui.editingBeanId }

    if (bean == null) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            CremaIconButton(icon = "arrow-left", onClick = onBack)
            Text("No bean selected.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val isNew = ui.beans.none { it.id == bean.id }
    val roasterName0 = bean.roasterId?.let { rid -> ui.roasters.firstOrNull { it.id == rid }?.name } ?: ""
    var name by remember(bean.id) { mutableStateOf(bean.name) }
    var roaster by remember(bean.id) { mutableStateOf(roasterName0) }
    val tags = remember(bean.id) { mutableStateListOf<String>().apply { addAll(bean.tags.orEmpty()) } }
    var active by remember(bean.id) { mutableStateOf(bean.id == ui.activeBeanId) }
    var pinned by remember(bean.id) { mutableStateOf(bean.favourite) }
    var decaf by remember(bean.id) { mutableStateOf(bean.decaf) }
    var roast by remember(bean.id) { mutableStateOf(bean.roastLevel?.toInt() ?: 6) }
    var mixSel by remember(bean.id) { mutableStateOf(bean.mix?.string ?: "single") }
    var roastTypeSel by remember(bean.id) { mutableStateOf(bean.roastType?.string ?: "") }
    var roasted by remember(bean.id) { mutableStateOf(bean.roastedOn ?: "") }
    var opened by remember(bean.id) { mutableStateOf(bean.openedOn ?: "") }
    var frozen by remember(bean.id) { mutableStateOf(bean.frozenOn != null) }
    var archived by remember(bean.id) { mutableStateOf(bean.archivedAt != null) }
    var bagSize by remember(bean.id) { mutableStateOf(bean.bagSize.toDouble()) }
    var remaining by remember(bean.id) { mutableStateOf(bean.remaining.toDouble()) }
    var grinder by remember(bean.id) { mutableStateOf(bean.grinder) }
    var grind by remember(bean.id) { mutableStateOf(bean.grinderSetting) }
    var country by remember(bean.id) { mutableStateOf(bean.origin.country ?: "") }
    var region by remember(bean.id) { mutableStateOf(bean.origin.region ?: "") }
    var farm by remember(bean.id) { mutableStateOf(bean.origin.farm ?: "") }
    var variety by remember(bean.id) { mutableStateOf(bean.origin.variety ?: "") }
    var elevation by remember(bean.id) { mutableStateOf(bean.origin.elevation ?: "") }
    var processing by remember(bean.id) { mutableStateOf(bean.origin.processing ?: "") }
    var rating by remember(bean.id) { mutableStateOf(bean.rating.toInt()) }
    var tastingNotes by remember(bean.id) { mutableStateOf(bean.tastingNotes) }
    var url by remember(bean.id) { mutableStateOf(bean.url ?: "") }
    var notes by remember(bean.id) { mutableStateOf(bean.notes) }

    val save: () -> Unit = {
        vm.updateBean(bean.id, roaster) { b ->
            b.copy(
                name = name.trim().ifBlank { b.name },
                roastLevel = roast.toUByte(),
                mix = BeanMix.entries.firstOrNull { it.string == mixSel } ?: b.mix,
                roastType = roastTypeSel.ifBlank { null }?.let { v -> BeanRoastType.entries.firstOrNull { it.string == v } },
                roastedOn = roasted.ifBlank { null },
                openedOn = opened.ifBlank { null },
                frozenOn = if (frozen) (b.frozenOn ?: java.time.LocalDate.now().toString()) else null,
                archivedAt = if (archived) (b.archivedAt ?: System.currentTimeMillis()) else null,
                decaf = decaf,
                favourite = pinned,
                bagSize = bagSize.toFloat(),
                remaining = remaining.toFloat(),
                origin = b.origin.copy(
                    country = country.ifBlank { null },
                    region = region.ifBlank { null },
                    farm = farm.ifBlank { null },
                    variety = variety.ifBlank { null },
                    elevation = elevation.ifBlank { null },
                    processing = processing.ifBlank { null },
                ),
                grinder = grinder.trim(),
                grinderSetting = grind.trim(),
                rating = rating.coerceIn(0, 5).toUByte(),
                tastingNotes = tastingNotes,
                url = url.ifBlank { null },
                notes = notes,
                tags = tags.toList().ifEmpty { null },
            )
        }
        if (active) vm.setActiveBean(bean.id)
        onBack()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CremaIconButton(icon = "arrow-left", onClick = onBack)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Eyebrow(if (isNew) "Beans › New bean" else "Beans › Edit bean")
                Text(
                    listOfNotNull(roaster.ifBlank { null }, name.ifBlank { "Untitled bag" }).joinToString(" · "),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            CremaButton(onClick = onBack, variant = CremaButtonVariant.Text, label = "Cancel")
            CremaButton(onClick = save, icon = "check", label = "Save")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // ── Two-pane body ────────────────────────────────────────────────────
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Left rail — photo + TOC + help.
            Column(
                Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                BeanPhoto(roaster, name)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BE_TOC.forEachIndexed { i, label ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "%02d".format(i + 1),
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhIcon("info", sizeDp = 16, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Name & roaster are required. Everything else is optional and editable later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))

            // Right column — the form blocks.
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp).padding(top = 20.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                BeBlock("01", "Identity", "The name and roaster this bag is filed under.") {
                    BeField("Name", name) { name = it }
                    BeField("Roaster", roaster) { roaster = it }
                    Eyebrow("Tags")
                    TagChips(tags)
                    Eyebrow("Flags")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BeFlag("check-circle", "Active", active) { active = it }
                        BeFlag("star", "Pinned", pinned) { pinned = it }
                        BeFlag("drop-half", "Decaf", decaf) { decaf = it }
                    }
                }

                BeBlock("02", "Roast & mix", "Roast level drives the freshness window.") {
                    BeRow("Roast level", "1 = light · 10 = dark", stack = true) {
                        RoastPicker(roast) { roast = it }
                    }
                    BeRow("Mix", "Single origin or blend.") {
                        CremaSegmentedButton(options = MIX_OPTIONS, value = mixSel, onChange = { mixSel = it })
                    }
                    BeRow("Roast type", "What it's roasted for.") {
                        CremaSegmentedButton(options = ROASTTYPE_OPTIONS, value = roastTypeSel, onChange = { roastTypeSel = it })
                    }
                }

                BeBlock("03", "Dates", "Roast date drives the freshness signal everywhere else.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { BeField("Roasted on (YYYY-MM-DD)", roasted) { roasted = it } }
                        Box(Modifier.weight(1f)) { BeField("Opened on (YYYY-MM-DD)", opened) { opened = it } }
                    }
                    BeRow("Frozen storage", "Pauses the off-roast clock.") { CremaSwitch(frozen, { frozen = it }) }
                    BeRow("Archived", "Hide this bag from the active grid.") { CremaSwitch(archived, { archived = it }) }
                }

                BeBlock("04", "Bag & Grind", "Bag size auto-debits per shot. Grinder is bean-scoped.") {
                    BeRow("Bag size", stack = true) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CremaStepper(value = bagSize, unit = "g", onChange = { bagSize = it }, step = 10.0, min = 0.0, max = 2000.0, fmt = { "%.0f".format(it) })
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                BE_BAG_PRESETS.forEach { g -> PresetChip(g, bagSize.toInt() == g) { bagSize = g.toDouble() } }
                            }
                        }
                    }
                    BeRow("Remaining", stack = true) {
                        CremaStepper(value = remaining, unit = "g", onChange = { remaining = it }, step = 5.0, min = 0.0, max = 2000.0, fmt = { "%.0f".format(it) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { BeField("Grinder", grinder) { grinder = it } }
                        Box(Modifier.weight(1f)) { BeField("Grind setting", grind) { grind = it } }
                    }
                }

                BeBlock("05", "Origin", "Free text. For blends, separate components with a slash.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { BeField("Country", country) { country = it } }
                        Box(Modifier.weight(1f)) { BeField("Region", region) { region = it } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { BeField("Farm / co-op", farm) { farm = it } }
                        Box(Modifier.weight(1f)) { BeField("Variety", variety) { variety = it } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { BeField("Elevation", elevation) { elevation = it } }
                        Box(Modifier.weight(1f)) { BeField("Processing", processing) { processing = it } }
                    }
                }

                BeBlock("06", "Tasting", "How the bag is drinking — surfaces on the brew card and history.") {
                    BeRow("Rating", stack = true) { StarRating(rating) { rating = it } }
                    BeField("Tasting notes", tastingNotes, singleLine = false) { tastingNotes = it }
                }

                BeBlock("07", "Buy again", "A roastery link for quick reordering.") {
                    BeField("Product URL", url) { url = it }
                }

                BeBlock("08", "Notes", "Anything else worth remembering about this bag.") {
                    BeField("Notes", notes, singleLine = false) { notes = it }
                }
            }
        }
    }
}

// ── Form helpers ─────────────────────────────────────────────────────────────

/** A numbered section: a hairline rule, a mono number + serif title + sub, then body. */
@Composable
private fun BeBlock(n: String, title: String, sub: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(n, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        content()
    }
}

/** A label (+ optional sub) paired with a control — side-by-side, or stacked. */
@Composable
private fun BeRow(label: String, sub: String? = null, stack: Boolean = false, control: @Composable () -> Unit) {
    if (stack) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BeLabel(label, sub)
            control()
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f)) { BeLabel(label, sub) }
            control()
        }
    }
}

@Composable
private fun BeLabel(label: String, sub: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BeField(label: String, value: String, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Left-rail bag photo placeholder — colored roaster mark + caption. */
@Composable
private fun BeanPhoto(roaster: String, name: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    roaster.ifBlank { name }.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Text(roaster.ifBlank { "No roaster" }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Text("Bag photo upload is coming soon.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 10-pip roast-level picker with Light / Medium / Dark band labels. */
@Composable
private fun RoastPicker(value: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            (1..10).forEach { n ->
                val filled = n <= value
                val current = n == value
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (filled) MaterialTheme.colorScheme.primary.copy(alpha = 0.30f) else MaterialTheme.colorScheme.surfaceContainerLowest)
                        .then(if (current) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(7.dp)) else Modifier)
                        .clickable { onChange(n) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$n", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        val band = if (value <= 3) "light" else if (value <= 6) "medium" else "dark"
        Row(Modifier.fillMaxWidth()) {
            listOf("light" to "Light", "medium" to "Medium", "dark" to "Dark").forEach { (id, lbl) ->
                Text(
                    lbl.uppercase(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (band == id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

/** 5-star rating; tap the current value to clear to 0. */
@Composable
private fun StarRating(value: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..5).forEach { n ->
            IconButton(onClick = { onChange(if (n == value) 0 else n) }) {
                PhIcon("star", sizeDp = 26, tint = if (n <= value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A flag pill: icon + label + switch (Active / Pinned / Decaf). */
@Composable
private fun BeFlag(icon: String, label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhIcon(icon, sizeDp = 16, tint = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
        CremaSwitch(on, onToggle)
    }
}

/** A bag-size preset chip. */
@Composable
private fun PresetChip(value: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text("$value", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Tag input — removable chips over an "Add tag" field (commits on IME Done). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChips(tags: SnapshotStateList<String>) {
    var input by remember { mutableStateOf("") }
    if (tags.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.toList().forEach { tag ->
                InputChip(
                    selected = false,
                    onClick = { tags.remove(tag) },
                    label = { Text(tag) },
                    trailingIcon = { Text("✕", style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        label = { Text("Add tag") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                val t = input.trim()
                if (t.isNotEmpty() && tags.none { it.equals(t, ignoreCase = true) }) tags.add(t)
                input = ""
            },
        ),
    )
}
