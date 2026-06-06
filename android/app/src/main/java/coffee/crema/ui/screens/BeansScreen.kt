package coffee.crema.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.beans.daysOffRoast
import coffee.crema.beans.roastBand
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.core.Bean
import coffee.crema.core.Roaster
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaNavigationRail
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.components.CremaConfirmDialog
import coffee.crema.ui.components.CremaOverflowMenu
import coffee.crema.ui.components.OverflowItem
import coffee.crema.ui.components.CremaSegmentedButton
import coffee.crema.ui.components.SegOption
import androidx.compose.material3.IconButton

/*
 * Beans (library) — M3 v1. The bean bags the user has on hand, persisted via
 * LibraryStore. A grid of bean cards (roaster · name, roast band, days off
 * roast), each with set-active (→ the Brew bean block) and delete, plus an
 * "Add bean" dialog. days-off-roast is computed shell-side for v1; the freshness
 * band/colour belongs in the core (FFI follow-up).
 *
 * Later M3 increments: the full bean editor (origin, grind, tasting notes,
 * burn-down), Beanconqueror import (import_beanconqueror_json), and roasters.
 */
@Composable
fun BeansScreen(
    vm: MainViewModel,
    onNav: (String) -> Unit,
    onConnect: (String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val connected = ui.bleState == De1BleManager.State.READY
    val scaleConnected = ui.scaleState == ScaleBleManager.State.READY
    var showAdd by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("bags") }
    var roasterDialogOpen by remember { mutableStateOf(false) }
    var roasterEditing by remember { mutableStateOf<Roaster?>(null) }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CremaNavigationRail(
            active = "beans",
            onNav = onNav,
            machineConnected = connected,
            scaleConnected = scaleConnected,
            onConnect = onConnect,
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Eyebrow("Library")
                    Text(
                        "Beans",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${ui.beans.size} bags · ${ui.roasters.size} roasters · ${ui.beans.count { it.id == ui.activeBeanId }} active",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CremaButton(
                    onClick = { if (tab == "bags") { vm.startNewBean(); onNav("bean-edit") } else { roasterEditing = null; roasterDialogOpen = true } },
                    icon = "plus",
                    label = if (tab == "bags") "Add bean" else "Add roaster",
                )
            }
            // Bags / Roasters — the prototype's two-tab Beans surface.
            CremaSegmentedButton(
                options = listOf(
                    SegOption("bags", "Bags  ${ui.beans.size}"),
                    SegOption("roasters", "Roasters  ${ui.roasters.size}"),
                ),
                value = tab,
                onChange = { tab = it },
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            if (tab == "bags") {
                if (ui.beans.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No beans yet — add a bag to get started.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    ) {
                        items(ui.beans, key = { it.id }) { bean ->
                            BeanCard(
                                bean = bean,
                                roasterName = ui.roasters.firstOrNull { it.id == bean.roasterId }?.name,
                                isActive = bean.id == ui.activeBeanId,
                                onSetActive = { vm.setActiveBean(bean.id) },
                                onEdit = { vm.startEditBean(bean.id); onNav("bean-edit") },
                                onDuplicate = { vm.duplicateBean(bean.id) },
                                onFreezeToggle = { if (bean.frozenOn != null) vm.defrostBean(bean.id) else vm.freezeBean(bean.id) },
                                onArchiveToggle = { if (bean.archivedAt != null) vm.unarchiveBean(bean.id) else vm.archiveBean(bean.id) },
                                onToggleFavourite = { vm.toggleBeanFavourite(bean.id) },
                                onDelete = { vm.deleteBean(bean.id) },
                            )
                        }
                    }
                }
            } else {
                if (ui.roasters.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No roasters yet — add one to group your bags.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    ) {
                        items(ui.roasters, key = { it.id }) { roaster ->
                            RoasterCard(
                                roaster = roaster,
                                bagCount = ui.beans.count { it.roasterId == roaster.id },
                                onEdit = { roasterEditing = roaster; roasterDialogOpen = true },
                                onVisit = { vm.visitRoasterWebsite(roaster.website) },
                                onDelete = { vm.deleteRoaster(roaster.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddBeanDialog(
            onAdd = { name, roaster, level, roasted -> vm.addBean(name, roaster, level, roasted) },
            onDismiss = { showAdd = false },
        )
    }
    if (roasterDialogOpen) {
        RoasterDialog(
            initial = roasterEditing,
            onSave = { name, website, city, country, notes ->
                val editing = roasterEditing
                if (editing == null) vm.addRoaster(name, website, city, country, notes)
                else vm.updateRoaster(editing.id, name, website, city, country, notes)
            },
            onDismiss = { roasterDialogOpen = false },
        )
    }
}

@Composable
private fun BeanCard(
    bean: Bean,
    roasterName: String?,
    isActive: Boolean,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onFreezeToggle: () -> Unit,
    onArchiveToggle: () -> Unit,
    onToggleFavourite: () -> Unit,
    onDelete: () -> Unit,
) {
    val band = roastBand(bean.roastLevel?.toInt())
    val days = daysOffRoast(bean.roastedOn)
    val frozen = bean.frozenOn != null
    val tagList = bean.tags?.filter { it.isNotBlank() }.orEmpty()
    var confirmDelete by remember { mutableStateOf(false) }
    CremaCard(
        modifier = Modifier.fillMaxWidth(),
        container = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        border = if (isActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                BeanAvatar(roasterName ?: bean.name)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (roasterName != null) Eyebrow(roasterName)
                    Text(
                        bean.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 24.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    bean.origin.country?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = onToggleFavourite, modifier = Modifier.size(32.dp)) {
                    PhIcon(if (bean.favourite) "star-fill" else "star", sizeDp = 18, tint = if (bean.favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val pills = buildList {
                band?.let { add(it to true) }
                if (frozen) add("Frozen" to false)
                if (bean.decaf) add("Decaf" to false)
                tagList.forEach { add(it to false) }
            }
            if (pills.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pills.take(4).forEach { (t, isRoast) -> Pill(t, roast = isRoast) }
                }
            }
            val fresh = if (frozen) "Frozen" else days?.let { "${it}d off roast" }
            if (fresh != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(999.dp)).background(freshnessColor(frozen, days)))
                    Text(fresh, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (bean.bagSize > 0f) {
                val pct = (bean.remaining / bean.bagSize).coerceIn(0f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        Box(Modifier.fillMaxWidth(pct).height(6.dp).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.primary))
                    }
                    Text(
                        "${bean.remaining.toInt()} / ${bean.bagSize.toInt()} g",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CremaButton(
                    onClick = onSetActive,
                    modifier = Modifier.weight(1f),
                    variant = if (isActive) CremaButtonVariant.Outlined else CremaButtonVariant.Tonal,
                    icon = if (isActive) "check-circle" else null,
                    label = if (isActive) "Active for brew" else "Set active",
                )
                FilledTonalIconButton(onClick = onDuplicate) { PhIcon("copy", sizeDp = 18) }
                FilledTonalIconButton(onClick = onEdit) { PhIcon("pencil-simple", sizeDp = 18) }
                CremaOverflowMenu(items = buildList {
                    add(OverflowItem(if (frozen) "drop" else "snowflake", if (frozen) "Defrost" else "Freeze bag", onFreezeToggle))
                    add(OverflowItem("archive", if (bean.archivedAt != null) "Unarchive" else "Archive", onArchiveToggle))
                    add(OverflowItem("trash", "Delete bean", { confirmDelete = true }, danger = true))
                })
            }
        }
    }
    if (confirmDelete) {
        CremaConfirmDialog(
            title = "Delete bean?",
            body = "“${bean.name}” will be removed. This can’t be undone.",
            confirmLabel = "Delete",
            icon = "trash",
            danger = true,
            onConfirm = { onDelete(); confirmDelete = false },
            onDismiss = { confirmDelete = false },
        )
    }
}

// Roast variant = uppercase copper-tinted; everything else = neutral.
@Composable
private fun Pill(text: String, roast: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (roast) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            if (roast) text.uppercase() else text,
            style = MaterialTheme.typography.labelSmall,
            color = if (roast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Freshness status colour, keyed off days-off-roast (freeze pauses the counter).
// Resting (<4d) / aging (22–40d) = amber; peak (4–21d) = green; past-peak = muted
// red; frozen = icy blue; unknown = neutral. Semantic status hues (not theme tokens).
private fun freshnessColor(frozen: Boolean, days: Int?): Color = when {
    frozen -> Color(0xFF7FB0E0)
    days == null -> Color(0xFF8A8175)
    days < 4 -> Color(0xFFDBA764)
    days <= 21 -> Color(0xFF5FB87A)
    days <= 40 -> Color(0xFFDBA764)
    else -> Color(0xFFC58B8B)
}

// A 44dp roaster-mark avatar — a theme-color square keyed off the seed name.
@Composable
private fun BeanAvatar(seed: String) {
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
    )
    val color = palette[(seed.hashCode() and Int.MAX_VALUE) % palette.size]
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            seed.take(1).uppercase(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun AddBeanDialog(
    onAdd: (name: String, roaster: String, roastLevel: Int?, roastedOn: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var roaster by remember { mutableStateOf("") }
    var roasted by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(5) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add bean") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                OutlinedTextField(value = roaster, onValueChange = { roaster = it }, singleLine = true, label = { Text("Roaster") })
                OutlinedTextField(value = roasted, onValueChange = { roasted = it }, singleLine = true, label = { Text("Roasted on (YYYY-MM-DD)") })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Roast level  $level", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    FilledTonalIconButton(onClick = { level = (level - 1).coerceAtLeast(1) }) { Text("−") }
                    FilledTonalIconButton(onClick = { level = (level + 1).coerceAtMost(10) }) { Text("+") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, roaster, level, roasted); onDismiss() },
                enabled = name.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// A roaster directory card — avatar + name + "City · Country · N bags", with a
// kebab (Edit / Visit website / Delete). Proto's kebab-only roaster pattern.
@Composable
private fun RoasterCard(
    roaster: Roaster,
    bagCount: Int,
    onEdit: () -> Unit,
    onVisit: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    CremaCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                BeanAvatar(roaster.name)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        roaster.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 24.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val place = listOfNotNull(roaster.city, roaster.country).joinToString(" · ")
                    val sub = listOfNotNull(place.ifBlank { null }, "$bagCount ${if (bagCount == 1) "bag" else "bags"}").joinToString(" · ")
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CremaOverflowMenu(items = buildList {
                    add(OverflowItem("pencil-simple", "Edit roaster", onEdit))
                    if (!roaster.website.isNullOrBlank()) add(OverflowItem("arrow-square-out", "Visit website", onVisit))
                    add(OverflowItem("trash", "Delete roaster", { confirmDelete = true }, danger = true))
                })
            }
            roaster.website?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (confirmDelete) {
        CremaConfirmDialog(
            title = "Delete roaster?",
            body = "“${roaster.name}” will be removed. Its bags keep their data but lose the roaster link. This can’t be undone.",
            confirmLabel = "Delete",
            icon = "trash",
            danger = true,
            onConfirm = { onDelete(); confirmDelete = false },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun RoasterDialog(
    initial: Roaster?,
    onSave: (name: String, website: String, city: String, country: String, notes: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var website by remember { mutableStateOf(initial?.website ?: "") }
    var city by remember { mutableStateOf(initial?.city ?: "") }
    var country by remember { mutableStateOf(initial?.country ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add roaster" else "Edit roaster") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                OutlinedTextField(value = website, onValueChange = { website = it }, singleLine = true, label = { Text("Website") })
                OutlinedTextField(value = city, onValueChange = { city = it }, singleLine = true, label = { Text("City") })
                OutlinedTextField(value = country, onValueChange = { country = it }, singleLine = true, label = { Text("Country") })
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, website, city, country, notes); onDismiss() }, enabled = name.isNotBlank()) {
                Text(if (initial == null) "Add" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

