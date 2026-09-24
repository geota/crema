package coffee.crema.decent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The Decent client's HTTP → [DecentError] mapping, against a real local server. */
class DecentClientTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private lateinit var server: MockWebServer
    private lateinit var client: DecentClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = DecentClient(json, FakeDecentCore(), baseUrl = server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun reply(code: Int, body: String = "") = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private suspend inline fun <reified E : DecentError> uploadFails(): E {
        try {
            client.uploadShot("a@b.c", "token", "{}")
        } catch (e: DecentError) {
            assertTrue("expected ${E::class.simpleName}, got $e", e is E)
            return e as E
        }
        fail("expected ${E::class.simpleName}")
        error("unreachable")
    }

    @Test
    fun `401 is auth`() = runTest {
        reply(401)
        uploadFails<DecentError.Auth>()
    }

    @Test
    fun `403 is retryable network, not a permanent rejection`() = runTest {
        reply(403, "forbidden")
        assertEquals(403, uploadFails<DecentError.Network>().status)
    }

    @Test
    fun `408 and 429 are retryable`() = runTest {
        reply(408)
        assertEquals(408, uploadFails<DecentError.Network>().status)
        reply(429)
        assertEquals(429, uploadFails<DecentError.Network>().status)
    }

    @Test
    fun `5xx is retryable`() = runTest {
        reply(503, "down")
        val e = uploadFails<DecentError.Network>()
        assertEquals(503, e.status)
        assertTrue(e.recoverable)
    }

    @Test
    fun `other 4xx is a permanent rejection`() = runTest {
        reply(422, "serial not on account")
        val e = uploadFails<DecentError.Rejected>()
        assertEquals(422, e.status)
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
    fun `a body read failure is a network error after the request went out`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("x".repeat(64 * 1024))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val e = uploadFails<DecentError.Network>()
        assertNull(e.status)
        assertTrue(e.requestSent)
        assertTrue(e.offline)
    }

    @Test
    fun `an unreachable server is a network error before sending`() = runTest {
        val dead = DecentClient(json, FakeDecentCore(), baseUrl = "http://127.0.0.1:1/")
        try {
            dead.uploadShot("a@b.c", "t", "{}")
            fail("expected a network error")
        } catch (e: DecentError.Network) {
            assertNull(e.status)
            assertTrue(!e.requestSent)
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
        // Basic auth and the upload path go out as expected.
        val first = server.takeRequest()
        assertTrue(first.getHeader("Authorization")!!.startsWith("Basic "))
        assertEquals("/support/api/login_test", first.path)
    }
}
