package coffee.crema.drive

import coffee.crema.core.SyncFailure
import kotlinx.serialization.Serializable
import java.io.IOException

/*
 * The ways Crema gets a Google Drive token (#336). Each distribution builds its
 * own list in `DriveAuthorizers` (src/foss, src/play: the same name, so the
 * compiler takes the one built). [DriveSync] picks one at sign-in and
 * dispatches on the stored [DriveTokenSet.method] afterwards; it knows nothing
 * of how any way works. The browser way ([BrowserAuthorizer]) is in both.
 */

const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"

/** How a stored grant was made, so it's refreshed and revoked the same way. */
@Serializable
enum class AuthMethod {
    /** The browser sign-in: PKCE over a Custom Tab, with a refresh token. */
    BROWSER,

    /** Google Play services' Authorization API (`play` builds only). */
    PLAY,
}

/** A Drive grant — persisted by [DriveStore]. */
@Serializable
data class DriveTokenSet(
    val accessToken: String,
    /** Present only on the first consent (`access_type=offline` + `prompt=consent`);
     *  carried forward on silent refresh. */
    val refreshToken: String? = null,
    /** Absolute expiry, ms since epoch. */
    val expiresAt: Long,
    val scope: String = DRIVE_SCOPE,
    val tokenType: String = "Bearer",
    /** The way that made it. Stored state from before #336 decodes as [AuthMethod.BROWSER]. */
    val method: AuthMethod = AuthMethod.BROWSER,
    /** [AuthMethod.PLAY]: the Google account the grant is for, so silent calls stay on it. */
    val account: String? = null,
)

/**
 * What the grant's refresh spends, and what a late refresh's write keys on
 * ([coffee.crema.sync.SingleFlightRefresh]): the refresh token for the browser
 * way, which has one; the access token for a way that has none.
 */
internal fun DriveTokenSet.grantKey(): String? = when (method) {
    AuthMethod.BROWSER -> refreshToken
    AuthMethod.PLAY -> accessToken
}

/**
 * Google answered, but not with what was asked for: its HTTP [status] and reply
 * [body], so the core can tell what that means (`classify_sync_failure`). A
 * transport failure stays a plain [IOException].
 */
class DriveHttpError(val status: Int, val body: String, message: String) : IOException(message)

/** What a silent token request found. */
sealed interface FreshToken {
    /** A set to call with. */
    data class Token(val set: DriveTokenSet) : FreshToken

    /** The session stands, but no token for now. */
    data class Waiting(val failure: SyncFailure) : FreshToken

    /** Google ended the sign-in, or it needs the user: signed out, with the reconnect notice. */
    data object NeedsReconnect : FreshToken
}

/** Where an interactive sign-in shows its UI: the Activity. */
fun interface SignInHost {
    /** Open [url] in a Custom Tab. */
    fun openUrl(url: String)
}

/** How an interactive sign-in began. */
sealed interface Begin {
    /**
     * The browser hop: [DriveSync] keeps [verifier] and [state] for the
     * redirect (the process can die while the Custom Tab is up), then opens [url].
     */
    class Browser(val verifier: String, val state: String, val url: String) : Begin
}

/** What came back to the app from an interactive sign-in. */
sealed interface SignInResult {
    /** The browser redirect's code, checked against the stored handshake, and its verifier. */
    class Browser(val code: String, val verifier: String) : SignInResult
}

/** One way to get a Drive token. */
interface DriveAuthorizer {
    val method: AuthMethod

    /** Cheap, no UI: false when this way can't work on this phone. */
    fun available(): Boolean

    /**
     * A token for [stored]'s grant, silently: no UI, so a background backup can
     * call it. [rejected]: Drive just refused [stored]'s access token.
     */
    suspend fun fresh(stored: DriveTokenSet, rejected: Boolean): FreshToken

    /** Start an interactive sign-in; its result returns through [complete]. */
    suspend fun begin(host: SignInHost): Begin

    /** Finish an interactive sign-in: the grant to store. Throws when it failed. */
    suspend fun complete(result: SignInResult): DriveTokenSet

    /** End [stored]'s grant at Google, best-effort. Never throws. */
    suspend fun revoke(stored: DriveTokenSet)
}
