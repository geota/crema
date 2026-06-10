package coffee.crema.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Roaster mark — the deterministic two-letter avatar mark + tone colour.
 *
 * A 1:1 port of web `$lib/bean/roaster-mark` (roasterMark / roasterTone) so the
 * avatars on the Brew bean picker, bean tiles and roaster cards look the same
 * on every shell: same initials rule (skip the "Coffee / Roasters / Co"
 * boilerplate), same djb2 hash, same 10-tone palette. Same name → same mark and
 * colour, every render, every device, both shells.
 */

// The web TONES palette, verbatim (roaster-mark.ts) — order matters for the hash.
private val TONES = listOf(
    Color(0xFFC44E3F), // brick red
    Color(0xFF4A6FA5), // dusty blue
    Color(0xFF6B8C5F), // sage
    Color(0xFFD89030), // amber
    Color(0xFFC7763B), // copper (matches brand)
    Color(0xFF8A5C3F), // walnut
    Color(0xFF3A4C5E), // slate
    Color(0xFFA35538), // brick orange
    Color(0xFF5A6F5C), // forest
    Color(0xFF9A6B8C), // muted plum
)

private val STOPWORDS = setOf(
    "coffee", "coffees", "cafe", "café", "roasters", "roaster", "roasting",
    "roastery", "roastworks", "co", "co.", "inc", "inc.", "lab", "labs",
    "company", "the", "and", "&",
)

/** Two-letter (or one-letter) initialled mark; blank/null → `"?"`. */
fun roasterMark(name: String?): String {
    val trimmed = name?.trim().orEmpty()
    if (trimmed.isEmpty()) return "?"
    val words = trimmed.split(Regex("\\s+"))
        .map { w -> w.trim { c -> !c.isLetterOrDigit() } }
        .filter { it.isNotEmpty() && it.lowercase() !in STOPWORDS }
    return when {
        words.isEmpty() -> trimmed.first().uppercase()
        words.size == 1 -> words[0].first().uppercase()
        else -> "${words[0].first()}${words[1].first()}".uppercase()
    }
}

/** Deterministic tone-from-name (djb2 → palette); blank/null → copper. */
fun roasterTone(name: String?): Color {
    val trimmed = name?.trim()?.lowercase().orEmpty()
    if (trimmed.isEmpty()) return TONES[4]
    var hash = 5381
    for (ch in trimmed) hash = (hash shl 5) + hash + ch.code // wraps like JS `| 0`
    // JS Math.abs promotes past Int range; mirror via Long so MIN_VALUE can't wrap.
    val idx = (kotlin.math.abs(hash.toLong()) % TONES.size).toInt()
    return TONES[idx]
}

/**
 * The avatar itself — a rounded tone square with the mark. Sizes used so far:
 * 32dp/9dp (Brew bean picker), 44dp/12dp (bean tiles + roaster cards).
 */
@Composable
fun RoasterMarkAvatar(
    name: String?,
    sizeDp: Int = 32,
    cornerDp: Int = 9,
    fontSize: TextUnit = 14.sp,
) {
    Box(
        Modifier.size(sizeDp.dp).clip(RoundedCornerShape(cornerDp.dp)).background(roasterTone(name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            roasterMark(name),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = fontSize, fontWeight = FontWeight.SemiBold),
            // Warm cream, fixed — reads on all ten tones in both themes (web uses paper).
            color = Color(0xFFF4EDE3),
        )
    }
}
