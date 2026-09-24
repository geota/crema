package coffee.crema.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/*
 * The Android side of the History menu's cloud rows — kept out of
 * [UploadTargets] so that file stays pure Kotlin and JVM-testable.
 */

/**
 * Share = hand someone the PUBLIC link to an uploaded copy, via the system
 * share sheet (Messages, the forum, …). No-op when the destination has no
 * public link for the shot (see [UploadTarget.shareUrl]).
 */
fun shareUploadedLink(context: Context, target: UploadTarget) {
    val url = target.shareUrl ?: return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        putExtra(Intent.EXTRA_SUBJECT, "Espresso shot on ${target.name}")
    }
    context.startActivity(Intent.createChooser(send, "Share ${target.name} link"))
}

/** "View on X" — open the uploaded copy (or the account's history page) in the browser. */
fun openUploadedCopy(context: Context, target: UploadTarget) {
    val url = target.viewUrl ?: return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        // No browser — nothing sensible to do from a menu row.
    }
}
