package coffee.crema.drive

import android.content.Context
import kotlinx.serialization.json.Json

/**
 * The `foss` build's ways to a Drive token (#336): the browser sign-in only.
 * This build carries no Google code (the `checkFossHasNoGoogle` guard).
 * `src/play` has the same object; the compiler takes the one built.
 */
object DriveAuthorizers {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, clientId: String, json: Json): List<DriveAuthorizer> =
        listOf(BrowserAuthorizer(clientId, json))
}
