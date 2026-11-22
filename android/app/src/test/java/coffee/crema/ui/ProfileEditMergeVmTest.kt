package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.profiles.CremaProfile
import coffee.crema.profiles.SegmentEdit
import coffee.crema.profiles.toEdit
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * REVIEW-FINAL S6 / REVIEW-FINAL-2 N2 on Android: the profile editor's Save
 * goes through the core's `merge_profile_edit`, so what the user changed lands
 * on the stored row and a pin written while the editor was open stays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ProfileEditMergeVmTest {
    private val store = ViewModelStore()

    @After fun clear() {
        store.clear()
        VmHarness.idle()
    }

    private fun vm(): MainViewModel = VmHarness.build(store).also {
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(it)
    }

    private fun MainViewModel.profile(id: String) = VmHarness.ownState(this).profiles.single { it.id == id }

    /** Save as both editor screens do, from the fields they seeded from [p]. */
    private fun MainViewModel.saveAsEditor(
        p: CremaProfile,
        name: String = p.name,
        segments: List<SegmentEdit> = p.segments.map { it.toEdit(p.brewTemp) },
    ) = saveProfile(
        id = p.id,
        name = name,
        roast = p.roast,
        tags = p.tags.filterNot { it.equals("Built-in", ignoreCase = true) },
        pinned = p.pinned,
        notes = p.notes,
        author = p.author,
        beverageType = p.beverageType,
        dose = p.dose.toDouble().toFloat(),
        yieldOut = p.yieldOut.toDouble().toFloat(),
        brewTemp = (segments.firstOrNull()?.temp ?: p.brewTemp).toDouble().toFloat(),
        maxTotalVolumeMl = p.maxTotalVolumeMl,
        preinfuseStepCount = p.preinfuseStepCount,
        tankTemperatureC = p.tankTemperatureC.toDouble().toFloat(),
        segments = segments,
    )

    /** A stored custom profile, named [name]. */
    private fun MainViewModel.custom(name: String): String {
        startNewProfile()
        VmHarness.idle()
        val draft = assertNotNull(ui.value.draftProfile)
        saveAsEditor(draft, name = name)
        VmHarness.idle()
        assertEquals(name, profile(draft.id).name)
        return draft.id
    }

    @Test
    fun `a pin made while the editor is open survives its Save`() {
        val vm = vm()
        val id = vm.custom("Mine")
        vm.startEditProfile(id)
        VmHarness.idle()
        val opened = vm.profile(id)
        assertEquals(false, opened.pinned)
        // Meanwhile: the Favorites star (another screen, or a remote's on the hub).
        vm.togglePinProfile(id)
        VmHarness.idle()
        assertTrue(vm.profile(id).pinned)

        vm.saveAsEditor(opened, name = "Renamed")
        VmHarness.idle()
        val saved = vm.profile(id)
        assertEquals("Renamed", saved.name, "the user's edit lands")
        assertTrue(saved.pinned, "the pin made meanwhile stays")
    }

    @Test
    fun `an edited body lands whole and the pin made meanwhile stays`() {
        val vm = vm()
        val id = vm.custom("Mine")
        vm.startEditProfile(id)
        VmHarness.idle()
        val opened = vm.profile(id)
        vm.togglePinProfile(id)
        VmHarness.idle()

        val segs = opened.segments.map { it.toEdit(opened.brewTemp) }
        val edited = segs.mapIndexed { i, s -> if (i == 0) s.copy(target = s.target + 1f) else s }
        vm.saveAsEditor(opened, segments = edited)
        VmHarness.idle()
        val saved = vm.profile(id)
        assertEquals(opened.segments[0].target + 1f, saved.segments[0].target)
        assertEquals(opened.name, saved.name)
        assertTrue(saved.pinned)
    }

    /** REVIEW-519: a profile deleted while open in the editor isn't added back
     *  by its Save, which says the changes weren't saved. */
    @Test
    fun `a Save of a profile deleted while open adds nothing and says so`() {
        val vm = vm()
        val id = vm.custom("Mine")
        vm.startEditProfile(id)
        VmHarness.idle()
        val opened = vm.profile(id)
        vm.deleteProfile(id)
        VmHarness.idle()
        val messages = vm.ui.value.userMessages.size

        vm.saveAsEditor(opened, name = "Renamed")
        VmHarness.idle()
        assertTrue(VmHarness.ownState(vm).profiles.none { it.id == id }, "not re-added")
        assertEquals(listOf("“Renamed” was deleted elsewhere; your changes weren’t saved"), vm.ui.value.userMessages.drop(messages))
    }
}
