package coffee.crema.ui

import androidx.compose.runtime.Composable
import coffee.crema.ui.components.CremaConfirmDialog

/**
 * The prompt after a debit empties a bag (geota/crema#302, web's
 * `promptBagEmpty`): archive the bag, which also drops it as the active one, then
 * nudge a rating if it has none. Held back while a shot runs so it never covers
 * the Stop button; it shows once the shot ends.
 */
@Composable
fun BagEmptyDialog(ui: MainUiState, vm: MainViewModel, onNav: (String) -> Unit) {
    val prompt = ui.bagEmptyPrompt ?: return
    if (ui.shotInProgress) return
    if (prompt.askingRating) {
        CremaConfirmDialog(
            title = "Rate this bag?",
            body = "You haven’t rated ${prompt.name} yet — how was it?",
            confirmLabel = "Rate it",
            cancelLabel = "Later",
            onConfirm = { vm.dismissBagEmptyPrompt(); onNav("beans") },
            onDismiss = vm::dismissBagEmptyPrompt,
        )
    } else {
        CremaConfirmDialog(
            title = "Bag empty",
            body = "That was the last of ${prompt.name}. Archive this bag?",
            confirmLabel = "Archive",
            cancelLabel = "Keep",
            onConfirm = { vm.answerBagEmpty(archive = true) },
            onDismiss = { vm.answerBagEmpty(archive = false) },
        )
    }
}
