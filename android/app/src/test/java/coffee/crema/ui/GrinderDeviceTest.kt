package coffee.crema.ui

import coffee.crema.core.FollowStatus
import coffee.crema.core.GrindScale
import coffee.crema.core.Grinder
import coffee.crema.core.GrinderFollowView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Show in Devices and the rail": when the Devices grinder row, the rail's
 * grinder dot and the switch itself show, and what the row says.
 */
class GrinderDeviceTest {
    private val motto = Grinder(id = "grinder:motto", name = "Motto 80", scale = GrindScale(0f, 999f, 1f, "μm"), presetId = "bookoo-motto-80", updatedAt = 1L)
    private val niche = Grinder(id = "grinder:niche", name = "Niche Zero", scale = GrindScale(0f, 50f, 1f, ""), presetId = "niche-zero", updatedAt = 1L)

    private fun state(on: Boolean, vararg grinders: Grinder, status: GrinderLinkStatus = GrinderLinkStatus.OFF) = MainUiState(
        grinderInDevices = on,
        grinders = rowViewOf(grinders.toList()),
        connectedGrinder = ConnectedGrinderUi(status = status),
    )

    @Test
    fun `with the switch off nothing shows, whatever the grinders`() {
        val s = state(false, motto, status = GrinderLinkStatus.LIVE)
        assertTrue(s.hasBluetoothGrinder, "the switch itself shows")
        assertFalse(s.showGrinderDevice)
        assertNull(s.grinderRailDot())
    }

    @Test
    fun `with the switch on but no Bluetooth grinder nothing shows, nor the switch`() {
        for (s in listOf(state(true), state(true, niche))) {
            assertFalse(s.hasBluetoothGrinder, "a Niche Zero alone never sees the switch")
            assertFalse(s.showGrinderDevice)
            assertNull(s.grinderRailDot())
        }
        // An archived Motto doesn't count.
        val archived = state(true, motto.copy(archivedAt = 2L), niche)
        assertFalse(archived.showGrinderDevice)
    }

    @Test
    fun `with the switch on and a Bluetooth grinder the row and dot show`() {
        val s = state(true, niche, motto, status = GrinderLinkStatus.RECONNECTING)
        assertTrue(s.hasBluetoothGrinder)
        assertTrue(s.showGrinderDevice)
        assertEquals(GrinderDot.CONNECTING, s.grinderRailDot())
        assertEquals("Motto 80", s.grinderDeviceName())
    }

    @Test
    fun `a record linked to a connected grinder counts, whatever its preset`() {
        val mine = niche.copy(id = "grinder:mine", name = "My grinder", presetId = null)
        val s = state(true, mine).copy(grinderLinkedId = mine.id)
        assertTrue(s.showGrinderDevice)
        assertEquals("My grinder", s.grinderDeviceName())
    }

    @Test
    fun `a remote never shows the grinder, the hub holds it`() {
        val s = state(true, motto).copy(proxyRole = "secondary")
        assertFalse(s.hasBluetoothGrinder)
        assertFalse(s.showGrinderDevice)
    }

    @Test
    fun `connected, the status is the grind and never the battery`() {
        assertEquals("Connected · Grind 2.4", grinderDeviceStatus(GrinderLinkStatus.LIVE, "2.4", null))
        // The live dial when nothing is followed; the followed value wins.
        assertEquals("Connected · Grind 480", grinderDeviceStatus(GrinderLinkStatus.LIVE, null, "480"))
        assertEquals("Connected · Grind 2.4", grinderDeviceStatus(GrinderLinkStatus.LIVE, "2.4", "2.6"))
        assertEquals("Connected", grinderDeviceStatus(GrinderLinkStatus.WAITING, null, null))
        assertEquals("Reconnecting…", grinderDeviceStatus(GrinderLinkStatus.RECONNECTING, "2.4", "2.4"))
        assertEquals("Not connected", grinderDeviceStatus(GrinderLinkStatus.OFF, "2.4", null))
        for (status in GrinderLinkStatus.entries) {
            assertFalse("%" in grinderDeviceStatus(status, "2.4", "2.4"), "$status")
        }
    }

    @Test
    fun `the follow view's grind feeds the row`() {
        val follow = GrinderFollowView(status = FollowStatus.Following, grinder_id = motto.id, grind = "2.4")
        assertEquals("Connected · Grind 2.4", grinderDeviceStatus(GrinderLinkStatus.LIVE, follow.grind, null))
    }

    @Test
    fun `the dot is green, amber or grey`() {
        assertEquals(GrinderDot.CONNECTED, grinderDot(GrinderLinkStatus.LIVE))
        assertEquals(GrinderDot.CONNECTED, grinderDot(GrinderLinkStatus.WAITING))
        for (s in listOf(GrinderLinkStatus.SCANNING, GrinderLinkStatus.CONNECTING, GrinderLinkStatus.RECONNECTING)) {
            assertEquals(GrinderDot.CONNECTING, grinderDot(s), "$s")
        }
        for (s in listOf(GrinderLinkStatus.OFF, GrinderLinkStatus.NOT_FOUND, GrinderLinkStatus.NO_DATA, GrinderLinkStatus.MISMATCH)) {
            assertEquals(GrinderDot.OFF, grinderDot(s), "$s")
        }
    }

    @Test
    fun `the action is the card's`() {
        assertEquals(GrinderLinkAction.CONNECT, grinderLinkAction(GrinderLinkStatus.OFF, null))
        assertEquals(GrinderLinkAction.CONNECT, grinderLinkAction(GrinderLinkStatus.NOT_FOUND, null))
        assertEquals(GrinderLinkAction.RECONNECT, grinderLinkAction(GrinderLinkStatus.NO_DATA, "AA"))
        assertEquals(GrinderLinkAction.DISCONNECT, grinderLinkAction(GrinderLinkStatus.LIVE, "AA"))
        assertEquals(GrinderLinkAction.DISCONNECT, grinderLinkAction(GrinderLinkStatus.RECONNECTING, null))
        assertEquals(GrinderLinkAction.DISCONNECT, grinderLinkAction(GrinderLinkStatus.NO_ANSWER, "AA"))
        assertEquals(GrinderLinkAction.CONNECT, grinderLinkAction(GrinderLinkStatus.NO_ANSWER, null))
    }
}
