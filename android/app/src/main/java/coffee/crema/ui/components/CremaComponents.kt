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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coffee.crema.ui.theme.CremaTheme
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowCounterClockwise
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.Bluetooth
import com.adamglin.phosphoricons.regular.Bug
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Coffee
import com.adamglin.phosphoricons.regular.Copy
import com.adamglin.phosphoricons.regular.GearSix
import com.adamglin.phosphoricons.regular.LinkBreak
import com.adamglin.phosphoricons.regular.Minus
import com.adamglin.phosphoricons.regular.PencilSimple
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.Power
import com.adamglin.phosphoricons.regular.Question
import com.adamglin.phosphoricons.regular.SlidersHorizontal
import com.adamglin.phosphoricons.regular.SpeakerHigh
import com.adamglin.phosphoricons.regular.Timer
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.X
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.CheckCircle
import com.adamglin.phosphoricons.regular.DotsThreeVertical
import com.adamglin.phosphoricons.regular.Export
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.Shapes
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.Star
import com.adamglin.phosphoricons.regular.UploadSimple
import com.adamglin.phosphoricons.regular.ListBullets
import com.adamglin.phosphoricons.regular.CoffeeBean
import com.adamglin.phosphoricons.regular.ChartLine
import com.adamglin.phosphoricons.regular.Scales
import com.adamglin.phosphoricons.regular.BookmarkSimple
import com.adamglin.phosphoricons.regular.Gauge
import com.adamglin.phosphoricons.regular.Drop
import com.adamglin.phosphoricons.regular.DropHalf
import com.adamglin.phosphoricons.regular.Thermometer
import com.adamglin.phosphoricons.regular.ShareNetwork
import com.adamglin.phosphoricons.regular.Wrench
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.Snowflake
import com.adamglin.phosphoricons.regular.Archive
import com.adamglin.phosphoricons.regular.Storefront
import com.adamglin.phosphoricons.regular.Monitor
import com.adamglin.phosphoricons.regular.ArrowCircleUp
import com.adamglin.phosphoricons.regular.ArrowSquareOut
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.ArrowsLeftRight
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.PaperPlaneTilt
import com.adamglin.phosphoricons.regular.Funnel
import com.adamglin.phosphoricons.regular.Sparkle
import com.adamglin.phosphoricons.regular.Wind
import com.adamglin.phosphoricons.regular.Target
import com.adamglin.phosphoricons.regular.Play
import com.adamglin.phosphoricons.regular.Cloud
import com.adamglin.phosphoricons.regular.Stop

/*
 * Crema component library — a faithful port of tablet/m3-components.jsx.
 *
 * These wrap stock Material 3 composables and pin them to Crema conventions so
 * screen code stays declarative. Names mirror the JSX (M3Button → CremaButton,
 * M3SegmentedButton → CremaSegmentedButton, …) so porting screens is mechanical.
 *
 * ICONS: the mockup uses Phosphor (regular weight). Bound via
 *     implementation("com.adamglin:phosphor-icon:1.0.0")
 * in [PhIcon] below — screen code references the exact Phosphor names
 * ("coffee", "gear-six", "bluetooth", "link-break", …).
 */

