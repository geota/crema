package coffee.crema.ble

import coffee.crema.ble.ReconnectKicker.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a foreground / retry / Bluetooth-on kick does per device. */
class ReconnectKickPlanTest {

    private fun plan(
        remembered: Boolean = true,
        userDisconnected: Boolean = false,
        phase: Phase = Phase.IDLE,
        link: LinkState = LinkState.IDLE,
        scanAgeMs: Long? = null,
    ) = planKick(remembered, userDisconnected, phase, link, scanAgeMs)

    @Test
    fun `a live session between or during attempts gets kicked`() {
        assertEquals(KickAction.KICK_SESSION, plan(phase = Phase.WAITING, link = LinkState.CONNECTING))
        assertEquals(KickAction.KICK_SESSION, plan(phase = Phase.RETRYING, link = LinkState.SCANNING))
        assertEquals(KickAction.KICK_SESSION, plan(phase = Phase.ATTEMPTING, link = LinkState.CONNECTING))
    }

    @Test
    fun `READY, user-disconnected and forgotten devices are left alone`() {
        assertEquals(KickAction.NONE, plan(phase = Phase.CONNECTED, link = LinkState.READY))
        assertEquals(KickAction.NONE, plan(userDisconnected = true, link = LinkState.DISCONNECTED))
        assertEquals(KickAction.NONE, plan(userDisconnected = true, phase = Phase.WAITING))
        assertEquals(KickAction.NONE, plan(remembered = false, phase = Phase.WAITING))
    }

    @Test
    fun `no session - a stale scan or nothing becomes a direct connect`() {
        assertEquals(KickAction.DIRECT_CONNECT, plan(link = LinkState.IDLE))
        assertEquals(KickAction.DIRECT_CONNECT, plan(link = LinkState.DISCONNECTED))
        assertEquals(KickAction.DIRECT_CONNECT, plan(link = LinkState.SCANNING, scanAgeMs = FRESH_SCAN_MS))
        assertEquals(KickAction.DIRECT_CONNECT, plan(link = LinkState.SCANNING, scanAgeMs = null))
    }

    @Test
    fun `a fresh cold-start scan and a starting handshake get their chance`() {
        assertEquals(KickAction.NONE, plan(link = LinkState.SCANNING, scanAgeMs = 2_000))
        assertEquals(KickAction.NONE, plan(link = LinkState.CONNECTING))
    }

    @Test
    fun `reconnecting status shows for re-attempts and cold-start scans, not a first connect`() {
        assertTrue(showsReconnecting(true, false, Phase.WAITING, LinkState.CONNECTING))
        assertTrue(showsReconnecting(true, false, Phase.RETRYING, LinkState.SCANNING))
        assertTrue(showsReconnecting(true, false, Phase.IDLE, LinkState.SCANNING))
        assertFalse(showsReconnecting(true, false, Phase.ATTEMPTING, LinkState.CONNECTING))
        assertFalse(showsReconnecting(true, false, Phase.CONNECTED, LinkState.READY))
        assertFalse(showsReconnecting(true, true, Phase.WAITING, LinkState.CONNECTING))
        assertFalse(showsReconnecting(false, false, Phase.WAITING, LinkState.CONNECTING))
    }
}
