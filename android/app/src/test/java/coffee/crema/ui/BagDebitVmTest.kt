package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.beans.BagEmptyPrompt
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.newBean
import coffee.crema.core.ShotBean
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.visualizer.storedShotFromBackupJson
import coffee.crema.visualizer.wireShotJson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What a shot takes from its bag and what moving it gives back (#303), and the
 * prompt after a debit empties a bag (#302), through the real view model and
 * the host build of the core.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BagDebitVmTest {
    private val store = ViewModelStore()

    @After fun tearDown() { store.clear(); VmHarness.idle() }

    private fun bag(id: String, remaining: Float, size: Float, rating: Int = 0) =
        newBean(id, null, null, null, 0L).copy(id = id, remaining = remaining, bagSize = size, rating = rating.toUByte())

    private fun row(id: String, beanId: String, bagDebit: Float?, doseG: Float = 18f) = StoredShot(
        id = id,
        completedAtMs = 1_700_000_000_000,
        durationMs = 28_000,
        doseG = doseG,
        bagDebitG = bagDebit,
        brewMethod = "espresso",
        bean = ShotBean(beanId = beanId, name = beanId),
    )

    private fun vmWith(beans: List<coffee.crema.core.Bean>, shots: List<StoredShot> = emptyList()): MainViewModel {
        val dir = VmHarness.app().filesDir
        val library = BeanLibrary(beans, emptyList(), beans.firstOrNull()?.id)
        File(dir, "beanLibrary.json").writeText(coreJson.encodeToString(BeanLibrary.serializer(), library))
        runBlocking { HistoryStore(VmHarness.app(), coreJson).save(shots) }
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        return vm
    }

    private fun MainViewModel.remaining(id: String) = VmHarness.ownState(this).beans.single { it.id == id }.remaining

    private fun MainViewModel.shot(id: String) = VmHarness.ownState(this).history.single { it.id == id }

    private fun MainViewModel.logBrew(beanId: String, doseG: Float): StoredShot = addManualBrew(
        LibraryController.ManualBrewInput(
            method = "espresso", completedAtMs = 1_700_000_000_000, beanId = beanId, doseG = doseG, waterG = null,
            yieldG = 36f, grindSetting = null, brewTempC = null, durationMs = 28_000, rating = null, notes = null, nextPlan = null,
        ),
    )

    @Test
    fun `a brew records the dose it took`() {
        val vm = vmWith(listOf(bag("a", 100f, 250f)))
        assertEquals(18f, vm.logBrew("a", 18f).bagDebitG)
    }

    @Test
    fun `a brew that runs the bag out records what was left`() {
        val vm = vmWith(listOf(bag("a", 10f, 250f)))
        assertEquals(10f, vm.logBrew("a", 18f).bagDebitG)
        assertEquals(0f, vm.remaining("a"))
    }

    @Test
    fun `a brew on an empty bag records nothing taken`() {
        val vm = vmWith(listOf(bag("a", 0f, 250f)))
        assertEquals(0f, vm.logBrew("a", 18f).bagDebitG)
    }

    @Test
    fun `moving a shot credits only what it took`() {
        val vm = vmWith(listOf(bag("a", 100f, 250f)), listOf(row("s", "a", 10f)))
        vm.setShotBean("s", null)
        assertEquals(110f, vm.remaining("a"))
    }

    @Test
    fun `an untracked bag gains nothing`() {
        val vm = vmWith(listOf(bag("a", 0f, 0f)), listOf(row("s", "a", 18f)))
        vm.setShotBean("s", null)
        assertEquals(0f, vm.remaining("a"))
    }

    @Test
    fun `moving five old shots off an empty bag adds nothing`() {
        val shots = (1..5).map { row("s$it", "a", bagDebit = null) }
        val vm = vmWith(listOf(bag("a", 0f, 250f)), shots)
        shots.forEach { vm.setShotBean(it.id, null) }
        assertEquals(0f, vm.remaining("a"))
    }

    @Test
    fun `an old shot still credits its dose while the bag holds grams`() {
        val vm = vmWith(listOf(bag("a", 100f, 250f)), listOf(row("s", "a", bagDebit = null)))
        vm.setShotBean("s", null)
        assertEquals(118f, vm.remaining("a"))
    }

    @Test
    fun `a move debits the new bag and records it`() {
        val vm = vmWith(listOf(bag("a", 100f, 250f), bag("b", 10f, 250f)), listOf(row("s", "a", 18f)))
        vm.setShotBean("s", "b")
        assertEquals(118f, vm.remaining("a"))
        assertEquals(0f, vm.remaining("b"))
        assertEquals(10f, vm.shot("s").bagDebitG)
    }

    @Test
    fun `a move to no bag clears the record`() {
        val vm = vmWith(listOf(bag("a", 100f, 250f)), listOf(row("s", "a", 18f)))
        vm.setShotBean("s", null)
        assertNull(vm.shot("s").bagDebitG)
    }

    @Test
    fun `a dose edit gives back no more than the row took`() {
        val vm = vmWith(listOf(bag("a", 0f, 250f)), listOf(row("s", "a", 10f)))
        vm.updateManualBrew("s", doseG = 5f)
        assertEquals(5f, vm.remaining("a"))
        assertEquals(5f, vm.shot("s").bagDebitG)
    }

    @Test
    fun `bagDebit survives a backup`() {
        val wire = wireShotJson(row("s", "a", 10.5f), forBackup = true)
        assertEquals(10.5f, storedShotFromBackupJson(wire, coreJson)?.bagDebitG)
    }

    @Test
    fun `an old row stays without bagDebit through a backup`() {
        val wire = wireShotJson(row("s", "a", bagDebit = null), forBackup = true)
        assertFalse("bagDebit" in wire)
    }

    @Test
    fun `bagDebit stays out of an upload`() {
        assertFalse("bagDebit" in wireShotJson(row("s", "a", 10.5f)))
    }

    @Test
    fun `emptying a bag asks what to do with it`() {
        val vm = vmWith(listOf(bag("a", 10f, 250f, rating = 4)))
        vm.logBrew("a", 18f)
        assertEquals(BagEmptyPrompt("a", "a", unrated = false), VmHarness.ownState(vm).bagEmptyPrompt)
    }

    @Test
    fun `a bag that stays above 0 asks nothing`() {
        val vm = vmWith(listOf(bag("a", 100f, 250f)))
        vm.logBrew("a", 18f)
        assertNull(VmHarness.ownState(vm).bagEmptyPrompt)
    }

    @Test
    fun `archiving drops the bag as active`() {
        val vm = vmWith(listOf(bag("a", 10f, 250f, rating = 4)))
        vm.logBrew("a", 18f)
        vm.answerBagEmpty(archive = true)
        VmHarness.idle()
        val own = VmHarness.ownState(vm)
        assertNotNull(own.beans.single().archivedAt)
        assertNull(own.activeBeanId)
        assertNull(own.bagEmptyPrompt)
    }

    @Test
    fun `keeping leaves the bag active`() {
        val vm = vmWith(listOf(bag("a", 10f, 250f, rating = 4)))
        vm.logBrew("a", 18f)
        vm.answerBagEmpty(archive = false)
        VmHarness.idle()
        val own = VmHarness.ownState(vm)
        assertNull(own.beans.single().archivedAt)
        assertEquals("a", own.activeBeanId)
    }

    @Test
    fun `an unrated bag goes on to the rating question`() {
        val vm = vmWith(listOf(bag("a", 10f, 250f)))
        vm.logBrew("a", 18f)
        vm.answerBagEmpty(archive = false)
        assertEquals(true, VmHarness.ownState(vm).bagEmptyPrompt?.askingRating)
        vm.dismissBagEmptyPrompt()
        assertNull(VmHarness.ownState(vm).bagEmptyPrompt)
    }
}
