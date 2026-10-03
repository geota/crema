package coffee.crema.persist

import android.content.ContextWrapper
import coffee.crema.history.HistoryStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Crash-safe saves and the Absent / Loaded / Corrupt load. */
class SafeFileTest {
    @get:Rule val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private fun target() = File(tmp.root, "shots.json")
    private fun decodeList(text: String): List<Int> = json.decodeFromString(text)

    @Before fun reset() {
        SafeFile.resetForTest()
        KeptAsideNotices.drain()
    }

    @After fun cleanup() = SafeFile.resetForTest()

    @Test
    fun missingFileIsAbsent() {
        assertIs<SafeLoad.Absent>(SafeFile.load(target(), decode = ::decodeList))
    }

    @Test
    fun savedFileLoadsAndKeepsThePreviousAsBackup() {
        SafeFile.write(target(), "[1]")
        SafeFile.write(target(), "[1,2]")
        assertEquals(SafeLoad.Loaded(listOf(1, 2)), SafeFile.load(target(), decode = ::decodeList))
        assertEquals("[1]", SafeFile.backupOf(target()).readText())
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun truncatedJsonIsCorruptMovedAsideAndSurvivesTheNextSave() {
        target().writeText("""[1,2,3,4""") // a save killed mid-write by the old code
        val outcome = SafeFile.load(target(), decode = ::decodeList)
        assertIs<SafeLoad.Corrupt>(outcome)
        val kept = assertNotNull(outcome.keptAt)
        assertTrue(kept.name.startsWith("shots.json.corrupt-"))
        assertEquals("[1,2,3,4", kept.readText())
        assertTrue(!target().exists())

        // The store now starts empty and saves: the damaged bytes are untouched.
        SafeFile.write(target(), "[]")
        assertEquals("[1,2,3,4", kept.readText())
        assertEquals(listOf(kept), SafeFile.keptAside(target()))
    }

    @Test
    fun failureBetweenWriteAndRenameNeverLeavesAPartialTarget() {
        SafeFile.write(target(), "[1,2,3]")
        SafeFile.beforeRename = { throw IOException("killed") }
        assertFailsWith<IOException> { SafeFile.write(target(), "[9,9,9,9,9]") }
        assertEquals("[1,2,3]", target().readText())
        assertTrue(!File(tmp.root, "shots.json.tmp").exists())
        SafeFile.beforeRename = {}
        assertEquals(SafeLoad.Loaded(listOf(1, 2, 3)), SafeFile.load(target(), decode = ::decodeList))
    }

    @Test
    fun crashBetweenTheTwoRenamesRecoversTheBackup() {
        SafeFile.write(target(), "[1,2]")
        // Process died after target → .bak and before .tmp → target.
        SafeFile.write(target(), "[1,2,3]")
        target().renameTo(File(tmp.root, "shots.json.tmp"))
        File(tmp.root, "shots.json.tmp").writeText("[1,2,") // and the temp is partial
        val outcome = SafeFile.load(target(), decode = ::decodeList)
        assertEquals(SafeLoad.Loaded(listOf(1, 2), recoveredFrom = "shots.json.bak"), outcome)
        assertEquals("[1,2]", target().readText()) // re-materialised
    }

    @Test
    fun crashBetweenTheTwoRenamesPrefersTheCompleteNewerTemp() {
        SafeFile.write(target(), "[1,2]")
        // Process died after target → .bak, with the new save fully synced in .tmp.
        target().renameTo(SafeFile.backupOf(target()))
        File(tmp.root, "shots.json.tmp").writeText("[1,2,3]")
        val outcome = SafeFile.load(target(), decode = ::decodeList)
        assertEquals(SafeLoad.Loaded(listOf(1, 2, 3), recoveredFrom = "shots.json.tmp"), outcome)
        assertEquals("[1,2,3]", target().readText())
    }

    @Test
    fun corruptTargetFallsBackToTheBackup() {
        SafeFile.write(target(), "[7]")
        SafeFile.write(target(), "[7,8]")
        target().writeText("{garbage")
        val outcome = SafeFile.load(target(), decode = ::decodeList)
        assertEquals(SafeLoad.Loaded(listOf(7), recoveredFrom = "shots.json.bak"), outcome)
        assertEquals(1, SafeFile.keptAside(target()).size) // the bad one is still kept
    }

    @Test
    fun completeLeftoverTempIsUsedWhenNothingElseIs() {
        File(tmp.root, "shots.json.tmp").writeText("[5]")
        assertEquals(
            SafeLoad.Loaded(listOf(5), recoveredFrom = "shots.json.tmp"),
            SafeFile.load(target(), decode = ::decodeList),
        )
    }

    @Test
    fun rotationKeepsAtMostThreeCorruptCopies() {
        repeat(5) { i ->
            target().writeText("broken $i")
            assertIs<SafeLoad.Corrupt>(SafeFile.load(target(), decode = ::decodeList))
        }
        val kept = SafeFile.keptAside(target())
        assertEquals(SafeFile.MAX_KEPT, kept.size)
        assertEquals(listOf("broken 4", "broken 3", "broken 2"), kept.map { it.readText() })
    }

    @Test
    fun aCorruptFileThatCantBeKeptAsideIsNeverOverwritten() {
        val dir = tmp.newFolder("ro")
        val t = File(dir, "prefs.json").apply { writeText("{bad") }
        dir.setWritable(false)
        try {
            assertIs<SafeLoad.Corrupt>(SafeFile.load(t) { json.decodeFromString<Map<String, Int>>(it) })
            assertFailsWith<IOException> { SafeFile.write(t, "{}") }
            assertEquals("{bad", t.readText())
        } finally {
            dir.setWritable(true)
        }
    }

    @Test
    fun historyStoreReportsCorruptAndDoesNotWipeTheShots() = runTest {
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = tmp.root
        }
        target().writeText("""[{"id":"a","ts""")
        val store = HistoryStore(context, json)
        assertEquals(emptyList(), store.load())
        val notices = KeptAsideNotices.drain()
        assertEquals(listOf("shot history"), notices.map { it.what })
        assertEquals(
            "Couldn't read your shot history; the damaged file was kept aside.",
            KeptAsideNotices.message(notices),
        )
        store.save(emptyList())
        assertEquals("""[{"id":"a","ts""", assertNotNull(notices.single().keptAt).readText())
    }

    @Test
    fun noticeNamesEveryStore() {
        val f = File("x")
        assertEquals(
            "Couldn't read your shot history, bean library and settings; the damaged files were kept aside.",
            KeptAsideNotices.message(
                listOf(KeptAside("shot history", f), KeptAside("bean library", f), KeptAside("settings", f)),
            ),
        )
    }
}
