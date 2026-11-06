package coffee.crema.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coffee.crema.beans.emptyBagBannerText
import coffee.crema.beans.emptyBagName
import coffee.crema.ui.MainUiState

/** The amber of the Brew banners (the maintenance strip's). */
private val BANNER_AMBER = Color(0xFFDBA764)

/**
 * The active bag is empty (geota/crema#302): a persistent strip on Brew with a
 * Switch bag action opening the one bean picker. It holds nothing up, because a
 * shot can start from the machine's own button, and it goes away while one runs.
 */
@Composable
fun EmptyBagBanner(
    ui: MainUiState,
    onSelectBag: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Remembered: the strip recomposes on every UI-state change and this
    // crosses the FFI.
    val name = remember(ui.beans, ui.activeBeanId, ui.shotInProgress, ui.mirror != null, ui.mirroredBagEmpty) {
        ui.emptyBagName()
    } ?: return
    var pickerOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BANNER_AMBER.copy(alpha = 0.14f))
            .border(1.dp, BANNER_AMBER.copy(alpha = 0.40f), RoundedCornerShape(10.dp))
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag("empty-bag-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PhIcon("warning", sizeDp = 16, tint = BANNER_AMBER)
        Column(Modifier.weight(1f)) {
            Text("Bag empty", style = MaterialTheme.typography.labelLarge, color = BANNER_AMBER)
            Text(
                emptyBagBannerText(name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Switch bag",
            style = MaterialTheme.typography.labelLarge,
            color = BANNER_AMBER,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { pickerOpen = true }
                .heightIn(min = 40.dp)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        )
    }
    if (pickerOpen) {
        BeanPickerDialog(
            beans = ui.beans,
            roasters = ui.roasters,
            currentBeanId = ui.activeBeanId,
            onSelect = { pickerOpen = false; onSelectBag(it) },
            onDismiss = { pickerOpen = false },
            allowArchived = false,
        )
    }
}
