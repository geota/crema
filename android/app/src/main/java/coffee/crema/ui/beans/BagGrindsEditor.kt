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
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coffee.crema.beans.DEFAULT_GRIND_SCALE
import coffee.crema.beans.bagEditorGrinders
import coffee.crema.beans.formatGrind
import coffee.crema.beans.sameGrind
import coffee.crema.beans.isLive
import coffee.crema.core.BagGrind
import coffee.crema.core.Bean
import coffee.crema.core.Grinder
import coffee.crema.core.LastGrind
import coffee.crema.core.StagedGrinderPick
import coffee.crema.ui.GrinderPick
import coffee.crema.ui.components.CremaStepper
import coffee.crema.ui.components.CremaStepperStyle
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.GrinderAddBox
import coffee.crema.ui.components.PhIcon
import coffee.crema.beans.newestShotGrind

/*
 * The bag editor's "Grind per grinder" block (#488, boards WideBagGrind,
 * PhoneBagGrind, PhoneBagGrindAdd): one row per saved grind with ☆/★, the
 * grinder's name, a stepper on that grinder's scale (tap to type), 🗑. An
 * edited value says "Changed from 470 μm · saved when you tap Save"; a
 * grinder no longer on Devices stays, dimmed and read-only, until removed.
 * The type-or-pick box adds a row. A grinder it adds (a catalogue entry,
 * "+ Create", an archived one) is only staged in [staged] (FINAL S7): it is
 * stored, and may become Brew's grinder, only on Save; Cancel discards it.
 * [rows] and [staged] are the editor's draft.
 */
@Composable
fun BagGrindsEditor(
    rows: SnapshotStateList<BagGrind>,
    /** The rows the editor opened on, for "Changed from …". */
    opened: List<BagGrind>,
    /** The grinder records as stored. */
    grinders: List<Grinder>,
    /** The grinders this editor's picks staged (stored on Save, dropped on Cancel). */
    staged: SnapshotStateList<Grinder>,
    lastGrinds: List<LastGrind>,
    /** Stage a pick over the staged list so far (core `stage_grinder_pick`); stores nothing. */
    onStage: (GrinderPick, List<Grinder>) -> StagedGrinderPick?,
    /** The grinder ids whose grind Save refuses ("Enter a grind or remove this row"). */
    toFix: Set<String> = emptySet(),
    compact: Boolean = false,
) {
    // The rows and the box read the stored grinders with the staged ones over them.
    val stagedNow = staged.toList()
    val shown = remember(grinders, stagedNow) {
        runCatching { bagEditorGrinders(grinders, stagedNow) }.getOrElse { grinders + stagedNow.filter { g -> grinders.none { it.id == g.id } } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Grind per grinder", Modifier.weight(1f))
            Text("Saved when you tap Save", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        rows.toList().forEach { row ->
            val grinder = shown.firstOrNull { it.id == row.grinderId }
            val onDevices = row.grinderId.isEmpty() || grinder?.isLive == true
            val scale = grinder?.scale ?: DEFAULT_GRIND_SCALE
            val before = opened.firstOrNull { it.grinderId == row.grinderId }
            val sub = when {
                !onDevices -> "No longer on Devices · kept until you remove it"
                row.grinderId in toFix -> "Enter a grind or remove this row"
                before != null && !sameGrind(before.grind, row.grind) -> "Changed from ${formatGrind(before.grind, scale)} · saved when you tap Save"
                row.starred -> listOfNotNull("This bag's grinder", grinder?.let { g -> newestShotGrind(lastGrinds, g)?.let { "last shot ${formatGrind(it.grind, scale)}" } }).joinToString(" · ")
                else -> grinder?.let { g -> newestShotGrind(lastGrinds, g)?.let { "Last shot ${formatGrind(it.grind, scale)}" } }
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
                            if (!onDevices) Text(formatGrind(row.grind, scale), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp))
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
            grinders = shown,
            excludeIds = rows.map { it.grinderId },
            onPick = { pick ->
                val picked = onStage(pick, stagedNow) ?: return@GrinderAddBox
                if (picked.staged != stagedNow) {
                    staged.clear()
                    staged.addAll(picked.staged)
                }
                val g = picked.grinder
                if (rows.none { it.grinderId == g.id }) {
                    // The newest shot on this bag with it, else empty (Save asks for one): the core's seed.
                    rows.add(coffee.crema.beans.newBagGrindRow(g, lastGrinds))
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
    val value = coffee.crema.beans.grindNumber(row.grind)
    // Blank ("—") or text ("2.1.3"): the first + or − goes to the middle of the scale on its step (the core's rule, as on Brew).
    val blank = if (value == null) coffee.crema.ui.components.StepperBlank(row.grind.trim().ifEmpty { "—" }, remember(scale) { coffee.crema.beans.grindStartFromBlank(scale) }) else null
    CremaStepper(
        modifier = modifier,
        value = value ?: ((lo + hi) / 2),
        unit = scale.unit.takeIf { it.isNotBlank() },
        onChange = { onChange(coffee.crema.beans.grindText(it, scale)) },
        step = scale.step.toDouble().takeIf { it.isFinite() && it > 0 } ?: 0.1,
        min = lo,
        max = hi,
        fmt = { v -> coffee.crema.beans.grindText(v, scale) },
        parse = { coffee.crema.beans.grindNumber(it) },
        fullKeyboard = coffee.crema.beans.grindFullKeyboard(scale),
        style = CremaStepperStyle.BareCompact,
        clampTyped = false,
        blank = blank,
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
                Text(formatGrind(row.grind, g?.scale), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = coffee.crema.ui.theme.JetBrainsMono))
            }
        }
    }
}
