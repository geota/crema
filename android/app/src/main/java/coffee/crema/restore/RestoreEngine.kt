package coffee.crema.restore

import coffee.crema.core.LocalState
import coffee.crema.core.PlanCode
import coffee.crema.core.PlanNote
import coffee.crema.core.RestorePlan
import coffee.crema.core.Verdict
import coffee.crema.core.planRestore
import coffee.crema.core.scanBackupBytes
import coffee.crema.persist.SafeFile
import coffee.crema.persist.readZipCapped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

/*
 * An Android restore through the core's plan (restore-safety design, sections
 * 1, 2 and 8, PR7a). The core reads the backup's bytes strictly and decides per
 * store and mode; this file reads the file or zip, stages its photos, and runs
 * the plan's steps in order through a [RestoreSink]. Each store computes its
 * next value and lands in one awaited write: nothing is cleared, and `remove`
 * names ids, so a shot that lands during the restore survives it.
 */

/** The two restore buttons, in the core's words. */
enum class RestoreKind(val core: String) { MERGE("merge"), REPLACE("replace") }

/** A backup read off its file: the JSONL bytes, and the zip's photos staged on disk. */
class PreparedBackup(
    /** For the marker and the report. */
    val name: String,
    val jsonl: ByteArray,
    /** Bean id → its staged photo, outside the photos directory until step 3. */
    val photos: Map<String, File>,
    /** The core's `BackupManifest`, as JSON. */
    val manifestJson: String,
) {
    /**
     * Which backup this is, by its header (`createdAt`, device, app version),
     * so the same backup from Drive or from a file is the same (DECISIONS §7);
     * the file name stands in only when the header didn't read. The web's
     * `PreparedBackup.identity`, in the same form.
     */
    val identity: String = backupIdentity(manifestJson, name)
}

/** `[createdAt, deviceLabel, appVersion]` from the manifest's header, or `["file", name]`. */
internal fun backupIdentity(manifestJson: String, name: String): String {
    val header = runCatching {
        (Json.parseToJsonElement(manifestJson) as? JsonObject)?.get("header") as? JsonObject
    }.getOrNull()
    if (header == null) return JsonArray(listOf(JsonPrimitive("file"), JsonPrimitive(name))).toString()
    fun field(key: String) = (header[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }
    val createdAt = field("createdAt")?.content?.toLongOrNull()
    return JsonArray(
        listOf(
            createdAt?.let { JsonPrimitive(it) } ?: JsonNull,
            JsonPrimitive(field("deviceLabel")?.content ?: ""),
            JsonPrimitive(field("appVersion")?.content ?: ""),
        ),
    ).toString()
}

private fun isZip(bytes: ByteArray): Boolean =
    bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
        bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

/**
 * Read a backup: a `.crema.zip` (its `backup.jsonl` and `images/<beanId>`) or
 * bare JSONL. The bytes go to the core undecoded, so one bad byte is one bad
 * line. Photos go to [stageDir], not into the photo directory: a refused
 * restore must touch nothing. Throws on a zip with no backup inside.
 */
fun prepareBackup(bytes: ByteArray, name: String, stageDir: File): PreparedBackup {
    var jsonl = bytes
    val photos = LinkedHashMap<String, File>()
    if (isZip(bytes)) {
        stageDir.deleteRecursively()
        stageDir.mkdirs()
        var member: ByteArray? = null
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
            readZipCapped(zis, accept = { it == "backup.jsonl" || it == "crema.jsonl" || it.startsWith("images/") }) { entry, data ->
                if (entry.startsWith("images/")) {
                    val beanId = entry.substringAfter("images/")
                    if (beanId.isNotEmpty() && data.isNotEmpty()) {
                        val staged = File(stageDir, "photo-${photos.size}")
                        staged.writeBytes(data)
                        photos[beanId] = staged
                    }
                } else if (member == null || entry == "backup.jsonl") {
                    member = data
                }
            }
        }
        jsonl = member ?: throw IllegalArgumentException("Not a Crema backup zip (no backup.jsonl inside).")
    }
    return PreparedBackup(name, jsonl, photos, scanBackupBytes(jsonl, photos.keys.toList()))
}

// ── The marker ───────────────────────────────────────────────────────────────

/** What a restore owes to delete, by id. */
@Serializable
data class MarkerDeletes(val shots: List<String> = emptyList(), val photos: List<String> = emptyList())

