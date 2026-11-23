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

        vm.pickProfile(profile)
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

        again.pickProfile(again.profileId())
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

    /** Bags "a" (active) and "b" in this device's library, before the VM loads it. */
    private fun writeTwoBags() =
        java.io.File(VmHarness.app().filesDir, "beanLibrary.json").writeText(
            coreJson.encodeToString(
                coffee.crema.beans.BeanLibrary.serializer(),
                coffee.crema.beans.BeanLibrary(
                    listOf(
                        coffee.crema.beans.newBean("Bag a", null, null, null, 0L).copy(id = "a", updatedAt = 1L),
                        coffee.crema.beans.newBean("Bag b", null, null, null, 0L).copy(id = "b", updatedAt = 1L),
                    ),
                    activeBeanId = "a",
                ),
            ),
        )

    /** Manual with a guided brew armed on this hub (REVIEW-532 M1's probe). */
    private fun armedInManual(): Triple<MainViewModel, coffee.crema.core.BrewRecipe, String> {
        val vm = vm()
        val profile = vm.profileId()
        vm.setActiveProfile(profile)
        val recipe = vm.recipe()
        vm.pickRecipe(recipe.id)
        vm.brewSessionArm(recipe, startOnPour = true)
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
        assertEquals("armed", vm.own().guidedBrew.phase)
        assertEquals(BrewMode.Manual, vm.own().brewView().mode)
        return Triple(vm, recipe, profile)
    }

    private fun MainViewModel.assertStillManual(recipe: coffee.crema.core.BrewRecipe, profile: String, what: String) {
        assertEquals(recipe.id, own().activeRecipeId, "$what: the brew keeps its recipe")
        assertEquals(BrewMode.Manual, own().brewView().mode, "$what: Brew stays on its Manual page")
        assertEquals("armed", own().guidedBrew.phase, what)
        assertEquals(profile, own().activeProfileId, "$what: the profile is held like a shot's")
    }

    /** REVIEW-532 M1: a peer's bag pick with a linked profile can't strand the hub's guided brew. */
    @Test
    fun `a relayed bag pick with a linked profile leaves the hub's guided brew in Manual`() {
        writeTwoBags()
        val (vm, recipe, profile) = armedInManual()
        val linked = vm.own().profiles.first { it.id != profile && it.source != "custom" }
        vm.updateBean("b", "") { it.copy(linkedProfileId = linked.id) }
        VmHarness.idle()

        val outcome = runBlocking { VmHarness.proxyController(vm).handleRelayedControl("setActiveBean", "b", "remote-1", "Phone") }
        VmHarness.idle()
        assertTrue(outcome !is CommandOutcome.Refused, "the bag itself is picked: $outcome")
        assertEquals("b", vm.own().activeBeanId)
        vm.assertStillManual(recipe, profile, "relayed bag pick")

        // The hub's own bag pick, during its own pour-over, the same.
        vm.setActiveBean("a")
        vm.setActiveBean("b")
        VmHarness.idle()
        vm.assertStillManual(recipe, profile, "local bag pick")
        vm.brewSessionCancel()
    }

    /** REVIEW-532 M1 (c): a peer's Quick Save copy is saved, not made active, during the hub's brew. */
    @Test
    fun `a relayed Quick Save copy during the hub's guided brew saves without activating`() {
        val (vm, recipe, profile) = armedInManual()
        vm.quickAdjustBrew(19.5, 41.0, 91.0)
        VmHarness.idle()
        val outcome = runBlocking { VmHarness.proxyController(vm).handleRelayedControl("quickSave", "copy:Probe copy", "remote-1", "Phone") }
        VmHarness.idle()
        assertTrue(outcome !is CommandOutcome.Refused, "$outcome")
        assertEquals(1, vm.own().profiles.count { it.name == "Probe copy" }, "the copy is saved")
        vm.assertStillManual(recipe, profile, "relayed Quick Save copy")
        vm.brewSessionCancel()
    }

    /** REVIEW-532 M1 (a): a profile change that does land mid-brew still keeps the recipe. */
    @Test
    fun `a profile set during a guided brew keeps the recipe`() {
        val (vm, recipe, _) = armedInManual()
        val other = vm.own().profiles.first { it.id != vm.own().activeProfileId }
        vm.setActiveProfile(other.id)
        VmHarness.idle()
        assertEquals(recipe.id, vm.own().activeRecipeId)
        assertEquals(BrewMode.Manual, vm.own().brewView().mode)
        vm.brewSessionCancel()
    }

    /** Web parity (#531): only Brew's picker leaves Manual; other profile changes update the profile. */
    @Test
    fun `a profile change outside Brew's picker keeps Manual, even at rest`() {
        val vm = vm()
        val recipe = vm.recipe()
        vm.pickRecipe(recipe.id)
        val other = vm.own().profiles.first { it.id != vm.own().activeProfileId }
        vm.setActiveProfile(other.id)
        VmHarness.idle()
        assertEquals(other.id, vm.own().activeProfileId, "the stored profile follows")
        assertEquals(recipe.id, vm.own().activeRecipeId)
        assertEquals(BrewMode.Manual, vm.own().brewView().mode)
    }

    /** REVIEW-532 round 2: a peer's bag pick after a brew finishes, before it's saved, keeps "Save brew…". */
    @Test
    fun `a relayed bag pick after a brew finishes keeps its Manual page until it's saved`() {
        writeTwoBags()
        val vm = vm()
        val recipe = vm.recipe()
        vm.pickRecipe(recipe.id)
        vm.brewSessionArm(recipe, startOnPour = false)
        vm.brewSessionBegin()
        VmHarness.drainCoreLane(vm)
        vm.brewSessionFinish()
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
        assertEquals("done", vm.own().guidedBrew.phase)
        val linked = vm.own().profiles.first { it.id != vm.own().activeProfileId && it.source != "custom" }
        vm.updateBean("b", "") { it.copy(linkedProfileId = linked.id) }
        VmHarness.idle()

        runBlocking { VmHarness.proxyController(vm).handleRelayedControl("setActiveBean", "b", "remote-1", "Phone") }
        VmHarness.idle()
        assertEquals(linked.id, vm.own().activeProfileId, "nothing runs: the linked profile loads")
        assertEquals(recipe.id, vm.ui.value.manualRecipe()?.id, "Manual stays, with Save brew…")
        assertEquals(BrewMode.Manual, vm.own().brewView().mode)
        vm.brewSessionClearSummary()
    }

    /** REVIEW-532 round 2: deleting the running recipe mid-brew keeps its Manual page. */
    @Test
    fun `deleting the running recipe mid-brew keeps the brew's Manual page`() {
        val vm = vm()
        val copy = assertNotNull(vm.duplicateBrewRecipe(vm.recipe().id))
        vm.pickRecipe(copy.id)
        vm.brewSessionArm(copy, startOnPour = true)
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
        vm.deleteBrewRecipe(copy.id)
        VmHarness.idle()
        assertNull(vm.ui.value.selectedRecipe(), "the selection falls back")
        assertEquals("armed", vm.own().guidedBrew.phase)
        assertEquals(copy.id, vm.ui.value.manualRecipe()?.id, "the brew keeps its page with its own recipe")
        vm.brewSessionCancel()
        assertNull(vm.ui.value.manualRecipe(), "with the brew gone, Espresso")
    }

    @Test
    fun `the hub's profile reaching this device keeps its recipe`() {
        val stored = coffee.crema.core.StoredBrewSelection(activeProfileId = "p1", activeRecipeId = "builtin:x")
        val next = BrewSelectionRules.followHub(stored, "p2")
        assertEquals(coffee.crema.core.StoredBrewSelection(activeProfileId = "p2", activeRecipeId = "builtin:x"), next)
    }
}
