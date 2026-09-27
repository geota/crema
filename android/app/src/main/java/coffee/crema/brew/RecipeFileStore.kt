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
 * bean library and shot log. Carries the user's recipes, the per-method
 * default pointer, the hidden built-in ids and the user's own brewing
 * methods in one envelope. Built-in
 * recipes are bundled with the app and never written here.
 */
class RecipeFileStore(private val context: Context, private val json: Json) {
    @Serializable
    data class Envelope(
        val recipes: List<BrewRecipe> = emptyList(),
        val lastUsedByMethod: Map<String, String> = emptyMap(),
        /** Built-in recipe ids the user hid from the library + picker. */
        val hiddenBuiltins: Set<String> = emptySet(),
        /**
         * The user's own brewing methods (`custom:<uuid>`), tombstones kept so
         * old brews still resolve. Rides here, beside the recipes that use
         * them — one file, one atomic write.
         */
        val customMethods: List<coffee.crema.core.CustomBrewMethod> = emptyList(),
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
