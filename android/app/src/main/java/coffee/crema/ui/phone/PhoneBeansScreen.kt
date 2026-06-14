package coffee.crema.ui.phone

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.beans.daysOffRoast
import coffee.crema.beans.isFrozen
import coffee.crema.beans.roastBand
import coffee.crema.beans.roastBand5
import coffee.crema.core.Bean
import coffee.crema.core.Roaster
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.freshnessColor
import coffee.crema.ui.components.*
import coffee.crema.ui.phone.components.*
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono

/*
 * PhoneBeansScreen — the handset Beans library (port of
 * prototype/phone/phone-beans.jsx), wired to LIVE state.
 *
 * Bags + Roasters tabs; single column. Bean tiles lead with the roaster-mark
 * avatar, show color-coded freshness and a burn-down bar; the active bean is
 * accented like the loaded profile. Tile overflow lives in a bottom sheet.
 * Roasters are push rows into the pushed `roaster-edit` editor.
 */
@Composable
fun PhoneBeansScreen(
    vm: MainViewModel,
    onNav: (String) -> Unit,
    onConnect: (String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf("bags") }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf("freshest") }
    var sortDesc by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Bean?>(null) }
    var confirmDelete by remember { mutableStateOf<Bean?>(null) }
    var exportSheet by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBeanconquerorUri(uri)
    }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val t = pendingExport; pendingExport = null
        if (uri != null && t != null) vm.writeTextToUri(uri, t)
    }
    val launchSave: (String, String?) -> Unit = { name, content ->
        if (content != null) { pendingExport = content; saveLauncher.launch(name) }
    }

    val roasterNameOf: (Bean) -> String? = { b -> ui.roasters.firstOrNull { it.id == b.roasterId }?.name }
    val visibleBeans = ui.beans.filter { b ->
        val matchesSearch = query.isBlank() ||
            b.name.contains(query, ignoreCase = true) ||
            (roasterNameOf(b)?.contains(query, ignoreCase = true) == true) ||
            (b.origin.country?.contains(query, ignoreCase = true) == true)
        val matchesFilter = when (filter) {
            "archived" -> b.archivedAt != null
            "active" -> b.archivedAt == null && !b.isFrozen
            "favourite" -> b.archivedAt == null && b.favourite
            "frozen" -> b.archivedAt == null && b.isFrozen
            "light", "medium", "dark" -> b.archivedAt == null && roastBand(b.roastLevel?.toInt())?.equals(filter, ignoreCase = true) == true
            // "All" excludes archived (matches tablet); the dedicated Archived
            // chip is the only place they surface.
            else -> b.archivedAt == null
        }
        matchesSearch && matchesFilter
    }
    val beansAsc = when (sort) {
        "name" -> visibleBeans.sortedBy { it.name.lowercase() }
        "roast" -> visibleBeans.sortedBy { it.roastLevel?.toInt() ?: Int.MAX_VALUE }
        "rating" -> visibleBeans.sortedBy { it.rating.toInt() }
        "remaining" -> visibleBeans.sortedBy { it.remaining }
        else -> visibleBeans.sortedBy { daysOffRoast(it.roastedOn) ?: Int.MAX_VALUE }
    }
    val sortedBeans = if (sortDesc) beansAsc.reversed() else beansAsc
    val visibleRoasters = ui.roasters.filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true) ||
            (it.city?.contains(query, ignoreCase = true) == true) ||
            (it.country?.contains(query, ignoreCase = true) == true)
    }.sortedBy { it.name.lowercase() }

    Scaffold(
        topBar = {
            CremaPhoneTopBar(
                title = "Beans",
                actions = listOf(
                    BarAction("upload-simple") { importLauncher.launch(arrayOf("*/*")) },
                    BarAction("download-simple") { exportSheet = true },
                    BarAction("gear-six") { onNav("settings") },
                ),
            )
        },
        floatingActionButton = {
            CremaNewFab(label = "New") {
                if (tab == "bags") { vm.startNewBean(); onNav("bean-edit") }
                else { vm.startEditRoaster(null); onNav("roaster-edit") }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            PhoneTabs(
                options = listOf("bags" to "Bags", "roasters" to "Roasters"),
                value = tab,
                onChange = { tab = it },
            )
            if (tab == "bags") {
                Spacer(Modifier.height(6.dp))
                val nonArchived = ui.beans.filter { it.archivedAt == null }
                CremaFilterChipRow(
                    chips = buildList {
                        add(FilterChipSpec("all", "All", ui.beans.size))
                        add(FilterChipSpec("active", "Active", nonArchived.count { !it.isFrozen }))
                        add(FilterChipSpec("favourite", "Favourite", nonArchived.count { it.favourite }, icon = "star"))
                        add(FilterChipSpec("frozen", "Frozen", nonArchived.count { it.isFrozen }))
                        add(FilterChipSpec("archived", "Archived", ui.beans.count { it.archivedAt != null }))
                        add(FilterChipSpec("light", "Light", nonArchived.count { roastBand(it.roastLevel?.toInt()).equals("light", true) }))
                        add(FilterChipSpec("medium", "Medium", nonArchived.count { roastBand(it.roastLevel?.toInt()).equals("medium", true) }))
                        add(FilterChipSpec("dark", "Dark", nonArchived.count { roastBand(it.roastLevel?.toInt()).equals("dark", true) }))
                    },
                    selected = filter,
                    onSelect = { filter = it },
                    trailing = {
                        CremaSortControl(
                            keys = listOf(
                                SortKey("freshest", "Freshest", "clock"),
                                SortKey("name", "Name", "sort-ascending"),
                                SortKey("roast", "Roast", "fire"),
                                SortKey("rating", "Rating", "star"),
                                SortKey("remaining", "Remaining", "gauge"),
                            ),
                            selectedKey = sort,
                            descending = sortDesc,
                            onKeyChange = { sort = it },
                            onToggleDirection = { sortDesc = !sortDesc },
                        )
                    },
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = CremaEdge, end = CremaEdge, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    CremaPhoneSearch(query = query, onQueryChange = { query = it }, placeholder = "Search beans & roasters")
                }
                if (tab == "bags") {
                    val groups = listOf(
                        Triple("active", "Active") { b: Bean -> b.archivedAt == null && !b.isFrozen },
                        Triple("frozen", "Frozen") { b: Bean -> b.archivedAt == null && b.isFrozen },
                        Triple("archived", "Archived") { b: Bean -> b.archivedAt != null },
                    )
                    groups.forEach { (key, label, predicate) ->
                        val group = sortedBeans.filter(predicate)
                        if (group.isEmpty()) return@forEach
                        item(key = "sec-$key") {
                            Row(
                                Modifier.padding(start = 2.dp, top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Eyebrow(label)
                                Text(
                                    "${group.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                        }
                        items(group, key = { it.id }) { bean ->
                            PhoneBeanTile(
                                bean = bean,
                                roasterName = roasterNameOf(bean),
                                isActive = bean.id == ui.activeBeanId,
                                onPrimary = {
                                    if (bean.archivedAt != null) vm.unarchiveBean(bean.id)
                                    else vm.setActiveBean(bean.id)
                                },
                                onEdit = { vm.startEditBean(bean.id); onNav("bean-edit") },
                                onMenu = { menuFor = bean },
                            )
                        }
                    }
                    if (sortedBeans.isEmpty()) {
                        item {
                            Text(
                                if (ui.beans.isEmpty()) "No beans yet — add a bag to get started." else "No beans match your search or filters.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    }
                } else {
                    items(visibleRoasters, key = { it.id }) { roaster ->
                        PhoneRoasterRow(
                            roaster = roaster,
                            bagCount = ui.beans.count { it.roasterId == roaster.id && it.archivedAt == null },
                            onClick = { vm.startEditRoaster(roaster.id); onNav("roaster-edit") },
                        )
                    }
                    if (visibleRoasters.isEmpty()) {
                        item {
                            Text(
                                if (ui.roasters.isEmpty()) "No roasters yet — add one to group your bags." else "No roasters match your search.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // Export-format sheet (the tablet's split button, phone-native).
    if (exportSheet) {
        CremaOverflowSheet(
            title = "Export as",
            items = listOf(
                SheetItem("file-text", "Crema backup", sub = "Lossless round-trip — beans and roasters. Re-importable in Crema.") {
                    launchSave("crema-beans.json", vm.beansLibraryJson())
                },
                SheetItem("file-zip", "Beanconqueror", sub = "For sharing with Beanconqueror users. Crema-only fields like tags don't survive.") {
                    launchSave("crema-to-beanconqueror.json", vm.beansBeanconquerorJson())
                },
            ),
            onDismiss = { exportSheet = false },
        )
    }

    // Tile overflow — modal bottom sheet.
    menuFor?.let { b ->
        CremaOverflowSheet(
            title = listOfNotNull(roasterNameOf(b), b.name).joinToString(" · "),
            items = buildList {
                add(SheetItem("star", if (b.favourite) "Unfavourite" else "Favourite") { vm.toggleBeanFavourite(b.id) })
                add(SheetItem("copy", "Duplicate bag") { vm.duplicateBean(b.id) })
                add(SheetItem("snowflake", if (b.isFrozen) "Take out of freezer" else "Move to freezer") {
                    if (b.isFrozen) vm.defrostBean(b.id) else vm.freezeBean(b.id)
                })
                add(SheetItem("archive", if (b.archivedAt != null) "Restore to active" else "Archive") {
                    if (b.archivedAt != null) vm.unarchiveBean(b.id) else vm.archiveBean(b.id)
                })
                add(SheetItem(divider = true))
                add(SheetItem("trash", "Delete bag", danger = true) { confirmDelete = b })
            },
            onDismiss = { menuFor = null },
        )
    }

    confirmDelete?.let { b ->
        CremaConfirmDialog(
            title = "Delete bag?",
            body = "“${b.name}” will be removed from your library. This can’t be undone.",
            confirmLabel = "Delete",
            icon = "trash",
            danger = true,
            onConfirm = { vm.deleteBean(b.id); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
}

// ── Bags / Roasters tabs (proto .pb-tab — text + 3dp primary underline) ──────
@Composable
internal fun PhoneTabs(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = CremaEdge)) {
        options.forEach { (id, label) ->
            val on = value == id
            Column(
                Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onChange(id) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent),
                )
            }
        }
    }
}

// ── Bean tile (proto .pb-tile) ───────────────────────────────────────────────
@Composable
private fun PhoneBeanTile(
    bean: Bean,
    roasterName: String?,
    isActive: Boolean,
    onPrimary: () -> Unit,
    onEdit: () -> Unit,
    onMenu: () -> Unit,
) {
    val tel = CremaTheme.telemetry
    val band = roastBand5(bean.roastLevel?.toInt())
    val days = daysOffRoast(bean.roastedOn)
    val frozen = bean.isFrozen
    val archived = bean.archivedAt != null
    val tileBg =
        if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            .blendOver(MaterialTheme.colorScheme.surfaceContainer)
        else MaterialTheme.colorScheme.surfaceContainer

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = tileBg,
        border = if (isActive) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            // Head: avatar + roaster/name/origin + Active badge or rating stars.
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoasterMarkAvatar(name = roasterName ?: bean.name, sizeDp = 46, cornerDp = 12, fontSize = 17.sp)
                Column(Modifier.weight(1f)) {
                    if (roasterName != null) Text(
                        roasterName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        bean.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val origin = listOfNotNull(
                        bean.origin.country?.takeIf { it.isNotBlank() },
                        bean.origin.region?.takeIf { it.isNotBlank() },
                        bean.origin.processing?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (origin.isNotEmpty()) Text(
                        origin,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
                if (isActive) {
                    Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.primary) {
                        Row(
                            Modifier.height(24.dp).padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            PhIcon("check", sizeDp = 11, tint = MaterialTheme.colorScheme.onPrimary)
                            Text(
                                "ACTIVE",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.4.sp, fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        val r = bean.rating.toInt()
                        (1..5).forEach { n ->
                            PhIcon(
                                if (n <= r) "star-fill" else "star",
                                sizeDp = 14,
                                tint = if (n <= r) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }

            // Pills + freshness line.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                band?.let { RoastPill(it) }
                if (frozen) FrozenPill()
                if (bean.decaf) NeutralPill("DECAF")
                Spacer(Modifier.weight(1f))
                val freshColor = freshnessColor(frozen, bean.roastLevel?.toInt(), days)
                val freshLabel = when {
                    frozen -> "frozen"
                    days == null -> "no roast date"
                    else -> "${days}d off roast"
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(freshColor))
                    Text(
                        freshLabel,
                        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                        color = freshColor,
                    )
                }
            }

            // Burn-down (only when the bag size is known).
            if (bean.bagSize > 0f) {
                val pct = (bean.remaining / bean.bagSize).coerceIn(0f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Text(
                            "REMAINING",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.3.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${bean.remaining.toInt()} / ${bean.bagSize.toInt()} g",
                            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    val barColor = when {
                        bean.remaining <= 0f -> MaterialTheme.colorScheme.outline
                        frozen -> tel.flow
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Box(
                        Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        if (pct > 0f) Box(
                            Modifier.fillMaxWidth(pct).fillMaxHeight()
                                .clip(RoundedCornerShape(999.dp)).background(barColor),
                        )
                    }
                }
            }

            // Actions: Set active / Active bean / Restore + edit + overflow.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    onClick = onPrimary,
                    shape = RoundedCornerShape(999.dp),
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.weight(1f).height(38.dp),
                ) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        PhIcon(
                            if (isActive) "check-circle" else "coffee-bean",
                            sizeDp = 15,
                            tint = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            when {
                                isActive -> "Active bean"
                                archived -> "Restore"
                                else -> "Set active"
                            },
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                            color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                RoundIconButton("pencil-simple", onEdit)
                RoundIconButton("dots-three-vertical", onMenu)
            }
        }
    }
}

@Composable
private fun FrozenPill() {
    val bg = Color(0xFF4A6FA5).copy(alpha = 0.26f)
    Row(
        Modifier.clip(RoundedCornerShape(7.dp)).background(bg).height(23.dp).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PhIcon("snowflake", sizeDp = 11, tint = Color(0xFF8FB0DD))
        Text(
            "FROZEN",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.3.sp, fontWeight = FontWeight.SemiBold),
            color = Color(0xFF8FB0DD),
        )
    }
}

@Composable
private fun NeutralPill(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .height(23.dp).padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.3.sp, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Roaster push row (proto .pb-roaster) ─────────────────────────────────────
@Composable
private fun PhoneRoasterRow(roaster: Roaster, bagCount: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            RoasterMarkAvatar(name = roaster.name, sizeDp = 42, cornerDp = 12, fontSize = 16.sp)
            Column(Modifier.weight(1f)) {
                Text(
                    roaster.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val loc = listOfNotNull(
                    roaster.city?.takeIf { it.isNotBlank() },
                    roaster.country?.takeIf { it.isNotBlank() },
                ).joinToString(", ")
                Text(
                    listOf(loc, "$bagCount active ${if (bagCount == 1) "bag" else "bags"}")
                        .filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            PhIcon("caret-right", sizeDp = 18, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// CSS color-mix equivalent: composite a translucent tint over an opaque base.
private fun Color.blendOver(base: Color): Color = Color(
    red = red * alpha + base.red * (1 - alpha),
    green = green * alpha + base.green * (1 - alpha),
    blue = blue * alpha + base.blue * (1 - alpha),
    alpha = 1f,
)
