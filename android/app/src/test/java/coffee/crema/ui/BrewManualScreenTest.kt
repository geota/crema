package coffee.crema.ui

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import coffee.crema.ui.phone.PhoneBrewScreen
import coffee.crema.ui.phone.PhoneScaleScreen
import coffee.crema.ui.screens.BrewScreen
import coffee.crema.ui.screens.ScaleScreen
import coffee.crema.ui.theme.CremaTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Brew's modes on screen (brew-modes spec §2–§4): the tablet picker's
 * Espresso | Manual tabs beside search, opening on the mode's tab; a recipe
 * pick turns Brew into the Manual page (steps, Step tile, Start brew, Hot
 * water kept, Steam and Flush gone, Duplicate to edit); the lock while a
 * guided brew runs; Devices → Scale without Weigh | Brew; the phone's Manual page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w1280dp-h800dp-land")
class BrewManualScreenTest {

    @get:Rule val compose = createComposeRule()

    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    private fun vm(): MainViewModel {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(vm)
        VmHarness.awaitStartupLoaded(vm)
        VmHarness.idle()
        return vm
    }

    private fun tablet(vm: MainViewModel) {
        compose.setContent { CremaTheme { BrewScreen(vm, onNav = {}, onConnect = {}) } }
        compose.waitForIdle()
    }

    /** The header's picker anchor: its title is the profile or recipe on show. */
    private fun openPicker(title: String) {
        compose.onNode(hasText(title) and hasClickAction(), useUnmergedTree = false).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `the picker's tabs open on Espresso, and a recipe pick is Manual`() {
        val vm = vm()
        val profile = VmHarness.ownState(vm).profiles.first()
        vm.setActiveProfile(profile.id)
        val own = VmHarness.ownState(vm)
        val recipe = coffee.crema.ui.brewlog.visibleBrewRecipes(own.brewRecipes, "", own.builtinRecipes, own.hiddenBuiltinRecipes).first()
        tablet(vm)
        compose.onNodeWithText("Quick Controls").assertExists()
        compose.onNodeWithText("Steam").assertExists()

        openPicker(vm.ui.value.activeProfile()!!.name)
        compose.onNodeWithTag("brew-mode-tabs").assertExists()
        compose.onNodeWithText("Search profiles…").assertExists()
        compose.onNodeWithText("Manual").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Search recipes…").assertExists()
        compose.onNode(hasText(recipe.name) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(recipe.id, vm.ui.value.activeRecipeId)
        compose.onNodeWithTag("manual-page").assertExists()
        compose.onNodeWithText("RECIPE · ${coffee.crema.brew.methodLabel(recipe.method).uppercase()}").assertExists()
        compose.onNodeWithTag("manual-start-brew").assertExists()
        compose.onNodeWithTag("manual-step-tile").assertExists()
        compose.onNodeWithTag("manual-steps").assertExists()
        compose.onNodeWithText("Duplicate to edit").assertExists()
        compose.onNodeWithText("Quick Controls").assertDoesNotExist()
        compose.onNodeWithText("Hot water").assertExists()
        compose.onNodeWithText("Steam").assertDoesNotExist()
        compose.onNodeWithText("Flush").assertDoesNotExist()
        compose.onNodeWithText("Coffee").assertDoesNotExist()

        // Back on the picker it opens on Manual; a profile turns Brew back.
        openPicker(recipe.name)
        compose.onNodeWithText("Search recipes…").assertExists()
        compose.onNodeWithText("Espresso").performClick()
        compose.waitForIdle()
        compose.onNode(hasText(profile.name) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(null, vm.ui.value.activeRecipeId)
        compose.onNodeWithTag("manual-page").assertDoesNotExist()
        compose.onNodeWithText("Steam").assertExists()
    }

    @Test
    fun `Start brew runs the guided brew on Brew, and the picker locks its rows`() {
        val vm = vm()
        val recipe = VmHarness.ownState(vm).builtinRecipes.first()
        vm.pickRecipe(recipe.id)
        tablet(vm)
        compose.onNodeWithTag("manual-start-brew").performClick()
        VmHarness.drainCoreLane(vm)
        compose.waitForIdle()
        assertEquals("running", VmHarness.ownState(vm).guidedBrew.phase)
        compose.onNodeWithTag("guided-finish").assertExists()
        compose.onNodeWithTag("manual-step-current").assertExists()

        openPicker(recipe.name)
        compose.onNodeWithTag("brew-pick-lock").assertExists()
        compose.onNodeWithText("Finish the current brew first").assertExists()
        val other = VmHarness.ownState(vm).builtinRecipes.first { it.id != recipe.id }
        compose.onNode(hasText(other.name) and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Espresso").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Finish the current brew first").assertExists()
        vm.brewSessionCancel()
    }

    @Test
    fun `Devices - Scale only weighs, on the tablet`() {
        val vm = vm()
        compose.setContent { CremaTheme { ScaleScreen(vm, onNav = {}, onConnect = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("Weigh").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `the phone's Manual page has the step card, the steps and Start brew`() {
        val vm = vm()
        val recipe = VmHarness.ownState(vm).builtinRecipes.first()
        vm.pickRecipe(recipe.id)
        compose.setContent { CremaTheme { PhoneBrewScreen(vm, onNav = {}, onConnect = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("${recipe.name.trim()} · ${coffee.crema.brew.methodLabel(recipe.method)}").assertExists()
        compose.onNodeWithTag("manual-phone-step").assertExists()
        compose.onNodeWithTag("manual-steps").assertExists()
        compose.onNodeWithTag("manual-start-brew").assertExists()
        compose.onNodeWithText("Steam").assertDoesNotExist()
        compose.onNodeWithText("Water").assertDoesNotExist()
        compose.onNodeWithText("Flush").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `the phone's Devices - Scale keeps Weigh and Brew until its picker has the Manual tab`() {
        val vm = vm()
        compose.setContent { CremaTheme { PhoneScaleScreen(vm, onNav = {}, onConnect = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("Weigh").assertExists()
    }
}
