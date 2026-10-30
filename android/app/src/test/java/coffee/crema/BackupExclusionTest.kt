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

    @Test
    fun `the account tokens and the v3 pairing file stay excluded`() {
        val required = listOf("visualizer.json", "decent.json", "drive.json", "proxy-pairing.json").flatMap(::withSiblings)
        for (section in listOf("cloud-backup", "device-transfer")) {
            val excluded = excludedFiles(section)
            for (path in required) assertTrue("$section doesn't exclude $path", path in excluded)
        }
    }
}
