package coffee.crema.persist

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

/** The newest snapshot wins: a save launched later lands later. */
class SaveOrderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `saves land in the order they were called`() = runBlocking {
        val target = tmp.newFile("store.json")
        val landed = CopyOnWriteArrayList<Int>()
        val saves = (1..50).map { n ->
            launch(start = CoroutineStart.UNDISPATCHED) { inSaveOrder(target) { landed += n } }
        }
        saves.forEach { it.join() }
        assertEquals((1..50).toList(), landed.toList())
    }

    @Test
    fun `an erase after a save leaves nothing`() = runBlocking {
        val target = tmp.newFile("store.json")
        val save = launch(start = CoroutineStart.UNDISPATCHED) {
            inSaveOrder(target) {
                Thread.sleep(20)
                saveStore(target, "[1]")
            }
        }
        val erase = launch(start = CoroutineStart.UNDISPATCHED) { inSaveOrder(target) { eraseStore(target) } }
        delay(1)
        save.join()
        erase.join()
        assertEquals(false, target.exists())
    }
}
