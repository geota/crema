package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.BrewMode
import coffee.crema.core.BrewPickLockReason
import coffee.crema.core.BrewSelection
import coffee.crema.core.BrewSelectionKind
import coffee.crema.machine.CommandOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Brew's selection decides its mode (brew-modes spec §2, §4, §5) on Android,
 * through the core's BrewSelection functions: a recipe pick is Manual and
 * keeps the profile, a profile pick is Espresso, the selection survives a
 * restart, the lock holds while a guided brew runs, and the hub refuses a
 * remote's profile pick while its own shot or guided brew runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BrewModeVmTest {
    private var store = ViewModelStore()

    @After fun tearDown() { store.clear(); VmHarness.idle() }

    private fun vm(): MainViewModel {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(vm)
        VmHarness.awaitStartupLoaded(vm)
        VmHarness.idle()
        return vm
    }

    /** The app closed and opened again: a new view model over the same files. */
    private fun reopen(): MainViewModel {
        VmHarness.idle()
        store.clear()
        VmHarness.idle()
        store = ViewModelStore()
        return vm()
    }

    private fun MainViewModel.own() = VmHarness.ownState(this)

    /** What a load would see, through the same lock as a save. */
    private fun savedPrefs(): String =
        (coffee.crema.persist.SafeFile.load(java.io.File(VmHarness.app().filesDir, "prefs.json"), open = false) { it } as? coffee.crema.persist.SafeLoad.Loaded)
            ?.value.orEmpty()

    private fun MainViewModel.recipe() = own().builtinRecipes.first()

    private fun MainViewModel.profileId() = own().profiles.first().id

    @Test
    fun `a fresh install opens in Espresso`() {
        val vm = vm()
        assertNull(vm.own().activeRecipeId)
        assertEquals(BrewMode.Espresso, vm.own().brewView().mode)
        assertNull(vm.own().selectedRecipe())
    }

    @Test
    fun `a recipe pick is Manual and keeps the profile, a profile pick is Espresso`() {
        val vm = vm()
        val profile = vm.profileId()
        vm.setActiveProfile(profile)
        VmHarness.idle()
        val recipe = vm.recipe()

        vm.pickRecipe(recipe.id)
        VmHarness.idle()
        assertEquals(BrewSelection(BrewSelectionKind.Recipe, recipe.id), vm.own().brewView().selection)
        assertEquals(BrewMode.Manual, vm.ui.value.brewView().mode)
        assertEquals(recipe.id, vm.ui.value.selectedRecipe()?.id)
        assertEquals(profile, vm.own().activeProfileId, "Espresso's profile stays")
        assertEquals(recipe.id, vm.guidedSetup.value.recipe?.id, "the guided engine brews the pick")

        vm.setActiveProfile(profile)
        VmHarness.idle()
        assertNull(vm.own().activeRecipeId)
        assertEquals(BrewSelection(BrewSelectionKind.Profile, profile), vm.own().brewView().selection)
    }

    @Test
    fun `the app reopens on the last selection`() {
        val first = vm()
        val recipe = first.recipe()
        first.pickRecipe(recipe.id)
        VmHarness.idleUntil("the recipe pick saved") { savedPrefs().contains("\"activeRecipeId\":\"${recipe.id}\"") }

        val again = reopen()
        assertEquals(recipe.id, again.own().activeRecipeId)
        assertEquals(BrewMode.Manual, again.own().brewView().mode)

        again.setActiveProfile(again.profileId())
        VmHarness.idleUntil("the profile pick saved") { !savedPrefs().contains("activeRecipeId\":\"") }
        assertEquals(BrewMode.Espresso, reopen().own().brewView().mode)
    }

    @Test
    fun `a deleted recipe falls back to Espresso`() {
        val vm = vm()
        val copy = assertNotNull(vm.duplicateBrewRecipe(vm.recipe().id))
        vm.pickRecipe(copy.id)
        assertEquals(BrewMode.Manual, vm.own().brewView().mode)
        vm.deleteBrewRecipe(copy.id)
        assertEquals(BrewMode.Espresso, vm.own().brewView().mode)
        assertNull(vm.own().selectedRecipe())
    }

    @Test
    fun `a guided brew locks every row, a shot only the profiles`() {
        val vm = vm()
        val recipe = vm.recipe()
        vm.pickRecipe(recipe.id)
        vm.brewSessionArm(recipe, startOnPour = true)
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
        val lock = assertNotNull(vm.own().brewPickLock())
        assertEquals(BrewPickLockReason.GuidedBrew, lock.reason)
        assertTrue(lock.recipesLocked)

        val other = vm.own().builtinRecipes.first { it.id != recipe.id }
        vm.pickRecipe(other.id)
        assertEquals(recipe.id, vm.own().activeRecipeId, "no pick while the brew runs")
        vm.brewSessionCancel()
        assertNull(vm.own().brewPickLock())

        val shot = MainUiState(shotInProgress = true).brewPickLock()
        assertEquals(BrewPickLockReason.Shot, shot?.reason)
        assertEquals(false, shot?.recipesLocked)
        assertEquals("Finish the current shot first", shot?.note)
    }

    @Test
    fun `the hub refuses a remote's profile pick while its guided brew runs`() {
        val vm = vm()
        val profile = vm.profileId()
        val pc = VmHarness.proxyController(vm)
        val recipe = vm.recipe()
        vm.brewSessionArm(recipe, startOnPour = true)
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()

        val refused = runBlocking { pc.handleRelayedControl("setActiveProfile", profile, "remote-1", "Phone") }
        assertEquals(CommandOutcome.Refused("Finish the current brew first"), refused)

        vm.brewSessionCancel()
        val accepted = runBlocking { pc.handleRelayedControl("setActiveProfile", profile, "remote-1", "Phone") }
        assertIs<CommandOutcome.Accepted>(accepted)
        assertEquals(profile, vm.own().activeProfileId)
    }

    @Test
    fun `the hub's profile reaching this device keeps its recipe`() {
        val stored = coffee.crema.core.StoredBrewSelection(activeProfileId = "p1", activeRecipeId = "builtin:x")
        val next = BrewSelectionRules.followHub(stored, "p2")
        assertEquals(coffee.crema.core.StoredBrewSelection(activeProfileId = "p2", activeRecipeId = "builtin:x"), next)
    }
}
