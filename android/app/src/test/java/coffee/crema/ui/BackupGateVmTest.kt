package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.Stores
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A store that couldn't be read stops the daily backup and dates the damage
 * for Drive's retention; a manual backup still goes, and says what it's missing
 * (restore-safety design, 6a, PR8c).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BackupGateVmTest {
    private val stores = mutableListOf<ViewModelStore>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val library get() = File(app.filesDir, "beanLibrary.json")
    private val profiles get() = File(app.filesDir, "customProfiles.json")

    @Before fun reset() {
        SafeFile.resetForTest()
        Stores.resetForTest()
        SaveFloor.resetForTest()
    }

    @After fun tearDown() {
        library.setReadable(true)
        app.filesDir.listFiles { f -> ".corrupt-" in f.name }?.forEach { it.delete() }
        app.filesDir.listFiles { f -> f.name.startsWith("drive.json") }?.forEach { it.delete() }
        stores.forEach { it.clear() }
        VmHarness.idle()
        SafeFile.resetForTest()
        Stores.resetForTest()
    }

    private fun vm(): MainViewModel {
        val s = ViewModelStore().also(stores::add)
        return VmHarness.build(s).also { VmHarness.idle(); VmHarness.awaitStartupLoaded(it) }
    }

    @Test
    fun aHealthyAppBacksUp() {
        val vm = vm()
        assertEquals(null, vm.autoBackupBlocked())
        assertEquals(emptyMap(), vm.damageByStore())
    }

    @Test
    fun aRestoreRunningStopsTheDailyBackupUntilItEnds() {
        val vm = vm()
        val bundle = listOf(
            """{"kind":"crema-backup/v1","createdAt":1,"beanCount":0}""",
            """{"kind":"settings","_shell":"android","common":{"weightUnit":"oz"}}""",
        ).joinToString("\n")
        vm.restoreBackupFromText(bundle, MainViewModel.RestoreMode.WIPE)
        assertEquals("a restore is running", vm.autoBackupBlocked())
        VmHarness.idleUntil("the restore to end") { vm.ui.value.userMessages.any { it.startsWith("Restored ") } }
        VmHarness.idle()
        assertEquals(null, vm.autoBackupBlocked())
    }

    @Test
    fun aCompletedRestoreReleasesDrivesPreDamagePin() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        runBlocking { coffee.crema.drive.DriveStore(app, json).let { it.load(); it.save(coffee.crema.drive.DriveState(preDamageCopy = "p")) } }
        val vm = vm()
        val bundle = listOf(
            """{"kind":"crema-backup/v1","createdAt":1,"beanCount":0}""",
            """{"kind":"settings","_shell":"android","common":{"weightUnit":"oz"}}""",
        ).joinToString("\n")
        vm.restoreBackupFromText(bundle, MainViewModel.RestoreMode.MERGE)
        VmHarness.idleUntil("the pin to be released") {
            runBlocking { coffee.crema.drive.DriveStore(app, json).load().pinReleased }
        }
    }

    @Test
    fun anUnreadableStoreStopsTheDailyBackup() {
        library.writeText("""{"beans":[],"roasters":[]}""")
        library.setReadable(false)
        val vm = vm()
        assertEquals("your bean library couldn't be read", vm.autoBackupBlocked())
    }

    @Test
    fun aDamagedStoreStopsItAndDatesTheDamageUntilTheCopyGoes() {
        library.writeText("not json")
        val before = System.currentTimeMillis()
        val vm = vm()
        assertEquals("your bean library couldn't be read", vm.autoBackupBlocked())
        val since = assertNotNull(vm.damageByStore()["beanLibrary.json"])
        assertTrue(since >= before, "$since < $before")

        // The next launch reads the store fine, but the kept copy still dates it.
        stores.forEach { it.clear() }
        VmHarness.idle()
        SafeFile.resetForTest()
        Stores.resetForTest()
        val next = vm()
        assertEquals(null, next.autoBackupBlocked())
        assertEquals(since, next.damageByStore()["beanLibrary.json"])
        SafeFile.keptAside(library).forEach { it.delete() }
        assertEquals(emptyMap(), next.damageByStore())
    }

    @Test
    fun theNewestDamageAcrossTheStoresCountsSettingsToo() {
        File(app.filesDir, "customProfiles.json.corrupt-1000").writeText("x")
        File(app.filesDir, "beanLibrary.json.corrupt-kept-2000").writeText("x")
        assertEquals(mapOf("customProfiles.json" to 1000L, "beanLibrary.json" to 2000L), vm().damageByStore())
        // A store's newest kept copy counts, not its oldest.
        File(app.filesDir, "prefs.json.corrupt-500").writeText("x")
        File(app.filesDir, "prefs.json.corrupt-3000").writeText("x")
        File(app.filesDir, "maintenance.json.corrupt-2500").writeText("x")
        assertEquals(3000L, vm().damageByStore()["prefs.json"])
        assertEquals(2500L, vm().damageByStore()["maintenance.json"])
        assertTrue(profiles.parentFile!!.listFiles()!!.any { it.name.endsWith("corrupt-1000") })
    }

    @Test
    fun aManualBackupGoesAheadAndSaysWhatIsMissing() {
        library.writeText("not json")
        val vm = vm()
        val bytes = runBlocking { vm.buildBackupZipBytes() }
        VmHarness.idle()
        // An empty library on top of the defaults may still be nothing to back up;
        // either way the user hears what the backup can't hold.
        assertTrue(
            "This backup doesn't include your bean library: it couldn't be read." in vm.ui.value.userMessages,
            "${vm.ui.value.userMessages} (built: ${bytes != null})",
        )
    }
}
