package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.brew.RecipeFileStore
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
 * Overlapping, the two used to collide and leave no `brew-recipes.json`, so the
 * restored recipes were gone on the next launch (#313).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RestoreRecipesVmTest {
    private val store = ViewModelStore()

    @After fun tearDown() { store.clear(); VmHarness.idle() }

    @Test
    fun `a wipe restore keeps the recipes on disk`() {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        val backup = File("../../core/de1-domain/tests/fixtures/backup/android-backup.crema.jsonl").readBytes()
        val file = File(VmHarness.app().filesDir, "brew-recipes.json")
        repeat(10) { round ->
            VmHarness.restore(vm, backup, MainViewModel.RestoreMode.WIPE)
            VmHarness.idleUntil("the recipes on disk, round $round") { file.exists() && savedIds() == listOf(RECIPE) }
        }
        assertEquals(listOf(RECIPE), savedIds())
    }

    private fun savedIds() = runBlocking { RecipeFileStore(VmHarness.app(), coreJson).load() }.recipes.map { it.id }

    private companion object {
        const val RECIPE = "recipe:android-copy"
    }
}
