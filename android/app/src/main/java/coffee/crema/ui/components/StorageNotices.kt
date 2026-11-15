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
internal fun freeUpBody(plan: FreeUpPlan?, counting: Boolean, beforeMs: Long): String {
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
    if (plan.ids.isEmpty()) return "No unpinned shots from before $date.$pinned"
    val n = plan.ids.size
    return "Deletes $n shot${if (n == 1) "" else "s"} from before $date from this device, about ${StorageSpace.format(plan.bytes)}, " +
        "with ${if (n == 1) "its curve" else "their curves"}.$pinned Bag weights don't change. " +
        "Copies on Visualizer stay there, and a backup brings ${if (n == 1) "it" else "them"} back. This can't be undone on this device, so back up first."
}

/**
 * Free up space (#310 P6a): pick how far back, see exactly how many shots go
 * and about how much space, back up first, then confirm. Pinned shots are
 * never deleted. Settings → Storage and backups and the storage banners open it.
 */
@Composable
fun FreeUpSpaceDialog(
    vm: MainViewModel,
    ui: MainUiState,
    launchSaveFile: (name: String, file: java.io.File?) -> Unit,
    onDismiss: () -> Unit,
) {
    var months by rememberSaveable { mutableStateOf(12) }
    val today = remember { LocalDate.now() }
    val beforeMs = remember(months, today) { freeUpCutoff(months, today) }
    // Counted again as the rows change, so the preview is what a confirm deletes.
    val state by produceState<Pair<Boolean, FreeUpPlan?>>(true to null, beforeMs, ui.history, ui.historyLoaded) {
        value = true to null
        value = false to vm.planFreeUp(beforeMs)
    }
    val (counting, plan) = state
    CremaConfirmDialog(
        title = "Free up space",
        body = freeUpBody(plan, counting, beforeMs),
        confirmLabel = plan?.ids?.size?.takeIf { it > 0 }?.let { "Delete $it shot${if (it == 1) "" else "s"}" } ?: "Delete",
        danger = true,
        icon = "trash",
        armed = !counting && plan != null && plan.ids.isNotEmpty(),
        onConfirm = {
            plan?.let(vm::freeUpSpace)
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    coffee.crema.ui.screens.BackUpEverythingButton(vm, launchSaveFile)
                    if (ui.drive.connected) {
                        CremaButton(
                            onClick = { vm.drive.backupNow() },
                            variant = CremaButtonVariant.Outlined,
                            icon = "cloud-arrow-up",
                            enabled = !ui.drive.busy,
                            label = "Back up to Drive",
                        )
                    }
                }
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
