package coffee.crema.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coffee.crema.history.HistoryHealth
import coffee.crema.history.LostChoices
import java.text.DateFormat
import java.util.Date

/** History before its rows land (#310 P3a): placeholder rows, never the empty state. */
@Composable
fun HistoryLoadingSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 24.dp, vertical = 16.dp).semantics { contentDescription = "Loading shot history" },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Loading shot history…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        repeat(6) {
            Box(
                Modifier.fillMaxWidth().height(56.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
            )
        }
    }
}

/**
 * Stays up while the history's store is lost or won't open, or a shot couldn't
 * be saved (SPEC §1 and §6). Lost offers each way back as an explicit choice;
 * nothing is migrated or written until the user picks one.
 */
@Composable
fun HistoryStoreBanner(
    health: HistoryHealth,
    lost: LostChoices?,
    error: String?,
    unsaved: Int,
    busy: Boolean,
    onTryAgain: () -> Unit,
    onSnapshot: () -> Unit,
    onOldCopy: () -> Unit,
    onStartEmpty: () -> Unit,
    modifier: Modifier = Modifier,
    /** A shot that couldn't be saved whole: share the newest such shot, as a file (#310 P6a). */
    onShareUnsaved: (() -> Unit)? = null,
    /** A shot that couldn't be saved whole: open Free up space (#310 P6a). */
    onFreeUp: (() -> Unit)? = null,
) {
    val text = historyBannerText(health, lost, error, unsaved) ?: return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(text, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(2.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                when (health) {
                    HistoryHealth.LOST -> {
                        if (lost?.snapshotRows != null) TextButton(onClick = onSnapshot, enabled = !busy) { Text("Restore the daily snapshot") }
                        if (lost?.oldCopyRows != null) TextButton(onClick = onOldCopy, enabled = !busy) { Text("Recover the old copy") }
                        TextButton(onClick = onStartEmpty, enabled = !busy) { Text("Start empty") }
                    }
                    HistoryHealth.UNAVAILABLE -> TextButton(onClick = onTryAgain, enabled = !busy) { Text("Try again") }
                    else -> if (unsaved > 0) {
                        onShareUnsaved?.let { TextButton(onClick = it) { Text(if (unsaved == 1) "Share this shot" else "Share the newest") } }
                        onFreeUp?.let { TextButton(onClick = it) { Text("Free up space") } }
                    }
                }
            }
        }
    }
}

/** The banner's words, or null when there's nothing to say. */
internal fun historyBannerText(health: HistoryHealth, lost: LostChoices?, error: String?, unsaved: Int): String? {
    fun date(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
    return when (health) {
        HistoryHealth.LOST -> buildString {
            append("Your shot history's database is missing or damaged. Nothing has been changed; choose how to go on.")
            if (lost?.snapshotAtMs != null && lost.snapshotRows != null) append(" Daily snapshot: ${lost.snapshotRows} shots, ${date(lost.snapshotAtMs)}.")
            if (lost?.oldCopyAtMs != null && lost.oldCopyRows != null) append(" Old copy: ${lost.oldCopyRows} shots, ${date(lost.oldCopyAtMs)}.")
            append(" To restore a backup instead, start empty, then restore it from Settings.")
            if (unsaved > 0) append(" $unsaved new shot${if (unsaved == 1) " is" else "s are"} kept aside and will be added.")
        }
        HistoryHealth.UNAVAILABLE ->
            "Couldn't open your shot history${error?.let { " ($it)" }.orEmpty()}. Nothing is lost: new shots are kept aside and added once it opens."
        else -> if (unsaved > 0) {
            "$unsaved shot${if (unsaved == 1) "" else "s"} couldn't be fully saved, likely for lack of space. Crema holds what didn’t save and tries again with each save. Share ${if (unsaved == 1) "it" else "the newest"} to keep a copy, or free up space."
        } else {
            null
        }
    }
}
