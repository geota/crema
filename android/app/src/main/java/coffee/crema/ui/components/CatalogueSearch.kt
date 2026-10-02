package coffee.crema.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.beans.CatalogueSearchController
import coffee.crema.beans.subline
import coffee.crema.core.CatalogueCoffeeBag
import coffee.crema.core.CataloguePage

/**
 * The bean editors' "Search Visualizer catalogue" field (web `CatalogueSearch`
 * parity): the app search pill, debounced through [CatalogueSearchController],
 * with the results inline beneath it (name + roaster · country · process).
 * Picking a row hands it to [onPick] with the "replace filled fields" choice;
 * the editor applies the core autofill rule (empty fields only unless
 * replacing). [linkedLabel] shows the current catalogue link with an unlink ✕.
 */
@Composable
fun CatalogueSearchField(
    search: suspend (String) -> CataloguePage,
    onPick: (CatalogueCoffeeBag, replaceAll: Boolean) -> Unit,
    linkedLabel: String?,
    onUnlink: () -> Unit,
    status: String?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val controller = remember { CatalogueSearchController(scope, search) }
    val st by controller.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var replaceAll by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CremaSearchPill(
                query = query,
                onQueryChange = {
                    query = it
                    controller.setQuery(it)
                },
                placeholder = "Search Visualizer catalogue",
                modifier = Modifier.weight(1f),
            )
            if (st.loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            }
        }

        if (st.query.length >= 2 && (st.results.isNotEmpty() || st.error != null || !st.loading)) {
            CremaMenuSurface(Modifier.fillMaxWidth()) {
                st.results.forEach { entry ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onPick(entry, replaceAll)
                                query = ""
                                controller.clear()
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(entry.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        val sub = entry.subline()
                        if (sub.isNotEmpty()) {
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val hint = when {
                    st.error != null -> st.error
                    st.results.isEmpty() && !st.loading -> "No catalogue matches for “${st.query}”."
                    else -> null
                }
                if (hint != null) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (st.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { replaceAll = !replaceAll },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = replaceAll, onCheckedChange = { replaceAll = it })
                Text("Replace fields I've already filled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (linkedLabel != null) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
            ) {
                Row(
                    Modifier.padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PhIcon("link", sizeDp = 16, tint = MaterialTheme.colorScheme.primary, contentDescription = null)
                    Text(
                        linkedLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    PhIcon(
                        "x",
                        sizeDp = 16,
                        tint = MaterialTheme.colorScheme.primary,
                        contentDescription = "Unlink catalogue entry",
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onUnlink).padding(4.dp),
                    )
                }
            }
        }
        if (status != null) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
