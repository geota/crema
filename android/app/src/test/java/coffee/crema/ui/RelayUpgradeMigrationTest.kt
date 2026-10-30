package coffee.crema.ui

import coffee.crema.ble.proxy.RelayKeyStore
import coffee.crema.ble.proxy.RelayUpgradeMigration
import coffee.crema.ble.proxy.RelayUpgradeMigration.Outcome
import coffee.crema.security.AndroidKeystoreCipher
import coffee.crema.security.ScriptedCipher
import coffee.crema.security.SecretBox
import coffee.crema.security.SoftKeySource
import java.io.File
import java.nio.file.Files
import java.security.KeyStoreException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store half of AC-38 (spec §5.1, §6.4): the v3 pairing state is deleted,
 * every other preference and the role are kept, and the flags the UI needs are
 * recorded in the v4 store. (The status line, the banner and "no dial" are
 * PR-3b/PR-5 wiring.) Not run while the relay keys are unavailable.
 */
class RelayUpgradeMigrationTest {
    private val dir: File = Files.createTempDirectory("relay-migration").toFile()
    private val keySource = SoftKeySource()
    private val cipher = ScriptedCipher(AndroidKeystoreCipher(RelayKeyStore.RELAY_ALIAS, RelayKeyStore.RELAY_AAD, keySource))
    private val migration = RelayUpgradeMigration(dir, coreJson)

    private val prefsFile get() = File(dir, "prefs.json")
    private val pairingFile get() = File(dir, RelayUpgradeMigration.V3_PAIRING_FILE)
    private val deviceIdFile get() = File(dir, RelayUpgradeMigration.V3_DEVICE_ID_FILE)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun loadedStore() = RelayKeyStore(dir, SecretBox(cipher)).also { it.load() }

    private fun prefs(role: String, pairedDevices: String = """[{"id":"abc","name":"Pixel","canControl":true}]""") =
        """{"common":{"baristaName":"Ana"},"proxyRole":"$role","proxyPrimaryHost":"192.168.1.20",""" +
            """"proxyPrimaryPort":41234,"relayPort":41235,"replayPrimary":false,"pairedDevices":$pairedDevices}"""

    private fun v3(role: String) {
        prefsFile.writeText(prefs(role))
        pairingFile.writeText("""{"secrets":{"abc":"sb1:AAAA"},"hostId":"h"}""")
        File(pairingFile.path + ".bak").writeText("old")
        File(pairingFile.path + ".tmp").writeText("half")
        deviceIdFile.writeText("0123456789abcdef")
    }

    private fun prefsObject(): JsonObject = Json.parseToJsonElement(prefsFile.readText()).jsonObject

    private fun assertV3Gone() {
        for (name in listOf("proxy-pairing.json", "proxy-pairing.json.bak", "proxy-pairing.json.tmp", "deviceId")) {
            assertFalse("$name left behind", File(dir, name).exists())
        }
        assertFalse(prefsObject().containsKey("pairedDevices"))
    }

    /** Every key but `pairedDevices` is kept as it was. */
    private fun assertOtherPrefsKept(role: String) {
        val expected = Json.parseToJsonElement(prefs(role)).jsonObject - "pairedDevices"
        assertEquals(expected, prefsObject().toMap())
    }

    @Test
    fun `AC-38 a v3 remote loses its pairing state, keeps its role and needs to pair again`() {
        v3("secondary")
        val store = loadedStore()
        assertEquals(Outcome.MIGRATED, migration.run(store))
        assertV3Gone()
        assertOtherPrefsKept("secondary")
        assertTrue(store.needsRepair)
        assertFalse(store.upgradeNotice)
        // Recorded in the v4 file, so it survives a restart.
        assertTrue(loadedStore().needsRepair)
    }

