package coffee.crema.drive

import android.content.ContextWrapper
import coffee.crema.core.SyncFailure
import coffee.crema.security.SecretBox
import coffee.crema.security.SecretCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #336 §5: which way a sign-in takes, and when it falls back to the browser.
 * A Play services sign-in that can't work on this phone hands over to the
 * browser at once and for later sign-ins; a cancel, no network, a later
 * silent failure or a Drive 401 never switch ways.
 */
class DriveSignInWayTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val dir: File = Files.createTempDirectory("drive-way").toFile()
    private val context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
    private val box = SecretBox(
        object : SecretCipher {
            override fun seal(plain: ByteArray) = ByteArray(12) + plain
            override fun open(sealed: ByteArray) = sealed.copyOfRange(12, sealed.size)
        },
    )
    private val store = DriveStore(context, json, box)
    private val job = SupervisorJob()
    private val calls = CopyOnWriteArrayList<String>()
    private val notices = CopyOnWriteArrayList<String>()
    private val opened = CopyOnWriteArrayList<String>()
    private val uploads = CopyOnWriteArrayList<String>()
    private val clock = 2_000_000_000_000L
    private var uploadFails: Exception? = null

    @org.junit.Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    @After
    fun cleanUp() {
        job.cancel()
        dir.deleteRecursively()
    }

    private val browserUrl = "$GOOGLE_AUTHORIZE_URL?state=state"

    /** The browser way, with a fixed handshake (the PKCE helpers need android.util.Base64). */
    private val browser = object : DriveAuthorizer by BrowserAuthorizer("cid", json, now = { clock }, revokeGrant = {}) {
        override suspend fun begin(host: SignInHost): Begin {
            calls += "browser begin"
            return Begin.Browser("verifier", "state", browserUrl)
        }
    }

    private fun play(access: String, expiresAt: Long = Long.MAX_VALUE) =
        DriveTokenSet(accessToken = access, expiresAt = expiresAt, method = AuthMethod.PLAY, account = "a@example.com")

    /** A stand-in for `play`'s PlayAuthorizer. */
    private inner class FakePlay(
        private val isAvailable: Boolean = true,
        var begins: () -> Begin = { Begin.Granted(play("play-new")) },
        var completes: () -> DriveTokenSet = { play("play-consented") },
        var renews: (DriveTokenSet, Boolean) -> FreshToken = { _, _ -> FreshToken.Token(play("play-fresh")) },
    ) : DriveAuthorizer {
        override val method = AuthMethod.PLAY
        override fun available() = isAvailable
        override suspend fun fresh(stored: DriveTokenSet, rejected: Boolean): FreshToken {
            calls += "play fresh ${stored.accessToken} rejected=$rejected"
            return renews(stored, rejected)
        }
        override suspend fun begin(host: SignInHost): Begin {
            calls += "play begin"
            return begins()
        }
        override suspend fun complete(result: SignInResult): DriveTokenSet {
            calls += "play complete"
            return completes()
        }
        override suspend fun revoke(stored: DriveTokenSet) {
            calls += "play revoke ${stored.accessToken}"
        }
    }

    private fun failing(kind: SignInFailure.Kind): () -> Nothing = { throw SignInFailure(kind, "because") }

    private fun sync(way: FakePlay, state: DriveState = DriveState()): DriveSync {
        runBlocking { assertTrue(store.save(state)) }
        return DriveSync(
            store, json, CoroutineScope(job + Dispatchers.Default), "cid",
            notify = { notices += it },
            backupZip = {
                BackupZip(File.createTempFile("backup", ".zip").apply { writeBytes(ByteArray(1)) }, coffee.crema.core.BackupCounts(1u, 1u))
            },
            backupFileName = { "crema-backup-1" },
            applyRestore = { _, _, _ -> },
            authorizers = listOf(way, browser),
            now = { clock },
            uploadBackup = { token, name, _ ->
                uploads += token
                uploadFails?.let { throw it }
                DriveFile(id = "f", name = name)
            },
            listBackups = { emptyList() },
        ).also { runBlocking { it.load() } }
    }

    private fun settle() = runBlocking { repeat(4) { job.children.toList().forEach { it.join() } } }

    private fun stored() = runBlocking { store.load() }

    private fun DriveSync.signIn() {
        beginSignIn { opened += it }
        settle()
    }

    /** The Activity on screen, for a consent result's fallback. */
    private fun DriveSync.onScreen() = attachHost(SignInHost { opened += it })

    @Test
    fun `sign-in takes Play services where they work, and stores its grant`() {
        val s = sync(FakePlay())
        assertTrue("Settings offers the browser", s.state.value.browserInstead)
        s.signIn()

        assertEquals(listOf("play begin"), calls)
        assertTrue(opened.isEmpty())
        assertEquals("play-new", stored().tokens!!.accessToken)
        assertEquals(AuthMethod.PLAY, stored().tokens!!.method)
        assertEquals("a@example.com", stored().tokens!!.account)
        assertTrue("Connected to Google Drive" in notices)
    }

    @Test
    fun `no Play services on this phone signs in through the browser, with no escape hatch`() {
        val s = sync(FakePlay(isAvailable = false))
        assertFalse(s.state.value.browserInstead)
        s.signIn()

        assertEquals(listOf("browser begin"), calls)
        assertEquals(listOf(browserUrl), opened)
        assertFalse(stored().browserOnly)
    }

    @Test
    fun `a Play sign-in that can't work here opens the browser at once, and later sign-ins skip Play`() {
        val s = sync(FakePlay(begins = failing(SignInFailure.Kind.UNSUPPORTED)))
        s.signIn()

        assertEquals(listOf("play begin", "browser begin"), calls)
        assertEquals(listOf(browserUrl), opened)
        assertTrue("no error between: $notices", notices.isEmpty())
        assertTrue(stored().browserOnly)
        assertEquals("verifier", stored().pendingVerifier)
        assertFalse(s.state.value.browserInstead)

        // Signing out keeps it; the next sign-in goes straight to the browser.
        s.disconnect(quiet = true)
        settle()
        calls.clear()
        opened.clear()
        s.signIn()
        assertEquals(listOf("browser begin"), calls)
        assertTrue(stored().browserOnly)
    }

    @Test
    fun `an unexpected Play failure falls back too`() {
        val s = sync(FakePlay(begins = { throw IllegalStateException("broken") }))
        s.signIn()
        assertEquals(listOf("play begin", "browser begin"), calls)
        assertTrue(stored().browserOnly)
    }

    @Test
    fun `a cancel, no network or no Activity never switch ways`() {
        for ((kind, said) in listOf(
            SignInFailure.Kind.CANCELLED to "Google Drive sign-in was cancelled",
            SignInFailure.Kind.OFFLINE to "Google Drive sign-in failed: couldn’t reach Google",
            SignInFailure.Kind.FAILED to "Google Drive sign-in failed: because",
        )) {
            calls.clear()
            notices.clear()
            val s = sync(FakePlay(begins = failing(kind)))
            s.signIn()
            assertEquals("$kind", listOf("play begin"), calls)
            assertEquals("$kind", listOf(said), notices)
            assertTrue("$kind", opened.isEmpty())
            assertFalse("$kind", stored().browserOnly)
            assertNull("$kind", stored().tokens)
        }
    }

    @Test
    fun `a consent screen's grant is stored when it comes back`() {
        val s = sync(FakePlay(begins = { Begin.Launched }))
        s.signIn()
        assertNull(stored().tokens)

        s.handleConsentResult(android.app.Activity.RESULT_OK, null)
        settle()
        assertEquals(listOf("play begin", "play complete"), calls)
        assertEquals("play-consented", stored().tokens!!.accessToken)
        assertFalse(s.state.value.busy)
    }

    @Test
    fun `a consent screen that gives no token falls back to the browser on screen`() {
        val s = sync(FakePlay(begins = { Begin.Launched }, completes = failing(SignInFailure.Kind.UNSUPPORTED)))
        s.onScreen()
        s.signIn()
        s.handleConsentResult(android.app.Activity.RESULT_OK, null)
        settle()

        assertEquals(listOf("play begin", "play complete", "browser begin"), calls)
        assertEquals(listOf(browserUrl), opened)
        assertTrue(stored().browserOnly)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `a cancelled consent screen is only said`() {
        val s = sync(FakePlay(begins = { Begin.Launched }, completes = failing(SignInFailure.Kind.CANCELLED)))
        s.onScreen()
        s.signIn()
        s.handleConsentResult(android.app.Activity.RESULT_CANCELED, null)
        settle()

        assertEquals(listOf("Google Drive sign-in was cancelled"), notices)
        assertTrue(opened.isEmpty())
        assertFalse(stored().browserOnly)
    }

    @Test
    fun `the escape hatch keeps this phone to the browser and starts its sign-in`() {
        val s = sync(FakePlay())
        s.signInWithBrowser { opened += it }
        settle()

        assertEquals(listOf("browser begin"), calls)
        assertEquals(listOf(browserUrl), opened)
        assertTrue(stored().browserOnly)
        assertFalse(s.state.value.browserInstead)
    }

    @Test
    fun `a stored browser grant keeps refreshing through the browser after the update`() {
        val refreshed = CopyOnWriteArrayList<String>()
        val oldBrowser = BrowserAuthorizer(
            "cid", json, now = { clock },
            refresh = {
                refreshed += it
                DriveTokenSet(accessToken = "browser-fresh", refreshToken = it, expiresAt = Long.MAX_VALUE)
            },
            revokeGrant = {},
        )
        runBlocking { assertTrue(store.save(DriveState(tokens = DriveTokenSet("old", "r1", expiresAt = clock - 1)))) }
        val s = DriveSync(
            store, json, CoroutineScope(job + Dispatchers.Default), "cid",
            notify = { notices += it },
            backupZip = { BackupZip(File.createTempFile("backup", ".zip").apply { writeBytes(ByteArray(1)) }, coffee.crema.core.BackupCounts(1u, 1u)) },
            backupFileName = { "crema-backup-1" },
            applyRestore = { _, _, _ -> },
            authorizers = listOf(FakePlay(), oldBrowser),
            now = { clock },
            uploadBackup = { token, name, _ -> uploads += token; DriveFile(id = "f", name = name) },
            listBackups = { emptyList() },
        ).also { runBlocking { it.load() } }
        s.backupNow()
        settle()

        assertEquals(listOf("r1"), refreshed)
        assertTrue("Play isn't asked: $calls", calls.isEmpty())
        assertEquals(listOf("browser-fresh"), uploads)
        assertEquals(AuthMethod.BROWSER, stored().tokens!!.method)
    }

    @Test
    fun `a silent Play grant that needs the user raises the reconnect notice once`() {
        val way = FakePlay(renews = { _, _ -> FreshToken.NeedsReconnect })
        val s = sync(way, DriveState(tokens = play("play-old", expiresAt = clock - 1)))
        s.autoBackupIfDue(true)
        settle()

        assertNull(stored().tokens)
        assertTrue(stored().needsReconnect)
        assertFalse("a silent failure never switches", stored().browserOnly)
        assertEquals(listOf(DRIVE_RECONNECT_LINE), notices)

        // The next launch says nothing new.
        sync(way, stored()).autoBackupIfDue(true)
        settle()
        assertEquals(listOf(DRIVE_RECONNECT_LINE), notices)
    }

    @Test
    fun `a Drive 401 asks Play again with rejected set, and a second 401 is a refusal, not a switch`() {
        uploadFails = DriveHttpError(401, """{"error":{"code":401}}""", "Drive upload failed (HTTP 401).")
        val s = sync(FakePlay(), DriveState(tokens = play("play-live")))
        s.backupNow()
        settle()

        assertEquals(listOf("play fresh play-live rejected=true"), calls)
        assertEquals(listOf("play-live", "play-fresh"), uploads)
        assertFalse(stored().browserOnly)
        assertEquals("still signed in", AuthMethod.PLAY, stored().tokens!!.method)
        assertTrue("$notices", notices.single().startsWith("Google Drive backup failed"))
    }

    @Test
    fun `the way back to Play services undoes browserOnly, and the next sign-in takes them`() {
        val s = sync(FakePlay(), DriveState(browserOnly = true))
        assertTrue(s.state.value.playAgain)
        assertFalse(s.state.value.browserInstead)
        s.usePlayServicesAgain()
        settle()

        assertFalse(stored().browserOnly)
        assertFalse(s.state.value.playAgain)
        assertTrue(s.state.value.browserInstead)
        s.signIn()
        assertEquals(listOf("play begin"), calls)
    }

    @Test
    fun `no way back is offered where Play services don't work`() {
        assertFalse(sync(FakePlay(isAvailable = false), DriveState(browserOnly = true)).state.value.playAgain)
    }

    @Test
    fun `a disconnect or Erase all data revokes a Play grant through the Play way`() {
        val s = sync(FakePlay(), DriveState(tokens = play("play-live")))
        s.disconnect(quiet = true)
        settle()
        assertEquals(listOf("play revoke play-live"), calls)

        calls.clear()
        val erased = sync(FakePlay(), DriveState(tokens = play("play-erased")))
        runBlocking { erased.eraseAll() }
        settle()
        assertEquals(listOf("play revoke play-erased"), calls)
        assertNull(stored().tokens)
    }

    @Test
    fun `a Play wait keeps the session`() {
        val s = sync(FakePlay(renews = { _, _ -> FreshToken.Waiting(SyncFailure.Offline) }), DriveState(tokens = play("play-old", expiresAt = clock - 1)))
        s.autoBackupIfDue(true)
        settle()
        assertEquals("play-old", stored().tokens!!.accessToken)
        assertFalse(stored().needsReconnect)
    }
}
