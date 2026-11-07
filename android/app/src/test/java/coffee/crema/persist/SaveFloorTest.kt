package coffee.crema.persist

import android.content.ContextWrapper
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.LibraryStore
import coffee.crema.brew.RecipeFileStore
import coffee.crema.decent.StolenSerialsCache
import coffee.crema.decent.StolenSerialsFileStore
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.maintenance.defaultMaintenanceState
import coffee.crema.maintenance.MaintenanceStore
import coffee.crema.profiles.CustomProfileStore
import coffee.crema.profiles.HiddenProfileStore
import coffee.crema.profiles.PinnedProfileStore
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.SettingsStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    /** A save made while the store loads, then the load opening it, as Main runs them. */
    private fun saveDuringLoad(
        load: suspend () -> Unit,
        save: suspend () -> Unit,
        open: () -> Unit,
    ) = runBlocking {
        val loading = launch(start = CoroutineStart.UNDISPATCHED) { load() }
        val saving = launch(start = CoroutineStart.UNDISPATCHED) { save() }
        open()
        loading.join()
        saving.join()
    }

    @Test
    fun aSaveQueuedBehindALoadCannotLandOnceTheLoadOpensIt() {
        val library = LibraryStore(context, json)
        val raw = file("beanLibrary.json").apply { writeText("""{"activeBeanId":"bean-1"}""") }
        saveDuringLoad({ library.load() }, { library.save(BeanLibrary()) }, library::openSaves)
        assertEquals("""{"activeBeanId":"bean-1"}""", raw.readText())
        assertEquals(listOf("bean library"), SaveFloor.drain())
    }

    @Test
    fun aHistorySaveDuringItsLoadCannotLandEither() {
        repeat(20) { round ->
            val raw = file("shots.json").apply { writeText("[]") }
            val history = HistoryStore(context, json)
            SafeFile.startSession()
            saveDuringLoad({ history.loadRows() }, { history.save(listOf(StoredShot("s$round", 1, 1))) }, history::openSaves)
            assertEquals("[]", raw.readText(), "round $round")
        }
    }

    @Test
    fun aCustomProfileSaveDuringItsLoadCannotLandEither() {
        repeat(20) { round ->
            val raw = file("customProfiles.json").apply { writeText("""{"profiles":[]}""") }
            val custom = CustomProfileStore(context, json)
            SafeFile.startSession()
            saveDuringLoad({ custom.load() }, { custom.save(listOf("{}")) }, custom::openSaves)
            assertEquals("""{"profiles":[]}""", raw.readText(), "round $round")
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
        assertFalse(saveNow(a, "first", "1"))
        assertFalse(saveNow(a, "first", "2"))
        assertFalse(saveNow(b, "second", "1"))
        assertEquals(listOf("first", "second"), SaveFloor.drain())
        assertFalse(a.exists())
    }

    @Test
    fun aRefusalFailsATestUnderTheBuildsStrictMode() {
        SaveFloor.resetForTest()
        assertFailsWith<AssertionError> { saveNow(file("a.json"), "first", "1") }
    }

    @Test
    fun anyLoadOutcomeOpensTheStore() {
        val absent = file("absent.json")
        SafeFile.load(absent) { it }
        assertTrue(saveNow(absent, "x", "1"))

        val loaded = file("loaded.json").apply { writeText("1") }
        SafeFile.load(loaded) { it }
        assertTrue(saveNow(loaded, "x", "2"))

        val corrupt = file("corrupt.json").apply { writeText("{") }
        SafeFile.load(corrupt) { throw IllegalArgumentException("bad") }
        assertTrue(saveNow(corrupt, "x", "3"))
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
        assertFalse(saveNow(t, "x", "1"))
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
        assertTrue(saveNow(t, "x", "2"))
    }

    @Test
    fun aNewSessionMakesARefusalNewsAgain() {
        val t = file("a.json")
        assertFalse(saveNow(t, "first", "1"))
        assertEquals(listOf("first"), SaveFloor.drain())
        assertFalse(saveNow(t, "first", "2"))
        assertEquals(emptyList(), SaveFloor.drain())

        SafeFile.startSession()
        assertFalse(saveNow(t, "first", "3"))
        assertEquals(listOf("first"), SaveFloor.drain())
    }

    @Test
    fun onlyTheNewestSessionIsCurrent() {
        val first = SafeFile.startSession()
        assertTrue(SafeFile.isCurrentSession(first))
        val second = SafeFile.startSession()
        assertFalse(SafeFile.isCurrentSession(first))
        assertTrue(SafeFile.isCurrentSession(second))
    }

    @Test
    fun theMessageNamesTheStore() {
        assertEquals(
            "Crema was still loading your settings, so that change wasn't saved. Please make it again.",
            SaveFloor.message("settings"),
        )
    }

    @Test
    fun aShotIsNotAskedToBeMadeAgain() {
        assertEquals(
            "Crema was still loading your shot history, so the latest change to it wasn't saved.",
            SaveFloor.message("shot history"),
        )
    }
}
