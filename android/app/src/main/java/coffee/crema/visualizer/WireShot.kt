package coffee.crema.visualizer

import coffee.crema.history.StoredShot
import coffee.crema.history.effectiveGrindSetting
import coffee.crema.history.beanLabel
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Flat Android `StoredShot` → the Rust wire `de1_domain::StoredShot` JSON —
 * the shape `export_v2_json_shot` (FFI) consumes and the web shell persists
 * natively (`formatVersion: 3`, nested `record.samples` of `TimedSample`).
 *
 * Android's history store still carries its own flat shape (the tracked
 * StoredShot-migration debt); this assembler is the explicit bridge so the
 * Visualizer payload comes out of the SAME core exporter as the web's —
 * identical wire bytes, no shell-side v2 mirror. When the store migrates to
 * the wire shape, this file deletes.
 */

/** The shot's canonical final weight for de-dup matching (web `shotFinalWeight`):
 *  final sample weight, then peak sample weight, then the journal yield. */
fun shotFinalWeight(shot: StoredShot): Float? {
    val weights = shot.samples.mapNotNull { it.weight }
    return weights.lastOrNull() ?: weights.maxOrNull() ?: shot.yieldG
}

/** Assemble the Rust wire `StoredShot` JSON for [shot]. [grinderModel] is the
 *  equipment-level settings cascade value (web `resolveGrinderModel`).
 *
 *  [forBackup] preserves the locally-held identity fields the Visualizer UPLOAD
 *  path intentionally nulls (the server assigns `visualizerId`; the upload omits
 *  the per-shot grind) so a whole-app backup round-trips them (issue 01). Leave
 *  it false for the upload path. */
fun wireShotJson(shot: StoredShot, grinderModel: String? = null, forBackup: Boolean = false): JsonObject {
    return buildJsonObject {
        put("formatVersion", 3)
        put("id", shot.id)
        put("completedAt", shot.completedAtMs)
        // `put(key, String?)` emits JsonNull for null; the old
        // `?.let { put } ?: put(JsonNull)` form silently overwrote a set value
        // with null (put returns the previous value, so the elvis always fired).
        put("profileName", shot.profileName)
        // Embed the recipe (#12) so the uploaded shot round-trips through Visualizer
        // as a real, importable profile — not an empty stub. Absent for shots
        // captured before this field existed (older records ride without it).
        shot.profile?.let { put("profile", it) }
        put(
            "metadata",
            buildJsonObject {
                put("dose", shot.doseG)
                put("yieldOut", shot.yieldG)
                put("beans", shot.beanLabel)
                // Backup keeps the RAW per-shot dial only (materialising the bean
                // fallback would turn it into a per-shot value on restore); the
                // upload sends the effective grind — recorded/edited value, else
                // the bean's reference — the exact string History displays, so
                // the v2 export emits the grinder block Visualizer shows
                // (issue #16: grind edits must reach the uploaded copy).
                if (forBackup) {
                    put("grinderSetting", shot.grindSetting?.toString())
                } else {
                    put("grinderSetting", shot.effectiveGrindSetting)
                }
                put("notes", shot.notes)
                put("rating", shot.rating ?: 0)
                put("tds", JsonNull)
                put("extractionYield", JsonNull)
            },
        )
        put(
            "record",
            buildJsonObject {
                put("duration", shot.durationMs)
                put(
                    "samples",
                    buildJsonArray {
                        shot.samples.forEach { s ->
                            add(
                                buildJsonObject {
                                    put("elapsed", s.elapsedMs)
                                    put(
                                        "sample",
                                        buildJsonObject {
                                            put("sampleTime", 0)
                                            put("groupPressure", s.pressure)
                                            put("groupFlow", s.flow)
                                            put("headTemp", s.headTemp)
                                            put("mixTemp", s.mixTemp)
                                            put("setHeadTemp", s.setHeadTemp)
                                            put("setMixTemp", s.setHeadTemp)
                                            put("setGroupPressure", s.setGroupPressure)
                                            put("setGroupFlow", s.setGroupFlow)
                                            put("frameNumber", 0)
                                            put("steamTemp", 0)
                                        },
                                    )
                                    // Overlay channels ride only when known —
                                    // the shell treats 0 / absent as "no signal".
                                    s.weight?.takeIf { it != 0f }?.let { put("scaleWeight", it) }
                                    s.weightFlow?.takeIf { it != 0f }?.let { put("scaleFlowWeight", it) }
                                    s.dispensedVolume.takeIf { it != 0f }?.let { put("dispensedVolume", it) }
                                    s.resistance?.takeIf { it != 0f }?.let { put("resistance", it) }
                                    s.resistanceWeight?.takeIf { it != 0f }?.let { put("resistanceWeight", it) }
                                },
                            )
                        }
                    },
                )
            },
        )
        put(
            "bean",
            // The bean snapshot (roaster / roast date / roast level) frozen at shot
            // time — issue 06. `put(key, value?)` emits JsonNull for null; do NOT use
            // `x?.let { put } ?: put(JsonNull)` (put returns the *previous* value, so
            // the elvis always overwrites with null).
            shot.bean?.let { b ->
                buildJsonObject {
                    put("beanId", b.beanId)
                    put("name", b.name)
                    put("roasterName", b.roasterName)
                    put("roastedOn", b.roastedOn)
                    put("roastLevel", b.roastLevel?.toInt())
                    put("tags", buildJsonArray { b.tags?.forEach { add(JsonPrimitive(it)) } })
                }
            } ?: JsonNull,
        )
        put("grinderModel", grinderModel)
        put("tags", buildJsonArray {})
        put("yieldTarget", JsonNull)
        put("brewTempTarget", JsonNull)
        put("preinfuseTarget", JsonNull)
        put("stopOnWeight", false)
        put("autoTare", false)
        // Server-assigned on upload (rides as null); preserved for a backup so a
        // restore PATCHes the same Visualizer row instead of duplicating it.
        if (forBackup) put("visualizerId", shot.visualizerId) else put("visualizerId", JsonNull)
        put("deletedAt", JsonNull)
    }
}

