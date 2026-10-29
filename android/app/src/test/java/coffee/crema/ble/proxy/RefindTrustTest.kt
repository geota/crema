package coffee.crema.ble.proxy

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.VmHarness
import coffee.crema.ui.canConnectDirectly
import coffee.crema.ui.remoteStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The re-find dials addresses from unauthenticated NSD advertisements, so it
 * keeps #199's unattended trust intact. Against three impostors that answer
 * a real remote ([MainViewModel]) over real sockets: one claiming the pinned
 * host's id without proving the secret, one with another host id, and one
 * that answers the Hello with a Denied. The remote never sends a pairing
 * request (or any Auth), never replaces the held secret, keeps the pin,
 * raises no "forget this host" prompt, stays a remote, and after three
 * refusals shows "Couldn't verify <hub>" with Connect directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RefindTrustTest {

    private val store = ViewModelStore()
    private val endpoints = mutableListOf<TestHubServer>()
    private val secret = PairingCrypto.newSecret()
    private val pairingFile get() = File(VmHarness.app().filesDir, "proxy-pairing.json")

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
        endpoints.forEach { runCatching { it.kill() } }
    }

    /** Advance the main looper's clock in steps until [done] holds, letting
     *  socket threads post back between steps. */
    private fun advanceUntil(what: String, maxMs: Long, done: () -> Boolean) {
        var spent = 0L
        while (!done()) {
            check(spent < maxMs) { "Not $what after ${maxMs}ms of virtual time" }
            VmHarness.advance(250)
            Thread.sleep(10)
            spent += 250
        }
    }

    private fun heldSecret(): String? =
        Json.parseToJsonElement(pairingFile.readText()).jsonObject["held"]?.jsonObject?.get("host-1")?.jsonPrimitive?.content

    @Test
    fun `an unattended re-find refuses impostors without pairing or touching the pin`() {
        val claimsPinnedId = TestHubServer.scripted { Frame.Challenge("host-1", "Kitchen tablet", PairingCrypto.newNonce()) }
        val otherHost = TestHubServer.scripted { Frame.Challenge("rogue-1", "Kitchen tablet", PairingCrypto.newNonce()) }
        val denier = TestHubServer.scripted { Frame.Denied(DenyReason.REVOKED) }
        endpoints += listOf(claimsPinnedId, otherHost, denier)

        // A remote restored on a target that's gone (nothing listens on :1),
        // paired with and pinned to host-1 (plaintext secret: Robolectric has
        // no keystore, and the store opens a pre-keystore value).
        pairingFile.writeText("""{"hostId":"this-device","held":{"host-1":"$secret"},"pinnedHost":"host-1"}""")
        VmHarness.persistRole("secondary", "127.0.0.1", 1)
        val vm = VmHarness.build(store)
        VmHarness.idle()
        val proxy = VmHarness.proxyController(vm)
        proxy.overridePeers(
            MutableStateFlow(
                listOf(claimsPinnedId, otherHost, denier).mapIndexed { i, e ->
                    Peer("nsd-impostor-$i", "Kitchen tablet", "primary", holdsDe1 = true, host = "127.0.0.1", port = e.port)
                },
            ),
        )

        advanceUntil("given up", maxMs = 150_000) { vm.ui.value.refindGaveUp }

        for (e in listOf(claimsPinnedId, otherHost, denier)) {
            assertTrue(e.received.any { "\"type\":\"hello\"" in it }, "the re-find dialed :${e.port}")
            assertEquals(emptyList(), e.auths, "no Auth, so no pairing request and no proof, to :${e.port}")
        }
        assertEquals("secondary", proxy.state.value.role, "an impostor's refusal or Denied doesn't make it normal")
        assertEquals("host-1", proxy.pairingStore.pinnedHost, "the pin is unchanged")
        assertEquals(secret, heldSecret(), "the held secret is unchanged")
        assertNull(vm.ui.value.changedHost, "no \"forget this host\" prompt from an advertisement")
        assertEquals(1, vm.ui.value.proxyPrimaryPort, "back on its own target")
        assertEquals("Couldn't verify 127.0.0.1", vm.ui.value.remoteStatus())
        assertTrue(vm.ui.value.canConnectDirectly())
    }
}
