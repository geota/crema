package coffee.crema.ui.screens

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Shown instead of the app when the last launch crashed or didn't stabilise
 * (see [coffee.crema.diag.SafeMode]). Lets the user copy/share the report and —
 * crucially — reset the saved DE1/scale to escape a connect-crash loop, all
 * *before* any auto-connect runs.
 *
 * `report` is null when only the boot marker tripped (a native crash leaves no
 * in-process report): the copy/share affordances are hidden, but Reset/Continue
 * still recover.
 */
@Composable
fun CrashRecoveryScreen(
    report: String?,
    onReset: () -> Unit,
    onContinue: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Crema recovered from a crash", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (report != null) {
                    "The last session ended unexpectedly. Sending this report helps fix it. " +
                        "If it crashes right after connecting, reset your saved device."
                } else {
                    "The last session didn't finish starting up — likely a crash while " +
                        "connecting. Reset your saved device, or try again."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (report != null) {
                Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            report,
                            modifier = Modifier
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("Crema crash report", report)),
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Copy") }
                    OutlinedButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Crema crash report")
                                putExtra(Intent.EXTRA_TEXT, report)
                            }
                            context.startActivity(Intent.createChooser(send, "Share crash report"))
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Share…") }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text("Reset DE1 & scale")
            }
            TextButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Continue without reset")
            }
        }
    }
}
