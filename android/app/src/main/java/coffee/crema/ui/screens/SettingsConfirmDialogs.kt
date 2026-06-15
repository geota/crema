package coffee.crema.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import coffee.crema.ui.MainUiState
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaConfirmDialog

/**
 * The staged destructive-action confirm state, shared by the tablet
 * `SettingsScreen` and the phone `PhoneSettingsScreen` (issue 27) — both had it
 * copied verbatim. Each `pending*`/`confirm*` flag is its own `rememberSaveable`
 * (survives rotation / process death, as before); the rows in either shell flip
 * them (`state.confirmErase = true`, `state.pendingCycle = "descale"`, …) and
 * [SettingsConfirmDialogs] renders the matching dialog.
 *
 * [launchSave] is the SAF export-to-file plumbing (CreateDocument launcher +
 * the deferred text payload) — plain `remember`, not saveable, since a multi-MB
 * history JSON would blow the 1 MB Binder cap on background.
 */
@androidx.compose.runtime.Stable
class SettingsConfirmState(
    resetPrefs: MutableState<Boolean>,
    erase: MutableState<Boolean>,
    heaterVoltage: MutableState<String?>,
    lineFreq: MutableState<String?>,
    flowMultiplier: MutableState<Float?>,
    cycle: MutableState<String?>,
    resync: MutableState<Boolean>,
    val launchSave: (name: String, content: String?) -> Unit,
) {
    var confirmResetPrefs by resetPrefs
    var confirmErase by erase
    var pendingHeaterVoltage by heaterVoltage
    var pendingLineFreq by lineFreq
    var pendingFlowMultiplier by flowMultiplier
    var pendingCycle by cycle
    var pendingResync by resync
}

/** Build the shared confirm-state holder (saveable flags + SAF export launcher). */
@Composable
fun rememberSettingsConfirmState(vm: MainViewModel): SettingsConfirmState {
    val resetPrefs = rememberSaveable { mutableStateOf(false) }
    val erase = rememberSaveable { mutableStateOf(false) }
    val heaterVoltage = rememberSaveable { mutableStateOf<String?>(null) }
    val lineFreq = rememberSaveable { mutableStateOf<String?>(null) }
    val flowMultiplier = rememberSaveable { mutableStateOf<Float?>(null) }
    val cycle = rememberSaveable { mutableStateOf<String?>(null) }
    val resync = rememberSaveable { mutableStateOf(false) }
    // SAF export-to-file plumbing (same pattern as History / Beans / Profiles).
    val pendingExport = remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val t = pendingExport.value; pendingExport.value = null
        if (uri != null && t != null) vm.writeTextToUri(uri, t)
    }
    return remember(vm) {
        SettingsConfirmState(
            resetPrefs, erase, heaterVoltage, lineFreq, flowMultiplier, cycle, resync,
            launchSave = { name, content ->
                if (content != null) { pendingExport.value = content; saveLauncher.launch(name) }
            },
        )
    }
}

/**
 * Renders the seven staged confirm dialogs (reset-prefs, erase-all, heater
 * voltage, AC mains frequency, flow calibration, maintenance cycle, Visualizer
 * re-pull) for whichever of [state]'s flags is set. Identical bodies across both
 * settings shells — title/body/typed-confirm copy and the VM writes live here.
 */
