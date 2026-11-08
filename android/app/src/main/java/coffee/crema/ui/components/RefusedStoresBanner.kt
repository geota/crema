package coffee.crema.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coffee.crema.persist.RefusedStore

/**
 * Stays up while a store's saves are being refused, because its file couldn't
 * be read: the app looks normal, but changes to that store only last until it
 * closes. Try again reads it again. Set this aside is always there, so an
 * unreadable file is never a dead end: it's a rename, so nothing is lost. Both
 * load the store, so they wait for a shot to end ([busy]).
 */
@Composable
fun RefusedStoresBanner(
    stores: List<RefusedStore>,
    busy: Boolean,
    onTryAgain: (RefusedStore) -> Unit,
    onSetAside: (RefusedStore) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (stores.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            stores.forEach { store ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        refusedStoreText(store),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onTryAgain(store) }, enabled = !busy) { Text("Try again") }
                    TextButton(onClick = { onSetAside(store) }, enabled = !busy) { Text("Set this aside") }
                }
            }
        }
    }
}

/** The banner's line for [store]. */
internal fun refusedStoreText(store: RefusedStore): String =
    "Couldn't read your ${store.what}, so changes aren't being saved. " +
        "Try again reloads what's saved and discards changes made since."
