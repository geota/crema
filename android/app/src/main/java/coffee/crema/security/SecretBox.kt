package coffee.crema.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
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
 * needing migration, so the store re-saves it wrapped. A value that provably
 * never opens again (key gone or invalidated, app reinstalled with the old file
 * restored: [KeystoreFailures] says DEFINITIVE) reads as [Opened.Lost] — callers
 * treat that as signed out, never as a crash. Any other failure (a keystore that
 * isn't ready at boot) reads as [Opened.Unavailable]: the stored value is kept
 * as it is and a later start tries again.
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

        /**
         * It didn't open this time, but nothing proves it never will (a transient
         * keystore error). Keep the stored value unchanged, don't re-save it, and
         * try again on a later start.
         */
        data object Unavailable : Opened()
    }

    /** Wrap [plain] for storage. Throws [GeneralSecurityException] when the keystore is unusable. */
    fun wrap(plain: String): String =
        PREFIX + Base64.getEncoder().encodeToString(cipher.seal(plain.toByteArray(Charsets.UTF_8)))

    /**
     * Open a token field. A plaintext value opens and asks to be migrated; a
     * sealed one goes through [openBytes], so the same classification decides
     * [Opened.Lost] (definitive) versus [Opened.Unavailable] (anything else).
     */
    fun open(stored: String): Opened {
        if (!stored.startsWith(PREFIX)) return Opened.Value(stored, migrate = true)
        return when (val o = openBytes(stored)) {
            is OpenedBytes.Value -> Opened.Value(String(o.bytes, Charsets.UTF_8), migrate = false).also { o.bytes.fill(0) }
            is OpenedBytes.Failed -> when (o.kind) {
                KeystoreFailure.DEFINITIVE -> Opened.Lost
                KeystoreFailure.TRANSIENT -> Opened.Unavailable
            }
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

/** Where an AES key lives: [existing] is null only when the alias is absent from a working keystore. */
interface SecretKeySource {
    /**
     * The key, or null when no key exists under the alias (a definitive loss).
     * Throws on a keystore error, including a keystore that can't yet be shown to work.
     */
    fun existing(): SecretKey?

    /** The key, created when absent (sealing only). */
    fun getOrCreate(): SecretKey
}

/** The AndroidKeyStore calls [AndroidKeystoreKeySource] needs (a seam so its probe logic runs on the JVM). */
interface KeystoreBackend {
    fun containsAlias(alias: String): Boolean

    /** The key under [alias], or null. */
    fun getKey(alias: String): SecretKey?

    /** Create a non-exportable AES-256-GCM key under [alias]. */
    fun generate(alias: String): SecretKey
}

/** The device's AndroidKeyStore. */
object AndroidKeyStoreBackend : KeystoreBackend {
    private const val KEYSTORE = "AndroidKeyStore"

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    override fun containsAlias(alias: String): Boolean = keyStore().containsAlias(alias)

    override fun getKey(alias: String): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    override fun generate(alias: String): SecretKey {
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
}

/**
 * A non-exportable AES-256-GCM key in AndroidKeyStore under [alias].
 *
 * **"Absent" is only believed from a keystore proven to work.** `containsAlias`
 * returning false could also be a keystore2 that isn't ready yet (at boot), and
 * a definitive loss read from that would re-key the relay and drop every pin.
 * So when [alias] looks absent, [existing] first runs a liveness probe in the
 * same call: look up (or create) [PROBE_ALIAS], require `containsAlias(probe)`
 * to be true, and seal and open a random block with it. Only when the probe
 * passes, and [alias] is still absent, does it return null (a definitive loss).
 * If the probe fails or throws, it throws [KeyStoreException]: transient, the
 * caller keeps its data and retries later. [getOrCreate] goes through the same
 * check, so a not-ready keystore never gets a new key written over a real one.
 */
class AndroidKeystoreKeySource(
    private val alias: String,
    private val backend: KeystoreBackend = AndroidKeyStoreBackend,
    /**
     * How to read an alias the keystore lists but returns no key for. False
     * (the relay key): a transient [KeyStoreException]. True (the account
     * tokens, as before relay v4): absent, so a seal makes a new key and an
     * open reads as lost, but still only once the probe shows the keystore works.
     */
    private val listedWithoutKeyIsAbsent: Boolean = false,
) : SecretKeySource {
    override fun existing(): SecretKey? {
        if (backend.containsAlias(alias)) present()?.let { return it }
        probe()
        // The keystore works. The alias may have appeared meanwhile (it was just
        // starting); otherwise it is really gone.
        return if (backend.containsAlias(alias)) present() else null
    }

    override fun getOrCreate(): SecretKey = KeyCreation.locked(alias) { existing() ?: backend.generate(alias) }

    private fun present(): SecretKey? = backend.getKey(alias)
        ?: if (listedWithoutKeyIsAbsent) null else throw KeyStoreException("alias $alias present but no secret key returned")

    /** Throws [KeyStoreException] unless the keystore lists, returns and uses a key. */
    private fun probe() {
        try {
            val key = KeyCreation.locked(PROBE_ALIAS) {
                (if (backend.containsAlias(PROBE_ALIAS)) backend.getKey(PROBE_ALIAS) else null)
                    ?: backend.generate(PROBE_ALIAS)
            }
            if (!backend.containsAlias(PROBE_ALIAS)) throw KeyStoreException("probe alias not listed")
            val block = ByteArray(PROBE_BYTES).also { SecureRandom().nextBytes(it) }
            val enc = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
            val sealed = enc.doFinal(block)
            val dec = Cipher.getInstance(TRANSFORMATION)
            dec.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, enc.iv))
            if (!dec.doFinal(sealed).contentEquals(block)) throw KeyStoreException("probe round trip mismatch")
        } catch (e: KeyStoreException) {
            throw KeyStoreException("keystore not ready: ${e.message}", e)
        } catch (e: Exception) {
            throw KeyStoreException("keystore not ready: ${e.javaClass.simpleName}", e)
        }
    }

    companion object {
        /** A throwaway key whose only job is proving the keystore works. */
        const val PROBE_ALIAS = "crema.keystore.probe.v1"
        private const val PROBE_BYTES = 16
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}

/**
 * One check-then-create per alias at a time, process-wide: generating under an
 * alias that already has a key replaces it, so two first-use sealers (the three
 * token stores share one alias) must not both see "absent" and both generate.
 */
internal object KeyCreation {
    private val locks = ConcurrentHashMap<String, Any>()

    fun <T> locked(alias: String, block: () -> T): T = synchronized(locks.getOrPut(alias) { Any() }) { block() }
}

/**
 * Seals with AES-256-GCM under [keys] (by default the strict, probed
 * [AndroidKeystoreKeySource] for [alias]; the token boxes pass one that re-keys
 * an alias listed without a key), binding [aad] as associated data when it
 * is non-empty: a blob sealed under one purpose's AAD never opens under
 * another's (the relay key uses `"relay-v4 static"`). A missing key throws
 * [KeystoreKeyMissingException]; a wrong key, AAD or tampered blob throws
 * `AEADBadTagException`.
 */
class AndroidKeystoreCipher(
    private val alias: String,
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
    /** The account tokens' alias. */
    const val TOKEN_ALIAS = "crema.secretbox.v1"

    val instance: SecretBox by lazy { create() }

    /**
     * The token box over [backend]: the token alias, no AAD, and a key source
     * that re-keys an alias listed without a key (as before relay v4) but only
     * believes "absent" from a keystore the liveness probe shows working, so a
     * keystore that isn't ready never gets a new key over the real one.
     */
    internal fun create(backend: KeystoreBackend = AndroidKeyStoreBackend): SecretBox = SecretBox(
        AndroidKeystoreCipher(TOKEN_ALIAS, keys = AndroidKeystoreKeySource(TOKEN_ALIAS, backend, listedWithoutKeyIsAbsent = true)),
    )
}
