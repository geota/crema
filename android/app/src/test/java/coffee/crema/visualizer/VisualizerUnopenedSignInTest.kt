package coffee.crema.visualizer

import android.app.Application
import android.content.ContextWrapper
import coffee.crema.security.DeviceSecretBox
import coffee.crema.security.FakeKeystore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * SR-3a P2-2 through [VisualizerSync]: a sign-in the keystore couldn't open at
 * start ([VisualizerState.unopened]) survives the session's saves, but an
 * explicit sign-out removes it, and new tokens replace it for good (a later
 * session clear can't bring the old ones back). Robolectric for the PKCE
 * helpers' `android.util.Base64`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class VisualizerUnopenedSignInTest {
    private val dir: File = Files.createTempDirectory("vz-unopened").toFile()
    private val context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val keystore = FakeKeystore()
    private val store = VisualizerStore(context, json, DeviceSecretBox.create(keystore))
    private val engine = MockEngine { req ->
        val body = if (req.url.encodedPath.endsWith("/me")) """{"id":"u2","name":"New","public":true}""" else "{}"
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    // Its own thread: a busy shared pool from other tests can't delay these saves.
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private var issued = TokenSet("new-token", "new-refresh", expiresAt = System.currentTimeMillis() + 3_600_000)

    private fun sync() = VisualizerSync(
        store = store,
        client = VisualizerClient(json, engine, retryBaseDelayMs = 1),
        json = json,
        scope = scope,
        clientId = "cid",
        appVersion = "t",
        notify = {},
        onShotSynced = { _, _ -> },
        shotPatchBody = { _, _ -> null },
        exchangeCode = { _, _ -> issued },
    )

    @After
    fun cleanUp() {
        scope.cancel()
        executor.shutdownNow()
        dir.deleteRecursively()
    }

    /** A signed-in file, then a start whose keystore isn't ready: the sync loads signed out. */
    private fun unopenedStart(): VisualizerSync = runBlocking {
        store.save(VisualizerState(tokens = TokenSet("old-token", "old-refresh", 9), account = VisualizerAccount("1", "Old")))
        keystore.notReady = true
        sync().also {
            it.load()
            assertFalse(it.state.value.signedIn)
            keystore.notReady = false
        }
    }

    private fun stored(): VisualizerState = runBlocking { store.load() }

    /** The raw file, for polling: [VisualizerStore.load] purges a save's temp file, so it can't poll mid-save. */
    private fun raw(): String = File(dir, "visualizer.json").takeIf(File::isFile)?.readText().orEmpty()

    private fun waitFor(what: String, done: () -> Boolean) {
        repeat(1000) {
            if (done()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out: $what")
    }

    private fun signIn(sync: VisualizerSync) {
        var url = ""
        sync.beginSignIn { url = it }
        waitFor("authorize url") { url.isNotEmpty() }
        val state = URI(url).rawQuery.split('&').first { it.startsWith("state=") }.substringAfter('=')
        sync.handleCallback("code", state, null)
    }

    @Test
    fun `the session's saves keep the stored sign-in for the next start`() {
        val sync = unopenedStart()
        sync.setPrivacy("public")
        waitFor("privacy saved") { raw().contains("\"public\"") }
        assertEquals("old-token", stored().tokens!!.accessToken)
        assertEquals("Old", stored().account!!.name)
    }

    @Test
    fun `signing out removes a sign-in that couldn't be opened`() {
        val sync = unopenedStart()
        sync.signOut(quiet = true)
        waitFor("signed out") { !raw().contains("accessToken") }
        assertNull(stored().account)
    }

    @Test
    fun `a new sign-in replaces it, and a later session clear doesn't bring it back`() {
        // Tokens already expired, no refresh token: the first authorized call
        // after sign-in clears the session (refreshOrSignOut).
        issued = TokenSet("new-token", null, expiresAt = 0)
        val sync = unopenedStart()
        signIn(sync)
        waitFor("signed in") { raw().contains("\"New\"") }
        waitFor("session cleared") { !raw().contains("accessToken") }
        Thread.sleep(200)
        assertNull(stored().tokens)
    }

    @Test
    fun `a new sign-in is stored`() {
        val sync = unopenedStart()
        signIn(sync)
        waitFor("new tokens") { raw().contains("\"New\"") }
        Thread.sleep(200)
        assertEquals("new-token", stored().tokens!!.accessToken)
    }
}
