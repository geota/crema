package coffee.crema.persist

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Final review 05 N-1: Erase all data must delete a store's file AND every
 * copy of it. Saving an empty value through the normal path renames the full
 * old file to `.bak`, which `SafeFile.load` can even bring back.
 */
class SafeFileEraseTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun reset() = SafeFile.resetForTest()

    @After fun cleanup() = SafeFile.resetForTest()

    private fun names() = tmp.root.listFiles()!!.map { it.name }.sorted()

    @Test
    fun anEmptySaveKeepsTheOldFileAsBackupWhichIsTheBug() {
        val shots = File(tmp.root, "shots.json")
        SafeFile.write(shots, """[{"id":"old-shot","notes":"secret"}]""")
        SafeFile.write(shots, "[]") // what erase used to do
        assertTrue(SafeFile.backupOf(shots).readText().contains("secret"))
    }

    @Test
    fun eraseDeletesTheTargetAndEveryCopy() {
        val shots = File(tmp.root, "shots.json")
        SafeFile.write(shots, """[{"id":"a"}]""")
        SafeFile.write(shots, """[{"id":"a"},{"id":"b"}]""") // leaves shots.json.bak
        File(tmp.root, "shots.json.tmp").writeText("partial")
        File(tmp.root, "shots.json.corrupt-1700000000000").writeText("garbage")
        File(tmp.root, "shots.json.corrupt-1700000000001").writeText("garbage")
        File(tmp.root, "beanLibrary.json").writeText("{}")
        File(tmp.root, "beanLibrary.json.bak").writeText("{}")

        assertTrue(SafeFile.erase(shots))

        assertEquals(listOf("beanLibrary.json", "beanLibrary.json.bak"), names())
        assertTrue(SafeFile.load(shots) { it } is SafeLoad.Absent)
        assertTrue(SafeFile.erase(shots)) // idempotent
    }

    @Test
    fun eraseLiftsASaveBlockSoTheStoreCanBeWrittenAgain() {
        val target = File(tmp.root, "prefs.json")
        target.writeText("not json")
        // A load that can't move the bad file aside blocks saves to it.
        tmp.root.setWritable(false)
        try {
            SafeFile.load(target) { error("bad") }
        } finally {
            tmp.root.setWritable(true)
        }
        SafeFile.erase(target)
        SafeFile.write(target, "{}")
        assertEquals("{}", target.readText())
    }

    @Test
    fun eraseCopiesInSweepsEveryLeftoverCopyAndNothingElse() {
        listOf(
            "shots.json", "shots.json.bak", "prefs.json.tmp", "maintenance.json.corrupt-12",
            "deviceId", "stolenSerials.json",
        ).forEach { File(tmp.root, it).writeText("x") }
        File(tmp.root, "bean-images").mkdirs()
        File(tmp.root, "bean-images/a.bak").writeText("x") // not recursive

        assertEquals(emptyList<String>(), SafeFile.eraseCopiesIn(tmp.root))

        assertEquals(listOf("bean-images", "deviceId", "shots.json", "stolenSerials.json"), names())
        assertTrue(File(tmp.root, "bean-images/a.bak").exists())
    }
}
