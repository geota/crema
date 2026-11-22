package coffee.crema.history

import android.content.Context
import coffee.crema.core.BrewSample
import coffee.crema.core.BrewSeries
import coffee.crema.core.ShotBean
import coffee.crema.core.StageMark
import coffee.crema.ui.TelemetrySample
import coffee.crema.ui.coreJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Real-shaped history rows for #310 P3a's migration tests, and an old build's
 * files: `shots.json` and its `.json` series, as a build before P3a wrote them.
 */
internal object HistoryFixtures {

    fun samples(n: Int, seed: Int, weight: Boolean = true): List<TelemetrySample> = List(n) { i ->
        val t = i * 227L
        val ramp = (i.toFloat() / n.coerceAtLeast(1))
        TelemetrySample(
            elapsedMs = t,
            pressure = 9f * ramp + (seed % 7) * 0.01f,
            flow = 2f + ramp,
            headTemp = 92.5f + (seed % 3) * 0.1f,
            mixTemp = 91.8f,
            weight = if (weight && i > 3) i * 0.21f + seed % 5 else null,
            weightFlow = if (weight && i > 3) 1.9f else null,
            dispensedVolume = i * 0.3f,
            resistance = if (i % 4 == 0) null else 0.4f,
            resistanceWeight = null,
            setHeadTemp = 93f,
            setGroupPressure = 9f,
            setGroupFlow = 0f,
            frameNumber = i / 20,
        )
    }

    fun profile(seed: Int) = JsonObject(
        mapOf(
            "title" to JsonPrimitive("Profile $seed"),
            "steps" to JsonArray(List(3) { s -> JsonObject(mapOf("name" to JsonPrimitive("step $s"), "pressure" to JsonPrimitive(6 + s))) }),
            "target_weight" to JsonPrimitive(36.0 + seed % 4),
        ),
    )

    /**
     * [count] rows, newest first, of every shape the history holds: machine
     * shots with and without a scale, a manual log, a guided brew with its
     * series, a pinned shot, an uploaded one, a pulled one, a shot with no
     * profile, an id with characters the file names encode.
     */
    fun rows(count: Int, samplesPer: Int = 120, start: Long = 1_760_000_000_000L): List<StoredShot> = List(count) { i ->
        val at = start - i * 3_600_000L
        val base = StoredShot(
            id = when (i % 50) {
                7 -> "shot:remote:$i"
                13 -> "shot:$i/odd id"
                else -> "0190a1b2-0000-7000-8000-${i.toString().padStart(12, '0')}"
            },
            completedAtMs = at,
            durationMs = 28_000L + i % 9 * 100,
            yieldG = 36f + i % 5,
            doseG = 18f,
            bagDebitG = if (i % 3 == 0) 18f else null,
            grindSetting = "${2.5 + i % 4}",
            yieldTargetG = 36f,
            peakPressure = 9.1f,
            peakTemp = 93.2f,
            peakWeightG = 37f,
            profileName = if (i % 11 == 5) null else "Profile ${i % 6}",
            profile = if (i % 11 == 5) null else profile(i % 6),
            bean = if (i % 4 == 1) null else ShotBean(beanId = "bean-${i % 8}", name = "Bean ${i % 8}", roasterName = "Roaster", roastedOn = "2026-10-01"),
            rating = if (i % 2 == 0) i % 6 else null,
            notes = if (i % 3 == 0) "Notes for $i, with “quotes” and ünïcode" else null,
            nextPlan = if (i % 10 == 0) "finer" else null,
            barista = if (i % 5 == 0) "Ana" else null,
            drinker = if (i % 7 == 0) "Bo" else null,
            grinderModel = "Niche",
            tags = if (i % 4 == 0) listOf("fruity", "light") else emptyList(),
            preinfuseTargetS = 8f,
            stopOnWeight = true,
            autoTare = true,
            stopReason = if (i % 6 == 0) "Weight" else null,
            privacy = if (i % 9 == 0) "unlisted" else null,
            visualizerId = if (i % 3 == 1) "vis-$i" else null,
            decentId = if (i % 8 == 2) "dec-$i" else null,
            visualizerUpdatedAt = if (i % 3 == 1) 1_760_000_000L else null,
            pinned = i % 97 == 3,
            machineSerial = "1234",
            machineFirmware = "1352",
            machineModel = "DE1Pro",
        )
        when (i % 23) {
            // A manual log: a method, no telemetry at all.
            4 -> base.copy(brewMethod = "pourover", profileName = null, profile = null, waterG = 250f, brewTempC = 94f)
            // A guided brew, with its weight series.
            9 -> base.copy(
                brewMethod = "v60",
                recipeName = "V60 4:6",
                profile = null,
                waterG = 300f,
                brewSeries = BrewSeries(
                    samples = List(40) { s -> BrewSample(elapsedMs = s * 500L, weightG = s * 7.5f, flowGS = if (s == 0) null else 3f) },
                    stageMarks = listOf(StageMark(elapsedMs = 0, stepIndex = 0, targetWaterG = 50f), StageMark(elapsedMs = 9_000, stepIndex = 1, targetWaterG = 120f)),
                ),
            )
            // A shot with no scale.
            15 -> base.copy(samples = samples(samplesPer, i, weight = false))
            else -> base.copy(samples = samples(samplesPer, i))
        }
    }

