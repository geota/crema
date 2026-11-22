package coffee.crema.ui.beans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coffee.crema.beans.DEFAULT_GRIND_SCALE
import coffee.crema.beans.grindLabel
import coffee.crema.beans.grindNumber
import coffee.crema.beans.isLive
import coffee.crema.core.BagGrind
import coffee.crema.core.Bean
import coffee.crema.core.Grinder
import coffee.crema.core.LastGrind
import coffee.crema.ui.GrinderPick
import coffee.crema.ui.components.CremaStepper
import coffee.crema.ui.components.CremaStepperStyle
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.GrinderAddBox
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.newestShotGrind
import coffee.crema.ui.scaleNumber

/*
 * The bag editor's "Grind per grinder" block (#488, boards WideBagGrind,
 * PhoneBagGrind, PhoneBagGrindAdd): one row per saved grind with ☆/★, the
 * grinder's name, a stepper on that grinder's scale (tap to type), 🗑. An
 * edited value says "Changed from 470 μm · saved when you tap Save"; a
 * grinder no longer on Devices stays, dimmed and read-only, until removed.
 * The type-or-pick box adds a row. The editor's Save writes all of it;
 * Cancel discards it. [rows] is the editor's draft.
 */
@Composable
fun BagGrindsEditor(
    rows: SnapshotStateList<BagGrind>,
    /** The rows the editor opened on, for "Changed from …". */
    opened: List<BagGrind>,
    grinders: List<Grinder>,
    lastGrinds: List<LastGrind>,
    /** Resolve a pick (adds a catalogue or created grinder to Devices). */
    onPick: (GrinderPick) -> Grinder?,
    /** The grinder ids whose grind Save refuses ("Enter a grind or remove this row"). */
    toFix: Set<String> = emptySet(),
    compact: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Grind per grinder", Modifier.weight(1f))
            Text("Saved when you tap Save", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        rows.toList().forEach { row ->
            val grinder = grinders.firstOrNull { it.id == row.grinderId }
            val onDevices = row.grinderId.isEmpty() || grinder?.isLive == true
            val scale = grinder?.scale ?: DEFAULT_GRIND_SCALE
            val before = opened.firstOrNull { it.grinderId == row.grinderId }
            val sub = when {
                !onDevices -> "No longer on Devices · kept until you remove it"
                row.grinderId in toFix -> "Enter a grind or remove this row"
                before != null && before.grind != row.grind -> "Changed from ${grindLabel(before.grind, scale)} · saved when you tap Save"
                row.starred -> listOfNotNull("This bag's grinder", grinder?.let { g -> newestShotGrind(lastGrinds, g)?.let { "last shot ${grindLabel(it.grind, scale)}" } }).joinToString(" · ")
                else -> grinder?.let { g -> newestShotGrind(lastGrinds, g)?.let { "Last shot ${grindLabel(it.grind, scale)}" } }
            }
            fun update(next: BagGrind) {
                val i = rows.indexOfFirst { it.grinderId == row.grinderId }
                if (i >= 0) rows[i] = next
            }
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).alpha(if (onDevices) 1f else 0.6f), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                // One star per bag: starring this one takes it off the others.
                                val star = !row.starred
                                for (i in rows.indices) rows[i] = rows[i].copy(starred = star && rows[i].grinderId == row.grinderId)
                            },
                            enabled = onDevices,
                        ) {
                            PhIcon(
                                if (row.starred) "star-fill" else "star",
                                sizeDp = 18,
                                tint = if (row.starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                contentDescription = if (row.starred) "This bag's grinder" else "Make this the bag's grinder",
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                grinder?.name ?: row.grinderName.ifBlank { "No grinder" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (sub != null) {
                                Text(
                                    sub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (row.grinderId in toFix) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                        }
                        if (!onDevices || compact) {
                            if (!onDevices) Text(grindLabel(row.grind, scale), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp))
                        } else {
                            GrindStepper(row, scale, Modifier.width(190.dp)) { update(row.copy(grind = it)) }
                        }
                        IconButton(onClick = { rows.removeAll { it.grinderId == row.grinderId } }) {
                            PhIcon("trash", sizeDp = 18, tint = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = "Remove ${grinder?.name ?: row.grinderName} from this bag")
                        }
                    }
                    // The phone puts the stepper on its own line under the name.
                    if (compact && onDevices) GrindStepper(row, scale, Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 4.dp)) { update(row.copy(grind = it)) }
                }
            }
        }
        GrinderAddBox(
            grinders = grinders,
            excludeIds = rows.map { it.grinderId },
            onPick = { pick ->
                val g = onPick(pick) ?: return@GrinderAddBox
                if (rows.none { it.grinderId == g.id }) {
                    // The newest shot on this bag with it, else empty (Save asks for one).
                    val seed = newestShotGrind(lastGrinds, g)?.grind.orEmpty()
                    rows.add(BagGrind(grinderId = g.id, grinderName = g.name, grind = seed, starred = false))
                }
            },
        )
    }
}

/** A row's stepper on its grinder's scale; a blank grind reads "—" until set. A typed value outside the scale is kept. */
@Composable
private fun GrindStepper(row: BagGrind, scale: coffee.crema.core.GrindScale, modifier: Modifier, onChange: (String) -> Unit) {
    val lo = minOf(scale.min, scale.max).toDouble()
    val hi = maxOf(scale.min, scale.max).toDouble()
    val value = coffee.crema.beans.grindDouble(row.grind)
    CremaStepper(
        modifier = modifier,
        value = value ?: ((lo + hi) / 2),
        unit = scale.unit.takeIf { it.isNotBlank() },
        onChange = { onChange(coffee.crema.beans.grindText(it.toFloat())) },
        step = scale.step.toDouble().takeIf { it.isFinite() && it > 0 } ?: 0.1,
        min = lo,
        max = hi,
        fmt = { v -> if (value == null && row.grind.isBlank()) "—" else if (value == null) row.grind else scaleNumber(v.toFloat()) },
        style = CremaStepperStyle.BareCompact,
        clampTyped = false,
    )
}

/** The bag detail's read-only list: ★, the grinder, its saved grind. */
@Composable
fun BagGrindsList(bean: Bean, grinders: List<Grinder>) {
    val rows = bean.grinds.orEmpty()
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            val g = grinders.firstOrNull { it.id == row.grinderId }
            val onDevices = row.grinderId.isEmpty() || g?.isLive == true
            Row(Modifier.fillMaxWidth().alpha(if (onDevices) 1f else 0.6f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.starred) PhIcon("star-fill", sizeDp = 13, tint = MaterialTheme.colorScheme.primary, contentDescription = "this bag's grinder")
                Text(g?.name ?: row.grinderName.ifBlank { "No grinder" }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(grindLabel(row.grind, g?.scale), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = coffee.crema.ui.theme.JetBrainsMono))
            }
        }
    }
}
