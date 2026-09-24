package coffee.crema.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coffee.crema.decent.DecentSync
import coffee.crema.ui.CatchUpOffer
import coffee.crema.ui.UploadTargetId

// ════════════════════════════════════════════════════════════════════════════
// SHARED SHARING ROWS (Settings → Sharing) — the Decent account form + status
// copy and the one-time catch-up offer, one set for both shells. Density
// follows [LocalSettingsRowDense] like the other shared settings rows; the
// shells only differ in the frame around them (the tablet's hero card, the
// phone's settings group).
// ════════════════════════════════════════════════════════════════════════════

/**
 * The one-time catch-up offer: a destination just became able to receive
 * shots, so offer the ones already here — once, inline. "Not now" dismisses
 * until the next sign-in / toggle-on edge; later catch-up lives in History.
 */
@Composable
fun CatchUpRow(offer: CatchUpOffer, busy: Boolean, onUpload: () -> Unit, onDismiss: () -> Unit) {
    CremaSettingsRow(
        "Upload the ${offer.count} shot(s) already on this device to ${offer.destination.displayName}?",
        if (offer.destination == UploadTargetId.Decent) {
            "Older shots need the DE1 connected for its serial. You can also do this later from History."
        } else {
            "You can also do this later from History."
        },
        last = true,
        stacked = LocalSettingsRowDense.current,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CremaButton(onClick = onDismiss, variant = CremaButtonVariant.Text, enabled = !busy, label = "Not now")
            CremaButton(onClick = onUpload, variant = CremaButtonVariant.Filled, icon = "cloud-arrow-up", enabled = !busy, label = if (busy) "Uploading…" else "Upload")
        }
    }
}

/** The status sentence for a Decent account (linked, or waiting to sign in again). */
fun decentAccountSummary(dc: DecentSync.UiState, connectedSerial: String?, serialOnAccount: Boolean?): String = buildString {
    if (!dc.linked && !dc.needsReauth) {
        append(
            "Upload every shot to your own Decent Espresso account — the shot history and charts at decentespresso.com, " +
                "the same place the tablet app and decaid upload to. Only a server token is kept on this device.",
        )
        return@buildString
    }
    append("Signed in as ${dc.email}. ${dc.serials.size} machine(s) on the account")
    if (connectedSerial != null && serialOnAccount != null) {
        append(" · the connected DE1 #$connectedSerial ")
        append(if (serialOnAccount) "is registered" else "is not on this account — uploads will be refused")
    }
    append(".")
    if (dc.needsReauth) append(" Your login stopped working — sign in again.")
    append(" Signing out only forgets the login on this device; if you lose a device, change your Decent password.")
}

/**
 * Email + password → the account token (the password is never stored).
 * Prefilled with the account email when signing in again. The email survives
 * rotation / process death; the password deliberately does not. Both fields
 * carry autofill hints so a password manager can fill them.
 */
@Composable
fun DecentSignInForm(
    dc: DecentSync.UiState,
    onSignIn: (email: String, password: String) -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier.fillMaxWidth(),
) {
    var email by rememberSaveable(dc.email) { mutableStateOf(dc.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    val canSubmit = !dc.busy && email.isNotBlank() && password.isNotEmpty()
    val submit = {
        if (canSubmit) {
            onSignIn(email, password)
            password = ""
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CremaTextField(
            value = email,
            onValueChange = { email = it },
            placeholder = "email@example.com",
            modifier = fieldModifier,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            contentType = ContentType.Username + ContentType.EmailAddress,
        )
        CremaTextField(
            value = password,
            onValueChange = { password = it },
            placeholder = "Decent password",
            modifier = fieldModifier,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            visualTransformation = PasswordVisualTransformation(),
            contentType = ContentType.Password,
        )
        dc.signInError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        CremaButton(
            onClick = submit,
            variant = CremaButtonVariant.Filled,
            icon = "sign-in",
            enabled = canSubmit,
            label = when {
                dc.busy -> "Signing in…"
                dc.needsReauth -> "Sign in again"
                else -> "Sign in"
            },
        )
    }
}

/** The "Upload finished shots" toggle for Decent, plus its catch-up offer when one is pending. */
@Composable
fun DecentUploadRows(
    dc: DecentSync.UiState,
    offer: CatchUpOffer?,
    busy: Boolean,
    onAutoUpload: (Boolean) -> Unit,
    onRunCatchUp: () -> Unit,
    onDismissCatchUp: () -> Unit,
) {
    val decentOffer = offer?.takeIf { it.destination == UploadTargetId.Decent }
    CremaSettingsRow(
        "Upload finished shots",
        "Push each shot to your Decent account as it finishes. Flushes under 5 s are skipped.",
        last = decentOffer == null,
    ) {
        CremaSwitch(dc.autoUpload, onAutoUpload)
    }
    decentOffer?.let { CatchUpRow(it, busy = busy, onUpload = onRunCatchUp, onDismiss = onDismissCatchUp) }
}
