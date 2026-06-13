package coffee.crema.visualizer

import coffee.crema.history.StoredShot
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
 *  equipment-level settings cascade value (web `resolveGrinderModel`). */
fun wireShotJson(shot: StoredShot, grinderModel: String? = null): JsonObject {
    return buildJsonObject {
        put("formatVersion", 3)
        put("id", shot.id)
        put("completedAt", shot.completedAtMs)
        // `put(key, String?)` emits JsonNull for null; the old
        // `?.let { put } ?: put(JsonNull)` form silently overwrote a set value
        // with null (put returns the previous value, so the elvis always fired).
        put("profileName", shot.profileName)
        put(
            "metadata",
            buildJsonObject {
                put("dose", shot.doseG)
                put("yieldOut", shot.yieldG)
                put("beans", shot.beanLabel)
                put("grinderSetting", JsonNull)
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
        put("visualizerId", JsonNull)
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
