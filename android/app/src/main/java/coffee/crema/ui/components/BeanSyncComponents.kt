package coffee.crema.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coffee.crema.beans.MergeSuggestion

// ════════════════════════════════════════════════════════════════════════════
// BEAN SYNC + ROASTER DUPLICATES — shared by the tablet and phone shells.
//
//  - [BeanSyncDirectionRows]: Settings → Sharing → Sync, the Beans / Roasters
//    direction rows (web BeanSyncSection), the push modes locked on free tier.
//  - [PremiumPushNotice]: the free-tier explainer under the Sync group.
//  - [RoasterMergeBanner]: the Roasters tab's "X looks like Y" merge prompt.
//  - [DuplicateOfLabel]: the "duplicate of X" tag + Un-merge on a tagged row.
//  - [AlsoDeleteOnVisualizer]: the delete confirm's remote option.
// ════════════════════════════════════════════════════════════════════════════

/** The four sync directions; [pushLocked] greys out the push modes (free tier). */
fun syncDirectionOptions(pushLocked: Boolean, phoneLabels: Boolean = false): List<SegOption> = listOf(
    SegOption("off", "Off"),
    SegOption("backup", if (phoneLabels) "Push" else "Backup", enabled = !pushLocked),
    SegOption("pull", "Pull"),
    SegOption("two-way", if (phoneLabels) "Both" else "Two-way", enabled = !pushLocked),
)

/**
 * The Beans and Roasters direction rows. Web parity: both rows show the bag /
 * roaster count and the last bean-sync time; on a free account (cached
 * `premium == false`) Backup / Two-way are disabled and a "Premium required
 * for push" tag rides in the row title.
 */
@Composable
fun BeanSyncDirectionRows(
    beanCount: Int,
    roasterCount: Int,
    lastSyncLabel: String,
    beansDirection: String,
    roastersDirection: String,
    premium: Boolean?,
    onBeansDirection: (String) -> Unit,
    onRoastersDirection: (String) -> Unit,
    stacked: Boolean,
) {
    val locked = premium == false
    val options = syncDirectionOptions(locked, phoneLabels = stacked)
    val lockNote = if (locked) " Premium required for push." else ""
    CremaSettingsRow(
        "Beans",
        "$beanCount bag(s). Last sync: $lastSyncLabel.$lockNote",
        stacked = stacked,
    ) {
        CremaSegmentedButton(options, beansDirection, onBeansDirection, uniform = !stacked, fillWidth = stacked)
    }
    CremaSettingsRow(
        "Roasters",
        "$roasterCount roaster(s). Last sync: $lastSyncLabel.$lockNote",
        stacked = stacked,
    ) {
        CremaSegmentedButton(options, roastersDirection, onRoastersDirection, uniform = !stacked, fillWidth = stacked)
    }
}

/**
 * Free-tier explainer (web `.bs-premium`): bag and roaster pushes need
 * Visualizer Premium; reads still work; shots are unrestricted.
 */
@Composable
fun PremiumPushNotice(onUpgrade: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhIcon("lock-key", sizeDp = 14, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Bag and roaster pushes need Visualizer Premium. Reads still work — your library picks up remote changes on every sync. Shots are unrestricted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "Upgrade →",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onUpgrade).padding(start = 22.dp, top = 2.dp, bottom = 2.dp),
        )
    }
}