    /**
     * Write [rows] as a build before P3a left them: `shots.json` with the rows
     * whose ids are in [inline] carrying their samples, the others' series in
     * `shot-series/<name>.json`, and those in [legacyName] under the name
     * before F10's encoding. With [previous], the list the save before wrote
     * is `shots.json.bak`, as 0.10.0's [coffee.crema.persist.SafeFile] kept it;
     * its series files are [rows]' only, as that build pruned the rest.
     */
    fun plantOldBuild(
        context: Context,
        rows: List<StoredShot>,
        inline: Set<String> = emptySet(),
        legacyName: Set<String> = emptySet(),
        previous: List<StoredShot>? = null,
    ) {
        val dir = File(context.filesDir, "shot-series").apply { mkdirs() }
        for (row in rows) {
            if (row.samples.isEmpty() || row.id in inline) continue
            val name = if (row.id in legacyName) row.id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json" else HistoryStore.seriesFileName(row.id)
            File(dir, name).writeText(coreJson.encodeToString(ListSerializer(TelemetrySample.serializer()), row.samples))
        }
        fun listJson(list: List<StoredShot>) =
            coreJson.encodeToString(ListSerializer(StoredShot.serializer()), list.map { if (it.id in inline) it else it.copy(samples = emptyList()) })
        val file = File(context.filesDir, HistoryStore.FILE_NAME)
        if (previous != null) File(file.path + ".bak").writeText(listJson(previous))
        file.writeText(listJson(rows))
    }

    /**
     * [rows] as 0.10.0 wrote them: without the fields that build never had
     * (`bagDebitG`, `stopEstimated`, `visualizerUpdatedAt`, `pinned`), so
     * `shots.json` holds none of their keys.
     */
    fun as0100(rows: List<StoredShot>): List<StoredShot> =
        rows.map { it.copy(bagDebitG = null, stopEstimated = false, visualizerUpdatedAt = null, pinned = false) }

    /** The keys [as0100] leaves out: none is in a `shots.json` 0.10.0 wrote. */
    val NOT_IN_0100 = listOf("bagDebitG", "stopEstimated", "visualizerUpdatedAt", "pinned")

    /**
     * What a build before P3a does on a save (`HistoryStore.save` then): rewrite
     * `shots.json` with [rows], write each new series as `.json`, and delete the
     * `.json` series of rows its list doesn't name.
     */
    fun oldBuildSave(context: Context, rows: List<StoredShot>) {
        val dir = File(context.filesDir, "shot-series").apply { mkdirs() }
        val named = rows.mapTo(HashSet()) { HistoryStore.seriesFileName(it.id) }
        for (row in rows) {
            if (row.samples.isEmpty()) continue
            File(dir, HistoryStore.seriesFileName(row.id)).writeText(coreJson.encodeToString(ListSerializer(TelemetrySample.serializer()), row.samples))
        }
        dir.listFiles()?.filter { it.name.endsWith(".json") && it.name !in named }?.forEach { it.delete() }
        File(context.filesDir, HistoryStore.FILE_NAME)
            .writeText(coreJson.encodeToString(ListSerializer(StoredShot.serializer()), rows.map { it.copy(samples = emptyList()) }))
    }

    /** Every file under [dir] by relative path, with its bytes. */
    fun filesUnder(dir: File): Map<String, ByteArray> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readBytes() }
}
