package coffee.crema.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Which name prompt Quick Controls' Save has open: a built-in's copy, or "Save as new…". */
enum class QuickSavePrompt { Copy, AsNew }

/** The prompt's title. */
val QuickSavePrompt.title: String get() = when (this) {
    QuickSavePrompt.Copy -> "Save a copy"
    QuickSavePrompt.AsNew -> "Save as new profile"
}

/**
 * Quick Controls' name prompt: a copy of a built-in, or "Save as new…" of a
 * custom profile. Nothing is written until Save; Cancel writes nothing.
 */
@Composable
fun QuickSaveNameDialog(
    title: String,
    seed: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(seed) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Profile name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
