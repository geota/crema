package coffee.crema.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coffee.crema.ui.theme.CremaTheme

/*
 * Crema component library — a faithful port of tablet/m3-components.jsx.
 *
 * These wrap stock Material 3 composables and pin them to Crema conventions so
 * screen code stays declarative. Names mirror the JSX (M3Button → CremaButton,
 * M3SegmentedButton → CremaSegmentedButton, …) so porting screens is mechanical.
 *
 * ICONS: the mockup uses Phosphor (regular weight; duotone for telemetry marks).
 * Add a Phosphor source to the project — recommended:
 *     implementation("com.adamglin:phosphor-icon:1.0.0")   // or the Phosphor icon font
 * and replace the PhIcon stub below. Glyph names in screen code are the exact
 * Phosphor names ("scales", "gear-six", "bluetooth", "link-break", …).
 */

// ── Icon (M0 placeholder) ───────────────────────────────────────────────────
// A tinted rounded square so layouts render and screens stay navigable (rail
// items carry text labels). Swap THIS ONE FUNCTION for the real Phosphor
// binding: convert the web's subset fonts (web/src/lib/icons/*.woff2) to .ttf,
// drop them in res/font, and map glyph `name` -> codepoint from phosphor.css,
// rendering Text(codepoint, fontFamily = phosphor). Glyph names in screen code
// are exact Phosphor names ("scales", "gear-six", "bluetooth", "link-break", …).
// sizeDp ∈ {16,20,24,32} per the icon spec.
@Composable
fun PhIcon(name: String, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, sizeDp: Int = 20) {
    Box(
        modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(tint.copy(alpha = 0.22f)),
    )
}

// ── Eyebrow — the small all-caps meta-label above readouts/sections ─────────
// 10.5sp, +0.7 tracking, uppercase, on-surface-variant. Used everywhere.
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(10.5f, androidx.compose.ui.unit.TextUnitType.Sp)),
        color = color,
        modifier = modifier,
    )
}

// ── Buttons ─────────────────────────────────────────────────────────────────
enum class CremaButtonVariant { Filled, Tonal, Outlined, Text }

@Composable
fun CremaButton(
    onClick: () -> Unit,
    variant: CremaButtonVariant = CremaButtonVariant.Filled,
    icon: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false, // text-button error color (e.g. Disconnect / Delete)
    label: String,
) {
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) { PhIcon(icon, sizeDp = 18); Spacer(Modifier.width(8.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
    when (variant) {
        CremaButtonVariant.Filled -> Button(onClick, enabled = enabled, shape = MaterialTheme.shapes.small, content = content)
        CremaButtonVariant.Tonal -> FilledTonalButton(onClick, enabled = enabled, shape = MaterialTheme.shapes.small, content = content)
        CremaButtonVariant.Outlined -> OutlinedButton(onClick, enabled = enabled, shape = MaterialTheme.shapes.small, content = content)
        CremaButtonVariant.Text -> TextButton(
            onClick, enabled = enabled,
            colors = if (danger) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            content = content,
        )
    }
}

// IconButton — standard 48dp touch target. tone: Standard | Filled | Tonal.
enum class CremaIconTone { Standard, Filled, Tonal }

@Composable
fun CremaIconButton(icon: String, onClick: () -> Unit, tone: CremaIconTone = CremaIconTone.Standard) {
    when (tone) {
        CremaIconTone.Standard -> IconButton(onClick) { PhIcon(icon, sizeDp = 24) }
        CremaIconTone.Filled -> FilledIconButton(onClick) { PhIcon(icon, sizeDp = 24) }
        CremaIconTone.Tonal -> FilledTonalIconButton(onClick) { PhIcon(icon, sizeDp = 24) }
    }
}

// ── Card ────────────────────────────────────────────────────────────────────
// Crema "filled" card: surfaceContainer (one step up from the page). On DARK
// there is NO border and NO bottom shadow — elevation is the lighter surface.
// On LIGHT, M3's tonal elevation + a hairline reads correctly. Header pad
// 20×24, body pad 24 — apply paddings inside `content`.
@Composable
fun CremaCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium, // 12dp; step buttons down to 8dp
        color = container,
        content = { Column(content = content) },
    )
}

