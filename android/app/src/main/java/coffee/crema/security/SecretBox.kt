package coffee.crema.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * At-rest wrapping for the account tokens the shell keeps in `filesDir`
 * (`decent.json`, `visualizer.json`, `drive.json`). The files stay where they
 * are (so `data_extraction_rules.xml` keeps excluding them); only the secret
 * fields inside become `sb1:<base64(iv ‖ ciphertext)>`, sealed with an AES-GCM
 * key that lives in AndroidKeyStore and never leaves it.
 *
 * Reads accept a legacy plaintext value (no `sb1:` prefix) and report it as
 * needing migration, so the store re-saves it wrapped. A value that no longer
 * opens (key invalidated, app reinstalled with the old file restored) reads as
 * [Opened.Lost] — callers treat that as signed out, never as a crash.
 *
 * The cipher sits behind [SecretCipher] so the wrap / unwrap / migrate logic is
 * JVM-testable with a fake; [AndroidKeystoreCipher] is the device implementation.
 * (androidx.security-crypto / EncryptedSharedPreferences is deprecated.)
 */

/** Seal / open raw bytes. `seal` returns `iv ‖ ciphertext`. */
interface SecretCipher {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

class SecretBox(private val cipher: SecretCipher) {
    sealed class Opened {
        /** The secret; [migrate] = it was stored as plaintext and should be re-saved wrapped. */
        data class Value(val value: String, val migrate: Boolean) : Opened()

        /** Wrapped, but the key can no longer open it — treat as signed out. */
        data object Lost : Opened()
    }

    /** Wrap [plain] for storage. Throws [GeneralSecurityException] when the keystore is unusable. */
    fun wrap(plain: String): String =
        PREFIX + Base64.getEncoder().encodeToString(cipher.seal(plain.toByteArray(Charsets.UTF_8)))

    fun open(stored: String): Opened {
        if (!stored.startsWith(PREFIX)) return Opened.Value(stored, migrate = true)
        return try {
            val bytes = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            Opened.Value(String(cipher.open(bytes), Charsets.UTF_8), migrate = false)
        } catch (e: Exception) {
            // GeneralSecurityException (key invalidated / missing / tag mismatch),
            // a keystore ProviderException or IOException, or corrupt base64 —
            // all mean the same thing: this secret is gone.
            Log.w(TAG, "stored secret no longer opens: ${e.javaClass.simpleName}")
            Opened.Lost
        }
    }

    /** Open an optional field: null stays null. */
    fun openOrNull(stored: String?): Opened? = stored?.let(::open)

    companion object {
        const val PREFIX = "sb1:"
        private const val TAG = "SecretBox"
        fun isWrapped(value: String?): Boolean = value?.startsWith(PREFIX) == true
    }
}

/** Seals with a non-exportable AES-256-GCM key in AndroidKeyStore. */
class AndroidKeystoreCipher(private val alias: String = "crema.secretbox.v1") : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        return iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size <= IV_BYTES) throw GeneralSecurityException("sealed blob too short")
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        // No key = it was lost (reinstall / wipe): the blob can never open again.
        val key = ks.getKey(alias, null) as? SecretKey ?: throw GeneralSecurityException("keystore key missing")
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return c.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Process-wide box over the device keystore — shared by every token store. */
object DeviceSecretBox {
    val instance: SecretBox by lazy { SecretBox(AndroidKeystoreCipher()) }
}
