package coffee.crema.persist

import android.content.ContextWrapper
import coffee.crema.brew.RecipeFileStore
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.maintenance.MaintenanceStore
import coffee.crema.profiles.HiddenProfileStore
import coffee.crema.profiles.PinnedProfileStore
import coffee.crema.settings.SawModelStore
import coffee.crema.ui.coreJson
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Two saves in a row, as a screen makes them: each store must end up holding the
 * second, not only hold a file. Each save starts on the caller's thread, as
 * `viewModelScope.launch` does on Main. Out of order this was a few rounds in a
 * hundred, so every test runs [ROUNDS].
 */
class StoreSaveOrderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context by lazy {
        object : ContextWrapper(null) {
            override fun getFilesDir(): File = tmp.root
        }
    }

    private suspend fun inARow(vararg saves: suspend () -> Unit) = coroutineScope {
        saves.forEach { save -> launch(start = CoroutineStart.UNDISPATCHED) { save() } }
    }

    private fun recipe(id: String) = coffee.crema.core.BrewRecipe(
        id = id, name = id, method = "pourover", doseG = 15f, waterG = 250f, tempC = 96f,
        steps = emptyList(), notes = null, favourite = false, createdAt = 1, updatedAt = 1,
    )

    @Test
    fun `the last recipe save wins`() = runBlocking {
        val store = RecipeFileStore(context, coreJson)
        repeat(ROUNDS) { round ->
            inARow(
                { store.save(RecipeFileStore.Envelope(listOf(recipe("old-$round")))) },
                { store.save(RecipeFileStore.Envelope(listOf(recipe("new-$round")))) },
            )
            assertEquals(listOf("new-$round"), store.load().recipes.map { it.id })
        }
    }

    @Test
    fun `an erase after two recipe saves leaves nothing`() = runBlocking {
        val store = RecipeFileStore(context, coreJson)
        repeat(ROUNDS) {
            inARow(
                { store.save(RecipeFileStore.Envelope(listOf(recipe("a")))) },
                { store.save(RecipeFileStore.Envelope(listOf(recipe("b")))) },
                { store.erase() },
            )
            assertEquals(emptyList(), store.load().recipes)
        }
    }

    @Test
    fun `the last pinned save wins`() = runBlocking {
        val store = PinnedProfileStore(context, coreJson)
        repeat(ROUNDS) { round ->
            inARow({ store.save(setOf("old-$round")) }, { store.save(setOf("new-$round")) })
            assertEquals(setOf("new-$round"), store.load())
        }
    }

    @Test
    fun `the last hidden save wins`() = runBlocking {
        val store = HiddenProfileStore(context, coreJson)
        repeat(ROUNDS) { round ->
            inARow({ store.save(setOf("old-$round")) }, { store.save(setOf("new-$round")) })
            assertEquals(setOf("new-$round"), store.load())
        }
    }

    @Test
    fun `the last maintenance save wins`() = runBlocking {
        val store = MaintenanceStore(context, coreJson)
        val state = coffee.crema.maintenance.defaultMaintenanceState(1L)
        repeat(ROUNDS) { round ->
            val old = state.copy(cleanAtMs = round.toLong())
            val new = state.copy(cleanAtMs = round + 1_000_000L)
            inARow({ store.save(old) }, { store.save(new) })
            assertEquals(new.cleanAtMs, store.load().cleanAtMs)
        }
    }

    @Test
    fun `the last SAW save wins`() = runBlocking {
        val store = SawModelStore(tmp.root)
        repeat(ROUNDS) { round ->
            inARow({ store.save("old-$round") }, { store.save("new-$round") })
            assertEquals("new-$round", store.load())
        }
    }

    private fun shot(id: String) = StoredShot(id = id, completedAtMs = 1, durationMs = 1)

    @Test
    fun `the last history save wins`() = runBlocking {
        val store = HistoryStore(context, coreJson)
        repeat(ROUNDS) { round ->
            inARow({ store.save(listOf(shot("old-$round"))) }, { store.save(listOf(shot("new-$round"))) })
            assertEquals(listOf("new-$round"), store.loadRows().map { it.id })
        }
    }

    @Test
    fun `an erase after a history save leaves nothing`() = runBlocking {
        val store = HistoryStore(context, coreJson)
        repeat(ROUNDS) {
            inARow({ store.save(listOf(shot("a"))) }, { store.erase() })
            assertFalse(File(tmp.root, "shots.json").exists())
        }
    }

    private companion object {
        const val ROUNDS = 300
    }
}