/**
 * Materialise a pulled remote shot as a local flat [StoredShot] stub — the
 * Android mirror of web `storedShotFromWire`. `wire` is the core's `WireShot`
 * JSON (`clock` already unix MS); `samplesJson` is the core's `TimedSample[]`
 * JSON from `samples_from_visualizer_detail` (empty → metadata-only stub).
 */
fun storedShotFromWire(wire: JsonObject, samplesJson: String?, json: kotlinx.serialization.json.Json): StoredShot? {
    fun str(k: String) = wire[k]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
    fun num(k: String) = str(k)?.toDoubleOrNull()
    val vid = str("id") ?: return null
    val samples = samplesJson?.let { parseTimedSamples(it, json) } ?: emptyList()
    return StoredShot(
        id = "shot:remote:$vid",
        completedAtMs = num("clock")?.toLong() ?: 0L,
        durationMs = num("duration_ms")?.toLong() ?: 0L,
        yieldG = num("final_weight_g")?.toFloat(),
        doseG = null,
        peakPressure = samples.maxOfOrNull { it.pressure },
        peakTemp = samples.maxOfOrNull { it.headTemp },
        profileName = str("profile_title"),
        rating = num("rating")?.toInt()?.takeIf { it > 0 },
        notes = str("notes"),
        samples = coffee.crema.history.downsampleForStorage(samples),
        visualizerId = vid,
    )
}

/**
 * Inverse of [wireShotJson]: materialise a backup/core-shape `StoredShot` JSON
 * object (the shape the core backup exporter emits and the web shell persists
 * natively — `formatVersion` / `completedAt` / nested `record.samples`) back into
 * Android's flat [StoredShot], for whole-app backup RESTORE (issue 01). Distinct
 * from [storedShotFromWire], which parses the Visualizer *detail* shape
 * (`clock` / `duration_ms`), not this. Peaks are re-derived from the samples
 * (the core stores none). Returns null only when `id` is absent.
 */
fun storedShotFromBackupJson(o: JsonObject, json: kotlinx.serialization.json.Json): StoredShot? {
    fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
    fun longOf(s: String?): Long? = s?.toLongOrNull() ?: s?.toDoubleOrNull()?.toLong()
    val id = str("id") ?: return null
    val meta = o["metadata"] as? JsonObject
    fun metaStr(k: String) = (meta?.get(k) as? JsonPrimitive)?.contentOrNull
    val record = o["record"] as? JsonObject
    val durationMs = longOf((record?.get("duration") as? JsonPrimitive)?.contentOrNull) ?: 0L
    val samples = record?.get("samples")?.let { parseTimedSamples(it.toString(), json) } ?: emptyList()
    // Decode the bean snapshot with the generated serializer (its wire keys match
    // what wireShotJson emits); fall back to null rather than dropping the shot.
    val bean = (o["bean"] as? JsonObject)?.let {
        runCatching { json.decodeFromString(coffee.crema.core.ShotBean.serializer(), it.toString()) }.getOrNull()
    }
    return StoredShot(
        id = id,
        completedAtMs = longOf(str("completedAt")) ?: 0L,
        durationMs = durationMs,
        yieldG = metaStr("yieldOut")?.toFloatOrNull(),
        doseG = metaStr("dose")?.toFloatOrNull(),
        grindSetting = metaStr("grinderSetting")?.toFloatOrNull(),
        peakPressure = samples.maxOfOrNull { it.pressure },
        peakTemp = samples.maxOfOrNull { it.headTemp },
        profileName = str("profileName"),
        bean = bean,
        rating = metaStr("rating")?.toIntOrNull()?.takeIf { it > 0 },
        notes = metaStr("notes"),
        samples = coffee.crema.history.downsampleForStorage(samples),
        privacy = str("privacy"),
        visualizerId = str("visualizerId"),
    )
}

/** Parse the core's `TimedSample[]` JSON into flat [TelemetrySample]s. */
fun parseTimedSamples(samplesJson: String, json: kotlinx.serialization.json.Json): List<coffee.crema.ui.TelemetrySample> =
    runCatching {
        json.parseToJsonElement(samplesJson).let { it as kotlinx.serialization.json.JsonArray }.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun p(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toFloatOrNull()
            val sample = o["sample"] as? JsonObject ?: return@mapNotNull null
            fun sp(k: String) = (sample[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toFloatOrNull()
            coffee.crema.ui.TelemetrySample(
                elapsedMs = p("elapsed")?.toLong() ?: 0L,
                pressure = sp("groupPressure") ?: 0f,
                flow = sp("groupFlow") ?: 0f,
                headTemp = sp("headTemp") ?: 0f,
                mixTemp = sp("mixTemp") ?: 0f,
                weight = p("scaleWeight")?.takeIf { it != 0f },
                weightFlow = p("scaleFlowWeight")?.takeIf { it != 0f },
                dispensedVolume = p("dispensedVolume") ?: 0f,
                resistance = p("resistance")?.takeIf { it != 0f },
                resistanceWeight = p("resistanceWeight")?.takeIf { it != 0f },
                setHeadTemp = sp("setHeadTemp") ?: 0f,
                setGroupPressure = sp("setGroupPressure") ?: 0f,
                setGroupFlow = sp("setGroupFlow") ?: 0f,
            )
        }
    }.getOrDefault(emptyList())
