package coffee.crema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.history.IndexHealth
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.theme.JetBrainsMono
import kotlinx.coroutines.launch

/**
 * The stage-1 history index's health (#310 A1), in the debug panel: whether
 * it's open, `pending`, the `versionCode` of its last full diff, its rows
 * and its seq against `shots.db`'s, hook failures and the last diff.
 * "Compare" checks every row's `row_json`; "Rebuild" runs a full diff. Only
 * behind the debug flag (Settings → Diagnostics); nothing else reads the index.
 */
@Composable
fun HistoryIndexDebugPanel(vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    var health by remember { mutableStateOf<IndexHealth?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    suspend fun refresh() {
        health = runCatching { vm.historyIndexHealth() }.getOrNull()
    }
    LaunchedEffect(Unit) { refresh() }
    fun run(block: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            result = runCatching { block() }.getOrElse { "failed: ${it.message}" }
            refresh()
            busy = false
        }
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("History index (stage 1)", style = MaterialTheme.typography.labelLarge)
        for (line in historyIndexLines(health) + listOfNotNull(result)) {
            Text(
                line,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono, fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CremaButton(onClick = { run { "refreshed" } }, variant = CremaButtonVariant.Text, enabled = !busy, label = "Refresh")
            CremaButton(
                onClick = { run { vm.compareHistoryIndex()?.toString() ?: "not open" } },
                variant = CremaButtonVariant.Text,
                enabled = !busy,
                label = "Compare",
            )
            CremaButton(
                onClick = { run { vm.rebuildHistoryIndex()?.let { "diff: ${it.rows} rows, ${it.upserted} written, ${it.removed} removed: ${it.outcome}" } ?: "nothing ran" } },
                variant = CremaButtonVariant.Text,
                enabled = !busy,
                label = "Rebuild",
            )
        }
    }
}

/** The panel's lines for [h]. */
internal fun historyIndexLines(h: IndexHealth?): List<String> {
    if (h == null) return listOf("reading…")
    val out = ArrayList<String>()
    out += if (h.open) "open, ${h.bytes / 1024} KB" else "not open"
    h.error?.let { out += "last error: $it" }
    out += "rows: index ${h.indexRows ?: "?"}, shots.db ${h.shotsRows ?: "?"}"
    out += "pending ${h.pending ?: "?"}; last full diff on versionCode ${h.diffVersionCode ?: "none"} (this is ${h.versionCode})"
    out += "seq: index ${h.sourceSeq ?: "?"}${if (h.seenSeqs.isEmpty()) "" else " (+${h.seenSeqs.size} past a gap)"}, shots.db ${h.shotsSeq ?: "?"}"
    if (h.hookFailures > 0) out += "hook failures this process: ${h.hookFailures}"
    out += if (h.misses.isEmpty()) "boot check: passes" else "boot check misses: ${h.misses.joinToString("; ")}"
    h.lastDiff?.let { d ->
        out += "last diff: ${d.rows} rows, ${d.upserted} written, ${d.removed} removed, ${d.tookMs} ms: ${d.outcome}"
    }
    return out
}
