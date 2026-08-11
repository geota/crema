package coffee.crema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coffee.crema.core.Bean
import coffee.crema.ui.beans.BeanDetailContent
import coffee.crema.ui.beans.ShotRowSummary
import coffee.crema.ui.components.BeanPhotoViewer
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaConfirmDialog
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon

/*
 * BeanDetailSheet — the tablet's read-only bean detail (issue 61).
 *
 * A right-side panel, 420 dp, full height, over a scrim: Material 3's
 * expanded-width guidance is to put detail *beside* its context rather than
 * over it, and it mirrors the web drawer so the two shells read identically.
 * Compose Material3 ships no side-sheet primitive, so this is a full-screen
 * `Dialog` (usePlatformDefaultWidth = false) holding a right-aligned Surface —
 * which also gets back-dismissal and correct focus handling for free.
 *
 * Everything below the header is [BeanDetailContent], shared with the phone.
 */
@Composable
fun BeanDetailSheet(
    bean: Bean,
    roasterName: String?,
    linkedProfileName: String?,
    shotCount: Int,
    recentShots: List<ShotRowSummary>,
    isActive: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onSetActive: () -> Unit,
    onToggleFavourite: () -> Unit,
    onToggleArchived: () -> Unit,
    onDelete: () -> Unit,
    /** Open one of this bag's shots in History; null = rows not tappable. */
    onOpenShot: ((String) -> Unit)? = null,
    /** Open History filtered to this bag ("See all N shots"); null = hidden. */
    onSeeAllShots: (() -> Unit)? = null,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var photoOpen by remember { mutableStateOf(false) }
    val archived = bean.archivedAt != null

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(Modifier.fillMaxSize()) {
            // Scrim: dismisses on tap, and no ripple — it is a backdrop, not a
            // control. The library stays legible behind it.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.width(420.dp).fillMaxHeight(),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Eyebrow(roasterName ?: "No roaster")
                            Text(
                                bean.name.ifBlank { "Untitled bag" },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (isActive) {
                            Surface(
                                shape = MaterialTheme.shapes.extraLarge,
                                color = MaterialTheme.colorScheme.primary,
                            ) {
                                Text(
                                    "ACTIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        IconButton(onClick = onToggleFavourite, modifier = Modifier.size(38.dp)) {
                            PhIcon(
                                if (bean.favourite == true) "star-fill" else "star",
                                sizeDp = 18,
                                tint = if (bean.favourite == true) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        IconButton(onClick = onEdit, modifier = Modifier.size(38.dp)) {
                            PhIcon("pencil-simple", sizeDp = 18)
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(38.dp)) {
                            PhIcon("x", sizeDp = 18)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                    ) {
                        BeanDetailContent(
                            bean = bean,
                            roasterName = roasterName,
                            linkedProfileName = linkedProfileName,
                            shotCount = shotCount,
                            recentShots = recentShots,
                            onPhotoTap = if (bean.imageRef != null) ({ photoOpen = true }) else null,
                            onOpenShot = onOpenShot,
                            onSeeAllShots = onSeeAllShots,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CremaButton(
                            onClick = onToggleArchived,
                            variant = CremaButtonVariant.Outlined,
                            icon = if (archived) "archive-box" else "archive",
                            label = if (archived) "Restore" else "Archive",
                        )
                        CremaButton(
                            onClick = { confirmDelete = true },
                            variant = CremaButtonVariant.Text,
                            icon = "trash",
                            danger = true,
                            label = "Delete",
                        )
                        Spacer(Modifier.weight(1f))
                        if (!isActive && !archived) {
                            CremaButton(onClick = onSetActive, icon = "coffee-bean", label = "Set active")
                        }
                    }
                }
            }
        }
    }

    if (photoOpen) {
        BeanPhotoViewer(
            beanId = bean.id,
            imageRef = bean.imageRef,
            updatedAt = bean.updatedAt,
            caption = listOfNotNull(roasterName, bean.name.ifBlank { null }).joinToString(" · "),
            onDismiss = { photoOpen = false },
        )
    }

    if (confirmDelete) {
        CremaConfirmDialog(
            title = "Delete bean?",
            body = "“${bean.name}” will be removed. This can’t be undone.",
            confirmLabel = "Delete",
            icon = "trash",
            danger = true,
            onConfirm = { confirmDelete = false; onDelete(); onDismiss() },
            onDismiss = { confirmDelete = false },
        )
    }
}
