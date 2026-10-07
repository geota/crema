package coffee.crema.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.ble.ReconnectOutcome
import coffee.crema.diag.ReconnectTimelines
import coffee.crema.ui.theme.JetBrainsMono

/**
 * The compact "Retry now" pill shown beside a reconnecting device's status —
 * the device rows (phone sheet, tablet tiles) and the Settings machine /
 * scale sections. A tap kicks that device's reconnect immediately (trigger
 * `user-retry`). 40 dp tall so it stays a comfortable touch target.
 */
@Composable
fun RetryNowPill(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onRetry,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.heightIn(min = 32.dp).semantics { role = Role.Button },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PhIcon("arrows-clockwise", sizeDp = 14)
            Text(
                "Retry now",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
        }
    }
}

/**
 * A "Reconnecting to the <device>…  [Retry now]" strip for the Settings
 * sections, where the hero card only knows connected / not connected.
 */
@Composable
fun ReconnectingNotice(device: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PhIcon("bluetooth", sizeDp = 16, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Reconnecting to the $device…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            RetryNowPill(onRetry)
        }
    }
}

/**
 * Settings → Advanced: the last reconnects, one per row, newest first — the
 * trigger, how long until READY, and where the time went phase by phase.
 * The same lines are in "Copy diagnostics".
 */
@Composable
fun ReconnectTimelineList(modifier: Modifier = Modifier) {
    val timelines by ReconnectTimelines.recorder.timelines.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Reconnects",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "The last ${ReconnectTimelines.recorder.capacity} reconnects: what started each one and how long every step took.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (timelines.isEmpty()) {
            Text(
                "No reconnects yet.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        timelines.forEach { t ->
            val at = android.text.format.DateFormat.format("HH:mm:ss", t.startedWallMs)
            Text(
                "$at  ${t.compactLine()}",
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp),
                color = if (t.outcome == ReconnectOutcome.READY || t.outcome == ReconnectOutcome.IN_PROGRESS) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

/**
 * The one-time offer after a device is first remembered: "Reconnect
 * automatically when it's nearby?" — "Set up" opens the system's association
 * dialog (filtered to that device), "Not now" never asks again for it.
 */
@Composable
fun CompanionOfferDialog(device: String, onSetUp: () -> Unit, onNotNow: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text("Reconnect automatically when it's nearby?") },
        text = {
            Text(
                "Android asks you to confirm the $device once. After that, Crema reconnects as soon as " +
                    "it's back in range — even while Crema is in the background — without searching for it. " +
                    "You can turn this off in Settings.",
            )
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onSetUp) { Text("Set up") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onNotNow) { Text("Not now") } },
    )
}

/**
 * Settings row for a device's companion association: its state and a way to
 * set it up or remove it. Hidden by the caller when the platform lacks
 * companion-device support; disabled until the device is remembered.
 */
@Composable
fun CompanionSettingsRow(
    device: String,
    associated: Boolean,
    remembered: Boolean,
    onSetUp: () -> Unit,
    onRemove: () -> Unit,
    last: Boolean = false,
) {
    CremaSettingsRow(
        "Reconnect when nearby",
        when {
            associated -> "On — Android tells Crema when the $device is back in range, and Crema reconnects right away."
            remembered -> "Off — set up once and Crema reconnects as soon as the $device is back in range, even in the background."
            else -> "Connect the $device once first."
        },
        last = last,
    ) {
        if (associated) {
            CremaButton(onClick = onRemove, variant = CremaButtonVariant.Text, danger = true, label = "Remove")
        } else {
            CremaButton(onClick = onSetUp, variant = CremaButtonVariant.Outlined, enabled = remembered, label = "Set up")
        }
    }
}
