package coffee.crema.ble.proxy

import coffee.crema.security.SecretBox
import coffee.crema.security.SecretCipher
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pairing secret, its proof, peer-name hygiene, and the sealed store. */
class PairingTest {

    @Test
    fun `a proof verifies only for the same secret, host, client and nonces`() {
        val secret = PairingCrypto.newSecret()
        val cn = PairingCrypto.newNonce()
        val hn = PairingCrypto.newNonce()
        val proof = PairingCrypto.clientProof(secret, "host-1", "phone-1", cn, hn)

        assertTrue(PairingCrypto.verifyClient(secret, "host-1", "phone-1", cn, hn, proof))
        assertFalse(PairingCrypto.verifyClient(PairingCrypto.newSecret(), "host-1", "phone-1", cn, hn, proof), "other secret")
        assertFalse(PairingCrypto.verifyClient(secret, "host-2", "phone-1", cn, hn, proof), "other host")
        assertFalse(PairingCrypto.verifyClient(secret, "host-1", "phone-2", cn, hn, proof), "other client")
        assertFalse(PairingCrypto.verifyClient(secret, "host-1", "phone-1", cn, PairingCrypto.newNonce(), proof), "replayed into a new session")
        assertFalse(PairingCrypto.verifyClient(secret, "host-1", "phone-1", PairingCrypto.newNonce(), hn, proof), "other client nonce")
        assertFalse(PairingCrypto.verifyClient(secret, "host-1", "phone-1", cn, hn, null), "no proof")
        assertFalse(PairingCrypto.verifyClient(secret, "host-1", "phone-1", cn, hn, "not-a-proof"), "garbage")
        assertFalse(PairingCrypto.verifyClient("%%%", "host-1", "phone-1", cn, hn, proof), "malformed secret")
    }

    @Test
    fun `host and device proofs can't stand in for each other`() {
        val secret = PairingCrypto.newSecret()
        val cn = PairingCrypto.newNonce()
        val hn = PairingCrypto.newNonce()
        val host = PairingCrypto.hostProof(secret, "host-1", "phone-1", cn, hn)
        val client = PairingCrypto.clientProof(secret, "host-1", "phone-1", cn, hn)
        assertTrue(PairingCrypto.verifyHost(secret, "host-1", "phone-1", cn, hn, host))
        assertFalse(PairingCrypto.verifyClient(secret, "host-1", "phone-1", cn, hn, host), "a host proof is not a device proof")
        assertFalse(PairingCrypto.verifyHost(secret, "host-1", "phone-1", cn, hn, client), "a device proof is not a host proof")
    }

    @Test
    fun `secrets and nonces are random`() {
        val secrets = List(50) { PairingCrypto.newSecret() }
        assertEquals(50, secrets.toSet().size)
        assertEquals(43, secrets.first().length, "32 bytes, base64url without padding")
        assertNotEquals(PairingCrypto.newNonce(), PairingCrypto.newNonce())
    }

    @Test
    fun `peer names are cleaned and clipped`() {
        assertEquals("Sams phone", sanitizePeerName("Sams\n phone"))
        assertEquals("ab", sanitizePeerName("a\u0000b\u2028"))
        assertEquals(32, sanitizePeerName("x".repeat(100)).length)
        assertEquals("Unnamed device", sanitizePeerName(" \t "))
    }

    private class FakeCipher : SecretCipher {
        var lost = false
        override fun seal(plain: ByteArray) = ByteArray(12) { 7 } + plain.map { (it.toInt() xor 0x5A).toByte() }
        override fun open(sealed: ByteArray): ByteArray {
            if (lost) throw GeneralSecurityException("key gone")
            return sealed.drop(12).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the store keeps secrets sealed on disk and survives a reload`() {
        val dir = Files.createTempDirectory("pairing").toFile()
        try {
            val file = File(dir, PairingStore.FILE_NAME)
            val cipher = FakeCipher()
            val store = PairingStore(file, SecretBox(cipher), json)
            val hostId = store.hostId
            assertTrue(hostId.isNotBlank())
            val issued = PairingCrypto.newSecret()
            val held = PairingCrypto.newSecret()
            assertTrue(store.putIssued("phone-1", issued))
            assertTrue(store.putHeld("host-9", held))

            val onDisk = file.readText()
            assertFalse(issued in onDisk || held in onDisk, "no plaintext secret on disk")

            val reloaded = PairingStore(file, SecretBox(cipher), json)
            assertEquals(hostId, reloaded.hostId, "the host id is stable")
            assertEquals(issued, reloaded.issuedSecret("phone-1"))
            assertEquals(held, reloaded.heldSecret("host-9"))
            assertEquals(setOf("phone-1"), reloaded.issuedIds())

            reloaded.removeIssued("phone-1")
            assertNull(PairingStore(file, SecretBox(cipher), json).issuedSecret("phone-1"))

            cipher.lost = true
            assertNull(PairingStore(file, SecretBox(cipher), json).heldSecret("host-9"), "a lost key means pair again")
        } finally {
            dir.deleteRecursively()
        }
    }
}
