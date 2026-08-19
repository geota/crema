package coffee.crema.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
 * time" plan rendered as an accent-barred quote, a tap-through to the shot
 * in History, and one button that re-applies the whole setup
 * (MainViewModel.startFromShot). Shared by the tablet BrewScreen and
 * PhoneBrewScreen; web parity: `DialInCard.svelte`.
 *
 * The tablet hosts this in the left column's FIXED bottom slot (the Phase
 * card is the column's only shock absorber), so the card must never outgrow
 * what the column has left — on a 600–800dp-tall window Compose clips the
 * column's last child, which used to eat the button. The card therefore
 * keeps everything but the plan at a fixed, small cost, and the HOST states
 * how many plan lines fit via [planMaxLines] (the tablet derives it from
 * measured leftover height; the phone's scrolling column keeps the default).
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
    /** How many lines the "next time" plan may take; 0 hides the plan. */
    planMaxLines: Int = 3,
) {
    val yieldM = convertWeight(shot.yieldG, weightUnit)
    val ratio = formatRatio(shot.doseG, shot.yieldG)
    val plan = shot.nextPlan?.trim()?.takeIf { it.isNotEmpty() && planMaxLines > 0 }
    CremaCard(modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Header — eyebrow left, rating right. No caret: the whole card is
            // the tap-through (kept the row within a 248dp column's width).
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Eyebrow("Where you left off · ${relativeAgo(shot.completedAtMs)}", Modifier.weight(1f))
                if ((shot.rating ?: 0) > 0) {
                    CremaStarRating(
                        shot.rating ?: 0,
                        starDp = 9,
                        emptyTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialInStat("Grind", shot.effectiveGrindSetting ?: "—", null, Modifier.weight(1f))
                DialInStat("Yield", yieldM.value, shot.yieldG?.let { yieldM.unit }, Modifier.weight(1f))
                DialInStat("Ratio", ratio, null, Modifier.weight(1f))
                DialInStat("Time", fmt("%.0f", shot.durationMs / 1000.0), "s", Modifier.weight(1f))
            }
            if (plan != null) {
                // The plan as a quote — accent bar left, italic snippet (the
                // same treatment the history rows give it), no label row.
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Spacer(
                        Modifier.width(2.dp).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                    )
                    Text(
                        plan,
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = planMaxLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            DialInApplyChip(onClick = onStart, modifier = Modifier.align(Alignment.End))
        }
    }
}

/**
 * The card's one action as a compact, self-sized pill — "Load setup"
 * re-applies the shot's whole setup (profile + bag + grind + targets).
 * Replaces the old squeezed FilledTonalButton ("Use these settings"), whose
 * M3 content padding drew the label low inside a forced 32dp height and
 * whose width dominated the card. Explicit padding here centres the label by
 * construction; the border + neutral fill mirror the web `.di-start` pill.
 * Shared by the tablet [DialInCard] and the phone Brew screen's variant.
 */
@Composable
fun DialInApplyChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PhIcon("arrow-counter-clockwise", sizeDp = 13, tint = MaterialTheme.colorScheme.primary)
            Text(
                "Load setup",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
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
