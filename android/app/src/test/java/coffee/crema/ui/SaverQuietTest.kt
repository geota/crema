package coffee.crema.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behind the screensaver the published UI state ignores telemetry-rate
 * changes (cohort review 2026-10-25, Android P2-4): the whole screen tree
 * used to recompose several times a second for hours under the saver.
 */
class SaverQuietTest {
    private val base = MainUiState(saverVisible = true, pressure = 1f, eventLog = listOf("a"))

    @Test
    fun liveOnlyChangesAreHiddenWhileTheSaverIsUp() {
        val next = base.copy(
            pressure = 8.8f,
            flow = 2f,
            headTemp = 92f,
            telemetry = "t",
            shotTelemetry = listOf(),
            scaleWeightG = 18f,
            waterLevelMm = 40f,
            modeElapsedMs = 1_000,
            eventLog = listOf("b", "a"),
        )
        assertTrue(hiddenBySaver(base, next))
    }

    @Test
    fun anyOtherChangePublishes() {
        assertFalse(hiddenBySaver(base, base.copy(pressure = 9f, themeMode = "light")))
        assertFalse(hiddenBySaver(base, base.copy(saverVisible = false)), "dismissing the saver publishes the latest state")
    }

    @Test
    fun withoutTheSaverEverythingPublishes() {
        val awake = base.copy(saverVisible = false)
        assertFalse(hiddenBySaver(awake, awake.copy(pressure = 9f)))
    }
}
