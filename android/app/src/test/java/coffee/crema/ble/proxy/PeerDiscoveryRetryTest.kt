package coffee.crema.ble.proxy

import android.app.Application
import android.net.nsd.NsdServiceInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A peer that leaves the network, or a discovery that stops, while its
 *  resolve is pending or waiting to retry, never comes back into the list. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PeerDiscoveryRetryTest {

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

    @After
    fun tearDown() = scope.cancel()

    private fun service(name: String) = NsdServiceInfo().apply { serviceName = name }

    private fun resolved(name: String) = NsdServiceInfo().apply {
        serviceName = name
        host = java.net.InetAddress.getLoopbackAddress()
        port = 4242
        setAttribute("id", "peer-$name")
        setAttribute("role", "primary")
        setAttribute("de1", "1")
    }

    @Test
    fun `a lost peer's pending retry is cancelled`() {
        val calls = AtomicInteger(0)
        val discovery = PeerDiscovery(ApplicationProvider.getApplicationContext(), scope, "self") {
            if (calls.incrementAndGet() == 1) NsdResolveOutcome.TimedOut else NsdResolveOutcome.Resolved(resolved("tablet"))
        }
        discovery.onFound(service("tablet"))
        scheduler.runCurrent()
        assertEquals(1, calls.get())

        discovery.onLost(service("tablet"))
        scheduler.advanceTimeBy(NSD_RESOLVE_RETRY_MS * 5)
        scheduler.runCurrent()
        assertEquals(1, calls.get(), "no retry after the peer left")
        assertTrue(discovery.peers.value.isEmpty())
    }

    @Test
    fun `a resolve that finishes after the peer left is dropped`() {
        val answer = CompletableDeferred<NsdResolveOutcome<NsdServiceInfo>>()
        val discovery = PeerDiscovery(ApplicationProvider.getApplicationContext(), scope, "self") { answer.await() }
        discovery.onFound(service("tablet"))
        scheduler.runCurrent()
        discovery.onLost(service("tablet"))
        answer.complete(NsdResolveOutcome.Resolved(resolved("tablet")))
        scheduler.runCurrent()
        assertTrue(discovery.peers.value.isEmpty(), "a peer that left is not resurrected")
    }

    @Test
    fun `a peer still on the network is listed`() {
        val discovery = PeerDiscovery(ApplicationProvider.getApplicationContext(), scope, "self") {
            NsdResolveOutcome.Resolved(resolved("tablet"))
        }
        discovery.onFound(service("tablet"))
        scheduler.runCurrent()
        assertEquals(listOf("peer-tablet"), discovery.peers.value.map { it.id })
    }
}
