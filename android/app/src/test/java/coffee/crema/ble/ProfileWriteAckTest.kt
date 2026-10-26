package coffee.crema.ble

import coffee.crema.core.NotificationSource
import coffee.crema.core.WriteTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cohort review 02 P0-2 / P2-2: which DE1 writes get a synthesized upload ack,
 * and which may be retried after a timeout.
 */
class ProfileWriteAckTest {

    @Test
    fun `a profile header write is confirmed as a header ack`() {
        assertEquals(NotificationSource.DE1_HEADER_ACK, profileWriteAckSource(WriteTarget.De1ProfileHeader))
    }

    @Test
    fun `a profile frame write is confirmed as a frame ack`() {
        assertEquals(NotificationSource.DE1_FRAME_ACK, profileWriteAckSource(WriteTarget.De1ProfileFrame))
    }

    @Test
    fun `other writes get no ack`() {
        assertNull(profileWriteAckSource(WriteTarget.De1RequestedState))
        assertNull(profileWriteAckSource(WriteTarget.De1MmrWrite))
    }

    @Test
    fun `a timed-out state request is not retried`() {
        // A late copy of Idle would stop the shot its Espresso just started.
        assertFalse(retriesAfterTimeout(WriteTarget.De1RequestedState))
    }

    @Test
    fun `idempotent writes keep the one retry`() {
        assertTrue(retriesAfterTimeout(WriteTarget.De1ProfileFrame))
        assertTrue(retriesAfterTimeout(WriteTarget.De1ProfileHeader))
        assertTrue(retriesAfterTimeout(WriteTarget.De1MmrWrite))
    }
}
