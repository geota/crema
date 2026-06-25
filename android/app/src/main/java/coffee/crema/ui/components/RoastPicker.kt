package coffee.crema.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coffee.crema.beans.roastBand5
import coffee.crema.ui.theme.JetBrainsMono

/**
 * The shared 1–10 roast-level picker (issue 14): one definition for BOTH the tablet
 * ([coffee.crema.ui.screens.BeanEditScreen]) and phone
 * ([coffee.crema.ui.phone.PhoneBeanEditScreen]) bean editors, so a given roast level
 * reads identically everywhere (value 4 = "Med-light" on both, not "Medium" on phone).
 *
 * Theme-token coloured (no hardcoded hex → responds to dark mode + retheming), 36 dp
 * pips, a 5-band label row (Light / Med-light / Medium / Med-dark / Dark) driven by
 * core `roastBand5`, and the numeric `"$value /10 · band"` readout. Mirrors the web
 * RoastSlider's five band anchors weighted under their pip spans (1-2 / 3-4 / 5 / 6-7 /
 * 8-10).
 */
@Composable
fun RoastPicker(value: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            (1..10).forEach { n ->
                val current = n == value
                // Selected pip = solid copper; pips below it = copper wash; rest = well.
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
        // Web RoastSlider: FIVE band anchors under the 1..10 track, weighted to sit
        // under their pip spans (1-2 / 3-4 / 5 / 6-7 / 8-10).
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