// ── Icon — Phosphor (regular weight) via com.adamglin:phosphor-icon ──────────
// Screen code references icons by their exact Phosphor kebab-case name; this maps
// each to the library's PascalCase ImageVector accessor. Unknown/typo'd names
// fall back to a question-mark glyph rather than crashing. sizeDp ∈ {16,18,20,24,
// 32} per the icon spec; tint defaults to the current content color.
@Composable
fun PhIcon(name: String, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, sizeDp: Int = 20) {
    val vector: ImageVector = when (name) {
        "arrow-counter-clockwise" -> PhosphorIcons.Regular.ArrowCounterClockwise
        "arrow-left" -> PhosphorIcons.Regular.ArrowLeft
        "bluetooth" -> PhosphorIcons.Regular.Bluetooth
        "bug" -> PhosphorIcons.Regular.Bug
        "check" -> PhosphorIcons.Regular.Check
        "coffee" -> PhosphorIcons.Regular.Coffee
        "copy" -> PhosphorIcons.Regular.Copy
        "gear-six" -> PhosphorIcons.Regular.GearSix
        "link-break" -> PhosphorIcons.Regular.LinkBreak
        "minus" -> PhosphorIcons.Regular.Minus
        "pencil-simple" -> PhosphorIcons.Regular.PencilSimple
        "plus" -> PhosphorIcons.Regular.Plus
        "power" -> PhosphorIcons.Regular.Power
        "sliders-horizontal" -> PhosphorIcons.Regular.SlidersHorizontal
        "speaker-high" -> PhosphorIcons.Regular.SpeakerHigh
        "timer" -> PhosphorIcons.Regular.Timer
        "trash" -> PhosphorIcons.Regular.Trash
        "x" -> PhosphorIcons.Regular.X
        "caret-down" -> PhosphorIcons.Regular.CaretDown
        "caret-right" -> PhosphorIcons.Regular.CaretRight
        "check-circle" -> PhosphorIcons.Regular.CheckCircle
        "dots-three-vertical" -> PhosphorIcons.Regular.DotsThreeVertical
        "export" -> PhosphorIcons.Regular.Export
        "magnifying-glass" -> PhosphorIcons.Regular.MagnifyingGlass
        "shapes" -> PhosphorIcons.Regular.Shapes
        "shuffle" -> PhosphorIcons.Regular.Shuffle
        "star" -> PhosphorIcons.Regular.Star
        "upload-simple" -> PhosphorIcons.Regular.UploadSimple
        "list-bullets" -> PhosphorIcons.Regular.ListBullets
        "coffee-bean" -> PhosphorIcons.Regular.CoffeeBean
        "chart-line" -> PhosphorIcons.Regular.ChartLine
        "scales" -> PhosphorIcons.Regular.Scales
        "bookmark-simple" -> PhosphorIcons.Regular.BookmarkSimple
        "gauge" -> PhosphorIcons.Regular.Gauge
        "drop" -> PhosphorIcons.Regular.Drop
        "drop-half" -> PhosphorIcons.Regular.DropHalf
        "thermometer" -> PhosphorIcons.Regular.Thermometer
        "share-network" -> PhosphorIcons.Regular.ShareNetwork
        "wrench" -> PhosphorIcons.Regular.Wrench
        "info" -> PhosphorIcons.Regular.Info
        "snowflake" -> PhosphorIcons.Regular.Snowflake
        "archive" -> PhosphorIcons.Regular.Archive
        "storefront" -> PhosphorIcons.Regular.Storefront
        "monitor" -> PhosphorIcons.Regular.Monitor
        "arrow-circle-up" -> PhosphorIcons.Regular.ArrowCircleUp
        "arrow-square-out" -> PhosphorIcons.Regular.ArrowSquareOut
        "arrows-clockwise" -> PhosphorIcons.Regular.ArrowsClockwise
        "arrows-left-right" -> PhosphorIcons.Regular.ArrowsLeftRight
        "download-simple" -> PhosphorIcons.Regular.DownloadSimple
        "paper-plane-tilt" -> PhosphorIcons.Regular.PaperPlaneTilt
        "funnel" -> PhosphorIcons.Regular.Funnel
        "sparkle" -> PhosphorIcons.Regular.Sparkle
        "wind" -> PhosphorIcons.Regular.Wind
        "target" -> PhosphorIcons.Regular.Target
        "play" -> PhosphorIcons.Regular.Play
        "cloud" -> PhosphorIcons.Regular.Cloud
        "stop" -> PhosphorIcons.Regular.Stop
        else -> PhosphorIcons.Regular.Question // unknown-name fallback
    }
    Icon(
        imageVector = vector,
        contentDescription = name,
        modifier = modifier.size(sizeDp.dp),
        tint = tint,
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
    modifier: Modifier = Modifier,
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
        CremaButtonVariant.Filled -> Button(onClick, modifier, enabled = enabled, shape = MaterialTheme.shapes.small, content = content)
        CremaButtonVariant.Tonal -> FilledTonalButton(onClick, modifier, enabled = enabled, shape = MaterialTheme.shapes.small, content = content)
        CremaButtonVariant.Outlined -> OutlinedButton(onClick, modifier, enabled = enabled, shape = MaterialTheme.shapes.small, content = content)
        CremaButtonVariant.Text -> TextButton(
            onClick, modifier, enabled = enabled,
            colors = if (danger) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            content = content,
        )
    }
}

// IconButton — standard 48dp touch target. tone: Standard | Filled | Tonal.
enum class CremaIconTone { Standard, Filled, Tonal }

@Composable
fun CremaIconButton(icon: String, onClick: () -> Unit, modifier: Modifier = Modifier, tone: CremaIconTone = CremaIconTone.Standard) {
    when (tone) {
        CremaIconTone.Standard -> IconButton(onClick, modifier) { PhIcon(icon, sizeDp = 24) }
        CremaIconTone.Filled -> FilledIconButton(onClick, modifier) { PhIcon(icon, sizeDp = 24) }
        CremaIconTone.Tonal -> FilledTonalIconButton(onClick, modifier) { PhIcon(icon, sizeDp = 24) }
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
fun CremaSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
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
fun CremaAssistChip(label: String, modifier: Modifier = Modifier, icon: String? = null, onClick: () -> Unit) {
    AssistChip(onClick = onClick, modifier = modifier, label = { Text(label) },
        leadingIcon = icon?.let { { PhIcon(it, sizeDp = 18) } })
}

@Composable
fun CremaFilterChip(label: String, selected: Boolean, modifier: Modifier = Modifier, count: Int? = null, icon: String? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick,
        modifier = modifier,
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
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
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
