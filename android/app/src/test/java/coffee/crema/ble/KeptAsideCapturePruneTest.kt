package coffee.crema.ble

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coffee.crema.persist.SafeFile
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #578 follow-up: a replay capture that didn't parse is renamed aside as
 * `session-*.jsonl.corrupt-<ms>` (#576), so it's no session and the session
 * limit never removed it. Where the recorder prunes, only the newest
 * [SafeFile.MAX_KEPT] of those stay, in both capture directories, as for a
 * store's kept-aside copies; the sessions themselves are left to their limit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class KeptAsideCapturePruneTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val external get() = File(app.getExternalFilesDir(null), "captures")
    private val internal get() = File(app.filesDir, "captures")

    @Before fun clean() {
        external.deleteRecursively()
        internal.deleteRecursively()
        external.mkdirs()
        internal.mkdirs()
    }

    @After fun tidy() {
        external.deleteRecursively()
        internal.deleteRecursively()
    }

    private fun recorder(settled: Boolean) = BleSessionRecorder(app).apply {
        sessionsKeepOf = { 10 }
        historySettled = { settled }
        sessionsToDrop = { coffee.crema.core.sessionsToDrop(it) }
    }

    /** Five kept-aside captures in [dir], 1 to 5 ms, each its own capture's; the session files beside them. */
    private fun planted(dir: File): Pair<List<File>, List<File>> {
        val kept = (1..5).map { i -> File(dir, "session-20261120-09000$i.jsonl.corrupt-$i").apply { writeBytes(ByteArray(64)) } }
        val sessions = (1..2).map { i -> File(dir, "session-20261121-09000$i.jsonl").apply { writeText("{}") } }
        return kept to sessions
    }

    private fun keptIn(dir: File) = dir.listFiles { f -> ".corrupt-" in f.name }.orEmpty().map { it.name }.toSet()

    @Test fun `a new session keeps only the newest three kept-aside captures in each directory`() {
        val (outside, sessions) = planted(external)
        val (inside, _) = planted(internal)
        val r = recorder(settled = true)

        assertTrue(r.open() != null, "recording started")
        r.close()

        assertEquals(outside.takeLast(SafeFile.MAX_KEPT).map { it.name }.toSet(), keptIn(external))
        assertEquals(inside.takeLast(SafeFile.MAX_KEPT).map { it.name }.toSet(), keptIn(internal))
        assertTrue(sessions.all { it.exists() }, "sessions within the limit stay")
    }

    @Test fun `lowering the limit prunes them too, before the history has said which shots are pinned`() {
        val (outside, sessions) = planted(external)

        assertEquals(0, recorder(settled = false).pruneTo(1), "no session deleted before the history settles")

        assertEquals(outside.takeLast(SafeFile.MAX_KEPT).map { it.name }.toSet(), keptIn(external))
        assertTrue(sessions.all { it.exists() })
    }
}
