package coffee.crema.visualizer

import android.content.ContextWrapper
import coffee.crema.history.StoredShot
import coffee.crema.security.SecretBox
import coffee.crema.security.SecretCipher
import coffee.crema.ui.TelemetrySample
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
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
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * One token refresh at a time, compared before it clears or stores anything, and
 * which account a session is before anything goes to it. The refresh mirrors the
 * web `token-vault.ts`; `/me` runs through the host-built core's
 * `account_change_action`.
 */
class VisualizerSessionTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir: File = Files.createTempDirectory("vz-session").toFile()
    private val context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
    private val box = SecretBox(
        object : SecretCipher {
            override fun seal(plain: ByteArray) = ByteArray(12) + plain
            override fun open(sealed: ByteArray) = sealed.copyOfRange(12, sealed.size)
        },
    )
    private val store = VisualizerStore(context, json, box)

    @org.junit.Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    private val uploads = CopyOnWriteArrayList<String>()
    private val deletes = CopyOnWriteArrayList<String>()
    private var me: Pair<HttpStatusCode, String> = HttpStatusCode.OK to """{"id":"u1","name":"Ada","public":true}"""
    private val engine = MockEngine { req ->
        val path = req.url.encodedPath
        val headers = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            path.endsWith("/me") -> respond(me.second, me.first, headers)
            path.endsWith("/shots/upload") -> {
                uploads += req.headers[HttpHeaders.Authorization].orEmpty()
                respond("""{"id":"v-1"}""", HttpStatusCode.OK, headers)
            }
            req.method.value == "DELETE" -> {
                deletes += path
                respond("", HttpStatusCode.NoContent, headers)
            }
            else -> respond("{}", HttpStatusCode.OK, headers)
        }
    }
    private val client = VisualizerClient(json, engine, retryBaseDelayMs = 1)
    private val job = SupervisorJob()
    private val notices = CopyOnWriteArrayList<String>()
    private val history = mutableMapOf<String, StoredShot>()
    private val grants = AtomicInteger()
    private var clock = 1_800_000_000_000L
    private var refresh: suspend (String) -> TokenSet = { error("no refresh expected") }
    private var issued = TokenSet("y-token", "y-refresh", expiresAt = Long.MAX_VALUE)
    private var rowsLoad: suspend () -> Unit = {}

    private val sample = TelemetrySample(
        elapsedMs = 0, pressure = 9f, flow = 2f, headTemp = 93f, mixTemp = 92f, weight = null, weightFlow = null,
        dispensedVolume = 0f, resistance = null, resistanceWeight = null, setHeadTemp = 93f, setGroupPressure = 9f,
        setGroupFlow = 0f, frameNumber = 1,
    )

    private fun shot(id: String, completedAt: Long = 1) =
        StoredShot(id = id, completedAtMs = completedAt, durationMs = 30_000, profileName = "Test", samples = listOf(sample))
            .also { history[id] = it }

    private val live = TokenSet("tok", "rt", expiresAt = Long.MAX_VALUE)
    private val expired = TokenSet("tok", "rt", expiresAt = 0)

    private fun sync(state: VisualizerState): VisualizerSync {
        runBlocking { store.save(state) }
        return reopen()
    }

    private fun reopen() = VisualizerSync(
        store = store,
        client = client,
        json = json,
        scope = CoroutineScope(job + Dispatchers.Default),
        clientId = "cid",
        appVersion = "t",
        notify = { notices += it },
        onShotSynced = { local, remote -> history[local] = history.getValue(local).copy(visualizerId = remote) },
        currentShot = { history[it] },
        allShots = { history.values.toList() },
        seriesReady = rowsLoad,
        rowsWaitMs = 50,
        now = { clock },
        exchangeCode = { _, _ -> issued },
        refreshTokens = {
            grants.incrementAndGet()
            refresh(it)
        },
        revoke = {},
        retryTimers = false,
    ).also { runBlocking { it.load() } }

    @After
    fun tearDown() {
        job.cancel()
        dir.deleteRecursively()
    }

    private fun settle() = runBlocking { repeat(6) { job.children.toList().forEach { it.join() } } }

    private fun stored() = runBlocking { store.load() }

    /** A sign-in landing: the redirect, the exchange, then `/me`. */
    private fun signIn() {
        runBlocking { store.save(stored().copy(pendingVerifier = "v", pendingState = "st")) }
        val again = reopen()
        again.handleCallback("code", "st", null)
        settle()
        lastSync = again
    }

    private lateinit var lastSync: VisualizerSync

    // ── Single flight ────────────────────────────────────────────────────────

    @Test
    fun `concurrent_refresh_keeps_session`() {
        val gate = CompletableDeferred<Unit>()
        refresh = { spent ->
            gate.await()
            // Doorkeeper rotates: the same token twice is an invalid_grant.
            if (grants.get() > 1) throw TokenEndpointError(400, """{"error":"invalid_grant"}""", "invalid_grant")
            TokenSet("fresh", "$spent-2", expiresAt = Long.MAX_VALUE)
        }
        val s = sync(VisualizerState(tokens = expired))
        s.uploadShot(shot("a"), silent = true)
        s.uploadShot(shot("b"), silent = true)
        Thread.sleep(100)
        gate.complete(Unit)
        settle()
        assertEquals("one refresh between them", 1, grants.get())
        assertEquals(listOf("Bearer fresh", "Bearer fresh"), uploads)
        assertEquals("rt-2", stored().tokens?.refreshToken)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `a rotated token from another caller answers a 401 without a refresh`() = runBlocking {
        val guarded = SingleFlight(live)
        assertEquals("fresh", guarded.refresh(live, rejected = true)?.accessToken)
        assertEquals("a second caller holding the old set takes the new one", "fresh", guarded.refresh(live, rejected = true)?.accessToken)
        assertEquals(1, guarded.grants)
    }

    @Test
    fun `refresh_after_sign_out_stores_nothing`() {
        val gate = CompletableDeferred<Unit>()
        refresh = {
            gate.await()
            TokenSet("fresh", "rt-2", expiresAt = Long.MAX_VALUE)
        }
        val s = sync(VisualizerState(tokens = expired))
        s.uploadShot(shot("a"), silent = true)
        Thread.sleep(100)
        s.signOut(quiet = true)
        Thread.sleep(100)
        gate.complete(Unit)
        settle()
        assertNull("the sign-out stands", stored().tokens)
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun `refresh_after_sign_out_stores_nothing - a sign-in as Y during it`() {
        val gate = CompletableDeferred<Unit>()
        refresh = {
            gate.await()
            TokenSet("x-fresh", "x-2", expiresAt = Long.MAX_VALUE)
        }
        val s = sync(VisualizerState(tokens = expired, pendingVerifier = "v", pendingState = "st"))
        s.uploadShot(shot("a"), silent = true)
        Thread.sleep(100)
        s.handleCallback("code", "st", null)
        Thread.sleep(100)
        gate.complete(Unit)
        settle()
        assertEquals("Y's tokens stay", "y-refresh", stored().tokens?.refreshToken)
    }

    @Test
    fun `invalid_grant for a replaced token leaves the new session alone`() {
        val gate = CompletableDeferred<Unit>()
        refresh = {
            gate.await()
            throw TokenEndpointError(400, """{"error":"invalid_grant"}""", "invalid_grant")
        }
        val s = sync(VisualizerState(tokens = expired, pendingVerifier = "v", pendingState = "st"))
        s.uploadShot(shot("a"), silent = true)
        Thread.sleep(100)
        s.handleCallback("code", "st", null)
        Thread.sleep(100)
        gate.complete(Unit)
        settle()
        assertEquals("y-refresh", stored().tokens?.refreshToken)
        assertFalse(stored().needsReconnect)
        assertTrue(notices.none { it == VISUALIZER_RECONNECT_LINE })
    }

    @Test
    fun `a refresh that leaves out the refresh token keeps the one in use`() {
        refresh = { TokenSet("fresh", null, expiresAt = Long.MAX_VALUE) }
        val s = sync(VisualizerState(tokens = expired))
        s.uploadShot(shot("a"), silent = true)
        settle()
        assertEquals("rt", stored().tokens?.refreshToken)
    }

    @Test
    fun `invalid_grant_ends_session_once`() {
        refresh = { throw TokenEndpointError(400, """{"error":"invalid_grant"}""", "invalid_grant") }
        val account = VisualizerAccount(id = "u1", name = "Ada", public = true, avatarUrl = "")
        val s = sync(VisualizerState(tokens = expired, account = account, shotPullCursor = 7L))
        s.uploadShot(shot("a"), silent = true)
        s.uploadShot(shot("b"), silent = true)
        settle()
        assertEquals("one notice: $notices", listOf(VISUALIZER_RECONNECT_LINE), notices)
        val after = stored()
        assertNull(after.tokens)
        assertTrue(after.needsReconnect)
        assertEquals("u1", after.account?.id)
        assertEquals(7L, after.shotPullCursor)
        assertTrue(s.state.value.needsReconnect)
        assertTrue("the banner outlives a relaunch", reopen().state.value.needsReconnect)
    }

    // ── Which account ────────────────────────────────────────────────────────

    @Test
    fun `the first sign-in notes the account and asks nothing`() {
        sync(VisualizerState())
        signIn()
        assertEquals("u1", stored().syncAccountId)
        assertFalse(lastSync.state.value.accountChangeAsk)
        assertTrue(lastSync.enabled)
    }

    @Test
    fun `an id is seeded from the cached account, so the first reconnect is covered`() {
        val account = VisualizerAccount(id = "u1", name = "Ada", public = true, avatarUrl = "")
        val s = sync(VisualizerState(tokens = live, account = account))
        s.signOut(quiet = true)
        settle()
        assertEquals("u1", stored().syncAccountId)
        assertNull(stored().account)
    }

    @Test
    fun `reconnect_other_account_asks_first`() {
        shot("old-shot")
        val tomb = ShotTombstone("gone", "v-gone", 1L, deleteRemote = true)
        sync(
            VisualizerState(
                syncAccountId = "u1",
                shotPullCursor = 99L,
                shotTombstones = listOf(tomb),
                baristaPending = setOf("x"),
                retryQueue = listOf(QueuedUpload("old-shot", 1, 0L)),
            ),
        )
        me = HttpStatusCode.OK to """{"id":"u2","name":"Bo","public":false}"""
        signIn()
        val v = lastSync

        val after = stored()
        assertEquals("u2", after.syncAccountId)
        assertNull("the cursor starts over", after.shotPullCursor)
        assertTrue("marks go", after.baristaPending.isEmpty())
        assertTrue("what was queued for the old account goes", after.retryQueue.isEmpty())
        assertEquals("kept for the old account", "u1", after.shotTombstones.single().parkedFor)
        assertTrue(v.state.value.accountChangeAsk)
        assertFalse(v.enabled)
        assertTrue(notices.any { "different Visualizer account" in it })

        assertFalse(v.uploadShot(shot("direct"), silent = true))
        assertFalse(v.maybeAutoUpload(shot("auto"), null))
        runBlocking { v.drainRetryQueue() }
        v.uploadAllUnsynced(history.values.toList())
        settle()
        assertTrue("nothing went up: $uploads", uploads.isEmpty())
        assertTrue("a parked delete stays: $deletes", deletes.isEmpty())
    }

    @Test
    fun `an account change parks unbound tombstones and drops attempt marks`() {
        shot("old-shot")
        val tomb = UnboundTombstone("gone", 1_000, 30_000, 1L, deleteRemote = true, attemptedAt = 1L)
        sync(VisualizerState(syncAccountId = "u1", unboundTombstones = listOf(tomb), uploadAttempted = mapOf("other" to 1L)))
        me = HttpStatusCode.OK to """{"id":"u2","name":"Bo","public":false}"""
        signIn()
        val after = stored()
        assertEquals("u1", after.unboundTombstones.single().parkedFor)
        assertTrue(after.uploadAttempted.isEmpty())
    }

    @Test
    fun `an unbound tombstone comes back with its own account`() {
        val tomb = UnboundTombstone("gone", 1_000, 30_000, 1L, parkedFor = "u1")
        sync(VisualizerState(syncAccountId = "u2", unboundTombstones = listOf(tomb)))
        signIn()
        assertNull(stored().unboundTombstones.single().parkedFor)
    }

    @Test
    fun `the same account comes back with everything in place`() {
        val tomb = ShotTombstone("gone", "v-gone", 1L, deleteRemote = true)
        sync(VisualizerState(syncAccountId = "u1", shotPullCursor = 99L, shotTombstones = listOf(tomb), needsReconnect = true))
        signIn()
        val after = stored()
        assertEquals(99L, after.shotPullCursor)
        assertNull(after.shotTombstones.single().parkedFor)
        assertFalse(after.needsReconnect)
        assertFalse(lastSync.state.value.accountChangeAsk)
    }

    @Test
    fun `a parked delete runs once its own account is back`() {
        val tomb = ShotTombstone("gone", "v-gone", 1L, deleteRemote = true, parkedFor = "u1")
        sync(VisualizerState(syncAccountId = "u2"))
        runBlocking { store.save(stored().copy(shotTombstones = listOf(tomb))) }
        signIn()
        lastSync.answerAccountChange(upload = false)
        settle()
        assertNull(stored().shotTombstones.single().parkedFor)
        runBlocking { lastSync.drainRetryQueue() }
        settle()
        assertEquals(1, deletes.size)
    }

    @Test
    fun `yes uploads the backlog`() {
        shot("old-shot")
        sync(VisualizerState(syncAccountId = "u1"))
        me = HttpStatusCode.OK to """{"id":"u2","name":"Bo","public":false}"""
        signIn()
        lastSync.answerAccountChange(upload = true)
        settle()
        assertEquals(1, uploads.size)
        assertFalse(stored().accountChangeAsk)
        assertNull(stored().uploadExcludedBefore)
    }

    @Test
    fun `decline_upload_lets_new_shots_flow`() {
        val old = shot("old-shot", completedAt = clock - 1)
        sync(VisualizerState(syncAccountId = "u1"))
        me = HttpStatusCode.OK to """{"id":"u2","name":"Bo","public":false}"""
        signIn()
        val v = lastSync
        v.answerAccountChange(upload = false)
        settle()

        assertTrue(v.enabled)
        assertFalse("the shots of then stay off", v.inBacklog(old))
        assertTrue(v.unsent(history.values.toList()).isEmpty())

        val new = shot("new-shot", completedAt = clock + 1)
        assertTrue(v.inBacklog(new))
        assertTrue(v.maybeAutoUpload(new, null))
        settle()
        assertEquals(1, uploads.size)
    }

    @Test
    fun `a change with nothing to upload asks nothing`() {
        sync(VisualizerState(syncAccountId = "u1"))
        me = HttpStatusCode.OK to """{"id":"u2","name":"Bo","public":false}"""
        signIn()
        assertFalse(lastSync.state.value.accountChangeAsk)
        assertTrue(lastSync.enabled)
    }

    @Test
    fun `unknown_identity_holds_uploads`() {
        me = HttpStatusCode.ServiceUnavailable to "{}"
        sync(VisualizerState(syncAccountId = "u1"))
        signIn()
        val v = lastSync
        assertTrue(v.state.value.signedIn)
        assertTrue(v.state.value.accountUnconfirmed)
        assertFalse(v.enabled)
        assertFalse(v.uploadShot(shot("a"), silent = true))
        assertFalse(v.maybeAutoUpload(shot("b"), null))
        v.syncNow(history.values.toList())
        v.uploadAllUnsynced(history.values.toList())
        settle()
        assertTrue(uploads.isEmpty())
        assertTrue("it survives a relaunch", reopen().state.value.accountUnconfirmed)
    }

    @Test
    fun `a blank id is not an answer`() {
        me = HttpStatusCode.OK to """{"name":"Ada"}"""
        sync(VisualizerState())
        signIn()
        assertTrue(lastSync.state.value.accountUnconfirmed)
    }

    @Test
    fun `identity_retries_from_drain`() {
        me = HttpStatusCode.ServiceUnavailable to "{}"
        sync(VisualizerState(syncAccountId = "u1", retryQueue = listOf(QueuedUpload("a", 1, 0L))))
        shot("a")
        signIn()
        val v = lastSync
        assertEquals(0, runBlocking { v.drainRetryQueue() })
        assertTrue(uploads.isEmpty())

        me = HttpStatusCode.OK to """{"id":"u1","name":"Ada","public":true}"""
        v.drainSoon()
        settle()
        assertFalse(v.state.value.accountUnconfirmed)
        assertTrue(v.enabled)
        assertEquals("the queue moves again", 1, uploads.size)
    }

    @Test
    fun `rows that never loaded leave the ask standing`() {
        rowsLoad = { awaitCancellation() }
        sync(VisualizerState(syncAccountId = "u1"))
        me = HttpStatusCode.OK to """{"id":"u2","name":"Bo","public":false}"""
        signIn()
        assertTrue("an empty list read before the rows says nothing", lastSync.state.value.accountChangeAsk)
        assertFalse(lastSync.enabled)
    }

    @Test
    fun `a session with no id on record asks at the drain, with no hold`() {
        val s = sync(VisualizerState(tokens = live))
        assertTrue(s.enabled)
        s.drainSoon()
        settle()
        assertEquals("u1", stored().syncAccountId)
    }

    @Test
    fun `a failed ask for a session with no id holds nothing`() {
        me = HttpStatusCode.ServiceUnavailable to "{}"
        shot("a")
        val s = sync(VisualizerState(tokens = live, retryQueue = listOf(QueuedUpload("a", 1, 0L))))
        runBlocking { s.drainRetryQueue() }
        assertEquals(1, uploads.size)
    }

    @Test
    fun `forgetting clears the row and keeps the account on record`() {
        val account = VisualizerAccount(id = "u1", name = "Ada", public = true, avatarUrl = "")
        val s = sync(VisualizerState(account = account, needsReconnect = true))
        s.signOut(quiet = true)
        settle()
        assertFalse(s.state.value.needsReconnect)
        assertEquals("u1", stored().syncAccountId)
    }

    @Test
    fun `erase forgets the account on record`() {
        val s = sync(VisualizerState(tokens = live, syncAccountId = "u1"))
        runBlocking { s.eraseAll() }
        settle()
        assertNull(stored().syncAccountId)
    }
}

/** A small harness for the rotation case: the 401 path over a stored set that already moved. */
private class SingleFlight(start: TokenSet) {
    private var tokens: TokenSet? = start
    var grants = 0
        private set
    private val guard = coffee.crema.sync.SingleFlightRefresh<TokenSet>(
        stored = { tokens },
        refreshTokenOf = { it.refreshToken },
        expiring = { false },
        grant = {
            grants += 1
            TokenSet("fresh", "rt-2", expiresAt = Long.MAX_VALUE)
        },
        endsSession = { false },
        store = { spent, fresh -> if (tokens?.refreshToken == spent) tokens = fresh },
        end = {},
    )

    suspend fun refresh(observed: TokenSet, rejected: Boolean) = guard.refresh(observed, rejected)
}
