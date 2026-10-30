package coffee.crema.security

import android.content.ContextWrapper
import coffee.crema.ble.proxy.PairingStore
import coffee.crema.decent.DecentState
import coffee.crema.decent.DecentStore
import coffee.crema.drive.DriveState
import coffee.crema.drive.DriveStore
import coffee.crema.drive.DriveTokenSet
import coffee.crema.visualizer.TokenSet
import coffee.crema.visualizer.VisualizerAccount
import coffee.crema.visualizer.VisualizerState
import coffee.crema.visualizer.VisualizerStore
import java.io.File
import java.nio.file.Files
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account-token boxes keep their pre-relay-v4 key handling
 * ([LegacyKeystoreKeySource]; relay v4 PR-3a limits the strict rule and the
 * liveness probe to the relay key). Every token caller, through the real
 * AES-GCM cipher over a fake keystore:
 * - a permanently lost key ends as signed out ("sign in again"), with no
 *   exception and no rewrite loop on the next start;
 * - a keystore error while sealing fails the save (false, logged), leaves the
 *   stored token as it was, and the next save after the keystore recovers stores it.
 */
class TokenBoxCallersTest {
    private val dir: File = Files.createTempDirectory("token-box").toFile()
    private val context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val keystore = FakeKeystore()
    private val box = DeviceSecretBox.create(keystore)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun file(name: String) = File(dir, name)

    private fun loseKey() {
        keystore.keys.remove(DeviceSecretBox.TOKEN_ALIAS)
    }

    // ---- the token boxes keep the old key handling ------------------------------

    @Test
    fun `the token box still re-keys when its alias is listed without a key`() {
        val listedOnly = object : KeystoreBackend by keystore {
            override fun containsAlias(alias: String) = true
            override fun getKey(alias: String): SecretKey? = null
        }
        val legacy = DeviceSecretBox.create(listedOnly)
        assertTrue(SecretBox.isWrapped(legacy.wrap("token")))
        assertEquals(listOf(DeviceSecretBox.TOKEN_ALIAS), keystore.generated)
    }

    @Test
    fun `the token box uses the token alias, no AAD and no probe`() {
        box.wrap("token")
        assertEquals(setOf(DeviceSecretBox.TOKEN_ALIAS), keystore.keys.keys)
        assertEquals("crema.secretbox.v1", DeviceSecretBox.TOKEN_ALIAS)
    }

    // ---- Decent -------------------------------------------------------------------

    @Test
    fun `Decent - a lost key asks to sign in again, once`() = runBlocking {
        val store = DecentStore(context, json, box)
        assertTrue(store.save(DecentState(email = "a@b.c", token = "tok-123456", autoUpload = true)))
        loseKey()
        val loaded = store.load()
        assertNull(loaded.token)
        assertTrue(loaded.needsReauth)
        assertEquals("a@b.c", loaded.email)
        assertFalse(file(DECENT).readText().contains("sb1:"))
        // The next start reads the same signed-out state and writes nothing.
        val after = file(DECENT).readBytes()
        assertEquals(loaded, store.load())
        assertArrayEquals(after, file(DECENT).readBytes())
    }

    @Test
    fun `Decent - a keystore error while saving keeps the stored token and retries`() = runBlocking {
        val store = DecentStore(context, json, box)
        assertTrue(store.save(DecentState(email = "a@b.c", token = "old-token")))
        val before = file(DECENT).readBytes()
        val inMemory = store.load()
        keystore.failure = KeyStoreException("keystore2 system error")
        assertFalse(store.save(inMemory.copy(token = "new-token")))
        assertArrayEquals(before, file(DECENT).readBytes())
        keystore.failure = null
        assertEquals("old-token", store.load().token)
        assertTrue(store.save(inMemory.copy(token = "new-token")))
        assertEquals("new-token", store.load().token)
    }

    // ---- Visualizer ---------------------------------------------------------------

    private val signedIn = VisualizerState(
        tokens = TokenSet(accessToken = "acc", refreshToken = "ref", expiresAt = 9),
        account = VisualizerAccount("1", "me"),
    )

    @Test
    fun `Visualizer - a lost key signs out, once`() = runBlocking {
        val store = VisualizerStore(context, json, box)
        assertTrue(store.save(signedIn))
        loseKey()
        val loaded = store.load()
        assertNull(loaded.tokens)
        assertNull(loaded.account)
        assertFalse(file(VISUALIZER).readText().contains("sb1:"))
        val after = file(VISUALIZER).readBytes()
        assertEquals(loaded, store.load())
        assertArrayEquals(after, file(VISUALIZER).readBytes())
    }

    @Test
    fun `Visualizer - a keystore error while saving keeps the stored token and retries`() = runBlocking {
        val store = VisualizerStore(context, json, box)
        assertTrue(store.save(signedIn))
        val before = file(VISUALIZER).readBytes()
        keystore.failure = ProviderException("Keystore operation failed")
        val fresh = signedIn.copy(tokens = TokenSet(accessToken = "acc2", refreshToken = "ref2", expiresAt = 10))
        assertFalse(store.save(fresh))
        assertArrayEquals(before, file(VISUALIZER).readBytes())
        keystore.failure = null
        assertEquals("acc", store.load().tokens!!.accessToken)
        assertTrue(store.save(fresh))
        assertEquals("acc2", store.load().tokens!!.accessToken)
    }

    // ---- Drive and the v3 pairing store (same box) --------------------------------

    @Test
    fun `Drive - a lost key signs out and a sealing error keeps the token`() = runBlocking {
        val store = DriveStore(context, json, box)
        val tokens = DriveState(tokens = DriveTokenSet(accessToken = "g", refreshToken = "r", expiresAt = 1))
        assertTrue(store.save(tokens))
        val before = file(DRIVE).readBytes()
        keystore.failure = KeyStoreException("busy")
        assertFalse(store.save(tokens.copy(tokens = DriveTokenSet(accessToken = "g2", refreshToken = "r", expiresAt = 2))))
        assertArrayEquals(before, file(DRIVE).readBytes())
        keystore.failure = null
        assertEquals("g", store.load().tokens!!.accessToken)
        loseKey()
        assertNull(store.load().tokens)
        assertFalse(file(DRIVE).readText().contains("sb1:"))
    }

    @Test
    fun `v3 pairing store - a lost key forgets the secret and a sealing error stores nothing`() {
        val f = file(PairingStore.FILE_NAME)
        val store = PairingStore(f, box, json)
        assertTrue(store.putHeld("host", "secret"))
        keystore.failure = KeyStoreException("busy")
        assertFalse(store.putHeld("host2", "secret2"))
        keystore.failure = null
        assertEquals("secret", PairingStore(f, box, json).heldSecret("host"))
        loseKey()
        assertNull(PairingStore(f, box, json).heldSecret("host"))
    }

    private companion object {
        const val DECENT = "decent.json"
        const val VISUALIZER = "visualizer.json"
        const val DRIVE = "drive.json"
    }
}
