package coffee.crema.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coffee.crema.drive.DriveBackupRow
import coffee.crema.drive.subtitle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The Settings row's line under "Restore from Drive". */
const val DRIVE_RESTORE_HINT = "Choose a backup in your Google Drive to merge or replace."

/** When [row] was saved, for the list and the confirm; "unknown date" if Drive didn't say. */
internal fun backupDate(row: DriveBackupRow, zone: ZoneId = ZoneId.systemDefault()): String =
    row.modifiedMs?.let {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .format(Instant.ofEpochMilli(it).atZone(zone))
    } ?: "unknown date"

/**
 * Every backup in Drive, newest first, each with Merge and Replace. The newest
 * copy isn't always the one that's wanted: after damage, the good copy is older,
 * and only Replace brings back a row this device already has.
 */
@Composable
fun DriveBackupPicker(
    rows: List<DriveBackupRow>,
    onMerge: (DriveBackupRow) -> Unit,
    onReplace: (DriveBackupRow) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore from Drive") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(rows, key = { it.id }) { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(backupDate(row), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                row.subtitle(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onMerge(row) }) { Text("Merge") }
                        TextButton(onClick = { onReplace(row) }) { Text("Replace") }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