@Composable
fun SettingsConfirmDialogs(state: SettingsConfirmState, vm: MainViewModel, ui: MainUiState) {
    if (state.confirmResetPrefs) CremaConfirmDialog(
        title = "Reset preferences?",
        body = "Crema's settings return to their defaults. Your beans, profiles and shots are kept.",
        confirmLabel = "Reset",
        onConfirm = { vm.resetPreferences(); state.confirmResetPrefs = false },
        onDismiss = { state.confirmResetPrefs = false },
    )
    if (state.confirmErase) CremaConfirmDialog(
        title = "Erase all data?",
        body = "Every bean, roaster, shot and custom profile will be permanently deleted. Built-in profiles remain. This can't be undone.",
        confirmLabel = "Erase everything",
        icon = "trash",
        danger = true,
        requireTyped = "ERASE",
        onConfirm = { vm.eraseAll(); state.confirmErase = false },
        onDismiss = { state.confirmErase = false },
    )
    // Heater voltage is service-grade and HARDWARE-DAMAGING if mis-set: the
    // web's MainsConfirmModal makes the user literally type the voltage, so the
    // dialog here requires the same typed value before Confirm arms.
    state.pendingHeaterVoltage?.let { volts ->
        CremaConfirmDialog(
            title = "Confirm machine voltage",
            body = "Wrong voltage on your mains can permanently damage your heater. Verify your wall outlet matches before proceeding, then type $volts to confirm.",
            confirmLabel = "Set $volts V",
            danger = true,
            requireTyped = volts,
            onConfirm = {
                volts.toIntOrNull()?.let { vm.setHeaterVoltage(it) }
                state.pendingHeaterVoltage = null
            },
            onDismiss = { state.pendingHeaterVoltage = null },
        )
    }
    // AC mains frequency — same type-to-confirm treatment (web MainsConfirmModal
    // kind="hz"): no hardware damage, but the volume integrator drifts ~5 % when
    // mis-set, and the two mains settings should feel equally deliberate.
    state.pendingLineFreq?.let { hz ->
        CremaConfirmDialog(
            title = "Confirm AC mains frequency",
            body = "Wrong AC frequency mis-calibrates the volume integrator (no hardware damage, but shot volumes drift up to 5 %). Verify your mains frequency, then type $hz to confirm.",
            confirmLabel = "Set $hz Hz",
            danger = true,
            requireTyped = hz,
            onConfirm = {
                hz.toFloatOrNull()?.let { vm.setLineFrequency(it) }
                state.pendingLineFreq = null
            },
            onDismiss = { state.pendingLineFreq = null },
        )
    }
    // Flow-calibration write — web gates Apply/Reset behind typing "flow".
    state.pendingFlowMultiplier?.let { mult ->
        CremaConfirmDialog(
            title = "Apply flow calibration?",
            body = "Every flow and volume estimate scales by this multiplier (×${String.format("%.2f", mult)}). Calibrate only against a known reference. Type flow to confirm.",
            confirmLabel = "Apply ×${String.format("%.2f", mult)}",
            requireTyped = "flow",
            onConfirm = {
                vm.setFlowMultiplier(mult)
                state.pendingFlowMultiplier = null
            },
            onDismiss = { state.pendingFlowMultiplier = null },
        )
    }
    // Maintenance cycles take minutes and need the kit fitted — confirm first
    // (web WaterSection's cycle ConfirmDialog).
    state.pendingCycle?.let { cycle ->
        val label = when (cycle) { "descale" -> "descale"; "clean" -> "cleaning"; else -> "steam rinse" }
        CremaConfirmDialog(
            title = "Start the $label cycle?",
            body = when (cycle) {
                "descale" -> "Have descaling solution in the tank and a container under the group. The cycle takes several minutes."
                "clean" -> "Fit the blind basket with cleaning tablet before starting. The cycle takes several minutes."
                else -> "Runs a short steam-wand rinse. Keep the wand over the drip tray."
            },
            confirmLabel = "Start",
            onConfirm = {
                when (cycle) {
                    "descale" -> vm.startDescale()
                    "clean" -> vm.startClean()
                    else -> vm.startSteamRinse()
                }
                state.pendingCycle = null
            },
            onDismiss = { state.pendingCycle = null },
        )
    }
    if (state.pendingResync) CremaConfirmDialog(
        title = "Re-pull every shot?",
        body = "Re-pulls your entire Visualizer history from the beginning. Existing shots are de-duplicated against what you already have.",
        confirmLabel = "Re-pull",
        onConfirm = { vm.visualizer.resyncAllShots(ui.history); state.pendingResync = false },
        onDismiss = { state.pendingResync = false },
    )
}
