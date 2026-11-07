package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** One `Event.Telemetry`, as the core sends it. */
internal fun telemetrySample(pressure: Float, elapsedMs: Int = 1_000) =
    """{"type":"Telemetry","content":{"elapsed":$elapsedMs,"group_pressure":$pressure,"group_flow":2.0,""" +
        """"head_temp":92.0,"mix_temp":90.0,"steam_temp":150.0,"dispensed_volume":12.0,""" +
        """"set_head_temp":93.0,"set_group_pressure":9.0,"set_group_flow":0.0,"counted_ml":0.0}}"""

/**
 * The high-rate readouts live apart from [MainUiState] (cohort review
 * 2026-10-25, Android P2-4): a sample changes [MainViewModel.live] and leaves
 * [MainViewModel.ui], and so every screen that only collects that, alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LiveTelemetryTest {
    private val store = ViewModelStore()
    private val remote = RemoteFixture(store)
    private val collectors = CoroutineScope(Dispatchers.Unconfined)

    @After fun tearDown() { collectors.cancel(); store.clear(); VmHarness.idle(); remote.kill() }

    private fun build(): MainViewModel {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        return vm
    }

    private fun events(vm: MainViewModel, vararg events: String) {
        VmHarness.deliverCoreOutput(vm, """{"events":[${events.joinToString(",")}],"commands":[]}""")
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
    }

    private val pouring = """{"type":"MachineStateChanged","content":{"state":"Espresso","substate":"Pouring"}}"""
    private val pourPhase = """{"type":"ShotPhaseChanged","content":{"phase":"Pouring"}}"""
    private val reading = """{"type":"ScaleReading","content":{"weight":12.5,"flow":1.5}}"""

    @Test
    fun `ui does not emit on a telemetry sample`() {
        val vm = build()
        // The threshold and the state settle first: they are UI state.
        events(vm, pouring, """{"type":"WaterLevel","content":{"level":40.0,"refill_threshold":5.0}}""")
        // Startup's last async writes are not samples: count from a still `ui`.
        var last = vm.ui.value
        var stillSince = System.nanoTime()
        VmHarness.idleUntil("ui to settle") {
            val now = vm.ui.value
            if (now !== last) {
                last = now
                stillSince = System.nanoTime()
            }
            System.nanoTime() - stillSince > 300_000_000
        }
        val emitted = CopyOnWriteArrayList<MainUiState>()
        collectors.launch { vm.ui.collect { emitted += it } }
        val before = vm.ui.value

        repeat(3) { n ->
            events(
                vm,
                telemetrySample(pressure = 8f + n),
                reading,
                """{"type":"WaterLevel","content":{"level":${39.0 - n},"refill_threshold":5.0}}""",
            )
        }

        assertEquals(1, emitted.size, "only the collector's first value")
        assertSame(before, vm.ui.value)
        val live = vm.live.value
        assertEquals(10f, live.pressure)
        assertEquals(12.5f, live.scaleWeightG)
        assertEquals(37f, live.waterLevelMm)
    }

    @Test
    fun `a sample in the flow window joins the chart with the scale and the frame`() {
        val vm = build()
        events(vm, pouring, """{"type":"ShotStarted"}""", pourPhase, reading, """{"type":"ShotFrameChanged","content":{"frame":2}}""")
        events(vm, telemetrySample(pressure = 9f, elapsedMs = 5_000))
        val sample = vm.live.value.shotTelemetry.single()
        assertEquals(5_000L, sample.elapsedMs)
        assertEquals(12.5f, sample.weight)
        assertEquals(1.5f, sample.weightFlow)
        assertEquals(2, sample.frameNumber)
        assertEquals(5_000L, vm.live.value.shotElapsedMs)
    }

    @Test
    fun `a new shot never shows over the last shot's samples`() {
        val vm = build()
        // The frame moves, so the next start changes the state and emits it.
        events(vm, pouring, """{"type":"ShotStarted"}""", pourPhase, telemetrySample(pressure = 9f), """{"type":"ShotFrameChanged","content":{"frame":2}}""")
        assertEquals(1, vm.liveStore.value.shotTelemetry.size)

        // What an observer of the state finds in the store as the start lands.
        val seen = CopyOnWriteArrayList<Int>()
        collectors.launch {
            vm.ownState.collect { if (it.shotFrame == 0) seen += vm.liveStore.value.shotTelemetry.size }
        }
        events(vm, """{"type":"ShotStarted"}""")

        assertEquals(emptyList(), vm.live.value.shotTelemetry)
        assertEquals(0L, vm.live.value.shotElapsedMs)
        assertEquals(0f, vm.live.value.dispensedVolume)
        assertEquals(0, seen.first(), "the readouts reset first")
    }

    @Test
    fun `the debug line reads the latest sample`() {
        val live = LiveTelemetry(pressure = 8.84f, flow = 2.04f, headTemp = 92.46f, shotElapsedMs = 1_500)
        assertEquals("t=1500ms  P=8.8bar  flow=2.0mL/s  head=92.5°C", live.debugLine())
        assertNull(LiveTelemetry().debugLine(), "nothing before the first sample")
    }

    @Test
    fun `the event log lives in the store and keeps every line`() {
        val vm = build()
        events(vm, """{"type":"ShotStarted"}""")
        assertEquals("Shot started", VmHarness.log(vm).first().substringAfter("  "))
    }

    @Test
    fun `a remote's readouts come from its own core, past the mirror slice`() {
        val vm = remote.start()
        remote.push()
        events(vm, telemetrySample(pressure = 7f), reading)
        assertEquals(7f, vm.live.value.pressure)
        assertEquals(12.5f, vm.live.value.scaleWeightG)
    }

    @Test
    fun `a disconnect clears the session's readouts`() {
        val vm = build()
        events(vm, telemetrySample(pressure = 7f), reading)
        vm.disconnectScale()
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
        assertNull(vm.live.value.scaleWeightG)
        assertNull(vm.live.value.scaleFlowGPerS)
        assertEquals(7f, vm.live.value.pressure, "the scale's clear leaves the machine's readouts")

        events(vm, pouring, """{"type":"ShotStarted"}""", pourPhase, telemetrySample(pressure = 8f), """{"type":"WaterLevel","content":{"level":40.0,"refill_threshold":5.0}}""")
        assertEquals(1, vm.live.value.shotTelemetry.size)
        vm.disconnect()
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
        val live = vm.live.value
        assertNull(live.pressure)
        assertNull(live.waterLevelMm)
        assertEquals(emptyList(), live.shotTelemetry)
        assertEquals(false, vm.ui.value.shotInProgress)
        assertNull(live.debugLine(), "the debug line clears with them")
    }

    /** The core keeps a sample in an unknown substate, so the chart does too. */
    @Test
    fun `an unknown substate mid pour keeps growing the chart`() {
        val vm = build()
        val unknown = """{"type":"MachineStateChanged","content":{"state":"Espresso","substate":"Unknown"}}"""
        events(vm, pouring, """{"type":"ShotStarted"}""", pourPhase, unknown, telemetrySample(pressure = 8f))
        assertEquals(1, vm.live.value.shotTelemetry.size)
    }
}
