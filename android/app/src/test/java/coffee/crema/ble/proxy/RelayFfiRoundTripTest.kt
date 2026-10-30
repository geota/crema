package coffee.crema.ble.proxy

import coffee.crema.brew.CoreJson
import coffee.crema.core.RelayEvent
import coffee.crema.core.testseeds.RelayHubConnection
import coffee.crema.core.testseeds.RelayKeys
import coffee.crema.core.testseeds.RelayPairingGate
import coffee.crema.core.testseeds.RelayPinSet
import coffee.crema.core.testseeds.RelayRemoteConnection
import coffee.crema.core.testseeds.RelayStep
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-22: the relay core through the generated Kotlin bindings reproduces the
 * seeded vectors (core/de1-relay/tests/vectors/v4.json) byte for byte, for a
 * pairing and a resume, then carries a 300 000-byte message both ways.
 *
 * Uses the test-only bindings (`coffee.crema.core.testseeds`, generated from the
 * host library built with `test-seeds`; see generateUniffiTestBindings), the
 * only place the `*ForTests` constructors exist. Events decode with the app's
 * typeshare `RelayEvent`, as the shell will.
 */
class RelayFfiRoundTripTest {
    private val vectors: JsonObject =
        CoreJson.parseToJsonElement(File("../../core/de1-relay/tests/vectors/v4.json").readText()).jsonObject
    private val t0: ULong = vectors["t0_ms"]!!.jsonPrimitive.long.toULong()
    private val names = vectors["names"]!!.jsonObject

    /** `SHA-256("de1-relay test seed " ‖ label ‖ " " ‖ n)` (spec §3.7). */
    private fun seed(label: String, n: Int): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("de1-relay test seed $label $n".toByteArray())

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    private fun events(step: RelayStep): List<RelayEvent> =
        CoreJson.decodeFromString(ListSerializer(RelayEvent.serializer()), step.eventsJson)

    private fun single(step: RelayStep): ByteArray = step.send.single().bytes

    private fun keys(label: String): RelayKeys = RelayKeys.generateForTests(seed(label, 0))

    @Test
    fun keysMatchTheVectors() {
        for ((label, name) in listOf("remote-static" to "remote", "hub-static" to "hub")) {
            keys(label).use { k ->
                val v = vectors["keys"]!!.jsonObject[name]!!.jsonObject
                assertEquals(v.str("public"), hex(k.publicKey()))
                assertEquals(v.str("device_id"), k.deviceId())
                assertEquals(v.str("short_id"), k.shortId())
                assertEquals(v.str("private"), hex(k.privateForStorage()))
            }
        }
    }

    @Test
    fun pairingReproducesTheVectors() {
        val v = vectors["pairing"]!!.jsonObject
        val remoteKeys = keys("remote-static")
        val hubKeys = keys("hub-static")
        val gate = RelayPairingGate()
        gate.open(t0)
        val pins = RelayPinSet(emptyList())
        val hub = RelayHubConnection.newForTests(hubKeys, gate, pins, names.str("hub"), t0, seed("hub-conn", 0))
        val remote = RelayRemoteConnection.pairForTests(remoteKeys, names.str("remote"), t0, seed("remote-conn", 0))

        val wire = mutableListOf<ByteArray>()
        val hello = remote.start()
        assertTrue(hello.send.single().text)
        wire += single(hello)
        val serverHello = hub.onText(single(hello), t0)
        wire += single(serverHello)
        assertTrue(events(serverHello).single() is RelayEvent.PairRequest)
        val pakeA = remote.onBinary(single(serverHello), t0)
        wire += single(pakeA)
        val shown = events(pakeA).filterIsInstance<RelayEvent.ShowCode>().single()
        assertEquals(v.str("code"), shown.content.code)
        assertEquals(v.str("code"), remote.code())
        assertTrue(hub.onBinary(single(pakeA), t0).send.isEmpty())
        val pakeB = hub.enterCode(shown.content.code, t0)
        wire += single(pakeB)
        val noise1 = remote.onBinary(single(pakeB), t0)
        wire += single(noise1)
        val noise2 = hub.onBinary(single(noise1), t0)
        wire += single(noise2)
        val noise3 = remote.onBinary(single(noise2), t0)
        wire += single(noise3)
        val pending = hub.onBinary(single(noise3), t0)
        assertTrue(pending.send.isEmpty())
        assertTrue(events(pending).single() is RelayEvent.PairPending)
        // The shell's commit: pin (new key → true), then confirm.
        val key = hub.peerPublicKey()!!
        assertTrue(pins.insert(key))
        val done = hub.confirmPaired(t0)
        wire += single(done)
        val paired = remote.onBinary(single(done), t0)
        assertTrue(events(paired).single() is RelayEvent.Paired)
        assertEquals(1000.toUShort(), paired.closeCode)
        assertArrayEquals(hubKeys.publicKey(), remote.peerPublicKey())

        val order = listOf("client_hello", "server_hello", "pake_a", "pake_b", "noise_1", "noise_2", "noise_3", "pair_complete")
        assertEquals(order.size, wire.size)
        order.zip(wire).forEach { (name, bytes) -> assertEquals(name, v.str(name), hex(bytes)) }

        listOf(hub, remote, pins, gate, hubKeys, remoteKeys).forEach { it.close() }
    }

