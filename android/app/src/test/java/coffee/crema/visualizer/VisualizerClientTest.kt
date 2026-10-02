package coffee.crema.visualizer

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import coffee.crema.net.RequestNotSentException

/** The Visualizer client's request shape and status → [VisualizerError] mapping, over MockEngine. */
class VisualizerClientTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()
    private var status = HttpStatusCode.OK
    /** Statuses for the first attempts (then [status]). */
    private val statuses = ArrayDeque<HttpStatusCode>()
    private var reply = ""
    private var failWith: Throwable? = null
    /** Fail only the first N attempts with [failWith], then answer normally. */
    private var failTimes = Int.MAX_VALUE

    private val client = VisualizerClient(
        json,
        MockEngine { req ->
            requests += req
            bodies += req.body.toByteArray().decodeToString()
            failWith?.let { if (requests.size <= failTimes) throw it }
            respond(reply, statuses.removeFirstOrNull() ?: status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
        retryBaseDelayMs = 1,
    )

    @After
    fun tearDown() = client.close()

    private fun last() = requests.last()

    @Test
    fun `GET me sends bearer auth and parses the account`() = runTest {
        reply = """{"id":"u1","name":"Ada","public":true,"avatar_url":"https://a/x.png"}"""
        val account = client.fetchAccount("tok")
        assertEquals("u1", account.id)
        assertEquals("Ada", account.name)
        assertTrue(account.public)
        val r = last()
        assertEquals(HttpMethod.Get, r.method)
        assertEquals("$API_BASE/me", r.url.toString())
        assertEquals("Bearer tok", r.headers[HttpHeaders.Authorization])
        assertEquals("application/json", r.headers[HttpHeaders.Accept])
        assertEquals("crema-android", r.headers[HttpHeaders.UserAgent])
        assertEquals("", bodies.last())
    }

    @Test
    fun `the catalogue search GETs canonical_coffee_bags with an encoded q and items`() = runTest {
        reply = """{"data":[],"paging":{"count":0,"page":1,"limit":10,"pages":1}}"""
        val body = client.searchCanonicalCoffeeBags("tok", "onyx & co")
        assertEquals(HttpMethod.Get, last().method)
        assertEquals("/api/canonical_coffee_bags", last().url.encodedPath)
        assertEquals("onyx & co", last().url.parameters["q"])
        assertEquals("10", last().url.parameters["items"])
        assertEquals("Bearer tok", last().headers[HttpHeaders.Authorization])
        assertEquals("1", body!!.jsonObject["paging"]!!.jsonObject["page"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET shots carries the paging query`() = runTest {
        reply = """{"data":[{"id":"s1","clock":"100","updated_at":"200"}],"paging":{"pages":3}}"""
        val (shots, pages) = client.listShots("tok", page = 2, items = 50)
        assertEquals(listOf(VisualizerClient.ShotSummary("s1", 100, 200)), shots)
        assertEquals(3, pages)
        assertEquals("page=2&items=50&sort=updated_at", last().url.encodedQuery)
    }

    @Test
    fun `POST upload sends the JSON payload and returns the id`() = runTest {
        reply = """{"id":"v-42"}"""
        val payload = buildJsonObject { put("clock", 1700000000); put("profile_title", "Blooming") }
        assertEquals("v-42", client.uploadShot("tok", payload))
        val r = last()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("$API_BASE/shots/upload", r.url.toString())
        assertEquals("Bearer tok", r.headers[HttpHeaders.Authorization])
        assertEquals("application/json; charset=UTF-8", r.body.contentType.toString())
        assertEquals(payload, json.parseToJsonElement(bodies.last()))
    }

    @Test
    fun `the premium probe is the web's sentinel roaster write - 201 premium then deleted`() = runTest {
        statuses += HttpStatusCode.Created
        status = HttpStatusCode.NoContent
        reply = """{"id":"r-sentinel"}"""
        assertEquals(true, client.probePremium("tok", nowMs = 42))
        val (post, delete) = requests.takeLast(2)
        assertEquals(HttpMethod.Post, post.method)
        assertEquals("$API_BASE/roasters", post.url.toString())
        assertEquals(
            json.parseToJsonElement("""{"roaster":{"name":"__crema_premium_probe_42","website":null,"canonical_roaster_id":null}}"""),
            json.parseToJsonElement(bodies[bodies.size - 2]),
        )
        assertEquals(HttpMethod.Delete, delete.method)
        assertEquals("$API_BASE/roasters/r-sentinel", delete.url.toString())
    }

    @Test
    fun `the premium probe reads 402 or 403 as free and anything else as inconclusive`() = runTest {
        reply = """{"error":"You must be a premium user to access this feature."}"""
        status = HttpStatusCode.Forbidden
        assertEquals(false, client.probePremium("tok"))
        status = HttpStatusCode.PaymentRequired
        assertEquals(false, client.probePremium("tok"))
        assertTrue("no cleanup without a sentinel", requests.none { it.method == HttpMethod.Delete })
        status = HttpStatusCode.UnprocessableEntity
        assertNull(client.probePremium("tok"))
        // A 401 propagates so withFreshToken can refresh and retry.
        status = HttpStatusCode.Unauthorized
        try {
            client.probePremium("tok")
            fail("expected Auth")
        } catch (_: VisualizerError.Auth) {
        }
    }

    @Test
    fun `a failed sentinel cleanup still reports premium`() = runTest {
        statuses += HttpStatusCode.Created
        status = HttpStatusCode.InternalServerError
        reply = """{"id":"r-sentinel"}"""
        assertEquals(true, client.probePremium("tok"))
    }

    @Test
    fun `PATCH wraps the shot body in a shot envelope`() = runTest {
        status = HttpStatusCode.NoContent
        reply = ""
        client.patchShot("tok", "v-42", buildJsonObject { put("bean_brand", "Onyx") })
        val r = last()
        assertEquals(HttpMethod.Patch, r.method)
        assertEquals("$API_BASE/shots/v-42", r.url.toString())
        assertEquals("Bearer tok", r.headers[HttpHeaders.Authorization])
        assertEquals(
            "Onyx",
            json.parseToJsonElement(bodies.last()).jsonObject["shot"]!!.jsonObject["bean_brand"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `an empty 2xx body is null`() = runTest {
        status = HttpStatusCode.NoContent
        assertNull(client.request("GET", "/x", "tok"))
    }

    private suspend inline fun <reified E : VisualizerError> fails(): E {
        try {
            client.request("GET", "/me", "tok")
        } catch (e: VisualizerError) {
            assertTrue("expected ${E::class.simpleName}, got $e", e is E)
            return e as E
        }
        fail("expected ${E::class.simpleName}")
        error("unreachable")
    }

    @Test
    fun `error statuses map onto the error union`() = runTest {
        status = HttpStatusCode.Unauthorized
        fails<VisualizerError.Auth>()
        status = HttpStatusCode.PaymentRequired
        fails<VisualizerError.PremiumGated>()
        status = HttpStatusCode.Forbidden
        fails<VisualizerError.PremiumGated>()
        status = HttpStatusCode.NotFound
        fails<VisualizerError.NotFound>()
        status = HttpStatusCode.InternalServerError
        assertEquals(500, fails<VisualizerError.Http>().status)
        status = HttpStatusCode.UnprocessableEntity
        reply = """{"error":"You've reached your daily limit of 30 shots."}"""
        val e = fails<VisualizerError.Http>()
        assertEquals(422, e.status)
        // The body rides along so the quota 422 can be told apart.
        assertEquals(reply, e.body)
    }

    @Test
    fun `a malformed 2xx body is a network error`() = runTest {
        reply = """{"id": """
        fails<VisualizerError.Network>()
    }

    @Test
    fun `a transport failure is a network error`() = runTest {
        failWith = ConnectException("Connection refused")
        fails<VisualizerError.Network>()
    }

    // --- The shared retry policy (net/HttpClients.kt) as Visualizer sees it.

    @Test
    fun `a GET is retried on 5xx`() = runTest {
        statuses += HttpStatusCode.ServiceUnavailable
        reply = """{"id":"u1","name":"Ada","public":true}"""
        assertEquals("u1", client.fetchAccount("tok").id)
        assertEquals(2, requests.size)
    }

    @Test
    fun `a GET 5xx surfaces after three attempts`() = runTest {
        status = HttpStatusCode.BadGateway
        assertEquals(502, fails<VisualizerError.Http>().status)
        assertEquals(3, requests.size)
    }

    @Test
    fun `401 is never retried here - the token refresh owns it`() = runTest {
        status = HttpStatusCode.Unauthorized
        fails<VisualizerError.Auth>()
        assertEquals(1, requests.size)
    }

    @Test
    fun `a GET that dies on a stale connection is re-sent`() = runTest {
        failWith = IOException("Connection reset")
        failTimes = 1
        reply = """{"id":"u1","name":"Ada","public":true}"""
        assertEquals("u1", client.fetchAccount("tok").id)
        assertEquals(2, requests.size)
    }

    @Test
    fun `a GET makes at most three attempts`() = runTest {
        failWith = IOException("Connection reset")
        fails<VisualizerError.Network>()
        assertEquals(3, requests.size)
    }

    @Test
    fun `a timed-out GET is retried`() = runTest {
        failWith = SocketTimeoutException("read timed out")
        fails<VisualizerError.Network>()
        assertEquals(3, requests.size)
    }

    @Test
    fun `a POST that may have been sent is never re-sent`() = runTest {
        failWith = IOException("Connection reset")
        failTimes = 1
        try {
            client.uploadShot("tok", buildJsonObject { put("clock", 1) })
            fail("expected a network error")
        } catch (e: VisualizerError.Network) {
            // expected
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun `a POST answered 5xx is not re-sent`() = runTest {
        status = HttpStatusCode.ServiceUnavailable
        try {
            client.uploadShot("tok", buildJsonObject { put("clock", 1) })
            fail("expected an HTTP error")
        } catch (e: VisualizerError.Http) {
            assertEquals(503, e.status)
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun `a PATCH answered 5xx or timed out is not re-sent`() = runTest {
        status = HttpStatusCode.InternalServerError
        try {
            client.patchShot("tok", "v-1", buildJsonObject { put("bean_brand", "Onyx") })
            fail("expected an HTTP error")
        } catch (_: VisualizerError.Http) {
        }
        assertEquals(1, requests.size)
        failWith = SocketTimeoutException("read timed out")
        try {
            client.patchShot("tok", "v-1", buildJsonObject { put("bean_brand", "Onyx") })
            fail("expected a network error")
        } catch (_: VisualizerError.Network) {
        }
        assertEquals(2, requests.size)
    }

    @Test
    fun `a POST that provably never left is re-sent`() = runTest {
        failWith = RequestNotSentException(ConnectException("Connection refused"))
        failTimes = 1
        reply = """{"id":"v-7"}"""
        assertEquals("v-7", client.uploadShot("tok", buildJsonObject { put("clock", 1) }))
        assertEquals(2, requests.size)
        // Same request both times: Visualizer sets no safe-resend marker.
        assertEquals(bodies[0], bodies[1])
        assertEquals(requests[0].url, requests[1].url)
    }
}