/** `restore-pending.json`: `applying` while a restore writes, `finishing` while its deletes run. */
@Serializable
data class RestoreMarker(
    val phase: String,
    val mode: String,
    val backupName: String,
    val startedAt: Long,
    val deletes: MarkerDeletes = MarkerDeletes(),
    /** Beans this restore added, across re-runs: they aren't this device's own bag for `zeroDebit`. */
    val addedBeans: List<String> = emptyList(),
    /** Which backup [addedBeans] belong to: its [PreparedBackup.identity]. */
    val backupId: String = "",
) {
    /** Whether this marker is an interrupted run of the backup with [identity], from Drive or a file alike. */
    fun isRunOf(identity: String): Boolean =
        phase == RestoreMarkerStore.PHASE_APPLYING && backupId.isNotEmpty() && backupId == identity
}

/** The marker file, through [SafeFile]: a kill is seen at the next launch. */
class RestoreMarkerStore(private val file: File, private val json: Json) {
    fun read(): RestoreMarker? =
        runCatching { if (file.exists()) json.decodeFromString(RestoreMarker.serializer(), file.readText()) else null }.getOrNull()

    /** Step 1: `applying`, keeping deletes an earlier marker owes, as a re-run removes nothing. */
    fun begin(
        mode: RestoreKind,
        backupName: String,
        deletes: MarkerDeletes,
        now: Long,
        addedBeans: List<String> = emptyList(),
        backupId: String = "",
    ): Boolean {
        val earlier = read()
        // Deletes owed stay whatever the backup; added beans only for a re-run of the same one.
        val sameBackup = backupId.isNotEmpty() && earlier?.isRunOf(backupId) == true
        val prior = earlier?.deletes ?: MarkerDeletes()
        val marker = RestoreMarker(
            phase = PHASE_APPLYING,
            mode = mode.core,
            backupName = backupName,
            startedAt = now,
            deletes = MarkerDeletes(
                shots = (prior.shots + deletes.shots).distinct(),
                photos = (prior.photos + deletes.photos).distinct(),
            ),
            addedBeans = ((if (sameBackup) earlier?.addedBeans.orEmpty() else emptyList()) + addedBeans).distinct(),
            backupId = backupId,
        )
        return write(marker)
    }

    fun finishing(): Boolean = read()?.let { write(it.copy(phase = PHASE_FINISHING)) } ?: true

    fun clear() {
        runCatching { coffee.crema.persist.eraseStore(file) }
    }

    private fun write(marker: RestoreMarker): Boolean =
        runCatching { SafeFile.write(file, json.encodeToString(RestoreMarker.serializer(), marker)) }.isSuccess

    companion object {
        const val FILE_NAME = "restore-pending.json"
        const val PHASE_APPLYING = "applying"
        const val PHASE_FINISHING = "finishing"
    }
}

/** `idle | running | leftover` (design §8): a marker alone can't tell a restore in progress from one a kill left. */
enum class RestoreState { IDLE, RUNNING, LEFTOVER }

// ── The sink: the stores, as the engine writes them ──────────────────────────

/** The steps, in order. A [RestoreSink] can be made to stop at one, as a kill or a full disk would. */
enum class RestoreStep { MARKER, TOMBSTONES, PHOTOS, RECIPES, BEANS, PROFILES, SHOTS, SETTINGS }

/** What the engine writes through. Each call updates memory, then awaits the write, and says whether it landed. */
interface RestoreSink {
    /** What the device holds, once the shots' telemetry has loaded. */
    suspend fun localState(): LocalState
    /** A shot runs, or a firmware flash holds the link. */
    fun machineBusy(): Boolean
    suspend fun cancelTombstones(ids: List<String>): Boolean
    suspend fun putPhotos(plan: RestorePlan, staged: Map<String, File>): Boolean
    suspend fun applyRecipes(plan: RestorePlan, records: RestoreRecords): Boolean
    suspend fun applyLibrary(plan: RestorePlan, records: RestoreRecords): Boolean
    suspend fun applyProfiles(plan: RestorePlan, records: RestoreRecords): Boolean
    /** Step 7: series first, then the rows, computed after the series land. Returns curves that failed, or null when the rows didn't land. */
    suspend fun applyShots(plan: RestorePlan, records: RestoreRecords): Int?
    /** Step 8's three writes. */
    suspend fun applySingletons(plan: RestorePlan, records: RestoreRecords): Boolean
    /** Step 9: the deletes the marker owes, only for ids no longer listed. False when a store
     *  they depend on didn't load, so they wait, with the marker, for a launch where it does. */
    suspend fun runDeletes(deletes: MarkerDeletes): Boolean
    /** Steps 1 to 9: the history's save diff deletes nothing; the marker owns the restore's deletes. */
    fun pauseDeletes()
    fun resumeDeletes()
    /** Step 9: the directories whose renames must be durable before any delete. */
    fun changedDirs(): List<File>
}