/**
 * "<dupe> looks like <canonical>." with Keep separate / Merge (web
 * `.bn-merge-banner`). Merge asks first, naming how many bags move — the same
 * confirm the web shows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoasterMergeBanner(
    suggestion: MergeSuggestion,
    onKeepSeparate: () -> Unit,
    onMerge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf(false) }
    val dupe = suggestion.dupe.name
    val canonical = suggestion.canonical.name
    CremaCard(
        modifier = modifier.fillMaxWidth(),
        container = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        shape = RoundedCornerShape(14.dp),
    ) {
        // Wide (tablet): the actions sit on the right of the text, like the web
        // banner; narrow (phone): they drop under it.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 600.dp
            val text: @Composable (Modifier) -> Unit = { m ->
                Row(m, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) { PhIcon("link", sizeDp = 16, tint = MaterialTheme.colorScheme.primary) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(dupe) }
                                append(" looks like ")
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(canonical) }
                                append(".")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${suggestion.bagCount} bag(s) from $dupe. Merging will move them to $canonical and tag the duplicate (it stays recoverable via “Show dupes”).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val actions: @Composable () -> Unit = {
                CremaButton(onClick = onKeepSeparate, variant = CremaButtonVariant.Text, label = "Keep separate")
                CremaButton(onClick = { confirm = true }, variant = CremaButtonVariant.Filled, icon = "link", label = "Merge")
            }
            if (wide) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    text(Modifier.weight(1f))
                    actions()
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    text(Modifier.fillMaxWidth())
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) { actions() }
                }
            }
        }
    }
    if (confirm) {
        CremaConfirmDialog(
            title = "Merge roasters?",
            body = "Move ${suggestion.bagCount} bag(s) from “$dupe” to “$canonical” and tag “$dupe” as a duplicate of “$canonical”?",
            confirmLabel = "Merge",
            icon = "link",
            onConfirm = { confirm = false; onMerge() },
            onDismiss = { confirm = false },
        )
    }
}

/**
 * "Duplicate of X" tag with an Un-merge action, on a row tagged as a merged
 * duplicate. A FlowRow, so in a narrow card Un-merge drops under the tag
 * instead of squeezing the canonical name into an ellipsis.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DuplicateOfLabel(canonicalName: String, onUnmerge: () -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PhIcon("link", sizeDp = 11, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "duplicate of $canonicalName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "Un-merge",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onUnmerge).padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

/** The delete confirm's remote option (web "Delete here and on Visualizer"). */
@Composable
fun AlsoDeleteOnVisualizer(checked: Boolean, onChange: (Boolean) -> Unit, what: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) {
            Text("Also delete on Visualizer", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("Removes the uploaded copy of the $what too.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A delete confirm with the remote option (web delete split-button:
 * "Delete from this device" / "Delete here and on Visualizer"). The option
 * shows only when [remoteAvailable] (signed in + the row is on Visualizer);
 * it defaults off, like the web's primary "this device" action.
 */
@Composable
fun DeleteWithVisualizerDialog(
    title: String,
    body: String,
    what: String,
    remoteAvailable: Boolean,
    onConfirm: (alsoOnVisualizer: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var alsoRemote by remember { mutableStateOf(false) }
    CremaConfirmDialog(
        title = title,
        body = body,
        confirmLabel = "Delete",
        icon = "trash",
        danger = true,
        onConfirm = { onConfirm(remoteAvailable && alsoRemote) },
        onDismiss = onDismiss,
        extra = if (remoteAvailable) ({ AlsoDeleteOnVisualizer(alsoRemote, { alsoRemote = it }, what) }) else null,
    )
}

/**
 * The roaster delete confirm (web `RoasterDeleteSplit`): when bags are filed
 * under the roaster, choose **Keep the bags** (detach — the default, like the
 * web's primary click) or **Delete the bags too** (cascade); plus "Also delete
 * on Visualizer" when [remoteAvailable] says the chosen scope has a synced copy.
 */
@Composable
fun RoasterDeleteDialog(
    roasterName: String,
    linkedBagCount: Int,
    remoteAvailable: (cascade: Boolean) -> Boolean,
    onConfirm: (alsoOnVisualizer: Boolean, cascade: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var cascade by remember { mutableStateOf(false) }
    var alsoRemote by remember { mutableStateOf(false) }
    val bags = "$linkedBagCount bag${if (linkedBagCount == 1) "" else "s"}"
    val remote = remoteAvailable(cascade)
    CremaConfirmDialog(
        title = "Delete roaster?",
        body = when {
            linkedBagCount == 0 -> "“$roasterName” will be removed. This can’t be undone."
            cascade -> "“$roasterName” and its $bags will be removed. This can’t be undone."
            else -> "“$roasterName” will be removed. Its $bags stay in your library, unlinked."
        },
        confirmLabel = "Delete",
        icon = "trash",
        danger = true,
        onConfirm = { onConfirm(remote && alsoRemote, cascade && linkedBagCount > 0) },
        onDismiss = onDismiss,
        extra = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (linkedBagCount > 0) {
                    DeleteScopeOption(
                        selected = !cascade,
                        title = "Keep the $bags",
                        sub = "Shown as “No roaster”.",
                        onClick = { cascade = false },
                    )
                    DeleteScopeOption(
                        selected = cascade,
                        title = "Delete the $bags too",
                        sub = "Removes every bag filed under this roaster.",
                        onClick = { cascade = true },
                    )
                }
                if (remote) {
                    AlsoDeleteOnVisualizer(alsoRemote, { alsoRemote = it }, if (cascade) "roaster and its bags" else "roaster")
                }
            }
        },
    )
}

@Composable
private fun DeleteScopeOption(selected: Boolean, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
