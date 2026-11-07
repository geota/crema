package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.brew.RecipeFileStore
import coffee.crema.persist.inSaveOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals

/**
 * A wipe restore saves the recipe store twice in a row (methods, then recipes).
 * Overlapping, the two used to collide and leave no `brew-recipes.json`, and
 * with the collision fixed the older one could still land last. Either way the
 * restored recipes were gone on the next launch (#313).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RestoreRecipesVmTest {
    private val store = ViewModelStore()

    @After fun tearDown() { store.clear(); VmHarness.idle() }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `a wipe restore keeps the recipes on disk`() {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        val backup = File("../../core/de1-domain/tests/fixtures/backup/android-backup.crema.jsonl").readBytes()
        val file = File(VmHarness.app().filesDir, "brew-recipes.json")
        repeat(30) { round ->
            VmHarness.restore(vm, backup, MainViewModel.RestoreMode.WIPE)
            // Past every save the restore queued: what's left is the last one.
            // Awaited through the looper, not blocking it: a save releases its
            // turn on Main.
            val drained = CompletableDeferred<Unit>()
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main.immediate) {
                inSaveOrder(file) {}
                drained.complete(Unit)
            }
            VmHarness.idleUntil("the restore's saves, round $round") { drained.isCompleted }
            assertEquals(listOf(RECIPE), savedIds(), "round $round")
        }
    }

    private fun savedIds() = runBlocking { RecipeFileStore(VmHarness.app(), coreJson).load() }.recipes.map { it.id }

    private companion object {
        const val RECIPE = "recipe:android-copy"
    }
}
