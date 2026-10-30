package coffee.crema.ui

import coffee.crema.core.Bean
import coffee.crema.core.CommonSettings
import coffee.crema.core.MaintenanceState
import coffee.crema.core.Roaster
import coffee.crema.core.VisualizerSyncPrefs
import coffee.crema.core.exportBackupJsonl
import coffee.crema.core.importBackupJsonl
import coffee.crema.history.StoredShot
import coffee.crema.maintenance.defaultMaintenanceState
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.toCommonSettings
import coffee.crema.visualizer.DEFAULT_VISUALIZER_SYNC_PREFS
import coffee.crema.visualizer.storedShotFromBackupJson
import coffee.crema.visualizer.wireShotJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/*
 * The whole-app `crema-backup/v1` bundle, both directions. The core owns the
 * format: [backupBundleJsonl] hands it an envelope of this shell's records
 * (`exportBackupJsonl`) and [parseBackup] reads a bundle back through the same
 * parser web uses (`importBackupJsonl`), so a backup from either shell restores
 * on the other. What stays here is the Android side of the mapping: the
 * envelope's shapes on the way out, the store types and the settings / blob
 * defaults on the way in. The wipe/merge apply lives in [LibraryController].
 */

/**
 * Everything the backup reads from controller and VM state, captured on the
 * caller's (Main) thread so the serialisation, the core export and the zip
 * can run on [Dispatchers.Default] without touching live mutable state
 * (cohort review 2026-10-25, Android P1-2: a few hundred shots with their
 * full sample series froze the UI thread, daily at launch for Drive users).
 */
internal class BackupSnapshot(
    val customs: List<String>,
    val state: MainUiState,
    val pinned: Set<String>,
    val hidden: Set<String>,
    val prefs: AppPrefs,
    val visualizerPrefs: VisualizerSyncPrefs,
    val userRecipes: List<coffee.crema.core.BrewRecipe>,
)

/** Build the whole-app backup bundle as `crema-backup/v1` JSONL from [snap].
 *  Heavy (every shot's sample series + the core export); reads only [snap],
 *  so it is safe off the main thread. */
internal fun backupBundleJsonl(
    snap: BackupSnapshot,
    json: Json,
    createdAtMs: Long,
    appVersion: String,
    deviceLabel: String,
    export: (envelope: String, createdAtMs: Long, appVersion: String, deviceLabel: String) -> String = ::exportBackupJsonl,
): String {
    val customs = snap.customs
    val s = snap.state
    val pinnedIds = snap.pinned
    val hiddenIds = snap.hidden
    val userRecipes = snap.userRecipes
    // Portable settings only — drop per-device identity (BLE addresses, LAN
    // proxy, paired devices); they're meaningless on another device.
    val portable = snap.prefs.copy(
        de1Address = null,
        scaleAddress = null,
        proxyRole = "normal",
        proxyPrimaryHost = "",
        proxyPrimaryPort = 0,
        replayPrimary = false,
        pairedDevices = emptyList(),
    )
    val beansJson = runCatching { json.encodeToString(ListSerializer(Bean.serializer()), s.beans) }.getOrElse { "[]" }
    val roastersJson = runCatching { json.encodeToString(ListSerializer(Roaster.serializer()), s.roasters) }.getOrElse { "[]" }
    // Shots ride in the canonical core `StoredShot` wire shape (the SAME shape
    // the Visualizer path emits via `export_v2_json_shot`), NOT Android's flat
    // store shape: the core backup exporter parses `shots:[StoredShot]`, so the
    // flat shape fails the whole envelope's deserialization → the entire backup
    // aborts once any shot exists (issue 01). `wireShotJson` emits
    // formatVersion/completedAt/record; restore inverts via storedShotFromBackupJson.
    val shotsJson = runCatching {
        JsonArray(s.history.map { wireShotJson(it, forBackup = true) }).toString()
    }.getOrElse { "[]" }
    // Settings line: the shared cross-shell CommonSettings `common` block (both
    // shells emit it identically, so common prefs restore web<->Android) + this
    // shell's `_shell` tag + Android-only `qcGrind` (re-applied only on Android).
    val settingsJson = runCatching {
        val common = json.parseToJsonElement(json.encodeToString(CommonSettings.serializer(), portable.toCommonSettings()))
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("common", common)
                put("_shell", "android")
                portable.qcGrind?.let { put("qcGrind", it) }
            },
        )
    }.getOrElse { "{\"_shell\":\"android\"}" }
    // Profile organisation — pinned + hidden BUILT-IN ids (cross-shell; custom
    // pins ride inside the profile JSON). Maintenance — a shared core type,
    // fully portable. Visualizer SYNC prefs — shell-tagged, token-free.
    val profileMetaJson = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                "pinned" to JsonArray(pinnedIds.map { JsonPrimitive(it) }),
                "hiddenBuiltins" to JsonArray(hiddenIds.map { JsonPrimitive(it) }),
            ),
        ),
    )
    val recipesJson = runCatching {
        json.encodeToString(ListSerializer(coffee.crema.core.BrewRecipe.serializer()), userRecipes)
    }.getOrElse { "[]" }
    // Recipe organisation — default pointers (may name built-ins) + hidden
    // built-in ids, cross-shell like profileMeta.
    val recipeMetaJson = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                "defaults" to JsonObject(s.lastRecipeByMethod.mapValues { JsonPrimitive(it.value) }),
                "hiddenBuiltins" to JsonArray(s.hiddenBuiltinRecipes.map { JsonPrimitive(it) }),
            ),
        ),
    )
    val customMethodsJson = runCatching {
        json.encodeToString(ListSerializer(coffee.crema.core.CustomBrewMethod.serializer()), s.customMethods)
    }.getOrElse { "[]" }
    val maintenanceJson = runCatching { json.encodeToString(MaintenanceState.serializer(), s.maintenance) }.getOrElse { "{}" }
    val visualizerPrefsJson = runCatching {
        json.encodeToString(VisualizerSyncPrefs.serializer(), snap.visualizerPrefs)
    }.getOrElse { "{}" }
    // Assemble the envelope as a JsonObject rather than raw string concat so a
    // dropped brace / comma can't silently corrupt it (issue 06 F5). Each piece
    // is already a serialized JSON string; parse it back into the tree. The core
    // re-serialises the envelope when it emits the JSONL, so formatting here is
    // immaterial — this only hardens assembly.
    val envelope = buildJsonObject {
        put("profiles", JsonArray(customs.map { json.parseToJsonElement(it) }))
        put("beans", json.parseToJsonElement(beansJson))
        put("roasters", json.parseToJsonElement(roastersJson))
        put("shots", json.parseToJsonElement(shotsJson))
        put("settings", json.parseToJsonElement(settingsJson))
        put("profileMeta", json.parseToJsonElement(profileMetaJson))
        put("recipes", json.parseToJsonElement(recipesJson))
        put("recipeMeta", json.parseToJsonElement(recipeMetaJson))
        // The user's own brewing methods, tombstones included (their old
        // brews still resolve) → `kind:"brewMethod"` lines.
        put("customMethods", json.parseToJsonElement(customMethodsJson))
        put("maintenance", json.parseToJsonElement(maintenanceJson))
        put("visualizerPrefs", json.parseToJsonElement(visualizerPrefsJson))
    }.toString()
    return export(envelope, createdAtMs, appVersion, deviceLabel)
}

