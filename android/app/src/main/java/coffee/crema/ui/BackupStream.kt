package coffee.crema.ui

import coffee.crema.core.BackupCounts
import coffee.crema.core.backupPrelude
import coffee.crema.core.backupShotLine
import coffee.crema.core.backupTail
import coffee.crema.history.StoredShot
import coffee.crema.visualizer.wireShotJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * The backup built one line at a time (#310 P2a, SPEC §7), the Android twin of
 * the web's `backup/stream.ts`. The core gives the lines: a prelude (header,
 * settings-like lines, roasters, beans), one line per shot and a tail
 * (methods, recipes, profiles). Joined, they're [backupBundleJsonl]'s bytes.
 * They go through a `ZipOutputStream` straight to a temporary file in the
 * cache directory, so a large history is never one string or one array.
 *
 * The header counts the shots first, so each shot is read twice: once to
 * learn whether the core can write it, once to write it. The rows are the
 * snapshot's, so a shot saved during the walk is in the next backup.
 */

private const val MiB = 1024L * 1024

/** The same in both shells (SPEC §7): what an export refuses past, and a restore reads. */
internal object BackupLimits {
    /** One line of `backup.jsonl`. */
    const val MAX_LINE_BYTES = 16 * MiB
    /** `backup.jsonl` inflated: below zip64. */
    const val MAX_INFLATED_BYTES = 3 * 1024 * MiB
    /** The `.crema.zip` file. */
    const val MAX_FILE_BYTES = 2 * 1024 * MiB
}

/** The limits a walk checks; tests pass smaller ones. */
internal data class BackupWalkLimits(
    val maxLineBytes: Long = BackupLimits.MAX_LINE_BYTES,
    val maxInflatedBytes: Long = BackupLimits.MAX_INFLATED_BYTES,
    val maxFileBytes: Long = BackupLimits.MAX_FILE_BYTES,
)

/** Temporary backup files in the cache directory start with this. */
internal const val BACKUP_TEMP_PREFIX = "backup-tmp-"

/** The user cancelled: nothing was kept. */
internal class BackupCancelled : Exception("Backup cancelled.")

/** Past [BackupLimits]: refused, nothing kept. */
internal class BackupTooLarge(message: String) : Exception(message)

/** How far a walk is. */
data class BackupProgress(val shotsWritten: Int, val shotsTotal: Int)

/** The core's `BackupPrelude`. */
@Serializable
private data class Prelude(val jsonl: String, val omitted: List<OmittedBackupRow> = emptyList())

/** The core's `BackupShotLine`. */
@Serializable
private data class ShotLine(val line: String, val withoutProfile: Boolean = false, val hasCurve: Boolean = false)

/** What a walk built. */
internal class StreamedBackup(
    val file: File,
    val counts: BackupCounts,
    /** As [backupContentHash] would give for the same data. */
    val contentHash: String,
    val degraded: List<String>,
    /** Rows left out, of every kind, shots included. */
    val omitted: List<OmittedBackupRow>,
    /** Photos that couldn't be read, so went without: bean id and why. */
    val photosSkipped: List<String> = emptyList(),
)

/** Which build a backup is: each has its own cancel and progress (#391 review). */
enum class BackupKind { FILE, DRIVE, DAILY }

/**
 * The running builds' cancels, one per kind: a cancel reaches only its own
 * build, and a build starting doesn't clear another's (#391 review).
 */
internal class BackupRuns {
    private val cancels = java.util.concurrent.ConcurrentHashMap<BackupKind, java.util.concurrent.atomic.AtomicBoolean>()

    /** A [kind] build starts: its own cancel. */
    fun start(kind: BackupKind): java.util.concurrent.atomic.AtomicBoolean =
        java.util.concurrent.atomic.AtomicBoolean().also { cancels[kind] = it }

    /** Cancel the [kind] build running now, if one is. */
    fun cancel(kind: BackupKind) {
        cancels[kind]?.set(true)
    }

    /** The build that started with [token] is over. */
    fun end(kind: BackupKind, token: java.util.concurrent.atomic.AtomicBoolean) {
        cancels.remove(kind, token)
    }
}

