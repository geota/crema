package coffee.crema.visualizer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/*
 * Visualizer OAuth — the Authorization-Code + PKCE flow against
 * visualizer.coffee's Doorkeeper, ported 1:1 from the web shell
 * (`web/src/lib/visualizer/oauth.ts`).
 *
 * Crema's Android shell is a public client exactly like the PWA — there is
 * no secret to keep — so the Doorkeeper Application is registered
 * `confidential = false` and PKCE (RFC 7636, S256) binds the authorization
 * code to this device. The browser (ACTION_VIEW) handles the authorize hop;
 * Visualizer bounces back to the custom-scheme [REDIRECT_URI], which the
 * manifest routes to MainActivity → [VisualizerSync.handleCallback].
 *
 * The verifier + CSRF state are persisted in [VisualizerStore] (not memory)
 * because the process can die while the user is in the browser.
 */

/** Visualizer authorization endpoint (Doorkeeper). */
const val AUTHORIZE_URL = "https://visualizer.coffee/oauth/authorize"

/** Visualizer token endpoint (Doorkeeper). */
const val TOKEN_URL = "https://visualizer.coffee/oauth/token"

/** Visualizer token-revocation endpoint (Doorkeeper). */
const val REVOKE_URL = "https://visualizer.coffee/oauth/revoke"

/**
 * Scopes Crema requests — `read` (account/shots/bags), `upload` (shot
 * ingestion), `write` (manage shots/bags; premium). Same set as the web
 * shell; the server downgrades silently on the free tier.
 */
const val OAUTH_SCOPE = "read write upload"

/**
 * The redirect URI Visualizer bounces back to. A custom scheme the manifest
 * claims (BROWSABLE intent-filter on MainActivity). Must be registered in
 * the Doorkeeper application's Redirect URI list alongside the web origins.
 */
const val REDIRECT_URI = "crema://visualizer/callback"

/** A successful token exchange — what [VisualizerStore] persists. */
@Serializable
data class TokenSet(
    val accessToken: String,
    /** Doorkeeper returns one when the application allows offline access. */
    val refreshToken: String? = null,
    /** Absolute expiry, ms since epoch (best-effort). */
    val expiresAt: Long,
    /** Space-separated scope string the server actually granted. */
    val scope: String = OAUTH_SCOPE,
    /** Always "Bearer" for Doorkeeper codes. */
    val tokenType: String = "Bearer",
)

/** URL-safe base64 (RFC 4648 §5), no padding — the PKCE encoding. */
private fun base64Url(bytes: ByteArray): String =
    Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

/** 48 random bytes → 64 URL-safe chars (RFC 7636 §4.1 allows 43..128). */
fun generateCodeVerifier(): String {
    val bytes = ByteArray(48)
    SecureRandom().nextBytes(bytes)
    return base64Url(bytes)
}

/** S256 code challenge: base64url(SHA-256(ascii(verifier))) (RFC 7636 §4.2). */
fun codeChallengeFromVerifier(verifier: String): String =
    base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

/** Random CSRF state — 16 bytes is plenty for one redirect round-trip. */
fun randomState(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return base64Url(bytes)
}

/** The full `/oauth/authorize` URL for the browser hop. */
fun buildAuthorizeUrl(clientId: String, state: String, codeChallenge: String): String {
    fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    return AUTHORIZE_URL +
        "?response_type=code" +
        "&client_id=${enc(clientId)}" +
        "&redirect_uri=${enc(REDIRECT_URI)}" +
        "&scope=${enc(OAUTH_SCOPE)}" +
        "&state=${enc(state)}" +
        "&code_challenge=${enc(codeChallenge)}" +
        "&code_challenge_method=S256"
}

/** Form-encode a body map. */
private fun formBody(params: Map<String, String>): String =
    params.entries.joinToString("&") { (k, v) ->
        "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
    }

/**
 * POST `application/x-www-form-urlencoded` to the token endpoint and map the
 * wire response onto a [TokenSet]. Throws [IOException] with the Doorkeeper
 * `error_description` on a non-2xx or token-less body (the web's
 * `tokenSetFromWire` semantics: `expires_in` seconds → absolute deadline,
 * 1-hour default when omitted).
 */
private suspend fun postToken(params: Map<String, String>, json: Json): TokenSet =
    withContext(Dispatchers.IO) {
        val conn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
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
                .getOrElse { throw IOException("Visualizer token endpoint returned non-JSON (HTTP $status).") }
            fun str(k: String) = obj[k]?.jsonPrimitive?.contentOrNull
            val access = str("access_token")
            if (status !in 200..299 || access == null) {
                throw IOException("Visualizer token endpoint: ${str("error_description") ?: str("error") ?: "HTTP $status"}")
            }
            val ttlS = obj["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
            TokenSet(
                accessToken = access,
                refreshToken = str("refresh_token"),
                expiresAt = System.currentTimeMillis() + ttlS * 1000,
                scope = str("scope") ?: OAUTH_SCOPE,
                tokenType = str("token_type") ?: "Bearer",
            )
        } finally {
            conn.disconnect()
        }
    }

/** Trade an authorization code for tokens (public client — verifier, no secret). */
suspend fun exchangeCodeForToken(clientId: String, code: String, verifier: String, json: Json): TokenSet =
    postToken(
        mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to clientId,
            "code_verifier" to verifier,
        ),
        json,
    )

/** Refresh the access token (Doorkeeper rotates refresh tokens — keep the new one). */
suspend fun refreshAccessToken(clientId: String, refreshToken: String, json: Json): TokenSet =
    postToken(
        mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to clientId,
        ),
        json,
    )

/** Best-effort revocation (RFC 7009 — 200 either way; failures are swallowed). */
suspend fun revokeToken(clientId: String, token: String) {
    withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(REVOKE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            try {
                conn.outputStream.use {
                    it.write(formBody(mapOf("token" to token, "client_id" to clientId)).toByteArray(Charsets.UTF_8))
                }
                conn.responseCode // drive the request
            } finally {
                conn.disconnect()
            }
        }
    }
}
