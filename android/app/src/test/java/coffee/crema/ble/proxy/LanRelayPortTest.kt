package coffee.crema.ble.proxy

import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The hub reuses its last relay port, so a remote's saved host:port still
 * reaches it after a restart; it falls back to an ephemeral port only when
 * that port is taken.
 */
class LanRelayPortTest {

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

    @Test
    fun `a free requested port is the one it binds`() = runBlocking {
        val wanted = freePort()
        val server = LanRelayServer(hub(), requestedPort = wanted)
        try {
            assertEquals(wanted, server.start())
            assertTrue(accepts(wanted))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a taken port falls back to an ephemeral one`() = runBlocking {
        ServerSocket(0).use { taken ->
            val server = LanRelayServer(hub(), requestedPort = taken.localPort)
            try {
                val port = server.start()
                assertNotEquals(taken.localPort, port)
                assertTrue(port > 0 && accepts(port), "listening on the fallback port $port")
            } finally {
                server.stop()
            }
        }
    }
}