/**
 * Temporary backup files this process is still building, saving or
 * uploading, by path: the sweep leaves them, however old (#391 review). Added
 * by [backupTempFile], removed by [releaseBackupFile].
 */
private val liveBackupFiles: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

/** A temporary file for a backup, in [cacheDir], live until [releaseBackupFile]. */
internal fun backupTempFile(cacheDir: File, now: Long = System.currentTimeMillis()): File =
    File(cacheDir, "$BACKUP_TEMP_PREFIX$now-${(0..0xffffff).random().toString(16)}.zip")
        .also { liveBackupFiles += it.absolutePath }

/** Done with a backup's temporary file: remove it. */
internal fun releaseBackupFile(file: File) {
    file.delete()
    liveBackupFiles -= file.absolutePath
}

/**
 * Remove the temporary backup files in [cacheDir] older than [keepMs], and
 * not live in this process: at startup and before a backup.
 */
internal fun sweepBackupTemp(cacheDir: File, keepMs: Long = 10 * 60_000L, now: Long = System.currentTimeMillis()): Int {
    var removed = 0
    for (f in cacheDir.listFiles().orEmpty()) {
        if (!f.isFile || !f.name.startsWith(BACKUP_TEMP_PREFIX)) continue
        if (f.absolutePath in liveBackupFiles) continue
        val stamp = f.name.removePrefix(BACKUP_TEMP_PREFIX).substringBefore('-').toLongOrNull()
        if (stamp != null && now - stamp < keepMs) continue
        if (f.delete()) removed += 1
    }
    return removed
}

/** An output stream that counts what goes through, and refuses past [max]. */
private class CountingOut(private val out: OutputStream, private val max: Long) : OutputStream() {
    var count = 0L
        private set

    private fun add(n: Int) {
        count += n
        if (count > max) throw BackupTooLarge("The backup would pass ${max / MiB} MB.")
    }

    override fun write(b: Int) { add(1); out.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) { add(len); out.write(b, off, len) }
    override fun flush() = out.flush()
    override fun close() = out.close()
}

/**
 * Build the `.crema.zip` for [snap] into [out], one line at a time, with the
 * bean [photos] (bean id → file) sorted by id. [stop] is checked between
 * batches and throws to stop: [BackupCancelled] for the user's cancel. On any
 * throw [out] is deleted. [shotJson] is the shot's backup JSON (a test can
 * stand in for it).
 */
internal fun streamBackupZip(
    snap: BackupSnapshot,
    json: Json,
    createdAtMs: Long,
    appVersion: String,
    deviceLabel: String,
    photos: List<Pair<String, File>>,
    out: File,
    limits: BackupWalkLimits = BackupWalkLimits(),
    batch: Int = 25,
    stop: () -> Unit = {},
    onProgress: (BackupProgress) -> Unit = {},
    shotJson: (StoredShot) -> String = { wireShotJson(it, forBackup = true).toString() },
): StreamedBackup {
    try {
        return walk(snap, json, createdAtMs, appVersion, deviceLabel, photos, out, limits, batch, stop, onProgress, shotJson)
    } catch (t: Throwable) {
        releaseBackupFile(out)
        throw t
    }
}

