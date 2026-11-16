package coffee.crema

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * AC-30 (backup part): the credential files never leave the device. Both
 * sections of `data_extraction_rules.xml` (cloud backup and device transfer)
 * exclude the relay v4 key store with its SafeFile siblings and the
 * `relay-lost/` directory, alongside the account-token files.
 */
class BackupExclusionTest {
    private val rules = File("src/main/res/xml/data_extraction_rules.xml")

    private fun excludedFiles(section: String): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rules)
        val node = doc.getElementsByTagName(section).item(0) as Element
        val excludes = node.getElementsByTagName("exclude")
        return (0 until excludes.length)
            .map { excludes.item(it) as Element }
            .filter { it.getAttribute("domain") == "file" }
            .map { it.getAttribute("path") }
            .toSet()
    }

    private fun withSiblings(name: String) = listOf(name, "$name.bak", "$name.tmp")

    @Test
    fun `AC-30 the relay key store and relay-lost are excluded from backup and transfer`() {
        val required = withSiblings("relay-v4.json") + "relay-lost/"
        for (section in listOf("cloud-backup", "device-transfer")) {
            val excluded = excludedFiles(section)
            for (path in required) assertTrue("$section doesn't exclude $path", path in excluded)
        }
    }

    /** #310 P3a: the history's store moves with a device transfer, as `shots.json` did. */
    @Test
    fun `the shot history's store, marker, snapshot and journal transfer with the device`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rules)
        val node = doc.getElementsByTagName("device-transfer").item(0) as Element
        val excludes = node.getElementsByTagName("exclude")
        val all = (0 until excludes.length).map { excludes.item(it) as Element }
        for (e in all) {
            val path = e.getAttribute("path")
            assertTrue("device transfer excludes ${e.getAttribute("domain")}:$path", !path.startsWith("shots") && !path.startsWith("history-") && path != "shot-series/")
            assertTrue("device transfer excludes the whole database domain", !(e.getAttribute("domain") == "database" && (path.isEmpty() || path == "." || path.startsWith("shots.db"))))
        }
    }

    /** #310 A5: after the flip `history-sqlite/` holds the truth, and its marker sits beside it; both move with the device. */
    @Test
    fun `history-sqlite and its marker transfer with the device`() {
        val kept = listOf(
            "history-sqlite/history.db", "history-sqlite/history.db-wal", "history-sqlite/history-2.db",
            "history-sqlite/done.json", "history-sqlite/pending.jsonl", "history-sqlite/history-snapshot.db",
            "history-sqlite.json", "history-sqlite.json.bak",
        )
        for (section in listOf("cloud-backup", "device-transfer")) {
            val excluded = excludedFiles(section)
            for (path in kept) {
                val by = excluded.firstOrNull { e -> path == e || path.startsWith(e.trimEnd('/') + "/") }
                assertTrue("$section excludes $path (by $by)", by == null)
            }
        }
    }

    @Test
    fun `the account tokens and the v3 pairing file stay excluded`() {
        val required = listOf("visualizer.json", "decent.json", "drive.json", "proxy-pairing.json").flatMap(::withSiblings)
        for (section in listOf("cloud-backup", "device-transfer")) {
            val excluded = excludedFiles(section)
            for (path in required) assertTrue("$section doesn't exclude $path", path in excluded)
        }
    }
}
