package coffee.crema.drive

import android.content.ContextWrapper
import coffee.crema.security.SecretBox
import coffee.crema.security.SecretCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * geota/crema#323: Drive used to sign out on any failed refresh, so opening the
 * app offline on a due day ended backups until someone noticed. Only Google
 * saying the refresh token is dead ends the session now, and a real end of
 * session stays visible.
 */
class DriveSessionTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val dir: File = Files.createTempDirectory("drive-session").toFile()
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

    @org.junit.Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    private val job = SupervisorJob()
    private val notices = CopyOnWriteArrayList<String>()
    private val uploads = CopyOnWriteArrayList<String>()
    private val grants = AtomicInteger()
    private var clock = 2_000_000_000_000L
    private var refresh: suspend (String) -> DriveTokenSet = { error("no refresh expected") }
    private var upload: suspend (String) -> Unit = {}

    private val invalidGrant = DriveHttpError(400, """{"error":"invalid_grant"}""", "invalid_grant")
    private fun reason(reason: String) = DriveHttpError(
        403,
        """{"error":{"code":403,"errors":[{"domain":"usageLimits","reason":"$reason"}]}}""",
        "Drive upload failed (HTTP 403).",
    )

    private fun set(access: String, refresh: String? = "r1", expiresAt: Long = Long.MAX_VALUE) =
        DriveTokenSet(accessToken = access, refreshToken = refresh, expiresAt = expiresAt)

    private val stale get() = set("old", expiresAt = clock - 1)

    private fun sync(state: DriveState) = run {
        runBlocking { assertTrue(store.save(state)) }
        reopen()
    }

    private fun reopen() = DriveSync(
        store, json, CoroutineScope(job + Dispatchers.Default), "cid",
        notify = { notices += it },
        backupZip = { BackupZip(ByteArray(1), coffee.crema.core.BackupCounts(1u, 1u)) },
        backupFileName = { "crema-backup-1" },
        applyRestore = { _, _, _ -> },
        exchangeCode = { _, _ -> set("new") },
        refreshToken = {
            grants.incrementAndGet()
            refresh(it)
        },
        now = { clock },
        uploadBackup = { token, name, _ ->
            uploads += "$token $name"
            upload(token)
            DriveFile(id = "f", name = name)
        },
        listBackups = { emptyList() },
        revokeGrant = {},
    ).also { runBlocking { it.load() } }

    @After
    fun cleanUp() {
        job.cancel()
        dir.deleteRecursively()
    }

    private fun settle() = runBlocking { repeat(4) { job.children.toList().forEach { it.join() } } }

    private fun stored() = runBlocking { store.load() }

    private fun autoBackup(s: DriveSync) {
        s.autoBackupIfDue(true)
        settle()
    }

    @Test
    fun `drive_offline_on_due_day_stays_connected`() {
        refresh = { throw UnknownHostException("oauth2.googleapis.com") }
        val s = sync(DriveState(tokens = stale))
        autoBackup(s)

        assertNotNull("still connected", stored().tokens)
        assertFalse(s.state.value.needsReconnect)
        assertTrue("no notice: $notices", notices.isEmpty())
        assertNull("no attempt is spent", stored().lastAutoBackupAtMs)
        assertTrue(s.state.value.healthLine!!.contains("Waiting for a connection"))

        // The next launch, online.
        refresh = { set("fresh") }
        val next = reopen()
        autoBackup(next)
        assertEquals(1, uploads.size)
        assertEquals("the line goes", null, next.state.value.healthLine)
    }

    @Test
    fun `a 5xx or a proxy page at the token endpoint keeps the session`() {
        for (reply in listOf(
            DriveHttpError(503, "", "Google token endpoint: HTTP 503"),
            DriveHttpError(200, "<html>portal</html>", "non-JSON"),
            DriveHttpError(403, "<html>proxy</html>", "non-JSON"),
            DriveHttpError(401, """{"error":"invalid_client"}""", "invalid_client"),
        )) {
            refresh = { throw reply }
            val s = sync(DriveState(tokens = stale))
            autoBackup(s)
            assertNotNull("${reply.status} keeps the tokens", stored().tokens)
            assertFalse(stored().needsReconnect)
        }
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun `invalid_grant_ends_session_once`() {
        refresh = { throw invalidGrant }
        val s = sync(DriveState(tokens = stale, lastBackupSuccessAtMs = 5L))
        autoBackup(s)

        val after = stored()
        assertNull(after.tokens)
        assertTrue(after.needsReconnect)
        assertEquals("what Drive knows otherwise stays", 5L, after.lastBackupSuccessAtMs)
        assertEquals(listOf(DRIVE_RECONNECT_LINE), notices)
        assertTrue(s.state.value.needsReconnect)
        assertFalse(s.state.value.connected)

        // Later launches and runs say nothing new.
        val next = reopen()
        autoBackup(next)
        assertEquals(1, notices.size)
        assertTrue("the banner outlives a relaunch", next.state.value.needsReconnect)

        next.backupNow()
        settle()
        assertEquals("a tap is told, once", listOf(DRIVE_RECONNECT_LINE, DRIVE_RECONNECT_LINE), notices)
    }

    @Test
    fun `a set with no refresh token needs a reconnect`() {
        val s = sync(DriveState(tokens = set("old", refresh = null, expiresAt = clock - 1)))
        autoBackup(s)
        assertNull(stored().tokens)
        assertTrue(stored().needsReconnect)
        assertTrue("an expired token is not used", uploads.isEmpty())
    }

    @Test
    fun `reconnecting clears the banner`() {
        val s = sync(DriveState(needsReconnect = true, pendingVerifier = "v", pendingState = "st"))
        assertTrue(s.state.value.needsReconnect)
        s.handleCallback("code", "st", null)
        settle()
        assertFalse(s.state.value.needsReconnect)
        assertNotNull(stored().tokens)
    }

    @Test
    fun `a disconnect clears the banner`() {
        val s = sync(DriveState(needsReconnect = true))
        s.disconnect(quiet = true)
        settle()
        assertFalse(stored().needsReconnect)
    }

    @Test
    fun `concurrent_refresh_keeps_session`() {
        val gate = CompletableDeferred<Unit>()
        refresh = {
            gate.await()
            set("fresh", refresh = "r2")
        }
        val s = sync(DriveState(tokens = stale))
        s.backupNow()
        s.backupNow()
        Thread.sleep(100)
        gate.complete(Unit)
        settle()
        assertEquals("one refresh between them", 1, grants.get())
        assertEquals(2, uploads.size)
        assertEquals("r2", stored().tokens?.refreshToken)
    }

    @Test
    fun `refresh_after_sign_out_stores_nothing`() {
        val gate = CompletableDeferred<Unit>()
        refresh = {
            gate.await()
            set("fresh", refresh = "r2")
        }
        val s = sync(DriveState(tokens = stale))
        s.backupNow()
        Thread.sleep(100)
        s.disconnect(quiet = true)
        Thread.sleep(100)
        gate.complete(Unit)
        settle()
        assertNull("the disconnect stands", stored().tokens)
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun `invalid_grant after a disconnect leaves no banner`() {
        val gate = CompletableDeferred<Unit>()
        refresh = {
            gate.await()
            throw invalidGrant
        }
        val s = sync(DriveState(tokens = stale))
        s.backupNow()
        Thread.sleep(100)
        s.disconnect(quiet = true)
        Thread.sleep(100)
        gate.complete(Unit)
        settle()
        assertFalse(stored().needsReconnect)
        assertTrue(notices.none { it == DRIVE_RECONNECT_LINE })
    }

    @Test
    fun `a 401 refreshes once and retries`() {
        refresh = { set("fresh", refresh = "r2") }
        upload = { token -> if (token == "live") throw DriveHttpError(401, "", "Drive upload failed (HTTP 401).") }
        val s = sync(DriveState(tokens = set("live")))
        s.backupNow()
        settle()
        assertEquals(listOf("live crema-backup-1", "fresh crema-backup-1"), uploads)
        assertEquals(1, grants.get())
        assertTrue(notices.contains("Backed up to Google Drive"))
    }

    @Test
    fun `a 401 that won't clear is a refusal, said once, not an ended sign-in`() {
        refresh = { set("fresh", refresh = "r2") }
        upload = { throw DriveHttpError(401, "", "Drive upload failed (HTTP 401).") }
        val s = sync(DriveState(tokens = set("live")))
        s.backupNow()
        settle()
        assertEquals(2, uploads.size)
        assertEquals(1, grants.get())
        assertEquals(listOf("Google Drive backup failed: Drive upload failed (HTTP 401)."), notices)
        assertEquals("Google Drive refused the last backup.", s.state.value.healthLine)
        assertNotNull("the session stands", stored().tokens)
        assertFalse(stored().needsReconnect)
    }

    @Test
    fun `an automatic backup says a repeated 401 once, not every launch`() {
        refresh = { set("fresh", refresh = "r2") }
        upload = { throw DriveHttpError(401, "", "Drive upload failed (HTTP 401).") }
        autoBackup(sync(DriveState(tokens = set("live"))))
        assertEquals(listOf(DRIVE_REFUSED_LINE), notices)

        clock += 2 * 24 * 60 * 60 * 1000L
        autoBackup(reopen())
        assertEquals("the second one", 1, notices.size)
    }

    @Test
    fun `a tapped backup on a dead session says so once`() {
        refresh = { throw invalidGrant }
        val s = sync(DriveState(tokens = stale))
        s.backupNow()
        settle()
        assertEquals(listOf(DRIVE_RECONNECT_LINE), notices)
    }

    @Test
    fun `a refresh Google refuses for another reason names no null`() {
        refresh = { throw DriveHttpError(401, """{"error":"invalid_client"}""", "invalid_client") }
        val s = sync(DriveState(tokens = stale))
        s.backupNow()
        settle()
        assertEquals(listOf("Google Drive backup failed: Google Drive refused the request"), notices)
    }

    @Test
    fun `the attempt is stamped once a token is held`() {
        upload = { throw reason("dailyLimitExceeded") }
        val s = sync(DriveState(tokens = set("live")))
        autoBackup(s)
        assertEquals("a refusal waits a day", clock, stored().lastAutoBackupAtMs)
        assertEquals("Google Drive refused the last backup.", s.state.value.healthLine)
        assertTrue("no notice on a background run: $notices", notices.isEmpty())
    }

    @Test
    fun `a failure time can fix tries again next launch`() {
        for (failure in listOf(
            reason("rateLimitExceeded"),
            reason("userRateLimitExceeded"),
            DriveHttpError(429, "", "HTTP 429"),
            DriveHttpError(503, "", "HTTP 503"),
            IOException("connection reset"),
        )) {
            upload = { throw failure }
            val s = sync(DriveState(tokens = set("live"), lastAutoBackupAtMs = 1L))
            autoBackup(s)
            assertEquals("$failure", 1L, stored().lastAutoBackupAtMs)
            assertNotNull(s.state.value.healthLine)
        }
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `a full Drive stays in Settings without a daily notice`() {
        upload = { throw reason("storageQuotaExceeded") }
        val s = sync(DriveState(tokens = set("live")))
        autoBackup(s)
        assertEquals("Your Google Drive is full. Free up space to keep backing up.", s.state.value.healthLine)
        assertTrue(notices.isEmpty())
        assertEquals("not retried on every launch", clock, stored().lastAutoBackupAtMs)
        assertNotNull(stored().tokens)

        val next = reopen()
        assertEquals("it lasts", s.state.value.healthLine, next.state.value.healthLine)
    }

    @Test
    fun `a tapped backup says what happened, once`() {
        upload = { throw reason("storageQuotaExceeded") }
        val s = sync(DriveState(tokens = set("live")))
        s.backupNow()
        settle()
        assertEquals(listOf("Google Drive backup failed: your Google Drive is full"), notices)
    }

    @Test
    fun `a success clears what the last run left`() {
        upload = { throw reason("storageQuotaExceeded") }
        val s = sync(DriveState(tokens = set("live")))
        s.backupNow()
        settle()
        upload = {}
        s.backupNow()
        settle()
        assertNull(s.state.value.healthLine)
    }

    @Test
    fun `an unreadable stored status reads as nothing`() {
        assertNull(driveHealthLine("""{"kind":"SomethingNew"}"""))
        assertNull(driveHealthLine(null))
    }
}
