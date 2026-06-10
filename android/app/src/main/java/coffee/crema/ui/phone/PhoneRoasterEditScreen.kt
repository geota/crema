package coffee.crema.ui.phone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.*
import coffee.crema.ui.phone.components.CremaEdge

/*
 * PhoneRoasterEditScreen — the pushed roaster editor (DESIGN §3.9; the tablet
 * edits roasters in a dialog, the phone pushes this full-screen form).
 *
 * Identity (deterministic roaster-mark avatar + name) + Details (location,
 * website, notes). The avatar color derives from the name (RoasterMark), so
 * there is no manual color picker — the mark IS the identity.
 */
@Composable
fun PhoneRoasterEditScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val editing = ui.roasters.firstOrNull { it.id == ui.editingRoasterId }

    var name by remember(editing?.id) { mutableStateOf(editing?.name ?: "") }
    var city by remember(editing?.id) { mutableStateOf(editing?.city ?: "") }
    var country by remember(editing?.id) { mutableStateOf(editing?.country ?: "") }
    var website by remember(editing?.id) { mutableStateOf(editing?.website ?: "") }
    var notes by remember(editing?.id) { mutableStateOf(editing?.notes ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CremaPhoneBackBarWithSave(
                breadcrumb = "Roasters",
                title = if (editing == null) "New roaster" else editing.name,
                saveEnabled = name.isNotBlank(),
                onCancel = onBack,
                onSave = {
                    if (editing == null) vm.addRoaster(name, website, city, country, notes)
                    else vm.updateRoaster(editing.id, name, website, city, country, notes)
                    onBack()
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
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            Eyebrow("Identity")
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        RoasterMarkAvatar(name = name.ifBlank { "?" }, sizeDp = 56, cornerDp = 14, fontSize = 20.sp)
                        Column {
                            Text("Roaster mark", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            Text(
                                "The two-letter mark and its color follow the name.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    CremaTextField(value = name, onValueChange = { name = it }, label = "Name", placeholder = "e.g. Onyx Coffee Lab")
                }
            }

            Eyebrow("Details")
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CremaTextField(value = city, onValueChange = { city = it }, label = "City", placeholder = "Portland", modifier = Modifier.weight(1f))
                        CremaTextField(value = country, onValueChange = { country = it }, label = "Country / region", placeholder = "US", modifier = Modifier.weight(1f))
                    }
                    CremaTextField(value = website, onValueChange = { website = it }, label = "Website", placeholder = "https://…")
                    CremaTextField(value = notes, onValueChange = { notes = it }, label = "Notes", placeholder = "Private notes", singleLine = false, minLines = 3)
                }
            }

            if (editing != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!editing.website.isNullOrBlank()) {
                        CremaButton(
                            onClick = { vm.visitRoasterWebsite(editing.website) },
                            variant = CremaButtonVariant.Outlined,
                            icon = "arrow-square-out",
                            label = "Visit website",
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    CremaButton(
                        onClick = { confirmDelete = true },
                        variant = CremaButtonVariant.Text,
                        danger = true,
                        icon = "trash",
                        label = "Delete roaster",
                    )
                }
            }
        }
    }

    if (confirmDelete && editing != null) {
        CremaConfirmDialog(
            title = "Delete roaster?",
            body = "“${editing.name}” will be removed. Its bags stay in your library, unlinked.",
            confirmLabel = "Delete",
            icon = "trash",
            danger = true,
            onConfirm = { vm.deleteRoaster(editing.id); confirmDelete = false; onBack() },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Cancel/Save editor bar (proto .pe-bar): ‹ Breadcrumb · Title …… Cancel · Save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CremaPhoneBackBarWithSave(
    breadcrumb: String,
    title: String,
    saveEnabled: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onCancel) { PhIcon("arrow-left", sizeDp = 24) }
        },
        title = {
            Column {
                Text(
                    "$breadcrumb ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = onSave,
                enabled = saveEnabled,
                modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            ) { Text("Save") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}
