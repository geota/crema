package coffee.crema.persist

import kotlinx.coroutines.runBlocking
import java.io.File

/** [saveStore] for a test on one thread, with the text already made. */
internal fun saveNow(target: File, what: String, text: String): Boolean =
    runBlocking { saveStore(target, what) { text } }
