package coffee.crema.drive

import coffee.crema.core.SyncFailure
import coffee.crema.visualizer.codeChallengeFromVerifier
import coffee.crema.visualizer.generateCodeVerifier
import coffee.crema.visualizer.randomState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/*
 * The browser way to a Drive token ([BrowserAuthorizer], in both `foss` and
 * `play`): Authorization-Code + PKCE against Google, the Android
 * twin of `web/src/lib/drive/oauth.ts`. Public client (no secret); scope
 * `drive.file` (the app only ever sees backups it created). A Custom Tab handles
 * the consent hop; Google bounces back to the reversed-client-id custom scheme
 * ([googleRedirectUri]), which the manifest routes to MainActivity.
 *
 * The PKCE verifier/state are generated here (reusing the Visualizer helpers)
 * and persisted in [DriveStore] by [DriveSync]; this file owns the URL
 * builder + token endpoint. `access_type=offline` + `prompt=consent` guarantee
 * a refresh token (Google omits it on silent re-auth, which would strand us
 * once the first one is gone).
 *
 * ── Console requirement (geota/crema#45) ───────────────────────────────────
 * Every Android OAuth client this app ships with MUST have **Advanced
 * Settings → "Enable Custom URI scheme"** ticked in the Google Cloud console
 * (APIs & Services → Credentials). It is OFF by default and Google has been
 * turning it off on existing clients; without it the authorize request is
 * refused before the user sees anything:
 *
 *     Error 400: invalid_request
 *     "Custom URI scheme is not enabled for your Android client."
 *
 * There is no code-side workaround — the Android client type accepts nothing
 * else. Verified against the live client: a loopback (`http://127.0.0.1:…`)
 * or `urn:ietf:wg:oauth:2.0:oob` redirect is refused by Google's OAuth 2.0
 * policy, and an `https` redirect is a `redirect_uri_mismatch` because
 * Android clients carry no redirect list at all (they are keyed on
 * package name + signing SHA-1).
 *
 * That keying also means ONE client per signing key: the debug key, the
 * nightly/release keystore, and Play App Signing each need their own Android
 * client in the project — each with the same checkbox ticked.
 *
 * To reproduce a rejection without a device, request the authorize URL and
 * base64-decode the `authError` query param off the `Location` header.
 */

const val GOOGLE_AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
/** Google's token revocation endpoint: accepts an access or refresh token. */
const val GOOGLE_REVOKE_URL = "https://oauth2.googleapis.com/revoke"

/**
 * The scheme half of the reversed-client-id redirect — also the value of the
 * `googleRedirectScheme` manifest placeholder. For an Android OAuth client,
 * Google requires `com.googleusercontent.apps.<id>` (NOT an arbitrary
 * `crema://` scheme like the Visualizer flow uses).
 */
fun reversedClientScheme(clientId: String): String =
    if (clientId.endsWith(".apps.googleusercontent.com")) {
        "com.googleusercontent.apps." + clientId.removeSuffix(".apps.googleusercontent.com")
    } else {
        clientId
    }

/** Full reversed-client-id redirect URI Google bounces back to. */
fun googleRedirectUri(clientId: String): String = "${reversedClientScheme(clientId)}:/oauth2redirect"

/** Build the consent URL for the Custom-Tab hop. */
fun buildGoogleAuthorizeUrl(clientId: String, state: String, codeChallenge: String): String {
    fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    return GOOGLE_AUTHORIZE_URL +
        "?response_type=code" +
        "&client_id=${enc(clientId)}" +
        "&redirect_uri=${enc(googleRedirectUri(clientId))}" +
        "&scope=${enc(DRIVE_SCOPE)}" +
        "&state=${enc(state)}" +
        "&code_challenge=${enc(codeChallenge)}" +
        "&code_challenge_method=S256" +
        "&access_type=offline" +
        "&prompt=consent" +
        "&include_granted_scopes=true"
}

private fun formBody(params: Map<String, String>): String =
    params.entries.joinToString("&") { (k, v) ->
        "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
    }

/** POST the token endpoint and map the wire response → [DriveTokenSet]. Google
 *  keeps the same refresh token across refreshes, so carry [fallbackRefresh].
 *  Throws [DriveHttpError] on a non-2xx or token-less reply. */
