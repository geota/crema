package coffee.crema.security

import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.util.Base64
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The byte-oriented, strictly classified [SecretBox.wrapBytes] / [SecretBox.openBytes]
 * (relay v4 spec §5.1), run through the real AES-256-GCM code of
 * [AndroidKeystoreCipher] with a software key in place of AndroidKeyStore.
 */
class SecretBoxBytesTest {
    private val relayAad = "relay-v4 static".toByteArray()
    private val secret = ByteArray(32) { (it * 7 + 1).toByte() }

    private fun box(keys: SecretKeySource, aad: ByteArray = relayAad) =
        SecretBox(AndroidKeystoreCipher("test.alias", aad, keys))

    private fun opened(result: SecretBox.OpenedBytes): ByteArray =
        (result as SecretBox.OpenedBytes.Value).bytes

    private fun failure(result: SecretBox.OpenedBytes) = result as SecretBox.OpenedBytes.Failed

    @Test
    fun `wrapBytes seals with the sb1 prefix and openBytes round-trips`() {
        val keys = SoftKeySource()
        val sealed = box(keys).wrapBytes(secret)
        assertTrue(sealed.startsWith(SecretBox.PREFIX))
        val blob = Base64.getDecoder().decode(sealed.removePrefix(SecretBox.PREFIX))
        assertEquals(12 + secret.size + 16, blob.size)
        assertFalse(blob.toList().windowed(secret.size).any { it == secret.toList() })
        assertArrayEquals(secret, opened(box(keys).openBytes(sealed)))
    }

    @Test
    fun `the AAD binds the blob to its purpose`() {
        val keys = SoftKeySource()
        val sealed = box(keys, relayAad).wrapBytes(secret)
        // The account-token box (no AAD) and any other AAD can't open it: a tag failure, definitive.
        for (other in listOf(ByteArray(0), "relay-v4 statiC".toByteArray())) {
            val f = failure(box(keys, other).openBytes(sealed))
            assertEquals(KeystoreFailure.DEFINITIVE, f.kind)
            assertEquals(AEADBadTagException::class.java.simpleName, f.reason)
        }
    }

    @Test
    fun `a missing key, another key or a tampered blob is definitive`() {
        val keys = SoftKeySource()
        val sealed = box(keys).wrapBytes(secret)
        assertEquals(KeystoreFailure.DEFINITIVE, failure(box(SoftKeySource(key = null)).openBytes(sealed)).kind)
        assertEquals("KeystoreKeyMissingException", failure(box(SoftKeySource(key = null)).openBytes(sealed)).reason)
        val otherKey = SoftKeySource(SoftKeySource.newKey())
        assertEquals(KeystoreFailure.DEFINITIVE, failure(box(otherKey).openBytes(sealed)).kind)
        val blob = Base64.getDecoder().decode(sealed.removePrefix(SecretBox.PREFIX))
        blob[20] = (blob[20].toInt() xor 1).toByte()
        val tampered = SecretBox.PREFIX + Base64.getEncoder().encodeToString(blob)
        assertEquals(KeystoreFailure.DEFINITIVE, failure(box(keys).openBytes(tampered)).kind)
    }

    @Test
    fun `an unsealed value is definitive, never migrated`() {
        val plain = Base64.getEncoder().encodeToString(secret)
        val f = failure(box(SoftKeySource()).openBytes(plain))
        assertEquals(KeystoreFailure.DEFINITIVE, f.kind)
    }

    @Test
    fun `keystore errors and corrupt text are transient`() {
        val keys = SoftKeySource()
        val sealed = box(keys).wrapBytes(secret)
        keys.failure = KeyStoreException("system error")
        assertEquals(KeystoreFailure.TRANSIENT, failure(box(keys).openBytes(sealed)).kind)
        keys.failure = null
        assertEquals(KeystoreFailure.TRANSIENT, failure(box(keys).openBytes("${SecretBox.PREFIX}!!not base64!!")).kind)
        assertEquals(KeystoreFailure.TRANSIENT, failure(box(keys).openBytes("${SecretBox.PREFIX}AAAA")).kind)
    }

    @Test
    fun `sealing with an unusable keystore throws and never returns plaintext`() {
        val keys = SoftKeySource(failure = KeyStoreException("unusable"))
        val thrown = runCatching { box(keys).wrapBytes(secret) }.exceptionOrNull()
        assertTrue(thrown is GeneralSecurityException)
    }

    @Test
    fun `an opened value never prints its bytes`() {
        val keys = SoftKeySource()
        val value = box(keys).openBytes(box(keys).wrapBytes(secret))
        assertEquals("OpenedBytes.Value([redacted])", value.toString())
    }
}
