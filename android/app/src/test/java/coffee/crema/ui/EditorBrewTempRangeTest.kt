package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
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
 * F8 follow-up: both profile editors' Brew temp field uses the core's
 * per-profile range (F6 `brew_temp_range`) — the one the Quick Controls dial
 * uses — instead of a 20 °C (tablet) or 80 °C (phone) floor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EditorBrewTempRangeTest {
    private val store = ViewModelStore()

    @After fun clear() {
        store.clear()
        VmHarness.idle()
    }

    @Test
    fun `the editor brew temp range is the core's per-profile range`() {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        vm.startNewProfile()
        VmHarness.idle()
        val draft = assertNotNull(vm.ui.value.draftProfile)
        val segs = draft.segments.map { it.toEdit(draft.brewTemp) }
        val (min, max) = assertNotNull(vm.editorBrewTempRange(draft.id, segs))
        // A flat blank profile: the dial's floor and the core's 100 °C ceiling.
        assertEquals(CoreBounds.qc.brewTempC.min.toDouble(), min)
        assertEquals(CoreBounds.qc.brewTempC.max.toDouble(), max)
        // A step 10 °C above the first narrows the top so no step leaves 100 °C.
        val stepped = segs.mapIndexed { i, s -> if (i == 1) s.copy(temp = (s.temp ?: draft.brewTemp) + 10f) else s }
        val (_, steppedMax) = assertNotNull(vm.editorBrewTempRange(draft.id, stepped))
        assertTrue(steppedMax < max, "a hotter later step lowers the top: $steppedMax")
    }
}
