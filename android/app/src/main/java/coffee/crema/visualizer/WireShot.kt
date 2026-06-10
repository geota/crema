package coffee.crema.visualizer

import coffee.crema.history.StoredShot
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
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

/** Assemble the Rust wire `StoredShot` JSON for [shot]. */
fun wireShotJson(shot: StoredShot): JsonObject {
    val (roasterName, _, beanShort) = beanNameParts(shot.beanName)
    return buildJsonObject {
        put("formatVersion", 3)
        put("id", shot.id)
        put("completedAt", shot.completedAtMs)
        shot.profileName?.let { put("profileName", it) } ?: put("profileName", JsonNull)
        put(
            "metadata",
            buildJsonObject {
                put("dose", shot.doseG)
                put("yieldOut", shot.yieldG)
                put("beans", shot.beanName)
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
            if (shot.beanName == null) {
                JsonNull
            } else {
                buildJsonObject {
                    put("beanId", JsonNull)
                    put("name", beanShort ?: shot.beanName)
                    put("roasterName", roasterName)
                    put("roastedOn", JsonNull)
                    put("roastLevel", JsonNull)
                    put("tags", buildJsonArray {})
                }
            },
        )
        put("grinderModel", JsonNull)
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

/** Split the flat "Roaster · Bean" capture label into its parts. */
private fun beanNameParts(beanName: String?): Triple<String?, String?, String?> {
    if (beanName.isNullOrBlank()) return Triple(null, null, null)
    val idx = beanName.indexOf(" · ")
    return if (idx < 0) {
        Triple(null, null, beanName)
    } else {
        Triple(beanName.substring(0, idx), null, beanName.substring(idx + 3))
    }
}
