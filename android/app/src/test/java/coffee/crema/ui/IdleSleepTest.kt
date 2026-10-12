package coffee.crema.ui

import coffee.crema.core.IdleActivity
import coffee.crema.core.IdleSleepInput
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.resolveIdleSleep
import coffee.crema.settings.toCommonSettings
import coffee.crema.settings.withCommonSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The idle-sleep timer, shell side (web `idle-sleep.vitest.ts` parity). The
 *  timer itself is core-tested; this pins the feed, the JSON and the settings
 *  migration. */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleSleepTest {
    /** The core's rule (`idle_sleep_minutes_from_saver`), recorded: a saver the
     *  user changed from its old 30 min default, with the coupling on, carries
     *  over (snapped — 45 → 60 here); anything else is the default. */
    private val calls = mutableListOf<Pair<Int, Boolean>>()
    private val fromSaver: (Int, Boolean) -> Int = { saver, on ->
        calls += saver to on
        if (on && saver > 0 && saver != 30) 60 else 30
    }

    @Test
    fun aFreshInstallGetsTheDefault() {
        val p = resolveIdleSleep(null, fromSaver, defaultMin = { 30 })
        assertEquals(30, p.idleSleepMin)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun aStoredValueWins() {
        val p = resolveIdleSleep(AppPrefs(idleSleepMin = 90, screensaverAfterMin = 15), fromSaver, defaultMin = { 30 })
        assertEquals(90, p.idleSleepMin)
        assertTrue(calls.isEmpty(), "no migration once set")
    }

    @Test
    fun aChangedSaverWithTheCouplingOnCarriesOver() {
        val p = resolveIdleSleep(
            AppPrefs(screensaverAfterMin = 45, sleepMachineWithSaver = true),
            fromSaver, defaultMin = { 30 },
        )
        assertEquals(45 to true, calls.single(), "the saver and the coupling reach the core rule")
        assertEquals(60, p.idleSleepMin)
    }

    @Test
    fun theSaverAtItsOldDefaultGetsTheDefault() {
        val p = resolveIdleSleep(
            AppPrefs(screensaverAfterMin = 30, sleepMachineWithSaver = true),
            fromSaver, defaultMin = { 30 },
        )
        assertEquals(30 to true, calls.single())
        assertEquals(30, p.idleSleepMin)
    }

    @Test
    fun theCouplingOffGetsTheDefault() {
        val p = resolveIdleSleep(
            AppPrefs(screensaverAfterMin = 90, sleepMachineWithSaver = false),
            fromSaver, defaultMin = { 30 },
        )
        assertEquals(90 to false, calls.single())
        assertEquals(30, p.idleSleepMin)
    }

    @Test
    fun anUntouchedPreTimerFileGetsTheDefault() {
        // A prefs file written before the timer with the saver never touched
        // decodes to the old defaults (30 min, coupling on).
        val p = resolveIdleSleep(AppPrefs(), fromSaver, defaultMin = { 30 })
        assertEquals(30, p.idleSleepMin)
    }

    @Test
    fun theSettingTravelsInCommonSettingsAndAnOldBackupKeepsThisDevicesValue() {
        val c = AppPrefs(idleSleepMin = 15).toCommonSettings()
        assertEquals(15u, c.idleSleepMin)
        // Retired, but still written for older readers of a backup.
        assertTrue(c.suppressDe1Sleep)
        assertEquals(15, AppPrefs(idleSleepMin = 30).withCommonSettings(c).idleSleepMin)
        val old = c.copy(idleSleepMin = null)
        assertEquals(120, AppPrefs(idleSleepMin = 120).withCommonSettings(old).idleSleepMin)
    }

    @Test
    fun inputsEncodeAsTheCoresAdjacentlyTaggedJson() {
        assertEquals("""{"type":"Tick"}""", idleSleepInputJson(IdleSleepInput.Tick))
        assertEquals("""{"type":"Configure","content":{"minutes":60}}""", idleSleepInputJson(configureInput(60)))
        assertEquals("""{"type":"Activity","content":{"kind":"Touch"}}""", idleSleepInputJson(activityInput(IdleActivity.Touch)))
        assertEquals("""{"type":"Link","content":{"ready":false}}""", idleSleepInputJson(linkInput(false)))
        assertEquals("""{"type":"Configure","content":{"minutes":0}}""", idleSleepInputJson(configureInput(-5)))
    }

    @Test
    fun theRunnerTicksEveryThirtySecondsOnTheInjectedClock() = runTest {
        val fed = mutableListOf<Pair<IdleSleepInput, Long>>()
        val runner = IdleSleepRunner(TestScope(testScheduler), clock = { testScheduler.currentTime }, feed = { i, t -> fed += i to t })
        runner.start()
        runner.start() // idempotent
        advanceTimeBy(IDLE_SLEEP_TICK_MS + 1)
        runCurrent()
        assertEquals(listOf<Pair<IdleSleepInput, Long>>(IdleSleepInput.Tick to IDLE_SLEEP_TICK_MS), fed)
        advanceTimeBy(IDLE_SLEEP_TICK_MS)
        runCurrent()
        assertEquals(2, fed.count { it.first == IdleSleepInput.Tick })
        runner.stop()
        advanceTimeBy(10 * IDLE_SLEEP_TICK_MS)
        runCurrent()
        assertEquals(2, fed.size)
    }

    @Test
    fun touchesAreThrottledButForegroundAndMirrorAreNot() {
        var now = 1_000L
        val fed = mutableListOf<IdleSleepInput>()
        val runner = IdleSleepRunner(TestScope(), clock = { now }, feed = { i, _ -> fed += i })
        runner.activity(IdleActivity.Touch)
        now += 200
        runner.activity(IdleActivity.Touch)
        runner.activity(IdleActivity.Key)
        assertEquals(1, fed.size, "one feed per second of touches")
        runner.activity(IdleActivity.Foreground)
        runner.activity(IdleActivity.Mirror)
        runner.activity(IdleActivity.Navigation)
        assertEquals(4, fed.size)
        now += IDLE_ACTIVITY_THROTTLE_MS
        runner.activity(IdleActivity.Touch)
        assertEquals(5, fed.size)
        assertEquals(activityInput(IdleActivity.Touch), fed.last())
    }

    @Test
    fun configureAndLinkGoStraightThrough() {
        val fed = mutableListOf<IdleSleepInput>()
        val runner = IdleSleepRunner(TestScope(), clock = { 5L }, feed = { i, _ -> fed += i })
        runner.configure(90)
        runner.link(true)
        runner.tick()
        assertEquals(listOf<IdleSleepInput>(configureInput(90), linkInput(true), IdleSleepInput.Tick), fed)
    }

    @Test
    fun sleepOnQuitNeverDoublesAnIdleSleepInFlight() {
        assertTrue(quitSleepAllowed(lastIdleSleepAtMs = 0L, nowMs = 10_000L, windowMs = 15_000L))
        assertFalse(quitSleepAllowed(lastIdleSleepAtMs = 5_000L, nowMs = 10_000L, windowMs = 15_000L))
        assertTrue(quitSleepAllowed(lastIdleSleepAtMs = 5_000L, nowMs = 20_000L, windowMs = 15_000L))
    }

    @Test
    fun theStepperMapsChoicesAndLabels() {
        val choices = IDLE_SLEEP_FALLBACK_CHOICES
        assertEquals(3, idleSleepIndex(60, choices))
        assertEquals(0, idleSleepIndex(0, choices))
        assertEquals(2, idleSleepIndex(35, choices), "an off-list value snaps to the nearest")
        assertEquals("Never", idleSleepLabel(0))
        assertEquals("90 min", idleSleepLabel(90))
        assertEquals(30, DEFAULT_IDLE_SLEEP_MIN, "default 30")
    }
}
