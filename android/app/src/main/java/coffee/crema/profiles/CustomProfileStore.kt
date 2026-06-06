package coffee.crema.profiles

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

/*
 * Custom (user) profiles — persistence + the editor's JSON helpers.
 *
 * Profiles are part user-data (custom recipes) and part core-data (the built-in
 * corpus). The shell owns CRUD + storage for the custom ones; the core owns the
 * full `CremaProfile` shape and every adapter (segment↔step, blank, to-wire) —
 * which cross the FFI as JSON strings (`blankCremaProfile()`, `cremaProfileToWire()`).
 *
 * KEY: a custom profile is stored as the COMPLETE `CremaProfile` JSON (the same
 * shape `builtinCremaProfiles()` emits), NOT the thin display model. The thin
 * Kotlin [CremaProfile] drops most fields (per-segment temp / mode / ramp / …,
 * notes, author, beverageType, …) via `ignoreUnknownKeys`; serializing it would
 * silently corrupt a profile (every segment → 0 °C). So the non-curve editor
 * PATCHES only the edited fields into the complete tree ([patchCremaProfileJson])
 * and the rest round-trips untouched through `cremaProfileToWire()` to the DE1.
 *
 * Mirrors `beans/BeanLibrary.kt`: a dependency-free JSON file in filesDir, suspend
 * load/save on Dispatchers.IO; DataStore/Room can replace the backing later behind
 * the same suspend API.
 */

/** The persisted custom-profile library — each entry a full `CremaProfile` object. */
@Serializable
private data class CustomProfileLibrary(val profiles: List<JsonObject> = emptyList())

/** File-backed JSON persistence for the custom profiles (`filesDir/customProfiles.json`). */
class CustomProfileStore(private val context: Context, private val json: Json) {
    private val file get() = File(context.filesDir, FILE_NAME)

    /**
     * Load the custom profiles as full-JSON strings (newest first, as stored), or
     * an empty list if absent / unreadable. Each string is a complete `CremaProfile`
     * object ready for the thin-model decode (display) or `cremaProfileToWire`.
     */
    suspend fun load(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            file.takeIf { it.exists() }?.readText()
                ?.let { json.decodeFromString(CustomProfileLibrary.serializer(), it) }
                ?.profiles?.map { it.toString() }
        }.getOrNull() ?: emptyList()
    }

    /** Persist the custom profiles (full-JSON strings in; best-effort). */
    suspend fun save(profiles: List<String>) {
        withContext(Dispatchers.IO) {
            runCatching {
                val objs = profiles.mapNotNull {
                    runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
                }
                file.writeText(
                    json.encodeToString(CustomProfileLibrary.serializer(), CustomProfileLibrary(objs)),
                )
            }
        }
    }

    private companion object {
        const val FILE_NAME = "customProfiles.json"
    }
}

/**
 * The user's brew defaults for a brand-new profile, as the JSON `blankCremaProfile`
 * expects (`{doseG, ratio, brewTempC, preinfusionS}` — `BrewDefaults`, camelCase).
 *
 * Android has no brew-defaults settings store yet, so v1 passes neutral constants
 * (the same fallbacks the core uses on import). When a Brew-defaults settings
 * section lands, thread the dialled-in numbers through here.
 */
fun brewDefaultsJson(
    doseG: Float = 18f,
    ratio: Float = 2f,
    brewTempC: Float = 93f,
    preinfusionS: Float = 8f,
): String = """{"doseG":$doseG,"ratio":$ratio,"brewTempC":$brewTempC,"preinfusionS":$preinfusionS}"""

/** Read a profile's `id` out of its full JSON, or null if malformed. */
fun profileIdOf(rawJson: String, json: Json): String? =
    runCatching { json.parseToJsonElement(rawJson).jsonObject["id"]?.jsonPrimitive?.contentOrNull }
        .getOrNull()

/**
 * Duplicate a complete `CremaProfile` JSON into a fresh CUSTOM one: a new shell-minted
 * id, `source: "custom"`, an unpinned `"<name> (copy)"`, and every other field — all
 * segments with their temps / modes / limiters — preserved verbatim so the copy uploads
 * identically. Used by "Duplicate" (e.g. to customise a built-in).
 */