/** How a restore ended. */
sealed interface RestoreOutcome {
    data class Refused(val plan: RestorePlan) : RestoreOutcome
    /** The device changed while the report was open: here are the new numbers. */
    data class Changed(val plan: RestorePlan) : RestoreOutcome
    /** The plan asks for the user's yes, and none was given. */
    data class Unconfirmed(val plan: RestorePlan) : RestoreOutcome
    /** A restore is already running. */
    data object Busy : RestoreOutcome
    data class Done(
        val plan: RestorePlan,
        val curvesFailed: Int,
        /** Step 8 timed out: settings, maintenance and Visualizer prefs weren't changed. */
        val singletonsSkipped: Boolean,
    ) : RestoreOutcome
    data class Stopped(val plan: RestorePlan, val at: RestoreStep, val landed: List<RestoreStep>) : RestoreOutcome
}

/** The core's plan for [prepared] against [local]. */
fun planFor(prepared: PreparedBackup, kind: RestoreKind, local: LocalState, json: Json, reviveDeleted: Boolean = false): RestorePlan =
    json.decodeFromString(
        RestorePlan.serializer(),
        planRestore(
            prepared.manifestJson,
            json.encodeToString(LocalState.serializer(), local),
            kind.core,
            """{"reviveDeleted":$reviveDeleted}""",
        ),
    )

