package coffee.crema.persist

import android.content.ContextWrapper
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.LibraryStore
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.profiles.CustomProfileStore
import coffee.crema.profiles.PinnedProfileStore
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.SettingsStore
import coffee.crema.ui.coreJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * A save that's been asked for lands: a screen going away can't drop the one
 * queued behind a save in progress, or the file stays behind what the user saw.
 * Also: a store's encoding doesn't run on the caller's thread.
 */
class SaveCancellationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context by lazy {
        object : ContextWrapper(null) {
            override fun getFilesDir(): File = tmp.root
        }
    }

    @Before fun open() = SafeFile.openAllForTest()

    @After fun reset() = SafeFile.resetForTest()

    /** Hold [first] in the middle of its write, queue [second] behind it, cancel
     *  the scope both belong to, then let the write finish. */
    private fun cancelledBehind(file: String, first: suspend () -> Unit, second: suspend () -> Unit) = runBlocking {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        var held = false
        SafeFile.beforeRename = { temp ->
            if (temp.name == "$file.tmp" && !held) {
                held = true
                writing.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val one = scope.launch { first() }
        assertTrue(writing.await(10, TimeUnit.SECONDS), "the first save never reached its write")
        val two = scope.launch(start = CoroutineStart.UNDISPATCHED) { second() }
        Thread.sleep(50) // let it reach the queue on another thread, where it has one
        scope.cancel()
        release.countDown()
        listOf(one, two).joinAll()
        // A cancelled job can finish before its NonCancellable work: wait for that.
        repeat(200) {
            if (done(file)) return@runBlocking
            Thread.sleep(10)
        }
    }

    private var marker = "second"

    private fun done(file: String) = marker in File(tmp.root, file).readText()

    private fun assertLanded(file: String) = assertTrue(done(file), File(tmp.root, file).readText())

    @Test
    fun aLibrarySaveSurvivesItsScope() {
        val store = LibraryStore(context, coreJson)
        cancelledBehind(
            "beanLibrary.json",
            { store.save(BeanLibrary(activeBeanId = "first")) },
            { store.save(BeanLibrary(activeBeanId = "second")) },
        )
        assertLanded("beanLibrary.json")
    }

    @Test
    fun aHistorySaveSurvivesItsScope() {
        val store = HistoryStore(context, coreJson)
        cancelledBehind(
            "shots.json",
            { store.save(listOf(StoredShot("first", 1, 1))) },
            { store.save(listOf(StoredShot("second", 1, 1))) },
        )
        assertLanded("shots.json")
    }

    @Test
    fun aPinnedSaveSurvivesItsScope() {
        val store = PinnedProfileStore(context, coreJson)
        cancelledBehind(
            "pinnedProfiles.json",
            { store.save(setOf("first")) },
            { store.save(setOf("second")) },
        )
        assertLanded("pinnedProfiles.json")
    }

    @Test
    fun aCustomProfileSaveSurvivesItsScope() {
        val store = CustomProfileStore(context, coreJson)
        cancelledBehind(
            "customProfiles.json",
            { store.save(listOf("""{"id":"first"}""")) },
            { store.save(listOf("""{"id":"second"}""")) },
        )
        assertLanded("customProfiles.json")
    }

    @Test
    fun aSettingsSaveSurvivesItsScope() {
        val store = SettingsStore(context, coreJson)
        cancelledBehind(
            "prefs.json",
            { store.save(AppPrefs(baristaName = "first")) },
            { store.save(AppPrefs(baristaName = "second")) },
        )
        assertLanded("prefs.json")
    }

    @Test
    fun encodingDoesNotRunOnTheCaller() = runBlocking {
        val caller = Thread.currentThread()
        var encodedOn: Thread? = null
        saveStore(File(tmp.root, "a.json"), "a") {
            encodedOn = Thread.currentThread()
            "{}"
        }
        assertTrue(encodedOn != caller)
    }

    @Test
    fun anEncodingThatThrowsIsAFailedSaveNotACrash() = runBlocking {
        val saved = saveStore(File(tmp.root, "a.json"), "a") { throw IllegalStateException("no") }
        assertTrue(!saved)
    }

    @Test
    fun aSaveRefusedOnEntryNeverEncodes() = runBlocking {
        SafeFile.resetForTest()
        SaveFloor.throwOnRefusal = false
        var encoded = false
        val saved = saveStore(File(tmp.root, "a.json"), "a") {
            encoded = true
            "{}"
        }
        SaveFloor.resetForTest()
        SaveFloor.drain()
        assertTrue(!saved && !encoded)
    }
}
