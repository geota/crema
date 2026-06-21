package coffee.crema.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.TextStyle
import coffee.crema.ui.theme.Newsreader
import coffee.crema.ui.theme.HankenGrotesk
import coffee.crema.ui.theme.JetBrainsMono
import androidx.compose.ui.res.painterResource
import coffee.crema.R
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.TelemetrySample
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowCounterClockwise
import com.adamglin.phosphoricons.regular.ArrowDown
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.ArrowUp
import com.adamglin.phosphoricons.regular.Bluetooth
import com.adamglin.phosphoricons.regular.Bug
import com.adamglin.phosphoricons.regular.Calendar
import com.adamglin.phosphoricons.regular.ChatsCircle
import com.adamglin.phosphoricons.regular.Cube
import com.adamglin.phosphoricons.regular.House
import com.adamglin.phosphoricons.regular.Link
import com.adamglin.phosphoricons.regular.PlugsConnected
import com.adamglin.phosphoricons.regular.SignIn
import com.adamglin.phosphoricons.regular.SignOut
import com.adamglin.phosphoricons.regular.Warning
import com.adamglin.phosphoricons.regular.Cloud
import com.adamglin.phosphoricons.regular.ClockCounterClockwise
import com.adamglin.phosphoricons.regular.CloudArrowDown
import com.adamglin.phosphoricons.regular.CloudArrowUp
import com.adamglin.phosphoricons.regular.CloudCheck
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Clock
import com.adamglin.phosphoricons.regular.Fire
import com.adamglin.phosphoricons.regular.Package
import com.adamglin.phosphoricons.regular.SortAscending
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
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.Moon
import com.adamglin.phosphoricons.fill.Plugs
import com.adamglin.phosphoricons.fill.Sun
import com.adamglin.phosphoricons.fill.Star as StarFilled
import com.adamglin.phosphoricons.fill.Scales as ScalesFilled
import com.adamglin.phosphoricons.fill.ListBullets as ListBulletsFilled
import com.adamglin.phosphoricons.fill.CoffeeBean as CoffeeBeanFilled
import com.adamglin.phosphoricons.fill.ChartLine as ChartLineFilled
import com.adamglin.phosphoricons.regular.ArrowLineDown
import com.adamglin.phosphoricons.regular.ArrowRight
import com.adamglin.phosphoricons.regular.BluetoothConnected
import com.adamglin.phosphoricons.regular.BluetoothSlash
import com.adamglin.phosphoricons.regular.CalendarBlank
import com.adamglin.phosphoricons.regular.Camera
import com.adamglin.phosphoricons.regular.CircleHalf
import com.adamglin.phosphoricons.regular.DeviceMobile
import com.adamglin.phosphoricons.regular.FloppyDisk
import com.adamglin.phosphoricons.regular.List
import com.adamglin.phosphoricons.regular.WifiHigh
import com.adamglin.phosphoricons.Duotone
import com.adamglin.phosphoricons.duotone.FileText
import com.adamglin.phosphoricons.duotone.FileZip
import com.adamglin.phosphoricons.duotone.FileCode
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
        "arrow-down" -> PhosphorIcons.Regular.ArrowDown
        "arrow-left" -> PhosphorIcons.Regular.ArrowLeft
        "arrow-up" -> PhosphorIcons.Regular.ArrowUp
        "bluetooth" -> PhosphorIcons.Regular.Bluetooth
        "bug" -> PhosphorIcons.Regular.Bug
        "calendar" -> PhosphorIcons.Regular.Calendar
        "chats-circle" -> PhosphorIcons.Regular.ChatsCircle
        "clock-counter-clockwise" -> PhosphorIcons.Regular.ClockCounterClockwise
        "cloud" -> PhosphorIcons.Regular.Cloud
        "cloud-arrow-down" -> PhosphorIcons.Regular.CloudArrowDown
        "cloud-arrow-up" -> PhosphorIcons.Regular.CloudArrowUp
        "cloud-check" -> PhosphorIcons.Regular.CloudCheck
        "check" -> PhosphorIcons.Regular.Check
        "cube" -> PhosphorIcons.Regular.Cube
        "house" -> PhosphorIcons.Regular.House
        "link" -> PhosphorIcons.Regular.Link
        "plugs-connected" -> PhosphorIcons.Regular.PlugsConnected
        "sign-in" -> PhosphorIcons.Regular.SignIn
        "sign-out" -> PhosphorIcons.Regular.SignOut
        "clock" -> PhosphorIcons.Regular.Clock
        "fire" -> PhosphorIcons.Regular.Fire
        "package" -> PhosphorIcons.Regular.Package
        "sort-ascending" -> PhosphorIcons.Regular.SortAscending
        "coffee" -> PhosphorIcons.Regular.Coffee
        "copy" -> PhosphorIcons.Regular.Copy
        "gear-six" -> PhosphorIcons.Regular.GearSix
        "link-break" -> PhosphorIcons.Regular.LinkBreak
        "minus" -> PhosphorIcons.Regular.Minus
        "pencil-simple" -> PhosphorIcons.Regular.PencilSimple
        "plus" -> PhosphorIcons.Regular.Plus
        "power" -> PhosphorIcons.Regular.Power
        "file-text" -> PhosphorIcons.Duotone.FileText
        "file-zip" -> PhosphorIcons.Duotone.FileZip
        "file-code" -> PhosphorIcons.Duotone.FileCode
        "plugs" -> PhosphorIcons.Fill.Plugs
        "moon" -> PhosphorIcons.Fill.Moon
        "sun" -> PhosphorIcons.Fill.Sun
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
        "star-fill" -> PhosphorIcons.Fill.StarFilled
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
        "warning" -> PhosphorIcons.Regular.Warning
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
        "stop" -> PhosphorIcons.Regular.Stop
        // Phone (handset) chrome + screens.
        "scales-fill" -> PhosphorIcons.Fill.ScalesFilled
        "list-bullets-fill" -> PhosphorIcons.Fill.ListBulletsFilled
        "coffee-bean-fill" -> PhosphorIcons.Fill.CoffeeBeanFilled
        "chart-line-fill" -> PhosphorIcons.Fill.ChartLineFilled
        "arrow-line-down" -> PhosphorIcons.Regular.ArrowLineDown
        "arrow-right" -> PhosphorIcons.Regular.ArrowRight
        "bluetooth-connected" -> PhosphorIcons.Regular.BluetoothConnected
        "bluetooth-slash" -> PhosphorIcons.Regular.BluetoothSlash
        "calendar-blank" -> PhosphorIcons.Regular.CalendarBlank
        "camera" -> PhosphorIcons.Regular.Camera
        "circle-half" -> PhosphorIcons.Regular.CircleHalf
        "device-mobile" -> PhosphorIcons.Regular.DeviceMobile
        "floppy-disk" -> PhosphorIcons.Regular.FloppyDisk
        "list" -> PhosphorIcons.Regular.List
        "wifi-high" -> PhosphorIcons.Regular.WifiHigh
        else -> PhosphorIcons.Regular.Question // unknown-name fallback
    }
    Icon(
        imageVector = vector,
        contentDescription = name,
        modifier = modifier.size(sizeDp.dp),
        tint = tint,
    )
}

