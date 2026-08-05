package coffee.crema.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.core.FieldHit
import coffee.crema.core.SearchSegment

/*
 * Search-match highlighting (issue 62).
 *
 * The core hands back snippets already split into matched / unmatched runs
 * (`SearchSegment`), including any ellipses — precisely so no shell has to do
 * offset arithmetic across a UTF-8 / UTF-16 boundary. This file only
 * concatenates; if it ever starts slicing, that is the bug.
 */

/**
 * [segments] rendered as an `AnnotatedString` with the matched runs washed in
 * the brand tint, or plain [fallback] when there is no match to highlight.
 *
 * A tint wash rather than a saturated block: the bean grid is dense, and a
 * hard highlight per card reads as an error state.
 */
@Composable
fun highlighted(
    segments: List<SearchSegment>?,
    fallback: String,
    tint: Color = MaterialTheme.colorScheme.primary,
    transform: (String) -> String = { it },
): AnnotatedString {
    val wash = tint.copy(alpha = 0.24f)
    return remember(segments, fallback, wash, transform) {
        if (segments.isNullOrEmpty()) {
            AnnotatedString(transform(fallback))
        } else {
            buildAnnotatedString {
                segments.forEach { seg ->
                    // Transform each run separately: a case change can alter a
                    // run's length (ß → SS), and appending run-by-run keeps every
                    // span anchored to its own text regardless.
                    if (seg.hit) {
                        withStyle(SpanStyle(background = wash, fontWeight = FontWeight.SemiBold)) {
                            append(transform(seg.text))
                        }
                    } else {
                        append(transform(seg.text))
                    }
                }
            }
        }
    }
}

/** The all-caps variant the [coffee.crema.ui.components.Eyebrow] label needs. */
val UPPERCASE: (String) -> String = { it.uppercase() }

/**
 * The one-line "matched in <field>" hint under a bean card's title, shown
 * only when the query landed on something the card does not already display
 * (a process, a tasting note, where the bag was bought). Without it, such a
 * bag is indistinguishable from one that matched nothing at all.
 */
@Composable
fun SearchWhy(hit: FieldHit, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            hit.label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.5.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            maxLines = 1,
        )
        Text(
            highlighted(hit.snippet, ""),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
