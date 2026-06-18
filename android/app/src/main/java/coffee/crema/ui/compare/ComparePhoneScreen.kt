package coffee.crema.ui.compare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coffee.crema.history.StoredShot
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.convertPressure
import coffee.crema.ui.convertTemp
import coffee.crema.ui.convertWeight
import coffee.crema.ui.formatRatio
import coffee.crema.ui.theme.JetBrainsMono

/*
 * ComparePhoneScreen — handset port. Where the tablet shows a modal with a wide
 * metric table, the phone PUSHES a full screen: a back/title bar, a
 * horizontally-scrollable grouped channel selector, a chart card, then a
 * colour-coded legend list of per-shot metric cards. Pushed onto the History
 * back-stack from the select-mode "Compare (N)" action. Ported from
 * compare-handoff/kotlin/ComparePhoneScreen.kt over real [StoredShot].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComparePhoneScreen(
    shots: List<StoredShot>,
    weightUnit: String,
    tempUnit: String,
    pressureUnit: String,
    onBack: () -> Unit,
) {
    var channel by remember { mutableStateOf(Channel.Pressure) }

    Column(Modifier.fillMaxSize()) {
        // ── Top bar ──────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { PhIcon("arrow-left", sizeDp = 24) }
            Column(Modifier.weight(1f)) {
                Text("Compare", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${shots.size} shots overlaid",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Channel selector — grouped chips that WRAP (no horizontal overflow);
        // each family (icon + its two chips) stays together as one wrap unit. ──
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CHANNEL_GROUPS.forEach { group ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PhIcon(group.icon, sizeDp = 15, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    group.options.forEach { opt -> ChannelChip(opt.label, channel == opt.channel) { channel = opt.channel } }
                }
            }
        }

        // ── Scroll body ──────────────────────────────────────────────────
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Chart card — unit label floats top-right.
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                Box {
                    Text(
                        unitLabel(channel),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    )
                    CompareChart(shots, channel, Modifier.fillMaxWidth().height(220.dp).padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 6.dp))
                }
            }
            // Legend cards.
            shots.forEachIndexed { i, s -> LegendCard(i, s, weightUnit, tempUnit, pressureUnit) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegendCard(index: Int, s: StoredShot, weightUnit: String, tempUnit: String, pressureUnit: String) {
    val p = s.peakPressure?.let { convertPressure(it, pressureUnit) }
    val t = s.peakTemp?.let { convertTemp(it, tempUnit) }
    val w = s.yieldG?.let { convertWeight(it, weightUnit) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Surface(shape = CircleShape, color = compareColor(index), modifier = Modifier.padding(top = 4.dp).size(11.dp)) {}
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(compareName(s), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Row {
                        repeat(5) { i ->
                            PhIcon(
                                if (i < (s.rating ?: 0)) "star-fill" else "star",
                                sizeDp = 12,
                                tint = if (i < (s.rating ?: 0)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                Text(compareSub(s), style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Metric("${s.durationMs / 1000}", "s")
                    Metric(p?.value ?: "—", p?.unit ?: "")
                    Metric(t?.value ?: "—", t?.unit ?: "")
                    Metric(w?.value ?: "—", w?.unit ?: "")
                    Metric(formatRatio(s.doseG, s.yieldG), "")
                }
            }
        }
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMono), color = MaterialTheme.colorScheme.onSurface)
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
