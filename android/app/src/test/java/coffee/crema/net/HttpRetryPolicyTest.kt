package coffee.crema.net

import coffee.crema.decent.DecentClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/**
 * The ONE retry policy ([HttpClients.create]) over REAL OkHttp — Ktor's OkHttp
 * engine on a base built exactly like production's ([HttpClients.newBaseOkHttp]:
 * no OkHttp retries, the sent-signal interceptors) — against MockWebServer.
 * The backoff base is shrunk to a few ms; `Retry-After` is honoured as sent.
 */
class HttpRetryPolicyTest {
    private val server = MockWebServer()
    private val engines = mutableListOf<HttpClientEngine>()
    private val clients = mutableListOf<HttpClient>()

    @Before
    fun setUp() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        engines.forEach { it.close() }
        server.close()
    }

    private fun client(
        base: OkHttpClient = HttpClients.newBaseOkHttp(),
        socketTimeoutMs: Long = 5_000,
        baseDelayMs: Long = 5,
    ): HttpClient {
        val engine = HttpClients.okHttpEngine(base).also { engines += it }
        return HttpClients.create(
            connectTimeoutMs = 2_000,
            socketTimeoutMs = socketTimeoutMs,
            engine = engine,
            retryBaseDelayMs = baseDelayMs,
            httpsOnly = false,
        ).also { clients += it }
    }

    private fun url(path: String = "/x") = "http://127.0.0.1:${server.port}$path"

    private fun enqueue(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        server.enqueue(
            MockResponse.Builder().code(code).body(body).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build(),
        )
    }

    /** The server reads the whole request, then drops the socket before any response. */
    private fun enqueueDrop() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
    }

    /** The server reads the request and answers only after [ms] — past a short read timeout. */
    private fun enqueueSlow(ms: Long) {
        server.enqueue(MockResponse.Builder().headersDelay(ms, TimeUnit.MILLISECONDS).body("late").build())
    }

    private suspend fun HttpClient.get(u: String = url()) = request(u) { method = HttpMethod.Get }

    private suspend fun HttpClient.post(u: String = url("/upload"), safeResend: Boolean = false): HttpResponse =
        request(u) {
            method = HttpMethod.Post
            setBody("{}")
            if (safeResend) attributes.put(SafeResend, DecentClient.REPLACE_ON_RESEND)
        }

    private fun targets(n: Int = server.requestCount) = (1..n).map { server.takeRequest(1, TimeUnit.SECONDS)!!.target }

    // --- Responses

    @Test
    fun `GET 503 then 200 succeeds on the second attempt`() = runBlocking {
        enqueue(503)
        enqueue(200, "ok")
        val r = client().get()
        assertEquals(200, r.status.value)
        assertEquals("ok", r.bodyAsText())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `GET 503 three times surfaces the 503 after three attempts`() = runBlocking {
        repeat(3) { enqueue(503) }
        enqueue(200) // never reached
        assertEquals(503, client().get().status.value)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `408 is retried`() = runBlocking {
        enqueue(408)
        enqueue(200)
        assertEquals(200, client().get().status.value)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `400 404 and 401 are not retried`() = runBlocking {
        val c = client()
        for (code in listOf(400, 404, 401)) {
            enqueue(code)
            enqueue(200) // would be taken by a wrong retry
            assertEquals(code, c.get().status.value)
            assertEquals(200, c.get().status.value) // drain the spare
        }
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `429 waits for Retry-After`() = runBlocking {
        enqueue(429, "", "Retry-After" to "1")
        enqueue(200)
        val start = System.nanoTime()
        assertEquals(200, client().get().status.value)
        val waitedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("waited $waitedMs ms", waitedMs >= 950)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `POST 503 without the marker is not retried`() = runBlocking {
        enqueue(503)
        enqueue(200)
        assertEquals(503, client().post().status.value)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `POST 503 with the marker retries with replace, never duplicated`() = runBlocking {
        enqueue(503)
        enqueue(503)
        enqueue(200)
        assertEquals(200, client().post(safeResend = true).status.value)
        assertEquals(listOf("/upload", "/upload?replace=1", "/upload?replace=1"), targets())
    }

    // --- Exceptions

    /** Refuses the first [refusals] connects, then connects for real. */
    private class RefusingSocketFactory(refusals: Int) : SocketFactory() {
        val left = AtomicInteger(refusals)
        val connects = AtomicInteger()
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                connects.incrementAndGet()
                if (left.getAndDecrement() > 0) throw ConnectException("Connection refused (test)")
                super.connect(endpoint, timeout)
            }
        }
        override fun createSocket(host: String, port: Int) = throw UnsupportedOperationException()
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) = throw UnsupportedOperationException()
        override fun createSocket(host: InetAddress, port: Int) = throw UnsupportedOperationException()
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int) = throw UnsupportedOperationException()
    }

    @Test
    fun `POST to a refused port is retried without replace`() = runBlocking {
        val sockets = RefusingSocketFactory(refusals = 2)
        enqueue(200, "stored")
        val c = client(HttpClients.newBaseOkHttp().newBuilder().socketFactory(sockets).build())
        assertEquals(200, c.post(safeResend = true).status.value)
        assertEquals(3, sockets.connects.get())
        // Never reached the server before, so the resend is the ORIGINAL request.
        assertEquals(listOf("/upload"), targets())
    }

    @Test
    fun `POST to a dead port fails after three attempts, provably unsent`() = runBlocking {
        val dead = ServerSocket(0).use { it.localPort } // bound then released: nothing listens
        val calls = AtomicInteger()
        val base = HttpClients.newBaseOkHttp().newBuilder()
            .eventListenerFactory { object : okhttp3.EventListener() {
                override fun callStart(call: okhttp3.Call) { calls.incrementAndGet() }
            } }
            .build()
        try {
            client(base).post("http://127.0.0.1:$dead/upload")
            fail("expected a failure")
        } catch (e: IOException) {
            assertTrue("$e", e.requestNotSent())
            assertTrue("${e.unwrapNotSent()}", e.unwrapNotSent() is ConnectException)
        }
        assertEquals(3, calls.get())
    }

    @Test
    fun `POST dropped after the server read it is not retried without the marker`() = runBlocking {
        enqueueDrop()
        enqueue(200)
        try {
            client().post()
            fail("expected a failure")
        } catch (e: IOException) {
            assertFalse("$e", e.requestNotSent())
        }
        // Also proves OkHttp's own retryOnConnectionFailure is off in the engine.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `POST dropped after the server read it is retried with replace when marked`() = runBlocking {
        enqueueDrop()
        enqueue(200)
        assertEquals(200, client().post(safeResend = true).status.value)
        assertEquals(listOf("/upload", "/upload?replace=1"), targets())
    }

    @Test
    fun `a GET read timeout is retried`() = runBlocking {
        enqueueSlow(1_500)
        enqueue(200, "ok")
        val r = client(socketTimeoutMs = 300).get()
        assertEquals("ok", r.bodyAsText())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a POST read timeout is not retried without the marker`() = runBlocking {
        enqueueSlow(1_500)
        enqueue(200)
        try {
            client(socketTimeoutMs = 300).post()
            fail("expected a timeout")
        } catch (e: IOException) {
            assertFalse("$e", e.requestNotSent())
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a POST read timeout with the marker is retried with replace`() = runBlocking {
        enqueueSlow(1_500)
        enqueue(200)
        assertEquals(200, client(socketTimeoutMs = 300).post(safeResend = true).status.value)
        assertEquals(listOf("/upload", "/upload?replace=1"), targets())
    }

    @Test
    fun `cancelling during the backoff stops the retries`() = runBlocking {
        enqueue(503)
        enqueue(200)
        val c = client(baseDelayMs = 10_000)
        val call = async(Dispatchers.IO) { c.get() }
        server.takeRequest(5, TimeUnit.SECONDS)!!
        delay(200) // now inside the 10 s backoff
        withTimeout(2_000) { call.cancelAndJoin() }
        assertTrue(call.isCancelled)
        delay(200)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the shared base never lets OkHttp retry`() {
        assertFalse(HttpClients.base.retryOnConnectionFailure)
        assertFalse(HttpClients.newBaseOkHttp().retryOnConnectionFailure)
    }
}
