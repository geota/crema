package coffee.crema.ui.phone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.beans.isFrozen
import coffee.crema.core.BeanMix
import coffee.crema.core.BeanRoastType
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.*
import coffee.crema.ui.phone.components.CremaEdge
import coffee.crema.ui.phone.components.CremaPhoneBackBar

/*
 * PhoneBeanEditScreen — the pushed bean editor (DESIGN §3.8; port of
 * prototype/phone/phone-editors.jsx BeanEdit), wired to the SAME save seam as
 * the tablet editor (vm.updateBean maps the whole state onto the core Bean).
 *
 * Eight numbered sections: 01 Identity · 02 Roast & mix (1–10 RoastPicker) ·
 * 03 Dates · 04 Bag & grind (+ linked profile) · 05 Origin · 06 Tasting ·
 * 07 Buy again · 08 Notes.
 */
private val BAG_PRESETS = listOf(113, 227, 250, 340, 454, 1000)

@Composable
fun PhoneBeanEditScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val bean = ui.beans.firstOrNull { it.id == ui.editingBeanId }
        ?: ui.draftBean?.takeIf { it.id == ui.editingBeanId }

    if (bean == null) {
        Scaffold(
            topBar = { CremaPhoneBackBar(title = "Bean editor", onBack = onBack) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { inner ->
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No bean selected.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
    var frozen by remember(bean.id) { mutableStateOf(bean.isFrozen) }
    var archived by remember(bean.id) { mutableStateOf(bean.archivedAt != null) }
    var bagSize by remember(bean.id) { mutableStateOf(bean.bagSize.toDouble()) }
    var remaining by remember(bean.id) { mutableStateOf(bean.remaining.toDouble()) }
    var grinder by remember(bean.id) { mutableStateOf(bean.grinder) }
    var linkedProfileId by remember(bean.id) { mutableStateOf(bean.linkedProfileId) }
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

    val datesValid = roasted.isBlank() || opened.isBlank() || opened >= roasted

    val save: () -> Unit = {
        vm.updateBean(bean.id, roaster) { b ->
            b.copy(
                name = name.trim().ifBlank { b.name },
                roastLevel = roast.toUByte(),
                mix = BeanMix.entries.firstOrNull { it.string == mixSel } ?: b.mix,
                roastType = roastTypeSel.ifBlank { null }?.let { v -> BeanRoastType.entries.firstOrNull { it.string == v } },
                roastedOn = roasted.ifBlank { null },
                openedOn = opened.ifBlank { null },
                frozenOn = when {
                    frozen && b.isFrozen -> b.frozenOn
                    frozen -> java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
                    else -> b.frozenOn
                },
                defrostedOn = when {
                    frozen -> null
                    b.isFrozen -> java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
                    else -> b.defrostedOn
                },
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
                linkedProfileId = linkedProfileId,
                rating = rating.coerceIn(0, 5).toUByte(),
                tastingNotes = tastingNotes,
                url = url.ifBlank { null },
                notes = notes,
                tags = tags.toList().ifEmpty { null },
            )
        }
        if (active) {
            vm.setActiveBean(bean.id)
        } else if (ui.activeBeanId == bean.id) {
            vm.setActiveBean(null)
        }
        onBack()
    }

    Scaffold(
        topBar = {
            CremaPhoneBackBarWithSave(
                breadcrumb = "Beans",
                title = if (isNew) "New bean" else "Edit bean",
                saveEnabled = datesValid && name.isNotBlank(),
                onCancel = onBack,
                onSave = save,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CremaEdge)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            // 01 · Identity
            NumberedGroup("01", "Identity") {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoasterMarkAvatar(name = roaster.ifBlank { name.ifBlank { "?" } }, sizeDp = 64, cornerDp = 14, fontSize = 22.sp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        CremaTextField(value = name, onValueChange = { name = it }, placeholder = "Bean name *")
                        CremaTextField(value = roaster, onValueChange = { roaster = it }, placeholder = "Roaster")
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tags", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PhoneTagChips(tags)
                }
                EdRow("Active bean", sub = "Selected on Brew") { CremaSwitch(active, { active = it }) }
                EdRow("Favourite", sub = "Star in the library") { CremaSwitch(pinned, { pinned = it }) }
                EdRow("Decaf") { CremaSwitch(decaf, { decaf = it }) }
            }

            // 02 · Roast & mix
            NumberedGroup("02", "Roast & mix") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Roast level · 1 light → 10 dark",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RoastPicker(value = roast, onChange = { roast = it })
                }
                EdRow("Mix") {
                    CremaSegmentedButton(
                        options = listOf(SegOption("single", "Single"), SegOption("blend", "Blend")),
                        value = mixSel,
                        onChange = { mixSel = it },
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Roast type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CremaSegmentedButton(
                        options = listOf(
                            SegOption("", "None"),
                            SegOption("espresso", "Espresso"),
                            SegOption("filter", "Filter"),
                            SegOption("omni", "Omni"),
                        ),
                        value = roastTypeSel,
                        onChange = { roastTypeSel = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 03 · Dates
            NumberedGroup("03", "Dates") {
                CremaDateField(value = roasted, onValueChange = { roasted = it }, label = "Roasted on", maxDate = java.time.LocalDate.now().toString())
                CremaDateField(
                    value = opened, onValueChange = { opened = it }, label = "Opened on",
                    minDate = roasted.ifBlank { null }, maxDate = java.time.LocalDate.now().toString(),
                )
                if (!datesValid) {
                    Text(
                        "Opened can't precede the roast date.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                EdRow("Frozen storage", sub = "Pauses the off-roast clock") { CremaSwitch(frozen, { frozen = it }) }
                EdRow("Archived", sub = "Hide from the active list") { CremaSwitch(archived, { archived = it }) }
            }

            // 04 · Bag & grind
            NumberedGroup("04", "Bag & grind") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    EdRow("Bag size") {
                        EdStepper(bagSize, "g", 10.0, 0.0, 2000.0, { "%.0f".format(it) }) { bagSize = it }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BAG_PRESETS.forEach { g ->
                            val on = bagSize.toInt() == g
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .clickable { bagSize = g.toDouble() }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "$g",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                    color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                EdRow("Remaining") {
                    EdStepper(remaining, "g", 5.0, 0.0, bagSize.coerceAtLeast(0.0), { "%.0f".format(it) }) { remaining = it }
                }
                CremaTextField(value = grinder, onValueChange = { grinder = it }, label = "Grinder", placeholder = "e.g. Niche Zero")
                CremaTextField(value = grind, onValueChange = { grind = it }, label = "Grind setting", placeholder = "e.g. 4.2, 6 + a tooth")
                // Linked profile — bean → profile auto-load (feature parity with web/tablet).
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Linked profile", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    var pickerOpen by remember { mutableStateOf(false) }
                    val linked = ui.profiles.firstOrNull { it.id == linkedProfileId }
                    Box {
                        Surface(
                            onClick = { pickerOpen = true },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    linked?.name ?: "None — keep the loaded profile",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (linked != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                PhIcon("caret-down", sizeDp = 16, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        CremaAnchoredPopup(expanded = pickerOpen, onDismiss = { pickerOpen = false }) {
                            CremaMenuSurface(Modifier.widthIn(min = 260.dp, max = 320.dp).heightIn(max = 360.dp)) {
                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                    CremaMenuItem(
                                        label = "None — keep the loaded profile",
                                        onClick = { linkedProfileId = null; pickerOpen = false },
                                        active = linkedProfileId == null,
                                        showCheck = true,
                                    )
                                    ui.profiles.filter { it.id !in ui.hiddenProfileIds }.forEach { p ->
                                        CremaMenuItem(
                                            label = p.name,
                                            onClick = { linkedProfileId = p.id; pickerOpen = false },
                                            active = linkedProfileId == p.id,
                                            showCheck = true,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        "Setting this bean active also loads this profile on Brew.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 05 · Origin
            NumberedGroup("05", "Origin") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CremaTextField(value = country, onValueChange = { country = it }, label = "Country", placeholder = "Ethiopia", modifier = Modifier.weight(1f))
                    CremaTextField(value = region, onValueChange = { region = it }, label = "Region", placeholder = "Guji", modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CremaTextField(value = farm, onValueChange = { farm = it }, label = "Farm / co-op", placeholder = "Halo Hartume", modifier = Modifier.weight(1f))
                    CremaTextField(value = variety, onValueChange = { variety = it }, label = "Variety", placeholder = "Heirloom", modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CremaTextField(value = elevation, onValueChange = { elevation = it }, label = "Elevation", placeholder = "1900 masl", modifier = Modifier.weight(1f))
                    CremaTextField(value = processing, onValueChange = { processing = it }, label = "Process", placeholder = "Natural", modifier = Modifier.weight(1f))
                }
            }

            // 06 · Tasting
            NumberedGroup("06", "Tasting") {
                EdRow("Rating") {
                    CremaStarRating(rating, onChange = { rating = it }, touchDp = 36)
                }
                CremaTextField(
                    value = tastingNotes,
                    onValueChange = { tastingNotes = it },
                    label = "Tasting notes",
                    placeholder = "Red fruit, cocoa, juicy acidity…",
                    singleLine = false,
                    minLines = 3,
                )
            }

            // 07 · Buy again
            NumberedGroup("07", "Buy again") {
                CremaTextField(value = url, onValueChange = { url = it }, label = "Product URL", placeholder = "https://…")
            }

            // 08 · Notes
            NumberedGroup("08", "Notes") {
                CremaTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    placeholder = "Dial-in notes, storage, anything worth remembering.",
                    singleLine = false,
                    minLines = 3,
                )
            }
        }
    }
}

// Numbered section group (proto .pe-group-h "01 · Identity").
@Composable
private fun NumberedGroup(n: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                n,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = coffee.crema.ui.theme.JetBrainsMono,
                    fontSize = 10.5.sp,
                ),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            )
            Eyebrow(title)
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

// 1–10 roast pip track with Light / Medium / Dark bands (proto RoastPicker).
@Composable
private fun RoastPicker(value: Int, onChange: (Int) -> Unit) {
    val bandColor = when {
        value <= 3 -> Color(0xFFC99A5B)
        value <= 7 -> Color(0xFFA56A39)
        else -> Color(0xFF6E4326)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            (1..10).forEach { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (i <= value) bandColor else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { onChange(i) },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Light", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Medium", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Dark", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
