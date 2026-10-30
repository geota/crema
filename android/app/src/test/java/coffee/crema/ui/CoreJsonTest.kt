package coffee.crema.ui

import coffee.crema.core.Command
import coffee.crema.core.CoreOutput
import coffee.crema.core.CremaBridge
import coffee.crema.core.Event
import coffee.crema.core.NotificationSource
import coffee.crema.core.ProfileUploadFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The VM's `CoreOutput` decode ([coreJson]). The core tags `ProfileUploadFailure`
 * and `CalibrationRefusal` with `kind`, the generated Kotlin expects `type`: every
 * output carrying an upload failure used to fail to decode and was dropped whole,
 * including the superseding upload's writes it carried.
 */
class CoreJsonTest {

    private fun modelReply(model: Int): ByteArray = ByteArray(20).also {
        it[1] = 0x80.toByte(); it[2] = 0x00; it[3] = 0x0C
        it[4] = model.toByte()
    }

    private fun profile(title: String) = """
        {"id":"","title":"$title","notes":"","steps":[
          {"name":"a","pump":"pressure","target":9.0,"temperature_c":92.0,"temp_sensor":"coffee",
           "transition":"fast","duration_seconds":20.0,"exit":null,"volume_limit_ml":0,"limiter":null,"weight":null}],
         "preinfuse_step_count":1,"minimum_pressure":0.0,"maximum_flow":6.0,"max_total_volume_ml":0,
         "target_weight":0.0,"dose":0.0,"author":"","beverage_type":"espresso","tank_temperature":0.0,"version":"2"}
    """.trimIndent()

    @Test
    fun `an aborted upload and the retry that follows decode with every write`() {
        val bridge = CremaBridge() // the host build of the core
        bridge.onNotification(NotificationSource.DE1_MMR_READ, modelReply(1), 0uL)
        bridge.settleForUploads()
        bridge.uploadWire(profile("A"), 0uL)
        val aborted = coreJson.decodeFromString(CoreOutput.serializer(), bridge.cancelProfileUpload())
        val failed = aborted.events.filterIsInstance<Event.ProfileUploadFailed>().single()
        assertIs<ProfileUploadFailure.Aborted>(failed.content.reason)
        assertEquals("Aborted", uploadFailureLabel(failed.content.reason))
        assertTrue(aborted.events.any { it is Event.ProfileSyncWarning })
        // The core's retry, 3 s later.
        val out = coreJson.decodeFromString(CoreOutput.serializer(), bridge.onTick(3_000uL))
        assertTrue(out.events.any { it is Event.ProfileUploadStarted })
        assertTrue(out.commands.filterIsInstance<Command.WriteCharacteristic>().size >= 3, "the header, frame and tail")
    }

    @Test
    fun `failure reasons with details decode`() {
        val raw = """
            {"events":[
              {"type":"ProfileUploadFailed","content":{"reason":{"kind":"AckTimeout","details":{"awaiting":2}}}},
              {"type":"ProfileUploadFailed","content":{"reason":{"kind":"Internal","details":{"message":"boom"}}}},
              {"type":"ProfileUploadFailed","content":{"reason":{"kind":"MachineModelUnknown"}}}
            ],"commands":[]}
        """.trimIndent()
        val reasons = coreJson.decodeFromString(CoreOutput.serializer(), raw).events
            .filterIsInstance<Event.ProfileUploadFailed>().map { it.content.reason }
        assertEquals(2u.toUByte(), assertIs<ProfileUploadFailure.AckTimeout>(reasons[0]).details.awaiting)
        assertEquals("Internal: boom", uploadFailureLabel(reasons[1]))
        assertIs<ProfileUploadFailure.MachineModelUnknown>(reasons[2])
    }

    @Test
    fun `a calibration refusal decodes`() {
        val bridge = CremaBridge()
        bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(4, 5), 0uL) // Espresso pouring
        val out = coreJson.decodeFromString(
            CoreOutput.serializer(),
            bridge.writeCalibration(coffee.crema.core.CalSensor.FLOW, 1.0f, 1.0f),
        )
        assertTrue(out.events.any { it is Event.CalibrationRefused }, out.events.toString())
    }
}
