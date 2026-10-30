package coffee.crema.ble.proxy

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Golden wire fixtures for every [Frame] type (`proxy-golden/v3/frames/`).
 * Each sample below must encode to its fixture byte for byte, and the fixture
 * must decode back to the sample, so any change to a frame's wire form
 * (a renamed field, a new default that now stays off the wire, a changed
 * discriminator) shows up here as a fixture diff. A frame type added to the
 * codec without a fixture fails [every frame type has a golden fixture].
 *
 * Fixture names are the frame's `"type"`, plus `.variant` for a second shape
 * of the same frame (an optional field set, a non-default scope).
 */
class FrameGoldenTest {

    private val addr = "AA:BB:CC:DD:EE:FF"
    private val service = "0000a000-0000-1000-8000-00805f9b34fb"
    private val stateChar = "0000a00e-0000-1000-8000-00805f9b34fb"

    private val roster = listOf(
        DeviceInfo(addr, "DE1", "de1", "CONNECTED"),
        DeviceInfo("11:22:33:44:55:66", "BOOKOO_SC 502158", "scale", "CONNECTED"),
    )

    /** One canonical instance per fixture. */
    private val samples: Map<String, Frame> = linkedMapOf(
        "welcome" to Frame.Welcome("Kitchen tablet", "primary", roster, scope = SCOPE_CONTROL),
        "welcome.mirror" to Frame.Welcome("Kitchen tablet", "primary", emptyList(), scope = SCOPE_MIRROR),
        "roster" to Frame.Roster(roster),
        "attach" to Frame.Attach(7, addr),
        "attached" to Frame.Attached(7, addr, "CONNECTED"),
        "detach" to Frame.Detach(8, addr),
        "detached" to Frame.Detached(8, addr),
        "read" to Frame.Read(9, addr, service, stateChar),
        "readOk" to Frame.ReadOk(9, "0900"),
        "readErr" to Frame.ReadErr(9, "read failed"),
        "write" to Frame.Write(10, addr, service, "0000a002-0000-1000-8000-00805f9b34fb", "02"),
        "writeOk" to Frame.WriteOk(10),
        "writeErr" to Frame.WriteErr(10, "not-authoritative"),
        "control" to Frame.Control(11, "machineState", "IDLE"),
        "control.noargs" to Frame.Control(12, "tareScale"),
        "controlOk" to Frame.ControlOk(11),
        "controlErr" to Frame.ControlErr(12, "not authorized"),
        "notify" to Frame.Notify(addr, service, "0000a00d-0000-1000-8000-00805f9b34fb", "0712deadbeef", 123_456L),
        "notify.src" to Frame.Notify(addr, service, stateChar, "0200", 123_457L, src = "DE1_STATE"),
        "state" to Frame.State(addr, "DISCONNECTED"),
        "config" to Frame.Config("""{"activeProfileId":"p-1","stopOnWeight":true}"""),
        "event" to Frame.Event("Sam's phone started a shot"),
    )

    /** Every `"type"` the codec knows, read from the sealed serializer. */
    private val codecTypes: Set<String> =
        Frame.serializer().descriptor.getElementDescriptor(1).elementNames.toSet()

    @Test
    fun `the fixtures describe the current protocol version`() {
        assertEquals(
            Golden.VERSION,
            PROXY_PROTOCOL_VERSION,
            "PROXY_PROTOCOL_VERSION changed: keep proxy-golden/v${Golden.VERSION} as the old-peer fixtures and add the new version's",
        )
    }

    @Test
    fun `every frame type has a golden fixture`() {
        val covered = samples.keys.map { it.substringBefore('.') }.toSet()
        assertEquals(codecTypes, covered, "frame types without a golden sample (or samples for no type)")
    }

    @Test
    fun `every sample encodes to its fixture`() {
        for ((name, frame) in samples) Golden.check("frames/$name.json", FrameCodec.encode(frame))
    }

    @Test
    fun `every fixture decodes to its sample and re-encodes unchanged`() {
        for ((name, frame) in samples) {
            val text = Golden.require("frames/$name.json")
            val decoded = FrameCodec.decode(text)
            assertEquals(frame, decoded, "frames/$name.json decodes to a different frame")
            assertEquals(text, FrameCodec.encode(decoded), "frames/$name.json does not re-encode to itself")
        }
    }

    @Test
    fun `no fixture is left without a sample`() {
        assertEquals(emptySet(), Golden.list("frames") - samples.keys, "fixtures no sample checks")
    }

    /** The strict codec still refuses an unknown `"type"`; the relay v4 decode
     *  ([FrameCodec.decodeTolerant], spec §4.5) skips it, so a later v4.x can
     *  add frames without a version bump. */
    @Test
    fun `an unknown frame type fails the strict decode and is skipped by the relay decode`() {
        val text = Golden.require("unknown/type.json")
        val e = assertFailsWith<SerializationException> { FrameCodec.decode(text) }
        assertTrue("pairingQr" in e.message.orEmpty(), "the error names the unknown type: ${e.message}")
        assertEquals(null, FrameCodec.decodeTolerant(text))
    }

    /** An unknown field on a known frame is ignored (`ignoreUnknownKeys`): the
     *  additive change the protocol already tolerates. */
    @Test
    fun `an unknown field on a known frame is ignored`() {
        val text = Golden.require("unknown/field.json")
        assertEquals(Frame.Event("Sam's phone started a shot"), FrameCodec.decode(text))
    }
}
