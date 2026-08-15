package coffee.crema.ui.brewlog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.brew.BREW_METHOD_PRESETS
import coffee.crema.brew.formatClock
import coffee.crema.brew.isEspressoMethod
import coffee.crema.brew.methodIcon
import coffee.crema.brew.presetFor
import coffee.crema.core.BrewSeries
import coffee.crema.history.StoredShot
import coffee.crema.history.methodOf
import coffee.crema.ui.LibraryController
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaFilterChip
import coffee.crema.ui.components.CremaFilterDropdown
import coffee.crema.ui.components.CremaStarRating
import coffee.crema.ui.components.CremaStepper
import coffee.crema.ui.components.CremaTextField
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.components.SortKey
import coffee.crema.ui.fmt
import coffee.crema.ui.formatRatio

/*
 * The Log-brew form (issue #10) — the Brew Log's manual-entry door, shared by
 * the tablet's right side-sheet and the phone's pushed screen. Method chips
 * first (they re-template the form), the active bag pre-selected with its
 * remaining grams, per-method seeds from the bag's own last brew, and the
 * journal fields at the bottom. Saving routes through
 * [LibraryController.addManualBrew] — the bag debit + bag-empty notice ride
 * the same rails as a live shot.
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogBrewSheetContent(
    vm: MainViewModel,
    /** "Log again" seed — a prior brew whose facts re-fill the form. */
    prefill: StoredShot? = null,
    /** Bean-detail door: pre-select this bag. */
    prefillBeanId: String? = null,
    /** Guided-session hand-off (Phase 2): measured summary values. */
    guidedRecipeName: String? = null,
    guidedSeries: BrewSeries? = null,
    guidedDurationMs: Long? = null,
    guidedWaterG: Float? = null,
    guidedMethod: String? = null,
    guidedDoseG: Float? = null,
    guidedTempC: Float? = null,
    onDone: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    fun lastBrewOf(method: String, beanId: String?): StoredShot? {
        val matches: (StoredShot) -> Boolean = { (it.methodOf ?: "espresso") == method }
        return beanId?.let { bid -> ui.history.firstOrNull { matches(it) && it.bean?.beanId == bid } }
            ?: ui.history.firstOrNull(matches)
    }

    var method by rememberSaveable {
        mutableStateOf(guidedMethod ?: prefill?.methodOf ?: "pourover")
    }
    var customMethod by rememberSaveable { mutableStateOf("") }
    var beanId by rememberSaveable {
        mutableStateOf(prefillBeanId ?: prefill?.bean?.beanId ?: ui.activeBeanId)
    }
    val seedShot = remember { prefill }
    var dose by rememberSaveable {
        mutableStateOf(
            (guidedDoseG ?: seedShot?.doseG ?: lastBrewOf(method, beanId)?.doseG
                ?: presetFor(method)?.seedDose ?: 15f).toDouble(),
        )
    }
    var water by rememberSaveable {
        mutableStateOf(
            (guidedWaterG ?: seedShot?.let { it.waterG ?: it.yieldG }
                ?: lastBrewOf(method, beanId)?.let { it.waterG ?: it.yieldG }
                ?: presetFor(method)?.seedWater ?: presetFor(method)?.seedYield ?: 250f).toDouble(),
        )
    }
    var grind by rememberSaveable {
        mutableStateOf((seedShot?.grindSetting ?: lastBrewOf(method, beanId)?.grindSetting ?: 0f).toDouble())
    }
    var temp by rememberSaveable {
        mutableStateOf(
            (guidedTempC ?: seedShot?.brewTempC ?: lastBrewOf(method, beanId)?.brewTempC
                ?: presetFor(method)?.seedTemp ?: 0f).toDouble(),
        )
    }
    var timeStr by rememberSaveable {
        mutableStateOf(
            (guidedDurationMs ?: seedShot?.durationMs?.takeIf { it > 0 })
                ?.let { formatClock(it) } ?: "",
        )
    }
    var minutesAgo by rememberSaveable { mutableStateOf(0.0) }
    var rating by rememberSaveable { mutableStateOf(0) }
    var notes by rememberSaveable { mutableStateOf("") }
    var nextPlan by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }

    val isCustom = method == "other"
    val espresso = isEspressoMethod(if (isCustom) customMethod else method)
    val bean = beanId?.let { id -> ui.beans.firstOrNull { it.id == id } }
    val doseMissing = bean != null && dose <= 0.0

    fun reseed(newMethod: String) {
        val preset = presetFor(newMethod)
        val last = lastBrewOf(newMethod, beanId)
        val esp = isEspressoMethod(newMethod)
        dose = (last?.doseG ?: preset?.seedDose ?: 15f).toDouble()
        water = (
            (if (esp) last?.yieldG else last?.waterG ?: last?.yieldG)
                ?: (if (esp) preset?.seedYield ?: 36f else preset?.seedWater ?: 250f)
            ).toDouble()
        temp = (last?.brewTempC ?: preset?.seedTemp ?: 0f).toDouble()
        grind = (last?.grindSetting ?: 0f).toDouble()
    }

    fun parseDurationMs(raw: String): Long? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        Regex("^(\\d+):([0-5]?\\d)$").find(t)?.let { m ->
            return (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000
        }
        return t.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
    }

    fun save() {
        attempted = true
        val storedMethod = if (isCustom) {
            coffee.crema.core.normalizeBrewMethod(customMethod) ?: return
        } else {
            method
        }
        if (doseMissing) return
        vm.addManualBrew(
            LibraryController.ManualBrewInput(
                method = storedMethod,
                completedAtMs = System.currentTimeMillis() - (minutesAgo * 60_000).toLong(),
                beanId = bean?.id,
                doseG = dose.toFloat().takeIf { it > 0f },
                waterG = if (!espresso) water.toFloat().takeIf { it > 0f } else null,
                yieldG = if (espresso) water.toFloat().takeIf { it > 0f } else null,
                grindSetting = grind.toFloat().takeIf { it > 0f },
                brewTempC = temp.toFloat().takeIf { it > 0f },
                durationMs = parseDurationMs(timeStr),
                rating = rating.takeIf { it > 0 },
                notes = notes.ifBlank { null },
                nextPlan = nextPlan.ifBlank { null },
                recipeName = guidedRecipeName,
                brewSeries = guidedSeries,
            ),
        )
        onDone()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Method chips — selection re-templates the numeric seeds.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BREW_METHOD_PRESETS.forEach { p ->
                CremaFilterChip(
                    label = p.label,
                    icon = p.icon,
                    selected = method == p.id,
                    onClick = {
                        method = p.id
                        reseed(p.id)
                    },
                )
            }
            CremaFilterChip(
                label = "Other…",
                selected = isCustom,
                onClick = { method = "other" },
            )
        }
        if (isCustom) {
            CremaTextField(
                value = customMethod,
                onValueChange = { customMethod = it },
                placeholder = "e.g. Karlsbad Kanne",
                label = "Method name",
            )
        }

        // Bean — the active bag by default, with its remaining grams.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow("Bean")
            CremaFilterDropdown(
                icon = "coffee-bean",
                keys = buildList {
                    add(SortKey("none", "No bean — inventory untouched"))
                    ui.beans.filter { it.archivedAt == null }.forEach { b ->
                        val roaster = ui.roasters.firstOrNull { it.id == b.roasterId }?.name
                        val left = b.remaining?.let { " · ${it.toInt()} g left" }.orEmpty()
                        add(SortKey(b.id, listOfNotNull(roaster, b.name).joinToString(" · ") + left))
                    }
                },
                selectedKey = beanId ?: "none",
                onKeyChange = { beanId = if (it == "none") null else it },
            )
            if (attempted && doseMissing) {
                Text(
                    "Enter the dose so the bag can be debited.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // The numeric facts — steppers matching the Quick Sheet idiom.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CremaStepper(
                label = "Dose",
                value = dose,
                unit = "g",
                onChange = { dose = it },
                step = 0.5,
                min = 0.0,
                max = 200.0,
            )
            CremaStepper(
                label = if (espresso) "Yield" else "Water",
                value = water,
                unit = "g",
                onChange = { water = it },
                step = if (espresso) 1.0 else 10.0,
                min = 0.0,
                max = 2000.0,
                fmt = { fmt("%.0f", it) },
            )
            CremaStepper(
                label = "Grind",
                value = grind,
                unit = null,
                onChange = { grind = it },
                step = 0.1,
                min = 0.0,
                max = 200.0,
            )
            CremaStepper(
                label = "Temp",
                value = temp,
                unit = "°C",
                onChange = { temp = it },
                step = 1.0,
                min = 0.0,
                max = 100.0,
                fmt = { fmt("%.0f", it) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.width(120.dp)) {
                CremaTextField(
                    value = timeStr,
                    onValueChange = { timeStr = it },
                    label = "Brew time",
                    placeholder = "m:ss",
                )
            }
            CremaStepper(
                label = "Logged",
                value = minutesAgo,
                unit = "min ago",
                onChange = { minutesAgo = it },
                step = 5.0,
                min = 0.0,
                max = 1440.0,
                fmt = { fmt("%.0f", it) },
            )
            Spacer(Modifier.weight(1f))
            val ratio = coffee.crema.core.brewRatio(dose.toFloat(), water.toFloat())
            Text(
                if (ratio != null) "ratio 1:${fmt("%.1f", ratio)}" else "ratio 1:—",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Journal — rating, notes, next time (the dial-in trio).
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("Rating")
                CremaStarRating(rating, onChange = { rating = if (it == rating) 0 else it })
            }
            CremaTextField(
                value = notes,
                onValueChange = { notes = it },
                label = "Tasting notes",
                singleLine = false,
                minLines = 2,
            )
            CremaTextField(
                value = nextPlan,
                onValueChange = { nextPlan = it },
                label = "Next time",
                placeholder = "e.g. grind 1 finer, bloom 45 s",
                singleLine = false,
                minLines = 2,
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            CremaButton(onClick = onDone, variant = CremaButtonVariant.Outlined, label = "Cancel")
            CremaButton(onClick = { save() }, icon = "check", label = "Save brew")
        }
    }
}

