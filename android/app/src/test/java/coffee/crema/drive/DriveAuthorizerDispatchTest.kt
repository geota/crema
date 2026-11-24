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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #336: [DriveSync] asks the stored grant's way for its tokens, and knows
 * nothing of how any way works. A grant stored before the split is the
 * browser's, and keeps refreshing as it did.
 */
class DriveAuthorizerDispatchTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val dir: File = Files.createTempDirectory("drive-dispatch").toFile()
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
    private val uploads = CopyOnWriteArrayList<String>()
    private val opened = CopyOnWriteArrayList<String>()
    private val clock = 2_000_000_000_000L

    @org.junit.Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    @After
    fun cleanUp() {
        job.cancel()
        dir.deleteRecursively()
    }

    private val browser = BrowserAuthorizer(
        "cid", json,
        now = { clock },
        refresh = { spent ->
            calls += "browser refresh $spent"
            DriveTokenSet(accessToken = "browser-fresh", refreshToken = spent, expiresAt = Long.MAX_VALUE)
        },
        revokeGrant = { calls += "browser revoke ${it.refreshToken ?: it.accessToken}" },
    )

    /** A second way, standing in for `play`'s. */
    private inner class OtherWay(
        private val isAvailable: Boolean = true,
        private val answer: FreshToken = FreshToken.Token(play("play-fresh")),
    ) : DriveAuthorizer {
        override val method = AuthMethod.PLAY
        override fun available() = isAvailable
        override suspend fun fresh(stored: DriveTokenSet, rejected: Boolean): FreshToken {
            calls += "play fresh ${stored.accessToken} rejected=$rejected"
            return answer
        }
        override suspend fun begin(host: SignInHost): Begin = error("not this way")
        override suspend fun complete(result: SignInResult): DriveTokenSet = error("not this way")
        override suspend fun revoke(stored: DriveTokenSet) {
            calls += "play revoke ${stored.account}"
        }
    }

    private fun play(access: String, expiresAt: Long = Long.MAX_VALUE) =
        DriveTokenSet(accessToken = access, expiresAt = expiresAt, method = AuthMethod.PLAY, account = "a@example.com")

    private fun sync(state: DriveState?, vararg ways: DriveAuthorizer): DriveSync {
        if (state != null) runBlocking { assertTrue(store.save(state)) }
        return DriveSync(
            store, json, CoroutineScope(job + Dispatchers.Default), "cid",
            notify = {},
            backupZip = {
                BackupZip(File.createTempFile("backup", ".zip").apply { writeBytes(ByteArray(1)) }, coffee.crema.core.BackupCounts(1u, 1u))
            },
            backupFileName = { "crema-backup-1" },
            applyRestore = { _, _, _ -> },
            authorizers = ways.toList(),
            now = { clock },
            uploadBackup = { token, name, _ ->
                uploads += token
                DriveFile(id = "f", name = name)
            },
            listBackups = { emptyList() },
        ).also { runBlocking { it.load() } }
    }

    private fun settle() = runBlocking { repeat(4) { job.children.toList().forEach { it.join() } } }

    private fun stored() = runBlocking { store.load() }

    private fun backUp(s: DriveSync) {
        s.backupNow()
        settle()
    }

    @Test
    fun `a grant stored before the split decodes as the browser's and refreshes through it`() {
        // drive.json as builds before #336 wrote it: no method, no account.
        val before = """{"tokens":{"accessToken":"old","refreshToken":"r1","expiresAt":${clock - 1}}}"""
        val decoded = json.decodeFromString(DriveState.serializer(), before)
        assertEquals(AuthMethod.BROWSER, decoded.tokens!!.method)
        assertNull(decoded.tokens!!.account)

        val s = sync(decoded, OtherWay(), browser)
        backUp(s)

        assertEquals(listOf("browser refresh r1"), calls)
        assertEquals(listOf("browser-fresh"), uploads)
        assertEquals(AuthMethod.BROWSER, stored().tokens!!.method)
    }

    @Test
    fun `a stored play grant renews through the play way, and is stored again`() {
        val s = sync(DriveState(tokens = play("play-old", expiresAt = clock - 1)), OtherWay(), browser)
        backUp(s)

        assertEquals(listOf("play fresh play-old rejected=false"), calls)
        assertEquals(listOf("play-fresh"), uploads)
        assertEquals("play-fresh", stored().tokens!!.accessToken)
        assertEquals(AuthMethod.PLAY, stored().tokens!!.method)
    }

    @Test
    fun `a way that needs the user ends the session with the reconnect banner`() {
        val s = sync(DriveState(tokens = play("play-old", expiresAt = clock - 1)), OtherWay(answer = FreshToken.NeedsReconnect), browser)
        backUp(s)

        assertNull(stored().tokens)
        assertTrue(stored().needsReconnect)
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun `a way that's waiting keeps the session`() {
        val s = sync(DriveState(tokens = play("play-old", expiresAt = clock - 1)), OtherWay(answer = FreshToken.Waiting(SyncFailure.Offline)), browser)
        backUp(s)

        assertEquals("play-old", stored().tokens!!.accessToken)
        assertTrue(!stored().needsReconnect)
        assertTrue(s.state.value.healthLine!!.contains("Waiting for a connection"))
    }

    @Test
    fun `a play grant on a build without that way asks to reconnect, and revokes its access token`() {
        // `play` installed over `foss` and back: the grant's way isn't here.
        val s = sync(DriveState(tokens = play("play-old", expiresAt = clock - 1)), browser)
        backUp(s)
        assertNull(stored().tokens)
        assertTrue(stored().needsReconnect)
        assertTrue("no way was asked: $calls", calls.isEmpty())

        val again = sync(DriveState(tokens = play("play-live")), browser)
        again.disconnect(quiet = true)
        settle()
        assertEquals(listOf("browser revoke play-live"), calls)
    }

    @Test
    fun `a disconnect revokes the way that made the grant`() {
        val s = sync(DriveState(tokens = play("play-live")), OtherWay(), browser)
        s.disconnect(quiet = true)
        settle()
        assertEquals(listOf("play revoke a@example.com"), calls)
    }

    @Test
    fun `sign-in takes the first available way`() {
        // The PKCE helpers need android.util.Base64, stubbed on the JVM: a fixed handshake.
        val handshake = object : DriveAuthorizer by browser {
            override suspend fun begin(host: SignInHost): Begin = Begin.Browser("verifier", "state", "$GOOGLE_AUTHORIZE_URL?state=state")
        }
        val s = sync(null, OtherWay(isAvailable = false), handshake)
        s.beginSignIn { opened += it }
        settle()
        assertEquals(listOf("$GOOGLE_AUTHORIZE_URL?state=state"), opened)
        assertEquals("verifier", stored().pendingVerifier)
        assertEquals("state", stored().pendingState)
    }
}
