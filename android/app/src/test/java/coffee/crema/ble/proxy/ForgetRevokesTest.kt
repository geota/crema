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
 * AC-28: Forget on the hub (the live pin set first, then the store, then
 * [RelayHub.revoke]) ends the remote's live session within 1 s with an
 * authenticated `CLOSE(REVOKED)`, and a later resume from it gets
 * `NOT_PAIRED`. (The remote's "removed this device" copy and the switch to
 * normal are `ProxyControllerModeTest`'s "only an authenticated revoke makes a
 * remote normal".) AC-48's Android half is the second test; the core half is
 * `pin_race.rs::forget_between_msg1_and_admission`.
 */
class ForgetRevokesTest {

    @Test
    fun `forget ends the live session with an authenticated revoke and a later resume is not paired`() {
        val rig = RelayRig().start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val keys = RelayKeys.generate()
            rig.trust.pin(keys)
            val refusals = CopyOnWriteArrayList<String>()
            val welcomed = AtomicBoolean(false)
            val link = ReconnectingClientLink(rig.url, scope, keys, rig.hubKeys.publicKey())
            ProxyTransport(link, scope, onRefused = { reason, auth, _ -> refusals += "$reason/$auth" }, onWelcome = { _, _ -> welcomed.set(true) })
            runBlocking { eventually(what = "welcomed") { welcomed.get() } }
            assertTrue(rig.hub.hasClient(keys.deviceId()))

            val forgotAt = System.nanoTime()
            rig.trust.forget(keys.deviceId())
            rig.hub.revoke(keys.deviceId())
            runBlocking { eventually(ms = 1_000, what = "the revoke within 1 s") { refusals.isNotEmpty() } }
            assertTrue(System.nanoTime() - forgotAt < 1_000_000_000L)
            assertEquals(listOf("Revoked/true"), refusals.toList())
            runBlocking { eventually(what = "the session gone") { !rig.hub.hasClient(keys.deviceId()) } }

            // A later resume from the forgotten remote.
            val later = CopyOnWriteArrayList<String>()
            val again = ReconnectingClientLink(rig.url, scope, keys, rig.hubKeys.publicKey())
            ProxyTransport(again, scope, onRefused = { reason, auth, _ -> later += "$reason/$auth" })
            runBlocking { eventually(what = "the later refusal") { later.isNotEmpty() } }
            assertEquals(listOf("NotPaired/true"), later.toList())
        } finally {
            scope.cancel()
            rig.stop()
        }
    }

    /**
     * AC-48 (Android half): a Forget that lands between IK message 1 and the
     * remote's client-finished record. The relay probe holds that record back
     * while the hub forgets the remote (the live pin set first, as the
     * controller does); the core re-reads the live set at admission, so the
     * session is never admitted and gets an authenticated `CLOSE(REVOKED)`.
     */
    @Test
    fun `a forget between IK message 1 and admission is never admitted`() {
        val rig = RelayRig().start()
        val probe = WsRelayProbe(rig.port).start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val keys = RelayKeys.generate()
            rig.trust.pin(keys)
            val held = java.util.concurrent.CompletableFuture<WsRelayProbe.Msg>()
            probe.tamper = { _, m ->
                if (!m.text && m.bytes[0] == WsRelayProbe.KIND_RECORD && !held.isDone) { held.complete(m); emptyList() } else listOf(m)
            }
            val refusals = CopyOnWriteArrayList<String>()
            val link = ReconnectingClientLink(probe.url, scope, keys, rig.hubKeys.publicKey())
            ProxyTransport(link, scope, onRefused = { reason, auth, _ -> refusals += "$reason/$auth" })
            val finished = held.get(5, java.util.concurrent.TimeUnit.SECONDS)
            rig.trust.forget(keys.deviceId())
            runBlocking { probe.injectToHub(finished) }
            runBlocking { eventually(what = "the refusal") { refusals.isNotEmpty() } }
            assertEquals(listOf("Revoked/true"), refusals.toList())
            assertTrue(rig.trust.admitted.isEmpty(), "never admitted")
            assertTrue(!rig.hub.hasClient(keys.deviceId()))
        } finally {
            scope.cancel()
            probe.stop()
            rig.stop()
        }
    }

    /**
     * SR-3b P3-1: a Forget that lands after the acceptor's pin check but
     * before [RelayHub.serve] lists the session. Its revoke loop can't see
     * the session, so serve checks the pin again once it is listed: the
     * remote gets an authenticated `CLOSE(REVOKED)` and is never welcomed.
     */
    @Test
    fun `a forget while the session is being admitted still revokes it`() {
        val rig = RelayRig().start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val keys = RelayKeys.generate()
            rig.trust.pin(keys)
            rig.trust.duringAdmit = { peer ->
                rig.trust.forget(peer.deviceId)
                rig.hub.revoke(peer.deviceId) // too early: the session isn't listed yet
            }
            val refusals = CopyOnWriteArrayList<String>()
            val welcomed = AtomicBoolean(false)
            val link = ReconnectingClientLink(rig.url, scope, keys, rig.hubKeys.publicKey())
            ProxyTransport(link, scope, onRefused = { reason, auth, _ -> refusals += "$reason/$auth" }, onWelcome = { _, _ -> welcomed.set(true) })
            runBlocking { eventually(what = "the refusal") { refusals.isNotEmpty() } }
            assertEquals(listOf("Revoked/true"), refusals.toList())
            assertTrue(!welcomed.get(), "never welcomed")
            assertTrue(!rig.hub.hasClient(keys.deviceId()), "not served")
        } finally {
            scope.cancel()
            rig.stop()
        }
    }
}
