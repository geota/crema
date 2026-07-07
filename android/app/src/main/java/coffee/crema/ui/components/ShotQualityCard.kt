package coffee.crema.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.core.ShotQualityReport
import coffee.crema.ui.theme.CremaTheme

/*
 * ShotQualityCard — the shot-quality summary block on both History detail
 * surfaces (tablet pane + phone pushed detail), rendering the core's
 * `analyze_shot` report (the Decenza `ShotAnalysis` port).
 *
 * Layout: badge chips first (the at-a-glance projection — or one green
 * "Clean extraction" chip when nothing fired), then the verdict line
 * (emphasized; always the report's last line), then the remaining prose
 * observations each behind a small severity dot.
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShotQualityCard(report: ShotQualityReport, modifier: Modifier = Modifier) {
    // Severity hues off the existing palettes — success green (telemetry) for
    // good, the freshness "ok" amber for cautions, M3 error for warnings.
    val green = CremaTheme.telemetry.success
    val amber = CremaTheme.freshness.ok
    val red = MaterialTheme.colorScheme.error
    CremaCard(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Eyebrow("Shot quality")
            val b = report.badges
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (b.channeling) QualityBadge("Channeling", "wind", red)
                if (b.grindIssue) QualityBadge("Grind issue", "sliders-horizontal", amber)
                if (b.pourTruncated) QualityBadge("Puck failed", "warning", red)
                if (b.skipFirstFrame) QualityBadge("First step skipped", "warning", red)
                if (!b.channeling && !b.grindIssue && !b.pourTruncated && !b.skipFirstFrame) {
                    QualityBadge("Clean extraction", "check-circle", green)
                }
            }
            // The verdict is the last line by contract (except the
            // insufficient-data early return, which has none).
            report.lines.lastOrNull { it.lineType == "verdict" }?.let { verdict ->
                Text(
                    verdict.text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            val rest = report.lines.filter { it.lineType != "verdict" }
            if (rest.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    rest.forEach { line ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val dot = when (line.lineType) {
                                "good" -> green
                                "caution" -> amber
                                "warning" -> red
                                else -> MaterialTheme.colorScheme.onSurfaceVariant // observation
                            }
                            // Top-padded so the dot rides the first line's center
                            // even when a long observation wraps.
                            Box(
                                Modifier.padding(top = 5.dp).size(6.dp).clip(CircleShape).background(dot),
                            )
                            Text(
                                line.text,
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// One badge chip — the PrivacyChip ghost-pill idiom, tinted by severity.
@Composable
private fun QualityBadge(label: String, icon: String, tint: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        PhIcon(icon, sizeDp = 13, tint = tint)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
            color = tint,
        )
    }
}