/**
 * A `crema-backup/v1` bundle's records mapped onto the Android stores' types,
 * before they're applied. Pure data, so [parseBackup] is testable without the
 * ViewModel.
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
    /** The user's brew recipes (the core drops built-in ids). */
    val recipes: List<coffee.crema.core.BrewRecipe> = emptyList(),
    /** Recipe organisation: `{ defaults:{method:id}, hiddenBuiltins:[ids] }`. */
    val recipeMeta: JsonObject? = null,
    /** The user's own brewing methods, tombstones included (the core accepts
     *  only the `custom:` namespace). */
    val customMethods: List<coffee.crema.core.CustomBrewMethod> = emptyList(),
)

/**
 * Parse a `crema-backup/v1` bundle through the core's `importBackupJsonl` (the
 * parser web restores with) and map its plan onto the Android types, or null
 * when the text has no backup header. [nowMs] seeds the maintenance default for
 * any field an older bundle omits. Shots arrive in the core `StoredShot` shape
 * and invert through [storedShotFromBackupJson]; a record this shell can't
 * decode is skipped, never thrown. [import] is injectable for tests.
 */
fun parseBackup(
    text: String,
    json: Json,
    nowMs: Long,
    import: (String) -> String = ::importBackupJsonl,
): ParsedBackup? {
    val plan = json.parseToJsonElement(import(text)).jsonObject
    if ((plan["hasHeader"] as? JsonPrimitive)?.booleanOrNull != true) return null
    fun array(key: String): List<JsonObject> = (plan[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    fun obj(key: String): JsonObject? = plan[key] as? JsonObject
    fun <T> typed(key: String, serializer: KSerializer<T>): List<T> =
        array(key).mapNotNull { runCatching { json.decodeFromJsonElement(serializer, it) }.getOrNull() }

    var prefs: AppPrefs? = null
    var common: CommonSettings? = null
    var qcGrind: Float? = null
    obj("settings")?.let { settings ->
        val shell = (settings["_shell"] as? JsonPrimitive)?.content
        val commonObj = settings["common"] as? JsonObject
        if (commonObj != null) {
            // Unified format: `common` is the cross-shell CommonSettings (apply
            // from any shell); `qcGrind` is Android-only.
            common = decodeFilled(json, CommonSettings.serializer(), AppPrefs().toCommonSettings(), commonObj)
            if (shell == null || shell == "android") qcGrind = (settings["qcGrind"] as? JsonPrimitive)?.floatOrNull
        } else if (shell == null || shell == "android") {
            // Pre-unification Android backup — a flat AppPrefs settings line.
            prefs = runCatching { json.decodeFromJsonElement(AppPrefs.serializer(), settings) }.getOrNull()
        }
    }
    return ParsedBackup(
        profiles = array("profiles").map { json.encodeToString(JsonObject.serializer(), it) },
        beans = typed("beans", Bean.serializer()),
        roasters = typed("roasters", Roaster.serializer()),
        shots = array("shots").mapNotNull { storedShotFromBackupJson(it, json) },
        prefs = prefs,
        common = common,
        qcGrind = qcGrind,
        profileMeta = obj("profileMeta"),
        maintenance = obj("maintenance")?.let { decodeFilled(json, MaintenanceState.serializer(), defaultMaintenanceState(nowMs), it) },
        visualizerPrefs = obj("visualizerPrefs")?.let { decodeFilled(json, VisualizerSyncPrefs.serializer(), DEFAULT_VISUALIZER_SYNC_PREFS, it) },
        recipes = typed("recipes", coffee.crema.core.BrewRecipe.serializer()),
        recipeMeta = obj("recipeMeta"),
        customMethods = typed("customMethods", coffee.crema.core.CustomBrewMethod.serializer()),
    )
}

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
