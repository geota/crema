package coffee.crema.security

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * An in-memory AES-256 key standing in for an AndroidKeyStore alias, so the real
 * AES-GCM code in [AndroidKeystoreCipher] runs on the JVM. [key] null = the alias
 * is absent; [failure] = the keystore throws on every lookup.
 */
class SoftKeySource(var key: SecretKey? = null, var failure: Exception? = null) : SecretKeySource {
    override fun existing(): SecretKey? {
        failure?.let { throw it }
        return key
    }

    override fun getOrCreate(): SecretKey = existing() ?: newKey().also { key = it }

    companion object {
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}

/**
 * Wraps a real cipher; a set [openFailure] / [sealFailure] is thrown instead, to
 * script what a device keystore might raise.
 */
class ScriptedCipher(private val inner: SecretCipher) : SecretCipher {
    var openFailure: Throwable? = null
    var sealFailure: Throwable? = null

    override fun seal(plain: ByteArray): ByteArray {
        sealFailure?.let { throw it }
        return inner.seal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        openFailure?.let { throw it }
        return inner.open(sealed)
    }
}
