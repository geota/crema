package coffee.crema.visualizer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Visualizer backlog loop's stop / retry policy ([runUploadPass]) — the
 * Android side of upstream port issue 07. The core classifiers are stood in by
 * a JVM fake that applies the same rule (422 + "daily limit" in the body).
 */
class UploadPassTest {
    /** The exact reply `POST /shots/upload` sends at the free-plan cap (visualizer 3e9ba33c). */
    private val quotaBody =
        """{"error":"Could not save the provided file. You've reached your daily limit of 30 shots. Please consider upgrading to a premium account."}"""

    private val sleeps = mutableListOf<Long>()

    private val policy = UploadPolicy(
        quotaLimit = { status, body -> if (status == 422 && body.contains("daily limit", ignoreCase = true)) 30 else null },
        backoffMs = { status, attempt -> if (status == 429) 60_000L shl attempt else 1_000L shl attempt },
    )

    private suspend fun pass(items: List<String>, step: suspend (String) -> Boolean) =
        runUploadPass(items, policy, sleep = { sleeps += it }, step = step)

    @Test
    fun `the quota 422 stops the pass after one POST and names the cap`() = runTest {
        val posted = mutableListOf<String>()
        val r = pass(listOf("s1", "s2", "s3")) { id ->
            posted += id
            throw VisualizerError.Http(422, "Visualizer HTTP 422", quotaBody)
        }
        assertEquals(listOf("s1"), posted)
        assertEquals(PassStop.Quota(30), r.stop)
        assertEquals(0, r.uploaded)
        assertEquals(1, r.failed)
        assertEquals(
            "Visualizer's free plan uploads up to 30 shots a day — the rest will upload tomorrow.",
            r.stop?.notice,
        )
    }

    @Test
    fun `the quota after some uploads keeps the count and leaves the rest`() = runTest {
        val posted = mutableListOf<String>()
        val r = pass(listOf("s1", "s2", "s3", "s4")) { id ->
            posted += id
            if (id == "s3") throw VisualizerError.Http(422, "Visualizer HTTP 422", quotaBody)
            true
        }
        assertEquals(listOf("s1", "s2", "s3"), posted)
        assertEquals(PassResult(uploaded = 2, failed = 1, stop = PassStop.Quota(30)), r)
    }

    @Test
    fun `an ordinary 422 is logged and the pass goes on`() = runTest {
        val failures = mutableListOf<String>()
        val r = runUploadPass(
            listOf("s1", "s2"),
            policy,
            sleep = { sleeps += it },
            onFailure = { id, _ -> failures += id },
        ) { id ->
            if (id == "s1") throw VisualizerError.Http(422, "Visualizer HTTP 422", """{"error":"Could not save the provided file."}""")
            true
        }
        assertEquals(listOf("s1"), failures)
        assertEquals(PassResult(uploaded = 1, failed = 1), r)
        assertNull(r.stop)
    }

    @Test
    fun `a 429 waits a minute and retries the same shot`() = runTest {
        var calls = 0
        val r = pass(listOf("s1", "s2")) { id ->
            calls++
            if (id == "s1" && calls == 1) throw VisualizerError.Http(429, "Visualizer HTTP 429")
            true
        }
        assertEquals(listOf(60_000L), sleeps)
        assertEquals(PassResult(uploaded = 2), r)
    }

    @Test
    fun `a lasting 429 stops the pass after the retries`() = runTest {
        val posted = mutableListOf<String>()
        val r = pass(listOf("s1", "s2")) { id ->
            posted += id
            throw VisualizerError.Http(429, "Visualizer HTTP 429")
        }
        assertEquals(listOf("s1", "s1", "s1"), posted)
        assertEquals(listOf(60_000L, 120_000L), sleeps)
        assertEquals(PassStop.RateLimited, r.stop)
    }

    @Test
    fun `an expired session stops the pass`() = runTest {
        val r = pass(listOf("s1", "s2")) { throw VisualizerError.Auth() }
        assertEquals(PassStop.Auth, r.stop)
        assertEquals(1, r.failed)
    }

    @Test
    fun `describe names the quota and keeps other messages`() {
        assertEquals(quotaNotice(30), policy.describe(VisualizerError.Http(422, "Visualizer HTTP 422", quotaBody)))
        assertEquals("Visualizer HTTP 500", policy.describe(VisualizerError.Http(500, "Visualizer HTTP 500")))
    }
}