/** The engine. One restore at a time per process: [running] is also the one-at-a-time rule. */
class RestoreEngine(
    private val sink: RestoreSink,
    private val marker: RestoreMarkerStore,
    private val json: Json,
    private val now: () -> Long = System::currentTimeMillis,
    /** How long step 8 waits for a shot or flash to end. */
    private val machineWaitMs: Long = MACHINE_WAIT_MS,
    /** Test seam: stop at a step. */
    var failAt: RestoreStep? = null,
) {
    fun state(): RestoreState = when {
        running.get() -> RestoreState.RUNNING
        marker.read()?.phase == RestoreMarkerStore.PHASE_APPLYING -> RestoreState.LEFTOVER
        else -> RestoreState.IDLE
    }

    /**
     * At launch: an `applying` marker with nothing running is a restore a kill
     * or a crash left, whose name comes back for the notice; a `finishing` one
     * runs the deletes it owes, under the same checks, then goes.
     */
    suspend fun checkAtBoot(): String? {
        if (running.get()) return null
        val m = marker.read() ?: return null
        if (m.phase == RestoreMarkerStore.PHASE_APPLYING) return m.backupName
        // Skipped deletes keep the marker, so a later launch still owes them.
        if (sink.runDeletes(m.deletes)) marker.clear()
        return null
    }

    /** The notice's Dismiss: the leftover marker moves to `finishing`, so its deletes still run. */
    suspend fun dismissLeftover() {
        if (running.get()) return
        marker.finishing()
        checkAtBoot()
    }

    /** The plan against this device as it is now. */
    suspend fun plan(prepared: PreparedBackup, kind: RestoreKind, reviveDeleted: Boolean = false): RestorePlan {
        // A re-run after an interrupted one: the beans it added aren't the device's own.
        // Only an interrupted run of this same backup, by its header (DECISIONS §7).
        val added = marker.read()?.takeIf { it.isRunOf(prepared.identity) }?.addedBeans.orEmpty()
        return planFor(prepared, kind, sink.localState().copy(addedBeans = added), json, reviveDeleted)
    }

    /**
     * Run a restore. With [expected], the plan the user confirmed: if the device
     * changed since, nothing runs and the new plan comes back. Without it, only
     * a plan the core calls `ok` runs. Runs to its end even if the caller's
     * scope is cancelled: a process death is what the marker is for.
     */
    suspend fun run(
        prepared: PreparedBackup,
        kind: RestoreKind,
        records: RestoreRecords,
        reviveDeleted: Boolean = false,
        expected: RestorePlan? = null,
    ): RestoreOutcome = withContext(NonCancellable) {
        if (!running.compareAndSet(false, true)) return@withContext RestoreOutcome.Busy
        try {
            // Step 0: re-plan from the device as it is now.
            val plan = plan(prepared, kind, reviveDeleted)
            when {
                plan.verdict == Verdict.Refused -> RestoreOutcome.Refused(plan)
                expected != null && expected != plan -> RestoreOutcome.Changed(plan)
                expected == null && plan.verdict != Verdict.Ok -> RestoreOutcome.Unconfirmed(plan)
                sink.machineBusy() -> RestoreOutcome.Refused(
                    plan.copy(verdict = Verdict.Refused, refusals = listOf(PlanNote(code = PlanCode.MachineBusy, confirm = true))),
                )
                else -> apply(prepared, kind, plan, records)
            }
        } finally {
            running.set(false)
        }
    }

    private suspend fun apply(prepared: PreparedBackup, kind: RestoreKind, plan: RestorePlan, records: RestoreRecords): RestoreOutcome {
        val landed = mutableListOf<RestoreStep>()
        suspend fun step(name: RestoreStep, run: suspend () -> Boolean): Boolean {
            if (failAt == name) return false
            val ok = try {
                run()
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                false
            }
            if (ok) landed += name
            return ok
        }
        fun stopped(at: RestoreStep) = RestoreOutcome.Stopped(plan, at, landed.toList())
        // From step 1 to the end, no save deletes by its diff: the marker owns them.
        sink.pauseDeletes()
        try {
            return applySteps(prepared, kind, plan, records, landed, ::step, ::stopped)
        } finally {
            sink.resumeDeletes()
        }
    }

    private suspend fun applySteps(
        prepared: PreparedBackup,
        kind: RestoreKind,
        plan: RestorePlan,
        records: RestoreRecords,
        landed: MutableList<RestoreStep>,
        step: suspend (RestoreStep, suspend () -> Boolean) -> Boolean,
        stopped: (RestoreStep) -> RestoreOutcome,
    ): RestoreOutcome {

        // 1. The marker, with what this restore will owe to delete.
        if (!step(RestoreStep.MARKER) {
                marker.begin(kind, prepared.name, MarkerDeletes(plan.shots.remove, plan.photos.remove), now(), plan.beans.add, prepared.identity)
            }
        ) return stopped(RestoreStep.MARKER)
        // 2. Tombstones the backup brings back: no remote DELETE may follow.
        if (!step(RestoreStep.TOMBSTONES) { sink.cancelTombstones(plan.tombstones.cancel) }) return stopped(RestoreStep.TOMBSTONES)
        // 3. Photos, so a bean never points at one that isn't there.
        if (!step(RestoreStep.PHOTOS) { sink.putPhotos(plan, prepared.photos) }) return stopped(RestoreStep.PHOTOS)
        // 4. Methods, then recipes, which name them: one file.
        if (!step(RestoreStep.RECIPES) { sink.applyRecipes(plan, records) }) return stopped(RestoreStep.RECIPES)
        // 5. Roasters and beans, and the active bean.
        if (!step(RestoreStep.BEANS) { sink.applyLibrary(plan, records) }) return stopped(RestoreStep.BEANS)
        // 6. Profiles, pins and hidden built-ins.
        if (!step(RestoreStep.PROFILES) { sink.applyProfiles(plan, records) }) return stopped(RestoreStep.PROFILES)
        // 7. Series, then rows.
        var curvesFailed = 0
        if (!step(RestoreStep.SHOTS) { sink.applyShots(plan, records)?.also { curvesFailed = it } != null }) {
            return stopped(RestoreStep.SHOTS)
        }
        // 8. Never mid-shot or mid-flash: a settings push writes to the machine. At most two minutes.
        var skipped = false
        if (records.hasSingletons(plan)) {
            if (failAt == RestoreStep.SETTINGS) return stopped(RestoreStep.SETTINGS)
            // Off the main thread, on the wall clock: the wait mustn't hold anything else up.
            withContext(Dispatchers.Default) {
                val until = now() + machineWaitMs
                while (sink.machineBusy() && now() < until) delay(250)
            }
            if (sink.machineBusy()) {
                skipped = true
            } else if (!step(RestoreStep.SETTINGS) { sink.applySingletons(plan, records) }) {
                return stopped(RestoreStep.SETTINGS)
            }
        }
        // 9. Finishing: the renames made durable, then the deletes, then the marker goes.
        marker.finishing()
        withContext(Dispatchers.IO) { sink.changedDirs().forEach(::syncDir) }
        val owed = marker.read()?.deletes
        if (owed == null || sink.runDeletes(owed)) marker.clear()
        return RestoreOutcome.Done(plan, curvesFailed, skipped)
    }

    companion object {
        const val MACHINE_WAIT_MS = 120_000L

        /** One restore at a time in this process. */
        private val running = AtomicBoolean(false)

        /** Test seam. */
        internal fun resetForTest() = running.set(false)

        /** `FileChannel.force` on a directory, as Lucene does; failures don't matter, only a missed rename would. */
        fun syncDir(dir: File) {
            runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
        }
    }
}
