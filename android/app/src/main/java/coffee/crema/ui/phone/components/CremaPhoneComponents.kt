package coffee.crema.ui.phone.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ui.components.PhIcon          // shared with tablet — the Phosphor binding

/*
 * Crema PHONE component library — a faithful port of the prototype/phone JSX.
 *
 * These are the handset-only primitives. They build ON TOP of the shared
 * tablet library (coffee.crema.ui.components.* — CremaButton, CremaSwitch,
 * CremaCard, CremaStepper, CremaSegmentedButton, Eyebrow, PhIcon …) and the
 * shared theme (coffee.crema.ui.theme.* — CremaTheme, colors, type, shape).
 * Reuse those verbatim; only the CHROME differs on phone:
 *
 *   tablet  →  phone
 *   ───────────────────────────────────────────────────────────────
 *   NavigationRail (6 dests + pips)  →  NavigationBar (5 dests; Settings = top-bar gear)
 *   two-column list+detail           →  single column + PUSH detail (back arrow)
 *   inline action bars               →  bottom-sheet overflow (CremaOverflowSheet)
 *   header buttons                   →  top app bar actions + extended FAB
 *   24dp edge insets                 →  16dp edge insets (CremaEdge)
 */

val CremaEdge = 16.dp   // phone edge inset (tablet is 24dp)

// ════════════════════════════════════════════════════════════════════════════
// BOTTOM NAVIGATION  (prototype: PfNav — `.pf-nav`)
// Five destinations. NOTE: Settings is intentionally NOT a bottom-nav item — it
// is reached from each screen's top-app-bar gear. Brew's active icon is the
// regular weight; the others fill when active (matches the prototype).
// ════════════════════════════════════════════════════════════════════════════
data class PhoneDest(val route: String, val icon: String, val label: String)

val CremaPhoneDestinations = listOf(
    PhoneDest("brew", "coffee", "Brew"),
    PhoneDest("scale", "scales", "Scale"),
    PhoneDest("profiles", "list-bullets", "Profiles"),
    PhoneDest("beans", "coffee-bean", "Beans"),
    PhoneDest("history", "chart-line", "History"),
)