    @Test
    fun resumeReproducesTheVectorsThenCarries300kBothWays() {
        val v = vectors["resume"]!!.jsonObject
        val remoteKeys = keys("remote-static")
        val hubKeys = keys("hub-static")
        val pins = RelayPinSet(listOf(remoteKeys.publicKey()))
        val gate = RelayPairingGate()
        val hub = RelayHubConnection.newForTests(hubKeys, gate, pins, names.str("hub"), t0, seed("hub-conn", 1))
        val remote = RelayRemoteConnection.resumeForTests(remoteKeys, hubKeys.publicKey(), t0, seed("remote-conn", 1))

        val hello = remote.start()
        assertEquals(v.str("client_hello"), hex(single(hello)))
        val serverHello = hub.onText(single(hello), t0)
        assertEquals(v.str("server_hello"), hex(single(serverHello)))
        val ik1 = remote.onBinary(single(serverHello), t0)
        assertEquals(v.str("ik_1"), hex(single(ik1)))
        val ik2 = hub.onBinary(single(ik1), t0)
        assertEquals(v.str("ik_2"), hex(single(ik2)))
        assertNull("not admitted before the client-finished record", hub.channelBinding())
        val finished = remote.onBinary(single(ik2), t0)
        assertEquals(v.str("client_finished"), hex(single(finished)))
        val admitted = hub.onBinary(single(finished), t0)
        assertTrue(events(admitted).single() is RelayEvent.Established)
        assertEquals(v.str("channel_binding"), hex(hub.channelBinding()!!))
        assertArrayEquals(hub.channelBinding(), remote.channelBinding())

        val welcome = hub.send(v.str("welcome_frame").toByteArray(), t0)
        assertEquals(v.str("welcome_record"), hex(single(welcome)))
        assertEquals(v.str("welcome_frame"), String(remote.onBinary(single(welcome), t0).deliver.single()))
        val large = ByteArray(200_000) { (it % 251).toByte() }
        val records = hub.send(large, t0).send
        assertEquals(v["large_message"]!!.jsonObject["records"]!!.jsonArray.map { it.jsonPrimitive.content }, records.map { hex(it.bytes) })
        assertArrayEquals(large, records.flatMap { remote.onBinary(it.bytes, t0).deliver }.single())

        // 300 000 bytes each way through the bindings.
        val toRemote = ByteArray(300_000) { (it * 7).toByte() }
        val delivered = hub.send(toRemote, t0).send.flatMap { remote.onBinary(it.bytes, t0).deliver }
        assertArrayEquals(toRemote, delivered.single())
        val toHub = ByteArray(300_000) { (it * 13).toByte() }
        val back = remote.send(toHub, t0).send.flatMap { hub.onBinary(it.bytes, t0).deliver }
        assertArrayEquals(toHub, back.single())

        listOf(hub, remote, pins, gate, hubKeys, remoteKeys).forEach { it.close() }
    }

    @Test
    fun aWrongCodeIsTheHubsOnlyCountedFailure() {
        val v = vectors["wrong_code"]!!.jsonObject
        val gate = RelayPairingGate()
        gate.open(t0)
        val hub = RelayHubConnection.newForTests(keys("hub-static"), gate, RelayPinSet(emptyList()), names.str("hub"), t0, seed("hub-conn", 3))
        val remote = RelayRemoteConnection.pairForTests(keys("remote-static"), names.str("remote"), t0, seed("remote-conn", 3))
        val sh = hub.onText(single(remote.start()), t0)
        hub.onBinary(single(remote.onBinary(single(sh), t0)), t0)
        val n1 = remote.onBinary(single(hub.enterCode(v.str("entered_code"), t0)), t0)
        val n3 = remote.onBinary(single(hub.onBinary(single(n1), t0)), t0)
        val failed = hub.onBinary(single(n3), t0)
        assertEquals(v.str("hub_abort"), hex(single(failed)))
        val event = events(failed).filterIsInstance<RelayEvent.PairingFailed>().single()
        assertEquals("WrongCode", event.content.reason)
        assertTrue(gate.status(t0).contains("\"failures\":1"))
    }
}
