package coffee.crema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coffee.crema.beans.BAG_PRESETS
import coffee.crema.beans.BeanDraft
import coffee.crema.beans.applyBeanEdits
import coffee.crema.beans.isFrozen
import coffee.crema.beans.roastBand5
import coffee.crema.core.Bean
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.BeanPhotoBox
import coffee.crema.ui.components.rememberBeanPhotoPicker
import coffee.crema.ui.components.roasterMark
import coffee.crema.ui.components.roasterTone
import coffee.crema.ui.components.CremaAnchoredPopup
import coffee.crema.ui.components.CremaStarRating
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaMenuItem
import coffee.crema.ui.components.CremaMenuSurface
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaIconButton
import coffee.crema.ui.components.CremaSegmentedButton
import coffee.crema.ui.components.CremaStepper
import coffee.crema.ui.components.CremaSwitch
import coffee.crema.ui.components.CremaDateField
import coffee.crema.ui.components.CremaTextField
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
    var pinned by remember(bean.id) { mutableStateOf(bean.favourite == true) }
    var decaf by remember(bean.id) { mutableStateOf(bean.decaf == true) }
    var roast by remember(bean.id) { mutableStateOf(bean.roastLevel?.toInt() ?: 6) }
    var mixSel by remember(bean.id) { mutableStateOf(bean.mix?.string ?: "single") }
    var roastTypeSel by remember(bean.id) { mutableStateOf(bean.roastType?.string ?: "") }
    var roasted by remember(bean.id) { mutableStateOf(bean.roastedOn ?: "") }
    var opened by remember(bean.id) { mutableStateOf(bean.openedOn ?: "") }
    var frozen by remember(bean.id) { mutableStateOf(bean.isFrozen) }
    var archived by remember(bean.id) { mutableStateOf(bean.archivedAt != null) }
    var bagSize by remember(bean.id) { mutableStateOf((bean.bagSize ?: 0f).toDouble()) }
    var remaining by remember(bean.id) { mutableStateOf((bean.remaining ?: 0f).toDouble()) }
    var grinder by remember(bean.id) { mutableStateOf(bean.grinder ?: "") }
    var linkedProfileId by remember(bean.id) { mutableStateOf(bean.linkedProfileId) }
    var grind by remember(bean.id) { mutableStateOf(bean.grinderSetting ?: "") }
    var country by remember(bean.id) { mutableStateOf(bean.origin?.country ?: "") }
    var region by remember(bean.id) { mutableStateOf(bean.origin?.region ?: "") }
    var farm by remember(bean.id) { mutableStateOf(bean.origin?.farm ?: "") }
    var variety by remember(bean.id) { mutableStateOf(bean.origin?.variety ?: "") }
    var elevation by remember(bean.id) { mutableStateOf(bean.origin?.elevation ?: "") }
    var processing by remember(bean.id) { mutableStateOf(bean.origin?.processing ?: "") }
    var rating by remember(bean.id) { mutableStateOf(bean.rating?.toInt() ?: 0) }
    var tastingNotes by remember(bean.id) { mutableStateOf(bean.tastingNotes ?: "") }
    var url by remember(bean.id) { mutableStateOf(bean.url ?: "") }
    var notes by remember(bean.id) { mutableStateOf(bean.notes ?: "") }

    // Opened can't precede roasted (ISO yyyy-MM-dd sorts chronologically). Gates Save.
    val datesValid = roasted.isBlank() || opened.isBlank() || opened >= roasted

    val save: () -> Unit = {
        vm.updateBean(bean.id, roaster) { b ->
            applyBeanEdits(b, BeanDraft(
                name = name, roast = roast, mixSel = mixSel, roastTypeSel = roastTypeSel,
                roasted = roasted, opened = opened, frozen = frozen, archived = archived,
                decaf = decaf, pinned = pinned, bagSize = bagSize, remaining = remaining,
                country = country, region = region, farm = farm, variety = variety,
                elevation = elevation, processing = processing, grinder = grinder, grind = grind,
                linkedProfileId = linkedProfileId, rating = rating, tastingNotes = tastingNotes,
                url = url, notes = notes, tags = tags.toList(),
            ))
        }
        if (active) {
            vm.setActiveBean(bean.id)
        } else if (ui.activeBeanId == bean.id) {
            // The flag was switched OFF for the currently-active bag — clear it
            // (previously a silent one-way no-op).
            vm.setActiveBean(null)
        }
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
            CremaButton(onClick = save, icon = "check", label = "Save", enabled = datesValid)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // ── Two-pane body ────────────────────────────────────────────────────
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Left rail — photo + TOC + help.
            Column(
                Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                BeanPhotoEditor(vm, bean, roaster)
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
                        "Required fields are marked *. Everything else is optional and editable later.",
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
                    BeField("Name *", name) { name = it }
                    BeField("Roaster *", roaster) { roaster = it }
                    Eyebrow("Tags")
                    TagChips(tags)
                    Eyebrow("Flags")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BeFlag("check-circle", "Active", active) { active = it }
                        BeFlag("star", "Pinned", pinned) { pinned = it }
                        BeFlag("drop-half", "Decaf", decaf) { decaf = it }
                    }
                    Text(
                        "Active selects this bag on Brew. Pin keeps it on the brew strip; decaf is a filter facet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    val today = java.time.LocalDate.now().toString()
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { CremaDateField(roasted, { roasted = it }, "Roasted on", maxDate = today) }
                        Box(Modifier.weight(1f)) { CremaDateField(opened, { opened = it }, "Opened on", minDate = roasted.ifBlank { null }, maxDate = today) }
                    }
                    if (!datesValid) {
                        Text(
                            "Opened date can't be before the roast date.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    BeRow("Frozen storage", "Pauses the off-roast clock.") { CremaSwitch(frozen, { frozen = it }) }
                    BeRow("Archived", "Hide this bag from the active grid.") { CremaSwitch(archived, { archived = it }) }
                }

                BeBlock("04", "Bag & Grind", "Bag size auto-debits per shot. Grinder is bean-scoped.") {
                    BeRow("Bag size", stack = true) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CremaStepper(value = bagSize, unit = "g", onChange = { bagSize = it }, step = 10.0, min = 0.0, max = 2000.0, fmt = { "%.0f".format(it) })
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                BAG_PRESETS.forEach { g -> PresetChip(g, bagSize.toInt() == g) { bagSize = g.toDouble() } }
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
                    // Linked profile — auto-loads when this bean is activated on
                    // Brew/Beans (web BeanEditPage parity). "(missing profile)"
                    // marks a dangling link the user can clear via None.
                    BeRow("Linked profile", sub = "Auto-loads when this bean is selected on Brew.", stack = true) {
                        var lpOpen by remember { mutableStateOf(false) }
                        val lpName = linkedProfileId?.let { lid ->
                            ui.profiles.firstOrNull { it.id == lid }?.name ?: "(missing profile)"
                        } ?: "None"
                        Box {
                            Surface(
                                onClick = { lpOpen = true },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 11.dp).widthIn(min = 220.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(lpName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    PhIcon("caret-down", sizeDp = 14, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            CremaAnchoredPopup(expanded = lpOpen, onDismiss = { lpOpen = false }) {
                                CremaMenuSurface(Modifier.widthIn(min = 260.dp, max = 340.dp)) {
                                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                                        CremaMenuItem(label = "None", onClick = { lpOpen = false; linkedProfileId = null })
                                        ui.profiles.forEach { p ->
                                            CremaMenuItem(label = p.name, onClick = { lpOpen = false; linkedProfileId = p.id })
                                        }
                                    }
                                }
                            }
                        }
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
                    BeRow("Rating", stack = true) { CremaStarRating(rating, onChange = { rating = it }) }
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
    // Label-above filled input (CremaTextField) — the PWA form style, replacing
    // M3's floating-label OutlinedTextField across every editor field at once.
    CremaTextField(
        value = value,
        onValueChange = onChange,
        label = label,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Left-rail bag photo: the stored photo (web BeanEditPage hero), or the
 *  roaster-mark well, with camera / gallery / remove controls. */
@Composable
private fun BeanPhotoEditor(vm: MainViewModel, bean: Bean, roaster: String) {
    val picker = rememberBeanPhotoPicker(
        beanId = bean.id,
        newCameraUri = vm::newCameraOutputUri,
        onPicked = vm::setBeanImageFromUri,
    )
    val hasPhoto = bean.imageRef != null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BeanPhotoBox(
            beanId = bean.id,
            imageRef = bean.imageRef,
            updatedAt = bean.updatedAt,
            modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(14.dp)),
            fallback = { BeanMarkWell(roaster) },
        )
        Text(roaster.ifBlank { "No roaster" }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CremaButton(
                onClick = { picker.takePhoto() },
                modifier = Modifier.weight(1f),
                variant = CremaButtonVariant.Tonal,
                icon = "camera",
                label = if (hasPhoto) "Retake" else "Camera",
            )
            CremaButton(
                onClick = { picker.pickFromGallery() },
                modifier = Modifier.weight(1f),
                variant = CremaButtonVariant.Outlined,
                icon = "image",
                label = "Gallery",
            )
        }
        if (hasPhoto) {
            CremaButton(onClick = { vm.clearBeanImage(bean.id) }, variant = CremaButtonVariant.Text, danger = true, icon = "trash", label = "Remove photo")
        } else {
            Text("Add a bag photo — take one or pick from your gallery.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The roaster-mark hero well — the no-photo fallback (full-bleed tone square +
 *  the big two-letter mark; shared roasterMark/roasterTone, see RoasterMark.kt). */
@Composable
private fun BeanMarkWell(roaster: String) {
    Box(
        Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(14.dp))
            .background(roasterTone(roaster.ifBlank { null })),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            roasterMark(roaster.ifBlank { null }),
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color(0xFFF4EDE3),
        )
    }
}

/** 10-pip roast-level picker with Light / Medium / Dark band labels. */
@Composable
private fun RoastPicker(value: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            (1..10).forEach { n ->
                val current = n == value
                // Selected pip = solid copper (proto); pips below it = copper wash; rest = well.
                val bg = when {
                    current -> MaterialTheme.colorScheme.primary
                    n < value -> MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                    else -> MaterialTheme.colorScheme.surfaceContainerLowest
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(bg)
                        .clickable { onChange(n) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$n",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        // Web RoastSlider: FIVE band anchors under the 1..10 track, weighted to
        // sit under their pip spans (1-2 / 3-4 / 5 / 6-7 / 8-10).
        val band5 = roastBand5(value)
        Row(Modifier.fillMaxWidth()) {
            listOf(
                Triple("Light", 2f, false),
                Triple("Med-light", 2f, false),
                Triple("Medium", 1f, true),
                Triple("Med-dark", 2f, true),
                Triple("Dark", 3f, true),
            ).forEach { (lbl, w, center) ->
                Text(
                    lbl.uppercase(),
                    modifier = Modifier.weight(w),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = if (center) TextAlign.Center else TextAlign.Start,
                    color = if (band5 == lbl) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
        // Current readout (web "2 /10 · Light").
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$value", style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono), color = MaterialTheme.colorScheme.onSurface)
            Text(
                "/10${band5?.let { " · $it" }.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
