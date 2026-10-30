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
import java.io.File
import kotlin.test.assertTrue

/**
 * The library numbers its custom-profile saves (the P2b validation's P3), so
 * the store can skip a fire-and-forget save that lands after the T6
 * adoption's awaited one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CustomProfileSaveOrderTest {

    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    /** The store's last saved number, by reflection. */
    private fun lastSaved(library: LibraryController): Long {
        val store = LibraryController::class.java.getDeclaredField("customProfileStore").apply { isAccessible = true }.get(library)
        return store.javaClass.getDeclaredField("lastGeneration").apply { isAccessible = true }.getLong(store)
    }

    /** Every library save is numbered, rising: the fire-and-forget ones and
     *  the T6 adoption's awaited one. With the store's skip (pinned by
     *  CustomProfileStoreOrderTest), an earlier save that lands late is dropped. */
    @Test
    fun `the library numbers every custom-profile save`() {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        // The whole startup load, not just the prefs: its custom-profile load
        // replaces the library's list, and would drop a profile adopted before it.
        VmHarness.awaitStartupLoaded(vm)
        val library = MainViewModel::class.java.getDeclaredField("library").apply { isAccessible = true }.get(vm) as LibraryController
        val full = Json(coreJson) { encodeDefaults = true }
        val base = vm.ui.value.profiles.first()
        fun custom(id: String) = full.encodeToString(CremaProfile.serializer(), base.copy(id = id, name = id, source = "custom"))
        runBlocking { library.adoptCustomProfile(custom("mine")) }
        val afterAdopt = lastSaved(library)
        assertTrue(afterAdopt > Long.MIN_VALUE, "the adoption save is numbered")
        library.togglePinProfile("mine")
        VmHarness.idleUntil("the pin saved") { lastSaved(library) > afterAdopt }
        val afterPin = lastSaved(library)
        runBlocking { library.adoptCustomProfile(custom("custom-on-the-hub")) }
        assertTrue(lastSaved(library) > afterPin, "numbers rise")
        val onDisk = File(VmHarness.app().filesDir, "customProfiles.json").readText()
        assertTrue("custom-on-the-hub" in onDisk && "\"mine\"" in onDisk, onDisk)
    }
}
