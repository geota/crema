package coffee.crema.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coffee.crema.ui.components.CremaSwitch
import coffee.crema.ui.components.Eyebrow

/*
 * Quick Controls — the Brew header's bottom sheet (the prototype's
 * M3ModalBottomSheet + QuickControlsContent).
 *
 * v1: the shot-behaviour toggles, wired to the core via MainViewModel
 * (set_auto_tare / set_stop_on_weight / enable_steam_eco_mode). The 6-up param
 * stepper grid (Dose↔Grind, Yield↔Ratio, Brew temp, Steam, Hot water,
 * Pre-inf↔Flush), the chart channel-visibility toggles, and the favorites strip
 * follow in later increments (they need a brew-params model + the ShotChart
 * channel generalization).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickControlsSheet(
    autoTare: Boolean,
    stopOnWeight: Boolean,
    steamEco: Boolean,
    onAutoTare: (Boolean) -> Unit,
    onStopOnWeight: (Boolean) -> Unit,
    onSteamEco: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Quick Controls",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Eyebrow("Shot behaviour", modifier = Modifier.padding(top = 8.dp))
            ToggleRow("Auto-tare", autoTare, onAutoTare)
            ToggleRow("Stop on weight", stopOnWeight, onStopOnWeight)
            ToggleRow("Steam eco", steamEco, onSteamEco)
            Text(
                "Chart channels and dose / yield steppers arrive next.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        CremaSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
