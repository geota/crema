package coffee.crema.decent

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeStringUtf8
import coffee.crema.net.RequestNotSentException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import io.ktor.http.URLBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * The Decent client's HTTP → [DecentError] mapping, against Ktor's MockEngine,
 * with the shared retry policy (net/HttpClients.kt) in the loop: what the
 * client throws is the FINAL answer after its attempts.
 */
class DecentClientTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val handlers = ArrayDeque<suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>()
    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()
    private lateinit var client: DecentClient

    @Before
    fun setUp() {
        val engine = MockEngine { req ->
            requests += req
            bodies += req.body.toByteArray().decodeToString()
            handlers.removeFirst()(this, req)
        }
        client = DecentClient(json, FakeDecentCore(), baseUrl = "https://decent.test/", engine = engine, retryBaseDelayMs = 1)
    }

    @After
    fun tearDown() {
        client.close()
    }

    private fun reply(code: Int, body: String = "") {
        handlers += { respond(body, HttpStatusCode.fromValue(code)) }
    }

    private fun throwing(e: Throwable) {
        handlers += { throw e }
    }

    private suspend inline fun <reified E : DecentError> uploadFails(c: DecentClient = client): E {
        try {
            c.uploadShot("a@b.c", "token", "{}")
        } catch (e: DecentError) {
            assertTrue("expected ${E::class.simpleName}, got $e", e is E)
            return e as E
        }
        fail("expected ${E::class.simpleName}")
        error("unreachable")
    }

    private fun queries() = requests.map { it.url.encodedQuery }

    @Test
    fun `401 is auth, never retried`() = runTest {
        reply(401)
        uploadFails<DecentError.Auth>()
        assertEquals(1, requests.size)
    }

    @Test
    fun `403 is a network error, not a permanent rejection, and not retried`() = runTest {
        reply(403, "forbidden")
        assertEquals(403, uploadFails<DecentError.Network>().status)
        assertEquals(1, requests.size)
    }

    @Test
    fun `408 and 429 are retried, then surface as network errors`() = runTest {
        repeat(3) { reply(408) }
        assertEquals(408, uploadFails<DecentError.Network>().status)
        repeat(3) { reply(429) }
        assertEquals(429, uploadFails<DecentError.Network>().status)
        assertEquals(6, requests.size)
    }

    @Test
    fun `a 5xx upload is retried with replace, never duplicated`() = runTest {
        repeat(3) { reply(503, "down") }
        val e = uploadFails<DecentError.Network>()
        assertEquals(503, e.status)
        assertTrue(e.recoverable)
        // The first 503 proves the server saw the POST: resends overwrite.
        assertEquals(listOf("", "replace=1", "replace=1"), queries())
    }

    @Test
    fun `a 5xx then a 2xx uploads`() = runTest {
        reply(502)
        reply(200, """{"id":"9"}""")
        assertEquals("9", client.uploadShot("a@b.c", "token", "{}").id)
        assertEquals(listOf("", "replace=1"), queries())
    }

    @Test
    fun `other 4xx is a permanent rejection`() = runTest {
        reply(422, "serial not on account")
        val e = uploadFails<DecentError.Rejected>()
        assertEquals(422, e.status)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a 2xx zero is auth, not an upload`() = runTest {
        reply(200, "0")
        uploadFails<DecentError.Auth>()
    }

    @Test
    fun `a 2xx with an id returns it`() = runTest {
        reply(200, """{"id":"77"}""")
        assertEquals("77", client.uploadShot("a@b.c", "token", "{}").id)
    }

    @Test
    fun `a 2xx without an id uploads with a null id`() = runTest {
        reply(200, "")
        assertNull(client.uploadShot("a@b.c", "token", "{}").id)
    }

    @Test
    fun `the upload goes out as a basic-auth JSON POST`() = runTest {
        reply(200, """{"id":"1"}""")
        client.uploadShot("a@b.c", "tok", """{"x":1}""", replace = true)
        val r = requests.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("https://decent.test/support/api/shot_upload?replace=1", r.url.toString())
        // base64("a@b.c:tok")
        assertEquals("Basic YUBiLmM6dG9r", r.headers[HttpHeaders.Authorization])
        assertEquals("crema-android", r.headers[HttpHeaders.UserAgent])
        assertEquals("application/json; charset=UTF-8", r.body.contentType.toString())
        assertEquals("""{"x":1}""", bodies.single())
    }

    private fun midBodyReset() {
        handlers += {
            val ch = ByteChannel()
            ch.writeStringUtf8("x".repeat(1024))
            ch.flush()
            ch.close(IOException("connection reset mid-body"))
            respond(ch, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "65536"))
        }
    }

    @Test
    fun `a body read failure is an offline network error once the retries are spent`() = runTest {
        repeat(3) { midBodyReset() }
        val e = uploadFails<DecentError.Network>()
        assertNull(e.status)
        assertTrue(e.offline)
        assertEquals(listOf("", "replace=1", "replace=1"), queries())
    }

    @Test
    fun `a maybe-sent transport failure resends the upload with replace`() = runTest {
        throwing(SocketTimeoutException("read timed out"))
        reply(200, """{"id":"5"}""")
        assertEquals("5", client.uploadShot("a@b.c", "token", "{}").id)
        assertEquals(listOf("", "replace=1"), queries())
    }

    @Test
    fun `a not-sent failure resends the upload as it was`() = runTest {
        repeat(3) { throwing(RequestNotSentException(ConnectException("Connection refused"))) }
        val e = uploadFails<DecentError.Network>()
        assertNull(e.status)
        // The message is the original failure's, not the wrapper's.
        assertEquals("Couldn't reach decentespresso.com: Connection refused", e.message)
        assertEquals(listOf("", "", ""), queries())
    }

    @Test
    fun `not sent, then maybe sent, then replace sticks`() = runTest {
        throwing(RequestNotSentException(ConnectException("Connection refused")))
        throwing(IOException("Broken pipe"))
        reply(200, """{"id":"6"}""")
        client.uploadShot("a@b.c", "token", "{}")
        assertEquals(listOf("", "", "replace=1"), queries())
    }

    @Test
    fun `the replace-on-resend marker is idempotent`() {
        val b = HttpRequestBuilder().apply { url(URLBuilder("https://decent.test/support/api/shot_upload").build()) }
        DecentClient.REPLACE_ON_RESEND(b)
        DecentClient.REPLACE_ON_RESEND(b)
        assertEquals("replace=1", b.url.build().encodedQuery)
        val already = HttpRequestBuilder().apply { url(URLBuilder("https://decent.test/x?replace=1").build()) }
        DecentClient.REPLACE_ON_RESEND(already)
        assertEquals("replace=1", already.url.build().encodedQuery)
    }

    @Test
    fun `cancelling the caller propagates, never a network error`() = runTest {
        val started = CompletableDeferred<Unit>()
        handlers += {
            started.complete(Unit)
            CompletableDeferred<Unit>().await() // hang until cancelled
            error("unreachable")
        }
        val call = async(Dispatchers.Default) { client.uploadShot("a@b.c", "t", "{}") }
        withContext(Dispatchers.Default) { started.await() }
        call.cancel()
        try {
            call.await()
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun `a plain-http base is refused before anything is sent`() = runTest {
        val engine = MockEngine { error("must not be reached") }
        DecentClient(json, FakeDecentCore(), baseUrl = "http://decent.test/", engine = engine).use { c ->
            val e = uploadFails<DecentError.Network>(c)
            assertTrue(e.message!!, e.message!!.contains("non-https"))
        }
    }

    // --- The real OkHttp engine: what it actually throws on a live socket.

    private fun real(base: String) =
        DecentClient(json, FakeDecentCore(), baseUrl = base, retryBaseDelayMs = 1, allowCleartext = true)

    @Test
    fun `an unreachable server is an offline network error`() = runTest {
        real("http://127.0.0.1:1/").use { dead ->
            val e = uploadFails<DecentError.Network>(dead)
            assertNull(e.status)
            assertTrue(e.message!!, e.message!!.contains("refused", ignoreCase = true) || e.message!!.contains("connect", ignoreCase = true))
        }
    }

    @Test
    fun `an unresolvable host is an offline network error`() = runTest {
        real("http://crema-no-such-host.invalid/").use { dead ->
            val e = uploadFails<DecentError.Network>(dead)
            assertNull(e.status)
        }
    }

    @Test
    fun `a connection dropped mid-body on a real socket is resent with replace`() = runTest {
        ServerSocket(0).use { server ->
            val targets = java.util.concurrent.CopyOnWriteArrayList<String>()
            thread(isDaemon = true) {
                repeat(3) {
                    runCatching {
                        server.accept().use { s ->
                            val input = s.getInputStream().bufferedReader()
                            targets += input.readLine().split(" ")[1]
                            while (input.readLine()?.isNotEmpty() == true) Unit // rest of the head
                            s.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Length: 65536\r\n\r\n${"x".repeat(1024)}".toByteArray())
                                flush()
                            }
                        }
                    }
                }
            }
            real("http://127.0.0.1:${server.localPort}/").use { c ->
                val e = uploadFails<DecentError.Network>(c)
                assertNull(e.status)
            }
            assertEquals(
                listOf("/support/api/shot_upload", "/support/api/shot_upload?replace=1", "/support/api/shot_upload?replace=1"),
                targets.toList(),
            )
        }
    }

    @Test
    fun `login maps token, rejection and machines`() = runTest {
        reply(200, "abcdef0123456789\n")
        assertEquals("abcdef0123456789", client.login("a@b.c", "pw"))
        reply(200, "0")
        assertNull(client.login("a@b.c", "bad"))
        reply(200, "6262 DE1PRO\n6262 DE1PRO\n7000\n")
        assertEquals(listOf("6262", "7000"), client.fetchMachines("a@b.c", "t").map { it.serial })
        reply(200, "0")
        try {
            client.fetchMachines("a@b.c", "t")
            fail("expected auth")
        } catch (_: DecentError.Auth) {
        }
        // Basic auth and the paths go out as expected.
        val first = requests.first()
        assertEquals(HttpMethod.Get, first.method)
        assertTrue(first.headers[HttpHeaders.Authorization]!!.startsWith("Basic "))
        assertEquals("/support/api/login_test", first.url.encodedPath)
        assertEquals("/support/api/sn", requests[2].url.encodedPath)
        assertEquals("onlyespressomachines=1&withskus=1", requests[2].url.encodedQuery)
    }
}
