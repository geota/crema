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
/**
 * Extract the nightly versionName from the release's asset names. The
 * nightly release carries the APK **plus** its `.apk.idsig` and
 * `.apk.sha256` siblings, and the GitHub API's asset order follows upload
 * id — not contractual, and racy when the workflow uploads in parallel.
 * Naively taking `assets[0]` occasionally parsed
 * `0.0.4-nightly.25+g9eabfed.apk.idsig` as the "latest" version, which
 * fails equality against the installed versionName and prompted users on
 * the current nightly with an "update" to the very build they run. Pick
 * the asset that IS the apk (exactly one `.apk`-suffixed asset exists),
 * never positionally.
 */
internal fun nightlyVersionFromAssets(assetNames: List<String>): String? =
    assetNames
        .firstOrNull { it.startsWith("crema-nightly-") && it.endsWith(".apk") }
        ?.removePrefix("crema-nightly-")
        ?.removeSuffix(".apk")

/*
 * Version comparison — deliberately semantic, not `!=`.
 *
 * The installed versionName and the published one must be compared by what
 * they MEAN, for two reasons learnt the hard way:
 *
 *  - A build-type marker can differ without the build differing. The nightly
 *    buildType used to append `-nightly` on top of CI's already-nightly
 *    versionName, so the installed `0.0.5-nightly.42+gabc1234-nightly` never
 *    equalled the `0.0.5-nightly.42+gabc1234` it was built from — a permanent
 *    "update available" pointing at the running build. [canonicalVersion]
 *    strips that marker so builds already in the field stop nagging too.
 *  - "Different" is not "newer". A local build ahead of the published nightly,
 *    or a rollback of the rolling `nightly` release, would otherwise prompt a
 *    downgrade. Only a strictly greater version counts.
 */

/** A nightly versionName: `<major>.<minor>.<patch>-nightly.<commits>+g<sha>`. */
private val NIGHTLY_RE =
    Regex("""^(\d+)\.(\d+)\.(\d+)-nightly\.(\d+)(?:\+g([0-9a-f]+))?$""")

/** A stable versionName: `<major>.<minor>.<patch>`, any trailing metadata. */
private val STABLE_RE = Regex("""^(\d+)\.(\d+)\.(\d+)""")

/**
 * Strip the redundant `-nightly` buildType marker a nightly versionName may
 * carry twice, and surrounding whitespace. Idempotent: a name that says
 * `-nightly` exactly once (as the `nightly.<commits>` train marker, or as the
 * off-train marker on a local `assembleNightly`) is returned unchanged.
 */
internal fun canonicalVersion(v: String): String {
    val t = v.trim()
    val stripped = t.removeSuffix("-nightly")
    return if (stripped != t && stripped.contains("-nightly")) stripped else t
}

/** Ordering key for a nightly: base semver first, then the commit count. */
private fun nightlyKey(v: String): List<Int>? =
    NIGHTLY_RE.matchEntire(canonicalVersion(v))?.let { m ->
        (1..4).map { m.groupValues[it].toInt() }
    }

/** Ordering key for a stable release: the semver triple. */
private fun stableKey(v: String): List<Int>? =
    STABLE_RE.find(canonicalVersion(v))?.let { m ->
        (1..3).map { m.groupValues[it].toInt() }
    }

private fun greater(a: List<Int>, b: List<Int>): Boolean {
    for (i in a.indices) {
        if (a[i] != b[i]) return a[i] > b[i]
    }
    return false
}

/**
 * Whether [installed] and [published] are the same build — the "you're on the
 * latest" test. Compares canonical names, so the doubled `-nightly` marker
 * doesn't make a build look different from itself.
 */
fun isSameBuild(installed: String, published: String?): Boolean =
    published != null && canonicalVersion(installed) == canonicalVersion(published)

/**
 * Whether [published] is a strictly NEWER build than [installed] on the same
 * channel — the only condition that should ever raise an update notice.
 *
 * Nightlies compare by base semver then commit count; stables by semver
 * triple. Cross-channel or unparseable names fall back to "different, and not
 * a nightly we're ahead of", which is the most useful answer available.
 */
fun isNewerBuild(installed: String, published: String?): Boolean {
    val remote = published?.let(::canonicalVersion) ?: return false
    val local = canonicalVersion(installed)
    if (remote == local) return false
    nightlyKey(local)?.let { l ->
        nightlyKey(remote)?.let { r -> return greater(r, l) }
    }
    val ls = stableKey(local)
    val rs = stableKey(remote)
    if (ls != null && rs != null && ls != rs) return greater(rs, ls)
    // One side is a nightly and the other isn't (or neither parses): a version
    // string we can't order. Treat any difference as an update rather than
    // silently withholding a real one.
    return true
}

suspend fun checkForUpdates(json: Json): UpdateInfo = withContext(Dispatchers.IO) {
    runCatching {
        val stable = getJson("$REPO_API/releases/latest", json)
        val nightly = getJson("$REPO_API/releases/tags/nightly", json)
        val stableTag = stable?.get("tag_name")?.jsonPrimitive?.content
        // The nightly's versionName rides its APK asset name — the release
        // name/tag are both just "nightly".
        val assetNames = nightly?.get("assets")?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
            .orEmpty()
        val nightlyVersion = nightlyVersionFromAssets(assetNames)
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
