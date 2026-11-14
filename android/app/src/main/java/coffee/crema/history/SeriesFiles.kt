package coffee.crema.history

import coffee.crema.persist.SafeFile
import coffee.crema.persist.SafeLoad
import coffee.crema.persist.loadItem
import coffee.crema.ui.TelemetrySample
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/*
 * One file per shot's telemetry (F10). From #310 P3a a new series is
 * `<name>.series.gz`, gzip JSON (SPEC §3). The `<name>.json` files a build
 * before it wrote stay where they are, read and never written or deleted: the
 * old copy. The suffix also keeps a new file out of an old build's prune, which
 * only deletes `.json`.
 */

/** What reading a shot's series found. */
internal sealed interface SeriesRead {
    data class Found(val samples: List<TelemetrySample>, val file: File) : SeriesRead
    data object Absent : SeriesRead
    /** A file is there and doesn't decode; it was kept aside. */
    data object Corrupt : SeriesRead
    /** A file is there and couldn't be read: an I/O error, which says nothing about it. */
    data class Unavailable(val error: String) : SeriesRead
}

internal class SeriesFiles(val dir: File, private val json: Json) {

    private val serializer = ListSerializer(TelemetrySample.serializer())

    /** The new name: [HistoryStore.seriesFileName] with `.series.gz`. */
    fun gzFile(id: String): File = File(dir, HistoryStore.seriesFileName(id).removeSuffix(".json") + GZ)

    /** The F10 name, read but never written or deleted from P3a. */
    fun jsonFile(id: String): File = File(dir, HistoryStore.seriesFileName(id))

    /** The name before F10's encoding, read the same way. */
    fun legacyFile(id: String): File = File(dir, id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    /** [id]'s series, in read order: `.series.gz`, then `.json`, then the pre-rename name. */
    fun read(id: String): SeriesRead {
        when (val gz = readGz(gzFile(id))) {
            SeriesRead.Absent -> Unit
            else -> return gz
        }
        val candidates = listOf(jsonFile(id), legacyFile(id)).distinctBy { it.name }
        for (f in candidates) {
            when (val outcome = loadItem(f, "shot telemetry", open = false) { json.decodeFromString(serializer, it) }) {
                is SafeLoad.Loaded -> return SeriesRead.Found(outcome.value, f)
                is SafeLoad.Corrupt -> return SeriesRead.Corrupt
                is SafeLoad.Unavailable -> return SeriesRead.Unavailable(outcome.error)
                SafeLoad.Absent -> Unit
            }
        }
        return SeriesRead.Absent
    }

    /** Whether any file names [id]'s series. */
    fun exists(id: String): Boolean = gzFile(id).exists() || jsonFile(id).exists() || legacyFile(id).exists()

    private fun readGz(f: File): SeriesRead {
        val bytes = try {
            if (!f.exists()) return SeriesRead.Absent
            f.readBytes()
        } catch (e: java.io.IOException) {
            return SeriesRead.Unavailable(e.message ?: "read failed")
        }
        return try {
            val text = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
            SeriesRead.Found(json.decodeFromString(serializer, text), f)
        } catch (_: Exception) {
            // Kept aside, never deleted: the file may still be recovered by hand.
            runCatching { f.renameTo(File(f.path + ".corrupt-" + System.currentTimeMillis())) }
            SeriesRead.Corrupt
        }
    }

    /** Write [samples] as [id]'s `.series.gz`, atomically. True when on disk. */
    fun write(id: String, samples: List<TelemetrySample>): Boolean = runCatching {
        dir.mkdirs()
        val raw = json.encodeToString(serializer, samples).toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(raw.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(raw) }
        beforeWrite(id)
        SafeFile.writeBytes(gzFile(id), out.toByteArray())
        true
    }.getOrDefault(false)

    /** Delete [id]'s `.series.gz`, the one file P3a writes. The `.json` files are the old copy. */
    fun deleteGz(id: String) {
        gzFile(id).delete()
    }

    /** Delete every file naming [id]'s series: the legacy backend, before any migration. */
    fun deleteAll(id: String) {
        for (f in listOf(gzFile(id), jsonFile(id), legacyFile(id))) f.delete()
    }

    companion object {
        const val GZ = ".series.gz"

        /** Test seam: runs before a series write, and may throw to fail it. */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var beforeWrite: (String) -> Unit = {}
    }
}
