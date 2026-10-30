package coffee.crema.ui

import coffee.crema.core.Command
import coffee.crema.core.CoreOutput
import coffee.crema.core.CremaBridge
import coffee.crema.core.NotificationSource
import coffee.crema.core.WriteTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The profile-write generation rule (cohort review 2026-10-28, #2 N2 / #6 #3):
 * a header write opens a generation; a superseded or failed batch's writes are
 * neither sent nor acked; the live batch's first failure is reported once.
 */
class ProfileWriteGenerationsTest {

    private val upload = listOf(WriteTarget.De1ProfileHeader, WriteTarget.De1ProfileFrame, WriteTarget.De1ProfileFrame)

    @Test
    fun `an output without profile writes opens no batch`() {
        val g = ProfileWriteGenerations()
        assertNull(g.begin(listOf(WriteTarget.De1RequestedState, WriteTarget.De1MmrWrite)))
        assertEquals(0L, g.generation)
    }

    @Test
    fun `a new header supersedes the older batch`() {
        val g = ProfileWriteGenerations()
        val a = assertNotNull(g.begin(upload))
        assertEquals(ProfileWriteGenerations.Outcome.ACK, g.settle(a, true))
        val b = assertNotNull(g.begin(upload))
        assertFalse(g.mayWrite(a))
        assertEquals(ProfileWriteGenerations.Outcome.DROP, g.settle(a, true), "a late write of A acks nothing")
        assertEquals(ProfileWriteGenerations.Outcome.DROP, g.settle(a, false), "nor reports a failure")
        assertEquals(ProfileWriteGenerations.Outcome.ACK, g.settle(b, true))
    }

    @Test
    fun `the first failure stops the batch and is reported once`() {
        val g = ProfileWriteGenerations()
        val a = assertNotNull(g.begin(upload))
        assertEquals(ProfileWriteGenerations.Outcome.FAILED, g.settle(a, false))
        assertFalse(g.mayWrite(a))
        assertEquals(ProfileWriteGenerations.Outcome.DROP, g.settle(a, false))
        val b = assertNotNull(g.begin(upload))
        assertTrue(g.mayWrite(b), "the re-upload starts clean")
    }

    @Test
    fun `each batch carries its upload's number and frames without a header keep it`() {
        val g = ProfileWriteGenerations()
        val a = assertNotNull(g.begin(upload, 7u))
        assertEquals(7u, a.uploadId)
        assertEquals(7u, assertNotNull(g.begin(listOf(WriteTarget.De1ProfileFrame))).uploadId)
        val b = assertNotNull(g.begin(upload, 8u))
        assertEquals(8u, b.uploadId)
        assertEquals(7u, a.uploadId, "the older batch keeps its own number")
    }

    private val json = coreJson

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

    private fun CoreOutput.writeTargets() =
        commands.filterIsInstance<Command.WriteCharacteristic>().map { it.content.target }

    @Test
    fun `only a batch without an upload id is flagged, and real uploads always carry one`() {
        // Round-3 clean check C3 #3: the shell does not guess an id for such a
        // batch (its acks could reach the wrong upload); it logs
        // UNNAMED_PROFILE_BATCH_LOG and leaves the upload to the ack watchdog.
        val g = ProfileWriteGenerations()
        assertFalse(g.unnamed(null))
        assertTrue(g.unnamed(g.begin(upload)))
        // Through the host core, an immediate and a parked-then-released upload
        // both pair their header with `ProfileUploadStarted`.
        val bridge = CremaBridge()
        val parked = json.decodeFromString(CoreOutput.serializer(), bridge.uploadWire(profile("P"), 0uL))
        assertTrue(WriteTarget.De1ProfileHeader !in parked.writeTargets(), "waits for the model")
        val released = json.decodeFromString(
            CoreOutput.serializer(),
            bridge.onNotification(NotificationSource.DE1_MMR_READ, modelReply(1), 10uL),
        )
        assertFalse(g.unnamed(g.begin(released.writeTargets(), startedUploadId(released.events))))
        val now = json.decodeFromString(CoreOutput.serializer(), bridge.uploadWire(profile("N"), 20uL))
        assertFalse(g.unnamed(g.begin(now.writeTargets(), startedUploadId(now.events))))
    }

    @Test
    fun `a late failure report for a superseded upload leaves the shot start's upload running`() {
        // Round-2 verification V3 #1, through the host core: upload N's frame
        // write fails on the Main-thread batch coroutine; before it reports,
        // a shot start on the core lane begins upload N+1. The report names N
        // (its batch's upload id), so N+1 is not failed and the shot is not
        // refused. The old report carried no identity and failed N+1.
        val bridge = CremaBridge() // the host build of the core
        bridge.onNotification(NotificationSource.DE1_MMR_READ, modelReply(1), 0uL)
        val g = ProfileWriteGenerations()

        val n = json.decodeFromString(CoreOutput.serializer(), bridge.uploadWire(profile("N"), 0uL))
        val batchN = assertNotNull(g.begin(n.writeTargets(), startedUploadId(n.events)))
        assertNotNull(batchN.uploadId)
        // N's first frame fails: the batch's first failure.
        assertEquals(ProfileWriteGenerations.Outcome.FAILED, g.settle(batchN, false))

        // The shot start's upload, applied on the lane before the report lands.
        val n1 = json.decodeFromString(CoreOutput.serializer(), bridge.uploadWire(profile("N+1"), 10uL))
        val batchN1 = assertNotNull(g.begin(n1.writeTargets(), startedUploadId(n1.events)))

        val late = json.decodeFromString(
            CoreOutput.serializer(),
            bridge.profileWriteFailed(20uL, assertNotNull(batchN.uploadId)),
        )
        assertTrue(late.events.isEmpty(), "the report is about N, not N+1: ${late.events}")
        assertTrue(bridge.profileUploadInProgress(), "N+1 still uploading")

        // N+1's own failure still fails N+1.
        val own = bridge.profileWriteFailed(30uL, assertNotNull(batchN1.uploadId))
        assertTrue("WriteFailed" in own, own)
        assertFalse(bridge.profileUploadInProgress())
    }

    @Test
    fun `a superseded upload's late ack never reaches the shot start's upload`() {
        // Round-2 verification V1 P3-1, ack variant: upload N's write lands
        // after the core lane armed N+1 but before N+1's batch began, so the
        // generation still let N's ack through. The ack now names N.
        val bridge = CremaBridge()
        bridge.onNotification(NotificationSource.DE1_MMR_READ, modelReply(1), 0uL)
        val g = ProfileWriteGenerations()
        val n = json.decodeFromString(CoreOutput.serializer(), bridge.uploadWire(profile("N"), 0uL))
        val batchN = assertNotNull(g.begin(n.writeTargets(), startedUploadId(n.events)))
        val nHeader = n.commands.filterIsInstance<Command.WriteCharacteristic>().first().content.data
            .map { it.toByte() }.toByteArray()
        // N+1 armed on the lane; its batch has not begun, so N still "may write".
        bridge.uploadWire(profile("N+1"), 10uL)
        assertTrue(g.mayWrite(batchN))
        val ackId = batchN.uploadId.takeIf { g.mayWrite(batchN) } // the VM's ackUploadId
        val late = json.decodeFromString(
            CoreOutput.serializer(),
            bridge.onProfileWriteAck(NotificationSource.DE1_HEADER_ACK, nHeader, 20uL, assertNotNull(ackId)),
        )
        assertTrue(late.events.isEmpty(), late.events.toString())
        // N+1's header is still unconfirmed: its watchdog would say HeaderNotAcked,
        // not a frame mismatch caused by N.
        assertTrue(bridge.profileUploadInProgress())
    }
}
