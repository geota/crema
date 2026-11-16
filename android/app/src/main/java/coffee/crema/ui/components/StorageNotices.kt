package coffee.crema.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coffee.crema.history.FreeUpBackup
import coffee.crema.history.FreeUpPlan
import coffee.crema.history.StorageLevel
import coffee.crema.history.StorageSpace
import coffee.crema.ui.MainUiState
import coffee.crema.ui.MainViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/*
 * The storage notices and Free up space (#310 P6a, SPEC §1 and §8). With no cap
 * on the history the disk is the limit, so the app says when it runs low and
 * offers two ways on: back up, and free up space. Nothing is deleted unless the
 * user picks a date, sees what goes, and confirms.
 */

/** The storage banner's words, or null when there's nothing to say. Getting full is one notice; nearly full lasts. */
internal fun storageBannerText(level: StorageLevel, dismissed: Boolean): String? = when (level) {
    StorageLevel.NEARLY_FULL ->
        "This device is nearly out of space. New shots may not save whole. Back up, then free up space: Crema never deletes shots on its own."
    StorageLevel.GETTING_FULL -> if (dismissed) null else
        "This device is getting full. Back up and free up space while there's room: Crema never deletes shots on its own."
    StorageLevel.OK -> null
}

/**
 * Says the device is getting full (dismissible, once) or nearly full (lasting),
 * with Back up and Free up space.
 */
