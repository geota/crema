package coffee.crema.visualizer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/*
 * The authenticated Visualizer API client — the Android `visualizerCall`
 * (web `$lib/services/visualizer-call.ts`): one entry point, the same error
 * taxonomy (401 → auth, 402/403 → premium-gated, 404 → not-found, transport
 * → network), JSON bodies in and out over plain HttpURLConnection.
 *
 * Token freshness (proactive refresh + the one-shot 401 retry) lives in
 * [VisualizerSync.withFreshToken], which owns the store — this client is
 * stateless per call.
 */

/** Visualizer API base (one SaaS; no self-hosting story). */
const val API_BASE = "https://visualizer.coffee/api"

/** The closed error union (web `VisualizerCallError`). */
sealed class VisualizerError(message: String) : Exception(message) {
    /** 401 — token invalid/expired beyond refresh. */
    class Auth : VisualizerError("Not signed in to Visualizer")

    /** 402/403 — premium-gated endpoint on a free account. */
    class PremiumGated : VisualizerError("Requires a Visualizer premium account")

    /** 404 — the resource is gone server-side. */
    class NotFound : VisualizerError("Not found on Visualizer")

    /** Transport failure or malformed body — worth retrying later. */
    class Network(message: String) : VisualizerError(message)

    /** Any other non-2xx. */
    class Http(val status: Int, message: String) : VisualizerError(message)
}

class VisualizerClient(private val json: Json) {

    /**
     * One authenticated request. Returns the parsed JSON body for a 2xx (null
     * for 204 / empty); throws a [VisualizerError] otherwise.
     */
    suspend fun request(
        method: String,
        path: String,
        accessToken: String,
        body: JsonElement? = null,
    ): JsonElement? = withContext(Dispatchers.IO) {
        val conn = try {
            (URL("$API_BASE$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
        } catch (e: Exception) {
            throw VisualizerError.Network("Couldn't reach Visualizer: ${e.message}")
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(json.encodeToString(JsonElement.serializer(), body).toByteArray(Charsets.UTF_8)) }
            }
            val status = try {
                conn.responseCode
            } catch (e: Exception) {
                throw VisualizerError.Network("Couldn't reach Visualizer: ${e.message}")
            }
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            when {
                status == 401 -> throw VisualizerError.Auth()
                status == 402 || status == 403 -> throw VisualizerError.PremiumGated()
                status == 404 -> throw VisualizerError.NotFound()
                status !in 200..299 -> throw VisualizerError.Http(status, "Visualizer HTTP $status")
                text.isBlank() -> null
                else -> runCatching { json.parseToJsonElement(text) }
                    .getOrElse { throw VisualizerError.Network("Visualizer returned a malformed body") }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** `GET /api/me` → the account projection (defensive field reads). */
    suspend fun fetchAccount(accessToken: String): VisualizerAccount {
        val obj = request("GET", "/me", accessToken)?.jsonObject
            ?: throw VisualizerError.Network("Empty /me response")
        fun str(k: String) = obj[k]?.jsonPrimitive?.contentOrNull
        return VisualizerAccount(
            id = str("id") ?: "",
            name = str("name") ?: str("email") ?: "Visualizer user",
            public = str("public")?.toBoolean() ?: false,
            avatarUrl = str("avatar_url") ?: "",
        )
    }

    /**
     * `POST /api/shots/upload` with the community-v2 payload. Returns the new
     * Visualizer shot id.
     */
    suspend fun uploadShot(accessToken: String, payload: JsonObject): String {
        val res = request("POST", "/shots/upload", accessToken, payload)?.jsonObject
            ?: throw VisualizerError.Network("Visualizer accepted the shot but returned no body")
        return res["id"]?.jsonPrimitive?.contentOrNull
            ?: throw VisualizerError.Network("Visualizer accepted the shot but returned no id")
    }
}
