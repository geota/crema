package coffee.crema.settings

import android.app.Application
import coffee.crema.ui.VmHarness
import coffee.crema.ui.coreJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * AC-24's ordering clause, at the store (spec §7.5 step 5): saves are
 * numbered, and an older snapshot never lands after a newer one, whichever
 * reaches the disk first. A `persistPrefs` launched just before the T6 commit
 * therefore can't overwrite the adoption save.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SettingsStoreOrderTest {

    private val store = SettingsStore(VmHarness.app(), coreJson)

    @Test
    fun `an older snapshot after a newer one is skipped`() = runBlocking {
        store.save(AppPrefs(themeMode = "dark"), generation = 2)
        store.save(AppPrefs(themeMode = "light"), generation = 1)
        assertEquals("dark", store.load().themeMode)
    }

    /** The race itself: the newer save takes the lock first, the older one
     *  lands after it. */
    @Test
    fun `an older save that reaches the lock last doesn't land`() = runBlocking {
        val lock = SettingsStore::class.java.getDeclaredField("saveLock").apply { isAccessible = true }.get(store) as Mutex
        val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            lock.lock()
            val newer = io.async { store.save(AppPrefs(themeMode = "dark"), generation = 2) }
            delay(50) // the newer save is queued on the lock
            val older = io.async { store.save(AppPrefs(themeMode = "light"), generation = 1) }
            delay(50)
            lock.unlock()
            newer.await()
            older.await()
            assertEquals("dark", store.load().themeMode, "the adoption save stands")
        } finally {
            io.cancel()
        }
    }

    @Test
    fun `saves without a number always land`() = runBlocking {
        store.save(AppPrefs(themeMode = "dark"), generation = 5)
        store.save(AppPrefs(themeMode = "light"))
        assertEquals("light", store.load().themeMode)
    }
}
