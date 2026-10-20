package coffee.crema.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** How many name suggestions show under a person field. */
private const val PERSON_SUGGESTION_CAP = 4

/**
 * The names to offer under a person field: [names] (most recent first) that
 * start with what's typed, minus an exact match, capped. Empty input offers the
 * most recent names. Pure, for the JVM tests.
 */
fun personSuggestions(names: List<String>, typed: String): List<String> {
    val t = typed.trim()
    return names
        .filter { it.startsWith(t, ignoreCase = true) && !it.equals(t, ignoreCase = true) }
        .take(PERSON_SUGGESTION_CAP)
}

/**
 * Barista + drinker for a shot (dye2 audit #1): two text fields side by side,
 * each with chips for names used before (most recent first, filtered by what's
 * typed). Every change persists through [onChange]; blank clears. Keyed on
 * [shotId] so a selection change resets the drafts.
 */
@Composable
fun ShotPeopleFields(
    shotId: String,
    barista: String?,
    drinker: String?,
    baristaNames: List<String>,
    drinkerNames: List<String>,
    onChange: (field: String, name: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PersonField(shotId, "Barista", "Who made it", barista, baristaNames, { onChange("barista", it) }, Modifier.weight(1f))
        PersonField(shotId, "Drinker", "Who drank it", drinker, drinkerNames, { onChange("drinker", it) }, Modifier.weight(1f))
    }
}

@Composable
private fun PersonField(
    shotId: String,
    label: String,
    placeholder: String,
    value: String?,
    names: List<String>,
    onChange: (String?) -> Unit,
    modifier: Modifier,
) {
    var text by remember(shotId) { mutableStateOf(value ?: "") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CremaTextField(
            value = text,
            onValueChange = { text = it; onChange(it.trim().ifEmpty { null }) },
            label = label,
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth(),
        )
        val suggestions = personSuggestions(names, text)
        if (suggestions.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { name ->
                    CremaFilterChip(label = name, selected = false, onClick = { text = name; onChange(name) })
                }
            }
        }
    }
}
