package coffee.crema.drive

import android.content.Context
import kotlinx.serialization.json.Json

/**
 * The `play` build's ways to a Drive token (#336), in the order sign-in
 * prefers them. For now the browser sign-in only, as in `foss`; Google Play
 * services' Authorization API comes first here once it lands.
 * `src/foss` has the same object; the compiler takes the one built.
 */
object DriveAuthorizers {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, clientId: String, json: Json): List<DriveAuthorizer> =
        listOf(BrowserAuthorizer(clientId, json))
}