// ── Switch ──────────────────────────────────────────────────────────────────
@Composable
fun CremaSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
}

// ── Segmented button (single-select) ────────────────────────────────────────
data class SegOption(val id: String, val label: String)

@Composable
fun CremaSegmentedButton(
    options: List<SegOption>,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = value == o.id,
                onClick = { onChange(o.id) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(o.label, maxLines = 1) }
        }
    }
}

// ── Chips ─────────────────────────────────────────────────────────────────
@Composable
fun CremaAssistChip(label: String, icon: String? = null, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) },
        leadingIcon = icon?.let { { PhIcon(it, sizeDp = 18) } })
}

@Composable
fun CremaFilterChip(label: String, selected: Boolean, count: Int? = null, icon: String? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick,
        label = { if (count != null) Text("$label  $count") else Text(label) },
        leadingIcon = icon?.let { { PhIcon(it, sizeDp = 18) } },
    )
}

// ── Telemetry stepper — big touch − / value / + (Brew, Settings, editors) ───
// 56dp tall, mono value. fmt formats the number; presets render below if given.
@Composable
fun CremaStepper(
    label: String? = null,
    value: Double,
    unit: String? = null,
    onChange: (Double) -> Unit,
    step: Double = 0.1,
    min: Double = 0.0,
    max: Double = 100.0,
    fmt: (Double) -> String = { String.format("%.1f", it) },
) {
    Column {
        if (label != null) Eyebrow(label, Modifier.padding(bottom = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalIconButton(onClick = { onChange((value - step).coerceAtLeast(min)) }) { PhIcon("minus") }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmt(value), style = CremaTheme.readout.readoutSm, color = MaterialTheme.colorScheme.onSurface)
                if (unit != null) Text(" $unit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalIconButton(onClick = { onChange((value + step).coerceAtMost(max)) }) { PhIcon("plus") }
        }
    }
}

/*
 * NavigationRail — Crema's custom rail: a "C" wordmark at top, the six
 * destinations as M3 rail items (active-state pill retained), and DE1 + Scale
 * connection status pips pinned at the bottom. Tapping a pip triggers connect /
 * disconnect (onConnect("machine"|"scale")).
 */
data class RailItem(val id: String, val icon: String, val label: String)

private val cremaRailItems = listOf(
    RailItem("brew", "coffee", "Brew"),
    RailItem("profiles", "list-bullets", "Profiles"),
    RailItem("beans", "coffee-bean", "Beans"),
    RailItem("history", "chart-line", "History"),
    RailItem("scale", "scales", "Scale"),
    RailItem("settings", "gear-six", "Settings"),
)

@Composable
fun CremaNavigationRail(
    active: String,
    onNav: (String) -> Unit,
    machineConnected: Boolean = true,
    scaleConnected: Boolean = true,
    onConnect: (String) -> Unit = {},
) {
    NavigationRail(
        header = {
            // Brand "C" mark
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.padding(top = 12.dp).size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("C", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        },
    ) {
        Spacer(Modifier.height(8.dp))
        cremaRailItems.forEach { item ->
            NavigationRailItem(
                selected = active == item.id,
                onClick = { onNav(item.id) },
                icon = { PhIcon(item.icon, sizeDp = 24) },
                label = { Text(item.label, style = MaterialTheme.typography.labelMedium) },
            )
        }
        Spacer(Modifier.weight(1f))
        // Connection pips
        ConnectionPip("DE1", machineConnected) { onConnect("machine") }
        ConnectionPip("Scale", scaleConnected) { onConnect("scale") }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ConnectionPip(label: String, connected: Boolean, onClick: () -> Unit) {
    val tel = CremaTheme.telemetry
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
        Box(Modifier.size(8.dp).then(Modifier), contentAlignment = Alignment.Center) {
            Surface(shape = CircleShape, color = if (connected) tel.success else MaterialTheme.colorScheme.outline, modifier = Modifier.size(8.dp)) {}
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(4.dp))
        PhIcon(if (connected) "check" else "bluetooth", sizeDp = 16)
    }
}
