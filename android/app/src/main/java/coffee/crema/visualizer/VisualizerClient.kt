package coffee.crema.visualizer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/*
 * The authenticated Visualizer API client — the Android `visualizerCall`
 * (web `$lib/services/visualizer-call.ts`): one entry point, the same error
 * taxonomy (401 → auth, 402/403 → premium-gated, 404 → not-found, transport
 * → network), JSON bodies in and out over OkHttp (HttpURLConnection can't
 * send the PATCH verb shot-edit sync needs).
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

    // OkHttp rather than HttpURLConnection: the latter rejects the PATCH verb,
    // which shot-edit sync (PATCH /shots/{id}) requires.
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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
        val req = Request.Builder()
            .url("$API_BASE$path")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .method(
                method,
                body?.let {
                    json.encodeToString(JsonElement.serializer(), it)
                        .toRequestBody("application/json".toMediaType())
                },
            )
            .build()
        val response = try {
            http.newCall(req).execute()
        } catch (e: Exception) {
            throw VisualizerError.Network("Couldn't reach Visualizer: ${e.message}")
        }
        response.use { res ->
            val status = res.code
            val text = res.body?.string().orEmpty()
            when {
                status == 401 -> throw VisualizerError.Auth()
                status == 402 || status == 403 -> throw VisualizerError.PremiumGated()
                status == 404 -> throw VisualizerError.NotFound()
                status !in 200..299 -> throw VisualizerError.Http(status, "Visualizer HTTP $status")
                text.isBlank() -> null
                else -> runCatching { json.parseToJsonElement(text) }
                    .getOrElse { throw VisualizerError.Network("Visualizer returned a malformed body") }
            }
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

    /** One row of `GET /api/shots` — the fields the pull walk needs. */
    data class ShotSummary(val id: String, val clockSec: Long, val updatedAtSec: Long)

    /**
     * `GET /api/shots?page=…&items=…&sort=updated_at` — newest-updated first,
     * so the pull walk can stop at the cursor. Returns the summaries + total
     * page count.
     */
    suspend fun listShots(accessToken: String, page: Int, items: Int): Pair<List<ShotSummary>, Int> {
        val body = request("GET", "/shots?page=$page&items=$items&sort=updated_at", accessToken)?.jsonObject
            ?: return emptyList<ShotSummary>() to 1
        val data = (body["data"] as? kotlinx.serialization.json.JsonArray) ?: return emptyList<ShotSummary>() to 1
        val summaries = data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ShotSummary(
                id = id,
                clockSec = o["clock"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L,
                updatedAtSec = o["updated_at"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L,
            )
        }
        val pages = body["paging"]?.jsonObject?.get("pages")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
        return summaries to pages
    }

    /** `GET /api/shots/{id}` — the full detail, passed VERBATIM to the core. */
    suspend fun fetchShotDetail(accessToken: String, id: String): JsonElement =
        request("GET", "/shots/$id", accessToken)
            ?: throw VisualizerError.Network("Empty shot detail")

    /** `PATCH /api/shots/{id}` with a `{"shot": {…}}` envelope. */
    suspend fun patchShot(accessToken: String, visualizerId: String, shotBody: JsonObject) {
        request(
            "PATCH",
            "/shots/$visualizerId",
            accessToken,
            kotlinx.serialization.json.buildJsonObject { put("shot", shotBody) },
        )
    }
}