/**
 * The one 5-star rating row (issue 36) — was hand-rolled at ~8 sites across both
 * shells with byte-divergent sizes/tints. `onChange == null` is the read-only
 * variant (plain stars, no touch target); otherwise each star gets a circular
 * [touchDp] tap area and tapping the current value clears to 0 (the editors'
 * shared behaviour). `starDp`/[emptyTint] are configurable so a compact inline
 * row (history list, bean card) and a full editor control share one impl.
 */
@Composable
fun CremaStarRating(
    value: Int,
    modifier: Modifier = Modifier,
    onChange: ((Int) -> Unit)? = null,
    starDp: Int = 22,
    touchDp: Int = 40,
    spacingDp: Int = if (onChange != null) 2 else 1,
    filledTint: Color = MaterialTheme.colorScheme.primary,
    emptyTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    max: Int = 5,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacingDp.dp)) {
        (1..max).forEach { n ->
            val icon = if (n <= value) "star-fill" else "star"
            val tint = if (n <= value) filledTint else emptyTint
            if (onChange != null) {
                Box(
                    Modifier.size(touchDp.dp).clip(CircleShape).clickable { onChange(if (n == value) 0 else n) },
                    contentAlignment = Alignment.Center,
                ) { PhIcon(icon, sizeDp = starDp, tint = tint) }
            } else {
                PhIcon(icon, sizeDp = starDp, tint = tint)
            }
        }
    }
}

/**
 * CremaSparkChart — the tiny multi-channel shot silhouette for history list rows
 * (issue 34, was `SparkChart` on tablet ≡ `PhoneSpark` on phone, byte-identical
 * but for stroke/inset). Web `.hi-row` mini chart: temp + weight + flow behind,
 * pressure on top, each channel min-max normalised to the box on its own scale
 * (preserveAspectRatio: none — both axes stretch to fill). Series with <2 samples
 * draw nothing (the caller still sizes the box so row heights stay uniform).
 * [insetDp] keeps the round caps off the rounded corners; stroke widths are
 * per-channel so the denser phone row can run slightly thinner.
 */
