package coffee.crema.persist

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Review 05 P3-5: credential files leave no `.bak` / `.tmp` / `.corrupt-*`
 * copies that the backup / device-transfer rules cannot name.
 */
class SafeFileCredentialTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun reset() = SafeFile.resetForTest()

    @After fun cleanup() = SafeFile.resetForTest()

    private fun target() = File(tmp.root, "decent.json")

    private fun names() = tmp.root.listFiles()!!.map { it.name }.sorted()

    @Test
    fun writeCredentialKeepsNoBackupOfThePreviousToken() {
        SafeFile.writeCredential(target(), """{"token":"old-session"}""")
        SafeFile.writeCredential(target(), """{"token":null}""") // sign-out
        assertEquals(listOf("decent.json"), names())
        assertEquals("""{"token":null}""", target().readText())
    }

    @Test
    fun writeCredentialDeletesLeftoversFromOlderBuilds() {
        File(tmp.root, "decent.json.bak").writeText("""{"token":"plaintext-legacy"}""")
        File(tmp.root, "decent.json.tmp").writeText("partial")
        File(tmp.root, "decent.json.corrupt-1700000000000").writeText("garbage")
        File(tmp.root, "shots.json.bak").writeText("unrelated")
        SafeFile.writeCredential(target(), """{"token":"sealed"}""")
        assertEquals(listOf("decent.json", "shots.json.bak"), names())
    }

    @Test
    fun purgeCopiesAfterLoadKeepsAJustReportedCorruptCopy() {
        target().writeText("{}")
        File(tmp.root, "decent.json.bak").writeText("""{"token":"previous"}""")
        File(tmp.root, "decent.json.corrupt-1").writeText("garbage")
        SafeFile.purgeCopies(target())
        assertFalse(File(tmp.root, "decent.json.bak").exists())
        assertEquals(listOf("decent.json", "decent.json.corrupt-1"), names())
    }
}
