package coffee.crema.ble.proxy

import coffee.crema.core.RelayKeys
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AC-25, passive capture on Android: the relay probe records a complete
 * pairing and a resumed session that carries a `Config` push. The capture
 * holds no `Frame` JSON, neither static key (raw or hex), and no text message
 * other than the ClientHellos.
 */
class PassiveCaptureProbeTest {

    @Test
    fun `a pairing and a resumed session leak no plaintext`() {
        val marker = "grinder-only-the-hub-knows"
        val rig = RelayRig().start()
        val probe = WsRelayProbe(rig.port).start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val remote = RelayKeys.generate()
            val paired = runBlocking { rig.pairThrough(remote, url = probe.url) }

            val welcomed = AtomicBoolean(false)
            val link = ReconnectingClientLink(probe.url, scope, remote, paired.key)
            var config = ""
            ProxyTransport(link, scope, onWelcome = { _, _ -> welcomed.set(true) }, onConfig = { config = it })
            runBlocking { eventually(what = "the resume") { welcomed.get() } }
            rig.hub.pushConfig("""{"grinderModel":"$marker"}""")
            runBlocking { eventually(what = "the config push") { marker in config } }

            val texts = probe.captured.filter { it.text }.map { it.bytes.decodeToString() }
            assertTrue(texts.isNotEmpty() && texts.all { it.startsWith("{\"type\":\"hello\"") }, "only ClientHellos in clear: $texts")
            assertEquals(2, texts.size, "one per connection: the pairing and the resume")
            val all = probe.captured.map { String(it.bytes, Charsets.ISO_8859_1) }
            val binary = probe.captured.filterNot { it.text }.map { String(it.bytes, Charsets.ISO_8859_1) }
            for (b in binary) {
                assertFalse("\"type\"" in b, "Frame JSON on the wire")
                assertFalse(marker in b, "the config in clear")
            }
            for (key in listOf(remote.publicKey(), rig.hubKeys.publicKey(), remote.privateForStorage())) {
                val raw = String(key, Charsets.ISO_8859_1)
                val hex = key.joinToString("") { "%02x".format(it) }
                for (m in all) {
                    assertFalse(raw in m, "a static key in the capture")
                    assertFalse(hex in m, "a static key's hex in the capture")
                }
            }
        } finally {
            scope.cancel()
            probe.stop()
            rig.stop()
        }
    }
}