private suspend fun postToken(
    params: Map<String, String>,
    fallbackRefresh: String?,
    json: Json,
): DriveTokenSet =
    withContext(Dispatchers.IO) {
        val conn = (URL(GOOGLE_TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(formBody(params).toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            val obj: JsonObject = runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrElse { throw DriveHttpError(status, body, "Google token endpoint returned non-JSON (HTTP $status).") }
            fun str(k: String) = obj[k]?.jsonPrimitive?.contentOrNull
            val access = str("access_token")
            if (status !in 200..299 || access == null) {
                throw DriveHttpError(
                    status,
                    body,
                    "Google token endpoint: ${str("error_description") ?: str("error") ?: "HTTP $status"}",
                )
            }
            val ttlS = obj["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
            DriveTokenSet(
                accessToken = access,
                refreshToken = str("refresh_token") ?: fallbackRefresh,
                expiresAt = System.currentTimeMillis() + ttlS * 1000,
                scope = str("scope") ?: DRIVE_SCOPE,
                tokenType = str("token_type") ?: "Bearer",
            )
        } finally {
            conn.disconnect()
        }
    }

/** Trade an authorization code for tokens (public client — verifier, no secret). */
suspend fun exchangeCodeForDriveToken(
    clientId: String,
    code: String,
    verifier: String,
    json: Json,
): DriveTokenSet =
    postToken(
        mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to googleRedirectUri(clientId),
            "client_id" to clientId,
            "code_verifier" to verifier,
        ),
        fallbackRefresh = null,
        json = json,
    )

/** Swap a refresh token for a fresh access token (Google keeps the refresh token). */
suspend fun refreshDriveToken(clientId: String, refreshToken: String, json: Json): DriveTokenSet =
    postToken(
        mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to clientId,
        ),
        fallbackRefresh = refreshToken,
        json = json,
    )

/**
 * Revoke a Drive grant at Google (best-effort; false on any failure). The
 * refresh token is preferred: revoking it ends the whole grant, so the app
 * disappears from the user's Google Account permissions. Without one, the
 * access token is revoked instead. Never throws.
 */
suspend fun revokeDriveGrant(tokens: DriveTokenSet, revokeUrl: String = GOOGLE_REVOKE_URL): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(revokeUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            try {
                val token = tokens.refreshToken ?: tokens.accessToken
                conn.outputStream.use { it.write(formBody(mapOf("token" to token)).toByteArray(Charsets.UTF_8)) }
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

/**
 * Today's sign-in, the only way in `foss` and the fallback in `play`: a
 * Custom Tab to Google's consent page, the redirect back on the
 * reversed-client-id scheme, and a refresh token for silent renewals. The
 * calls to Google are injected so JVM tests make none.
 */
class BrowserAuthorizer(
    private val clientId: String,
    private val json: Json,
    /** Wall clock (unix ms), for the failure's retry time; tests pin it. */
    private val now: () -> Long = System::currentTimeMillis,
    /** The OAuth code → token exchange. */
    private val exchange: suspend (code: String, verifier: String) -> DriveTokenSet =
        { code, verifier -> exchangeCodeForDriveToken(clientId, code, verifier, json) },
    /** The refresh-token grant. */
    private val refresh: suspend (refreshToken: String) -> DriveTokenSet = { refreshDriveToken(clientId, it, json) },
    /** Revoke a grant at Google, best-effort. */
    private val revokeGrant: suspend (DriveTokenSet) -> Unit = { revokeDriveGrant(it) },
) : DriveAuthorizer {
    override val method = AuthMethod.BROWSER

    /** Needs only a browser. */
    override fun available() = true

    /** Spends the refresh token. Only Google saying it's dead ends the session (geota/crema#323). */
    override suspend fun fresh(stored: DriveTokenSet, rejected: Boolean): FreshToken {
        val spent = stored.refreshToken ?: return FreshToken.NeedsReconnect
        return try {
            FreshToken.Token(refresh(spent))
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            val failure = driveFailure(e, "refresh", now())
            if (failure == SyncFailure.SessionEnded) FreshToken.NeedsReconnect else FreshToken.Waiting(failure)
        }
    }

    /** Mints the PKCE verifier and CSRF state; [DriveSync] keeps them and opens the URL. */
    override suspend fun begin(host: SignInHost): Begin {
        val verifier = generateCodeVerifier()
        val csrf = randomState()
        return Begin.Browser(verifier, csrf, buildGoogleAuthorizeUrl(clientId, csrf, codeChallengeFromVerifier(verifier)))
    }

    override suspend fun complete(result: SignInResult): DriveTokenSet = when (result) {
        is SignInResult.Browser -> exchange(result.code, result.verifier)
        is SignInResult.Consent -> throw IllegalArgumentException("not a browser redirect")
    }

    /** Revoking the refresh token ends the whole grant (final review 05 N-5). */
    override suspend fun revoke(stored: DriveTokenSet) = revokeGrant(stored)
}
