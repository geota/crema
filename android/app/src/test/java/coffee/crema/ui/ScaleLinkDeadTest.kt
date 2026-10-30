package coffee.crema.ui

import coffee.crema.core.CoreOutput
import coffee.crema.core.CremaBridge
import coffee.crema.core.Event
import coffee.crema.core.NotificationSource
import coffee.crema.core.ScaleLinkDeadReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The scale dead-link rule is the core's, shared with web (core/shell fixes
 * F18d): Android no longer runs its own ScaleStale grace timer or write-failure
 * counter. The host core emits `ScaleLinkDead` once per silent episode (1 s
 * stale + 5 s grace) and after three failed writes with no reading between —
 * the event the VM answers with `ScaleBleManager.presumeDead`.
 */
class ScaleLinkDeadTest {

    /** A Bookoo weight frame (the core's own test helper). */
    private fun bookooPacket(centigrams: Int): ByteArray {
        val p = ByteArray(20)
        val head = intArrayOf(0x03, 0x0B, 0, 0, 0, 0, '+'.code, centigrams shr 16 and 0xFF, centigrams shr 8 and 0xFF, centigrams and 0xFF)
        head.forEachIndexed { i, b -> p[i] = b.toByte() }
        p[19] = p.take(19).fold(0) { a, b -> a xor b.toInt() }.toByte()
        return p
    }

    private fun deadReasons(raw: String): List<ScaleLinkDeadReason> =
        coreJson.decodeFromString(CoreOutput.serializer(), raw).events
            .filterIsInstance<Event.ScaleLinkDead>()
            .map { it.content.reason }

    private fun connectedBridge(): CremaBridge {
        val bridge = CremaBridge()
        assertNotNull(bridge.connectScale("BOOKOO_SC", emptyList()))
        bridge.onNotification(NotificationSource.SCALE_WEIGHT, bookooPacket(2_000), 1_000uL)
        return bridge
    }

    @Test
    fun `a silent scale is presumed dead once after the grace`() {
        val bridge = connectedBridge()
        assertEquals(emptyList(), deadReasons(bridge.onTick(6_900uL)))
        assertEquals(listOf(ScaleLinkDeadReason.Silent), deadReasons(bridge.onTick(7_000uL)))
        // Still silent after the teardown and reconnect: no second verdict.
        for (t in 8_000uL..60_000uL step 1_000) assertEquals(emptyList(), deadReasons(bridge.onTick(t)))
    }

    @Test
    fun `three failed writes in a row presume the link dead`() {
        val bridge = connectedBridge()
        assertEquals(emptyList(), deadReasons(bridge.onScaleWriteResult(false)))
        assertEquals(emptyList(), deadReasons(bridge.onScaleWriteResult(false)))
        assertEquals(listOf(ScaleLinkDeadReason.WritesFailing), deadReasons(bridge.onScaleWriteResult(false)))
    }
}
