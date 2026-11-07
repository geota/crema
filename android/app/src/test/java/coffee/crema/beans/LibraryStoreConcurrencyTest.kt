package coffee.crema.beans

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coffee.crema.ui.coreJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertTrue

/**
 * Two saves in a row (archiving a bag and dropping it as active) must not
 * collide: unserialised, the loser moved the library to its backup and found its
 * shared temp file gone, leaving no `beanLibrary.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LibraryStoreConcurrencyTest {
    @org.junit.Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    @Test
    fun `overlapping saves leave the library in place`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = LibraryStore(app, coreJson)
        val file = File(app.filesDir, "beanLibrary.json")
        repeat(200) { round ->
            (1..4).map { n ->
                async(Dispatchers.Default) { store.save(BeanLibrary(activeBeanId = "bean:$round-$n")) }
            }.awaitAll()
            assertTrue(file.exists(), "the library went missing in round $round")
        }
    }
}
