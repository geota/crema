package coffee.crema.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coffee.crema.ble.BleSessionRecorder
import coffee.crema.drive.DRIVE_COPIES_CHOICES
import coffee.crema.drive.DriveSync
import coffee.crema.drive.NEW_INSTALL_DRIVE_COPIES
import coffee.crema.ui.MainUiState
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.relativeAgo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings → Storage and backups (#311), shared by the tablet and phone
 * layouts: how many raw captures and automatic Drive copies this device keeps,
 * how automatic backups run, and what's held. Device-local, not relayed.
 * Lowering a limit says what it would delete before it saves, because raw
 * captures can't be rebuilt and Drive copies go for real.
 */
@Composable
fun StorageBackupsRows(vm: MainViewModel, ui: MainUiState, ds: DriveSync.UiState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingLimit?>(null) }

    CremaSettingsRow(
        "Raw captures to keep",
        "BLE sessions whose raw Bluetooth capture stays, for replays and bug reports. A session holding a pinned shot keeps its capture whatever the limit. Off records none.",
        stacked = true,
    ) {
        CremaSegmentedButton(
            options = BleSessionRecorder.SESSIONS_KEEP_CHOICES.map { SegOption(it.toString(), sessionsLabel(it)) },
            value = ui.bleSessionsKeep.toString(),
            onChange = { id ->
                val keep = id.toInt()
                val doomed = vm.bleSessionsOverLimit(keep)
                if (doomed > 0) {
                    pending = PendingLimit(
                        "Delete $doomed raw capture${if (doomed == 1) "" else "s"}?",
                        "This deletes the raw captures of $doomed older BLE session${if (doomed == 1) "" else "s"} now. They can't be rebuilt; the shots and their curves stay. Sessions holding a pinned shot keep theirs.",
                    ) { vm.setBleSessionsKeep(keep) }
                } else {
                    vm.setBleSessionsKeep(keep)
                }
            },
            fillWidth = true,
        )
    }

    if (ds.connected) {
        val keep = ds.autoBackupKeep ?: NEW_INSTALL_DRIVE_COPIES
        CremaSettingsRow(
            "Google Drive copies to keep",
            "Automatic backups kept on Drive by this device. The fullest copies always stay too, and backups you make yourself are never deleted.",
            stacked = true,
        ) {
            CremaSegmentedButton(
                options = DRIVE_COPIES_CHOICES.map { SegOption(it.toString(), it.toString()) },
                value = keep.toString(),
                onChange = { id ->
                    val next = id.toInt()
                    if (next >= keep) {
                        vm.setDriveCopiesKeep(next)
                    } else {
                        scope.launch {
                            val doomed = vm.driveCopiesOverLimit(next)
                            pending = PendingLimit(
                                "Keep $next automatic cop${if (next == 1) "y" else "ies"}?",
                                when (doomed) {
                                    null -> "Older automatic copies past $next are deleted at the next backup. Drive couldn't be listed to count them."
                                    0 -> "Nothing is deleted now; later backups keep $next."
                                    else -> "The next backup deletes $doomed older automatic cop${if (doomed == 1) "y" else "ies"} from Drive. They go to Drive's trash, where they stay 30 days."
                                },
                            ) { vm.setDriveCopiesKeep(next) }
                        }
                    }
                },
                fillWidth = true,
            )
        }
        CremaSettingsRow("Automatic backup", "How often the automatic backup runs, when the app opens.") {
            CremaSegmentedButton(
                options = listOf(SegOption("daily", "Daily"), SegOption("weekly", "Weekly")),
                value = if (ui.driveBackupWeekly) "weekly" else "daily",
                onChange = { vm.setDriveBackupWeekly(it == "weekly") },
                enabled = ui.autoDriveBackup,
            )
        }
        CremaSettingsRow("Wi-Fi only", "Automatic backups wait for Wi-Fi rather than use mobile data.") {
            CremaSwitch(ui.driveBackupWifiOnly, vm::setDriveBackupWifiOnly, enabled = ui.autoDriveBackup)
        }
    }

    val used by produceState<String?>(null, ui.history.size) {
        value = withContext(Dispatchers.IO) { formatBytes(dirSize(context.filesDir) + (context.getExternalFilesDir(null)?.let(::dirSize) ?: 0L)) }
    }
    CremaSettingsRow("Storage used", "Everything Crema stores on this device.") {
        ValueText(used ?: "—")
    }
    CremaSettingsRow("Pinned shots", "Pin a shot from its detail in History.") {
        ValueText(ui.history.count { it.pinned }.toString())
    }
    CremaSettingsRow("Last backup", "The last backup that reached Google Drive.", last = true) {
        ValueText(ds.lastBackupAtMs?.let { relativeAgo(it) } ?: "Never")
    }

    pending?.let { p ->
        CremaConfirmDialog(
            title = p.title,
            body = p.body,
            confirmLabel = "Delete",
            danger = true,
            onConfirm = { p.apply(); pending = null },
            onDismiss = { pending = null },
        )
    }
}

/** A read-only value, as a settings row's trailing text. */
@Composable
private fun ValueText(text: String) {
    androidx.compose.material3.Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A lower limit waiting for the user's OK. */
private class PendingLimit(val title: String, val body: String, val apply: () -> Unit)

private fun sessionsLabel(keep: Int): String = when (keep) {
    BleSessionRecorder.SESSIONS_OFF -> "Off"
    BleSessionRecorder.SESSIONS_ALL -> "All"
    else -> keep.toString()
}

private fun dirSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

private fun formatBytes(n: Long): String = when {
    n < 1024L * 1024 -> "${maxOf(1, n / 1024)} KB"
    n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / (1024.0 * 1024))
    else -> "%.2f GB".format(n / (1024.0 * 1024 * 1024))
}
