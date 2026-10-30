package coffee.crema.ble.proxy

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The hub reuses its last relay port, so a remote's saved host:port still
 * reaches it after a restart; it falls back to an ephemeral port only when
 * that port is taken. A taken port must never surface as an uncaught
 * exception: on Android that crashes the hub, on every launch while the port
 * stays taken (validation P1b R1).
 */
class LanRelayPortTest {

    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeTest
    fun recordUncaught() {
        // Arms kotlinx-coroutines-test's collector too: the runTest in
        // assertNothingLeaked reports anything uncaught since this one.
        runTest { }
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @AfterTest
    fun restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun hub() = RelayHub(
        primaryId = "host-1",
        primaryName = "Kitchen tablet",
        roster = { emptyList() },
        readSource = { _, _, _ -> ByteArray(0) },
        isSnapshotChar = { _, _ -> false },
    )

    /** A port nothing listens on right now. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun accepts(port: Int): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000) } }.isSuccess

    private fun assertNothingLeaked() {
        Thread.sleep(300) // CIO's jobs run on Dispatchers.IO
        assertEquals(emptyList(), uncaught.toList(), "uncaught exceptions")
        runTest { }
    }

    private fun fallsBack(server: (takenPort: Int) -> LanRelayServer) {
        ServerSocket(0).use { taken ->
            val relay = server(taken.localPort)
            try {
                val port = runBlocking { relay.start() }
                assertNotEquals(taken.localPort, port)
                assertTrue(port > 0 && accepts(port), "listening on the fallback port $port")
            } finally {
                relay.stop()
            }
        }
        assertNothingLeaked()
    }

    @Test
    fun `a free requested port is the one it binds`() {
        val wanted = freePort()
        val server = LanRelayServer(hub(), requestedPort = wanted)
        try {
            assertEquals(wanted, runBlocking { server.start() })
            assertTrue(accepts(wanted))
        } finally {
            server.stop()
        }
        assertNothingLeaked()
    }

    @Test
    fun `a taken port falls back to an ephemeral one`() =
        fallsBack { taken -> LanRelayServer(hub(), requestedPort = taken) }

    /** The port is taken between the probe and CIO's bind: CIO's own failure
     *  is caught too, and nothing escapes its accept job. */
    @Test
    fun `a port taken after the probe still falls back without leaking`() =
        fallsBack { taken -> LanRelayServer(hub(), requestedPort = taken, isPortFree = { true }) }
}
