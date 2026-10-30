package coffee.crema.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
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

    /** What [openBytes] found. */
    sealed class OpenedBytes {
        /** The secret bytes. Zero-fill them once used; `toString` never prints them. */
        class Value(val bytes: ByteArray) : OpenedBytes() {
            override fun toString(): String = "OpenedBytes.Value([redacted])"
        }

        /** It didn't open; [kind] says whether it never will. [reason] names the cause (no secret). */
        data class Failed(val kind: KeystoreFailure, val reason: String) : OpenedBytes()
    }

    /**
     * Wrap raw secret bytes (the relay's static private key, spec §5.1) so the
     * secret never becomes a `String`. The cipher binds its AAD. Throws when
     * the keystore is unusable; never falls back to plaintext.
     */
    fun wrapBytes(plain: ByteArray): String = PREFIX + Base64.getEncoder().encodeToString(cipher.seal(plain))

    /**
     * Strict open for [wrapBytes] values. Unlike [open] there is no plaintext
     * migration: a value without the `sb1:` prefix is a definitive loss. Every
     * other failure is classified by [KeystoreFailures.classify], which calls a
     * failure definitive only for the exceptions that prove the key or the blob
     * is gone for good; anything else is transient and the caller must leave
     * its data alone and retry later.
     */
    fun openBytes(stored: String): OpenedBytes {
        if (!stored.startsWith(PREFIX)) return OpenedBytes.Failed(KeystoreFailure.DEFINITIVE, "not sealed")
        val sealed = try {
            Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        } catch (e: IllegalArgumentException) {
            // Corrupt text is not proof that the key is gone: transient (spec §5.1).
            return OpenedBytes.Failed(KeystoreFailure.TRANSIENT, "corrupt base64")
        }
        return try {
            OpenedBytes.Value(cipher.open(sealed))
        } catch (e: Exception) {
            val kind = KeystoreFailures.classify(e)
            Log.w(TAG, "sealed bytes didn't open (${kind.name.lowercase()}): ${e.javaClass.simpleName}")
            OpenedBytes.Failed(kind, e.javaClass.simpleName)
        }
    }

    companion object {
        const val PREFIX = "sb1:"
        private const val TAG = "SecretBox"
        fun isWrapped(value: String?): Boolean = value?.startsWith(PREFIX) == true
    }
}

/** Where an AES key lives: [existing] is null only when the alias is absent. */
interface SecretKeySource {
    /** The key, or null when no key exists under the alias (a definitive loss). Throws on a keystore error. */
    fun existing(): SecretKey?

    /** The key, created when absent (sealing only). */
    fun getOrCreate(): SecretKey
}

/** A non-exportable AES-256-GCM key in AndroidKeyStore under [alias]. */
class AndroidKeystoreKeySource(private val alias: String) : SecretKeySource {
    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    override fun existing(): SecretKey? {
        val ks = keyStore()
        // containsAlias answers "is there a key" without conflating it with a
        // failed lookup: a keystore error throws (transient), absence is false.
        if (!ks.containsAlias(alias)) return null
        return ks.getKey(alias, null) as? SecretKey
            ?: throw KeyStoreException("alias $alias present but no secret key returned")
    }

    override fun getOrCreate(): SecretKey {
        existing()?.let { return it }
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

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
    }
}

/**
 * Seals with AES-256-GCM under [keys] (by default a non-exportable
 * AndroidKeyStore key under [alias]), binding [aad] as associated data when it
 * is non-empty: a blob sealed under one purpose's AAD never opens under
 * another's (the relay key uses `"relay-v4 static"`). A missing key throws
 * [KeystoreKeyMissingException]; a wrong key, AAD or tampered blob throws
 * `AEADBadTagException`.
 */
class AndroidKeystoreCipher(
    private val alias: String = "crema.secretbox.v1",
    private val aad: ByteArray = ByteArray(0),
    private val keys: SecretKeySource = AndroidKeystoreKeySource(alias),
) : SecretCipher {
    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, keys.getOrCreate())
        if (aad.isNotEmpty()) c.updateAAD(aad)
        val iv = c.iv
        return iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size <= IV_BYTES) throw GeneralSecurityException("sealed blob too short")
        // No key = it was lost (reinstall / wipe): the blob can never open again.
        val key = keys.existing() ?: throw KeystoreKeyMissingException(alias)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        if (aad.isNotEmpty()) c.updateAAD(aad)
        return c.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Process-wide box over the device keystore — shared by every token store. */
object DeviceSecretBox {
    val instance: SecretBox by lazy { SecretBox(AndroidKeystoreCipher()) }
}
