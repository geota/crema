package coffee.crema.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coffee.crema.ble.proxy.PairingState
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import kotlinx.coroutines.delay

/*
 * The relay v4 pairing UI (spec §6.1, §6.2): the hub's code-entry dialog and
 * the remote's code sheet. The words live in MultiDeviceCopy.kt. The code is
 * shown only on the remote's sheet: never logged, never in a notification
 * (SR-5).
 */

/**
 * Hub: a remote asks to pair. The hub user types the 6-digit code the remote
 * shows (one box per digit, auto-advancing, paste accepted) and chooses View
 * and control (default) or View only. Pair is enabled at 6 digits. Dismissing
 * is Cancel (`ABORT(CANCELLED)`, no failure counted). After a wrong code the
 * dialog stays for the remote's next attempt with the count.
 */
@Composable
internal fun PairingCodeDialog(prompt: PairingPrompt, onPair: (code: String, canControl: Boolean) -> Unit, onCancel: () -> Unit) {
    // A new attempt (a new request) starts with an empty field; the scope stays chosen.
    var code by remember(prompt.requestId) { mutableStateOf("") }
    var canControl by remember(prompt.clientName) { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(prompt.requestId) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        title = { Text(pairRemoteTitle(prompt.clientName), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Unauthenticated, as before pairing: a name and an id the remote chose.
                Text(
                    "ID ${prompt.shortId}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (prompt.lastWasWrong) {
                    Text(
                        wrongCodeLine(prompt.clientName, prompt.wrongCodes + 1),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Text(enterCodeLine(prompt.clientName), style = MaterialTheme.typography.bodyMedium)
                CodeField(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier.focusRequester(focus).testTag("pairing-code-field"),
                )
                Column(Modifier.selectableGroup()) {
                    ScopeOption(scopeLabel(canControl = true), selected = canControl) { canControl = true }
                    ScopeOption(scopeLabel(canControl = false), selected = !canControl) { canControl = false }
                }
            }
        },
        confirmButton = {
            CremaButton(onClick = { onPair(code, canControl) }, variant = CremaButtonVariant.Text, enabled = code.length == CODE_DIGITS, label = "Pair")
        },
        dismissButton = { CremaButton(onClick = onCancel, variant = CremaButtonVariant.Text, label = "Cancel") },
    )
}

private const val CODE_DIGITS = 6

/** Six digit boxes over one text field: typing advances, backspace goes back,
 *  and a pasted code fills them all. Digits only. */
@Composable
private fun CodeField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value,
        onValueChange = { v -> onValueChange(v.filter(Char::isDigit).take(CODE_DIGITS)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = modifier.semantics { contentDescription = "Code, ${value.length} of $CODE_DIGITS digits" },
        decorationBox = { inner ->
            Box {
                // The real field stays for input and the cursor; the boxes draw the digits.
                Box(Modifier.size(1.dp)) { inner() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(CODE_DIGITS) { i ->
                        val active = i == value.length.coerceAtMost(CODE_DIGITS - 1)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(
                                if (active) 2.dp else 1.dp,
                                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            modifier = Modifier.width(38.dp).height(48.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    value.getOrNull(i)?.toString().orEmpty(),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        if (i == 2) Spacer(Modifier.width(6.dp))
                    }
                }
            }
        },
    )
}

@Composable
private fun ScopeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Remote: pairing with a hub, as a full-width sheet. No code before the hub's
 * `AWAITING_CODE` ("Pairing with ‹hub›…"); then the code in large digits
 * (read digit by digit by TalkBack), its countdown, the attempt from the
 * second on, and Cancel. The screen stays on while the code shows. After an
 * outcome: Try again (three attempts failed) or the hub's reason.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemotePairingSheet(state: PairingState, onCancel: () -> Unit, onTryAgain: () -> Unit, onDismiss: () -> Unit) {
    if (state is PairingState.Idle || state is PairingState.Paired) return
    val inProgress = state is PairingState.Waiting || state is PairingState.ShowCode
    ModalBottomSheet(
        onDismissRequest = if (inProgress) onCancel else onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                is PairingState.Waiting -> {
                    SheetTitle(pairingWaitingTitle(state.hubName))
                    CircularProgressIndicator(Modifier.size(32.dp))
                    SheetBody(
                        if (state.retryAfterWrongCode) "That code didn't work. Getting a new one…"
                        else "Waiting for ${state.hubName} to accept the pairing.",
                    )
                    CremaButton(onClick = onCancel, variant = CremaButtonVariant.Outlined, label = "Cancel")
                }
                is PairingState.ShowCode -> ShowCodeContent(state, onCancel)
                is PairingState.Failed -> {
                    SheetTitle("Pairing didn't work")
                    SheetBody(pairingFailedLine(state.hubName))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CremaButton(onClick = onDismiss, variant = CremaButtonVariant.Text, label = "Close")
                        CremaButton(onClick = onTryAgain, label = "Try again")
                    }
                }
                is PairingState.Refused -> {
                    SheetTitle("Couldn't pair")
                    SheetBody(refusalMessage(state.reason, state.hubName))
                    CremaButton(onClick = onDismiss, label = "OK")
                }
                PairingState.Idle, is PairingState.Paired -> Unit
            }
        }
    }
}

@Composable
private fun ShowCodeContent(state: PairingState.ShowCode, onCancel: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.expiresAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val left = ((state.expiresAtMs - now) / 1_000L).coerceAtLeast(0)
    SheetTitle(pairingCodeTitle(state.hubName))
    if (state.retryAfterWrongCode) SheetBody(pairingRetryLine(state.hubName))
    Text(
        groupedCode(state.code),
        style = MaterialTheme.typography.displayLarge,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        // TalkBack reads it digit by digit, in its two groups.
        modifier = Modifier.clearAndSetSemantics { contentDescription = spokenCode(state.code) },
    )
    // Announced politely, once a minute, not every second.
    val minutesLeft = (left + 59) / 60
    Text(
        expiresIn(left),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = if (minutesLeft <= 1) "Expires in under a minute" else "Expires in about $minutesLeft minutes"
            liveRegion = LiveRegionMode.Polite
        },
    )
    if (state.attempt > 1) SheetBody(pairingAttemptLine(state.attempt))
    CremaButton(onClick = onCancel, variant = CremaButtonVariant.Outlined, label = "Cancel")
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun SheetBody(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
