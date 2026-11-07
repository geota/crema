package coffee.crema.persist

import android.content.ContextWrapper
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.LibraryStore
import coffee.crema.brew.RecipeFileStore
import coffee.crema.decent.StolenSerialsCache
import coffee.crema.decent.StolenSerialsFileStore
import coffee.crema.history.HistoryStore
import coffee.crema.maintenance.defaultMaintenanceState
import coffee.crema.maintenance.MaintenanceStore
import coffee.crema.profiles.CustomProfileStore
import coffee.crema.profiles.HiddenProfileStore
import coffee.crema.profiles.PinnedProfileStore
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.SettingsStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** No store takes a save before it has loaded: such a snapshot holds defaults. */
class SaveFloorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val context by lazy {
        object : ContextWrapper(null) {
            override fun getFilesDir(): File = tmp.root
        }
    }
    private fun file(name: String) = File(tmp.root, name)
    private val keep = """["what is on disk"]"""

    @Before fun reset() {
        SafeFile.resetForTest()
        SaveFloor.resetForTest()
        SaveFloor.throwOnRefusal = false
    }

    @After fun cleanup() {
        SafeFile.resetForTest()
        SaveFloor.resetForTest()
    }

    /** Every gated store on disk, with one save each. */
    private val stores: List<Triple<String, String, suspend () -> Unit>> by lazy {
        listOf(
            Triple("beanLibrary.json", "bean library") { LibraryStore(context, json).save(BeanLibrary()) },
            Triple("shots.json", "shot history") { HistoryStore(context, json).save(emptyList()) },
            Triple("prefs.json", "settings") { SettingsStore(context, json).save(AppPrefs()) },
            Triple("customProfiles.json", "custom profiles") { CustomProfileStore(context, json).save(emptyList()) },
            Triple("pinnedProfiles.json", "pinned profiles list") { PinnedProfileStore(context, json).save(setOf("a")) },
            Triple("hiddenProfiles.json", "hidden profiles list") { HiddenProfileStore(context, json).save(setOf("a")) },
            Triple("brew-recipes.json", "brew recipes") { RecipeFileStore(context, json).save(RecipeFileStore.Envelope()) },
            Triple("maintenance.json", "maintenance log") { MaintenanceStore(context, json).save(defaultMaintenanceState(0L)) },
            Triple("stolenSerials.json", "stolen serials list") { StolenSerialsFileStore(context).write(StolenSerialsCache(0, "")) },
        )
    }

    @Test
    fun everyStoreRefusesASaveBeforeItLoads() = runTest {
        for ((name, what, save) in stores) {
            file(name).writeText(keep)
            save()
            assertEquals(keep, file(name).readText(), name)
            assertEquals(listOf(what), SaveFloor.drain(), name)
        }
    }

    @Test
    fun aRefusedSaveIsNotQueued() = runTest {
        val (name, _, save) = stores.first()
        file(name).writeText(keep)
        save()
        SafeFile.load(file(name)) { it }
        assertEquals(keep, file(name).readText())
    }

    @Test
    fun theFirstRefusalPerPathIsTheOnlyNotice() {
        val a = file("a.json")
        val b = file("b.json")
        assertFalse(saveStore(a, "first", "1"))
        assertFalse(saveStore(a, "first", "2"))
        assertFalse(saveStore(b, "second", "1"))
        assertEquals(listOf("first", "second"), SaveFloor.drain())
        assertFalse(a.exists())
    }

    @Test
    fun aRefusalFailsATestUnderTheBuildsStrictMode() {
        SaveFloor.resetForTest()
        assertFailsWith<AssertionError> { saveStore(file("a.json"), "first", "1") }
    }

    @Test
    fun anyLoadOutcomeOpensTheStore() {
        val absent = file("absent.json")
        SafeFile.load(absent) { it }
        assertTrue(saveStore(absent, "x", "1"))

        val loaded = file("loaded.json").apply { writeText("1") }
        SafeFile.load(loaded) { it }
        assertTrue(saveStore(loaded, "x", "2"))

        val corrupt = file("corrupt.json").apply { writeText("{") }
        SafeFile.load(corrupt) { throw IllegalArgumentException("bad") }
        assertTrue(saveStore(corrupt, "x", "3"))
    }

    @Test
    fun aReadThatIsNotTheLoadLeavesItClosed() {
        val prefs = file("prefs.json").apply { writeText("1") }
        loadStore(prefs, "settings", open = false) { it }
        assertFalse(SafeFile.savesOpen(prefs))
    }

    @Test
    fun aStoreWhoseStateLandsLaterStaysClosedUntilItSaysSo() = runTest {
        val history = HistoryStore(context, json)
        val library = LibraryStore(context, json)
        val custom = CustomProfileStore(context, json)
        history.loadRows()
        library.load()
        custom.load()
        for (name in listOf("shots.json", "beanLibrary.json", "customProfiles.json")) {
            assertFalse(SafeFile.savesOpen(file(name)), name)
        }
        history.openSaves()
        library.openSaves()
        custom.openSaves()
        for (name in listOf("shots.json", "beanLibrary.json", "customProfiles.json")) {
            assertTrue(SafeFile.savesOpen(file(name)), name)
        }
    }

    @Test
    fun aNewSessionClosesEveryStore() {
        val t = file("a.json")
        SafeFile.load(t) { it }
        SafeFile.startSession()
        assertFalse(saveStore(t, "x", "1"))
    }

    @Test
    fun aCredentialIsRefusedBeforeItsStoreLoads() {
        val t = file("drive.json").apply { writeText(keep) }
        assertFailsWith<IOException> { SafeFile.writeCredential(t, "{}", "Google Drive sign-in") }
        assertEquals(keep, t.readText())
        assertEquals(listOf("Google Drive sign-in"), SaveFloor.drain())

        SafeFile.load(t) { it }
        SafeFile.writeCredential(t, "{}", "Google Drive sign-in")
        assertEquals("{}", t.readText())
    }

    @Test
    fun aStoreWithItsOwnLoadCanOptOut() {
        val t = file("relay.json")
        SafeFile.writeCredential(t, "{}", gated = false)
        assertEquals("{}", t.readText())
        assertEquals(emptyList(), SaveFloor.drain())
    }

    @Test
    fun aFileWithNoLoadIsNotGated() {
        val t = file("hot-water.json")
        assertTrue(saveItem(t, "{}"))
        assertEquals(emptyList(), SaveFloor.drain())
    }

    @Test
    fun eraseLeavesTheStoreOpen() {
        val t = file("a.json").apply { writeText("1") }
        SafeFile.load(t) { it }
        eraseStore(t)
        assertTrue(saveStore(t, "x", "2"))
    }

    @Test
    fun theMessageNamesTheStore() {
        assertEquals(
            "Crema was still loading your settings, so that change wasn't saved. Please make it again.",
            SaveFloor.message("settings"),
        )
    }
}
