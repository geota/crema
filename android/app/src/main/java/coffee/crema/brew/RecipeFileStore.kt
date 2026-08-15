package coffee.crema.brew

import android.content.Context
import coffee.crema.core.BrewRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-backed JSON persistence for the guided-brew recipe library
 * (issue #10) — `filesDir/brew-recipes.json`, the same pattern as the
 * bean library and shot log. Carries the recipes plus the per-method
 * last-used pointer in one envelope.
 */
class RecipeFileStore(private val context: Context, private val json: Json) {
    @Serializable
    data class Envelope(
        val recipes: List<BrewRecipe> = emptyList(),
        val lastUsedByMethod: Map<String, String> = emptyMap(),
    )

    private val file get() = File(context.filesDir, FILE_NAME)

    suspend fun load(): Envelope = withContext(Dispatchers.IO) {
        runCatching {
            file.takeIf { it.exists() }?.readText()
                ?.let { json.decodeFromString(Envelope.serializer(), it) }
        }.getOrNull() ?: Envelope()
    }

    suspend fun save(envelope: Envelope) {
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json.encodeToString(Envelope.serializer(), envelope)) }
        }
    }

    private companion object {
        const val FILE_NAME = "brew-recipes.json"
    }
}
