package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import coffee.crema.ble.BleSessionRecorder
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.StoreHealth
import coffee.crema.persist.Stores
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BLE session captures are pruned only once the history says which shots are
 * pinned (#311): not after a damaged load, whose unread rows may be pinned, and
 * not on a connect before the rows have loaded. On the real ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BleCaptureHistoryGateTest {
    private val stores = mutableListOf<ViewModelStore>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val shots get() = File(app.filesDir, "shots.json")
    private val captures get() = File(app.getExternalFilesDir(null), "captures")

    private val pinnedAt = 1_700_000_000_000L

    @Before fun reset() {
        SafeFile.resetForTest()
        Stores.resetForTest()
        SaveFloor.resetForTest()
        captures.deleteRecursively()
        captures.mkdirs()
    }

    @After fun tearDown() {
        stores.forEach { it.clear() }
        VmHarness.idle()
        SafeFile.resetForTest()
        Stores.resetForTest()
        captures.deleteRecursively()
    }

    /** A session file that started at [startMs] and was last written at [endMs]. */
    private fun session(startMs: Long, endMs: Long): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startMs))
        return File(captures, "session-$stamp.jsonl").apply { writeText("{}"); setLastModified(endMs) }
    }

    /** One session holding the pinned shot, and two later ones holding none. */
    private fun sessions(): Triple<File, File, File> = Triple(
        session(pinnedAt - 60_000, pinnedAt + 60_000),
        session(pinnedAt + 3_600_000, pinnedAt + 3_660_000),
        session(pinnedAt + 7_200_000, pinnedAt + 7_260_000),
    )

    private fun pinnedRow() =
        """[{"id":"pinned","completedAtMs":$pinnedAt,"durationMs":1000,"pinned":true}]"""

    private fun build(): MainViewModel = VmHarness.build(ViewModelStore().also(stores::add))

    private fun loaded(): MainViewModel = build().also { VmHarness.idle(); VmHarness.awaitStartupLoaded(it) }

    @Test
    fun aHealthyLoadPrunesAndSparesThePinnedShotsSession() {
        shots.writeText(pinnedRow())
        val (pinned, a, b) = sessions()
        val vm = loaded()
        assertEquals(StoreHealth.OK, Stores.health(shots))
        vm.setBleSessionsKeep(BleSessionRecorder.SESSIONS_OFF)
        assertTrue(pinned.exists(), "the pinned shot's session stays")
        assertFalse(a.exists() || b.exists(), "the others go")
    }

    @Test
    fun aDamagedLoadPrunesNothing() {
        shots.writeText("[not a shot list")
        val (pinned, a, b) = sessions()
        val vm = loaded()
        assertEquals(StoreHealth.DAMAGED, Stores.health(shots))
        vm.setBleSessionsKeep(BleSessionRecorder.SESSIONS_OFF)
        assertTrue(pinned.exists() && a.exists() && b.exists(), "an unread row may be a pinned one")
    }

    @Test
    fun aConnectBeforeTheRowsLoadPrunesNothing() {
        shots.writeText(pinnedRow())
        val (pinned, a, b) = sessions()
        // Before the main looper runs the startup load: the history is empty.
        val vm = build()
        assertTrue(vm.ui.value.history.isEmpty())
        vm.setBleSessionsKeep(BleSessionRecorder.SESSIONS_OFF)
        assertTrue(pinned.exists() && a.exists() && b.exists(), "no pinned shots are known yet")

        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        vm.setBleSessionsKeep(BleSessionRecorder.SESSIONS_OFF)
        assertTrue(pinned.exists(), "once loaded, the pinned shot's session stays")
        assertFalse(a.exists() || b.exists())
    }
}
