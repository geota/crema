package coffee.crema.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The Brew header's PROFILE / BEAN block, extracted (issue #16 round 4) so the
 * History shot detail renders the SAME anatomy — eyebrow row (+ trailing
 * chip), title + caret, then styled meta/spec/tags lines — instead of
 * hand-rolling a lookalike. Brew feeds it live library data and wraps it in
 * picker popups; History feeds it the shot's frozen snapshot (+ the pull
 * timestamp as the profile block's trailing element, the one deliberate
 * difference).
 */

/** One line under a header block's title, styled per the Brew header's CSS
 *  tiers (.bh-meta / .bh-spec / .bh-tags). */
data class HeaderBlockLine(val text: String, val style: HeaderLineStyle = HeaderLineStyle.Meta)

enum class HeaderLineStyle { Meta, Spec, Tags }

@Composable
fun CremaHeaderBlock(
    eyebrow: String,
    title: String,
    lines: List<HeaderBlockLine>,
    modifier: Modifier = Modifier,
    /** Right end of the eyebrow row — the freshness chip, an uploading label,
     *  or (History) the shot's timestamp. */
    eyebrowTrailing: (@Composable () -> Unit)? = null,
    /** The picker caret; hidden for non-interactive blocks (a shot's profile
     *  is a historical fact). */
    showCaret: Boolean = true,
    /** Open-state tint while an anchored picker is up (proto .bh-block.is-open). */
    openTint: Boolean = false,
    /** Brew's bean block pushes the caret to the block's right edge (the title
     *  takes the slack); its profile block lets the caret hug the name. */
    caretAtEnd: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (openTint) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // .bh-eyebrow-row — the label + (optionally) a right-justified chip.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Eyebrow(eyebrow)
            eyebrowTrailing?.invoke()
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f, fill = caretAtEnd),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 24.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showCaret) PhIcon("caret-down", sizeDp = 16, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        lines.forEach { line ->
            when (line.style) {
                HeaderLineStyle.Meta -> Text(
                    line.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HeaderLineStyle.Spec -> Text(
                    line.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HeaderLineStyle.Tags -> Text(
                    line.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The Brew header's freshness chip — 8 dp pip + label in the freshness-band
 *  colour. History passes an at-pull-time label so the shot's record stays
 *  static ("7d off roast" forever, if it was pulled at 7 days). */
@Composable
fun CremaFreshnessChip(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
