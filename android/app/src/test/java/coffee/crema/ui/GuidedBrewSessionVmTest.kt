package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.BrewRecipe
import coffee.crema.core.BrewStep
import coffee.crema.core.BrewStepKind
import coffee.crema.core.StepAdvance
import coffee.crema.ui.brewlog.BrewLogOwner
import coffee.crema.ui.brewlog.ScaleMode
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A guided session driven through the real core (MainViewModel breakup step
 * 3): the commands' output applies on the core lane, and the session handlers
 * the controller registers there move the UI from armed to running, through
 * each step, to the summary, then stop the tick.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class GuidedBrewSessionVmTest {
    private val store = ViewModelStore()

    @After fun tearDown() { store.clear(); VmHarness.idle() }

    private val recipe = BrewRecipe(
        id = coffee.crema.core.newRecipeId(),
        name = "Morning V60",
        method = "pourover",
        doseG = 15f,
        waterG = 250f,
        tempC = 96f,
        steps = listOf(
            BrewStep(kind = BrewStepKind.Bloom, targetWaterG = 45f, durationS = 45, advance = StepAdvance.Manual),
            BrewStep(kind = BrewStepKind.Pour, targetWaterG = 250f, advance = StepAdvance.Manual),
            BrewStep(kind = BrewStepKind.Drawdown, advance = StepAdvance.Manual),
        ),
        notes = null,
        favourite = false,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun settle(vm: MainViewModel) {
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
    }

    private fun session(vm: MainViewModel): GuidedBrewUi = VmHarness.ownState(vm).guidedBrew

    @Test
    fun `a session runs from arm through its steps to the summary, then the tick stops`() {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        val guided = VmHarness.guidedBrew(vm)

        vm.brewSessionArm(recipe, startOnPour = false)
        settle(vm)
        assertEquals("armed", session(vm).phase)
        assertTrue(guided.brewTicking, "the session tick runs while armed")
        assertEquals(ScaleMode.BREW, vm.guidedSetup.value.scaleMode)
        // Arming remembers the recipe as its method's default.
        assertEquals(recipe.id, VmHarness.ownState(vm).lastRecipeByMethod["pourover"])

        vm.brewSessionBegin()
        settle(vm)
        assertEquals("running", session(vm).phase)
        assertEquals(0, session(vm).stepIndex)
        assertNotNull(session(vm).startedAtRealtime)
        assertEquals(1, vm.liveBrewSeries.value.stageMarks.size)
        assertTrue(VmHarness.log(vm).any { it.contains("Guided brew started — Morning V60") }, "${VmHarness.log(vm)}")

        vm.brewSessionSkip()
        settle(vm)
        assertEquals(1, session(vm).stepIndex)
        assertEquals(2, vm.liveBrewSeries.value.stageMarks.size)
        assertEquals("step", session(vm).cueKind, "a step change flashes the step cue")

        vm.brewSessionSkip()
        settle(vm)
        assertEquals(2, session(vm).stepIndex)

        vm.brewSessionFinish()
        settle(vm)
        assertEquals("done", session(vm).phase)
        assertEquals("Morning V60", session(vm).summary?.recipeName)
        assertFalse(guided.brewTicking, "the tick stops with the session")
        assertTrue(VmHarness.log(vm).any { it.contains("Guided brew completed — Morning V60") }, "${VmHarness.log(vm)}")

        // "Save brew…" opens the log form on the Scale tab, seeded from the summary.
        vm.openGuidedLogBrew()
        val draft = assertNotNull(vm.logBrew.value)
        assertEquals(BrewLogOwner.SCALE, draft.owner)
        assertEquals("pourover", draft.method)
        assertEquals("Morning V60", draft.recipeName)
        vm.closeLogBrew()
        assertNull(vm.logBrew.value)

        vm.brewSessionClearSummary()
        assertEquals(GuidedBrewUi(), session(vm))
    }

    @Test
    fun `cancelling an armed session stops the tick and clears it`() {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        val guided = VmHarness.guidedBrew(vm)

        vm.brewSessionArm(recipe, startOnPour = true)
        settle(vm)
        assertTrue(guided.brewTicking)
        vm.brewSessionCancel()
        settle(vm)
        assertFalse(guided.brewTicking)
        assertEquals(GuidedBrewUi(), session(vm))
    }
}
