package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import coffee.crema.history.HistoryStore
import coffee.crema.history.historyCutNotice
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.Stores
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A normal save that drops a row says so (#310 P0): restore and import had
 * their notices, a brew logged on a full history had none. On the real VM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HistoryCutNoticeVmTest {
    private val stores = mutableListOf<ViewModelStore>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val shots get() = File(app.filesDir, "shots.json")
    private val cut = "History keeps the newest 300 shots, so the oldest shot was dropped"

    @Before fun reset() {
        SafeFile.resetForTest()
        Stores.resetForTest()
        SaveFloor.resetForTest()
    }

    @After fun tearDown() {
        stores.forEach { it.clear() }
        VmHarness.idle()
        SafeFile.resetForTest()
        Stores.resetForTest()
    }

    /** A full history on disk, newest first; [pinOldest] pins the last row. */
    private fun full(pinOldest: Boolean = false) {
        val rows = (0 until HistoryStore.MAX_SHOTS).joinToString(",") { i ->
            val pin = if (pinOldest && i == HistoryStore.MAX_SHOTS - 1) ""","pinned":true""" else ""
            """{"id":"r$i","completedAtMs":${1_700_000_000_000L - i * 60_000},"durationMs":1000,"brewMethod":"pourover"$pin}"""
        }
        shots.writeText("[$rows]")
    }

    private fun vm(): MainViewModel =
        VmHarness.build(ViewModelStore().also(stores::add)).also { VmHarness.idle(); VmHarness.awaitStartupLoaded(it) }

    private fun MainViewModel.logBrew() = addManualBrew(
        LibraryController.ManualBrewInput(
            method = "pourover", completedAtMs = 1_800_000_000_000, beanId = null, doseG = 15f, waterG = 250f,
            yieldG = null, grindSetting = null, brewTempC = null, durationMs = 180_000, rating = null, notes = null, nextPlan = null,
        ),
    )

    @Test
    fun aBrewLoggedOnAFullHistoryNamesTheRowItDropped() {
        full()
        val vm = vm()
        vm.logBrew()
        VmHarness.idle()
        assertTrue(cut in vm.ui.value.userMessages, "${vm.ui.value.userMessages}")
        assertEquals(HistoryStore.MAX_SHOTS, vm.ui.value.history.size)
    }

    @Test
    fun aPinnedRowLeavesRoomSoNothingIsDroppedOrSaid() {
        full(pinOldest = true)
        val vm = vm()
        vm.logBrew()
        VmHarness.idle()
        assertTrue(vm.ui.value.userMessages.none { it.startsWith("History keeps") }, "${vm.ui.value.userMessages}")
        assertEquals(HistoryStore.MAX_SHOTS + 1, vm.ui.value.history.size)
    }

    @Test
    fun theWording() {
        assertEquals("History keeps the newest 300 shots, so the oldest 2 were dropped", historyCutNotice(2, newKept = true))
        assertEquals(
            "History keeps the newest 300 shots, and this one is older than all of them, so it wasn’t kept",
            historyCutNotice(0, newKept = false),
        )
        assertNull(historyCutNotice(0, newKept = true))
    }
}
