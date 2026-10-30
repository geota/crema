package coffee.crema.ble.proxy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spec §4.8: a discovered peer below protocol 4 is listed but not dialable. */
class PeerVersionTest {
    @Test
    fun `an older hub isn't a mirror source`() {
        assertTrue(Peer("a", "Hub", "primary", true, "10.0.0.2", 8765, version = 4, kid = "0123abcd").isMirrorSource)
        assertFalse(Peer("b", "Old hub", "primary", true, "10.0.0.3", 8765, version = 3).isMirrorSource)
        assertFalse(Peer("c", "No version", "primary", true, "10.0.0.4", 8765, version = 0).isMirrorSource)
    }
}
