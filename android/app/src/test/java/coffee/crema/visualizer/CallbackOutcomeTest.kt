package coffee.crema.visualizer

import org.junit.Assert.assertEquals
import org.junit.Test

/** Review 05 note: a stray `crema://visualizer` intent must not abort a pending sign-in. */
class CallbackOutcomeTest {
    @Test
    fun aCallbackWithAnotherStateIsNotOurs() {
        assertEquals(CallbackOutcome.NotOurs, callbackOutcome("s1", "v", "code", "evil", null))
        assertEquals(CallbackOutcome.NotOurs, callbackOutcome("s1", "v", null, null, "access_denied"))
        assertEquals(CallbackOutcome.NotOurs, callbackOutcome("s1", "v", "code", null, null))
    }

    @Test
    fun theMatchingCallbackCompletesOrReportsTheHandshake() {
        assertEquals(CallbackOutcome.Exchange, callbackOutcome("s1", "v", "code", "s1", null))
        assertEquals(CallbackOutcome.Cancelled, callbackOutcome("s1", "v", null, "s1", "access_denied"))
        assertEquals(CallbackOutcome.NoCode, callbackOutcome("s1", "v", null, "s1", null))
    }

    @Test
    fun withNoPendingHandshakeTheCallbackHasExpired() {
        assertEquals(CallbackOutcome.Expired, callbackOutcome(null, null, "code", "s1", null))
        assertEquals(CallbackOutcome.Expired, callbackOutcome("s1", null, "code", "s1", null))
    }
}
