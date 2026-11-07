package coffee.crema.ui

import android.app.Application
import coffee.crema.MainSources
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * geota/crema#322 end to end: the sync controllers' own state loads apart from
 * the row load, so a chain that dies early can't strand a sign-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OwnStateLoadVmTest {

    @Test
    fun `a cold start routes both services' redirects`() {
        val activity = MainSources.kotlinFiles().toMap().getValue("coffee/crema/ui/MainActivity.kt")
        val onCreate = activity.substringAfter("override fun onCreate(").substringBefore("setContent {")
        assertTrue("handleVisualizerCallback(intent)" in onCreate)
        assertTrue("handleDriveCallback(intent)" in onCreate)
    }

    @Test
    fun `failed_row_chain_still_opens_own_state`() = VmHarness.holdingStartupLoad { release ->
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Plain-text copies, as a build before sealing left them: this keystore
        // can't seal, so a store save would fail here.
        File(app.filesDir, "visualizer.json").writeText("""{"pendingVerifier":"verifier","pendingState":"csrf"}""")
        File(app.filesDir, "drive.json").writeText("""{"tokens":{"accessToken":"d","expiresAt":9000000000000}}""")

        // The row chain ends before it reaches anything after the hold.
        release.cancel()
        val vm = ViewModelProvider(
            ViewModelStore(),
            ViewModelProvider.AndroidViewModelFactory.getInstance(app),
        )[MainViewModel::class.java]

        VmHarness.idleUntil("own state") { vm.drive.state.value.connected }
        assertEquals(false, vm.startupLoad.isActive)

        // A redirect still completes.
        vm.visualizer.handleCallback(null, "csrf", "access_denied")
        VmHarness.idleUntil("the sign-in answered") {
            "Visualizer sign-in was cancelled" in VmHarness.ownState(vm).userMessages
        }
    }
}
