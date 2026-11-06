package coffee.crema.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coffee.crema.beans.holdsBagPrompt
import coffee.crema.ui.components.CremaConfirmDialog
import kotlinx.coroutines.delay

/** How long a new question ignores taps: a finger on the screen as a shot ends
 *  must not land on Archive. */
private const val PROMPT_ARM_MS = 600L

/**
 * The prompt after a debit empties a bag (geota/crema#302, web's
 * `promptBagEmpty`): archive the bag, which also drops it as the active one, then
 * nudge a rating if it has none. Held back during a shot or a steam, hot-water or
 * flush run so it never covers a Stop; it shows once the machine is free.
 */
@Composable
fun BagEmptyDialog(ui: MainUiState, vm: MainViewModel, onNav: (String) -> Unit) {
    val prompt = ui.bagEmptyPrompt ?: return
    if (ui.holdsBagPrompt()) return
    var armed by remember(prompt) { mutableStateOf(false) }
    LaunchedEffect(prompt) {
        delay(PROMPT_ARM_MS)
        armed = true
    }
    val whenArmed: (() -> Unit) -> () -> Unit = { action -> { if (armed) action() } }
    if (prompt.askingRating) {
        CremaConfirmDialog(
            title = "Rate this bag?",
            body = "You haven’t rated ${prompt.name} yet — how was it?",
            confirmLabel = "Rate it",
            cancelLabel = "Later",
            armed = armed,
            onConfirm = { vm.dismissBagEmptyPrompt(); onNav("beans") },
            onDismiss = whenArmed { vm.dismissBagEmptyPrompt() },
        )
    } else {
        CremaConfirmDialog(
            title = "Bag empty",
            body = "That was the last of ${prompt.name}. Archive this bag?",
            confirmLabel = "Archive",
            cancelLabel = "Keep",
            armed = armed,
            onConfirm = { vm.answerBagEmpty(archive = true) },
            onDismiss = whenArmed { vm.answerBagEmpty(archive = false) },
        )
    }
}
