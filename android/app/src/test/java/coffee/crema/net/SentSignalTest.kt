package coffee.crema.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.concurrent.thread

/**
 * The sent signal ([SentTracking] → [RequestNotSentException]) as it comes
 * out of Ktor's OkHttp engine — ONE attempt, no retry plugin, so each case
 * shows exactly what the retry policy and the clients' mapping get to see.
 */
class SentSignalTest {
    private val engines = mutableListOf<HttpClientEngine>()
    private val server = MockWebServer()

    @After
    fun tearDown() {
        engines.forEach { it.close() }
        server.close()
    }

    private fun client(base: OkHttpClient = HttpClients.newBaseOkHttp(), connectMs: Long = 2_000, socketMs: Long = 2_000) =
        HttpClient(HttpClients.okHttpEngine(base).also { engines += it }) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = connectMs
                socketTimeoutMillis = socketMs
            }
        }

    /** One POST; returns the failure it ends in. */
    private fun failureOf(url: String, c: HttpClient = client()): Throwable = runBlocking {
        try {
            val r = c.request(url) { method = HttpMethod.Post; setBody("{}") }
            r.bodyAsText()
            fail("expected a failure, got ${r.status}")
            error("unreachable")
        } catch (e: IOException) {
            e
        }
    }

    // --- Not sent

    @Test
    fun `a refused connection is not sent`() {
        val dead = ServerSocket(0).use { it.localPort }
        val e = failureOf("http://127.0.0.1:$dead/x")
        assertTrue("$e", e.requestNotSent())
        assertTrue("${e.unwrapNotSent()}", e.unwrapNotSent() is ConnectException)
    }

    @Test
    fun `an unknown host is not sent`() {
        val e = failureOf("http://crema-no-such-host.invalid/x")
        assertTrue("$e", e.requestNotSent())
        assertTrue("${e.unwrapNotSent()}", e.unwrapNotSent() is UnknownHostException)
    }

    @Test
    fun `a connect timeout is not sent`() {
        // Deterministic: the socket's connect times out the way the JDK reports it.
        val sockets = object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    throw SocketTimeoutException("connect timed out")
                }
            }
            override fun createSocket(host: String, port: Int) = throw UnsupportedOperationException()
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) = throw UnsupportedOperationException()
            override fun createSocket(host: InetAddress, port: Int) = throw UnsupportedOperationException()
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int) = throw UnsupportedOperationException()
        }
        val e = failureOf("http://127.0.0.1:9/x", client(HttpClients.newBaseOkHttp().newBuilder().socketFactory(sockets).build()))
        // Ktor maps a bare SocketTimeoutException; the wrapper passes through intact.
        assertTrue("$e", e.requestNotSent())
        assertTrue("${e.unwrapNotSent()}", e.unwrapNotSent() is SocketTimeoutException)
    }

    @Test
    fun `a real connect timeout to a blackhole address is not sent`() {
        // 10.255.255.1 normally drops SYNs (a genuine connect timeout); some
        // networks answer "unreachable" at once instead — not sent either way.
        val e = failureOf("http://10.255.255.1:81/x", client(connectMs = 300))
        assertTrue("$e", e.requestNotSent())
    }

    // --- Sent (maybe processed)

    @Test
    fun `a read timeout counts as sent`() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        server.enqueue(MockResponse.Builder().headersDelay(1_500, TimeUnit.MILLISECONDS).build())
        val e = failureOf("http://127.0.0.1:${server.port}/x", client(socketMs = 300))
        assertFalse("$e", e.requestNotSent())
        assertTrue("$e", generateSequence(e) { it.cause }.any { it is SocketTimeoutException })
    }

    @Test
    fun `a socket dropped after the request was read counts as sent`() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        val e = failureOf("http://127.0.0.1:${server.port}/x")
        assertFalse("$e", e.requestNotSent())
    }

    @Test
    fun `a reset after the response headers counts as sent`() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { ss ->
            thread(isDaemon = true) {
                runCatching {
                    ss.accept().use { s ->
                        val input = s.getInputStream().bufferedReader()
                        while (input.readLine()?.isNotEmpty() == true) Unit
                        s.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 65536\r\n\r\n${"x".repeat(512)}".toByteArray())
                            flush()
                        }
                        s.setSoLinger(true, 0) // RST, not FIN
                    }
                }
            }
            val e = failureOf("http://127.0.0.1:${ss.localPort}/x")
            assertFalse("$e", e.requestNotSent())
        }
    }
}