@Composable
fun StorageBanner(
    level: StorageLevel,
    dismissed: Boolean,
    onBackUp: () -> Unit,
    onFreeUp: () -> Unit,
    onDismiss: () -> Unit,
    backingUp: Boolean,
    modifier: Modifier = Modifier,
) {
    val text = storageBannerText(level, dismissed) ?: return
    val lasting = level == StorageLevel.NEARLY_FULL
    Surface(
        color = if (lasting) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (lasting) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(text, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(2.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBackUp, enabled = !backingUp) { Text(if (backingUp) "Backing up…" else "Back up") }
                TextButton(onClick = onFreeUp) { Text("Free up space") }
                if (!lasting) TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/** How far back Free up space reaches: shots dated before this many months ago. */
internal val FREE_UP_CHOICES = listOf(6 to "6 months", 12 to "1 year", 24 to "2 years", 36 to "3 years", 60 to "5 years")

/** The start of the day [months] before [today]: Free up space deletes unpinned shots dated before it. */
internal fun freeUpCutoff(months: Int, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
    today.minusMonths(months.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()

/** The preview's words for [plan] (null: still counting, or the history isn't fully read). */
internal fun freeUpBody(plan: FreeUpPlan?, counting: Boolean, beforeMs: Long, backup: FreeUpBackup = FreeUpBackup.FILE): String {
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(beforeMs))
    if (plan == null) {
        return if (counting) "Counting the shots from before $date…"
        else "Free up space waits until your shot history is fully read, so a pinned shot it hasn't read yet can't be deleted."
    }
    val pinned = when (plan.pinnedKept) {
        0 -> ""
        1 -> " 1 pinned shot from then is kept."
        else -> " ${plan.pinnedKept} pinned shots from then are kept."
    }
    val unsent = when (plan.syncKept) {
        0 -> ""
        1 -> " 1 shot still waiting to reach Visualizer is kept."
        else -> " ${plan.syncKept} shots still waiting to reach Visualizer are kept."
    }
    if (plan.ids.isEmpty()) return "No unpinned shots from before $date to delete.$pinned$unsent"
    val n = plan.ids.size
    val first = when (backup) {
        FreeUpBackup.NONE -> "Without a backup, they can come back only from Visualizer or an older backup. This can't be undone."
        else -> "It backs up first, then deletes only the shots the backup holds whole, with their curves; any it doesn't stay. " +
            "If the backup doesn't finish or isn't saved, nothing is deleted."
    }
    return "Deletes $n shot${if (n == 1) "" else "s"} from before $date from this device, about ${StorageSpace.format(plan.bytes)}, " +
        "with ${if (n == 1) "its curve" else "their curves"}.$pinned$unsent Bag weights don't change, and copies on Visualizer stay there. $first"
}

/**
 * Free up space (#310 P6a): pick how far back, see exactly how many shots go
 * and about how much space, pick where to back up, then confirm. The confirm
 * runs "back up, then delete" as one flow in the view model (REVIEW-440 B1):
 * closing the dialog cancels nothing, and only what the backup holds whole is
 * deleted. Pinned shots are never deleted. Settings → Storage and backups and
 * the storage banners open it.
 */
@Composable
fun FreeUpSpaceDialog(
    vm: MainViewModel,
    ui: MainUiState,
    onDismiss: () -> Unit,
) {
    var months by rememberSaveable { mutableStateOf(12) }
    var backup by rememberSaveable { mutableStateOf(if (ui.drive.connected) FreeUpBackup.DRIVE else FreeUpBackup.FILE) }
    val today = remember { LocalDate.now() }
    val beforeMs = remember(months, today) { freeUpCutoff(months, today) }
    // Counted again as the rows change, so the preview is what a confirm deletes.
    val state by produceState<Pair<Boolean, FreeUpPlan?>>(true to null, beforeMs, ui.history.version, ui.historyLoaded) {
        value = true to null
        value = false to vm.planFreeUp(beforeMs)
    }
    val (counting, plan) = state
    // A backup running now would write the shots this deletes without their curves: wait for it.
    val busy = ui.backupProgress.isNotEmpty() || ui.drive.busy || ui.freeUpRunning
    val n = plan?.ids?.size ?: 0
    CremaConfirmDialog(
        title = "Free up space",
        body = freeUpBody(plan, counting, beforeMs, backup) +
            if (busy) " A backup is running: Free up space waits for it." else "",
        confirmLabel = when {
            n == 0 -> "Delete"
            backup == FreeUpBackup.NONE -> "Delete $n shot${if (n == 1) "" else "s"}"
            else -> "Back up, then delete $n"
        },
        danger = true,
        icon = "trash",
        armed = !counting && !busy && plan != null && n > 0,
        onConfirm = {
            plan?.let { vm.freeUpSpace(it, backup) }
            onDismiss()
        },
        onDismiss = onDismiss,
        extra = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shots older than", style = MaterialTheme.typography.labelMedium)
                CremaSegmentedButton(
                    options = FREE_UP_CHOICES.map { (m, label) -> SegOption(m.toString(), label) },
                    value = months.toString(),
                    onChange = { months = it.toInt() },
                    fillWidth = true,
                )
                Text("Back up first", style = MaterialTheme.typography.labelMedium)
                CremaSegmentedButton(
                    options = buildList {
                        add(SegOption(FreeUpBackup.FILE.name, "To a file"))
                        if (ui.drive.connected) add(SegOption(FreeUpBackup.DRIVE.name, "To Google Drive"))
                        add(SegOption(FreeUpBackup.NONE.name, "Don't back up"))
                    },
                    value = backup.name,
                    onChange = { backup = FreeUpBackup.valueOf(it) },
                    fillWidth = true,
                )
            }
        },
    )
}

/**
 * The banners' Back up: to Drive when it's connected, else a `.crema.zip`
 * through the save picker. Returns the action and whether one is running.
 */
@Composable
fun rememberBackUpAction(vm: MainViewModel, ui: MainUiState, launchSaveFile: (name: String, file: java.io.File?) -> Unit): Pair<() -> Unit, Boolean> {
    val scope = rememberCoroutineScope()
    var building by remember { mutableStateOf(false) }
    val busy = building || (ui.drive.connected && ui.drive.busy)
    val action: () -> Unit = {
        if (ui.drive.connected) {
            vm.drive.backupNow()
        } else if (!building) {
            building = true
            scope.launch {
                try {
                    launchSaveFile(vm.backupFileName(), vm.buildBackupZipFile())
                } finally {
                    building = false
                }
            }
        }
    }
    return action to busy
}
