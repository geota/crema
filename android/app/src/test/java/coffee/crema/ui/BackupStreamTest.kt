package coffee.crema.ui

import android.app.Application
import coffee.crema.history.StoredShot
import coffee.crema.maintenance.defaultMaintenanceState
import coffee.crema.persist.readZipCapped
import coffee.crema.settings.AppPrefs
import coffee.crema.visualizer.DEFAULT_VISUALIZER_SYNC_PREFS
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The backup built one line at a time (#310 P2a): the same `backup.jsonl` as
 * the whole-string path, the same counts and hash, a file that grows as the
 * walk goes, and cancel, limits and failures that keep nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BackupStreamTest {
    @get:Rule val tmp = TemporaryFolder()

    private val json = coreJson
    private val now = 1_790_000_000_000

    private fun sample(i: Int, pressure: Float = 9f) = TelemetrySample(
        elapsedMs = i * 227L, pressure = pressure, flow = 2f, headTemp = 92f, mixTemp = 90f,
        weight = i / 5f, weightFlow = null, dispensedVolume = 20f, resistance = null, resistanceWeight = null,
        setHeadTemp = 93f, setGroupPressure = 9f, setGroupFlow = 0f, frameNumber = 1,
    )

    private val curve = List(180) { sample(it) }

    private fun shot(i: Int, samples: List<TelemetrySample> = curve) =
        StoredShot(id = "shot-$i", completedAtMs = now - i * 60_000L, durationMs = 40_000, samples = samples, notes = "shot $i")

    private fun snapshot(shots: List<StoredShot>, beans: List<coffee.crema.core.Bean> = emptyList()) = BackupSnapshot(
        customs = listOf("""{"id":"c1","title":"Mine"}"""),
        state = MainUiState(
            maintenance = defaultMaintenanceState(now),
            history = rowViewOf(shots),
            beans = rowViewOf(beans),
        ),
        pinned = setOf("builtin:a"),
        hidden = emptySet(),
        prefs = AppPrefs(),
        visualizerPrefs = DEFAULT_VISUALIZER_SYNC_PREFS,
        userRecipes = emptyList(),
    )

    private fun stream(
        snap: BackupSnapshot,
        photos: List<Pair<String, File>> = emptyList(),
        out: File = File(tmp.root, "${BACKUP_TEMP_PREFIX}1-a.zip"),
        limits: BackupWalkLimits = BackupWalkLimits(),
        stop: () -> Unit = {},
        onProgress: (BackupProgress) -> Unit = {},
    ) = streamBackupZip(snap, json, now, "test", "Android test", photos, out, limits, batch = 25, stop = stop, onProgress = onProgress)

    /** The zip's entries, read with the restore's reader and accept rule. */
    private fun entries(file: File): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(file.inputStream()).use { zin ->
            readZipCapped(zin, accept = { it == "backup.jsonl" || it.startsWith("images/") }) { n, b -> out[n] = b }
        }
        return out
    }

    @Test
    fun `300 shots give the whole-string path's bytes, counts and hash`() {
        val bean = coffee.crema.beans.newBean("Kenya", "r1", 4, null, now).copy(id = "bean-1")
        val snap = snapshot(List(300) { shot(it) }, listOf(bean))
        val photo = File(tmp.root, "photo").apply { writeBytes(ByteArray(2048) { (it * 31).toByte() }) }
        val whole = backupBundleJsonl(snap, json, now, "test", "Android test")
        val built = stream(snap, listOf("bean-1" to photo))
        val zip = entries(built.file)
        assertEquals(whole.jsonl, zip.getValue("backup.jsonl").decodeToString())
        assertEquals(whole.shotCount, built.counts.shots)
        assertEquals(whole.curveCount, built.counts.curves)
        assertEquals(backupContentHash(whole.jsonl, listOf("bean-1" to photo.readBytes())), built.contentHash)
        assertTrue(zip.getValue("images/bean-1").contentEquals(photo.readBytes()))
    }

    @Test
    fun `a shot that won't encode is left out and counted, as the whole path does`() {
        val snap = snapshot(listOf(shot(0), shot(1, listOf(sample(0, Float.NaN))), shot(2)))
        val whole = backupBundleJsonl(snap, json, now, "test", "Android test")
        val built = stream(snap)
        assertEquals(whole.jsonl, entries(built.file).getValue("backup.jsonl").decodeToString())
        assertEquals(listOf(OmittedBackupRow("shot", 1, null)), built.omitted)
        val header = json.parseToJsonElement(whole.jsonl.lineSequence().first()).jsonObject
        assertEquals(1, header.getValue("omittedCount").jsonPrimitive.int)
    }

    @Test
    fun `shots of which none read fail the backup and keep nothing`() {
        val out = File(tmp.root, "${BACKUP_TEMP_PREFIX}1-b.zip")
        assertFailsWith<IllegalStateException> { stream(snapshot(List(3) { shot(it, listOf(sample(0, Float.NaN))) }), out = out) }
        assertFalse(out.exists())
    }

    @Test
    fun `5,000 shots reach the file as the walk goes`() {
        val out = File(tmp.root, "${BACKUP_TEMP_PREFIX}1-c.zip")
        var atHalf = -1L
        val seen = mutableListOf<Int>()
        // Not a whole number of batches, so the last report comes after the walk.
        val built = stream(snapshot(List(5_010) { shot(it) }), out = out, onProgress = { p ->
            seen += p.shotsWritten
            if (atHalf < 0 && p.shotsWritten >= 2_500) atHalf = out.length()
        })
        assertEquals(5_010u, built.counts.shots)
        assertTrue(atHalf > out.length() / 4, "half the shots in, ${atHalf} of ${out.length()} bytes were on disk")
        assertEquals(5_010, seen.last(), "the walk's end is reported")
        assertTrue(seen.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun `a cancel mid-walk keeps nothing`() {
        val out = File(tmp.root, "${BACKUP_TEMP_PREFIX}1-d.zip")
        var written = 0
        assertFailsWith<BackupCancelled> {
            stream(
                snapshot(List(100) { shot(it) }), out = out,
                onProgress = { written = it.shotsWritten },
                stop = { if (written >= 50) throw BackupCancelled() },
            )
        }
        assertFalse(out.exists())
    }

    @Test
    fun `past a limit, a line, unzipped or the file, keeps nothing`() {
        val snap = snapshot(List(100) { shot(it) })
        for (limits in listOf(
            BackupWalkLimits(maxLineBytes = 1024),
            BackupWalkLimits(maxInflatedBytes = 200_000),
            BackupWalkLimits(maxFileBytes = 20_000),
        )) {
            val out = File(tmp.root, "${BACKUP_TEMP_PREFIX}1-e.zip")
            assertFailsWith<BackupTooLarge> { stream(snap, out = out, limits = limits) }
            assertFalse(out.exists(), "$limits")
        }
        // A prelude line past the limit too, with shots well inside it.
        val small = snapshot(List(3) { shot(it, emptyList()) }).let {
            BackupSnapshot(listOf("""{"id":"c1","title":"${"x".repeat(4096)}"}"""), it.state, it.pinned, it.hidden, it.prefs, it.visualizerPrefs, it.userRecipes)
        }
        assertFailsWith<BackupTooLarge> { stream(small, limits = BackupWalkLimits(maxLineBytes = 2048)) }
    }

    @Test
    fun `the sweep removes old temporary files and leaves new and other ones`() {
        val old = File(tmp.root, "${BACKUP_TEMP_PREFIX}${now - 3_600_000}-a.zip").apply { writeText("x") }
        val fresh = File(tmp.root, "${BACKUP_TEMP_PREFIX}${now - 60_000}-b.zip").apply { writeText("x") }
        val other = File(tmp.root, "image-cache").apply { writeText("x") }
        assertEquals(1, sweepBackupTemp(tmp.root, now = now))
        assertFalse(old.exists())
        assertTrue(fresh.exists() && other.exists())
    }

    /** The cross-shell fixture the web reads (`android-fixture.vitest.ts`). */
    @Test
    fun `an Android-streamed backup with a photo reads back, and is the committed fixture's kind`() {
        val bean = coffee.crema.beans.newBean("Kenya", "r1", 4, null, now).copy(id = "bean-1")
        val photo = File(tmp.root, "photo").apply { writeBytes(ByteArray(4096) { ((it * 7919 + 13) % 251).toByte() }) }
        val built = stream(snapshot(listOf(shot(0)), listOf(bean)), listOf("bean-1" to photo))
        System.getenv("CREMA_WRITE_FIXTURE")?.takeIf { it == "1" }?.let {
            File("../../web/src/lib/backup/fixtures/android-streamed-photo.crema.zip").apply { parentFile.mkdirs() }
                .writeBytes(built.file.readBytes())
        }
        for (file in listOf(built.file, File("../../web/src/lib/backup/fixtures/android-streamed-photo.crema.zip"))) {
            val zip = entries(file)
            assertEquals(listOf("backup.jsonl", "images/bean-1"), zip.keys.toList(), file.path)
            assertTrue(zip.getValue("backup.jsonl").decodeToString().lines().any { "\"kind\":\"shot\"" in it })
            assertTrue(zip.getValue("images/bean-1").contentEquals(photo.readBytes()))
        }
    }
}
