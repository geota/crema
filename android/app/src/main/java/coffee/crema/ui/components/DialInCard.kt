package coffee.crema.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.history.StoredShot
import coffee.crema.history.effectiveGrindSetting
import coffee.crema.ui.convertWeight
import coffee.crema.ui.fmt
import coffee.crema.ui.formatRatio
import coffee.crema.ui.relativeAgo
import coffee.crema.ui.theme.JetBrainsMono

/*
 * Dial-in card — "pick up where you left off" for the active bag
 * (bean-workflow-unify §A2). Shown on the idle Brew screen when the active
 * bean has at least one stored shot AND no session Last-shot card is up (the
 * two never describe the same shot twice): that bag's most recent shot
 * (grind · yield · ratio · time · rating) with its forward-looking "next
 * time" plan rendered prominently, a tap-through to the shot in History,
 * and one button that re-applies the whole setup (MainViewModel.startFromShot).
 * Shared by the tablet BrewScreen and PhoneBrewScreen; web parity:
 * `DialInCard.svelte`.
 */
@Composable
fun DialInCard(
    /** The active bag's most recent stored shot. */
    shot: StoredShot,
    weightUnit: String,
    /** Re-apply this shot's setup (profile + bag + grind + targets). */
    onStart: () -> Unit,
    /** Open this shot in History. */
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val yieldM = convertWeight(shot.yieldG, weightUnit)
    val ratio = formatRatio(shot.doseG, shot.yieldG)
    val plan = shot.nextPlan?.trim()?.takeIf { it.isNotEmpty() }
    CremaCard(modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Eyebrow("Where you left off · ${relativeAgo(shot.completedAtMs)}")
                PhIcon("caret-right", sizeDp = 13, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialInStat("Grind", shot.effectiveGrindSetting ?: "—", null, Modifier.weight(1f))
                DialInStat("Yield", yieldM.value, shot.yieldG?.let { yieldM.unit }, Modifier.weight(1f))
                DialInStat("Ratio", ratio, null, Modifier.weight(1f))
                DialInStat("Time", fmt("%.0f", shot.durationMs / 1000.0), "s", Modifier.weight(1f))
            }
            if ((shot.rating ?: 0) > 0) {
                CremaStarRating(
                    shot.rating ?: 0,
                    starDp = 11,
                    emptyTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                )
            }
            if (plan != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Eyebrow("Next time")
                    Text(
                        plan,
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FilledTonalButton(onClick = onStart, modifier = Modifier.height(36.dp)) {
                PhIcon("coffee", sizeDp = 15)
                Spacer(Modifier.width(6.dp))
                Text("Use these settings")
            }
        }
    }
}

// One stat cell — mirrors the tablet LastShotStat treatment (mono value +
// small unit over a dimmed caps label) without reaching into BrewScreen's
// private helpers.
@Composable
private fun DialInStat(label: String, value: String, unit: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 15.sp, fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (unit != null) {
                Text(
                    unit,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 2.dp, bottom = 1.dp),
                )
            }
        }
    }
}