@Composable
fun CremaSparkChart(
    samples: List<TelemetrySample>,
    modifier: Modifier = Modifier,
    insetDp: Float = 2f,
    tempStroke: Float = 1.1f,
    weightStroke: Float = 1.3f,
    flowStroke: Float = 1.3f,
    pressureStroke: Float = 1.8f,
) {
    val tel = CremaTheme.telemetry
    Canvas(modifier) {
        if (samples.size < 2) return@Canvas
        val firstT = samples.first().elapsedMs.toFloat()
        val tSpan = (samples.last().elapsedMs.toFloat() - firstT).takeIf { it > 0f } ?: 1f
        val inset = insetDp.dp.toPx()
        val plotW = (size.width - inset * 2f).coerceAtLeast(1f)
        val plotH = (size.height - inset * 2f).coerceAtLeast(1f)
        fun channel(color: Color, widthDp: Float, value: (TelemetrySample) -> Float?) {
            var mn = Float.POSITIVE_INFINITY
            var mx = Float.NEGATIVE_INFINITY
            samples.forEach { s -> value(s)?.let { v -> if (v < mn) mn = v; if (v > mx) mx = v } }
            val span = (mx - mn).takeIf { it > 0f } ?: return
            val path = Path()
            var started = false
            samples.forEach { s ->
                val v = value(s) ?: return@forEach
                val x = inset + ((s.elapsedMs.toFloat() - firstT) / tSpan) * plotW
                val y = inset + (1f - (v - mn) / span) * plotH
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = widthDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        channel(tel.temp.copy(alpha = 0.75f), tempStroke) { it.headTemp }
        channel(tel.weight.copy(alpha = 0.9f), weightStroke) { it.weight }
        channel(tel.flow.copy(alpha = 0.9f), flowStroke) { it.flow }
        channel(tel.pressure, pressureStroke) { it.pressure }
    }
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
    // Stock M3 pill shape (no shapes.small override) — the handset design uses
    // full pills everywhere, and the tablet now matches it instead of the PWA's
    // squared 8dp buttons.
    when (variant) {
        CremaButtonVariant.Filled -> Button(onClick, modifier, enabled = enabled, content = content)
        CremaButtonVariant.Tonal -> FilledTonalButton(onClick, modifier, enabled = enabled, content = content)
        CremaButtonVariant.Outlined -> OutlinedButton(onClick, modifier, enabled = enabled, content = content)
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

// ── Split button (PWA SplitButton) — a primary action butted against a caret ──
// that opens a format menu: eyebrow head + rows of [duotone icon · title · sub].
data class SplitMenuItem(
    val icon: String,
    val title: String,
    val sub: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
fun CremaSplitButton(
    icon: String,
    label: String,
    menuHead: String,
    items: List<SplitMenuItem>,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(999.dp)
    // Same hairline as M3's OutlinedButton (outlineVariant) so Import / Export
    // read as one family — the old outline@50% was visibly brighter.
    val border = MaterialTheme.colorScheme.outlineVariant
    // onSurfaceVariant — what the stock OutlinedButton beside it (Import)
    // resolves its label to, so the pair share the same off-white.
    val content = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box(modifier) {
        Row(
            // Pin to the M3 button height (40dp) so Export lines up with the
            // OutlinedButton (Import) + filled button (Add) beside it. The halves
            // fill the height, so only horizontal padding shapes them — the old
            // top/bottom-9 padding rendered ~2dp short ("Export is smaller").
            Modifier.clip(shape).border(1.dp, border, shape).height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Primary half.
            Row(
                Modifier
                    .fillMaxHeight()
                    .clickable(enabled = enabled, onClick = onPrimary)
                    .padding(start = 16.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PhIcon(icon, sizeDp = 18, tint = content)
                Text(label, style = MaterialTheme.typography.labelLarge, color = content)
            }
            Box(Modifier.width(1.dp).height(22.dp).background(border))
            // Caret half.
            Box(
                Modifier
                    .fillMaxHeight()
                    .clip(shape)
                    .clickable(enabled = enabled) { open = true }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                PhIcon("caret-down", sizeDp = 13, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 288.dp),
        ) {
            Eyebrow(menuHead, Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp))
            items.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = item.enabled) { item.onClick(); open = false }
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                        .alpha(if (item.enabled) 1f else 0.45f),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    PhIcon(item.icon, sizeDp = 20, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 1.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(item.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
                    }
                }
            }
        }
    }
}

// ── Scale pill — flat 48dp neutral action (surfaceContainerHigh), icon + label.
// The design's secondary action on Scale (Reset peak / Start timer); distinct
// from CremaButton's M3 filled/tonal/outlined/text variants.
@Composable
fun ScalePillButton(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp), // a real pill — matches the handset chrome
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().height(48.dp),
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            PhIcon(icon, sizeDp = 18)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

// ── Library-card geometry — ONE source of truth for the 3 library screens ────
// Profiles / Beans / History cards each hard-coded the same paddings, radius,
// preview height and grid gaps as inline literals (they matched, but would
// silently drift on any edit — the "cards different sizes" risk). Pull them
// here so the set stays provably identical. Values from the proto's v2 card
// anatomy (screens.css). Reserved head/tag rows keep cards in a grid row
// equal-height regardless of active/pinned/tag content.
object CremaCardSpec {
    val radius = 16.dp            // large corner — tablet tiles
    val phoneRadius = 18.dp       // handset large-surface corner (cards, hero panels, dropdown sheets) — intentionally softer than the 16dp tablet tile
    val pad = 16.dp               // card body padding
    val gap = 12.dp               // inter-row gap inside a card
    val gridGap = 16.dp           // gap between cards
    val previewHeight = 96.dp     // curve/figure preview height
    val headMinHeight = 28.dp     // reserved head row (LOADED pill / pin star)
    val tagRowMinHeight = 22.dp   // reserved tag row (empty != collapsed)
    val gridContentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
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
    shape: Shape = MaterialTheme.shapes.medium, // 12dp; pass shapes.large (16) for tiles
    border: BorderStroke? = null, // e.g. active/selected card → 1dp primary
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = container,
        border = border,
        content = { Column(content = content) },
    )
}

// ── Confirm dialog — the single espresso-themed destructive-action confirm ──
// A 1:1 port of the PWA `confirmDialog({ title, message, confirmLabel, danger })`
// contract onto an M3 AlertDialog (M3 gives scrim/elevation/back-dismiss/a11y
// for free; we just theme it + bake in the danger/Cancel pair). Caller owns
// visibility: render inside `if (showConfirm) { … }`, clear the flag in both
// onConfirm and onDismiss. Pass `requireTyped` for the nuclear "Erase all data"
// case → the confirm stays disabled until the user types the exact word.
@Composable
fun CremaConfirmDialog(
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "Confirm",
    cancelLabel: String = "Cancel",
    danger: Boolean = false,
    icon: String? = null,
    requireTyped: String? = null,
) {
    var typed by remember { mutableStateOf("") }
    val confirmEnabled = requireTyped == null || typed.trim() == requireTyped
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shape = MaterialTheme.shapes.large,
        icon = icon?.let {
            { PhIcon(it, sizeDp = 24, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        },
        title = { Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (requireTyped != null) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        label = { Text("Type “$requireTyped” to confirm") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            CremaButton(onClick = onConfirm, variant = CremaButtonVariant.Text, danger = danger, enabled = confirmEnabled, label = confirmLabel)
        },
        // Cancel stays neutral (onSurfaceVariant) so it doesn't compete with the
        // warm copper/rose confirm — the danger action should be the only tinted one.
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) { Text(cancelLabel, style = MaterialTheme.typography.labelLarge) }
        },
    )
}

// ── Overflow (⋮) menu — circular tonal kebab + bordered anchored popup ───────
// The Android answer to the proto's M3OverflowMenu. Inline icons handle the
// frequent actions on a card; this kebab carries the low-frequency / destructive
// rest (Export, Freeze, Archive, Delete) so the touch row stays short. `danger`
// items render in the error color. Built on [CremaAnchoredPopup] + a bordered
// surfaceContainerHigh card (the same chrome as the Brew header pickers) so the
// menu reads against the dark page instead of vanishing like the stock M3
// DropdownMenu did on same-tone backgrounds.
data class OverflowItem(
    val icon: String,
    val label: String,
    val onClick: () -> Unit,
    val danger: Boolean = false,
    val enabled: Boolean = true,
)

@Composable
fun CremaOverflowMenu(items: List<OverflowItem>, icon: String = "dots-three-vertical") {
    var open by remember { mutableStateOf(false) }
    Box {
        // Circular tonal trigger — matches the card's other FilledTonalIconButtons
        // (the bare IconButton had no circle and read as a stray glyph).
        FilledTonalIconButton(onClick = { open = true }) { PhIcon(icon, sizeDp = 20) }
        CremaAnchoredPopup(expanded = open, onDismiss = { open = false }) {
            CremaMenuSurface(Modifier.widthIn(min = 200.dp, max = 280.dp)) {
                items.forEach { item ->
                    CremaMenuItem(
                        label = item.label,
                        onClick = { open = false; item.onClick() },
                        leadingIcon = item.icon,
                        danger = item.danger,
                        enabled = item.enabled,
                    )
                }
            }
        }
    }
}

// ── Anchored popup — a bordered card pinned just below a tap anchor ──────────
// The reusable substrate for the Brew header pickers (proto .bh-pop) and any
// similar "tap a block → bordered popover card" surface. Unlike M3's
// ExposedDropdownMenu it (a) carries NO chrome of its own — the caller supplies
// the bordered Surface so the card reads against same-tone backgrounds, and
// (b) lets the caller bound height + scroll + pin a footer internally.
//
// Positioning: the popup's top-left is placed at the anchor's bottom-left plus
// an 8dp gap (proto `top: calc(100% + 8px); left: 0`). If the card would spill
// past the window bottom it flips ABOVE the anchor (its bottom 8dp above the
// anchor top); the x is clamped so the card stays on-screen. focusable = true
// so the first tap outside dismisses (onDismiss) and back closes it.
//
// USAGE: wrap the anchor block + this call in a `Box` so the Popup anchors to
// that Box's bounds, e.g.
//   Box { AnchorBlock(); CremaAnchoredPopup(open, { open = false }) { Card() } }
@Composable
fun CremaAnchoredPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    gap: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    if (!expanded) return
    val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { gap.roundToPx() }
    val provider = remember(gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                // x: left-align to the anchor, clamped on-screen.
                val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                val x = anchorBounds.left.coerceIn(0, maxX)
                // y: below the anchor; flip above if it would overflow the bottom.
                val below = anchorBounds.bottom + gapPx
                val y = if (below + popupContentSize.height <= windowSize.height) {
                    below
                } else {
                    (anchorBounds.top - gapPx - popupContentSize.height)
                        .coerceAtLeast(0)
                }
                return IntOffset(x, y)
            }
        }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        // A no-op Box wrapper so callers can pass a sizing/test modifier; the
        // card itself (Surface + border) is supplied by `content`.
        Box(modifier) { content() }
    }
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
    enabled: Boolean = true,
    /** Denser pill for tight rows (e.g. the phone phase editor): shorter, tighter
     *  padding, smaller label, no check icon (selection reads from the fill), and
     *  equal-width segments. Give the caller's [modifier] a fixed width (e.g.
     *  `Modifier.width(176.dp)`) so a set of compact pills line up. */
    compact: Boolean = false,
    /** Uniform segments. With [groupWidth] null the row self-sizes to its own
     *  widest segment × count; pass a [groupWidth] to pin every toggle in a
     *  settings group to one shared width so they all line up (the weighted
     *  segments split it evenly). Either way the selection check icon is dropped
     *  so the width never jitters. Used by the settings toggles. */
    uniform: Boolean = false,
    groupWidth: Dp? = null,
    /** Stretch the segments to fill the available width — for a stacked settings
     *  row where the pill sits full-width under its title. */
    fillWidth: Boolean = false,
) {
    val even = uniform || groupWidth != null || fillWidth
    val rowMod = when {
        fillWidth -> modifier.fillMaxWidth()
        groupWidth != null -> modifier.width(groupWidth)
        uniform -> modifier.width(IntrinsicSize.Max)
        else -> modifier
    }
    SingleChoiceSegmentedButtonRow(rowMod) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                enabled = enabled,
                selected = value == o.id,
                onClick = { onChange(o.id) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                modifier = when {
                    compact -> Modifier.weight(1f).requiredHeight(32.dp)
                    even -> Modifier.weight(1f)
                    else -> Modifier
                },
                contentPadding = if (compact) PaddingValues(horizontal = 10.dp, vertical = 0.dp) else SegmentedButtonDefaults.ContentPadding,
                icon = if (compact || even) ({}) else ({ SegmentedButtonDefaults.Icon(value == o.id) }),
                label = {
                    if (compact) Text(o.label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    else Text(o.label, maxLines = 1)
                },
            )
        }
    }
}

