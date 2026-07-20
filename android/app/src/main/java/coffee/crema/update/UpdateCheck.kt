package coffee.crema.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/*
 * On-demand release check against the crema GitHub repo (issue #36).
 *
 * Deliberately user-triggered, never automatic: an app that silently phones
 * GitHub on every launch is a privacy posture the Settings row shouldn't
 * default into, and unauthenticated api.github.com is rate-limited to
 * 60 req/hr per IP anyway. One tap → two GETs → facts.
 *
 * Release layout (see .github/workflows/):
 *  - stable:  tag `v<semver>` — `releases/latest` returns the newest.
 *  - nightly: ONE rolling prerelease tag literally named `nightly`,
 *    deleted + recreated on every push to main; its APK asset is named
 *    `crema-nightly-<versionName>.apk`.
 */

/** What the release check found — raw facts; the Settings row formats them. */
data class UpdateInfo(
    /** Newest stable version, e.g. `0.2.0` (the `v` tag stripped). */
    val latestStable: String?,
    /** Newest nightly versionName, e.g. `0.2.0-nightly.37+gabc1234`. */
    val latestNightly: String?,
    /** Whole days since the newest stable was published. */
    val stableAgeDays: Long?,
    /** Whole days since the newest nightly was published. */
    val nightlyAgeDays: Long?,
    /** Non-null when the check failed (offline, rate-limited, …). */
    val error: String? = null,
)

private const val REPO_API = "https://api.github.com/repos/geota/crema"

private val http = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

private fun getJson(url: String, json: Json): kotlinx.serialization.json.JsonObject? {
    val req = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        // GitHub rejects UA-less requests.
        .header("User-Agent", "crema-app")
        .build()
    return http.newCall(req).execute().use { res ->
        if (!res.isSuccessful) return@use null
        val text = res.body?.string().orEmpty()
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    }
}

private fun ageDays(publishedAt: String?): Long? = runCatching {
    publishedAt?.let { ChronoUnit.DAYS.between(Instant.parse(it), Instant.now()) }
}.getOrNull()

/** One release check — two GETs, no auth, no caching. Call off the main thread. */
suspend fun checkForUpdates(json: Json): UpdateInfo = withContext(Dispatchers.IO) {
    runCatching {
        val stable = getJson("$REPO_API/releases/latest", json)
        val nightly = getJson("$REPO_API/releases/tags/nightly", json)
        val stableTag = stable?.get("tag_name")?.jsonPrimitive?.content
        // The nightly's versionName rides its APK asset name — the release
        // name/tag are both just "nightly".
        val nightlyAsset = nightly?.get("assets")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("name")?.jsonPrimitive?.content
        val nightlyVersion = nightlyAsset
            ?.removePrefix("crema-nightly-")
            ?.removeSuffix(".apk")
        UpdateInfo(
            latestStable = stableTag?.removePrefix("v"),
            latestNightly = nightlyVersion,
            stableAgeDays = ageDays(stable?.get("published_at")?.jsonPrimitive?.content),
            nightlyAgeDays = ageDays(nightly?.get("published_at")?.jsonPrimitive?.content),
        )
    }.getOrElse {
        UpdateInfo(null, null, null, null, error = it.message ?: "Update check failed")
    }
}
