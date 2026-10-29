package coffee.crema.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Final review 05 N-2: BLE session captures on external storage are bounded
 * (newest N, nothing older than the age limit) and Erase all data deletes
 * them.
 */
class BleCapturePruneTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun capture(name: String, ageMs: Long): File =
        File(tmp.root, name).apply { writeText("{}"); setLastModified(now - ageMs) }

    @Test
    fun keepsOnlyTheNewestFiles() {
        val files = (0 until 15).map { capture("session-$it.jsonl", ageMs = it * 60_000L) }
        val other = capture("notes.txt", ageMs = 99 * day)

        val deleted = BleSessionRecorder.pruneCaptures(tmp.root, keep = 9, maxAgeMs = 14 * day, nowMs = now)

        assertEquals(6, deleted.size)
        assertTrue(files.take(9).all { it.exists() })
        assertTrue(files.drop(9).none { it.exists() })
        assertTrue("only session captures are touched", other.exists())
    }

    @Test
    fun dropsFilesPastTheAgeLimitEvenUnderTheCount() {
        val fresh = capture("session-fresh.jsonl", ageMs = day)
        val stale = capture("session-stale.jsonl", ageMs = 15 * day)

        BleSessionRecorder.pruneCaptures(tmp.root, keep = 9, maxAgeMs = 14 * day, nowMs = now)

        assertTrue(fresh.exists())
        assertFalse(stale.exists())
    }

    @Test
    fun theCapLeavesRoomForTheNewSession() {
        assertEquals(10, BleSessionRecorder.MAX_KEPT)
        assertEquals(14 * day, BleSessionRecorder.MAX_AGE_MS)
    }

    @Test
    fun eraseDeletesEveryCaptureAndTheDirectory() {
        val dir = File(tmp.root, "captures").apply { mkdirs() }
        File(dir, "session-1.jsonl").writeText("{}")
        File(dir, "session-2.jsonl").writeText("{}")

        assertEquals(2, BleSessionRecorder.eraseCapturesIn(dir))
        assertFalse(dir.exists())
        assertEquals(0, BleSessionRecorder.eraseCapturesIn(dir)) // a missing dir is fine
    }
}
