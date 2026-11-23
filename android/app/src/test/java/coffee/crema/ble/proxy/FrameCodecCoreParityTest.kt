package coffee.crema.ble.proxy

import coffee.crema.core.wellFormed
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Relay slice 2: [FrameCodec] is the core's (`de1_relay_app::frame`). [Oracle]
 * is this shell's kotlinx codec as it was before the move, kept here as the
 * reference: on every fixture, a list of hostile inputs, a few thousand
 * seeded mutations of the fixtures and a few thousand random frames, the
 * core encodes the same bytes and its relay decode ([FrameCodec.decodeTolerant],
 * the one the sessions use) delivers, skips or refuses exactly what kotlinx did.
 *
 * Differences, by design: an escaped lone surrogate decodes as U+FFFD (a
 * Rust string can't hold the lone UTF-16 unit kotlinx kept), and the test-only
 * strict [FrameCodec.decode] reads as the relay decode does, failing only on
 * an unknown type.
 */
class FrameCodecCoreParityTest {

    private val random = Random(2)

    private sealed interface Outcome {
        data class Delivered(val frame: String) : Outcome
        data object Skipped : Outcome
        data object Refused : Outcome
    }

    private fun outcome(decode: () -> Frame?): Outcome = try {
        decode()?.let { Outcome.Delivered(it.toString().wellFormed()) } ?: Outcome.Skipped
    } catch (e: Exception) {
        Outcome.Refused
    }

    private fun assertSame(text: String) {
        assertEquals(outcome { Oracle.decodeTolerant(text) }, outcome { FrameCodec.decodeTolerant(text) }, text.shown())
    }

    private val fixtures: List<String> =
        (Golden.list("frames").map { "frames/$it.json" } + Golden.list("unknown").map { "unknown/$it.json" } +
            Golden.list("control").map { "control/$it.json" }).map { Golden.require(it) }

    @Test
    fun `every fixture decodes as it did, and re-encodes to the same bytes`() {
        assertEquals(53, fixtures.size)
        for (text in fixtures) {
            assertSame(text)
            val frame = Oracle.decodeTolerant(text) ?: continue
            assertEquals(Oracle.encode(frame), FrameCodec.encode(frame))
            assertEquals(Oracle.decode(text), FrameCodec.decode(text))
        }
    }

    @Test
    fun `hostile input is delivered, skipped or refused as it was`() {
        val cases = listOf(
            """{"type":"attach","id":"7","address":"a"}""", """{"type":"attach","id":1e3,"address":"a"}""",
            """{"type":"attach","id":1.5e3,"address":"a"}""", """{"type":"attach","id":7.0,"address":"a"}""",
            """{"type":"attach","id":-0,"address":"a"}""", """{"type":"attach","id":07,"address":"a"}""",
            """{"type":"attach","id":+7,"address":"a"}""", """{"type":"attach","id":9223372036854775808,"address":"a"}""",
            """{"type":"attach","id":92233720368547758080,"address":"a"}""", """{"type":"attach","id":-9223372036854775808,"address":"a"}""",
            """{"type":"attach","id":"1e3","address":"a"}""", """{"type":"attach","id":" 7 ","address":"a"}""",
            """{"type":"attach","id":"\"7\"","address":"a"}""", """{"type":"attach","id":"\"7","address":"a"}""",
            """{"type":"attach","id":"-e5","address":"a"}""", """{"type":"attach","id":1e--5,"address":"a"}""",
            """{"type":"attach","id":10e-1,"address":"a"}""", """{"type":"attach","id":1e19,"address":"a"}""",
            """{"type":"attach","id":0e999,"address":"a"}""", """{"type":"attach","id":1e99999999999999999999,"address":"a"}""",
            """{"type":"attach","id":true,"address":"a"}""", """{"type":"attach","id":null,"address":"a"}""",
            """{"type":"attach","id":[7],"address":"a"}""", """{"type":"attach","id":7,"address":"a","address":"b"}""",
            """{"type":"attach","type":"detach","id":7,"address":"a"}""", """{"id":7,"address":"a","type":"attach"}""",
            "{\"type\":\"attach\",\"id\":7,\"address\":\"a\nb\u0001\"}", """{"type":"attach","id":7,"address":"a\ud800b"}""",
            """{"type":"attach","id":7,"address":"a\udc00b"}""", """{"type":"attach","id":7,"address":"a😀b"}""",
            """{"type":"attach","id":7,"address":"a\ud83d"}""", """{"type":"attach","id":7,"address":"\ud800😀"}""",
            """{"type":"attach","id":7,"address":"a\x"}""", """{"type":"attach","id":7,"address":"a\/b"}""",
            """{"type":"attach","id":7,"address":"a\'b"}""", """{"type":"attach","id":7,"address":"\u004"}""",
            """{"type":"attach","id":7,"address":5}""", """{"type":"attach","id":7,"address":a}""",
            """{"type":"attach","id":7,"address":"a",}""", """{"type":"attach","id":7,"address":"a"} """,
            """ {"type":"attach","id":7,"address":"a"}x""", """{"type":"attach","id":7,"address":"a"}{}""",
            """{'type':'attach','id':7,'address':'a'}""", """{"type":"control","id":1,"method":"m","args":null}""",
            """{"type":"controlOk","id":1,"result":null}""", """{"type":"controlOk","id":1,"result":5}""",
            """{"type":"notify","address":"a","service":"s","char":"c","hex":"00","t":1,"src":null}""",
            """{"type":"event","text":"x","extra":{"a":[1,2,{"b":null}]}}""", """{"type":"event","text":"x","extra":tru}""",
            """{"type":"event","text":"x","extra":NaN}""", """{"type":"event","text":"x","extra":"\ud800"}""",
            """{"type":"event","text":"x","extra":--}""", """{"type":"event","text":"x","extra":nulls}""",
            """{"type":"roster","devices":[{"address":"a","name":"n","kind":"k","state":"s","x":1}]}""",
            """{"type":"roster","devices":null}""", """{"type":"roster","devices":{}}""", """{"type":"roster","devices":[null]}""",
            """{"type":"welcome","primaryName":"p","authority":"a","roster":[],"scope":null}""",
            """{"type":5,"id":7}""", """{"type":null}""", """{"type":"Attach","id":7,"address":"a"}""", """{"type":"pairingQr","code":tru}""",
            "[]", "\"x\"", "{}", "", " ", "null", "7", """{"type":"pairingQr"""", """{"type" : "attach" , "id" : 7 , "address" : "a"}""",
            "{\"type\":\"attach\",\"id\":7,\"address\":\"a\"\u000b}", "{\"type\":\"attach\",\"id\":7,\"address\":\"a\" }",
            "{\"type\":\"attach\",\"id\":7,\"address\":\"a\"}\u0000", """{,"type":"writeOk","id":7}""", """{"type":"writeOk","id":7,,}""",
            """{"type":"writeOk","id":7 "x":1}""", """{"type":"writeOk"  "id":7}""", """{"type":"writeOk","id":7}}""",
            """{"type":"writeOk","id":7,"x":[1,,2]}""", """{"type":"writeOk","id":7,"x":[,1]}""", """{"type":"writeOk","id":7,"x":[1,]}""",
            """{"type":"writeOk","id":7,"x":{"a"}}""", """{"type":"writeOk","id":7,"x":{"a":}}""", """{"type":"writeOk","id":7,"x":{1:2}}""",
        )
        for (c in cases) assertSame(c)
        for (n in listOf(398, 399, 400, 401, 3000)) {
            assertSame("""{"type":"event","text":"x","extra":${"[".repeat(n)}${"]".repeat(n)}}""")
            assertSame("""{"type":"event","text":"x","extra":${"{\"a\":".repeat(n)}1${"}".repeat(n)}}""")
        }
    }

    /** A hostile peer's object with as many distinct keys as fit in one
     *  relay message (up to 1 MiB once reassembled), and a duplicate that
     *  wins: decoded as kotlinx did, in linear time. */
    @Test
    fun `an object with a megabyte of keys decodes as it did, quickly`() {
        val text = buildString {
            append("""{"type":"event","text":"first"""")
            for (i in 0 until 60_000) append(",\"k$i\":$i")
            append(""","text":"last"}""")
        }
        assertTrue(text.length < 1 shl 20, "${text.length}")
        val start = System.nanoTime()
        val frame = FrameCodec.decodeTolerant(text)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs < 2_000, "took $elapsedMs ms")
        assertEquals(Frame.Event("last"), frame)
        assertSame(text)
    }

    /** Bits of JSON a mutation splices in. */
    private val bits = listOf(
        "{", "}", "[", "]", "\"", "\\", ",", ":", "0", "1", "7", "e", "E", "-", "+", ".", "null", "true", "tru", " ", "\t",
        "\n", "\u0001", "\u000b", "é", "😀", "\\u", "\\ud800", "\\udc00", "\\ud83d\\ude00", "\\n", "\\/", "\"type\"",
        "\"id\"", "\"args\"", "\"result\"", "\"src\"", "\"attach\"", "\"controlOk\"", "\"1e3\"", "x",
    )

    @Test
    fun `mutated fixtures are delivered, skipped or refused as they were`() {
        repeat(20_000) {
            var text = fixtures[random.nextInt(fixtures.size)]
            repeat(random.nextInt(1, 4)) {
                val at = random.nextInt(text.length + 1)
                text = when (random.nextInt(3)) {
                    0 -> text.substring(0, at) + bits.random(random) + text.substring(at)
                    1 -> if (at < text.length) text.removeRange(at, minOf(text.length, at + random.nextInt(1, 4))) else text
                    else -> if (at < text.length) text.substring(0, at) + bits.random(random) + text.substring(at + 1) else text
                }
            }
            assertSame(text)
        }
    }

    private val palette = listOf(
        "a", "Z", "0", " ", "\"", "\\", "/", "\n", "\t", "\b", "\u000c", "\r", "\u0000", "\u001f", "\u007f", "é", "😀",
        " ", "{", ":", "\uD800", "\uDC00",
    )

    private fun text(): String = buildString { repeat(random.nextInt(12)) { append(palette.random(random)) } }

    private fun id(): Long = if (random.nextBoolean()) random.nextLong() else random.nextLong(-3, 1000)

    private fun devices() = List(random.nextInt(3)) { DeviceInfo(text(), text(), text(), text()) }

    private fun frame(): Frame = when (random.nextInt(19)) {
        0 -> Frame.Welcome(text(), text(), devices(), text())
        1 -> Frame.Roster(devices())
        2 -> Frame.Attach(id(), text())
        3 -> Frame.Attached(id(), text(), text())
        4 -> Frame.Detach(id(), text())
        5 -> Frame.Detached(id(), text())
        6 -> Frame.Read(id(), text(), text(), text())
        7 -> Frame.ReadOk(id(), text())
        8 -> Frame.ReadErr(id(), text())
        9 -> Frame.Write(id(), text(), text(), text(), text())
        10 -> Frame.WriteOk(id())
        11 -> Frame.WriteErr(id(), text())
        12 -> Frame.Control(id(), text(), if (random.nextBoolean()) "" else text())
        13 -> Frame.ControlOk(id(), if (random.nextBoolean()) null else text())
        14 -> Frame.ControlErr(id(), text())
        15 -> Frame.Notify(text(), text(), text(), text(), id(), if (random.nextBoolean()) null else text())
        16 -> Frame.State(text(), text())
        17 -> Frame.Config(text())
        else -> Frame.Event(text())
    }

    @Test
    fun `random frames encode to the same bytes and decode back the same`() {
        repeat(5_000) {
            val f = frame()
            val bytes = FrameCodec.encode(f).encodeToByteArray()
            assertEquals(Oracle.encode(f).encodeToByteArray().toList(), bytes.toList(), "$f")
            assertSame(bytes.decodeToString())
        }
    }

    @Test
    fun `the core knows the types the sealed class names, and fails as kotlinx did`() {
        assertEquals(Oracle.knownTypes, FrameCodec.TYPES)
        assertIs<IllegalArgumentException>(runCatching { FrameCodec.decodeTolerant("[]") }.exceptionOrNull())
        assertFailsWith<SerializationException> { FrameCodec.decodeTolerant("{}") }
        assertFailsWith<SerializationException> { FrameCodec.decodeTolerant("""{"type":"attach"}""") }
        assertEquals(Frame.Event("a�b"), FrameCodec.decodeTolerant("""{"type":"event","text":"a\ud800b"}"""))
    }

    private fun String.shown(): String = take(120).map { c ->
        if (c.code < 0x20 || c.isSurrogate()) "\\u%04x".format(c.code) else c.toString()
    }.joinToString("") + if (length > 120) "… ($length)" else ""

    /** This shell's kotlinx frame codec before the move, verbatim. */
    private object Oracle {
        val json: Json = Json {
            classDiscriminator = "type"
            encodeDefaults = false
            ignoreUnknownKeys = true
        }

        fun encode(frame: Frame): String = json.encodeToString(Frame.serializer(), frame)

        fun decode(text: String): Frame = json.decodeFromString(Frame.serializer(), text)

        fun decodeTolerant(text: String): Frame? {
            val obj = json.parseToJsonElement(text).jsonObject
            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw SerializationException("frame without a type")
            if (type !in knownTypes) return null
            return json.decodeFromJsonElement(Frame.serializer(), obj)
        }

        val knownTypes: Set<String> by lazy {
            val d = Frame.serializer().descriptor
            (0 until d.getElementDescriptor(1).elementsCount).map { d.getElementDescriptor(1).getElementName(it) }.toSet()
        }
    }
}
