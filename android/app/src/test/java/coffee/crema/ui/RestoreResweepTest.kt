package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.HotWaterMode
import coffee.crema.core.HotWaterTarget
import coffee.crema.core.ProfileSyncStatus
import coffee.crema.settings.AppPrefs
import coffee.crema.ui.RemoteFixture.Companion.writePrefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A restore while the DE1 is down (hot-water modes spec §4 writers, R6,
 * AC-HW25): the restored dose is pushed, and the machine settings are re-sent
 * with `reapply_machine_settings`, which never settles the link — the real
 * reconnect's sweep still uploads the profile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RestoreResweepTest {

    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    @Test
    fun `a restore while the DE1 is down pushes the dose and doesn't settle the link`() {
        writePrefs(AppPrefs(idleSleepMin = 30))
        val vm = VmHarness.build(store)
        VmHarness.awaitStartupLoaded(vm)
        val volume = HotWaterUi.defaults.copy(mode = HotWaterMode.Volume, volumeMl = 220f)
        vm.setHotWaterDose(volume)
        VmHarness.idle()
        val backup = assertNotNull(runBlocking { vm.buildBackupZipBytes() })
        vm.setHotWaterDose(HotWaterUi.defaults)
        VmHarness.idle()

        VmHarness.restore(vm, backup)
        VmHarness.idle()
        assertEquals(volume, VmHarness.ownState(vm).hotWaterDose(), "restored")
        val bridge = VmHarness.bridge(vm)
        val target = coreJson.decodeFromString(HotWaterTarget.serializer(), bridge.hotWaterTargetJson())
        assertEquals(HotWaterMode.Volume to 220f, target.mode to target.volumeMl, "pushed to the core")
        // A settled link would have started the desired profile's upload.
        val sync = coreJson.decodeFromString(ProfileSyncStatus.serializer(), bridge.profileSyncStatus())
        assertNotNull(sync.desired, "a desired profile to upload once settled")
        assertFalse(sync.uploading, "no upload: the link isn't settled")
        assertNull(sync.lastPushed)
    }
}