// A compact split (segmented) switch with a per-segment count badge — the Beans
// page's Bags/Roasters toggle. A bordered pill groups the segments into one
// unit; the active segment gets a neutral wash and its count pill turns copper,
// the same treatment as CremaFilterChip. Shorter than the M3 SegmentedButton.
data class TabOption(val id: String, val label: String, val count: Int? = null)

@Composable
fun CremaTabSwitch(
    options: List<TabOption>,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(999.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { o ->
            val selected = value == o.id
            val fg = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent)
                    .clickable { onChange(o.id) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    o.label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
                    color = fg,
                )
                if (o.count != null) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            "${o.count}",
                            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, fontFeatureSettings = "tnum"),
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
    }
}

// ── Chips ─────────────────────────────────────────────────────────────────
@Composable
fun CremaFilterChip(label: String, selected: Boolean, modifier: Modifier = Modifier, count: Int? = null, icon: String? = null, onClick: () -> Unit) {
    // PWA `.pp-tag`: a borderless ghost chip — faint label, subtle wash when
    // active — with a faint count pill (`.pp-tag-count`) that turns copper when
    // selected. NOT the bordered M3 FilterChip.
    val fg = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) PhIcon(icon, sizeDp = 15, tint = fg)
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = fg)
        if (count != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    "$count",
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, fontFeatureSettings = "tnum"),
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
        }
    }
}

// ── Value + unit — mono value with a small, faint, baseline-aligned unit ─────
// The PWA `.pp-metric-val em` / readout-unit pattern: unit is ~0.7× the value,
// dimmed, and baseline-aligned (the "subscript" the cards/history/brew share).
@Composable
fun CremaValueUnit(
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
    valueSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    // Unit size + face vary by context in the PWA: stat tiles use a SANS unit at
    // half the value size (.hi-stat-val em = 11px on 22px); metric/row values use a
    // mono unit ~0.7x. Defaults preserve the original mono 0.72x behaviour.
    unitSize: androidx.compose.ui.unit.TextUnit = valueSize * 0.72f,
    unitSans: Boolean = false,
    // Gap between the value and its unit. A hair wider than the PWA's 1px so the
    // unit (esp. word units like "shots") doesn't crowd the value.
    unitGap: androidx.compose.ui.unit.Dp = 3.dp,
) {
    Row(modifier) {
        Text(
            value,
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = valueSize, fontFeatureSettings = "tnum"),
            color = valueColor,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.alignByBaseline(),
        )
        if (!unit.isNullOrEmpty()) {
            Text(
                unit,
                style = TextStyle(fontFamily = if (unitSans) HankenGrotesk else JetBrainsMono, fontSize = unitSize),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alignByBaseline().padding(start = unitGap),
            )
        }
    }
}

