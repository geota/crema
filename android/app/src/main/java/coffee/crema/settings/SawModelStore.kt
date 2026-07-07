package coffee.crema.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * File persistence for the core's learned SAW drip model
 * (`de1_domain::saw_learning`, an opaque JSON blob) — same file-JSON
 * pattern as the other stores. The core owns the shape; this store never
 * parses it. Seeded into the core at startup, saved after every shot.
 */
class SawModelStore(private val context: Context) {
    private val file get() = File(context.filesDir, FILE_NAME)

    suspend fun load(): String? = withContext(Dispatchers.IO) {
        runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
    }

    suspend fun save(json: String) {
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json) }
        }
    }

    private companion object {
        const val FILE_NAME = "sawModel.json"
    }
}
