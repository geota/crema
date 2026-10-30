package coffee.crema.ble.proxy

import kotlin.test.Test
import kotlin.test.assertEquals

/** Names a peer chose (NSD, legacy hellos) follow the core's hello-name rule. */
class PeerNameTest {
    @Test
    fun `control, format and separator characters go, and the name is capped`() {
        assertEquals("Samsphone", sanitizePeerName("Sams\nphone"), "a newline")
        assertEquals("evilgnp.exe", sanitizePeerName("evil‮gnp.exe"), "a bidi override")
        assertEquals("ab", sanitizePeerName("a​b "), "zero-width space, line separator")
        assertEquals("Kitchen tablet", sanitizePeerName("  Kitchen tablet \u0007"))
        assertEquals(32, sanitizePeerName("x".repeat(100)).length)
        assertEquals(32, sanitizePeerName("☕".repeat(40)).codePointCount(0, sanitizePeerName("☕".repeat(40)).length), "counted in characters")
        assertEquals("Unnamed device", sanitizePeerName(" ‎\t "))
    }
}
