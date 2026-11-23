package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.newBean
import coffee.crema.beans.newRoaster
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #488 review SF1 on Android, through the real view model, its stores and the
 * host build of the core: a roaster on Visualizer deleted on this device stays
 * a tombstone, which no list, lookup or count shows, while the library file,
 * the backup and the restore keep it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RoasterTombstoneVmTest {
    @Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    private var store = ViewModelStore()

    @After fun tearDown() { store.clear(); VmHarness.idle() }

    private val app get() = VmHarness.app()

    private val sey = newRoaster("Sey", 1L).copy(id = "roaster:sey", visualizerId = "vz-r")
    private val onyx = newRoaster("Onyx", 1L).copy(id = "roaster:onyx")
    private fun bag(id: String, roasterId: String?) =
        newBean("Bag $id", roasterId, null, null, 1L).copy(id = id, updatedAt = 1L, visualizerId = "vz-$id")

    private fun writeLibrary(lib: BeanLibrary) =
        File(app.filesDir, "beanLibrary.json").writeText(coreJson.encodeToString(BeanLibrary.serializer(), lib))

    private fun vm(): MainViewModel {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(vm)
        VmHarness.awaitStartupLoaded(vm)
        VmHarness.idle()
        return vm
    }

    private fun freshDevice(): MainViewModel {
        VmHarness.idle()
        store.clear()
        VmHarness.idle()
        app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        coffee.crema.persist.SafeFile.resetForTest()
        coffee.crema.persist.Stores.resetForTest()
        coffee.crema.persist.SaveFloor.resetForTest()
        coffee.crema.persist.SafeFile.openAllForTest()
        store = ViewModelStore()
        return vm()
    }

    private fun MainViewModel.own() = VmHarness.ownState(this)

    private fun awaitLibrarySaved(what: String, done: (BeanLibrary) -> Boolean) = VmHarness.idleUntil(what) {
        runCatching { coreJson.decodeFromString(BeanLibrary.serializer(), File(app.filesDir, "beanLibrary.json").readText()) }
            .getOrNull()?.let(done) ?: false
    }

    @Test
    fun `a local-only delete of a roaster on Visualizer keeps a tombstone and its bags' link`() {
        writeLibrary(BeanLibrary(listOf(bag("a", sey.id), bag("c", onyx.id)), listOf(sey, onyx), activeBeanId = "a"))
        val vm = vm()
        vm.deleteRoaster(sey.id)
        VmHarness.idle()
        assertEquals(listOf(onyx.id), vm.own().roasters.map { it.id }, "the roaster list leaves the tombstone out")
        val tomb = vm.own().roasters.withTombstones().single { it.id == sey.id }
        assertNotNull(tomb.deletedAt)
        assertEquals("vz-r", tomb.visualizerId)
        assertEquals(sey.id, vm.own().beans.single { it.id == "a" }.roasterId, "the bag keeps its roaster")
        assertEquals(1L, vm.own().beans.single { it.id == "a" }.updatedAt, "and isn't stamped, so nothing pushes it")
        assertEquals("a", vm.own().activeBeanId)
        awaitLibrarySaved("the tombstone on disk") { lib -> lib.roasters.any { it.id == sey.id && it.deletedAt != null } }
    }

    @Test
    fun `a roaster tombstone is in no list, lookup or count, and a bag is never filed under it by name`() {
        val tomb = sey.copy(deletedAt = 5L)
        writeLibrary(BeanLibrary(listOf(bag("a", sey.id)), listOf(tomb, onyx)))
        val vm = vm()
        assertEquals(listOf(onyx.id), vm.own().roasters.map { it.id })
        assertNull(vm.own().roasters.firstOrNull { it.id == sey.id }, "no lookup by id finds it")
        assertTrue(vm.roasterDuplicates(vm.own().roasters).isEmpty())
        assertEquals(listOf(tomb, onyx), vm.own().libraryFile().roasters, "the library file keeps it")

        // A bag edit that leaves the (hidden) roaster blank keeps the link.
        vm.updateBean("a", "") { it.copy(notes = "tasty") }
        VmHarness.idle()
        assertEquals(sey.id, vm.own().beans.single().roasterId)
        // A bag filed by the tombstone's name gets a live roaster.
        vm.updateBean("a", "Sey") { it }
        VmHarness.idle()
        val live = vm.own().roasters.single { it.name == "Sey" }
        assertTrue(live.id != sey.id && live.deletedAt == null)
        assertEquals(live.id, vm.own().beans.single().roasterId)
    }

    @Test
    fun `a roaster tombstone goes into the backup and comes back with a restore`() {
        val tomb = sey.copy(deletedAt = 5L, notes = "")
        writeLibrary(BeanLibrary(listOf(bag("a", sey.id)), listOf(tomb, onyx)))
        val vm = vm()
        val backup = assertNotNull(VmHarness.backupBytes(vm))
        val restored = freshDevice()
        VmHarness.restore(restored, backup, MainViewModel.RestoreMode.WIPE)
        assertEquals(listOf(onyx.id), restored.own().roasters.map { it.id })
        assertEquals(5L, restored.own().roasters.withTombstones().single { it.id == sey.id }.deletedAt, "the tombstone came back")
        assertEquals(sey.id, restored.own().beans.single().roasterId)
    }
}