    @Test
    fun `AC-38 a v3 hub keeps hosting and gets the one-time banner`() {
        v3("primary")
        val store = loadedStore()
        assertEquals(Outcome.MIGRATED, migration.run(store))
        assertV3Gone()
        assertOtherPrefsKept("primary")
        assertTrue(store.upgradeNotice)
        assertFalse(store.needsRepair)
    }

    @Test
    fun `a v3 normal device just loses the files`() {
        v3("normal")
        val store = loadedStore()
        assertEquals(Outcome.MIGRATED, migration.run(store))
        assertV3Gone()
        assertOtherPrefsKept("normal")
        assertFalse(store.needsRepair || store.upgradeNotice)
    }

    @Test
    fun `nothing to migrate changes nothing`() {
        prefsFile.writeText(prefs("secondary").replace(""","pairedDevices":[{"id":"abc","name":"Pixel","canControl":true}]""", ""))
        val before = prefsFile.readBytes()
        val store = loadedStore()
        assertEquals(Outcome.NOTHING_TO_MIGRATE, migration.run(store))
        assertArrayEquals(before, prefsFile.readBytes())
        assertFalse(store.needsRepair)
        // And with no prefs at all.
        prefsFile.delete()
        assertEquals(Outcome.NOTHING_TO_MIGRATE, migration.run(store))
        assertFalse(prefsFile.exists())
    }

    @Test
    fun `a second run does nothing`() {
        v3("secondary")
        val store = loadedStore()
        migration.run(store)
        store.clearUpgradeFlags() // the user paired again
        val after = prefsFile.readBytes()
        assertEquals(Outcome.NOTHING_TO_MIGRATE, migration.run(store))
        assertArrayEquals(after, prefsFile.readBytes())
        assertFalse(store.needsRepair)
    }

    @Test
    fun `an empty pairedDevices list is dropped without raising a flag`() {
        prefsFile.writeText(prefs("secondary", pairedDevices = "[]"))
        val store = loadedStore()
        assertEquals(Outcome.MIGRATED, migration.run(store))
        assertFalse(prefsObject().containsKey("pairedDevices"))
        assertOtherPrefsKept("secondary")
        assertFalse(store.needsRepair)
    }

    @Test
    fun `a leftover v3 copy alone still counts as v3`() {
        prefsFile.writeText(prefs("secondary", pairedDevices = "[]").replace(""","pairedDevices":[]""", ""))
        File(pairingFile.path + ".bak").writeText("old")
        val store = loadedStore()
        assertEquals(Outcome.MIGRATED, migration.run(store))
        assertFalse(File(pairingFile.path + ".bak").exists())
        assertTrue(store.needsRepair)
    }

    @Test
    fun `with the relay keys unavailable nothing is touched`() {
        v3("secondary")
        val snapshot = dir.listFiles()!!.associate { it.name to it.readBytes() }
        // A store that never loaded, and one whose load hit a transient keystore error.
        val neverLoaded = RelayKeyStore(dir, SecretBox(cipher))
        assertEquals(Outcome.DEFERRED, migration.run(neverLoaded))
        loadedStore() // writes relay-v4.json
        keySource.failure = KeyStoreException("not ready")
        val unavailable = RelayKeyStore(dir, SecretBox(cipher)).also { it.load() }
        assertFalse(unavailable.isLoaded)
        assertEquals(Outcome.DEFERRED, migration.run(unavailable))
        for ((name, bytes) in snapshot) assertArrayEquals(name, bytes, File(dir, name).readBytes())
        // The next start, with the keystore back, migrates.
        keySource.failure = null
        assertEquals(Outcome.MIGRATED, migration.run(loadedStore()))
        assertV3Gone()
    }

    @Test
    fun `unreadable prefs still remove the v3 files and leave prefs alone`() {
        v3("secondary")
        prefsFile.writeText("{not json")
        val store = loadedStore()
        assertEquals(Outcome.MIGRATED, migration.run(store))
        assertFalse(pairingFile.exists())
        assertFalse(deviceIdFile.exists())
        assertEquals("{not json", prefsFile.readText())
    }
}
