package coffee.crema.ble.proxy

import android.security.keystore.KeyPermanentlyInvalidatedException
import coffee.crema.core.RelayKeys
import coffee.crema.security.AndroidKeystoreCipher
import coffee.crema.security.KeystoreKeyMissingException
import coffee.crema.security.ScriptedCipher
import coffee.crema.security.SecretBox
import coffee.crema.security.SoftKeySource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyStoreException
import java.security.ProviderException
import java.util.Base64
import javax.crypto.AEADBadTagException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * AC-30 (store part): [RelayKeyStore] keeps the relay private key sealed under
 * its own alias and AAD, tells a transient keystore error (touch nothing, retry)
 * from a definitive loss (move aside, new key, no pins), and never persists
 * anything while the key is memory-only.
 *
 * The cipher is the real AES-256-GCM [AndroidKeystoreCipher] over a software key
 * ([SoftKeySource]), with the relay AAD; [ScriptedCipher] injects what a device
 * keystore may throw. Keys are real [RelayKeys] from the host core library.
 */
class RelayKeyStoreTest {
    private val dir: File = Files.createTempDirectory("relay-store").toFile()
    private val keySource = SoftKeySource()
    private val cipher = ScriptedCipher(AndroidKeystoreCipher(RelayKeyStore.RELAY_ALIAS, RelayKeyStore.RELAY_AAD, keySource))
    private var now = 1_700_000_000_000L

    private val storeFile get() = File(dir, RelayKeyStore.FILE_NAME)
    private val lostDir get() = File(dir, RelayKeyStore.LOST_DIR)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun store() = RelayKeyStore(dir, SecretBox(cipher)) { now }

    private fun ready(load: RelayKeyLoad): RelayKeyLoad.Ready = load as? RelayKeyLoad.Ready
        ?: throw AssertionError("expected Ready, got $load")

    private fun remote(id: String, name: String = "Pixel 8") =
        RelayRemotePin(id, Base64.getEncoder().encodeToString(ByteArray(32) { 1 }), name, "control", pairedAtMs = 5)

    private fun hub(id: String) =
        RelayHubPin(id, Base64.getEncoder().encodeToString(ByteArray(32) { 2 }), "Kitchen tablet", "192.168.1.20", 41234, 7)

    /** A store with a persisted key, a hub, a remote and a saved hub; returns its device id. */
    private fun seeded(): String {
        val s = store()
        val keys = ready(s.load()).keys
        s.pinHub(hub("h1"))
        s.pinRemote(remote("r1"))
        s.setSavedHub("h1")
        return keys.deviceId()
    }

    /** Every text that could carry the private key in clear. */
    private fun clearForms(keys: RelayKeys): List<String> {
        val raw = keys.privateForStorage()
        return listOf(
            Base64.getEncoder().encodeToString(raw),
            Base64.getUrlEncoder().withoutPadding().encodeToString(raw),
            raw.joinToString("") { "%02x".format(it) },
        ).also { raw.fill(0) }
    }

    private fun assertNoClearKeyOnDisk(keys: RelayKeys) {
        val forms = clearForms(keys)
        val raw = keys.privateForStorage()
        dir.walkTopDown().filter(File::isFile).forEach { f ->
            val bytes = f.readBytes()
            val text = String(bytes, Charsets.ISO_8859_1)
            for (form in forms) assertFalse("${f.name} holds the key in clear", text.contains(form))
            assertFalse("${f.name} holds the raw key", text.contains(String(raw, Charsets.ISO_8859_1)))
        }
        raw.fill(0)
    }

    // ---- sealing ------------------------------------------------------------

    @Test
    fun `AC-30 the private key is stored only sb1-sealed`() {
        val result = ready(store().load())
        assertTrue(result.persistent)
        assertFalse(result.keyReset)
        val file = Json.parseToJsonElement(storeFile.readText()).jsonObject
        val self = file["self"]!!.jsonObject
        assertTrue(self["private"]!!.jsonPrimitive.content.startsWith(SecretBox.PREFIX))
        assertEquals(result.keys.deviceId(), self["id"]!!.jsonPrimitive.content)
        assertEquals(RelayKeyStore.b64(result.keys.publicKey()), self["public"]!!.jsonPrimitive.content)
        assertNoClearKeyOnDisk(result.keys)
    }

