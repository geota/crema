package coffee.crema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaIconButton
import coffee.crema.ui.components.Eyebrow

/*
 * Bean editor (the pushed `bean-edit` route) — M3. Edits the bean opened via
 * MainViewModel.startEditBean (ui.editingBeanId): a scrollable form over the
 * common fields; Save applies via vm.updateBean (roaster find-or-create) and
 * pops back. Origin (country/process), grinder, rating, and notes round out the
 * quick fields from the add dialog.
 */
@Composable
fun BeanEditScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val bean = ui.beans.firstOrNull { it.id == ui.editingBeanId }

    if (bean == null) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            CremaIconButton(icon = "arrow-left", onClick = onBack)
            Text(
                "No bean selected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val roasterName = bean.roasterId?.let { rid -> ui.roasters.firstOrNull { it.id == rid }?.name } ?: ""
    // Field state seeded from the bean; re-seeds if the edited bean changes.
    var name by remember(bean.id) { mutableStateOf(bean.name) }
    var roaster by remember(bean.id) { mutableStateOf(roasterName) }
    var roasted by remember(bean.id) { mutableStateOf(bean.roastedOn ?: "") }
    var country by remember(bean.id) { mutableStateOf(bean.origin.country ?: "") }
    var process by remember(bean.id) { mutableStateOf(bean.origin.processing ?: "") }
    var grinder by remember(bean.id) { mutableStateOf(bean.grinder) }
    var setting by remember(bean.id) { mutableStateOf(bean.grinderSetting) }
    var notes by remember(bean.id) { mutableStateOf(bean.notes) }
    var level by remember(bean.id) { mutableStateOf(bean.roastLevel?.toInt() ?: 5) }
    var rating by remember(bean.id) { mutableStateOf(bean.rating.toInt()) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CremaIconButton(icon = "arrow-left", onClick = onBack)
            Eyebrow("Beans › Edit bean", Modifier.weight(1f))
            CremaButton(
                onClick = {
                    vm.updateBean(
                        id = bean.id,
                        name = name,
                        roasterName = roaster,
                        roastLevel = level,
                        roastedOn = roasted,
                        country = country,
                        processing = process,
                        grinder = grinder,
                        grinderSetting = setting,
                        rating = rating,
                        notes = notes,
                    )
                    onBack()
                },
                icon = "check",
                label = "Save",
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FormField("Name", name) { name = it }
            FormField("Roaster", roaster) { roaster = it }
            FormField("Roasted on (YYYY-MM-DD)", roasted) { roasted = it }
            IntStepper("Roast level", level, 1, 10) { level = it }
            FormField("Country", country) { country = it }
            FormField("Process", process) { process = it }
            FormField("Grinder", grinder) { grinder = it }
            FormField("Grind setting", setting) { setting = it }
            IntStepper("Rating", rating, 0, 5) { rating = it }
            FormField("Notes", notes, singleLine = false) { notes = it }
        }
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
    )
}

@Composable
private fun IntStepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().widthIn(max = 560.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "$label  $value",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FilledTonalIconButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }) { Text("−") }
        FilledTonalIconButton(onClick = { onChange((value + 1).coerceAtMost(max)) }) { Text("+") }
    }
}
