package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import coffee.crema.beans.BeanImageStore
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.LibraryStore
import coffee.crema.brew.RecipeFileStore
import coffee.crema.core.Bean
import coffee.crema.core.BrewRecipe
import coffee.crema.core.CustomBrewMethod
import coffee.crema.core.LocalState
import coffee.crema.core.MaintenanceState
import coffee.crema.core.PlanAction
import coffee.crema.core.RestorePlan
import coffee.crema.core.Roaster
import coffee.crema.core.StorePlan
import coffee.crema.core.Verdict
import coffee.crema.core.VisualizerSyncPrefs
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.maintenance.MaintenanceStore
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.Stores
import coffee.crema.profiles.CustomProfileStore
import coffee.crema.profiles.HiddenProfileStore
import coffee.crema.profiles.PinnedProfileStore
import coffee.crema.restore.RestoreKind
import coffee.crema.restore.RestoreOutcome
import coffee.crema.restore.RestoreRecords
import coffee.crema.restore.RestoreState
import coffee.crema.restore.prepareBackup
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.SettingsStore
import coffee.crema.visualizer.DEFAULT_VISUALIZER_SYNC_PREFS
import coffee.crema.visualizer.ShotTombstone
import coffee.crema.visualizer.VisualizerState
import coffee.crema.visualizer.VisualizerStore
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The shared restore matrix (`core/de1-domain/tests/fixtures/restore/matrix.json`,
 * restore-safety design, "Tests: Shared matrix") against the real ViewModel and
 * stores, through the restore engine. Each case seeds its local state into the
 * stores, builds its backup from the Android fixture's rows, checks the plan's
 * `expect` pointers as `tests/backup_plan.rs` does, runs the plan, then reads
 * every store back through a fresh ViewModel over the same files.
 *
 * Where Android's state differs from the matrix's terms:
 * - History has a fixed cap ([HistoryStore.MAX_SHOTS]). A case's
 *   `historyCapacity` C is reached with `MAX_SHOTS - C` filler rows: local
 *   rows for Merge, unpinned backup rows for Replace, whose over-capacity
 *   count moves by the same number.
 * - A store's health is named as Android names it: the matrix's "bean library" is "beans".
 * - A same-shell settings case written for web runs as android on both sides.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RestoreMatrixTest(private val name: String) {

    companion object {
        private val fixtures = File("../../core/de1-domain/tests/fixtures")
        private val plain = Json

        private val cases: Map<String, JsonObject> by lazy {
            plain.parseToJsonElement(File(fixtures, "restore/matrix.json").readText()).jsonObject["cases"]!!.jsonArray
                .map { it.jsonObject }
                .associateBy { it["name"]!!.jsonPrimitive.content }
        }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun names(): List<Array<Any>> = cases.keys.map { arrayOf<Any>(it) }

        /** Cases Android can't build, with why. */
        private val SKIPPED = mapOf(
            "settings: a line with no _shell doesn't apply web extras" to
                "web only: a line with no _shell is Android's own, so on Android it applies the extras",
        )

        /** Pointers Android can't reproduce in a case it otherwise runs, with why: none, now shots pin (#311). */
        private val DROPPED = emptyMap<String, Map<String, String>>()

        /** The matrix names stores as web does; Android's health list names them its own way. */
        private val ANDROID_STORE_NAMES = mapOf("bean library" to "beans")

        /** The Android fixture's first row of each kind. */
        private val templates: Map<String, JsonObject> by lazy {
            val out = LinkedHashMap<String, JsonObject>()
            File(fixtures, "backup/android-backup.crema.jsonl").readLines().filter { it.isNotBlank() }.forEach { line ->
                val o = plain.parseToJsonElement(line).jsonObject
                out.putIfAbsent(o["kind"]!!.jsonPrimitive.content, o)
            }
            out
        }
    }

    private var store = ViewModelStore()
    private val app get() = VmHarness.app()
    private val json = coreJson

    @Before fun reset() {
        SafeFile.resetForTest()
        Stores.resetForTest()
        SaveFloor.resetForTest()
    }

    @After fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────

    private operator fun JsonObject.plus(pair: Pair<String, JsonElement>) = JsonObject(toMap() + pair)
    private operator fun JsonObject.minus(key: String) = JsonObject(toMap() - key)
    private fun str(v: String) = JsonPrimitive(v)

    private fun JsonObject.ids(key: String): List<String> =
        (this[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.map { it.jsonObject } ?: emptyList()

    private fun JsonElement?.isTrue() = (this as? JsonPrimitive)?.booleanOrNull == true

    /** RFC 6901, as serde_json's `pointer`. */
    private fun JsonElement.at(pointer: String): JsonElement? =
        pointer.split("/").drop(1).fold(this as JsonElement?) { node, raw ->
            val token = raw.replace("~1", "/").replace("~0", "~")
            when (node) {
                is JsonObject -> node[token]
                is JsonArray -> token.toIntOrNull()?.let { node.getOrNull(it) }
                else -> null
            }
        }

    /** Equal as JSON, numbers by value: `90.0` and `90` are one setting. */
    private fun sameJson(a: JsonElement?, b: JsonElement?): Boolean = when {
        a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString &&
            a.doubleOrNull != null && b.doubleOrNull != null -> a.doubleOrNull == b.doubleOrNull
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { sameJson(a[it], b[it]) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { sameJson(a[it], b[it]) }
        else -> a == b
    }

    // ── The case on Android ──────────────────────────────────────────────────

    private class AndroidCase(
        val local: JsonObject,
        val backup: JsonObject,
        val mode: RestoreKind,
        val revive: Boolean,
        val expect: Map<String, JsonElement>,
        /** Local filler rows, to reach a Merge case's cap. */
        val localFillers: Int,
        /** Backup filler rows, to reach a Replace case's cap. */
        val backupFillers: Int,
    )

    private fun androidCase(case: JsonObject): AndroidCase {
        var local = case["local"]!!.jsonObject
        var backup = case["backup"]!!.jsonObject
        val mode = if (case["mode"]!!.jsonPrimitive.content == "replace") RestoreKind.REPLACE else RestoreKind.MERGE
        // A same-shell settings case written for web is the same case on this shell.
        val backupShell = (backup["settings"] as? JsonObject)?.get("_shell")?.jsonPrimitive?.content
        if (local["shell"]?.jsonPrimitive?.content == "web" && backupShell == "web") {
            local = local + ("shell" to str("android"))
            backup = backup + ("settings" to (backup["settings"]!!.jsonObject + ("_shell" to str("android"))))
        }
        val shift = local["historyCapacity"]?.jsonPrimitive?.content?.toInt()?.let { HistoryStore.MAX_SHOTS - it } ?: 0
        val dropped = DROPPED[name].orEmpty()
        val expect = case["expect"]!!.jsonObject.filterKeys { it !in dropped }.mapValues { (pointer, want) ->
            when {
                Regex("^/refusals/\\d+/store$").matches(pointer) ->
                    str(ANDROID_STORE_NAMES[want.jsonPrimitive.content] ?: want.jsonPrimitive.content)
                // Replace's over-capacity count is the backup's unpinned rows, fillers included.
                mode == RestoreKind.REPLACE && Regex("^/refusals/\\d+/count$").matches(pointer) && shift > 0 ->
                    JsonPrimitive(want.jsonPrimitive.content.toInt() + shift)
                else -> want
            }
        }
        return AndroidCase(
            local = local,
            backup = backup,
            mode = mode,
            revive = (case["opts"] as? JsonObject)?.get("reviveDeleted").isTrue(),
            expect = expect,
            localFillers = if (mode == RestoreKind.MERGE) shift else 0,
            backupFillers = if (mode == RestoreKind.REPLACE) shift else 0,
        )
    }

    // ── The backup, as `backup_of` in tests/backup_plan.rs, from Android's rows ──

    private fun backupBytes(c: AndroidCase): ByteArray {
        val spec = c.backup
        val t = templates
        val shots = spec.objects("shots") + List(c.backupFillers) { JsonObject(mapOf("id" to str("backup-filler-$it"))) }
        var header = t.getValue("crema-backup/v1")
        for ((key, n) in listOf(
            "beanCount" to spec.ids("beans").size,
            "roasterCount" to spec.ids("roasters").size,
            "shotCount" to shots.size,
            "profileCount" to spec.ids("profiles").size,
            "recipeCount" to spec.ids("recipes").size,
            "customMethodCount" to spec.ids("customMethods").size,
        )) header = header + (key to JsonPrimitive(n))
        for (key in spec.ids("omitCounts")) header = header - key
        val lines = mutableListOf(header)
        (spec["settings"] as? JsonObject)?.let { s ->
            val line = t.getValue("settings")
            lines += s["_shell"]?.let { line + ("_shell" to it) } ?: (line - "_shell")
        }
        for (key in listOf("profileMeta", "recipeMeta")) {
            (spec[key] as? JsonObject)?.let { lines += it + ("kind" to str(key)) }
        }
        for (key in listOf("maintenance", "visualizerPrefs")) {
            if (spec[key].isTrue()) lines += t.getValue(key)
        }
        fun row(kind: String, id: String) = t.getValue(kind) + ("id" to str(id))
        lines += spec.ids("roasters").map { row("roaster", it) }
        lines += spec.ids("beans").map { row("bean", it) }
        for (shot in shots) {
            var line = row("shot", shot["id"]!!.jsonPrimitive.content)
            line = when (val bean = (shot["bean"] as? JsonPrimitive)?.content) {
                null -> line - "bean"
                else -> line + ("bean" to (line["bean"]!!.jsonObject + ("beanId" to str(bean))))
            }
            if (!shot["samples"].isTrue()) line = line + ("record" to (line["record"]!!.jsonObject + ("samples" to JsonArray(emptyList()))))
            line = line + ("completedAt" to (shot["completedAt"] ?: JsonPrimitive(1)))
            if (shot["pinned"].isTrue()) line = line + ("pinned" to JsonPrimitive(true))
            lines += line
        }
        lines += spec.ids("customMethods").map { row("brewMethod", it) }
        lines += spec.ids("recipes").map { row("recipe", it) }
        lines += spec.ids("profiles").map { row("profile", it) }
        val jsonl = lines.joinToString("") { plain.encodeToString(JsonObject.serializer(), it) + "\n" }.toByteArray()
        val photos = spec.ids("photos")
        if (photos.isEmpty()) return jsonl
        // Photos come in a zip: `backup.jsonl` and `images/<beanId>`.
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("backup.jsonl")); zip.write(jsonl); zip.closeEntry()
            for (id in photos) {
                zip.putNextEntry(ZipEntry("images/$id")); zip.write(photoOf("backup", id)); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun photoOf(side: String, id: String) = "$side:$id".toByteArray()

    /** `bean-images/`, by file name, with each file's text. */
    private fun photoFiles(): Map<String, String> =
        BeanImageStore.beanImageFile(app, "x").parentFile!!.listFiles().orEmpty()
            .filter { it.isFile }.associate { it.name to it.readText() }

    // ── The local state, seeded into the real stores ─────────────────────────

    private fun <T> decodeRow(kind: String, id: String, serializer: kotlinx.serialization.KSerializer<T>): T =
        json.decodeFromJsonElement(serializer, templates.getValue(kind) - "kind" + ("id" to str(id)))

    private fun seed(c: AndroidCase) {
        val local = c.local
        SafeFile.openAllForTest()
        val libraryFile = File(app.filesDir, LibraryStore.FILE_NAME)
        val health = local.objects("health").associate { it["store"]!!.jsonPrimitive.content to it["health"]!!.jsonPrimitive.content }
        assertTrue(health.keys.all { it == "bean library" }, "only the bean library's health is seeded here: $health")
        runBlocking {
            val missing = local.ids("beansMissingPhoto").toSet()
            val beans = local.ids("beans").map { id ->
                decodeRow("bean", id, Bean.serializer()).let { if (id in missing) it.copy(imageRef = "photo:$id") else it }
            }
            val roasters = local.ids("roasters").map { decodeRow("roaster", it, Roaster.serializer()) }
            val active = (local["activeBeanId"] as? JsonPrimitive)?.content
            when (health["bean library"]) {
                "damaged" -> libraryFile.writeText("{ not json")
                // A directory can't be read as a file: the load is Unavailable, not Corrupt.
                "unavailable" -> libraryFile.mkdirs()
                null -> if (beans.isNotEmpty() || roasters.isNotEmpty() || active != null) {
                    assertTrue(LibraryStore(app, json).save(BeanLibrary(beans, roasters, active)))
                }
                else -> fail("health ${health["bean library"]} isn't seeded")
            }
            for (id in local.ids("photos")) BeanImageStore.put(app, id, photoOf("here", id))

            val shots = local.objects("shots")
            val live = shots.filterNot { it["deleted"].isTrue() }.mapIndexed { n, s ->
                StoredShot(
                    id = s["id"]!!.jsonPrimitive.content,
                    completedAtMs = 1_700_000_100_000L + n,
                    durationMs = 30_000,
                    samples = if (s["hasSamples"].isTrue()) curve() else emptyList(),
                    pinned = s["pinned"].isTrue(),
                )
            }
            val fillers = List(c.localFillers) { n -> StoredShot(id = "filler-$n", completedAtMs = 1_600_000_000_000L + n, durationMs = 30_000) }
            if (live.isNotEmpty() || fillers.isNotEmpty()) assertTrue(HistoryStore(app, json).save(live + fillers))

            // A shot deleted here: no row, and a tombstone owed to Visualizer.
            val tombstones = shots.filter { it["deleted"].isTrue() }.map {
                val id = it["id"]!!.jsonPrimitive.content
                ShotTombstone(id = id, visualizerId = "v-$id", deletedAt = 1)
            }
            val prefs = (local["visualizerPrefs"] as? JsonObject)?.let { overlay ->
                val base = json.encodeToJsonElement(VisualizerSyncPrefs.serializer(), DEFAULT_VISUALIZER_SYNC_PREFS).jsonObject
                json.decodeFromJsonElement(VisualizerSyncPrefs.serializer(), JsonObject(base + overlay))
            }
            if (tombstones.isNotEmpty() || prefs != null) {
                assertTrue(VisualizerStore(app, json).save(VisualizerState(prefs = prefs ?: DEFAULT_VISUALIZER_SYNC_PREFS, shotTombstones = tombstones)))
            }

            val profiles = local.ids("profiles").map {
                plain.encodeToString(JsonObject.serializer(), templates.getValue("profile") - "kind" + ("id" to str(it)))
            }
            if (profiles.isNotEmpty()) assertTrue(CustomProfileStore(app, json).save(profiles))
            (local["profileMeta"] as? JsonObject)?.let { meta ->
                assertTrue(PinnedProfileStore(app, json).save(meta.ids("pinned").toSet()))
                assertTrue(HiddenProfileStore(app, json).save(meta.ids("hiddenBuiltins").toSet()))
            }

            val recipeMeta = local["recipeMeta"] as? JsonObject
            val recipes = local.ids("recipes").map { decodeRow("recipe", it, BrewRecipe.serializer()) }
            val methods = local.ids("customMethods").map { decodeRow("brewMethod", it, CustomBrewMethod.serializer()) }
            if (recipes.isNotEmpty() || methods.isNotEmpty() || recipeMeta != null) {
                val defaults = (recipeMeta?.get("defaults") as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
                assertTrue(
                    RecipeFileStore(app, json).save(
                        RecipeFileStore.Envelope(recipes, defaults, recipeMeta?.ids("hiddenBuiltins").orEmpty().toSet(), methods),
                    ),
                )
            }

            (local["common"] as? JsonObject)?.let { common ->
                val base = json.encodeToJsonElement(AppPrefs.serializer(), AppPrefs()).jsonObject
                assertTrue(SettingsStore(app, json).save(json.decodeFromJsonElement(AppPrefs.serializer(), JsonObject(base + common))))
            }
            local["totalLitres"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }?.let { litres ->
                val line = templates.getValue("maintenance") - "kind" + ("totalLitres" to JsonPrimitive(litres)) + ("cleanAtMs" to JsonPrimitive(5))
                assertTrue(MaintenanceStore(app, json).save(json.decodeFromJsonElement(MaintenanceState.serializer(), line)))
            }
        }
    }

    private fun curve() = List(3) { n ->
        TelemetrySample(
            elapsedMs = n * 222L, pressure = 9f, flow = 2f, headTemp = 93f, mixTemp = 92f, weight = null, weightFlow = null,
            dispensedVolume = 0f, resistance = null, resistanceWeight = null, setHeadTemp = 93f, setGroupPressure = 9f,
            setGroupFlow = 0f, frameNumber = 1,
        )
    }

    // ── The ViewModel ────────────────────────────────────────────────────────

    private fun launch(): MainViewModel = VmHarness.build(store).also {
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(it)
    }

    private fun relaunch(): MainViewModel {
        store.clear()
        VmHarness.idle()
        store = ViewModelStore()
        return launch()
    }

    /** [block] on the VM's own scope, as the screens run a restore, with the main looper turning. */
    private fun <T> onMain(vm: MainViewModel, what: String, block: suspend () -> T): T {
        val job = vm.viewModelScope.async { block() }
        VmHarness.idleUntil(what, 60_000) { job.isCompleted }
        return runBlocking { job.await() }
    }

    /** What the device holds, as the restore sees it: the same projection the plan is made from. */
    private fun localState(vm: MainViewModel): LocalState = onMain(vm, "the local state") {
        VmHarness.library(vm).restoreSink(
            LibraryController.RestoreDeps(
                machineBusy = { false },
                totalLitres = { vm.ui.value.maintenance.totalLitres },
                restoreRecipes = { _, _, _, _ -> error("read only") },
                restoreSettings = { _: AppPrefs -> error("read only") },
                restoreMaintenance = { _: MaintenanceState -> error("read only") },
            ),
        ).localState()
    }

    private fun startShot(vm: MainViewModel) {
        VmHarness.deliverCoreOutput(vm, """{"events":[{"type":"ShotStarted"}],"commands":[]}""")
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
        assertTrue(vm.ui.value.shotInProgress, "a shot runs")
    }

    // ── The case ─────────────────────────────────────────────────────────────

    @Test
    fun case() {
        SKIPPED[name]?.let { assumeTrue("skipped on Android: $it", false) }
        val c = androidCase(cases.getValue(name))
        seed(c)
        val vm = launch()
        if (c.local["machineBusy"].isTrue()) startShot(vm)
        val before = localState(vm)
        val maintenanceBefore = vm.ui.value.maintenance
        val photosBefore = photoFiles()

        // Plan, and the case's pointers.
        val bytes = backupBytes(c)
        val prepared = prepareBackup(bytes, "matrix", File(app.cacheDir, "restore-photos"))
        val records = RestoreRecords.read(prepared, json, System.currentTimeMillis()) ?: fail("$name: no backup header")
        val plan = onMain(vm, "the plan") { vm.restoreEngine.plan(prepared, c.mode, c.revive) }
        val encoded = plain.parseToJsonElement(json.encodeToString(RestorePlan.serializer(), plan))
        val failures = c.expect.mapNotNull { (pointer, want) ->
            val got = encoded.at(pointer)
            if (got == want) null else "$pointer is $got, want $want"
        }
        assertTrue(failures.isEmpty(), "$name:\n" + failures.joinToString("\n") + "\nplan: $encoded")

        // Run what was planned.
        val ran = plan.verdict != Verdict.Refused
        if (ran) {
            val outcome = onMain(vm, "the restore") { vm.restoreEngine.run(prepared, c.mode, records, c.revive, expected = plan) }
            assertTrue(outcome is RestoreOutcome.Done, "$name: $outcome")
            VmHarness.idleUntil("the restore to finish") { vm.restoreState() == RestoreState.IDLE }
        }

        // Read every store back through a fresh ViewModel over the same files.
        val vm2 = relaunch()
        val after = localState(vm2)
        if (!ran) {
            fun LocalState.stores() = copy(health = emptyList(), machineBusy = false)
            assertEquals(before.stores(), after.stores(), "$name: a refused restore changes nothing")
            // The zip's photos were read before the verdict: none reached the photo store.
            assertEquals(photosBefore, photoFiles(), "$name: a refused restore's photos stay out of bean-images")
            return
        }
        readBack(c, plan, records, before, after, vm2, maintenanceBefore)
    }

    private fun readBack(
        c: AndroidCase,
        plan: RestorePlan,
        records: RestoreRecords,
        before: LocalState,
        after: LocalState,
        vm: MainViewModel,
        maintenanceBefore: MaintenanceState,
    ) {
        fun rows(store: String, here: List<String>, now: List<String>, p: StorePlan) {
            val want = (here.toSet() - p.remove.toSet()) + p.add + p.overwrite
            assertEquals(want.sorted(), now.sorted(), "$name: $store read back")
        }
        rows("beans", before.beans, after.beans, plan.beans)
        rows("roasters", before.roasters, after.roasters, plan.roasters)
        rows("profiles", before.profiles, after.profiles, plan.profiles)
        rows("recipes", before.recipes, after.recipes, plan.recipes)
        rows("customMethods", before.customMethods, after.customMethods, plan.customMethods)

        // Shots: rows, curves filled, tombstones, debits.
        val s = plan.shots
        val liveBefore = before.shots.filterNot { it.deleted == true }.map { it.id }.toSet()
        val liveAfter = after.shots.filterNot { it.deleted == true }
        assertEquals(
            ((liveBefore - s.remove.toSet()) + s.add + s.overwrite + s.revive).sorted(),
            liveAfter.map { it.id }.sorted(),
            "$name: shots read back",
        )
        for (id in s.fill) assertTrue(liveAfter.single { it.id == id }.hasSamples, "$name: $id's curve was filled")
        val deletedBefore = before.shots.filter { it.deleted == true }.map { it.id }.toSet()
        assertEquals(
            (deletedBefore - plan.tombstones.cancel.toSet()).sorted(),
            after.shots.filter { it.deleted == true }.map { it.id }.sorted(),
            "$name: shots deleted here",
        )
        val history = VmHarness.ownState(vm).history.associateBy { it.id }
        val theirs = records.parsed.shots.associateBy { it.id }
        for (id in s.add + s.revive) {
            val want = if (id in s.zeroDebit) 0f else theirs.getValue(id).bagDebitG
            assertEquals(want, history.getValue(id).bagDebitG, "$name: $id's bag debit")
        }

        // Photos: put ones are the backup's, kept ones are this device's, removed ones are gone.
        val photosWant = (before.photos.toSet() - plan.photos.remove.toSet()) + plan.photos.put
        for (id in before.photos + plan.photos.put + plan.photos.remove) {
            val file = BeanImageStore.beanImageFile(app, id)
            when {
                id !in photosWant -> assertTrue(!BeanImageStore.exists(app, id), "$name: $id's photo went")
                id in plan.photos.put -> assertEquals("backup:$id", file.readText(), "$name: $id's photo")
                else -> assertEquals("here:$id", file.readText(), "$name: $id's photo")
            }
        }

        assertEquals(if (plan.clearActiveBean) null else before.activeBeanId, after.activeBeanId, "$name: active bean")

        if (plan.profileMeta.action == PlanAction.Apply) {
            assertEquals(plan.profileMeta.pinned.toSet(), after.profileMeta.pinned.toSet(), "$name: pins")
            assertEquals(plan.profileMeta.hiddenBuiltins.toSet(), after.profileMeta.hiddenBuiltins.toSet(), "$name: hidden profiles")
        } else {
            assertEquals(before.profileMeta.pinned.toSet(), after.profileMeta.pinned.toSet(), "$name: pins kept")
            assertEquals(before.profileMeta.hiddenBuiltins.toSet(), after.profileMeta.hiddenBuiltins.toSet(), "$name: hidden profiles kept")
        }
        if (plan.recipeMeta.action == PlanAction.Apply) {
            assertEquals(plan.recipeMeta.defaults.toMap(), after.recipeMeta.defaults.toMap(), "$name: recipe defaults")
            assertEquals(plan.recipeMeta.hiddenBuiltins.toSet(), after.recipeMeta.hiddenBuiltins.toSet(), "$name: hidden recipes")
        } else {
            assertEquals(before.recipeMeta.defaults.toMap(), after.recipeMeta.defaults.toMap(), "$name: recipe defaults kept")
            assertEquals(before.recipeMeta.hiddenBuiltins.toSet(), after.recipeMeta.hiddenBuiltins.toSet(), "$name: hidden recipes kept")
        }

        // Settings: the backup's shared settings land, or this device's stay.
        val settingsLine = (c.backup["settings"] as? JsonObject)?.let { templates.getValue("settings")["common"]!!.jsonObject }
        if (plan.settings.common == PlanAction.Apply && settingsLine != null) {
            val now = json.encodeToJsonElement(coffee.crema.core.CommonSettings.serializer(), after.common)
            val wrong = settingsLine.keys.filterNot { sameJson(settingsLine[it], now.jsonObject[it]) }
            assertTrue(wrong.isEmpty(), "$name: settings not applied: ${wrong.associateWith { settingsLine[it] to now.jsonObject[it] }}")
        } else {
            assertEquals(before.common, after.common, "$name: settings kept")
        }

        val maintenanceNow = vm.ui.value.maintenance
        if (plan.maintenance == PlanAction.Apply && c.backup["maintenance"].isTrue()) {
            val line = templates.getValue("maintenance")
            assertEquals(line["cleanAtMs"]!!.jsonPrimitive.content.toLong(), maintenanceNow.cleanAtMs, "$name: maintenance applied")
        } else {
            assertEquals(maintenanceBefore.totalLitres, maintenanceNow.totalLitres, "$name: maintenance kept")
            // A device with no maintenance file stamps "now" at each launch: only a stored one has a fixed stamp.
            if (maintenanceBefore.totalLitres > 0) assertEquals(maintenanceBefore.cleanAtMs, maintenanceNow.cleanAtMs, "$name: maintenance kept")
        }
        // Applied prefs are guarded (sharing never widens), so only a keep is exact.
        if (plan.visualizerPrefs != PlanAction.Apply) assertEquals(before.visualizerPrefs, after.visualizerPrefs, "$name: Visualizer prefs kept")
    }
}