    @Test
    fun `AC-30 the relay key has its own alias and AAD`() {
        assertEquals("crema.relay.v1", RelayKeyStore.RELAY_ALIAS)
        assertNotEquals("crema.secretbox.v1", RelayKeyStore.RELAY_ALIAS)
        assertArrayEquals("relay-v4 static".toByteArray(Charsets.US_ASCII), RelayKeyStore.RELAY_AAD)
        // The account-token box (same key, no AAD) can't open the relay key.
        ready(store().load())
        val sealed = Json.parseToJsonElement(storeFile.readText()).jsonObject["self"]!!.jsonObject["private"]!!
            .jsonPrimitive.content
        val tokenBox = SecretBox(AndroidKeystoreCipher(RelayKeyStore.RELAY_ALIAS, ByteArray(0), keySource))
        assertTrue(tokenBox.openBytes(sealed) is SecretBox.OpenedBytes.Failed)
    }

    @Test
    fun `AC-30 the key never passes through a String`() {
        val source = File("src/main/java/coffee/crema/ble/proxy/RelayKeyStore.kt").readText()
        assertTrue(source.contains("box.wrapBytes("))
        assertTrue(source.contains("box.openBytes("))
        // The String API (`wrap`/`open`, with its plaintext migration) is never used.
        assertFalse(Regex("""\bbox\.(wrap|open|openOrNull)\(""").containsMatchIn(source))
    }

    @Test
    fun `AC-30 the same key reloads with its pins`() {
        val id = seeded()
        val again = store()
        val result = ready(again.load())
        assertEquals(id, result.keys.deviceId())
        assertTrue(result.persistent)
        assertFalse(result.keyReset)
        assertEquals(listOf("h1"), again.hubs.map { it.id })
        assertEquals(listOf("r1"), again.remotes.map { it.id })
        assertEquals("h1", again.savedHub)
        assertFalse(again.keyResetNotice)
    }

    // ---- transient: touch nothing ---------------------------------------------

    private fun assertTransientLeavesEverything(cause: String, inject: () -> Unit, heal: () -> Unit = {}) {
        val id = seeded()
        inject()
        val before = storeFile.takeIf(File::isFile)?.readBytes()
        val s = store()
        val result = s.load()
        assertTrue("$cause: $result", result is RelayKeyLoad.Unavailable)
        assertFalse(cause, s.isLoaded)
        assertArrayEquals(cause, before, storeFile.readBytes())
        assertFalse("$cause made $lostDir", lostDir.exists())
        assertFalse(cause, s.isPersistent)
        try {
            s.pinRemote(remote("r2"))
            fail("$cause: a write without keys must throw")
        } catch (_: IllegalStateException) {
        }
        assertArrayEquals(cause, before, storeFile.readBytes())
        // The next start, with the keystore back, finds the same key and pins.
        heal()
        val retry = store()
        assertEquals(cause, id, ready(retry.load()).keys.deviceId())
        assertEquals(cause, listOf("r1"), retry.remotes.map { it.id })
        assertEquals(cause, listOf("h1"), retry.hubs.map { it.id })
    }

    @Test
    fun `AC-30 transient keystore exceptions leave the file and every pin unchanged`() {
        val transient = listOf(
            KeyStoreException("keystore2 system error"),
            ProviderException("Keystore operation failed"),
            IOException("binder"),
            IllegalStateException("anything else"),
            // A definitive exception only as a cause is not proof (KeystoreFailures).
            ProviderException("wrapped", AEADBadTagException("inner")),
        )
        for (e in transient) {
            assertTransientLeavesEverything(e.toString(), { cipher.openFailure = e }, { cipher.openFailure = null })
            cleanUp()
            dir.mkdirs()
        }
    }

    @Test
    fun `AC-30 a keystore lookup error is transient`() {
        assertTransientLeavesEverything(
            "containsAlias throws",
            { keySource.failure = KeyStoreException("not ready") },
            { keySource.failure = null },
        )
    }

