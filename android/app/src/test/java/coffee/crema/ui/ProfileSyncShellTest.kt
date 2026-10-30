package coffee.crema.ui

import coffee.crema.core.Command
import coffee.crema.core.CoreOutput
import coffee.crema.core.CremaBridge
import coffee.crema.core.Event
import coffee.crema.core.NotificationSource
import coffee.crema.core.ProfileSyncOutput
import coffee.crema.core.WriteTarget
import coffee.crema.core.cremaProfileFromWire
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F7 on Android, through the host core: the shell's connect path (the
 * [ConnectSweepRunner] the VM runs on every READY, and `de1LinkLost` on a drop)
 * makes the core's profile sync upload the desired profile on every connect,
 * and a remote's read-only core never uploads.
 */
class ProfileSyncShellTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val wire = """
        {"id":"","title":"Daily","notes":"","steps":[
          {"name":"a","pump":"pressure","target":9.0,"temperature_c":92.0,"temp_sensor":"coffee",
           "transition":"fast","duration_seconds":20.0,"exit":null,"volume_limit_ml":0,"limiter":null,"weight":null}],
         "preinfuse_step_count":1,"minimum_pressure":0.0,"maximum_flow":0.0,"max_total_volume_ml":0,
         "target_weight":36.0,"dose":18.0,"author":"","beverage_type":"espresso","tank_temperature":0.0,"version":"2"}
    """.trimIndent()

    private fun modelReply(model: Int): ByteArray = ByteArray(20).also {
        it[1] = 0x80.toByte(); it[2] = 0x00; it[3] = 0x0C
        it[4] = model.toByte()
    }

    private fun out(raw: String) = json.decodeFromString(CoreOutput.serializer(), raw)

    private fun started(o: CoreOutput) = o.events.count { it is Event.ProfileUploadStarted }

    /** Ack every profile write of [o], as the VM's write executor does. */
    private fun ackAll(bridge: CremaBridge, o: CoreOutput): List<Event> =
        o.commands.filterIsInstance<Command.WriteCharacteristic>()
            .filter { it.content.target == WriteTarget.De1ProfileHeader || it.content.target == WriteTarget.De1ProfileFrame }
            .flatMap { w ->
                val source = if (w.content.target == WriteTarget.De1ProfileHeader) {
                    NotificationSource.DE1_HEADER_ACK
                } else {
                    NotificationSource.DE1_FRAME_ACK
                }
                out(bridge.onNotification(source, w.content.data.map { it.toByte() }.toByteArray(), 0uL)).events
            }

    @Test
    fun `f7_1 every connect and reconnect uploads the desired profile`() = runTest {
        val bridge = CremaBridge()
        bridge.onNotification(NotificationSource.DE1_MMR_READ, modelReply(1), 0uL)
        bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(2, 0), 0uL)
        // The active profile is pushed before the DE1 connects: nothing yet.
        val early = json.decodeFromString(ProfileSyncOutput.serializer(), bridge.setDesiredProfile(cremaProfileFromWire(wire), null, 0uL))
        assertEquals(0, started(early.output))
        val sweeps = mutableListOf<CoreOutput>()
        val runner = ConnectSweepRunner(TestScope(testScheduler), sweep = { sweeps += out(bridge.connectSweep("{}", 0uL)) }, tick = {})

        runner.onReady()
        assertEquals(1, started(sweeps.last()), "the connect sweep is followed by the upload")
        assertTrue(ackAll(bridge, sweeps.last()).any { it is Event.ProfileSyncHolds })

        // The link drops (De1BleManager → de1LinkLost) and comes back.
        runner.onDropped()
        out(bridge.de1LinkLost())
        bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(2, 0), 0uL)
        runner.onReady()
        assertEquals(1, started(sweeps.last()), "a reconnect re-sends")
        assertTrue(ackAll(bridge, sweeps.last()).any { it is Event.ProfileSyncHolds })

        // A teardown (De1BleManager → reset) and a new connection: again.
        runner.onDropped()
        out(bridge.reset())
        bridge.onNotification(NotificationSource.DE1_MMR_READ, modelReply(1), 0uL)
        bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(2, 0), 0uL)
        runner.onReady()
        assertEquals(1, started(sweeps.last()), "a new connection re-sends")
        runner.onDropped()
    }

    @Test
    fun `f7_7 a remote's read-only core never uploads`() {
        val bridge = CremaBridge()
        bridge.setReadOnly(true) // a remote (the hub holds the BLE link)
        bridge.onNotification(NotificationSource.DE1_MMR_READ, modelReply(1), 0uL)
        bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(2, 0), 0uL)
        val outs = listOf(
            out(bridge.connectSweep("{}", 0uL)),
            json.decodeFromString(ProfileSyncOutput.serializer(), bridge.setDesiredProfile(cremaProfileFromWire(wire), null, 10uL)).output,
            json.decodeFromString(
                ProfileSyncOutput.serializer(),
                bridge.setDesiredProfile(cremaProfileFromWire(wire), """{"brewTemp":95}""", 20uL),
            ).output,
            out(bridge.onTick(60_000uL)),
        )
        for (o in outs) {
            assertEquals(0, started(o))
            assertTrue(o.commands.isEmpty(), "${o.commands}")
        }
        assertFalse(json.decodeFromString(coffee.crema.core.ProfileSyncStatus.serializer(), bridge.profileSyncStatus()).uploading)
    }
}
