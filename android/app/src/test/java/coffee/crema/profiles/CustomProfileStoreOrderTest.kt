package coffee.crema.profiles

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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Custom-profile saves are serialized and numbered like the prefs saves (the
 * P2b validation's P3): an earlier fire-and-forget save that lands late never
 * overwrites the T6 adoption's awaited save, so the adopted hub profile the
 * saved `activeProfileId` names stays on disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CustomProfileStoreOrderTest {

    private val store = CustomProfileStore(VmHarness.app(), coreJson)
    private val mine = """{"id":"mine","name":"Mine"}"""
    private val hubs = """{"id":"custom-on-the-hub","name":"Hub custom"}"""

    private fun ids(saved: List<String>) = saved.map { coreJson.parseToJsonElement(it).jsonObject.getValue("id").jsonPrimitive.content }

    @Test
    fun `an older snapshot after a newer one is skipped`() = runBlocking {
        store.save(listOf(hubs, mine), generation = 2) // the adoption
        store.save(listOf(mine), generation = 1) // an earlier edit, late
        assertEquals(listOf("custom-on-the-hub", "mine"), ids(store.load()))
    }

    /** The race itself: the adoption save takes the lock first; the earlier
     *  fire-and-forget save reaches it after. */
    @Test
    fun `an earlier save that lands late doesn't overwrite the adoption save`() = runBlocking {
        val lock = CustomProfileStore::class.java.getDeclaredField("saveLock").apply { isAccessible = true }.get(store) as Mutex
        val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            lock.lock()
            val adoption = io.async { store.save(listOf(hubs, mine), generation = 2) }
            delay(50) // the adoption save is queued on the lock
            val earlier = io.async { store.save(listOf(mine), generation = 1) }
            delay(50)
            lock.unlock()
            adoption.await()
            earlier.await()
            assertEquals(listOf("custom-on-the-hub", "mine"), ids(store.load()), "the adopted profile stays")
        } finally {
            io.cancel()
        }
    }

    /** One save at a time: a save waits while another holds the store. */
    @Test
    fun `a save waits for the one before it`() = runBlocking {
        val lock = CustomProfileStore::class.java.getDeclaredField("saveLock").apply { isAccessible = true }.get(store) as Mutex
        val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            lock.lock()
            val save = io.async { store.save(listOf(mine), generation = 1) }
            delay(100)
            assertEquals(false, save.isCompleted, "it waited")
            lock.unlock()
            save.await()
            assertEquals(listOf("mine"), ids(store.load()))
        } finally {
            io.cancel()
        }
    }

    @Test
    fun `saves without a number always land`() = runBlocking {
        store.save(listOf(hubs), generation = 5)
        store.save(listOf(mine))
        assertEquals(listOf("mine"), ids(store.load()))
    }
}