/**
 * Tablet host — the Brew Log form as a 420dp right side-sheet over a scrim
 * (the [coffee.crema.ui.screens.BeanDetailSheet] pattern: a full-screen
 * Dialog, since M3 has no side-sheet primitive).
 */
@Composable
fun LogBrewSheet(
    vm: MainViewModel,
    prefill: StoredShot? = null,
    prefillBeanId: String? = null,
    guidedRecipeName: String? = null,
    guidedSeries: BrewSeries? = null,
    guidedDurationMs: Long? = null,
    guidedWaterG: Float? = null,
    guidedMethod: String? = null,
    guidedDoseG: Float? = null,
    guidedTempC: Float? = null,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.CenterEnd,
        ) {
            // 440dp side sheet on tablets; full-width on handsets.
            val sheetWidth = minOf(440, androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp)
            Surface(
                modifier = Modifier
                    .width(sheetWidth.dp)
                    .fillMaxHeight()
                    .clickable(enabled = false, onClick = {}),
                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PhIcon(methodIcon(null), sizeDp = 18, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Eyebrow("Journal")
                            Text("Log a brew", style = MaterialTheme.typography.titleLarge)
                        }
                        CremaButton(onClick = onDismiss, variant = CremaButtonVariant.Text, label = "Close")
                    }
                    LogBrewSheetContent(
                        vm = vm,
                        prefill = prefill,
                        prefillBeanId = prefillBeanId,
                        guidedRecipeName = guidedRecipeName,
                        guidedSeries = guidedSeries,
                        guidedDurationMs = guidedDurationMs,
                        guidedWaterG = guidedWaterG,
                        guidedMethod = guidedMethod,
                        guidedDoseG = guidedDoseG,
                        guidedTempC = guidedTempC,
                        onDone = onDismiss,
                    )
                }
            }
        }
    }
}
