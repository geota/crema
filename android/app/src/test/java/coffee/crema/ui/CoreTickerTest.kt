package coffee.crema.ui

import coffee.crema.ble.De1BleManager
import coffee.crema.core.Command
import coffee.crema.core.CoreOutput
import coffee.crema.core.CremaBridge
import coffee.crema.core.NotificationSource
import coffee.crema.core.WriteTarget
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The steady core clock (cohort review 2026-10-28, #2 N1). Round 1's tests
 * ticked the core by hand and hid that no shell did; the last test here drives
 * the real host core only through [CoreTicker] on virtual time.
 */
class CoreTickerTest {

    @Test
    fun `ticks every second while the link is up or coming up, and stops on a drop`() = runTest {
        var ticks = 0
        val ticker = CoreTicker(backgroundScope) { ticks += 1 }
        ticker.onLinkState(De1BleManager.State.CONNECTING)
        advanceTimeBy(3_001)
        assertEquals(3, ticks)
        ticker.onLinkState(De1BleManager.State.READY) // still one clock
        advanceTimeBy(1_000)
        assertEquals(4, ticks)
        ticker.onLinkState(De1BleManager.State.DISCONNECTED)
        advanceTimeBy(5_000)
        assertEquals(4, ticks)
        assertFalse(ticker.running)
    }

    @Test
    fun `does not tick while idle, scanning or disconnected`() = runTest {
        var ticks = 0
        val ticker = CoreTicker(backgroundScope) { ticks += 1 }
        for (state in listOf(De1BleManager.State.IDLE, De1BleManager.State.SCANNING, De1BleManager.State.DISCONNECTED)) {
            ticker.onLinkState(state)
            advanceTimeBy(2_000)
        }
        assertEquals(0, ticks)
    }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun modelReply(model: Int): ByteArray = ByteArray(20).also {
        it[1] = 0x80.toByte(); it[2] = 0x00; it[3] = 0x0C
        it[4] = model.toByte()
    }

    private val profile = """
        {"id":"","title":"Stall","notes":"","steps":[
          {"name":"a","pump":"pressure","target":9.0,"temperature_c":92.0,"temp_sensor":"coffee",
           "transition":"fast","duration_seconds":20.0,"exit":null,"volume_limit_ml":0,"limiter":null,"weight":null}],
         "preinfuse_step_count":1,"minimum_pressure":0.0,"maximum_flow":6.0,"max_total_volume_ml":0,
         "target_weight":0.0,"dose":0.0,"author":"","beverage_type":"espresso","tank_temperature":0.0,"version":"2"}
    """.trimIndent()

    @Test
    fun `a stalled upload fails on the ack watchdog through the ticker alone`() = runTest {
        val bridge = CremaBridge() // the host build of the core
        bridge.onNotification(NotificationSource.DE1_MMR_READ, modelReply(1), 0uL)
        bridge.settleForUploads()
        val upload = json.decodeFromString(CoreOutput.serializer(), bridge.uploadWire(profile, 0uL))
        val writes = upload.commands.filterIsInstance<Command.WriteCharacteristic>()
        // Every write but the tail lands and is acked; the tail never returns.
        for (w in writes.dropLast(1)) {
            val source = if (w.content.target == WriteTarget.De1ProfileHeader) {
                NotificationSource.DE1_HEADER_ACK
            } else {
                NotificationSource.DE1_FRAME_ACK
            }
            bridge.onNotification(source, w.content.data.map { it.toByte() }.toByteArray(), 0uL)
        }
        val outputs = mutableListOf<String>()
        val ticker = CoreTicker(backgroundScope) { outputs += bridge.onTick(testScheduler.currentTime.toULong()) }

        ticker.onLinkState(De1BleManager.State.DISCONNECTED)
        advanceTimeBy(7_000)
        assertTrue(bridge.profileUploadInProgress(), "control: no clock, no watchdog")

        ticker.onLinkState(De1BleManager.State.READY)
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(outputs.any { "ProfileUploadFailed" in it && "AckTimeout" in it }, outputs.joinToString())
        assertFalse(bridge.profileUploadInProgress())
        ticker.stop()
    }
}
