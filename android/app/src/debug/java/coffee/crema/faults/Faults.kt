package coffee.crema.faults

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

// Debug build only: the shipped builds' twin of this file
// (`src/shipped/java/coffee/crema/faults/Faults.kt`) has the same signatures
// and does nothing.

/** The app's injector: armable from the Debug screen. */
internal fun installedFaults(log: (String) -> Unit): FaultInjector =
    DebugFaults(now = SystemClock::elapsedRealtime, log = log)

/** What [status] arms, in words, at [nowMs]; empty when nothing is. */
internal fun describe(status: FaultStatus, nowMs: Long): String = buildList {
    if (status.stopsToDrop > 0) add("dropping the next ${status.stopsToDrop} Stop write(s)")
    if (status.dropNextRequest) add("dropping the next requested-state write")
    status.weightUntilMs?.let { until ->
        if (nowMs < until) add("dropping weight frames for ${(until - nowMs + 999) / 1000} s more")
    }
}.joinToString("; ")

/** The clock, re-read every half second while something is armed, so a
 *  weight window's end shows without a frame arriving. */
@Composable
private fun armedClock(status: FaultStatus): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(status) {
        now = SystemClock.elapsedRealtime()
        while (status.active(now)) {
            delay(500)
            now = SystemClock.elapsedRealtime()
        }
    }
    return now
}

/** The Debug screen's fault controls (DESIGN.md §7: H2, H11, H14, H15). */
@Composable
internal fun FaultInjectionPanel(faults: FaultInjector) {
    val debug = faults as? DebugFaults ?: return
    val status by debug.status.collectAsStateWithLifecycle()
    val now = armedClock(status)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Fault injection (debug build only)", style = MaterialTheme.typography.titleMedium)
        Text(
            "For the hardware test list. Keep a hand on the machine's stop.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Drop the next Stop writes", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in listOf(1, 2, 3, 5)) {
                Button(onClick = { debug.armStops(n) }) { Text("$n") }
            }
            OutlinedButton(onClick = { debug.armStops(0) }) { Text("Off") }
        }
        Text("Drop the next plain requested-state write", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { debug.armRequest(true) }) { Text("Arm") }
            OutlinedButton(onClick = { debug.armRequest(false) }) { Text("Off") }
        }
        Text("Drop weight frames for", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in listOf(5, 10, 30, 60)) {
                Button(onClick = { debug.armWeight(s) }) { Text("$s s") }
            }
            OutlinedButton(onClick = { debug.armWeight(0) }) { Text("Off") }
        }
        val armed = describe(status, now)
        Text(
            if (armed.isEmpty()) "Nothing armed." else "Armed: $armed.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = debug::disarmAll) { Text("Disarm all") }
    }
}

/** "Fault injection active", across the app while anything is armed. */
@Composable
internal fun FaultInjectionBanner(faults: FaultInjector) {
    val debug = faults as? DebugFaults ?: return
    val status by debug.status.collectAsStateWithLifecycle()
    val now = armedClock(status)
    if (!status.active(now)) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Fault injection active: ${describe(status, now)}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
