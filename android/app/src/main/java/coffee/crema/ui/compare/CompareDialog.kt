package coffee.crema.ui.compare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coffee.crema.history.StoredShot
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.convertPressure
import coffee.crema.ui.convertTemp
import coffee.crema.ui.convertWeight
import coffee.crema.ui.formatRatio
import coffee.crema.ui.theme.JetBrainsMono

/*
 * CompareDialog — tablet overlay. Full-screen scrim + a centred
 * surface-container-low panel (extra-large 28dp radius). One channel overlaid at
 * a time; a grouped chip selector switches channels; a 7-column metric table
 * underneath carries the headline numbers (unit-aware, like every shot readout).
 * Ported from compare-handoff/kotlin/CompareDialog.kt over real [StoredShot].
 */
@Composable
fun CompareDialog(
    shots: List<StoredShot>,
    weightUnit: String,
    tempUnit: String,
    pressureUnit: String,
    onDismiss: () -> Unit,
) {
    var channel by remember { mutableStateOf(Channel.Pressure) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge, // 28dp
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(max = 1120.dp).fillMaxWidth(0.9f).fillMaxHeight(0.92f),
        ) {
            Column(Modifier.fillMaxSize()) {
                // ── Header ───────────────────────────────────────────────
                Row(
                    Modifier.fillMaxWidth().padding(start = 26.dp, end = 18.dp, top = 22.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column {
                        Eyebrow("Compare", color = MaterialTheme.colorScheme.primary)
                        Text(
                            "${shots.size} shots overlaid",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = onDismiss) { PhIcon("x", sizeDp = 24) }
                }

                // ── Channel selector ─────────────────────────────────────
                ChannelSelector(channel, onChange = { channel = it }, Modifier.padding(horizontal = 26.dp, vertical = 12.dp))

                // ── Chart ────────────────────────────────────────────────
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.padding(horizontal = 26.dp).fillMaxWidth(),
                ) {
                    CompareChart(shots, channel, Modifier.fillMaxWidth().height(320.dp).padding(8.dp))
                }

                // ── Legend + metric table ────────────────────────────────
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 26.dp).padding(top = 16.dp, bottom = 24.dp)) {
                    MetricTableHeader()
                    shots.forEachIndexed { i, s -> MetricRow(i, s, weightUnit, tempUnit, pressureUnit) }
                }
            }
        }
    }
}

@Composable
internal fun ChannelSelector(channel: Channel, onChange: (Channel) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CHANNEL_GROUPS.forEachIndexed { gi, group ->
            if (gi > 0) VerticalDivider(
                modifier = Modifier.padding(horizontal = 7.dp).height(20.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            PhIcon(group.icon, sizeDp = 16, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            group.options.forEach { opt -> ChannelChip(opt.label, selected = channel == opt.channel) { onChange(opt.channel) } }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "· ${unitLabel(channel)}",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Pill chip — active = copper-tinted fill + copper text. */
@Composable
internal fun ChannelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(onClick = onClick, shape = CircleShape, color = bg) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
    }
}

// ── Metric table ────────────────────────────────────────────────────────────
private val COLS = listOf("Time", "Peak pressure", "Peak temp", "Final", "Ratio", "Rating")

@Composable
private fun MetricTableHeader() {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Eyebrow("Shot", Modifier.weight(2.2f))
        COLS.forEach { Eyebrow(it, Modifier.weight(1f)) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun MetricRow(index: Int, s: StoredShot, weightUnit: String, tempUnit: String, pressureUnit: String) {
    val dot = compareColor(index)
    val mono = TextStyle(fontFamily = JetBrainsMono, fontFeatureSettings = "tnum")
    val pressure = s.peakPressure?.let { convertPressure(it, pressureUnit).let { m -> "${m.value} ${m.unit}" } } ?: "—"
    val temp = s.peakTemp?.let { convertTemp(it, tempUnit).let { m -> "${m.value} ${m.unit}" } } ?: "—"
    val final = s.yieldG?.let { convertWeight(it, weightUnit).let { m -> "${m.value} ${m.unit}" } } ?: "—"
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(2.2f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = dot, modifier = Modifier.size(11.dp)) {}
            Column {
                Text(compareName(s), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(compareSub(s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        MetricCell("${s.durationMs / 1000} s", mono)
        MetricCell(pressure, mono)
        MetricCell(temp, mono)
        MetricCell(final, mono)
        MetricCell(formatRatio(s.doseG, s.yieldG), mono)
        Stars(s.rating ?: 0, Modifier.weight(1f))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MetricCell(text: String, style: TextStyle) {
    Text(text, modifier = Modifier.weight(1f), style = style, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Stars(rating: Int, modifier: Modifier = Modifier) {
    Row(modifier) {
        repeat(5) { i ->
            PhIcon(
                if (i < rating) "star-fill" else "star",
                sizeDp = 14,
                tint = if (i < rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
    }
}
