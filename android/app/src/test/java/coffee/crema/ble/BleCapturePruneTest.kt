package coffee.crema.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * BLE session captures on external storage are bounded by the Settings limit
 * (#311): the newest N sessions, plus any holding a pinned shot, with no age
 * limit; Erase all data deletes them (final review 05 N-2).
 */
class BleCapturePruneTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun capture(name: String, ageMs: Long): File =
        File(tmp.root, name).apply { writeText("{}"); setLastModified(now - ageMs) }

    /** The core's rule, as the recorder calls it. */
    private val rule: (String) -> String = { coffee.crema.core.sessionsToDrop(it) }

    private fun prune(keep: Int, pinned: List<Long> = emptyList()): List<File> =
        BleSessionRecorder.doomed(tmp.root, keep, pinned, rule).onEach { it.delete() }

    @Test
    fun keepsOnlyTheNewestSessions() {
        val files = (0 until 15).map { capture("session-$it.jsonl", ageMs = it * 60_000L) }
        val other = capture("notes.txt", ageMs = 99 * day)

        val deleted = prune(keep = 9)

        assertEquals(6, deleted.size)
        assertTrue(files.take(9).all { it.exists() })
        assertTrue(files.drop(9).none { it.exists() })
        assertTrue("only session captures are touched", other.exists())
    }

    @Test
    fun noAgeLimit_onlyTheCountRemovesACapture() {
        val stale = capture("session-stale.jsonl", ageMs = 90 * day)
        prune(keep = 9)
        assertTrue("the 14-day limit is gone", stale.exists())
    }

    @Test
    fun aSessionHoldingAPinnedShotSurvivesOffAndALowerLimit() {
        // Named with its start; its span runs to its last write.
        val pinnedSession = File(tmp.root, "session-20231114-221320.jsonl").apply { writeText("{}") }
        val started = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).parse("20231114-221320")!!.time
        pinnedSession.setLastModified(started + 60 * 60_000L)
        val today = System.currentTimeMillis()
        val newer = (0 until 3).map { i ->
            File(tmp.root, "session-new$i.jsonl").apply { writeText("{}"); setLastModified(today - i * 60_000L) }
        }

        prune(keep = 1, pinned = listOf(started + 10 * 60_000L))
        assertTrue(pinnedSession.exists())
        assertEquals(1, newer.count { it.exists() })

        prune(keep = BleSessionRecorder.SESSIONS_OFF, pinned = listOf(started + 10 * 60_000L))
        assertTrue("Off keeps only pinned shots' sessions", pinnedSession.exists())
        assertTrue(newer.none { it.exists() })

        // Unpinned: it goes at the next prune.
        prune(keep = BleSessionRecorder.SESSIONS_OFF)
        assertFalse(pinnedSession.exists())
    }

    @Test
    fun allDeletesNothing() {
        val files = (0 until 20).map { capture("session-$it.jsonl", ageMs = it * 60_000L) }
        assertTrue(prune(keep = BleSessionRecorder.SESSIONS_ALL).isEmpty())
        assertTrue(files.all { it.exists() })
    }

    @Test
    fun newInstallsKeep50_existingOnesKeepTodays10() {
        assertEquals(50, BleSessionRecorder.NEW_INSTALL_SESSIONS_KEEP)
        assertEquals(10, BleSessionRecorder.EXISTING_SESSIONS_KEEP)
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
