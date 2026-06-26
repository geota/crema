package coffee.crema.ui

import coffee.crema.core.Bean
import coffee.crema.core.CommonSettings
import coffee.crema.core.MaintenanceState
import coffee.crema.core.Roaster
import coffee.crema.core.VisualizerSyncPrefs
import coffee.crema.history.StoredShot
import coffee.crema.maintenance.defaultMaintenanceState
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.toCommonSettings
import coffee.crema.visualizer.DEFAULT_VISUALIZER_SYNC_PREFS
import coffee.crema.visualizer.storedShotFromBackupJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The records parsed out of a `crema-backup/v1` JSONL bundle, before they're
 * applied to the live stores. Pure data so the line-parser ([parseBackupRecords])
 * is unit-testable without spinning up [MainViewModel] (review #07).
 */
data class ParsedBackup(
    val profiles: List<String>,
    val beans: List<Bean>,
    val roasters: List<Roaster>,
    val shots: List<StoredShot>,
    val prefs: AppPrefs?,
    val common: CommonSettings?,
    val qcGrind: Float?,
    val profileMeta: JsonObject?,
    val maintenance: MaintenanceState?,
    val visualizerPrefs: VisualizerSyncPrefs?,
    val sawHeader: Boolean,
)

/**
 * Parse a `crema-backup/v1` bundle's raw JSONL into typed records. Tolerant of
 * blank / non-`{` / non-JSON lines (skipped, never thrown) and unknown kinds.
 * [nowMs] seeds the maintenance default for any field an older bundle omits.
 *
 * Pure + side-effect-free: the caller ([MainViewModel.restoreBackupFromText])
 * owns the wipe/merge apply onto the stores. Shots ride the core StoredShot wire
 * shape, so they invert through [storedShotFromBackupJson]; profiles + the
 * settings / meta / maintenance / visualizer blobs ride as the shell owns them.
 */
fun parseBackupRecords(text: String, json: Json, nowMs: Long): ParsedBackup {
    val profiles = ArrayList<String>()
    val beans = ArrayList<Bean>()
    val roasters = ArrayList<Roaster>()
    val shots = ArrayList<StoredShot>()
    var prefs: AppPrefs? = null
    var common: CommonSettings? = null
    var qcGrind: Float? = null
    var profileMeta: JsonObject? = null
    var maintenance: MaintenanceState? = null
    var visualizerPrefs: VisualizerSyncPrefs? = null
    var sawHeader = false
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.length < 2 || line[0] != '{') continue
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
        when ((obj["kind"] as? JsonPrimitive)?.content) {
            "crema-backup/v1" -> sawHeader = true
            "settings" -> {
                val shell = (obj["_shell"] as? JsonPrimitive)?.content
                val commonObj = obj["common"] as? JsonObject
                if (commonObj != null) {
                    // Unified format: `common` is the cross-shell CommonSettings
                    // (apply from any shell); `qcGrind` is Android-only.
                    common = decodeFilled(json, CommonSettings.serializer(), AppPrefs().toCommonSettings(), commonObj)
                    if (shell == null || shell == "android") {
                        qcGrind = (obj["qcGrind"] as? JsonPrimitive)?.floatOrNull
                    }
                } else if (shell == null || shell == "android") {
                    // Pre-unification Android backup — a flat AppPrefs settings line.
                    prefs = runCatching { json.decodeFromString(AppPrefs.serializer(), stripKind(json, obj)) }.getOrNull()
                }
            }
            "profileMeta" -> profileMeta = obj
            "maintenance" -> maintenance = decodeFilled(json, MaintenanceState.serializer(), defaultMaintenanceState(nowMs), obj)
            "visualizerPrefs" -> visualizerPrefs = decodeFilled(json, VisualizerSyncPrefs.serializer(), DEFAULT_VISUALIZER_SYNC_PREFS, obj)
            "roaster" -> runCatching { json.decodeFromString(Roaster.serializer(), stripKind(json, obj)) }.getOrNull()?.let(roasters::add)
            "bean" -> runCatching { json.decodeFromString(Bean.serializer(), stripKind(json, obj)) }.getOrNull()?.let(beans::add)
            // Shots are core-shape (formatVersion/completedAt/record) on the wire —
            // both from a fixed Android backup and from any web-origin backup — so
            // invert via storedShotFromBackupJson, NOT the flat serializer (which
            // MissingFieldException'd and silently dropped them) (issue 01).
            "shot" -> storedShotFromBackupJson(obj, json)?.let(shots::add)
            "profile" -> profiles.add(stripKind(json, obj))
            else -> {}
        }
    }
    return ParsedBackup(
        profiles = profiles,
        beans = beans,
        roasters = roasters,
        shots = shots,
        prefs = prefs,
        common = common,
        qcGrind = qcGrind,
        profileMeta = profileMeta,
        maintenance = maintenance,
        visualizerPrefs = visualizerPrefs,
        sawHeader = sawHeader,
    )
}

/** Strip the `kind` discriminator off a tagged JSONL object → the bare record JSON. */
internal fun stripKind(json: Json, obj: JsonObject): String =
    json.encodeToString(JsonObject.serializer(), JsonObject(obj.filterKeys { it != "kind" }))

/**
 * Decode a backup config blob with forward-compat tolerance: any field the bundle
 * omits (an older backup predating a field a newer build added) is filled from
 * [defaults] rather than failing the whole decode. The typeshare Kotlin classes
 * carry no field defaults, so a plain decode would throw on a missing field and
 * silently drop the entire group.
 */
internal fun <T> decodeFilled(json: Json, serializer: KSerializer<T>, defaults: T, blob: JsonObject): T? =
    runCatching {
        val defObj = json.parseToJsonElement(json.encodeToString(serializer, defaults)).jsonObject
        val merged = JsonObject(defObj + blob.filterKeys { it != "kind" })
        json.decodeFromString(serializer, json.encodeToString(JsonObject.serializer(), merged))
    }.getOrNull()