fun duplicatedCustomProfileJson(baseJson: String, json: Json): String {
    val root = json.parseToJsonElement(baseJson).jsonObject.toMutableMap()
    val baseName = (root["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    root["id"] = JsonPrimitive("profile:" + UUID.randomUUID())
    root["source"] = JsonPrimitive("custom")
    root["name"] = JsonPrimitive(if (baseName.isBlank()) "Custom profile" else "$baseName (copy)")
    root["pinned"] = JsonPrimitive(false)
    return json.encodeToString(JsonObject.serializer(), JsonObject(root))
}

/**
 * Patch the non-curve editor's edited fields into a complete `CremaProfile` JSON,
 * leaving EVERY other field untouched — this is what keeps a custom profile
 * round-trippable to the DE1.
 *
 * Overwrites only: `source` (forced to `"custom"`), `name`, `roast`
 * (`"light"|"medium"|"dark"` or null), `tags`, `pinned`, `notes`, `dose`, `yieldOut`,
 * `brewTemp`, `maxTotalVolumeMl`, and per-existing-segment `target` + `time`
 * (positional — the non-curve editor never adds / removes segments). Each
 * segment's `temp` / `mode` / `ramp` / `tempSensor` / `exit` / `limiter` etc.
 * survive as-is, as do `author` / `beverageType` / `tankTemperatureC` /
 * `preinfuseStepCount` / `stopOnWeight` / `autoTare` / `id`.
 *
 * @param segments (target, time) for each segment, in order; extra/missing entries
 *   are ignored so a length mismatch degrades gracefully.
 */
fun patchCremaProfileJson(
    baseJson: String,
    name: String,
    roast: String?,
    tags: List<String>,
    pinned: Boolean,
    notes: String,
    dose: Float,
    yieldOut: Float,
    brewTemp: Float,
    maxTotalVolumeMl: Int,
    segments: List<SegmentEdit>,
    json: Json,
): String {
    val root = json.parseToJsonElement(baseJson).jsonObject
    val baseSegments = (root["segments"] as? JsonArray) ?: JsonArray(emptyList())
    // Build the segment array from the EDIT list so the editor can add / remove
    // segments: an existing index reuses that base segment (its tempSensor /
    // volumeLimitMl / exit / limiter survive); a new index clones the first base
    // segment as a template and gets a fresh id + name. Removed segments simply
    // fall off the end.
    val template = baseSegments.firstOrNull() as? JsonObject
    val patchedSegments = JsonArray(
        segments.mapIndexed { i, edit ->
            val baseSeg = baseSegments.getOrNull(i) as? JsonObject
            val base = baseSeg ?: template ?: JsonObject(emptyMap())
            val overlay = buildMap {
                put("target", JsonPrimitive(edit.target))
                put("time", JsonPrimitive(edit.time))
                edit.mode?.let { put("mode", JsonPrimitive(it)) }
                edit.ramp?.let { put("ramp", JsonPrimitive(it)) }
                edit.temp?.let { put("temp", JsonPrimitive(it)) }
                edit.volume?.let { put("volumeLimitMl", JsonPrimitive(it)) }
                // Exit / limiter: write the complete object when enabled, else
                // JsonNull to CLEAR a base condition (the patch merges over base,
                // so an omitted key would inherit it). Objects are always complete
                // (Rust ExitCondition/Limiter have no Option fields).
                put(
                    "exit",
                    edit.exit?.let { ex ->
                        buildJsonObject {
                            put("metric", JsonPrimitive(ex.metric ?: "flow"))
                            put("compare", JsonPrimitive(ex.compare ?: "over"))
                            put("threshold", JsonPrimitive(ex.threshold ?: 4f))
                        }
                    } ?: JsonNull,
                )
                put(
                    "limiter",
                    edit.limiter?.let { lm ->
                        buildJsonObject {
                            put("value", JsonPrimitive(lm.value))
                            put("range", JsonPrimitive(lm.range))
                        }
                    } ?: JsonNull,
                )
                if (baseSeg == null) {
                    put("id", JsonPrimitive("seg:" + UUID.randomUUID()))
                    put("name", JsonPrimitive(edit.name.ifBlank { "Segment ${i + 1}" }))
                }
            }
            JsonObject(base + overlay)
        },
    )
    val patched = root.toMutableMap().apply {
        put("source", JsonPrimitive("custom"))
        put("name", JsonPrimitive(name))
        put("roast", roast?.let { JsonPrimitive(it) } ?: JsonNull)
        put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
        put("pinned", JsonPrimitive(pinned))
        put("notes", JsonPrimitive(notes))
        put("dose", JsonPrimitive(dose))
        put("yieldOut", JsonPrimitive(yieldOut))
        put("brewTemp", JsonPrimitive(brewTemp))
        put("maxTotalVolumeMl", JsonPrimitive(maxTotalVolumeMl))
        put("segments", patchedSegments)
    }
    return json.encodeToString(JsonObject.serializer(), JsonObject(patched))
}

/**
 * The editor's per-segment edit — the fields the segment editor changes, patched
 * onto each existing segment's JSON (mode / ramp / temp are null-skipped; every
 * other field on the base segment — tempSensor, volumeLimitMl, exit, limiter —
 * survives). `name` is for the editor's display only (not written to the wire).
 */
data class SegmentEdit(
    val name: String,
    val mode: String?,
    val ramp: String?,
    val target: Float,
    val time: Float,
    val temp: Float?,
    val volume: Float? = null,
    /** Early-exit condition, or null = disabled. */
    val exit: SegmentExit? = null,
    /** Max limiter, or null = disabled. */
    val limiter: SegmentLimiter? = null,
)

/**
 * Flip just the top-level `pinned` flag on a complete CremaProfile JSON, leaving
 * every other field untouched — the Favorites star toggle for a CUSTOM profile
 * (built-ins toggle via [PinnedProfileStore] instead). Persisted by the caller.
 */
fun setProfilePinnedJson(baseJson: String, pinned: Boolean, json: Json): String {
    val root = json.parseToJsonElement(baseJson).jsonObject.toMutableMap()
    root["pinned"] = JsonPrimitive(pinned)
    return json.encodeToString(JsonObject.serializer(), JsonObject(root))
}

/**
 * Override just the top-level dose / yield / brew-temp on a complete CremaProfile
 * JSON, leaving every other field (segments, limiters, …) untouched — the
 * Quick-Controls transient override baked into a shot upload (the web shell's
 * re-upload-with-overrides). Does NOT persist to the library.
 */
fun overrideBrewParamsJson(baseJson: String, dose: Float, yieldOut: Float, brewTemp: Float, json: Json): String {
    val root = json.parseToJsonElement(baseJson).jsonObject.toMutableMap()
    root["dose"] = JsonPrimitive(dose)
    root["yieldOut"] = JsonPrimitive(yieldOut)
    root["brewTemp"] = JsonPrimitive(brewTemp)
    return json.encodeToString(JsonObject.serializer(), JsonObject(root))
}

/**
 * Clone the active profile into a NEW custom profile carrying the Quick-Controls
 * dial values (the web shell's "Save preset"): fresh id + source:custom + the
 * given name + dose/yield/brew-temp, every segment preserved. Does NOT mutate the
 * source profile.
 */
fun quickPresetJson(baseJson: String, name: String, dose: Float, yieldOut: Float, brewTemp: Float, json: Json): String {
    val dup = duplicatedCustomProfileJson(baseJson, json)
    val root = json.parseToJsonElement(dup).jsonObject.toMutableMap()
    root["name"] = JsonPrimitive(name.ifBlank { "Custom preset" })
    root["dose"] = JsonPrimitive(dose)
    root["yieldOut"] = JsonPrimitive(yieldOut)
    root["brewTemp"] = JsonPrimitive(brewTemp)
    root["pinned"] = JsonPrimitive(false)
    return json.encodeToString(JsonObject.serializer(), JsonObject(root))
}