private fun walk(
    snap: BackupSnapshot,
    json: Json,
    createdAtMs: Long,
    appVersion: String,
    deviceLabel: String,
    photos: List<Pair<String, File>>,
    out: File,
    limits: BackupWalkLimits,
    batch: Int,
    stop: () -> Unit,
    onProgress: (BackupProgress) -> Unit,
    shotJson: (StoredShot) -> String,
): StreamedBackup {
    val shots = snap.state.history.toList()
    val lineOf = { shot: StoredShot ->
        runCatching { json.decodeFromString(ShotLine.serializer(), backupShotLine(shotJson(shot))) }.getOrNull()
    }
    stop()
    // Pass 1: which shots the core can write, so the header counts them.
    val readable = BooleanArray(shots.size)
    val omitted = mutableListOf<OmittedBackupRow>()
    for ((i, shot) in shots.withIndex()) {
        readable[i] = lineOf(shot) != null
        if (!readable[i]) omitted += OmittedBackupRow("shot", i, null)
        if ((i + 1) % batch == 0) stop()
    }
    val written = shots.size - omitted.size
    // As the whole-string export: shots of which none could be read are a
    // failed section, not a gap, or the file would look like a good backup of
    // nothing and retention would let it replace the copies that hold them.
    if (shots.isNotEmpty() && written == 0) error("none of the ${shots.size} shot rows could be read")
    val envelope = backupEnvelope(snap, json, withShots = false)
    val prelude = json.decodeFromString(
        Prelude.serializer(),
        backupPrelude(envelope, written.toUInt(), omitted.size.toUInt(), createdAtMs, appVersion, deviceLabel),
    )

    val hash = MessageDigest.getInstance("SHA-256")
    var inflated = 0L
    val degraded = mutableListOf<String>()
    val photosSkipped = mutableListOf<String>()
    var curves = 0u
    var shotsWritten = 0
    CountingOut(BufferedOutputStream(FileOutputStream(out), 64 * 1024), limits.maxFileBytes).use { counted ->
        ZipOutputStream(counted).use { zip ->
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            // Entries carry the backup's own time, not the clock's, so the same
            // data gives the same zip (the cross-shell fixture is compared with it).
            zip.putNextEntry(ZipEntry(BACKUP_JSONL_ENTRY).apply { time = createdAtMs })
            var header = true
            /** Each line in the limit, counted, hashed after the header, and zipped. */
            fun push(text: String) {
                for (line in text.split('\n')) {
                    if (line.toByteArray(Charsets.UTF_8).size > limits.maxLineBytes) {
                        throw BackupTooLarge("A record is larger than ${limits.maxLineBytes / MiB} MB, which a backup can't hold.")
                    }
                }
                val bytes = text.toByteArray(Charsets.UTF_8)
                inflated += bytes.size
                if (inflated > limits.maxInflatedBytes) {
                    throw BackupTooLarge("The backup would pass ${limits.maxInflatedBytes / MiB} MB unzipped.")
                }
                if (header) {
                    // As backupContentHash: everything after the header line.
                    val nl = bytes.indexOf('\n'.code.toByte())
                    if (nl >= 0) hash.update(bytes, nl + 1, bytes.size - nl - 1)
                    header = false
                } else {
                    hash.update(bytes)
                }
                zip.write(bytes)
            }
            push(prelude.jsonl)
            // Pass 2: the lines, in order.
            for ((i, shot) in shots.withIndex()) {
                if (!readable[i]) continue
                val line = lineOf(shot) ?: error("A shot changed while it was being backed up. Try again.")
                if (line.withoutProfile) degraded += shot.id
                if (line.hasCurve) curves += 1u
                push(line.line)
                shotsWritten += 1
                if (shotsWritten % batch == 0) {
                    onProgress(BackupProgress(shotsWritten, written))
                    stop()
                }
            }
            push(backupTail(envelope))
            zip.closeEntry()
            onProgress(BackupProgress(shotsWritten, written))
            for ((beanId, file) in photos.sortedBy { it.first }) {
                stop()
                val bytes = runCatching { file.readBytes() }
                    .onFailure { photosSkipped += "$beanId (${it.message})" }
                    .getOrNull() ?: continue
                hash.update(beanId.toByteArray(Charsets.UTF_8))
                hash.update(bytes)
                // Deflated: the web's streamed zips are too, and a stored entry
                // needs its size and CRC up front.
                zip.putNextEntry(ZipEntry("images/$beanId").apply { time = createdAtMs })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
    return StreamedBackup(
        file = out,
        counts = BackupCounts(shotsWritten.toUInt(), curves),
        contentHash = hash.digest().joinToString("") { "%02x".format(it) },
        degraded = degraded,
        omitted = prelude.omitted + omitted,
        photosSkipped = photosSkipped,
    )
}

/** The member name of the lines inside a `.crema.zip`. */
internal const val BACKUP_JSONL_ENTRY = "backup.jsonl"
