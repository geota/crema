package coffee.crema.ui.compare

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coffee.crema.history.StoredShot
import coffee.crema.history.beanLabel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.PhIcon

/*
 * HistoryCompareHooks — the select-mode chrome the History screen hosts.
 *
 * Compare is REACHED FROM History: enter select-mode, pick 2–COMPARE_MAX shots,
 * then "Compare (N)" opens [CompareDialog] (tablet) or pushes [ComparePhoneScreen]
 * (phone). This file holds the shared selection state plus the small bits History
 * injects into its existing list. History stays the owner of the list + the
 * dialog/screen call; these are drop-ins, not a screen. Ported from
 * compare-handoff/kotlin/HistoryCompareHooks.kt.
 */

class CompareSelectionState(initial: List<String> = emptyList()) {
    var selecting by mutableStateOf(false)
    val picked = mutableStateListOf<String>().apply { addAll(initial) }

    val count: Int get() = picked.size
    val canCompare: Boolean get() = picked.size >= 2
    fun isPicked(id: String) = picked.contains(id)

    /** True once the cap is reached and this id isn't one of the picks — the host
     *  dims such rows so the 5-shot ceiling is legible, not a dead tap. */
    fun atCap(id: String) = !isPicked(id) && picked.size >= COMPARE_MAX

    fun toggle(id: String) {
        if (picked.contains(id)) picked.remove(id)
        else if (picked.size < COMPARE_MAX) picked.add(id) // capped — silently ignore past 5
    }

    fun enter() { selecting = true }
    fun cancel() { selecting = false; picked.clear() }
}

@Composable
fun rememberCompareSelection(initial: List<String> = emptyList()) =
    remember { CompareSelectionState(initial) }

/**
 * Leading check a list row shows only while selecting. Picked = copper fill +
 * check; unpicked = a hairline ring. (At cap the host dims the whole row.)
 */
@Composable
fun RowCheck(picked: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = if (picked) MaterialTheme.colorScheme.primary else Color.Transparent,
        border = BorderStroke(2.dp, if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = modifier.size(24.dp),
    ) {
        if (picked) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PhIcon("check", sizeDp = 14, tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

// ── Tablet — copper strip above the split pane. ─────────────────────────────
@Composable
fun TabletCompareBar(state: CompareSelectionState, onCompare: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)),
        modifier = modifier,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) { PhIcon("arrows-left-right", sizeDp = 19, tint = MaterialTheme.colorScheme.primary) }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Pick 2–$COMPARE_MAX shots to overlay on one chart",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (!state.canCompare) "${state.count} picked — choose at least 2."
                    else "${state.count} picked — open Compare when ready.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CremaButton(onClick = state::cancel, variant = CremaButtonVariant.Text, label = "Cancel")
            CremaButton(onClick = onCompare, variant = CremaButtonVariant.Filled, icon = "chart-line", enabled = state.canCompare, label = "Compare (${state.count})")
        }
    }
}

// ── Phone — header hint + bottom action bar. ────────────────────────────────
@Composable
fun PhoneSelectHint(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhIcon("arrows-left-right", sizeDp = 17, tint = MaterialTheme.colorScheme.primary)
            Text(
                "Pick 2–$COMPARE_MAX shots to overlay on one chart.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun PhoneSelectBar(state: CompareSelectionState, onCompare: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CremaButton(onClick = state::cancel, variant = CremaButtonVariant.Text, label = "Cancel")
            Text(
                "${state.count} picked",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            CremaButton(onClick = onCompare, variant = CremaButtonVariant.Filled, icon = "chart-line", enabled = state.canCompare, label = "Compare (${state.count})")
        }
    }
}

// ── Shared labels (name + "bean · clock") used by the dialog + phone screen. ──
internal fun compareName(s: StoredShot): String = s.profileName ?: "Shot"

internal fun compareSub(s: StoredShot): String =
    listOfNotNull(s.beanLabel, clockLabel(s.completedAtMs)).joinToString(" · ")

internal fun clockLabel(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
