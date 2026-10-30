package coffee.crema.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Relay v4 spec §5.1 / AC-30: the transient-versus-definitive classification,
 * as a table. Only the three listed exceptions, thrown directly, are definitive;
 * everything else, including those three found only as a cause, is transient.
 */
class KeystoreFailuresTest {
    private fun kindOf(e: Throwable) = KeystoreFailures.classify(e)

    @Test
    fun `only the allow-listed exceptions are definitive`() {
        val definitive = listOf(
            KeystoreKeyMissingException("crema.relay.v1"),
            AEADBadTagException("tag mismatch"),
            KeyPermanentlyInvalidatedException(),
        )
        for (e in definitive) assertEquals(e.javaClass.simpleName, KeystoreFailure.DEFINITIVE, kindOf(e))
    }

    @Test
    fun `everything else is transient`() {
        val transient = listOf(
            KeyStoreException("keystore2 system error"),
            ProviderException("Keystore operation failed"),
            IOException("binder died"),
            UnrecoverableKeyException("can't recover"),
            GeneralSecurityException("sealed blob too short"),
            InvalidKeyException("not yet available"),
            // BadPaddingException is AEADBadTagException's parent: only the tag
            // failure itself proves the key or blob is wrong.
            BadPaddingException("padding"),
            IllegalStateException("unexpected"),
            RuntimeException("anything"),
            OutOfMemoryError("not even an Exception"),
        )
        for (e in transient) assertEquals(e.javaClass.simpleName, KeystoreFailure.TRANSIENT, kindOf(e))
    }

    @Test
    fun `a definitive exception wrapped in another stays transient`() {
        val wrapped = listOf(
            ProviderException("wrapper", AEADBadTagException("inner")),
            KeyStoreException("wrapper", KeystoreKeyMissingException("crema.relay.v1")),
            RuntimeException(KeyPermanentlyInvalidatedException()),
        )
        for (e in wrapped) assertEquals(e.toString(), KeystoreFailure.TRANSIENT, kindOf(e))
    }
}
