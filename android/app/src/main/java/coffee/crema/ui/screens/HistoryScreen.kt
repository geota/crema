package coffee.crema.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.history.StoredShot
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaNavigationRail
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.theme.CremaTheme

/*
 * History (shot log) — M4. A master-detail over the captured shots: the list on
 * the left, and a detail pane on the right with a metric strip + the shot's
 * static chart (ShotChart with live=false) over its stored telemetry slice.
 */
@Composable
fun HistoryScreen(
    vm: MainViewModel,
    onNav: (String) -> Unit,
    onConnect: (String) -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val connected = ui.bleState == De1BleManager.State.READY
    val scaleConnected = ui.scaleState == ScaleBleManager.State.READY
    var selectedId by remember { mutableStateOf<String?>(null) }
    // Default the detail to the newest shot until the user picks one.
    val selected = ui.history.firstOrNull { it.id == selectedId } ?: ui.history.firstOrNull()

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CremaNavigationRail(
            active = "history",
            onNav = onNav,
            machineConnected = connected,
            scaleConnected = scaleConnected,
            onConnect = onConnect,
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Column(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "History",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${ui.history.size} shots",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ui.history.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No shots yet — pull one on Brew.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Row(
                    Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LazyColumn(
                        modifier = Modifier.width(320.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                    ) {
                        items(ui.history, key = { it.id }) { shot ->
                            ShotRow(
                                shot = shot,
                                selected = shot.id == selected?.id,
                                onClick = { selectedId = shot.id },
                            )
                        }
                    }
                    if (selected != null) {
                        ShotDetail(
                            shot = selected,
                            channels = ui.chartChannels,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShotRow(shot: StoredShot, selected: Boolean, onClick: () -> Unit) {
    CremaCard(
        modifier = Modifier.fillMaxWidth(),
        container = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Column(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                DateUtils.getRelativeTimeSpanString(shot.completedAtMs).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOfNotNull(shot.profileName, shot.beanName).joinToString(" · ").ifEmpty { "Shot" },
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp, lineHeight = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                shotMetrics(shot),
                style = CremaTheme.readout.readoutSm.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShotDetail(shot: StoredShot, channels: Set<String>, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stat("Yield", shot.yieldG?.let { "%.1f g".format(it) } ?: "—")
            Stat("Ratio", shotRatio(shot) ?: "—")
            Stat("Time", "%.1f s".format(shot.durationMs / 1000.0))
            Stat("Peak P", shot.peakPressure?.let { "%.1f bar".format(it) } ?: "—")
            Stat("Peak T", shot.peakTemp?.let { "%.0f °C".format(it) } ?: "—")
        }
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            if (shot.samples.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No telemetry recorded for this shot.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ShotChart(
                    samples = shot.samples,
                    enabledChannels = channels,
                    live = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Eyebrow(label)
        Text(
            value,
            style = CremaTheme.readout.readoutSm.copy(fontSize = 15.sp, lineHeight = 19.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun shotRatio(shot: StoredShot): String? {
    val y = shot.yieldG
    val d = shot.doseG
    return if (y != null && d != null && d > 0f) "1:%.2f".format(y / d) else null
}

private fun shotMetrics(shot: StoredShot): String = listOfNotNull(
    shot.yieldG?.let { "%.1f g".format(it) },
    shotRatio(shot),
    "%.1f s".format(shot.durationMs / 1000.0),
).joinToString(" · ")
