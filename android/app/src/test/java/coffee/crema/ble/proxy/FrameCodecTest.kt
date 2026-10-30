package coffee.crema.ble.proxy

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the proxy wire frames: that they serialise to the flat,
 * `"type"`-discriminated JSON the protocol promises (a superset of the BLE
 * capture line), and round-trip through [FrameCodec] losslessly.
 */
class FrameCodecTest {

    @Test
    fun `notify serialises flat and internally-tagged, not content-wrapped`() {
        val f = Frame.Notify(
            address = "AA:BB:CC:DD:EE:FF",
            service = "0000a000-0000-1000-8000-00805f9b34fb",
            char = "0000a00d-0000-1000-8000-00805f9b34fb",
            hex = "0712deadbeef",
            t = 123_456L,
        )
        val json = FrameCodec.encode(f)
        // Discriminated on "type", payload inline — NOT adjacently tagged with a
        // "content" object (the symmetry with BleSessionRecorder's flat line).
        assertTrue(json.contains("\"type\":\"notify\""), json)
        assertFalse(json.contains("content"), json)
        assertTrue(json.contains("\"hex\":\"0712deadbeef\""), json)
        // Round-trips back to the same concrete subtype.
        val back = FrameCodec.decode(json)
        assertIs<Frame.Notify>(back)
        assertEquals(f, back)
    }

    @Test
    fun `optional nulls stay off the wire`() {
        val f = Frame.Hello(v = PROXY_PROTOCOL_VERSION, role = "secondary", clientId = "dev-1", clientName = "Phone")
        val json = FrameCodec.encode(f)
        assertFalse(json.contains("token"), json) // encodeDefaults = false
        val notify = Frame.Notify(address = "a", service = "s", char = "c", hex = "00", t = 0L)
        assertFalse(FrameCodec.encode(notify).contains("src"), "src is null → omitted")
        assertEquals(f, FrameCodec.decode(json))
    }

    @Test
    fun `welcome round-trips with a nested roster`() {
        val f = Frame.Welcome(
            primaryName = "Kitchen tablet",
            authority = "primary",
            roster = listOf(
                DeviceInfo(address = "AA:11", name = "DE1", kind = "de1", state = "CONNECTED"),
                DeviceInfo(address = "BB:22", name = "BOOKOO_SC 502158", kind = "scale", state = "CONNECTED"),
            ),
            scope = SCOPE_CONTROL,
        )
        val back = FrameCodec.decode(FrameCodec.encode(f))
        assertIs<Frame.Welcome>(back)
        assertEquals(f, back)
        assertEquals(2, back.roster.size)
    }

    @Test
    fun `request reply frames preserve their correlation id`() {
        val frames: List<Frame> = listOf(
            Frame.Attach(id = 7, address = "AA:11"),
            Frame.Attached(id = 7, address = "AA:11", state = "CONNECTED"),
            Frame.Read(id = 8, address = "AA:11", service = "s", char = "c"),
            Frame.ReadOk(id = 8, hex = "abcd"),
            Frame.Write(id = 9, address = "AA:11", service = "s", char = "c", hex = "01"),
            Frame.WriteErr(id = 9, reason = "not-authoritative"),
        )
        for (f in frames) assertEquals(f, FrameCodec.decode(FrameCodec.encode(f)), "round-trip $f")
    }

    @Test
    fun `the relay decode skips an unknown frame type and refuses malformed JSON`() {
        assertNull(FrameCodec.decodeTolerant("""{"type":"fromTheFuture","x":1}"""))
        assertEquals(Frame.Event("hi"), FrameCodec.decodeTolerant("""{"type":"event","text":"hi"}"""))
        assertFailsWith<Exception> { FrameCodec.decodeTolerant("{not json") }
        assertFailsWith<Exception> { FrameCodec.decodeTolerant("""{"text":"no type"}""") }
        // A known type with bad fields still fails (it closes the session).
        assertFailsWith<Exception> { FrameCodec.decodeTolerant("""{"type":"event"}""") }
    }

    @Test
    fun `a Welcome without a scope fails, and an unknown scope is view-only`() {
        assertFailsWith<Exception> {
            FrameCodec.decodeTolerant("""{"type":"welcome","primaryName":"H","authority":"primary","roster":[]}""")
        }
        val odd = FrameCodec.decodeTolerant("""{"type":"welcome","primaryName":"H","authority":"primary","roster":[],"scope":"admin"}""")
        assertIs<Frame.Welcome>(odd)
        assertFalse(odd.grantsControl)
        assertTrue(Frame.Welcome("H", "primary", emptyList(), SCOPE_CONTROL).grantsControl)
    }

    @Test
    fun `hex round-trips including high bytes`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x7f, 0xff.toByte(), 0x80.toByte(), 0x12)
        assertEquals("00017fff8012", Hex.encode(bytes))
        assertContentEquals(bytes, Hex.decode("00017fff8012"))
        assertContentEquals(bytes, Hex.decode("00017FFF8012")) // case-insensitive decode
    }
}
