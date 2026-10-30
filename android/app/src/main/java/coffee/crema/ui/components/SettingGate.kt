package coffee.crema.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * What a settings or Quick Controls control does on this device (spec §7.6),
 * built by `MainUiState.gate(setting)` from `availability()`. [editable] off
 * dims the control and swallows its input; [unknown] shows "—" for the value;
 * [note] is the line under the row ("Change on ‹hub›", "Sending to ‹hub›…").
 */
@Immutable
data class RowGate(val editable: Boolean = true, val unknown: Boolean = false, val note: String? = null) {
    companion object {
        /** A control this device edits as usual. */
        val Open = RowGate()
    }
}

/** Dims a control and swallows its taps when [gate] doesn't take edits. Its
 *  semantics are replaced too, so an accessibility click can't reach it, and
 *  a focused child's activation keys (Enter, D-pad centre, Space) never
 *  reach it either; focus can still move through. */
fun Modifier.gated(gate: RowGate): Modifier = if (gate.editable) this else this
    .alpha(0.4f)
    .clearAndSetSemantics {
        disabled()
        gate.note?.let { stateDescription = it }
    }
    .onPreviewKeyEvent { it.key in ACTIVATION_KEYS }
    .pointerInput(Unit) {
        // The initial pass reaches this parent before its children, so the
        // control never sees a press it could act on.
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

private val ACTIVATION_KEYS = setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar)

/** [f], or "—" while the value is unknown on this device. */
fun RowGate.fmt(f: (Double) -> String): (Double) -> String = if (unknown) { _ -> "—" } else f

/** The [gate]'s note as a caption, or nothing. */
@Composable
fun GateNote(gate: RowGate, modifier: Modifier = Modifier) {
    val note = gate.note ?: return
    Text(
        note,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 2.dp),
    )
}

/** One caption per distinct note across several controls, naming them:
 *  "Steam, Hot water: Change on Kitchen tablet". */
@Composable
fun GateNotes(controls: List<Pair<String, RowGate>>, modifier: Modifier = Modifier) {
    controls.filter { it.second.note != null }.groupBy({ it.second.note!! }, { it.first }).forEach { (note, labels) ->
        Text(
            "${labels.distinct().joinToString(", ")}: $note",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}
