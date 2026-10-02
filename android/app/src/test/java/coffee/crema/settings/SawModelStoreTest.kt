package coffee.crema.settings

import coffee.crema.core.SawModelLoad
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The SAW-model seed: absent vs valid vs corrupt, and the quarantine write. */
class SawModelStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = SawModelStore(tmp.root)
    private fun stored() = File(tmp.root, SawModelStore.FILE_NAME)
    private fun quarantined() = File(tmp.root, SawModelStore.QUARANTINE_FILE_NAME)

    @Test
    fun absentHandsTheCoreNullAndWritesNothing() = runTest {
        val seen = mutableListOf<String?>()
        val logs = mutableListOf<String>()
        val outcome = store().seed({ seen += it; """{"type":"Absent"}""" }, { logs += it })
        assertIs<SawModelLoad.Absent>(outcome)
        assertEquals(listOf<String?>(null), seen)
        assertFalse(quarantined().exists())
        assertTrue(logs.isEmpty())
    }

    @Test
    fun validBlobLoadsAndStaysPut() = runTest {
        stored().writeText("""{"pairHistory":{}}""")
        val seen = mutableListOf<String?>()
        val outcome = store().seed({ seen += it; """{"type":"Loaded"}""" }, {})
        assertIs<SawModelLoad.Loaded>(outcome)
        assertEquals(listOf<String?>("""{"pairHistory":{}}"""), seen)
        assertTrue(stored().exists())
        assertFalse(quarantined().exists())
    }

    @Test
    fun corruptBlobIsQuarantinedVerbatimThenTheStoreIsCleared() = runTest {
        val bad = """{"pairHistory":{"p::s":[{"drip":1.35,"""
        stored().writeText(bad)
        val logs = mutableListOf<String>()
        // Shaped like the real bridge's JSON for SawModelLoad::Corrupt.
        val coreAnswer =
            """{"type":"Corrupt","content":{"raw":${JsonPrimitive(bad)},"error":"EOF while parsing"}}"""

        val outcome = store().seed({ coreAnswer }, { logs += it })

        assertIs<SawModelLoad.Corrupt>(outcome)
        assertEquals(bad, quarantined().readText())
        assertFalse(stored().exists())
        assertEquals(1, logs.size)
        assertTrue("quarantined" in logs.single())
        // The next launch starts from Absent, not the same corruption again.
        assertNull(store().load())
    }

    @Test
    fun newestCaptureWins() = runTest {
        quarantined().writeText("older")
        val answer = """{"type":"Corrupt","content":{"raw":"{newer","error":"x"}}"""
        store().seed({ answer }, {})
        assertEquals("{newer", quarantined().readText())
    }

    @Test
    fun unreadableBridgeAnswerDecodesAsAbsent() {
        assertIs<SawModelLoad.Absent>(SawModelStore.decode("not json"))
    }
}