    @Test
    fun `AC-30 corrupt sealed text or an unreadable file is transient`() {
        fun rewriteSelfPrivate(value: String) {
            val obj = Json.parseToJsonElement(storeFile.readText()).jsonObject
            val self = JsonObject(obj["self"]!!.jsonObject + ("private" to JsonPrimitive(value)))
            storeFile.writeText(JsonObject(obj + ("self" to self)).toString())
        }
        var saved = ByteArray(0)
        assertTransientLeavesEverything(
            "corrupt base64",
            { saved = storeFile.readBytes(); rewriteSelfPrivate("${SecretBox.PREFIX}%%%") },
            { storeFile.writeBytes(saved) },
        )
        cleanUp(); dir.mkdirs()
        assertTransientLeavesEverything(
            "truncated json",
            { saved = storeFile.readBytes(); storeFile.writeBytes(saved.copyOf(saved.size / 2)) },
            { storeFile.writeBytes(saved) },
        )
        cleanUp(); dir.mkdirs()
        // An authenticated blob that isn't a 32-byte key: not a keystore verdict.
        assertTransientLeavesEverything(
            "sealed blob of the wrong length",
            { saved = storeFile.readBytes(); rewriteSelfPrivate(SecretBox(cipher).wrapBytes(ByteArray(31))) },
            { storeFile.writeBytes(saved) },
        )
    }

    @Test
    fun `AC-30 an unreadable store is transient and not replaced`() {
        storeFile.mkdirs() // exists, can't be read as a file
        val result = store().load()
        assertTrue(result is RelayKeyLoad.Unavailable)
        assertTrue(storeFile.isDirectory)
        assertFalse(lostDir.exists())
    }

    // ---- definitive: move aside, new key, no pins ------------------------------

    private fun assertDefinitiveResets(cause: String, inject: () -> Unit) {
        val oldId = seeded()
        inject()
        val before = storeFile.readBytes()
        now += 1
        val s = store()
        val result = ready(s.load())
        cipher.openFailure = null // a scripted failure hit the old blob only
        assertTrue(cause, result.keyReset)
        assertTrue(cause, result.persistent)
        assertNotEquals(cause, oldId, result.keys.deviceId())
        assertTrue(cause, s.hubs.isEmpty())
        assertTrue(cause, s.remotes.isEmpty())
        assertNull(cause, s.savedHub)
        assertTrue(cause, s.keyResetNotice)
        // The old file is kept, byte for byte, in relay-lost/.
        val lost = lostDir.listFiles()!!.toList()
        assertEquals(cause, listOf("${RelayKeyStore.FILE_NAME}.$now"), lost.map { it.name })
        assertArrayEquals(cause, before, lost.single().readBytes())
        assertNoClearKeyOnDisk(result.keys)
        // The new key reloads without another reset; the notice stays until cleared.
        val again = store()
        val reloaded = ready(again.load())
        assertEquals(cause, result.keys.deviceId(), reloaded.keys.deviceId())
        assertFalse(cause, reloaded.keyReset)
        assertTrue(cause, again.keyResetNotice)
        again.clearKeyResetNotice()
        assertFalse(cause, store().also { it.load() }.keyResetNotice)
    }

    private fun resetAll() {
        cleanUp()
        dir.mkdirs()
        cipher.openFailure = null
        keySource.failure = null
        keySource.key = null
    }

    @Test
    fun `AC-30 a missing alias is a definitive loss`() {
        assertDefinitiveResets("alias gone") { keySource.key = null }
    }

    @Test
    fun `AC-30 a tag mismatch is a definitive loss`() {
        // Another key under the alias (a reinstall that restored the file): the real GCM check fails.
        assertDefinitiveResets("other key") { keySource.key = SoftKeySource.newKey() }
        resetAll()
        assertDefinitiveResets("scripted AEADBadTagException") { cipher.openFailure = AEADBadTagException("tag") }
    }

    @Test
    fun `AC-30 an invalidated key is a definitive loss`() {
        assertDefinitiveResets("KeyPermanentlyInvalidatedException") {
            cipher.openFailure = KeyPermanentlyInvalidatedException()
        }
        resetAll()
        assertDefinitiveResets("KeystoreKeyMissingException") {
            cipher.openFailure = KeystoreKeyMissingException(RelayKeyStore.RELAY_ALIAS)
        }
    }

    @Test
    fun `AC-30 a plaintext private key reads as lost`() {
        assertDefinitiveResets("unsealed value") {
            val obj = Json.parseToJsonElement(storeFile.readText()).jsonObject
            val plain = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
            val self = JsonObject(obj["self"]!!.jsonObject + ("private" to JsonPrimitive(plain)))
            storeFile.writeText(JsonObject(obj + ("self" to self)).toString())
        }
    }

    // ---- sealing fails: memory only ------------------------------------------

