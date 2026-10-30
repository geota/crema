package coffee.crema.ble.proxy

import coffee.crema.core.RelayKeys
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The hub's code dialog opens at the remote's ClientHello; the remote's
 * PAKE_A follows a moment later, and the core takes the code only once it is
 * in. A code entered (or pasted) in that moment waits for PAKE_A instead of
 * being dropped, so the pairing completes. Seen as a CI flake: a code entered
 * at once was refused (InvalidState) and the attempt hung.
 */
class EarlyCodeEntryTest {

    @Test
    fun `a code entered before the remote's PAKE_A waits for it and pairs`() {
        val rig = RelayRig().start()
        val probe = WsRelayProbe(rig.port).start()
        try {
            // Hold the remote's first binary message (PAKE_A) back a little.
            probe.tamper = { _, m -> if (!m.text && m.bytes[0] == KIND_PAKE_A) Thread.sleep(400); listOf(m) }
            val keys = RelayKeys.generate()
            val stored = mutableListOf<String>()
            val dialer = PairingDialer(probe.url, "Kitchen tablet", keys, "Phone", rig.scope, storePin = { id, _, _ -> stored += id; true })
            rig.trust.gate.open(System.currentTimeMillis().toULong())
            dialer.start()
            runBlocking {
                eventually(what = "the request") { rig.trust.requests.isNotEmpty() }
                // The remote shows the code at AWAITING_CODE; its PAKE_A is still held.
                eventually(what = "the code") { dialer.state.value is PairingState.ShowCode }
                val code = (dialer.state.value as PairingState.ShowCode).code
                assertTrue(rig.trust.requests.last().enterCode(code, canControl = true), "the early code is taken once PAKE_A arrives")
                eventually(what = "paired") { dialer.state.value is PairingState.Paired }
            }
            assertTrue(stored.isNotEmpty())
            assertTrue(rig.trust.remotes.containsKey(keys.deviceId()))
        } finally {
            probe.stop()
            rig.stop()
        }
    }

    /** A pending code is dropped when the hub user cancels, and never applied
     *  once the remote's PAKE_A arrives. */
    @Test
    fun `a pending code is dropped on cancel and never applied`() {
        val rig = RelayRig().start()
        val probe = WsRelayProbe(rig.port).start()
        try {
            probe.tamper = { _, m -> if (!m.text && m.bytes[0] == KIND_PAKE_A) Thread.sleep(800); listOf(m) }
            val keys = RelayKeys.generate()
            val dialer = PairingDialer(probe.url, "Kitchen tablet", keys, "Phone", rig.scope, storePin = { _, _, _ -> true }, maxAttempts = 1)
            rig.trust.gate.open(System.currentTimeMillis().toULong())
            dialer.start()
            runBlocking {
                eventually(what = "the code") { dialer.state.value is PairingState.ShowCode && rig.trust.requests.isNotEmpty() }
                val request = rig.trust.requests.last()
                val code = (dialer.state.value as PairingState.ShowCode).code
                assertTrue(request.enterCode(code, canControl = true), "held for PAKE_A")
                assertTrue(request.holdsPendingCode)
                request.cancel()
                assertTrue(!request.holdsPendingCode, "cancel drops it")
                eventually(what = "the outcome") { dialer.state.value !is PairingState.ShowCode && dialer.state.value !is PairingState.Waiting }
                // PAKE_A lands after the cancel: still nothing applied.
                kotlinx.coroutines.delay(1_000)
            }
            assertTrue(dialer.state.value !is PairingState.Paired, "${dialer.state.value}")
            assertTrue(rig.trust.paired.isEmpty() && !rig.trust.remotes.containsKey(keys.deviceId()), "nothing paired")
            assertTrue(!rig.trust.requests.last().holdsPendingCode)
        } finally {
            probe.stop()
            rig.stop()
        }
    }

    /** No PAKE_A within the wait: the pending code is dropped (refused as before). */
    @Test
    fun `a pending code with no PAKE_A is dropped after the wait`() {
        val rig = RelayRig().start()
        val probe = WsRelayProbe(rig.port).start()
        try {
            // PAKE_A never reaches the hub.
            probe.tamper = { _, m -> if (!m.text && m.bytes[0] == KIND_PAKE_A) emptyList() else listOf(m) }
            val dialer = PairingDialer(probe.url, "Kitchen tablet", RelayKeys.generate(), "Phone", rig.scope, storePin = { _, _, _ -> true }, maxAttempts = 1)
            rig.trust.gate.open(System.currentTimeMillis().toULong())
            dialer.start()
            runBlocking {
                eventually(what = "the code") { dialer.state.value is PairingState.ShowCode && rig.trust.requests.isNotEmpty() }
                val request = rig.trust.requests.last()
                assertTrue(request.enterCode((dialer.state.value as PairingState.ShowCode).code, canControl = true))
                assertTrue(request.holdsPendingCode)
                eventually(ms = SecureRelayAcceptor.PAKE_A_WAIT_MS + 3_000, what = "dropped") { !request.holdsPendingCode }
            }
            assertTrue(rig.trust.paired.isEmpty())
        } finally {
            probe.stop()
            rig.stop()
        }
    }

    private companion object {
        /** The relay envelope kind of the remote's PAKE_A (spec §4.3). */
        const val KIND_PAKE_A: Byte = 0x20
    }
}
