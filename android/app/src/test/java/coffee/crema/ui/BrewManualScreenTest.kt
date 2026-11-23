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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
 * Brew's modes on screen (brew-modes spec §2–§4 and the owner's late
 * revision): the tablet picker's search over ESPRESSO PROFILES then MANUAL
 * RECIPES; a recipe pick turns Brew into the Manual page (steps, Step tile,
 * Start brew, Hot water kept, Steam and Flush gone, Duplicate to edit); the
 * lock while a guided brew runs; Devices → Scale without Weigh | Brew; the
 * phone's dropdown (search, sections, grouped results) and Manual page.
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
    fun `the picker lists profiles then recipes, and a recipe pick is Manual`() {
        val vm = vm()
        val profile = VmHarness.ownState(vm).profiles.first()
        vm.setActiveProfile(profile.id)
        val own = VmHarness.ownState(vm)
        val recipe = coffee.crema.ui.brewlog.visibleBrewRecipes(own.brewRecipes, "", own.builtinRecipes, own.hiddenBuiltinRecipes).first()
        tablet(vm)
        compose.onNodeWithText("Quick Controls").assertExists()
        compose.onNodeWithText("Steam").assertExists()

        openPicker(vm.ui.value.activeProfile()!!.name)
        // One list, two sections; no Espresso | Manual tabs (owner revision).
        compose.onNodeWithText("Search profiles and recipes…").assertExists()
        compose.onNodeWithTag("pick-section-profiles").assertExists()
        compose.onNodeWithTag("pick-section-recipes").performScrollTo().assertExists()
        compose.onNodeWithText("Espresso").assertDoesNotExist()
        compose.onNodeWithText("Manual").assertDoesNotExist()
        // The search filters both sections: a recipe's name leaves no profile.
        compose.onNode(hasSetTextAction()).performTextInput(recipe.name)
        compose.waitForIdle()
        compose.onNodeWithTag("pick-section-profiles").assertDoesNotExist()
        compose.onNodeWithTag("pick-section-recipes").assertExists()
        compose.onNode(hasText(recipe.name) and hasClickAction() and !hasSetTextAction()).performScrollTo().performClick()
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

        // Back on the picker both sections show; a profile turns Brew back.
        openPicker(recipe.name)
        compose.onNodeWithTag("pick-section-profiles").assertExists()
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
        compose.onNode(hasText(VmHarness.ownState(vm).profiles.first().name) and hasClickAction()).assertIsNotEnabled()
        vm.brewSessionCancel()
    }

    /** REVIEW-532 round 2: the running recipe deleted mid-brew, then finished: Finish and Save brew… stay reachable. */
    @Test
    fun `a brew whose recipe is deleted keeps its Manual page to Finish and Save`() {
        val vm = vm()
        val copy = vm.duplicateBrewRecipe(VmHarness.ownState(vm).builtinRecipes.first().id)!!
        vm.pickRecipe(copy.id)
        tablet(vm)
        compose.onNodeWithTag("manual-start-brew").performClick()
        VmHarness.drainCoreLane(vm)
        compose.waitForIdle()
        vm.deleteBrewRecipe(copy.id)
        compose.waitForIdle()
        compose.onNodeWithTag("manual-page").assertExists()
        compose.onNodeWithTag("guided-finish").performClick()
        VmHarness.drainCoreLane(vm)
        compose.waitForIdle()
        assertEquals("done", VmHarness.ownState(vm).guidedBrew.phase)
        compose.onNodeWithTag("guided-save").assertExists()
        vm.brewSessionClearSummary()
        compose.waitForIdle()
        compose.onNodeWithTag("manual-page").assertDoesNotExist()
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
    fun `the phone's Devices - Scale only weighs`() {
        val vm = vm()
        compose.setContent { CremaTheme { PhoneScaleScreen(vm, onNav = {}, onConnect = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("Weigh").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `the phone dropdown has search, then profiles, recipes, bean and grinder`() {
        val vm = vm()
        val profile = VmHarness.ownState(vm).profiles.first()
        vm.setActiveProfile(profile.id)
        val recipes = coffee.crema.ui.brewlog.recentBrewRecipes(VmHarness.ownState(vm), null)
        compose.setContent { CremaTheme { PhoneBrewScreen(vm, onNav = {}, onConnect = {}) } }
        compose.waitForIdle()
        compose.onNode(hasText(vm.ui.value.activeProfile()!!.name) and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Search profiles, recipes, beans, grinders").assertExists()
        compose.onNodeWithText("ESPRESSO PROFILES").assertExists()
        compose.onNodeWithText("All profiles →").assertExists()
        compose.onNodeWithText("MANUAL RECIPES").assertExists()
        compose.onNodeWithText("All recipes →").assertExists()
        compose.onNodeWithText("BEAN").performScrollTo().assertExists()
        compose.onNodeWithText("GRINDER").performScrollTo().assertExists()
        // Three recent recipes, not the whole library.
        recipes.take(3).forEach { compose.onNode(hasText(it.name) and hasClickAction()).assertExists() }
        recipes.drop(3).forEach { compose.onNode(hasText(it.name) and hasClickAction()).assertDoesNotExist() }

        // Typing searches every type, grouped; ✕ goes back to the sections.
        compose.onNodeWithTag("dd-search").performTextInput(recipes.first().name)
        compose.waitForIdle()
        compose.onNodeWithText("MANUAL RECIPES · ", substring = true).assertExists()
        compose.onNodeWithText("All recipes →").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("All recipes →").assertExists()

        compose.onNode(hasText(recipes.first().name) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(recipes.first().id, vm.ui.value.activeRecipeId)
        compose.onNodeWithTag("manual-phone-step").assertExists()
    }

    /** Brew-modes polish: "All recipes →" opens Profiles on its recipes only, "All profiles →" on the whole library. */
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `the phone dropdown's library links name the Profiles filter`() {
        val vm = vm()
        vm.setActiveProfile(VmHarness.ownState(vm).profiles.first().id)
        val routes = mutableListOf<String>()
        compose.setContent { CremaTheme { PhoneBrewScreen(vm, onNav = { routes += it }, onConnect = {}) } }
        compose.waitForIdle()
        compose.onNode(hasText(vm.ui.value.activeProfile()!!.name) and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("All recipes →").performClick()
        compose.waitForIdle()
        assertEquals(listOf(NavRestore.PROFILES_RECIPES), routes)
        compose.onNode(hasText(vm.ui.value.activeProfile()!!.name) and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("All profiles →").performClick()
        compose.waitForIdle()
        assertEquals(listOf(NavRestore.PROFILES_RECIPES, NavRestore.PROFILES_ALL), routes)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `the phone Profiles screen on its recipes only leaves the profiles out`() {
        val vm = vm()
        val own = VmHarness.ownState(vm)
        val profile = own.profiles.first()
        val recipe = coffee.crema.ui.brewlog.visibleBrewRecipes(own.brewRecipes, "", own.builtinRecipes, own.hiddenBuiltinRecipes).first()
        val routes = mutableListOf<String>()
        var filter by androidx.compose.runtime.mutableStateOf<String?>(NavRestore.FILTER_RECIPES)
        compose.setContent {
            CremaTheme { coffee.crema.ui.phone.PhoneProfilesScreen(vm, onNav = { routes += it }, onConnect = {}, filter = filter) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("profiles-recipes-only").assertExists()
        compose.onNodeWithText("Search recipes").assertExists()
        compose.onNodeWithText(profile.name).assertDoesNotExist()
        compose.onNodeWithText(recipe.name).performScrollTo().assertExists()
        compose.onNodeWithText("Show profiles too").performClick()
        assertEquals(listOf(NavRestore.PROFILES_ALL), routes)

        // The whole library: the profiles are back, the note is gone.
        filter = "all"
        compose.waitForIdle()
        compose.onNodeWithTag("profiles-recipes-only").assertDoesNotExist()
        compose.onNodeWithText(profile.name).assertExists()
    }

    @Test
    fun `the tablet Profiles screen on its recipes only leaves the profiles out`() {
        val vm = vm()
        val own = VmHarness.ownState(vm)
        val profile = own.profiles.first()
        compose.setContent {
            CremaTheme { coffee.crema.ui.screens.ProfilesScreen(vm, onNav = {}, onConnect = {}, filter = NavRestore.FILTER_RECIPES) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("profiles-recipes-only").assertExists()
        compose.onNodeWithText(profile.name).assertDoesNotExist()
        compose.onNodeWithText("Brew recipes").assertExists()
    }
}
