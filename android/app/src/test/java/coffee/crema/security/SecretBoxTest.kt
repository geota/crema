package coffee.crema.security

import coffee.crema.decent.DecentState
import coffee.crema.decent.openedWith
import coffee.crema.decent.sealedWith
import coffee.crema.drive.DriveState
import coffee.crema.drive.DriveTokenSet
import coffee.crema.drive.openedWith
import coffee.crema.drive.sealedWith
import coffee.crema.visualizer.TokenSet
import coffee.crema.visualizer.VisualizerAccount
import coffee.crema.visualizer.VisualizerState
import coffee.crema.visualizer.openedWith
import coffee.crema.visualizer.sealedWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException

/** Token wrapping at rest — wrap / open / plaintext migration / lost key, with a fake cipher. */
class SecretBoxTest {
    /**
     * Not crypto: a 12-byte "iv" then the bytes XOR-ed; [lost] makes every open
     * fail like a dead key (a tag mismatch), [busy] like a keystore error.
     */
    private class FakeCipher : SecretCipher {
        var lost = false
        var busy = false
        override fun seal(plain: ByteArray) = ByteArray(12) { 7 } + plain.map { (it.toInt() xor 0x5A).toByte() }
        override fun open(sealed: ByteArray): ByteArray {
            if (lost) throw AEADBadTagException("tag mismatch")
            if (busy) throw KeyStoreException("keystore2 system error")
            return sealed.drop(12).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private val cipher = FakeCipher()
    private val box = SecretBox(cipher)

    @Test
    fun `wrap then open round-trips and hides the plaintext`() {
        val wrapped = box.wrap("s3cret-token")
        assertTrue(wrapped.startsWith(SecretBox.PREFIX))
        assertFalse(wrapped.contains("s3cret"))
        assertEquals(SecretBox.Opened.Value("s3cret-token", migrate = false), box.open(wrapped))
    }

    @Test
    fun `a legacy plaintext value opens and asks to be migrated`() {
        assertEquals(SecretBox.Opened.Value("plain", migrate = true), box.open("plain"))
    }

    @Test
    fun `a value the key can no longer open is lost, not a crash`() {
        val wrapped = box.wrap("t")
        cipher.lost = true
        assertEquals(SecretBox.Opened.Lost, box.open(wrapped))
    }

    @Test
    fun `a value that doesn't open this time is unavailable, not lost`() {
        val wrapped = box.wrap("t")
        cipher.busy = true
        assertEquals(SecretBox.Opened.Unavailable, box.open(wrapped))
        for (e in listOf(GeneralSecurityException("x"), java.security.ProviderException("y"), java.io.IOException("z"))) {
            val throwing = SecretBox(object : SecretCipher by cipher {
                override fun open(sealed: ByteArray): ByteArray = throw e
            })
            assertEquals(e.toString(), SecretBox.Opened.Unavailable, throwing.open(wrapped))
        }
        // Corrupt text proves nothing about the key either.
        assertEquals(SecretBox.Opened.Unavailable, box.open("${SecretBox.PREFIX}!!not base64!!"))
    }

    @Test
    fun `decent state with an unavailable key keeps the stored token and doesn't re-save`() {
        val sealed = DecentState(email = "a@b.c", token = "tok-123456", autoUpload = true).sealedWith(box)
        cipher.busy = true
        val (opened, resave) = sealed.openedWith(box)
        assertFalse(resave)
        assertNull(opened.token)
        assertFalse(opened.linked)
        assertFalse(opened.needsReauth)
        // Any save this session writes the stored token back unchanged.
        assertEquals(sealed.token, opened.copy(autoUpload = false).sealedWith(box).token)
        // A sign-out or a new sign-in builds a fresh state: the old token goes.
        assertNull(DecentState(log = opened.log).sealedWith(box).token)
    }

    @Test
    fun `visualizer and drive with an unavailable key keep the stored tokens`() {
        val vz = VisualizerState(tokens = TokenSet("acc", "ref", 9), account = VisualizerAccount("1", "me"), pendingVerifier = "ver")
        val vzSealed = vz.sealedWith(box)
        val drive = DriveState(tokens = DriveTokenSet(accessToken = "g", refreshToken = "r", expiresAt = 1), lastAutoBackupAtMs = 5)
        val driveSealed = drive.sealedWith(box)
        cipher.busy = true
        val (vzOpened, vzResave) = vzSealed.openedWith(box)
        assertFalse(vzResave)
        assertNull(vzOpened.tokens)
        assertNull(vzOpened.account)
        val vzSaved = vzOpened.copy(lastShotSyncAt = 7).sealedWith(box)
        assertEquals(vzSealed.tokens, vzSaved.tokens)
        assertEquals(vz.account, vzSaved.account)
        // Sign-out (as VisualizerSync does it) and new tokens both replace it.
        assertNull(vzOpened.copy(tokens = null, account = null, unopened = null).sealedWith(box).tokens)
        cipher.busy = false
        val fresh = vzOpened.copy(tokens = TokenSet("acc2", "ref2", 10)).sealedWith(box)
        assertEquals("acc2", fresh.openedWith(box).first.tokens!!.accessToken)
        cipher.busy = true

        val (driveOpened, driveResave) = driveSealed.openedWith(box)
        assertFalse(driveResave)
        assertNull(driveOpened.tokens)
        assertEquals(driveSealed.tokens, driveOpened.copy(lastAutoBackupAtMs = 6).sealedWith(box).tokens)
        assertNull(driveOpened.copy(tokens = null, unopened = null).sealedWith(box).tokens)
    }

    @Test
    fun `decent state seals the token and migrates a plaintext file`() {
        val plain = DecentState(email = "a@b.c", token = "tok-123456", autoUpload = true)
        val (opened, resave) = plain.openedWith(box)
        assertEquals(plain, opened)
        assertTrue(resave)
        val sealed = opened.sealedWith(box)
        assertNotEquals("tok-123456", sealed.token)
        assertEquals(sealed, sealed.sealedWith(box)) // idempotent
        val (reopened, again) = sealed.openedWith(box)
        assertEquals(plain, reopened)
        assertFalse(again)
    }

    @Test
    fun `decent state with a lost key asks to sign in again, keeping the email`() {
        val sealed = DecentState(email = "a@b.c", token = "tok-123456").sealedWith(box)
        cipher.lost = true
        val (opened, resave) = sealed.openedWith(box)
        assertNull(opened.token)
        assertFalse(opened.linked)
        assertTrue(opened.needsReauth)
        assertEquals("a@b.c", opened.email)
        assertTrue(resave)
    }

    @Test
    fun `visualizer and drive tokens are sealed and a lost key signs out`() {
        val vz = VisualizerState(
            tokens = TokenSet(accessToken = "acc", refreshToken = "ref", expiresAt = 9),
            account = VisualizerAccount("1", "me"),
            pendingVerifier = "ver",
        )
        val vzSealed = vz.sealedWith(box)
        assertTrue(SecretBox.isWrapped(vzSealed.tokens!!.accessToken))
        assertTrue(SecretBox.isWrapped(vzSealed.tokens.refreshToken))
        assertTrue(SecretBox.isWrapped(vzSealed.pendingVerifier))
        assertEquals(vz to false, vzSealed.openedWith(box))
        assertEquals(vz to true, vz.openedWith(box))

        val drive = DriveState(tokens = DriveTokenSet(accessToken = "g", refreshToken = "r", expiresAt = 1))
        val driveSealed = drive.sealedWith(box)
        assertEquals(drive to false, driveSealed.openedWith(box))

        cipher.lost = true
        val (vzLost, _) = vzSealed.openedWith(box)
        assertNull(vzLost.tokens)
        assertNull(vzLost.account)
        val (driveLost, resave) = driveSealed.openedWith(box)
        assertNull(driveLost.tokens)
        assertTrue(resave)
    }
}
