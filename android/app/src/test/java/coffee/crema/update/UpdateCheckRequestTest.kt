package coffee.crema.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** [checkForUpdates]' two GitHub GETs and their parse, over MockEngine. */
class UpdateCheckRequestTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(handler: (HttpRequestData) -> Pair<HttpStatusCode, String>) = HttpClient(
        MockEngine { req ->
            requests += req
            val (status, body) = handler(req)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) { expectSuccess = false }

    @Test
    fun `reads stable and nightly from the two release endpoints`() = runTest {
        val http = client { req ->
            HttpStatusCode.OK to when (req.url.encodedPath) {
                "/repos/geota/crema/releases/latest" ->
                    """{"tag_name":"v0.3.1","published_at":"2020-01-01T00:00:00Z"}"""
                "/repos/geota/crema/releases/tags/nightly" ->
                    """{"published_at":"2020-01-02T00:00:00Z","assets":[
                        {"name":"crema-nightly-0.3.1-nightly.9+gabc1234.apk.idsig"},
                        {"name":"crema-nightly-0.3.1-nightly.9+gabc1234.apk"}]}"""
                else -> error("unexpected ${req.url}")
            }
        }
        val info = checkForUpdates(json, http)
        assertNull(info.error)
        assertEquals("0.3.1", info.latestStable)
        assertEquals("0.3.1-nightly.9+gabc1234", info.latestNightly)
        assertNotNull(info.stableAgeDays)
        assertEquals(2, requests.size)
        for (r in requests) {
            assertEquals("api.github.com", r.url.host)
            assertEquals("application/vnd.github+json", r.headers[HttpHeaders.Accept])
            assertEquals("crema-app", r.headers[HttpHeaders.UserAgent])
        }
    }

    @Test
    fun `a non-2xx release is just absent`() = runTest {
        val http = client { req ->
            if (req.url.encodedPath.endsWith("/latest")) HttpStatusCode.NotFound to "{}"
            else HttpStatusCode.OK to """{"assets":[]}"""
        }
        val info = checkForUpdates(json, http)
        assertNull(info.error)
        assertNull(info.latestStable)
        assertNull(info.latestNightly)
    }

    @Test
    fun `a transport failure is reported, not thrown`() = runTest {
        val http = HttpClient(MockEngine { throw IOException("offline") })
        assertEquals("offline", checkForUpdates(json, http).error)
    }
}
