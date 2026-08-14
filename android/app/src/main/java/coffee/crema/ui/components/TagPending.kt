package coffee.crema.ui.components

import androidx.compose.runtime.MutableState

/**
 * Folds text still sitting in a tag input into [tags] (trimmed, case-insensitive
 * dedupe) and clears the field. The tag inputs commit on IME Done and focus
 * loss, but tapping Save doesn't move focus off the field — so every editor's
 * Save also runs this, otherwise a typed-but-uncommitted tag is silently
 * dropped (geota/crema#73).
 */
fun commitPendingTag(tags: MutableList<String>, pending: MutableState<String>) {
    val t = pending.value.trim()
    if (t.isNotEmpty() && tags.none { it.equals(t, ignoreCase = true) }) tags.add(t)
    pending.value = ""
}
