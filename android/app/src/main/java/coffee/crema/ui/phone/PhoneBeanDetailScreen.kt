package coffee.crema.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.beans.BeanDetailContent
import coffee.crema.ui.beans.linkedProfileNameFor
import coffee.crema.ui.beans.shotRowSummary
import coffee.crema.ui.components.BeanPhotoViewer
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaConfirmDialog
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.phone.components.CremaEdge
import coffee.crema.ui.phone.components.CremaPhoneBackBar

/*
 * PhoneBeanDetailScreen — the handset's read-only bean detail (issue 61).
 *
 * The tablet puts this in a side sheet; the phone pushes it full-screen, the
 * same idiom the shot detail already uses (`PhoneShotDetail`). A bottom sheet
 * was the other candidate and was rejected: nine sections and a photo do not
 * belong in a surface the user has to hold open.
 *
 * The body is [BeanDetailContent], shared with the tablet, so the two never
 * drift on what a bag "is".
 */
@Composable
fun PhoneBeanDetailScreen(
    vm: MainViewModel,
    beanId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    /** Open one of this bag's shots in History; null = rows not tappable. */
    onOpenShot: ((String) -> Unit)? = null,
    /** Open History filtered to this bag ("See all N shots"); null = hidden. */
    onSeeAllShots: (() -> Unit)? = null,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val bean = ui.beans.firstOrNull { it.id == beanId }
    var confirmDelete by remember { mutableStateOf(false) }
    var photoOpen by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    // Deleted underneath us (from the library, or another device via mirror) —
    // there is nothing to show, so leave rather than render a husk.
    if (bean == null) {
        onBack()
        return
    }

    val roasterName = ui.roasters.firstOrNull { it.id == bean.roasterId }?.name
    val shots = ui.history.filter { it.bean?.beanId == bean.id }
    val archived = bean.archivedAt != null
    val isActive = bean.id == ui.activeBeanId

    Scaffold(
        topBar = {
            CremaPhoneBackBar(
                title = bean.name.ifBlank { "Untitled bag" },
                subtitle = roasterName,
                onBack = onBack,
                actions = {
                    IconButton(onClick = { vm.toggleBeanFavourite(bean.id) }, modifier = Modifier.size(40.dp)) {
                        PhIcon(
                            if (bean.favourite == true) "star-fill" else "star",
                            sizeDp = 19,
                            tint = if (bean.favourite == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                        PhIcon("pencil-simple", sizeDp = 19)
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CremaEdge)
                .padding(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BeanDetailContent(
                bean = bean,
                roasterName = roasterName,
                linkedProfileName = linkedProfileNameFor(bean, ui.profiles.map { it.id to it.name }),
                shotCount = shots.size,
                recentShots = shots.take(5).map { shotRowSummary(it) },
                onPhotoTap = if (bean.imageRef != null) ({ photoOpen = true }) else null,
                onOpenShot = onOpenShot,
                onSeeAllShots = onSeeAllShots,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CremaButton(
                    onClick = {
                        if (archived) vm.unarchiveBean(bean.id) else vm.archiveBean(bean.id)
                    },
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
                    CremaButton(
                        onClick = { vm.setActiveBean(bean.id) },
                        icon = "coffee-bean",
                        label = "Set active",
                    )
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
            title = "Delete bag?",
            body = "“${bean.name}” will be removed from your library. This can’t be undone.",
            confirmLabel = "Delete",
            icon = "trash",
            danger = true,
            onConfirm = { confirmDelete = false; vm.deleteBean(bean.id); onBack() },
            onDismiss = { confirmDelete = false },
        )
    }
}
