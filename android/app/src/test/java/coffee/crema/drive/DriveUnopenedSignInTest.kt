package coffee.crema.drive

import android.app.Application
import android.content.ContextWrapper
import coffee.crema.security.DeviceSecretBox
import coffee.crema.security.FakeKeystore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.Executors

/**
 * SR-3a P2-2 through [DriveSync]: a Drive sign-in the keystore couldn't open at
 * start ([DriveState.unopened]) survives the session's saves, and an explicit
 * disconnect removes it, and new tokens replace it for good. Robolectric for
 * the PKCE helpers' `android.util.Base64`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DriveUnopenedSignInTest {
    private val dir: File = Files.createTempDirectory("drive-unopened").toFile()
    private val context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val keystore = FakeKeystore()
    private val store = DriveStore(context, json, DeviceSecretBox.create(keystore))
    // Its own thread: a busy shared pool from other tests can't delay these saves.
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    @After
    fun cleanUp() {
        scope.cancel()
        executor.shutdownNow()
        dir.deleteRecursively()
    }

    private fun unopenedStart(): DriveSync = runBlocking {
        store.save(DriveState(tokens = DriveTokenSet(accessToken = "g", refreshToken = "r", expiresAt = 1)))
        keystore.notReady = true
        DriveSync(
            store, json, scope, "cid", notify = {}, backupZip = { null }, backupFileName = { "b" }, applyRestore = { _, _ -> },
            exchangeCode = { _, _ -> issued },
            refreshToken = { throw java.io.IOException("refresh refused") },
        ).also {
                it.load()
                keystore.notReady = false
            }
    }

    private fun stored(): DriveState = runBlocking { store.load() }

    /** The raw file, for polling: [DriveStore.load] purges a save's temp file, so it can't poll mid-save. */
    private fun raw(): String = File(dir, "drive.json").takeIf(File::isFile)?.readText().orEmpty()

    private var issued = DriveTokenSet(accessToken = "new", refreshToken = "new-r", expiresAt = Long.MAX_VALUE)

    private fun signIn(sync: DriveSync) {
        var url = ""
        sync.beginSignIn { url = it }
        waitFor("authorize url") { url.isNotEmpty() }
        val state = URI(url).rawQuery.split('&').first { it.startsWith("state=") }.substringAfter('=')
        sync.handleCallback("code", state, null)
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        repeat(1000) {
            if (done()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out: $what")
    }

    @Test
    fun `a new sign-in replaces it, and a later session clear doesn't bring it back`() {
        // Already expired, and the refresh fails: the next use clears the session.
        issued = DriveTokenSet(accessToken = "new", refreshToken = "new-r", expiresAt = 0)
        val sync = unopenedStart()
        signIn(sync)
        waitFor("signed in") { sync.state.value.connected }
        sync.backupNow()
        waitFor("session cleared") { !raw().contains("accessToken") }
        Thread.sleep(200)
        assertNull(stored().tokens)
    }

    @Test
    fun `a new sign-in is stored`() {
        val sync = unopenedStart()
        signIn(sync)
        waitFor("signed in") { sync.state.value.connected }
        Thread.sleep(200)
        assertEquals("new", stored().tokens!!.accessToken)
    }

    @Test
    fun `disconnecting removes a sign-in that couldn't be opened`() {
        val sync = unopenedStart()
        assertEquals("g", runBlocking { DriveStore(context, json, DeviceSecretBox.create(keystore)).load() }.tokens!!.accessToken)
        sync.disconnect(quiet = true)
        // Polls the raw file: [DriveStore.load] purges a save's temp file, so it can't poll mid-save.
        waitFor("disconnected") { !File(dir, "drive.json").readText().contains("accessToken") }
        assertEquals(null, stored().tokens)
    }
}