/**
 * Tap-to-edit number readout (web-shell parity — every stepper value is typeable).
 * Shows [content] (the formatted value); tapping it swaps to an inline keyboard
 * field pre-filled with the raw value. On Done or focus-loss it parses (comma-
 * tolerant for es-ES and other comma-decimal locales), clamps to [min]..[max], and
 * commits via [onCommit]. Used by [CremaStepper] and the phone Quick-Controls cell.
 */
@Composable
fun TapToEditValue(
    value: Double,
    min: Double,
    max: Double,
    onCommit: (Double) -> Unit,
    editStyle: TextStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    if (!editing) {
        Box(
            modifier.clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { editing = true },
        ) { content() }
        return
    }
    val focus = remember { FocusRequester() }
    // Only commit on focus LOSS once the field has actually held focus — the field
    // gets an initial onFocusChanged(false) on attach, before requestFocus lands,
    // which would otherwise immediately revert out of edit mode.
    var hadFocus by remember { mutableStateOf(false) }
    var text by remember {
        mutableStateOf(
            if (value == kotlin.math.floor(value)) "%.0f".format(value)
            else (kotlin.math.round(value * 100) / 100.0).toString(),
        )
    }
    fun commit() {
        if (!editing) return
        editing = false
        text.trim().replace(',', '.').toDoubleOrNull()?.let { onCommit(it.coerceIn(min, max)) }
    }
    BasicTextField(
        value = text,
        onValueChange = { new -> text = new.filter { it.isDigit() || it == '.' || it == ',' } },
        singleLine = true,
        enabled = enabled,
        textStyle = editStyle.copy(textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier
            .widthIn(min = 56.dp)
            .focusRequester(focus)
            .onFocusChanged { st -> if (st.isFocused) hadFocus = true else if (hadFocus) commit() },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

// ── Filter-bar group label + divider (PWA `.pp-tag-grouplabel` / `.pp-tag-divider`)
// A dimmed uppercase category label and a short vertical hairline between filter
// groups (STATUS · ROAST · …).
@Composable
fun CremaFilterGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(horizontal = 2.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
    )
}

// A full-height hairline between filter groups. Stretches to the row height
// (PWA .bn-tabs-divider: align-self: stretch) — place inside a Row given a
// bounded height (e.g. Modifier.height(IntrinsicSize.Min)).
@Composable
fun CremaFilterDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
    )
}

// ── Optional-config dot toggle (PWA .pe-num-dot / .qsplit-dot) ───────────────
// A small circular button that enables/disables an optional setting: filled
// copper when ON, a hollow grey ring when OFF. Pair with Modifier.alpha(0.4) on
// the control it gates. The hit target is padded out around the 9dp visual dot.
@Composable
fun CremaDotToggle(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        // 16dp keeps the dot header the same height as a plain eyebrow, so dot
        // and non-dot tiles line up in the Target + Limits row.
        modifier.size(16.dp).clip(CircleShape).clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                .border(
                    1.dp,
                    if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    CircleShape,
                ),
        )
    }
}

// ── Connection status dot — 10dp; ON = success fill, OFF = hairline ring. ─────
// A non-interactive read-out (vs the interactive CremaDotToggle above) — both
// settings shells use it for GATT/scale/grinder connection state.
@Composable
fun CremaStatusDot(on: Boolean, modifier: Modifier = Modifier) {
    if (on) {
        Box(modifier.size(10.dp).clip(CircleShape).background(CremaTheme.telemetry.success))
    } else {
        Box(modifier.size(10.dp).clip(CircleShape).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
    }
}

// ── Empty state — one centred placeholder for the library / log / scale screens.
// Optional [icon] disc on top, the [message] line, an optional [description]
// sentence, and an optional [action] (e.g. an Import button). One type scale for
// the message across both shells (was titleLarge on tablet vs titleSmall on phone).
@Composable
fun CremaEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    description: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(56.dp)) {
                Box(contentAlignment = Alignment.Center) { PhIcon(icon, sizeDp = 28, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Text(
            message,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            action()
        }
    }
}

// Header for an optional config block: the dot toggle + an eyebrow label. The
// caller greys out the gated control with Modifier.alpha(if (on) 1f else 0.4f).
@Composable
fun CremaOptionalHeader(label: String, on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        CremaDotToggle(on, onToggle)
        Eyebrow(label, color = if (on) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
    }
}

// ── Split label (PWA QuickSplitLabel) ────────────────────────────────────────
// An eyebrow-style label that's also a selector: [dot?] PREFIX  OPT | OPT, where
// the active option is copper-underlined. Used for the segment editor's Temp
// (Coffee|Water), Exit (Pressure|Flow) etc. `dot` adds the optional enable toggle.
data class SplitOption(val id: String, val label: String)

@Composable
fun CremaSplitLabel(
    prefix: String,
    modifier: Modifier = Modifier,
    options: List<SplitOption> = emptyList(),
    value: String? = null,
    onChange: ((String) -> Unit)? = null,
    dot: Boolean = false,
    dotOn: Boolean = false,
    onDot: (() -> Unit)? = null,
) {
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    val copper = MaterialTheme.colorScheme.primary
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (dot) CremaDotToggle(dotOn, { onDot?.invoke() })
        // The prefix carries the same 3dp bottom padding the options use to clear
        // their underline, so prefix + options share a baseline (no high options).
        // Blank prefix (e.g. the Dose|Grind header) renders options only.
        if (prefix.isNotBlank()) Text(prefix.uppercase(), style = style, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.padding(bottom = 3.dp))
        if (options.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEachIndexed { i, o ->
                    if (i > 0) Text("|", style = style, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f), modifier = Modifier.padding(bottom = 3.dp))
                    val active = value == o.id
                    Text(
                        o.label.uppercase(),
                        style = style,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) 0.85f else 0.32f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(2.dp))
                            .clickable(enabled = !active && onChange != null) { onChange?.invoke(o.id) }
                            .padding(bottom = 3.dp)
                            .drawBehind {
                                if (active) {
                                    val sw = 1.5.dp.toPx()
                                    drawLine(copper, Offset(0f, size.height - sw), Offset(size.width, size.height - sw), strokeWidth = sw)
                                }
                            },
                    )
                }
            }
        }
    }
}

