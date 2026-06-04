package coffee.crema.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import coffee.crema.ui.theme.CremaTheme

/*
 * History (shot log) — M4 v1. A list of completed shots captured on
 * ShotCompleted (newest first), persisted via HistoryStore. Each row shows when
 * it was pulled, the profile · bean, and yield / ratio / time.
 *
 * The list-detail view with the static chart (reusing ShotChart) is the next
 * increment — it adds a downsampled telemetry slice to StoredShot.
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
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp),
                ) {
                    items(ui.history, key = { it.id }) { shot -> ShotRow(shot) }
                }
            }
        }
    }
}

@Composable
private fun ShotRow(shot: StoredShot) {
    CremaCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            }
            val ratio = run {
                val y = shot.yieldG
                val d = shot.doseG
                if (y != null && d != null && d > 0f) "1:%.2f".format(y / d) else null
            }
            val metrics = listOfNotNull(
                shot.yieldG?.let { "%.1f g".format(it) },
                ratio,
                "%.1f s".format(shot.durationMs / 1000.0),
            ).joinToString(" · ")
            Text(
                metrics,
                style = CremaTheme.readout.readoutSm.copy(fontSize = 14.sp, lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