    @Test
    fun `AC-30 a sealing failure keeps the key in memory and persists nothing`() {
        cipher.sealFailure = ProviderException("keystore unusable")
        val s = store()
        val result = ready(s.load())
        assertFalse(result.persistent)
        assertFalse(s.isPersistent)
        assertTrue(s.isLoaded)
        assertFalse(storeFile.exists())
        // Pairings made now work for this process but are not written.
        assertNull(s.pinRemote(remote("r1")))
        s.pinHub(hub("h1"))
        s.setSavedHub("h1")
        assertEquals(listOf("r1"), s.remotes.map { it.id })
        assertFalse(storeFile.exists())
        assertFalse(lostDir.exists())
        assertNoClearKeyOnDisk(result.keys)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `AC-30 a sealing failure after a definitive loss writes no key and no pins`() {
        seeded()
        keySource.key = null
        cipher.sealFailure = KeyStoreException("can't create")
        val s = store()
        val result = ready(s.load())
        assertTrue(result.keyReset)
        assertFalse(result.persistent)
        assertFalse(storeFile.exists())
        assertTrue(s.remotes.isEmpty())
        s.pinRemote(remote("r9"))
        assertFalse(storeFile.exists())
        assertEquals(1, lostDir.listFiles()!!.size)
        assertNoClearKeyOnDisk(result.keys)
    }

    // ---- pins ------------------------------------------------------------------

    @Test
    fun `re-pair returns the replaced record and restoreRemote puts it back`() {
        val s = store()
        ready(s.load())
        val first = remote("r1", "Old name")
        assertNull(s.pinRemote(first))
        val second = remote("r1", "New name")
        assertEquals(first, s.pinRemote(second))
        assertEquals(listOf(second), s.remotes)
        s.restoreRemote("r1", first)
        assertEquals(listOf(first), store().also { it.load() }.remotes)
        // A first pairing that fails rolls back to "not pinned".
        assertNull(s.pinRemote(remote("r2")))
        s.restoreRemote("r2", null)
        assertEquals(listOf("r1"), store().also { it.load() }.remotes.map { it.id })
    }

    @Test
    fun `hub and remote edits persist`() {
        seeded()
        val s = store()
        ready(s.load())
        s.touchRemote("r1", 42)
        s.touchHub("h1", 43)
        val reloaded = store().also { it.load() }
        assertEquals(42L, reloaded.remotes.single().lastSeenMs)
        assertEquals(43L, reloaded.hubs.single().lastSeenMs)
        s.unpinHub("h1")
        s.unpinRemote("r1")
        val emptied = store().also { it.load() }
        assertTrue(emptied.hubs.isEmpty())
        assertTrue(emptied.remotes.isEmpty())
        assertNull(emptied.savedHub)
    }

    @Test
    fun `resetKey makes a new key and deletes every pin`() {
        val oldId = seeded()
        val s = store()
        ready(s.load())
        val fresh = s.resetKey()!!
        assertNotEquals(oldId, fresh.deviceId())
        assertTrue(s.hubs.isEmpty() && s.remotes.isEmpty())
        assertNull(s.savedHub)
        val reloaded = store()
        assertEquals(fresh.deviceId(), ready(reloaded.load()).keys.deviceId())
        assertTrue(reloaded.remotes.isEmpty())
        assertFalse(lostDir.exists())
    }

    @Test
    fun `upgrade flags are raised, never cleared, until cleared explicitly`() {
        val s = store()
        ready(s.load())
        s.markUpgraded(needsRepair = true, upgradeNotice = false)
        s.markUpgraded(needsRepair = false, upgradeNotice = false)
        val reloaded = store().also { it.load() }
        assertTrue(reloaded.needsRepair)
        assertFalse(reloaded.upgradeNotice)
        reloaded.clearUpgradeFlags()
        assertFalse(store().also { it.load() }.needsRepair)
    }

    @Test
    fun `writes need a loaded key`() {
        val s = store()
        for (write in listOf<() -> Unit>({ s.pinHub(hub("h")) }, { s.setSavedHub("h") }, { s.markUpgraded(true, true) })) {
            try {
                write()
                fail("expected IllegalStateException")
            } catch (_: IllegalStateException) {
            }
        }
        assertNull(s.resetKey())
        assertFalse(storeFile.exists())
    }

    @Test
    fun `a later version's extra fields still load`() {
        seeded()
        val obj = Json.parseToJsonElement(storeFile.readText()).jsonObject
        storeFile.writeText(JsonObject(obj + ("future" to JsonPrimitive(1))).toString())
        val s = store()
        assertFalse(ready(s.load()).keyReset)
        assertEquals(listOf("r1"), s.remotes.map { it.id })
    }
}
