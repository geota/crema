package coffee.crema.beans

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Review 05 P2-1 (Android parity): Erase all data removes every bag photo. */
class BeanImageStoreEraseTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun deleteAllRemovesThePhotoDirectory() {
        val dir = File(tmp.root, "bean-images").apply { mkdirs() }
        File(dir, BeanImageStore.sanitize("bean:1")).writeBytes(byteArrayOf(1, 2, 3))
        File(dir, BeanImageStore.sanitize("bean:2")).writeBytes(byteArrayOf(4))
        val other = File(tmp.root, "shots.json").apply { writeText("[]") }
        BeanImageStore.deleteAllIn(dir)
        assertFalse(dir.exists())
        assertTrue(other.exists())
        BeanImageStore.deleteAllIn(dir) // idempotent on a missing dir
    }
}
