package coffee.crema.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException

/** Whether a keystore failure means the sealed secret is gone for good. */
enum class KeystoreFailure {
    /** The key or the blob is provably gone: retrying can never succeed. */
    DEFINITIVE,

    /** Anything else: the data may be fine; leave it alone and retry on a later start. */
    TRANSIENT,
}

/** No key exists under [alias] (an app reinstall, a keystore reset, a restore onto another device). */
class KeystoreKeyMissingException(alias: String) : GeneralSecurityException("keystore key $alias is missing")

/**
 * Relay v4 spec §5.1: the one place that decides whether a failure to open the
 * relay's sealed private key is a definitive loss (which replaces the key and
 * clears every pin) or a transient error (which leaves everything untouched,
 * keeps the relay off for this start and retries on the next).
 *
 * It is an allow-list. Only these exceptions, thrown directly, are definitive:
 * - [KeystoreKeyMissingException]: no key under the alias (`containsAlias` was false);
 * - [AEADBadTagException]: the key exists but doesn't authenticate the blob (a
 *   different key under the same alias after a reinstall, a tampered blob, or a
 *   different AAD);
 * - [KeyPermanentlyInvalidatedException]: Android invalidated the key.
 *
 * Everything else is transient, including `KeyStoreException`,
 * `ProviderException` (keystore2 "system error" at boot), `IOException`,
 * `UnrecoverableKeyException`, a too-short blob, and a definitive exception
 * only found as the *cause* of another one: a definitive verdict is never
 * inferred from a wrapper. (A stored value without the `sb1:` prefix is the
 * fourth definitive case; [SecretBox.openBytes] handles it before decrypting.)
 */
object KeystoreFailures {
    fun classify(e: Throwable): KeystoreFailure = when (e) {
        is KeystoreKeyMissingException,
        is AEADBadTagException,
        is KeyPermanentlyInvalidatedException,
        -> KeystoreFailure.DEFINITIVE

        else -> KeystoreFailure.TRANSIENT
    }
}
