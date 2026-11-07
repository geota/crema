package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.profiles.CremaProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Custom profiles through a restore on the real path (#321): Replace on the
 * device that made the backup used to delete every one of them, because it
 * compared the backup against the customs it had just cleared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RestoreCustomProfilesTest {

    private var store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    private fun build(): MainViewModel {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        return vm
    }

    /** Add a custom profile, on disk once this returns; its id. */
    private fun addCustom(vm: MainViewModel, id: String): String {
        val full = Json(coreJson) { encodeDefaults = true }
        val base = vm.ui.value.profiles.first { it.source != "custom" }
        val raw = full.encodeToString(CremaProfile.serializer(), base.copy(id = id, name = id, source = "custom"))
        runBlocking { VmHarness.library(vm).adoptCustomProfile(raw) }
        return id
    }

    private fun customs(vm: MainViewModel) = vm.ui.value.profiles.filter { it.source == "custom" }

    private fun customIds(vm: MainViewModel) = customs(vm).map { it.id }.sorted()

    private fun backup(vm: MainViewModel) = assertNotNull(runBlocking { vm.buildBackupZipBytes() })

    /** The newest "Restored …" notice. */
    private fun report(vm: MainViewModel) = vm.ui.value.userMessages.last { it.startsWith("Restored ") }

    private fun customsOnDisk() = File(VmHarness.app().filesDir, "customProfiles.json").readText()

    private fun jsonlOf(zip: ByteArray): String = ZipInputStream(ByteArrayInputStream(zip)).use { z ->
        generateSequence { z.nextEntry }.first { it.name.endsWith(".jsonl") }
        z.readBytes().decodeToString()
    }

    @Test
    fun `Replace brings back your own custom profiles and counts them`() {
        val vm = build()
        val mine = listOf(addCustom(vm, "first"), addCustom(vm, "second")).sorted()
        val backup = backup(vm)
        // Not in the backup: Replace drops it, and its leaving the file shows
        // the restore's save has landed.
        val later = addCustom(vm, "later")

        VmHarness.restore(vm, backup, MainViewModel.RestoreMode.WIPE)

        assertEquals(mine, customIds(vm), "the device equals the backup")
        assertTrue(later !in customIds(vm))
        assertTrue(report(vm).startsWith("Restored 2 profile(s)"), report(vm))
        VmHarness.idleUntil("the restore's save") { later !in customsOnDisk() }
        assertTrue(mine.all { it in customsOnDisk() }, customsOnDisk())

        // And they're there on the next launch.
        store.clear()
        VmHarness.idle()
        store = ViewModelStore()
        assertEquals(mine, customIds(build()))
    }

    @Test
    fun `Replace on an unchanged device loses nothing`() {
        val vm = build()
        val mine = listOf(addCustom(vm, "first"), addCustom(vm, "second")).sorted()

        VmHarness.restore(vm, backup(vm), MainViewModel.RestoreMode.WIPE)

        assertEquals(mine, customIds(vm))
        assertTrue(report(vm).startsWith("Restored 2 profile(s)"), report(vm))
    }

    /** Only the customs stop counting as known: a built-in id stays taken. */
    @Test
    fun `Replace still keeps a backup line off a built-in id`() {
        val vm = build()
        val builtin = vm.ui.value.profiles.first { it.source != "custom" }
        val kept = addCustom(vm, "kept")
        val clash = addCustom(vm, "clash")
        val text = jsonlOf(backup(vm)).replace(clash, builtin.id)
        assertFalse(clash in text)
        val before = vm.ui.value.userMessages.size

        vm.restoreBackupFromText(text, MainViewModel.RestoreMode.WIPE)
        VmHarness.idleUntil("the restore") { vm.ui.value.userMessages.size > before }

        assertEquals(listOf(kept), customIds(vm))
        assertEquals(1, vm.ui.value.profiles.count { it.id == builtin.id })
        assertTrue(report(vm).startsWith("Restored 1 profile(s)"), report(vm))
    }

    /** The control: Merge still adds only the ids you don't have. */
    @Test
    fun `Merge adds what's missing, keeps the rest, and counts only what it added`() {
        val vm = build()
        val first = addCustom(vm, "first")
        val second = addCustom(vm, "second")
        val backup = backup(vm)
        vm.deleteProfile(second)
        VmHarness.idle()
        val later = addCustom(vm, "later")

        VmHarness.restore(vm, backup)

        assertEquals(listOf(first, second, later).sorted(), customIds(vm))
        assertTrue(report(vm).startsWith("Restored 1 profile(s)"), report(vm))

        // Again: nothing left to add, and no duplicates.
        VmHarness.restore(vm, backup)

        assertEquals(listOf(first, second, later).sorted(), customIds(vm))
        assertTrue(report(vm).startsWith("Restored 0 profile(s)"), report(vm))
    }
}
