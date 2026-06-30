package coffee.crema.ui.components

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coffee.crema.diag.CrashReporter
import kotlinx.coroutines.launch

/**
 * Settings row that hands the user a diagnostics snapshot — app + device + build,
 * the recent event log, and any Rust panic the FFI swallowed — for a bug report.
 *
 * This is the *didn't-crash* path: when the app merely misbehaved there's no
 * pending crash report, so the user pulls one on demand. No personal data, no
 * auto-upload — Copy to the clipboard or Share via the system chooser. Shared by
 * the tablet `SettingsScreen` and the phone `PhoneSettingsScreen` (one source).
 */
@Composable
fun CopyDiagnosticsRow(last: Boolean = true) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    CremaSettingsRow(
        "Copy diagnostics",
        "App, device, recent event log, and any Rust panic — for a bug report.",
        last = last,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CremaButton(
                onClick = {
                    val text = CrashReporter.diagnostics(context)
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Crema diagnostics", text)))
                        Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                    }
                },
                variant = CremaButtonVariant.Text,
                label = "Copy",
            )
            CremaButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Crema diagnostics")
                        putExtra(Intent.EXTRA_TEXT, CrashReporter.diagnostics(context))
                    }
                    context.startActivity(Intent.createChooser(send, "Share diagnostics"))
                },
                variant = CremaButtonVariant.Text,
                label = "Share",
            )
        }
    }
}
