package coffee.crema.drive

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A Drive upload is resumable (#310 P2a): the session opens with the
 * metadata, and the file goes in one fixed-length PUT streamed from disk.
 * Without a session URL, one multipart request. Drive's size must be the file's.
 */
class DriveUploadTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val json = Json { ignoreUnknownKeys = true }
    private var giveLocation = true
    private var reportSize: String? = null
    private val requests = mutableListOf<Triple<String, String, ByteArray>>()

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) } else close()
    }

    @Before fun start() {
        server.createContext("/upload/drive/v3/files") { x ->
            val body = x.requestBody.readBytes()
            requests += Triple(x.requestMethod, x.requestURI.toString(), body)
            val query = x.requestURI.query.orEmpty()
            when {
                "uploadType=resumable" in query -> {
                    if (giveLocation) x.responseHeaders.add("Location", "$driveApiRoot/upload/drive/v3/files?upload_id=s1")
                    x.respond(200, "")
                }
                else -> x.respond(200, """{"id":"f1","name":"b.crema.zip","size":"${reportSize ?: body.size}"}""")
            }
        }
        server.start()
        driveApiRoot = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stop() {
        server.stop(0)
        driveApiRoot = "https://www.googleapis.com"
    }

    private fun file() = tmp.newFile("backup.zip").apply { writeBytes(ByteArray(100_000) { (it % 251).toByte() }) }

    @Test
    fun `opens a session, then PUTs the file's bytes`() {
        val f = file()
        val out = runBlocking { driveUploadBackup("tok", "b.crema.zip", f, json) }
        assertEquals("f1", out.id)
        assertEquals(listOf("POST", "PUT"), requests.map { it.first })
        assertTrue(requests[0].third.decodeToString().contains("\"name\":\"b.crema.zip\""))
        assertTrue(requests[1].second.contains("upload_id=s1"))
        assertTrue(requests[1].third.contentEquals(f.readBytes()), "the file, whole")
    }

    @Test
    fun `without a session URL, one multipart request carries the file`() {
        giveLocation = false
        reportSize = "100000"
        val f = file()
        runBlocking { driveUploadBackup("tok", "b.crema.zip", f, json) }
        assertEquals(listOf("POST", "POST"), requests.map { it.first })
        assertTrue(requests[1].second.contains("uploadType=multipart"))
        val body = requests[1].third
        assertTrue(body.size > f.length())
        val start = body.indexOfFirst { it == 0.toByte() }
        assertTrue(body.copyOfRange(start, start + f.length().toInt()).contentEquals(f.readBytes()))
    }

    @Test
    fun `a file Drive holds short of what was sent is a failed upload`() {
        reportSize = "99"
        assertFailsWith<IOException> { runBlocking { driveUploadBackup("tok", "b.crema.zip", file(), json) } }
    }
}
