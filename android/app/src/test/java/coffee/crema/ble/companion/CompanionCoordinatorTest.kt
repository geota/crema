package coffee.crema.ble.companion

import coffee.crema.ble.LurkPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Association bookkeeping, presence → action, and the lurk policy, over a fake CDM. */
class CompanionCoordinatorTest {

    private class FakeGateway(
        override val isSupported: Boolean = true,
        val system: MutableMap<String, Int?> = mutableMapOf(),
        val failing: Boolean = false,
    ) : CompanionGateway {
        val observing = mutableSetOf<String>()
        val disassociated = mutableListOf<Pair<String, Int?>>()
        override fun associations(): Map<String, Int?> {
            if (failing) error("CDM exploded")
            return system.toMap()
        }
        override fun startObserving(address: String, id: Int?): Boolean {
            if (failing) error("CDM exploded")
            if (address !in system) return false
            observing += address
            return true
        }
        override fun stopObserving(address: String, id: Int?) { observing -= address }
        override fun disassociate(address: String, id: Int?) {
            disassociated += address to id
            system -= address
        }
    }

    private val de1 = "D9:B2:48:00:00:01"
    private val scale = "aa:bb:cc:dd:ee:ff"
    private val presence = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    @Test
    fun `refresh observes presence only for associated remembered devices`() {
        val gw = FakeGateway(system = mutableMapOf(de1 to 7, "11:22:33:44:55:66" to 8))
        val c = CompanionCoordinator(gw, presence)
        c.refresh(listOf(de1, scale))
        assertTrue(c.isAssociated(de1))
        assertFalse(c.isAssociated(scale))
        assertEquals(setOf(de1), gw.observing)
        assertEquals(7, c.associationId(de1))
    }

    @Test
    fun `an association created by the dialog is observed and its id kept (none pre-API 33)`() {
        val gw = FakeGateway()
        val c = CompanionCoordinator(gw, presence)
        gw.system[normalizeAddress(scale)] = null
        assertTrue(c.onAssociated(scale, null))
        assertTrue(c.isAssociated(scale.uppercase()))
        assertNull(c.associationId(scale))
        assertEquals(setOf(normalizeAddress(scale)), gw.observing)
    }

    @Test
    fun `removing stops observing and disassociates`() {
        val gw = FakeGateway(system = mutableMapOf(de1 to 3))
        val c = CompanionCoordinator(gw, presence)
        c.refresh(listOf(de1))
        c.remove(de1)
        assertFalse(c.isAssociated(de1))
        assertTrue(gw.observing.isEmpty())
        assertEquals(listOf<Pair<String, Int?>>(de1 to 3), gw.disassociated)
    }

    @Test
    fun `an association removed in system settings is dropped on refresh`() {
        val gw = FakeGateway(system = mutableMapOf(de1 to 3))
        val c = CompanionCoordinator(gw, presence)
        c.refresh(listOf(de1))
        gw.system.clear()
        c.refresh(listOf(de1))
        assertFalse(c.isAssociated(de1))
    }

    @Test
    fun `associated vs not decides whether presence shapes the lurk`() {
        val gw = FakeGateway(system = mutableMapOf(de1 to 3))
        val c = CompanionCoordinator(gw, presence)
        c.refresh(listOf(de1, scale))
        presence.value = mapOf(de1 to false, normalizeAddress(scale) to false)
        assertEquals(LurkPolicy.IDLE_UNTIL_KICK, c.lurkPolicy(de1, pendingSupported = true))
        // The scale isn't associated: its (stray) presence is ignored.
        assertEquals(LurkPolicy.PENDING_CONNECT, c.lurkPolicy(scale, pendingSupported = true))
        presence.value = mapOf(de1 to true)
        assertEquals(LurkPolicy.PENDING_CONNECT, c.lurkPolicy(de1, pendingSupported = true))
    }

    @Test
    fun `an unsupported or misbehaving CDM degrades to not associated`() {
        val none = CompanionCoordinator(FakeGateway(isSupported = false, system = mutableMapOf(de1 to 1)), presence)
        none.refresh(listOf(de1))
        assertFalse(none.isAssociated(de1))
        presence.value = mapOf(de1 to false)
        assertEquals(LurkPolicy.PENDING_CONNECT, none.lurkPolicy(de1, pendingSupported = true))

        val broken = CompanionCoordinator(FakeGateway(failing = true), presence)
        assertEquals(emptyMap<String, Int?>(), broken.refresh(listOf(de1)))
        assertFalse(broken.onAssociated(de1, 1)) // startObserving threw — reported, not crashed
    }

    @Test
    fun `presence appeared kicks, disappeared idles, strangers and user-disconnected are ignored`() {
        assertEquals(CompanionDevice.DE1, presenceTarget(de1.lowercase(), de1, scale))
        assertEquals(CompanionDevice.SCALE, presenceTarget(scale.uppercase(), de1, scale))
        assertNull(presenceTarget("00:00:00:00:00:00", de1, scale))

        assertEquals(PresenceAction.KICK, presenceAction(CompanionDevice.DE1, present = true, userDisconnected = false))
        assertEquals(PresenceAction.IDLE, presenceAction(CompanionDevice.DE1, present = false, userDisconnected = false))
        assertEquals(PresenceAction.IGNORE, presenceAction(null, present = true, userDisconnected = false))
        assertEquals(PresenceAction.IGNORE, presenceAction(CompanionDevice.SCALE, present = true, userDisconnected = true))
    }

    @Test
    fun `the presence hub keeps the last report per device`() {
        CompanionPresence.report(de1.lowercase(), true)
        assertEquals(true, CompanionPresence.presence.value[normalizeAddress(de1)])
        CompanionPresence.report(de1, false)
        assertEquals(false, CompanionPresence.presence.value[normalizeAddress(de1)])
        CompanionPresence.forget(de1)
        assertNull(CompanionPresence.presence.value[normalizeAddress(de1)])
    }
}
