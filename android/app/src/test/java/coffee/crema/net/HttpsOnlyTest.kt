package coffee.crema.net

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * The cleartext compensating control: the app permits cleartext (for the LAN
 * proxy's `ws://`), so every REST client refuses a non-https URL itself,
 * before anything reaches the engine — and the retry policy never re-tries it.
 */
class HttpsOnlyTest {
    private var calls = 0
    private val engine = MockEngine { calls++; respondOk("ok") }

    @Test
    fun `a REST client refuses http before sending`() = runTest {
        val http = HttpClients.create(15_000, 30_000, engine = engine, retryBaseDelayMs = 1)
        for (url in listOf("http://visualizer.coffee/api/me", "http://192.168.1.20:8080/x")) {
            try {
                http.get(url)
                fail("expected a refusal for $url")
            } catch (_: CleartextRefusedException) {
            }
        }
        assertEquals(0, calls)
        http.close()
    }

    @Test
    fun `a REST client sends https`() = runTest {
        val http = HttpClients.create(15_000, 30_000, engine = engine, retryBaseDelayMs = 1)
        assertEquals(200, http.get("https://visualizer.coffee/api/me").status.value)
        assertEquals(1, calls)
        http.close()
    }
}
