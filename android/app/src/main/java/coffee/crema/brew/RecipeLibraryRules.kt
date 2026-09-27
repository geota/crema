package coffee.crema.brew

import coffee.crema.core.BrewRecipe
import coffee.crema.core.RecipeLibrary
import coffee.crema.core.RecipeLibraryMigration
import coffee.crema.core.defaultBuiltinRecipeId
import coffee.crema.core.duplicateRecipeJson
import coffee.crema.core.migrateRecipeLibraryJson

/*
 * The recipe library's shell rules (built-in brew recipes). Built-ins are the
 * core's bundled, credited catalogue — read-only, never persisted, never
 * backed up — mirroring built-in DE1 profiles: identified by catalogue
 * membership, hidden (not deleted) through a persisted id set, and edited
 * only through a credited copy. Pure Kotlin; the core calls are injectable
 * FFI lambdas so the rules are JVM-testable.
 */
object RecipeLibraryRules {
    /**
     * Clean a loaded store: the core's `migrate_recipe_library` drops
     * untouched legacy "… classic" starters (and any stored built-in),
     * repointing a default at a dropped one to the method's built-in.
     * Returns null when nothing changed (the file needn't be rewritten).
     */
    fun migrate(
        env: RecipeFileStore.Envelope,
        core: (String) -> String = ::migrateRecipeLibraryJson,
    ): RecipeFileStore.Envelope? {
        val input = CoreJson.encodeToString(
            RecipeLibrary.serializer(),
            RecipeLibrary(env.recipes, HashMap(env.lastUsedByMethod)),
        )
        val out = runCatching {
            CoreJson.decodeFromString(RecipeLibraryMigration.serializer(), core(input))
        }.getOrNull() ?: return null
        if (out.droppedIds.isEmpty()) return null
        return env.copy(recipes = out.recipes, lastUsedByMethod = out.defaultByMethod.toMap())
    }

    /**
     * The recipe [method] opens on: its default pointer when that still
     * resolves (a live user recipe, or a built-in — hidden or not), else the
     * method's default built-in, else none (espresso, drip, free text).
     */
    fun defaultFor(
        method: String,
        userRecipes: List<BrewRecipe>,
        builtins: List<BrewRecipe>,
        lastByMethod: Map<String, String>,
        defaultBuiltinId: (String) -> String? = ::defaultBuiltinRecipeId,
    ): BrewRecipe? {
        val pointed = lastByMethod[method]?.let { id -> find(id, userRecipes, builtins) }
        return pointed ?: defaultBuiltinId(method)?.let { id -> builtins.firstOrNull { it.id == id } }
    }

    /** A live user recipe or a built-in by id. */
    fun find(id: String, userRecipes: List<BrewRecipe>, builtins: List<BrewRecipe>): BrewRecipe? =
        userRecipes.firstOrNull { it.id == id && it.deletedAt == null } ?: builtins.firstOrNull { it.id == id }

    /** Whether [id] is a built-in (catalogue membership, like `isBuiltinProfile`). */
    fun isBuiltin(id: String, builtins: List<BrewRecipe>): Boolean = builtins.any { it.id == id }

    /**
     * The recipes a method's picker offers: its visible built-ins first
     * (catalogue order), then the user's live recipes. [keep] stays listed
     * even when hidden — the current selection never vanishes from under
     * the user.
     */
    fun pickerFor(
        method: String,
        userRecipes: List<BrewRecipe>,
        builtins: List<BrewRecipe>,
        hidden: Set<String>,
        keep: String? = null,
    ): List<BrewRecipe> =
        builtins.filter { it.method == method && (it.id !in hidden || it.id == keep) } +
            userRecipes.filter { it.method == method && it.deletedAt == null }

    /**
     * Whether a recipe saved from the editor becomes its method's default:
     * only when the method has no default at all — no pointer and no
     * built-in. Editing never silently moves a default ("Make default" in
     * Profiles does that), and a method with a built-in keeps opening on it.
     */
    fun becomesDefaultOnSave(
        method: String,
        lastByMethod: Map<String, String>,
        defaultBuiltinId: (String) -> String? = ::defaultBuiltinRecipeId,
    ): Boolean = lastByMethod[method] == null && defaultBuiltinId(method) == null

    /**
     * An editable copy — the core's `duplicate_recipe`: fresh id, "<name>
     * (copy)", credit "Adapted from …", same source URL.
     */
    fun duplicate(
        source: BrewRecipe,
        newId: String,
        nowMs: Long,
        core: (String, String, Long) -> String = ::duplicateRecipeJson,
    ): BrewRecipe = CoreJson.decodeFromString(
        BrewRecipe.serializer(),
        core(CoreJson.encodeToString(BrewRecipe.serializer(), source), newId, nowMs),
    )
}
