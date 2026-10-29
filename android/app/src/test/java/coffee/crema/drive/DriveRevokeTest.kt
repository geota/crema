package coffee.crema.drive

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Final review 05 N-5: disconnecting Google Drive (and Erase all data)
 * revokes the grant at Google's revocation endpoint, preferring the refresh
 * token, which ends the whole grant. A failure never throws.
 */
class DriveRevokeTest {
    private val server = MockWebServer()

    @After fun tearDown() = server.close()

    private fun start(): String {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        return server.url("/revoke").toString()
    }

    @Test
    fun revokesTheRefreshTokenWhenThereIsOne() = runBlocking {
        val url = start()
        server.enqueue(MockResponse.Builder().code(200).build())

        val ok = revokeDriveGrant(DriveTokenSet("access-1", "refresh/1", expiresAt = 0), url)

        assertTrue(ok)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("application/x-www-form-urlencoded", req.headers["Content-Type"])
        assertEquals("token=refresh%2F1", req.body!!.utf8())
    }

    @Test
    fun fallsBackToTheAccessToken() = runBlocking {
        val url = start()
        server.enqueue(MockResponse.Builder().code(200).build())

        revokeDriveGrant(DriveTokenSet("access-1", null, expiresAt = 0), url)

        assertEquals("token=access-1", server.takeRequest(1, TimeUnit.SECONDS)!!.body!!.utf8())
    }

    @Test
    fun aRefusedOrUnreachableRevokeReportsFalseWithoutThrowing() = runBlocking {
        val url = start()
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":"invalid_token"}""").build())
        assertFalse(revokeDriveGrant(DriveTokenSet("a", "r", expiresAt = 0), url))
        server.close()
        assertFalse(revokeDriveGrant(DriveTokenSet("a", "r", expiresAt = 0), url))
    }
}
