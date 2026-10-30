package coffee.crema.ble.proxy

import coffee.crema.core.RelayKeys
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-27, the socket half (the role half is `ProxyControllerModeTest`, "only
 * an authenticated revoke makes a remote normal" and "an advisory refusal
 * keeps the remote role"): over real sockets, each refusal a remote can meet
 * arrives with the right authentication flag (spec §2.9). Only a `CLOSE`
 * record (`Revoked`, `NotPaired`, `HubStopped`) is authenticated; a hub whose
 * key isn't the pinned one stops at `HostMismatch`, advisory, before IK
 * message 1 is sent.
 */
class SecureRefusalTest {

    private class Remote(val refusals: MutableList<String>, val welcomed: AtomicBoolean, val link: ReconnectingClientLink)

    private fun remote(rig: RelayRig, scope: CoroutineScope, keys: RelayKeys, hubKey: ByteArray = rig.hubKeys.publicKey()): Remote {
        val refusals = CopyOnWriteArrayList<String>()
        val welcomed = AtomicBoolean(false)
        val link = ReconnectingClientLink(rig.url, scope, keys, hubKey)
        ProxyTransport(link, scope, onRefused = { reason, auth, terminal -> refusals += "$reason/$auth/$terminal" }, onWelcome = { _, _ -> welcomed.set(true) })
        return Remote(refusals, welcomed, link)
    }

    private fun withRig(block: (RelayRig, CoroutineScope) -> Unit) {
        val rig = RelayRig().start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            block(rig, scope)
        } finally {
            scope.cancel()
            rig.stop()
        }
    }

    @Test
    fun `a hub that doesn't hold the pinned key is HostMismatch, advisory, and gets no IK message`() = withRig { rig, scope ->
        val keys = RelayKeys.generate()
        rig.trust.pin(keys)
        val r = remote(rig, scope, keys, hubKey = RelayKeys.generate().publicKey())
        runBlocking { eventually(what = "the refusal") { r.refusals.isNotEmpty() } }
        assertEquals("HostMismatch/false/true", r.refusals.first())
        assertTrue(!r.welcomed.get())
        assertTrue(rig.trust.admitted.isEmpty())
    }

    @Test
    fun `an unpaired remote gets an authenticated NotPaired`() = withRig { rig, scope ->
        val r = remote(rig, scope, RelayKeys.generate())
        runBlocking { eventually(what = "the refusal") { r.refusals.isNotEmpty() } }
        assertEquals(listOf("NotPaired/true/true"), r.refusals.toList())
    }

    @Test
    fun `a revoked session gets an authenticated Revoked`() = withRig { rig, scope ->
        val keys = RelayKeys.generate()
        rig.trust.pin(keys)
        val r = remote(rig, scope, keys)
        runBlocking { eventually(what = "welcomed") { r.welcomed.get() } }
        rig.trust.forget(keys.deviceId())
        rig.hub.revoke(keys.deviceId())
        runBlocking { eventually(ms = 2_000, what = "the revoke within 1 s") { r.refusals.isNotEmpty() } }
        assertEquals(listOf("Revoked/true/true"), r.refusals.toList())
    }

    @Test
    fun `a hub that stops hosting says so, authenticated, and the remote redials`() = withRig { rig, scope ->
        val keys = RelayKeys.generate()
        rig.trust.pin(keys)
        val r = remote(rig, scope, keys)
        runBlocking {
            eventually(what = "welcomed") { r.welcomed.get() }
            rig.acceptor.closeAll()
            eventually(what = "the refusal") { r.refusals.isNotEmpty() }
        }
        assertEquals("HubStopped/true/false", r.refusals.first(), "role and pin kept: the link keeps redialing")
    }
}
