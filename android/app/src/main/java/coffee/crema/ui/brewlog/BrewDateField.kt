package coffee.crema.ui.brewlog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coffee.crema.history.brewDateError
import coffee.crema.ui.components.CremaDateTimeField

/**
 * Change a saved brew's date. A refused date stays in the field with its
 * message until it's corrected, as on the web; only a good one reaches [onCommit].
 */
@Composable
fun BrewDateEditor(
    completedAtMs: Long,
    onCommit: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var refused by remember(completedAtMs) { mutableStateOf<Long?>(null) }
    val error = refused?.let { brewDateError(it, System.currentTimeMillis()) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CremaDateTimeField(
            valueMs = refused ?: completedAtMs,
            onValueChange = { picked ->
                if (picked == null) return@CremaDateTimeField
                if (brewDateError(picked, System.currentTimeMillis()) != null) {
                    refused = picked
                } else {
                    refused = null
                    onCommit(picked)
                }
            },
            label = "When",
        )
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