@Composable
fun CremaBottomNav(active: String, onNav: (String) -> Unit) {
    Column {
        // Proto .pf-nav: hairline above a surface-container-low bar.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            CremaPhoneDestinations.forEach { d ->
                val selected = active == d.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { onNav(d.route) },
                    icon = {
                        // Brew stays outline even when active; other tabs fill when active.
                        val filled = selected && d.route != "brew"
                        PhIcon(if (filled) "${d.icon}-fill" else d.icon, sizeDp = 24)
                    },
                    label = { Text(d.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// TOP APP BARS
// Two shapes:
//   • CremaPhoneTopBar  — a destination's bar: big serif title (left) + action
//     icons (right). The trailing gear navigates to Settings.   (prototype: .pf-appbar)
//   • CremaPhoneBackBar — a pushed screen's bar: back arrow + title (+ subtitle)
//     + optional trailing actions.   (prototype: .ph-detail-bar / .pst-dbar / .pe-bar)
// ════════════════════════════════════════════════════════════════════════════

data class BarAction(val icon: String, val accent: Boolean = false, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CremaPhoneTopBar(title: String, actions: List<BarAction> = emptyList()) {
    TopAppBar(
        title = {
            // Proto .pf-appbar-title: 26px/30px Newsreader regular.
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 30.sp),
            )
        },
        actions = {
            actions.forEach { a ->
                IconButton(onClick = a.onClick) {
                    PhIcon(
                        a.icon, sizeDp = 22,
                        tint = if (a.accent) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CremaPhoneBackBar(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onBack) { PhIcon("arrow-left", sizeDp = 24) } },
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

// ════════════════════════════════════════════════════════════════════════════
// EXTENDED FAB  (prototype: .pp-fab "＋ New")
// Sits above the bottom nav on library screens (Profiles, Beans).
// ════════════════════════════════════════════════════════════════════════════
@Composable
fun CremaNewFab(label: String = "New", onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { PhIcon("plus", sizeDp = 20) },
        text = { Text(label) },
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

// ════════════════════════════════════════════════════════════════════════════
// HORIZONTAL FILTER-CHIP STRIP  (prototype: .pp-filters / .ph-filters)
// The tablet's measured "overflow into More" filter bar simplifies on phone to a
// single horizontally-scrolling row of bordered chips with trailing counts
// (proto .pp-chip: 1dp outline pill; selected → secondaryContainer, no border).
// ════════════════════════════════════════════════════════════════════════════
data class FilterChipSpec(val id: String, val label: String, val count: Int? = null, val icon: String? = null)

@Composable
fun CremaPhoneChip(label: String, selected: Boolean, count: Int? = null, icon: String? = null, onClick: () -> Unit) {
    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.height(34.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) PhIcon(icon, sizeDp = 14, tint = fg)
            Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = fg)
            if (count != null) Text(
                "$count",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = fg.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
fun CremaFilterChipRow(
    chips: List<FilterChipSpec>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = CremaEdge, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEach { c ->
            CremaPhoneChip(
                label = c.label,
                selected = selected == c.id,
                count = c.count,
                icon = c.icon,
                onClick = { onSelect(c.id) },
            )
        }
        trailing?.invoke()
    }
}

// ════════════════════════════════════════════════════════════════════════════
// SEARCH FIELD  (prototype: .pp-search / .ph-search — 46dp full pill)
// ════════════════════════════════════════════════════════════════════════════
@Composable
fun CremaPhoneSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PhIcon("magnifying-glass", sizeDp = 19, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                PhIcon(
                    "x", sizeDp = 16, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(CircleShape).clickable { onQueryChange("") },
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// OVERFLOW BOTTOM SHEET  (prototype: phone-ui.jsx `OverflowSheet` — `.pm-sheet`)
// Tapping a card's "…" opens a modal bottom sheet of the actions that don't fit
// inline. `SheetItem(divider = true)` renders a separator. `danger` → error tint.
// Used by Profiles, Beans, History, and the Brew profile menu.
// ════════════════════════════════════════════════════════════════════════════
data class SheetItem(
    val icon: String = "",
    val label: String = "",
    val sub: String? = null,
    val danger: Boolean = false,
    val divider: Boolean = false,
    val onClick: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CremaOverflowSheet(
    title: String?,
    items: List<SheetItem>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 10.dp).padding(bottom = 16.dp)) {
            if (title != null) Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 8.dp),
            )
            items.forEach { it ->
                if (it.divider) {
                    HorizontalDivider(
                        Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                } else {
                    val tint = if (it.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { it.onClick(); onDismiss() }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(15.dp),
                    ) {
                        PhIcon(
                            it.icon, sizeDp = 21,
                            tint = if (it.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(it.label, style = MaterialTheme.typography.bodyLarge, color = tint)
                            if (it.sub != null) Text(
                                it.sub, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// PUSH LIST ROW  (prototype: .pst-item — Settings section list)
// A tappable row with a leading icon, title + sub, and a trailing caret. The
// handset's stand-in for the tablet's left-hand section nav: tapping it PUSHES
// the detail view. Reuse for any "list that opens a sub-page" pattern.
// ════════════════════════════════════════════════════════════════════════════
@Composable
fun CremaPushRow(icon: String, title: String, sub: String? = null, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PhIcon(icon, sizeDp = 22, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PhIcon("caret-right", sizeDp = 18, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// SETTINGS GROUP  (prototype: SGroup — `.pst-group`)
// Wraps a settings detail section; rows inside are the shared CremaSettingsRow:
//   SettingsGroup("Targets") { CremaSettingsRow("Default dose", "…") { CremaStepper(...) } }
// ════════════════════════════════════════════════════════════════════════════
@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = CremaEdge, vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}
