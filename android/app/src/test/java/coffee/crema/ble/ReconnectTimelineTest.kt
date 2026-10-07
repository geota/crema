package coffee.crema.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Episode bookkeeping, phase durations, and the compact log line. */
class ReconnectTimelineTest {

    private var now = 0L
    private val finished = mutableListOf<ReconnectTimeline>()
    private val rec = ReconnectTimelineRecorder(nowMs = { now }, wallMs = { 0L }, onFinished = { finished += it })

    @Test
    fun `records each phase with its duration and the time to READY`() {
        rec.begin("DE1", ReconnectTrigger.FOREGROUND)
        rec.attemptStarted("DE1")
        now = 100; rec.mark("DE1", ReconnectPhase.SCAN)
        now = 900; rec.mark("DE1", ReconnectPhase.GATT_CONNECT)
        now = 2_000; rec.mark("DE1", ReconnectPhase.DISCOVER)
        now = 2_300; rec.mark("DE1", ReconnectPhase.SUBSCRIBE)
        now = 2_500; rec.ready("DE1", expectPostConnect = true)
        assertTrue(finished.isEmpty(), "waits for the post-connect reads")
        now = 3_100; rec.postConnectDone("DE1")

        val t = rec.timelines.value.single()
        assertEquals(ReconnectOutcome.READY, t.outcome)
        assertEquals(2_500L, t.timeToReadyMs)
        assertEquals(
            listOf(
                ReconnectPhase.SCAN to 800L,
                ReconnectPhase.GATT_CONNECT to 1_100L,
                ReconnectPhase.DISCOVER to 300L,
                ReconnectPhase.SUBSCRIBE to 200L,
                ReconnectPhase.POST_CONNECT to 600L,
            ),
            t.phaseDurations(),
        )
        assertEquals(
            "reconnect DE1 · foreground → READY in 2.5s · 1 attempt · scan 0.8s · gatt 1.1s · " +
                "discover 0.3s · subscribe 0.2s · post-connect 0.6s",
            finished.single().compactLine(),
        )
    }

    @Test
    fun `backoff waits and failures accumulate across attempts`() {
        rec.begin("Scale", ReconnectTrigger.DROP)
        repeat(2) {
            rec.mark("Scale", ReconnectPhase.BACKOFF); now += 500
            rec.attemptStarted("Scale")
            rec.mark("Scale", ReconnectPhase.GATT_CONNECT); now += 1_000
            rec.attemptFailed("Scale", "GATT 133")
        }
        rec.end("Scale", ReconnectOutcome.ABANDONED)
        val t = finished.single()
        assertEquals(2, t.attempts)
        assertEquals(2, t.failures)
        assertNull(t.timeToReadyMs)
        assertEquals(listOf(ReconnectPhase.BACKOFF to 1_000L, ReconnectPhase.GATT_CONNECT to 2_000L), t.phaseDurations())
        assertEquals(
            "reconnect Scale · drop → abandoned after 3.0s · 2 attempts (2 failed) · backoff 1.0s · gatt 2.0s · last error: GATT 133",
            t.compactLine(),
        )
    }

    @Test
    fun `a new trigger supersedes the open episode`() {
        rec.begin("DE1", ReconnectTrigger.DROP)
        now = 40_000
        rec.begin("DE1", ReconnectTrigger.USER_RETRY)
        val (retry, drop) = rec.timelines.value
        assertEquals(ReconnectTrigger.USER_RETRY, retry.trigger)
        assertEquals(ReconnectOutcome.IN_PROGRESS, retry.outcome)
        assertEquals(ReconnectOutcome.SUPERSEDED, drop.outcome)
        assertEquals(40_000L, drop.endedAtMs)
        assertEquals(listOf(drop.id), finished.map { it.id })
    }

    @Test
    fun `devices are independent and marks without an open episode are ignored`() {
        rec.mark("DE1", ReconnectPhase.SCAN) // first connect: no trigger, no record
        rec.ready("DE1")
        assertTrue(rec.timelines.value.isEmpty())

        rec.begin("DE1", ReconnectTrigger.FOREGROUND)
        rec.begin("Scale", ReconnectTrigger.FOREGROUND)
        rec.ready("Scale")
        assertTrue(rec.isOpen("DE1"))
        assertEquals(listOf("Scale"), finished.map { it.device })
    }

    @Test
    fun `keeps only the newest episodes`() {
        repeat(ReconnectTimelineRecorder.DEFAULT_CAPACITY + 5) { i ->
            now = i.toLong()
            rec.begin("DE1", ReconnectTrigger.DROP)
            rec.ready("DE1")
        }
        val kept = rec.timelines.value
        assertEquals(ReconnectTimelineRecorder.DEFAULT_CAPACITY, kept.size)
        assertEquals((ReconnectTimelineRecorder.DEFAULT_CAPACITY + 4).toLong(), kept.first().startedAtMs)
    }

    @Test
    fun `a days-long lurk can't grow an episode without bound`() {
        rec.begin("DE1", ReconnectTrigger.DROP)
        repeat(1_000) { now += 60_000; rec.mark("DE1", ReconnectPhase.BACKOFF) }
        assertEquals(ReconnectTimelineRecorder.DEFAULT_MAX_MARKS, rec.timelines.value.single().marks.size)
    }

    @Test
    fun `a user disconnect closes the episode as cancelled`() {
        rec.begin("DE1", ReconnectTrigger.LAUNCH)
        now = 5_000
        rec.end("DE1", ReconnectOutcome.CANCELLED)
        assertEquals("reconnect DE1 · launch → cancelled after 5.0s · 0 attempts", finished.single().compactLine())
    }
}
