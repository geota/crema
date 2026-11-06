package coffee.crema.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coffee.crema.beans.beanPickerMeta
import coffee.crema.beans.beanPickerRows
import coffee.crema.core.Bean
import coffee.crema.core.Roaster
import coffee.crema.ui.phone.DdRow
import coffee.crema.ui.theme.JetBrainsMono

/** How wide and tall the picker grows on a tablet; a phone gets its width. */
private val PICKER_MAX_WIDTH = 520.dp
private val PICKER_MAX_HEIGHT = 560.dp

/** The dimming of an archived row, as on the library's cards. */
private const val ARCHIVED_ROW_ALPHA = 0.6f

/**
 * Choose a bag: the one bean picker for changing a shot's bean and for Log brew
 * (geota/crema#301). A dialog rather than a dropdown so the list keeps its
 * place above the keyboard, on a phone as on a tablet.
 *
 * The core decides the rows ([beanPickerRows]). [onSelect] gets the chosen id,
 * or null for the "No bean" row. [allowArchived] false drops the "Include
 * archived" toggle, for a caller that couldn't use an archived bag.
 */
@Composable
fun BeanPickerDialog(
    beans: List<Bean>,
    roasters: List<Roaster>,
    currentBeanId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    noBeanLabel: String = "No bean",
    allowArchived: Boolean = true,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var includeArchived by rememberSaveable { mutableStateOf(false) }
    val rows = remember(beans, roasters, currentBeanId, includeArchived, query) {
        beanPickerRows(beans, roasters, currentBeanId, includeArchived, query)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .imePadding()
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = PICKER_MAX_WIDTH)
                    .fillMaxWidth()
                    .heightIn(max = PICKER_MAX_HEIGHT)
                    // A tap on the card's own padding must not reach the scrim.
                    .pointerInput(Unit) { detectTapGestures {} }
                    .testTag("bean-picker"),
                shape = RoundedCornerShape(CremaCardSpec.phoneRadius),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.padding(horizontal = 8.dp).padding(top = 8.dp)) {
                        Eyebrow("Library")
                        Text("Choose a bean", style = MaterialTheme.typography.titleLarge)
                    }
                    CremaSearchPill(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = "Search beans, origin, notes…",
                        modifier = Modifier.fillMaxWidth(),
                        compact = true,
                    )
                    if (allowArchived && (rows.hasArchived || includeArchived)) {
                        CremaFilterChip(
                            label = "Include archived",
                            selected = includeArchived,
                            icon = "archive",
                            onClick = { includeArchived = !includeArchived },
                        )
                    }
                    if (rows.beans.isEmpty()) {
                        Text(
                            if (query.isBlank()) "No bags in the library yet." else "No bags match — try a different search.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                        )
                    }
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(rows.beans, key = { it.id }) { b ->
                            BeanPickerRow(
                                bean = b,
                                roaster = roasters.firstOrNull { it.id == b.roasterId }?.name,
                                current = b.id == currentBeanId,
                                onClick = { onSelect(b.id) },
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DdRow(active = currentBeanId == null, onClick = { onSelect(null) }) {
                        PhIcon("prohibit", sizeDp = 18, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(noBeanLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        if (currentBeanId == null) PhIcon("check-circle", sizeDp = 18, tint = MaterialTheme.colorScheme.primary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

/** One bag: roaster mark, "Roaster · Name", its meta line, and the current mark. */
@Composable
private fun BeanPickerRow(bean: Bean, roaster: String?, current: Boolean, onClick: () -> Unit) {
    DdRow(active = current, onClick = onClick) {
        val dim = if (bean.archivedAt != null) Modifier.alpha(ARCHIVED_ROW_ALPHA) else Modifier
        Box(dim) {
            RoasterMarkAvatar(name = roaster ?: bean.name, sizeDp = 30, cornerDp = 8, fontSize = 13.sp)
        }
        Column(Modifier.weight(1f).then(dim)) {
            Text(
                listOfNotNull(roaster, bean.name).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                beanPickerMeta(bean),
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp),
                color = if (current) LocalContentColor.current.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (current) PhIcon("check-circle", sizeDp = 18, tint = MaterialTheme.colorScheme.primary)
    }
}
