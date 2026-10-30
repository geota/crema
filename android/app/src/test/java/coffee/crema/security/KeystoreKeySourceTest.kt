package coffee.crema.security

import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [AndroidKeystoreKeySource] believes "alias absent" (a definitive loss) only
 * after a liveness probe shows the keystore works; a keystore that isn't ready
 * yet (keystore2 at boot) is a transient [KeyStoreException], never a loss.
 */
class KeystoreKeySourceTest {
    private val ks = FakeKeystore()
    private val source = AndroidKeystoreKeySource("crema.relay.v1", ks)

    private fun assertNotReady(what: String, call: () -> Unit) {
        try {
            call()
            fail("$what: expected KeyStoreException")
        } catch (e: KeyStoreException) {
            assertEquals(what, KeystoreFailure.TRANSIENT, KeystoreFailures.classify(e))
        }
    }

    @Test
    fun `a present alias returns its key without probing`() {
        val key = SoftKeySource.newKey().also { ks.keys["crema.relay.v1"] = it }
        assertSame(key, source.existing())
        assertFalse(AndroidKeystoreKeySource.PROBE_ALIAS in ks.keys)
    }

    @Test
    fun `an absent alias in a working keystore is a loss, after the probe`() {
        assertNull(source.existing())
        assertTrue(AndroidKeystoreKeySource.PROBE_ALIAS in ks.keys)
        // The probe key is reused, not recreated, on the next check.
        assertNull(source.existing())
        assertEquals(listOf(AndroidKeystoreKeySource.PROBE_ALIAS), ks.generated)
    }

    @Test
    fun `a keystore listing no alias at all is not ready, never a loss`() {
        ks.keys["crema.relay.v1"] = SoftKeySource.newKey()
        ks.notReady = true
        assertNotReady("existing") { source.existing() }
        // And sealing doesn't write a new key over the real one.
        assertNotReady("getOrCreate") { source.getOrCreate() }
        assertFalse("crema.relay.v1" in ks.generated)
    }

    @Test
    fun `a probe that can't create its key is transient`() {
        ks.generateFailure = ProviderException("keystore2 system error")
        assertNotReady("generate fails") { source.existing() }
    }

    @Test
    fun `a probe key that doesn't work is transient`() {
        // Listed and returned, but unusable for AES-GCM.
        ks.keys[AndroidKeystoreKeySource.PROBE_ALIAS] = SecretKeySpec(ByteArray(3), "AES")
        assertNotReady("unusable probe key") { source.existing() }
    }

    @Test
    fun `a listed alias without a key is transient`() {
        val listedOnly = object : KeystoreBackend by ks {
            override fun containsAlias(alias: String) = true
            override fun getKey(alias: String) = null
        }
        assertNotReady("no key returned") { AndroidKeystoreKeySource("crema.relay.v1", listedOnly).existing() }
    }

    @Test
    fun `an alias that appears while probing is returned`() {
        val key = SoftKeySource.newKey()
        val starting = object : KeystoreBackend by ks {
            var first = true
            override fun containsAlias(alias: String): Boolean {
                if (alias == "crema.relay.v1" && first) {
                    first = false
                    return false
                }
                if (alias == "crema.relay.v1") ks.keys[alias] = key
                return ks.containsAlias(alias)
            }
        }
        assertSame(key, AndroidKeystoreKeySource("crema.relay.v1", starting).existing())
    }

    /**
     * Two threads call getOrCreate on their own source for the same absent alias
     * and meet right after seeing it absent. Without the per-alias lock both
     * would generate, and the second key would replace the first; with it, the
     * second waits (the barrier times out) and then finds the first's key.
     */
    private fun assertOneKeyForTwoSealers(source: (KeystoreBackend) -> SecretKeySource) {
        val barrier = java.util.concurrent.CyclicBarrier(2)
        fun meet() = runCatching { barrier.await(300, java.util.concurrent.TimeUnit.MILLISECONDS) }
        val racing = object : KeystoreBackend by ks {
            override fun containsAlias(alias: String): Boolean =
                ks.containsAlias(alias).also { if (alias == "crema.relay.v1" && !it) meet() }

            override fun getKey(alias: String): javax.crypto.SecretKey? =
                ks.getKey(alias).also { if (alias == "crema.relay.v1" && it == null) meet() }
        }
        val got = java.util.Collections.synchronizedList(mutableListOf<javax.crypto.SecretKey>())
        val threads = List(2) { Thread { got += source(racing).getOrCreate() } }
        threads.forEach(Thread::start)
        threads.forEach { it.join(5_000) }
        assertEquals(2, got.size)
        assertSame(got[0], got[1])
        assertEquals(1, ks.generated.count { it == "crema.relay.v1" })
    }

    @Test
    fun `SR-3a P3-1 two first-use sealers share one key`() {
        assertOneKeyForTwoSealers { AndroidKeystoreKeySource("crema.relay.v1", it) }
    }

    @Test
    fun `SR-3a P3-1 two first-use token sealers share one key`() {
        assertOneKeyForTwoSealers { AndroidKeystoreKeySource("crema.relay.v1", it, listedWithoutKeyIsAbsent = true) }
    }

    @Test
    fun `getOrCreate makes the key only in a working keystore`() {
        val created = source.getOrCreate()
        assertSame(created, ks.keys["crema.relay.v1"])
        assertSame(created, source.getOrCreate())
    }

    @Test
    fun `a missing key read through the cipher is definitive only when the keystore works`() {
        val sealed = SecretBox(AndroidKeystoreCipher("crema.relay.v1", "aad".toByteArray(), source)).wrapBytes(ByteArray(32))
        ks.keys.remove("crema.relay.v1")
        val box = SecretBox(AndroidKeystoreCipher("crema.relay.v1", "aad".toByteArray(), source))
        assertEquals(KeystoreFailure.DEFINITIVE, (box.openBytes(sealed) as SecretBox.OpenedBytes.Failed).kind)
        ks.notReady = true
        assertEquals(KeystoreFailure.TRANSIENT, (box.openBytes(sealed) as SecretBox.OpenedBytes.Failed).kind)
    }
}
