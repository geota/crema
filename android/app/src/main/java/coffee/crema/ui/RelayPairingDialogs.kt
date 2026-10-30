package coffee.crema.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ble.proxy.PairingDialer
import coffee.crema.ble.proxy.PairingState
import kotlinx.coroutines.delay

/*
 * The minimal relay v4 pairing UI (spec §6.1, §6.2; the full copy, attempt
 * states and accessibility land with the UI PR): the hub's code-entry dialog
 * and the remote's code sheet.
 */

/**
 * Hub: a remote asks to pair. The hub user types the 6-digit code the remote
 * shows and chooses View and control (default) or View only. Dismissing is
 * Cancel (`ABORT(CANCELLED)`, no failure counted).
 */
@Composable
internal fun PairingCodeDialog(prompt: PairingPrompt, onPair: (code: String, canControl: Boolean) -> Unit, onCancel: () -> Unit) {
    var code by remember(prompt.requestId) { mutableStateOf("") }
    var canControl by remember(prompt.requestId) { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Pair ${prompt.clientName}?") },
        text = {
            Column {
                Text(
                    "ID ${prompt.shortId}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (prompt.lastWasWrong) {
                    Text(
                        "That code didn't match. ${prompt.clientName} is showing a new code — enter that one. " +
                            "(${prompt.wrongCodes + 1} of 3)",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text("Enter the 6-digit code shown on ${prompt.clientName}.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    label = { Text("Code") },
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = canControl, onClick = { canControl = true })
                    Text("View and control")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !canControl, onClick = { canControl = false })
                    Text("View only")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPair(code, canControl) }, enabled = code.length == 6) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * Remote: pairing with a hub. No code before the hub's `AWAITING_CODE`; then
 * the code in large digits with its countdown (read digit by digit by
 * TalkBack), the attempt number from the second on, and Cancel. The screen
 * stays on while the code shows. The code is never logged.
 */
@SuppressLint("DefaultLocale")
@Composable
internal fun RemotePairingSheet(state: PairingState, onCancel: () -> Unit, onTryAgain: () -> Unit, onDismiss: () -> Unit) {
    when (state) {
        PairingState.Idle -> Unit
        is PairingState.Waiting -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Pairing with ${state.hubName}…") },
            text = {
                Text(
                    if (state.retryAfterWrongCode) "That code didn't work. Getting a new one…"
                    else "Waiting for ${state.hubName} to accept the pairing.",
                )
            },
            confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
        is PairingState.ShowCode -> {
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
            val shown = state.code.take(3) + " " + state.code.drop(3)
            AlertDialog(
                onDismissRequest = onCancel,
                title = { Text("Enter this code on ${state.hubName}") },
                text = {
                    Column {
                        if (state.retryAfterWrongCode) {
                            Text("That code didn't work. Here's a new one: enter it on ${state.hubName}.")
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            shown,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 40.sp,
                            modifier = Modifier.semantics { contentDescription = state.code.toList().joinToString(" ") },
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(String.format("Expires in %d:%02d", left / 60, left % 60))
                        if (state.attempt > 1) Text("Attempt ${state.attempt} of ${PairingDialer.MAX_PAIR_ATTEMPTS}")
                    }
                },
                confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
            )
        }
        is PairingState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Pairing didn't work") },
            text = {
                Text(
                    "Pairing with ${state.hubName} didn't work. Make sure you're entering the code on ${state.hubName} itself, then try again.",
                )
            },
            confirmButton = { TextButton(onClick = onTryAgain) { Text("Try again") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        is PairingState.Refused -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Couldn't pair") },
            text = { Text(refusalMessage(state.reason, state.hubName)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        is PairingState.Paired -> Unit
    }
}