// ── Text field — label-above + filled input (PWA form style) ─────────────────
// A calmer alternative to M3's floating-label OutlinedTextField for forms: a
// sentence-case label over a filled, 1dp-bordered, 8dp-rounded input box.
@Composable
fun CremaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    /** Blank = no header line (the host row already carries the title). */
    label: String = "",
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            if (value.isEmpty() && placeholder != null) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                minLines = minLines,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ── Date field — read-only filled field that opens a Material date picker ────
// Same chrome as CremaTextField but tap-to-pick. `minDate`/`maxDate` (ISO
// yyyy-MM-dd) constrain the selectable range (e.g. opened ≥ roasted, ≤ today).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CremaDateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "Pick a date",
    minDate: String? = null,
    maxDate: String? = null,
) {
    fun isoToMillis(iso: String): Long? =
        runCatching { LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()

    var open by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    value.ifBlank { placeholder },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (value.isBlank()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface,
                )
                PhIcon("calendar", sizeDp = 16, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (open) {
        val minMs = minDate?.let { isoToMillis(it) }
        val maxMs = maxDate?.let { isoToMillis(it) }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value.ifBlank { null }?.let { isoToMillis(it) },
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    if (minMs != null && utcTimeMillis < minMs) return false
                    if (maxMs != null && utcTimeMillis > maxMs) return false
                    return true
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onValueChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = {
                Row {
                    if (value.isNotBlank()) TextButton(onClick = { onValueChange(""); open = false }) { Text("Clear") }
                    TextButton(onClick = { open = false }) { Text("Cancel") }
                }
            },
        ) { DatePicker(state = state) }
    }
}

// ── Sort control — split pill: direction toggle | key dropdown ──────────────
// The PWA/proto sort affordance, reusable across the app. ONE bordered pill sized
// + shaped like the filter chips (32dp tall, full pill, 1dp outlineVariant),
// split by a hairline into:
//   • a direction toggle (↑ ascending / ↓ descending), and
//   • a key zone (current key + caret) that opens a bordered dropdown of keys.
// So the filter row reads as one cohesive set of chips + sort. Mirrors the web
// FavoritesStrip/ProfileLibrary sort split-button.
data class SortKey(val id: String, val label: String, val icon: String? = null)

@Composable
fun CremaSortControl(
    keys: List<SortKey>,
    selectedKey: String,
    descending: Boolean,
    onKeyChange: (String) -> Unit,
    onToggleDirection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = keys.firstOrNull { it.id == selectedKey } ?: keys.first()
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(999.dp) // pill — sits beside the pill filter chips
    val hairline = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier
            .height(32.dp)
            .clip(shape)
            .border(BorderStroke(1.dp, hairline), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Direction toggle — ↑ ascending / ↓ descending.
        Box(
            Modifier.fillMaxHeight().clip(shape).clickable(onClick = onToggleDirection)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            PhIcon(
                if (descending) "arrow-down" else "arrow-up",
                sizeDp = 15,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(hairline))
        // Key zone — current key + caret → bordered dropdown.
        Box {
            Row(
                Modifier.fillMaxHeight().clickable { menuOpen = true }
                    .padding(start = 12.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                current.icon?.let { PhIcon(it, sizeDp = 14, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                // onSurfaceVariant — the same off-white as its icons and the
                // outlined Import/Export pair (onSurface read as bright white).
                Text(current.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PhIcon("caret-down", sizeDp = 13, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CremaAnchoredPopup(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                CremaMenuSurface(Modifier.widthIn(min = 172.dp, max = 240.dp)) {
                    keys.forEach { key ->
                        CremaMenuItem(
                            label = key.label,
                            onClick = { menuOpen = false; onKeyChange(key.id) },
                            leadingIcon = key.icon,
                            active = key.id == selectedKey,
                            showCheck = true,
                        )
                    }
                }
            }
        }
    }
}

// ── Filter dropdown — CremaSortControl's sibling for picking a FILTER value ──
// Same split-pill anatomy (icon zone · hairline · current value + caret →
// bordered dropdown), minus the direction toggle: the left half is a static
// glyph naming the dimension (e.g. calendar = date range). Replaces a row of
// mutually-exclusive filter pills when the choice is low-frequency.
@Composable
fun CremaFilterDropdown(
    icon: String,
    keys: List<SortKey>,
    selectedKey: String,
    onKeyChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = keys.firstOrNull { it.id == selectedKey } ?: keys.first()
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(999.dp)
    val hairline = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier
            .height(32.dp)
            .clip(shape)
            .border(BorderStroke(1.dp, hairline), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            PhIcon(icon, sizeDp = 15, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(hairline))
        Box {
            Row(
                Modifier.fillMaxHeight().clickable { menuOpen = true }
                    .padding(start = 12.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(current.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PhIcon("caret-down", sizeDp = 13, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CremaAnchoredPopup(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                CremaMenuSurface(Modifier.widthIn(min = 172.dp, max = 240.dp)) {
                    keys.forEach { key ->
                        CremaMenuItem(
                            label = key.label,
                            onClick = { menuOpen = false; onKeyChange(key.id) },
                            leadingIcon = key.icon,
                            active = key.id == selectedKey,
                            showCheck = true,
                        )
                    }
                }
            }
        }
    }
}

// ── Menu surface + item — the one polished popup-menu style ──────────────────
// The shared chrome for every Crema popup menu (sort dropdown, card overflow,
// and the Brew header pickers' option rows): a bordered surfaceContainerHigh
// card whose rows are individually rounded, with a copper-tinted ACTIVE row
// (the M3 "selected" container) + hover/press feedback, instead of flat text on
// a flat fill. Mirrors the web `.sortpill-menu` / `.sb-menu` (rounded rows,
// copper active wash). Pass the card a width via `modifier` (widthIn).
@Composable
fun CremaMenuSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

// One menu row. `active` paints the copper "selected" wash + copper content (and,
// with `showCheck`, a trailing tick); `danger` paints the error color. Each row
// is its own rounded, ripple-clipped hit target (M3 min 48dp tall).
@Composable
fun CremaMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: String? = null,
    active: Boolean = false,
    danger: Boolean = false,
    showCheck: Boolean = false,
    enabled: Boolean = true,
) {
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        danger -> MaterialTheme.colorScheme.error
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val rowShape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp) // M3 min menu-item touch target
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            PhIcon(leadingIcon, sizeDp = 18, tint = if (active || danger || !enabled) content else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(label, color = content, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (showCheck && active) PhIcon("check", sizeDp = 16, tint = MaterialTheme.colorScheme.primary)
    }
}

// ── Stepper — one −/value/+ control for every shell surface. ──────────────────
// The numeric core (step/min/max, 2-decimal snap, optional quick-select chips) is
// identical everywhere; only the *look* changes, captured by CremaStepperStyle.
// Presets (Telemetry / Boxed / BoxedDense / Bare / BareCompact) cover the brew &
// scale readouts, the profile editors, Quick Controls, and both settings shells,
// so a call site picks a named style instead of re-deriving pixels. This replaced
// eight hand-rolled copies (Edit/StepperBox/Qc/Set/P/Ed steppers + their buttons).

/** Filled box drawn behind the whole −/value/+ bar (boxed variants); None = bare. */
enum class StepperFill { None, High, Highest }

@Immutable
data class CremaStepperStyle(
    val tonalButtons: Boolean = false, // true ⇒ FilledTonalIconButton (telemetry); else circle Surface
    val box: StepperFill = StepperFill.None,
    val boxShape: Dp = 10.dp,
    val boxPadding: PaddingValues = PaddingValues(0.dp),
    val buttonSize: Dp = 34.dp, // ignored when tonalButtons
    val iconSize: Int = 14,
    val valueSize: TextUnit = 15.sp, // ignored when tonalButtons (uses readoutSm)
    val fillWidth: Boolean = false, // weight the value + SpaceBetween the buttons
    val spacedBy: Dp = 8.dp, // gap when NOT fillWidth
    val valueMinWidth: Dp = 0.dp, // floor for the bare value column
) {
    companion object {
        /** Brew / scale readouts — tonal buttons, big mono readout (default, unchanged). */
        val Telemetry = CremaStepperStyle(tonalButtons = true, spacedBy = 12.dp)
        /** Prominent boxed editor field — 30dp buttons, 18sp value (target tiles, Quick Controls). */
        val Boxed = CremaStepperStyle(box = StepperFill.High, boxShape = 10.dp, boxPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp), buttonSize = 30.dp, iconSize = 14, valueSize = 18.sp, fillWidth = true)
        /** Dense boxed editor field — 28dp buttons, 15sp value (profile segment rows). */
        val BoxedDense = CremaStepperStyle(box = StepperFill.Highest, boxShape = 8.dp, boxPadding = PaddingValues(3.dp), buttonSize = 28.dp, iconSize = 12, valueSize = 15.sp, fillWidth = true)
        /** Bare settings-row stepper — 36dp buttons, 16sp value (tablet settings). */
        val Bare = CremaStepperStyle(buttonSize = 36.dp, iconSize = 15, valueSize = 16.sp, spacedBy = 8.dp, valueMinWidth = 64.dp)
        /** Compact bare stepper — 34dp buttons, 15sp value (phone settings + editors). */
        val BareCompact = CremaStepperStyle(buttonSize = 34.dp, iconSize = 14, valueSize = 15.sp, spacedBy = 6.dp, valueMinWidth = 58.dp)
    }
}

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
    style: CremaStepperStyle = CremaStepperStyle.Telemetry,
    enabled: Boolean = true,
    header: (@Composable () -> Unit)? = null,
    chips: List<Double>? = null,
    compareSymbol: String? = null,
    onCompare: (() -> Unit)? = null,
) {
    fun snap(x: Double) = (kotlin.math.round(x * 100) / 100).coerceIn(min, max)
    val dec = { onChange(snap(value - step)) }
    val inc = { onChange(snap(value + step)) }

    // Telemetry keeps its exact original chrome (big tonal buttons, readoutSm).
    if (style.tonalButtons) {
        Column(modifier) {
            if (label != null) Eyebrow(label, Modifier.padding(bottom = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalIconButton(onClick = dec) { PhIcon("minus") }
                Row(verticalAlignment = Alignment.Bottom) {
                    TapToEditValue(
                        value = value,
                        min = min,
                        max = max,
                        onCommit = { onChange(snap(it)) },
                        editStyle = CremaTheme.readout.readoutSm,
                        enabled = enabled,
                    ) {
                        Text(fmt(value), style = CremaTheme.readout.readoutSm, color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (unit != null) Text(" $unit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = inc) { PhIcon("plus") }
            }
        }
        return
    }

    // Compact (boxed / bare): optional header (or eyebrow label) above the bar,
    // optional quick-select chips below. enabled greys the bar + chips while the
    // header stays lit — the Quick-Controls "target off" behaviour.
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            header != null -> header()
            label != null -> Eyebrow(label)
        }
        Column(Modifier.alpha(if (enabled) 1f else 0.4f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            var barMod: Modifier = if (style.fillWidth) Modifier.fillMaxWidth() else Modifier
            if (style.box != StepperFill.None) {
                val fill = if (style.box == StepperFill.High) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerHighest
                barMod = barMod.clip(RoundedCornerShape(style.boxShape)).background(fill).padding(style.boxPadding)
            }
            Row(
                barMod,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (style.fillWidth) Arrangement.SpaceBetween else Arrangement.spacedBy(style.spacedBy),
            ) {
                StepperCircleBtn("minus", style.buttonSize, style.iconSize, dec)
                val valueMod = when {
                    style.fillWidth -> Modifier.weight(1f)
                    style.valueMinWidth > 0.dp -> Modifier.widthIn(min = style.valueMinWidth)
                    else -> Modifier
                }
                Row(valueMod, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                    if (compareSymbol != null) {
                        Text(
                            compareSymbol,
                            style = TextStyle(fontFamily = JetBrainsMono, fontSize = style.valueSize, fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = (onCompare?.let { Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = it) } ?: Modifier).padding(end = 3.dp),
                        )
                    }
                    TapToEditValue(
                        value = value,
                        min = min,
                        max = max,
                        onCommit = { onChange(snap(it)) },
                        editStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = style.valueSize),
                        enabled = enabled,
                    ) {
                        CremaValueUnit(fmt(value), unit, valueSize = style.valueSize)
                    }
                }
                StepperCircleBtn("plus", style.buttonSize, style.iconSize, inc)
            }
            if (chips != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    chips.forEach { c -> StepperChip(fmt(c), kotlin.math.abs(value - c) < 0.05) { onChange(snap(c)) } }
                }
            }
        }
    }
}

// Small circular −/+ button (surfaceContainerHighest) used by every compact stepper.
@Composable
private fun StepperCircleBtn(icon: String, size: Dp, iconSize: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { PhIcon(icon, sizeDp = iconSize) }
    }
}

// Quick-select value chip — copper fill when it matches the current value, faint
// hairline otherwise (Quick Controls .qchip).
@Composable
private fun RowScope.StepperChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .then(if (active) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(8.dp)))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono, fontSize = 10.sp),
            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            maxLines = 1,
        )
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
            // Brand "C" mark — the PWA favicon's Newsreader-500 glyph baked into a
            // vector (R.drawable.ic_crema_logo) so it's font-independent + identical
            // everywhere. Disc + off-centre serif C live in the drawable.
            Image(
                painter = painterResource(R.drawable.ic_crema_logo),
                contentDescription = "Crema",
                modifier = Modifier.padding(top = 12.dp).size(34.dp),
            )
        },
    ) {
        Spacer(Modifier.height(8.dp))
        cremaRailItems.forEach { item ->
            NavigationRailItem(
                selected = active == item.id,
                onClick = { onNav(item.id) },
                icon = { PhIcon(item.icon, sizeDp = 24) },
                label = { Text(item.label, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationRailItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
        Spacer(Modifier.weight(1f))
        // Connection pips
        ConnectionPip("DE1", machineConnected) { onConnect("machine") }
        ConnectionPip("Scale", scaleConnected) { onConnect("scale") }
        Spacer(Modifier.height(12.dp))
    }
}

// Rail connection status (proto .m3-rail-status): a 56dp-wide VERTICAL column —
// status dot ABOVE a 9sp uppercase label — that connects/disconnects on tap.
// Connected = green success dot inside a soft glow ring (proto box-shadow
// 0 0 0 2px success@30%). Disconnected = dim onSurface@20% dot with a small
// circular "connect" CTA badge pinned to the dot's top-right corner (proto
// .m3-rail-status-cta, shown via `:not(.is-connected)`); Android has no hover,
// so show-when-disconnected is the faithful mapping of that rule.
@Composable
private fun ConnectionPip(label: String, connected: Boolean, onClick: () -> Unit) {
    val tel = CremaTheme.telemetry
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    // CTA badges (proto .m3-rail-status-cta): a CONNECTED pip shows a copper check
    // on hover/press (Android maps hover→hover-or-press; tap = disconnect). A
    // DISCONNECTED pip shows a copper "bluetooth" connect cue at rest — Android has
    // no hover, so the proto's hover-only CTA maps to always-on, and that badge is
    // what tells you the grey dot is a tappable connect control.
    val showCheck = connected && (hovered || pressed)
    Column(
        modifier = Modifier
            .width(56.dp)
            .heightIn(min = 48.dp) // M3 min touch target — this pip is the connect/disconnect control
            .clip(RoundedCornerShape(8.dp))
            // Ripple feedback (the app default indication) so a tap visibly
            // registers. Was indication = null, which — with no disconnected CTA —
            // made the pip read as a dead status light rather than a button.
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        // The status dot is centered; the connect-confirm badge sits just to its
        // right (proto cta at right:8) — beside, not overlapping.
        Box(Modifier.fillMaxWidth().height(16.dp)) {
            Box(Modifier.align(Alignment.Center).size(14.dp), contentAlignment = Alignment.Center) {
                if (connected) {
                    Box(Modifier.size(13.dp).clip(CircleShape).background(tel.success.copy(alpha = 0.28f)))
                }
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (connected) tel.success else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)),
                )
            }
            val cta = when {
                !connected -> "bluetooth" // tap-to-connect cue, always shown at rest
                showCheck -> "check" // connected + hover/press → tap-to-disconnect
                else -> null
            }
            if (cta != null) {
                Box(
                    Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).size(14.dp)
                        .clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    PhIcon(cta, sizeDp = 9, tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.4.sp,
            ),
            color = if (connected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The app-wide search pill (web `.pp-search` / picker "Search …" input): a
 * magnifying-glass + borderless text field on a rounded surface. One
 * implementation for the Profiles / Beans / History command bars (40dp tall on
 * surfaceContainerHigh) and the Brew picker popups (compact 36dp, bordered on
 * surfaceContainerLowest so it reads inside the popup card).
 */
@Composable
fun CremaSearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val height = if (compact) 36.dp else 40.dp
    val iconSize = if (compact) 14 else 18
    val style = if (compact) {
        MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
    } else {
        MaterialTheme.typography.bodyMedium
    }
    // Full pill (matches the handset search field; was the PWA's squared 8dp).
    val pill = RoundedCornerShape(999.dp)
    Box(
        modifier
            .height(height)
            .clip(pill)
            .background(
                if (compact) MaterialTheme.colorScheme.surfaceContainerLowest
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .then(
                if (compact) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, pill)
                else Modifier,
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhIcon("magnifying-glass", sizeDp = iconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(placeholder, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
