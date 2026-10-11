package coffee.crema.firmware

import coffee.crema.net.HttpClients
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess

/**
 * Downloads a firmware image from Decent's own update channel
 * (`fast.decentespresso.com/download/sync/<channel>/fw/bootfwupdate.dat`, the
 * file de1app syncs — `de1plus/updater.tcl:969`). Over the shared OkHttp engine
 * with the shared retry policy; https only. The caller verifies the bytes
 * against the core's pinned manifest before anything is flashed.
 */
class KtorFirmwareFetcher(
    private val http: HttpClient = HttpClients.create(connectTimeoutMs = 15_000, socketTimeoutMs = 30_000),
) : FirmwareFetcher {
    override suspend fun fetch(url: String, expected: Long, onProgress: (Float) -> Unit): ByteArray {
        val res = http.get(url) {
            onDownload { received, total ->
                val of = total ?: expected
                if (of > 0) onProgress((received.toFloat() / of).coerceIn(0f, 1f))
            }
        }
        check(res.status.isSuccess()) { "HTTP ${res.status.value}" }
        return res.readRawBytes()
    }
}
