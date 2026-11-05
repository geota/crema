package coffee.crema.ui

import coffee.crema.core.CremaBridge
import coffee.crema.machine.MachineCommand
import coffee.crema.machine.QcField
import coffee.crema.settings.Setting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What a config command does on the device that owns the config, and the
 * setters' guards, through [MachineConfigController] alone: the callbacks are
 * recorded in order, and the host core stands behind the bridge. The lane only
 * counts what is queued on it, so nothing here waits on another thread.
 */
class MachineConfigControllerTest {
    private val calls = mutableListOf<String>()
    private val logs = mutableListOf<String>()
    private val state = MutableStateFlow(MainUiState())
    private var refusing: Setting? = null

    /** The state the stop targets were last pushed from. */
    private var pushed: MainUiState? = null

    /** Tasks queued on the core lane: never run, a write that was sent counts. */
    private var laneTasks = 0
    private val laneDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) { laneTasks += 1 }
    }
    private val scope = CoroutineScope(Job())

    private val controller = MachineConfigController(
        lane = CoreLane(
            scope = scope,
            json = coreJson,
            log = { logs += it },
            writeDe1 = { _, _, _, _ -> true },
            writeScale = {},
            shotRunning = { false },
            profileWriteFailed = { "" },
            dispatcher = laneDispatcher,
        ),
        bridge = CremaBridge(), // the host build of the core
        json = coreJson,
        uiState = { state.value },
        updateUi = { transform -> state.update(transform) },
        effectiveState = { state.value.effective() },
        appendLog = { logs += it },
        refusesOwnEdit = { setting -> setting == refusing },
        persistPrefs = { calls += "persistPrefs" },
        pushConfig = { calls += "pushConfig" },
        profileJson = { null },
        persistLibrary = { calls += "persistLibrary" },
        maybeLoadLinkedProfile = { calls += "linkedProfile($it)" },
        pushStopTargets = { s, delayMs -> pushed = s; calls += "pushStopTargets($delayMs)" },
        refreshStopTargetProjection = { calls += "refreshStopTargetProjection" },
        pushStartSettings = { calls += "pushStartSettings" },
        configureIdleSleep = { calls += "configureIdleSleep($it)" },
    )

    @AfterTest
    fun tearDown() = scope.coroutineContext[Job]!!.cancel()

    @Test
    fun `a new active profile persists, then its recipe becomes the stop targets`() {
        controller.commitConfig(MachineCommand.SetActiveProfile("p"))
        assertEquals(listOf("persistPrefs", "pushStopTargets(0)"), calls)
    }

    @Test
    fun `a new active bean persists the library, pushes it to mirrors, then loads its linked profile`() {
        controller.commitConfig(MachineCommand.SetActiveBean("b"))
        assertEquals(listOf("persistLibrary", "pushConfig", "linkedProfile(b)"), calls)
    }

    @Test
    fun `clearing the active bean loads no linked profile`() {
        controller.commitConfig(MachineCommand.SetActiveBean(null))
        assertEquals(listOf("persistLibrary", "pushConfig"), calls)
    }

    @Test
    fun `the dial debounces the desired profile before mirrors hear of it`() {
        controller.commitConfig(MachineCommand.AdjustBrew(18.0, 36.0, 92.0, null))
        assertEquals(listOf("pushStopTargets(500)", "pushConfig"), calls)
    }

    @Test
    fun `resetting the brew override drops the debounce`() {
        controller.commitConfig(MachineCommand.ResetBrew)
        assertEquals(listOf("pushStopTargets(0)", "pushConfig"), calls)
    }

    @Test
    fun `the core toggles persist`() {
        controller.commitConfig(MachineCommand.SetStopOnWeight(true))
        controller.commitConfig(MachineCommand.SetAutoTare(true))
        assertEquals(listOf("persistPrefs", "persistPrefs"), calls)
        assertEquals(emptyList(), logs)
    }

    @Test
    fun `grind is recorded and never reaches the machine`() {
        controller.commitConfig(MachineCommand.SetQc(QcField.GRIND, 12f))
        assertEquals(listOf("persistPrefs"), calls)
        assertEquals(0, laneTasks)
    }

    @Test
    fun `a quick control persists before its write goes on the lane`() {
        for (field in listOf(QcField.STEAM_FLOW, QcField.FLUSH_TIME, QcField.FLUSH_TEMP, QcField.STEAM_TEMP)) {
            calls.clear()
            laneTasks = 0
            controller.commitConfig(MachineCommand.SetQc(field, 2f))
            assertEquals("persistPrefs", calls.first(), "$field")
            assertEquals(1, laneTasks, "$field")
        }
    }

    @Test
    fun `core inputs follow the state they are given, not this device's own`() {
        val own = state.value
        val shown = own.copy(idleSleepMin = own.idleSleepMin + 7)
        controller.pushCoreInputs(shown)
        assertSame(shown, pushed)
        // The packet goes first; its new hot-water target reaches the mirrors on the way.
        assertEquals(listOf("pushConfig", "pushStopTargets(0)", "configureIdleSleep(${shown.idleSleepMin})"), calls)
        assertEquals(emptyList(), logs)
    }

    @Test
    fun `a remote refuses every gated setter, with no own value and no machine write`() {
        val gated: List<Pair<Setting, () -> Unit>> = listOf(
            Setting.HEATER_VOLTAGE to { controller.setHeaterVoltage(230) },
            Setting.LINE_FREQUENCY to { controller.setLineFrequency(50f) },
            Setting.MAX_SHOT_DURATION to { controller.setMaxShotDuration(30f) },
            Setting.WATER_REFILL_POINT to { controller.setWaterRefillPointMm(10f) },
            Setting.FAN_THRESHOLD to { controller.setFanThreshold(40f) },
            Setting.REQUIRE_SCALE to { controller.setRequireScale(true) },
            Setting.STEAM_TWO_TAP to { controller.setSteamTwoTap(true) },
            Setting.PRE_FLUSH to { controller.setPreFlush(true) },
            Setting.STEAM_PURGE to { controller.setSteamPurge(true) },
        )
        val before = state.value
        for ((setting, set) in gated) {
            refusing = setting
            set()
            assertSame(before, state.value, "$setting")
            assertEquals(emptyList(), calls, "$setting")
            assertEquals(0, laneTasks, "$setting")
        }
    }

    @Test
    fun `heater voltage takes only 120 or 230`() {
        controller.setHeaterVoltage(240)
        assertEquals(0, laneTasks)
        assertTrue(logs.single().startsWith("Heater voltage 240 ignored"))
        controller.setHeaterVoltage(230)
        assertEquals(2, laneTasks, "the write, then its read-back")
    }

    @Test
    fun `line frequency takes only auto, 50 or 60`() {
        val before = state.value
        controller.setLineFrequency(55f)
        assertSame(before, state.value)
        assertTrue(logs.single().startsWith("Line frequency 55.0 ignored"))
        assertEquals(emptyList(), calls)
        controller.setLineFrequency(50f)
        assertEquals(50f, state.value.lineFreqOverride)
        assertEquals(listOf("pushConfig"), calls)
    }
}
