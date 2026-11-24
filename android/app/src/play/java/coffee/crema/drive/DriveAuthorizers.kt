package coffee.crema.drive

import android.content.Context
import kotlinx.serialization.json.Json

/**
 * The `play` build's ways to a Drive token (#336), in the order sign-in
 * prefers them: Google Play services' Authorization API where it works, the
 * browser sign-in otherwise (and for grants made that way).
 * `src/foss` has the same object; the compiler takes the one built.
 */
object DriveAuthorizers {
    fun create(context: Context, clientId: String, json: Json): List<DriveAuthorizer> =
        listOf(PlayAuthorizer(context), BrowserAuthorizer(clientId, json))
}
