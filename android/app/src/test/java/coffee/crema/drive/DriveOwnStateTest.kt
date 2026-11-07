package coffee.crema.drive

import android.app.Application
import android.content.ContextWrapper
import coffee.crema.security.SecretBox
import coffee.crema.security.SecretCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Drive half of geota/crema#322: the redirect now lands in `onCreate` too,
 * and `handleCallback` no longer clears a handshake before knowing the redirect
 * is ours. Robolectric for the PKCE helpers' `android.util.Base64`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DriveOwnStateTest {
    @org.junit.Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val dir: File = Files.createTempDirectory("drive-own-state").toFile()
    private val file = File(dir, "drive.json")
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
    private val notices = CopyOnWriteArrayList<String>()
    private val exchanged = CopyOnWriteArrayList<String>()
    private val zips = AtomicInteger()
    private val saved = DriveState(
        tokens = DriveTokenSet(accessToken = "old", refreshToken = "old-r", expiresAt = Long.MAX_VALUE),
        lastAutoBackupAtMs = 77L,
        lastBackupSuccessAtMs = 88L,
    )

    private fun sync(scope: CoroutineScope = CoroutineScope(job + Dispatchers.Default)) = DriveSync(
        store, json, scope, "cid",
        notify = { notices += it },
        backupZip = {
            zips.incrementAndGet()
            null
        },
        backupFileName = { "b" },
        applyRestore = { _, _ -> },
        exchangeCode = { code, verifier ->
            exchanged += "$code/$verifier"
            DriveTokenSet(accessToken = "new", refreshToken = "new-r", expiresAt = Long.MAX_VALUE)
        },
        refreshToken = { error("no refresh expected") },
    )

    @After
    fun cleanUp() {
        job.cancel()
        dir.deleteRecursively()
    }

    private fun seed(state: DriveState) = runBlocking { assertTrue(store.save(state)) }

    private fun stored(): DriveState = runBlocking { store.load() }

    private fun waitFor(what: String, done: () -> Boolean) {
        repeat(1000) {
            if (done()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out: $what")
    }

    private fun settle() = Thread.sleep(150)

    @Test
    fun `drive_cold_redirect_keeps_state`() {
        seed(saved)
        val before = file.readBytes()
        val s = sync()

        s.handleCallback("code", "some-state", null)
        settle()
        assertArrayEquals("nothing is written before the load", before, file.readBytes())

        runBlocking { s.load() }
        settle()
        assertArrayEquals("no handshake is pending: the redirect isn't ours", before, file.readBytes())
        assertEquals(emptyList<String>(), notices.toList())
        assertEquals(true, s.state.value.connected)
        assertEquals("old", stored().tokens?.accessToken)
    }

    @Test
    fun `a sign-in the process died during finishes after the load`() {
        seed(saved.copy(tokens = null, pendingVerifier = "verifier", pendingState = "csrf", pendingStartedAtMs = 5L))
        val s = sync()

        s.handleCallback("code", "csrf", null)
        settle()
        assertEquals(emptyList<String>(), exchanged.toList())
        runBlocking { s.load() }
        waitFor("connected") { s.state.value.connected }

        assertEquals(listOf("code/verifier"), exchanged.toList())
        val after = stored()
        assertEquals("new", after.tokens?.accessToken)
        assertNull(after.pendingVerifier)
        assertEquals(88L, after.lastBackupSuccessAtMs)
    }

    @Test
    fun `a stray redirect keeps a handshake in progress`() {
        seed(saved.copy(tokens = null, pendingVerifier = "verifier", pendingState = "csrf"))
        val s = sync()
        runBlocking { s.load() }
        val before = file.readBytes()

        s.handleCallback("code", "evil", null)
        s.handleCallback(null, null, "access_denied")
        settle()

        assertArrayEquals(before, file.readBytes())
        assertEquals(emptyList<String>(), notices.toList())
        assertEquals(emptyList<String>(), exchanged.toList())
    }

    @Test
    fun `Google refusing the request keeps the error text`() {
        seed(saved.copy(tokens = null, pendingVerifier = "verifier", pendingState = "csrf"))
        val s = sync()
        runBlocking { s.load() }

        s.handleCallback(null, "csrf", "invalid_request")
        waitFor("refused") { notices.isNotEmpty() }

        assertEquals("Google Drive sign-in failed: invalid_request", notices.single())
        assertNull(stored().pendingState)
    }

    @Test
    fun `the daily backup waits for the stored tokens`() {
        seed(saved)
        val s = sync()

        s.autoBackupIfDue(true)
        settle()
        assertEquals(0, zips.get())

        runBlocking { s.load() }
        waitFor("the backup") { zips.get() == 1 }
        assertTrue("the attempt is stamped on the stored state", stored().lastAutoBackupAtMs!! > 77L)
        assertEquals("old", stored().tokens?.accessToken)
    }

    @Test
    fun `a load that never finishes reports, and leaves the file`() {
        seed(saved)
        val before = file.readBytes()
        val test = TestScope()
        val s = sync(test)

        s.handleCallback("code", "some-state", null)
        test.advanceTimeBy(coffee.crema.sync.OwnStateGate.TIMEOUT_MS + 1)
        test.runCurrent()

        assertEquals(1, notices.size)
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `erase waits for the load`() {
        seed(saved)
        val s = sync()
        val erase = CoroutineScope(job + Dispatchers.Default).launch { s.eraseAll() }
        settle()
        assertFalse(erase.isCompleted)
        assertTrue(file.exists())

        runBlocking {
            s.load()
            erase.join()
        }
        assertFalse(file.exists())
    }

    @Test
    fun `a handshake from before the restart isn't called abandoned`() {
        seed(saved.copy(tokens = null, pendingVerifier = "verifier", pendingState = "csrf", pendingStartedAtMs = 5L))
        val s = sync()
        runBlocking { s.load() }

        s.notePossibleAbandon()
        Thread.sleep(1_700)

        assertEquals("csrf", stored().pendingState)
        assertEquals(emptyList<String>(), notices.toList())
    }
}
