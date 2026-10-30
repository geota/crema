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
 * AC-47 (Android half): the hub's pairing commit over real sockets
 * (spec §2.4 step 9). With the pin write delayed by 500 ms, a pairing followed
 * at once by a resume reaches the session and never meets `NOT_PAIRED`:
 * `PAIR_COMPLETE` (which the remote waits for) goes out only after the store
 * write and the live pin set insert. A store that can't write gets
 * `reject_pairing`: the remote is refused (`CANCELLED`) and nothing is pinned.
 */
class PinRaceTest {

    @Test
    fun `a resume right after pairing finds the pin even with a slow store`() {
        val rig = RelayRig().start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            rig.trust.storeDelayMs = 500
            val keys = RelayKeys.generate()
            val paired = runBlocking { rig.pairThrough(keys) }
            assertEquals(rig.hubKeys.deviceId(), paired.id)
            assertTrue(rig.trust.remotes.containsKey(keys.deviceId()), "pinned in the store")
            assertTrue(rig.trust.pins.contains(keys.publicKey()), "and in the live set")

            val refusals = CopyOnWriteArrayList<String>()
            val welcomed = AtomicBoolean(false)
            val link = ReconnectingClientLink(rig.url, scope, keys, paired.key)
            ProxyTransport(link, scope, onRefused = { reason, _, _ -> refusals += reason }, onWelcome = { _, _ -> welcomed.set(true) })
            runBlocking { eventually(what = "the resume") { welcomed.get() } }
            assertTrue(refusals.isEmpty(), "never NOT_PAIRED: $refusals")
        } finally {
            scope.cancel()
            rig.stop()
        }
    }

    /** The window closes between the code entry and the commit: `confirm_paired`
     *  fails (`PairingClosed`) and the shell restores what was there before. */
    private fun commitThatCantConfirm(prior: RelayRemotePin?) {
        val rig = RelayRig().start()
        try {
            val keys = RelayKeys.generate()
            if (prior != null) {
                rig.trust.remotes[keys.deviceId()] = prior.copy(id = keys.deviceId(), public = RelayKeyStore.b64(keys.publicKey()))
                rig.trust.pins.insert(keys.publicKey())
            }
            rig.trust.duringStore = { rig.trust.gate.closeWindow() }
            val dialer = PairingDialer(rig.url, "Kitchen tablet", keys, "New name", rig.scope, storePin = { _, _, _ -> true })
            rig.trust.gate.open(System.currentTimeMillis().toULong())
            dialer.start()
            runBlocking {
                eventually(what = "the code") { dialer.state.value is PairingState.ShowCode }
                val code = (dialer.state.value as PairingState.ShowCode).code
                eventually(what = "the request") { rig.trust.requests.isNotEmpty() }
                rig.trust.requests.last().enterCode(code, canControl = false)
                try {
                    eventually(what = "the outcome") { dialer.state.value !is PairingState.ShowCode && dialer.state.value !is PairingState.Waiting }
                } catch (e: AssertionError) {
                    // Seen once on CI: say where it stuck.
                    throw AssertionError("${e.message}; state ${dialer.state.value}, requests ${rig.trust.requests.size}, failures ${rig.trust.failures}", e)
                }
            }
            assertTrue(dialer.state.value !is PairingState.Paired, "${dialer.state.value}")
            if (prior == null) {
                assertTrue(!rig.trust.remotes.containsKey(keys.deviceId()), "a new key leaves the store")
                assertTrue(!rig.trust.pins.contains(keys.publicKey()), "and the live set")
            } else {
                assertEquals(prior.name, rig.trust.remotes[keys.deviceId()]?.name, "a re-pair keeps the old record")
                assertEquals(prior.scope, rig.trust.remotes[keys.deviceId()]?.scope, "with its old scope")
                assertTrue(rig.trust.pins.contains(keys.publicKey()), "and stays pinned")
            }
        } finally {
            rig.stop()
        }
    }

    @Test
    fun `a commit that can't confirm unpins a new key`() = commitThatCantConfirm(prior = null)

    @Test
    fun `a re-pair that can't confirm restores the old record`() =
        commitThatCantConfirm(prior = RelayRemotePin("x", "x", "Old name", SCOPE_CONTROL, pairedAtMs = 1))

    @Test
    fun `a pin that can't be saved is rejected and nothing is pinned`() {
        val rig = RelayRig().start()
        try {
            rig.trust.storeSaves = false
            val keys = RelayKeys.generate()
            val dialer = PairingDialer(rig.url, "Kitchen tablet", keys, "Phone", rig.scope, storePin = { _, _, _ -> true })
            rig.trust.gate.open(System.currentTimeMillis().toULong())
            dialer.start()
            runBlocking {
                eventually(what = "the code") { dialer.state.value is PairingState.ShowCode }
                val code = (dialer.state.value as PairingState.ShowCode).code
                eventually(what = "the request") { rig.trust.requests.isNotEmpty() }
                rig.trust.requests.last().enterCode(code, canControl = true)
                eventually(what = "the outcome") { dialer.state.value is PairingState.Refused || dialer.state.value is PairingState.Failed }
            }
            val outcome = dialer.state.value
            assertTrue(outcome is PairingState.Refused && outcome.reason.equals("Cancelled", ignoreCase = true), "$outcome")
            assertEquals(listOf("Phone"), rig.trust.notSaved.toList())
            assertTrue(!rig.trust.remotes.containsKey(keys.deviceId()), "nothing pinned in the store")
            assertTrue(!rig.trust.pins.contains(keys.publicKey()), "nor in the live set")
        } finally {
            rig.stop()
        }
    }
}
