package coffee.crema.ui

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Construct-and-clear smoke test for MainViewModel (cohort review
 * 2026-10-25, Android P3-2). The July 2026 launch crash was an NPE inside
 * `MainViewModel.<init>`: a property declared below an `init` block was
 * still null while that block ran. No test built the VM, so nothing caught
 * it. This one builds it the way the activity does (through a
 * ViewModelStore), drains the main looper so the init coroutines run, then
 * clears it, which also runs the sleep-on-quit teardown path.
 *
 * Runs against the host build of the Rust core (see cargoBuildHostCore in
 * app/build.gradle.kts). A plain Application keeps CremaApplication's
 * crash-reporter hooks out of the test JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MainViewModelSmokeTest {

    private fun build(store: ViewModelStore): MainViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[MainViewModel::class.java]
    }

    @Test
    fun constructsCollectsAndClears() {
        val store = ViewModelStore()
        val vm = build(store)
        shadowOf(Looper.getMainLooper()).idle()

        // The published state is live, and nothing is connected yet.
        val ui = vm.ui.value
        assertTrue(ui.profiles.isNotEmpty(), "built-in profiles load during construction")
        assertEquals(false, ui.saverVisible)

        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aSecondViewModelInTheSameProcessAlsoConstructsAndClears() {
        // Activity finish + relaunch without process death: the process-wide
        // singletons (QuitSleep, the recorder, ProcessScope) must tolerate a
        // second generation.
        repeat(2) {
            val store = ViewModelStore()
            build(store)
            shadowOf(Looper.getMainLooper()).idle()
            store.clear()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
