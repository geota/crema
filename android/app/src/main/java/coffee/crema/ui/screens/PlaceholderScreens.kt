package coffee.crema.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coffee.crema.ui.components.CremaIconButton
import coffee.crema.ui.components.Eyebrow

/*
 * Pushed editor placeholders (no rail; back + breadcrumb).
 *
 * All six rail destinations (Brew, Profiles, Beans, History, Scale, Settings)
 * are now live VM-driven screens; only the profile / bean editors remain stubbed
 * (milestone: M3 editors).
 */

@Composable
fun ProfileEditScreen(onBack: () -> Unit) = EditorPlaceholder("Profiles", "Edit profile", onBack)

@Composable
private fun EditorPlaceholder(crumbRoot: String, crumbLeaf: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CremaIconButton(icon = "arrow-left", onClick = onBack)
            Eyebrow("$crumbRoot › $crumbLeaf")
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Eyebrow(crumbLeaf)
                Text(
                    crumbLeaf,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Full editor — milestone M3.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
