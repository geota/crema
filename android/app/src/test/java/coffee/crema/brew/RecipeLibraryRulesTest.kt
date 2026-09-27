package coffee.crema.brew

import coffee.crema.core.BrewRecipe
import coffee.crema.core.BrewStep
import coffee.crema.core.BrewStepKind
import coffee.crema.core.RecipeLibrary
import coffee.crema.core.RecipeLibraryMigration
import coffee.crema.core.StepAdvance
import coffee.crema.ui.brewlog.isOpenableSourceUrl
import coffee.crema.ui.brewlog.visibleBrewRecipes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recipe library's shell rules over the built-in catalogue. The core
 * rules themselves (which starters are legacy, the duplicate naming) are
 * pinned by de1-domain tests; these pin the shell's wiring with the FFI
 * calls injected.
 */
class RecipeLibraryRulesTest {
    private fun r(
        id: String,
        method: String = "pourover",
        name: String = "R $id",
        credit: String? = null,
        deleted: Long? = null,
        updated: Long = 1,
    ) = BrewRecipe(
        id = id, name = name, method = method, doseG = 15f, waterG = 250f, tempC = 96f,
        steps = listOf(BrewStep(kind = BrewStepKind.Pour, targetWaterG = 250f, advance = StepAdvance.Auto)),
        notes = null, favourite = false, createdAt = 1, updatedAt = updated, deletedAt = deleted,
        credit = credit, sourceUrl = credit?.let { "https://example.com/$id" },
    )

    private val v60 = r("builtin:v60", credit = "James Hoffmann", name = "1 Cup V60")
    private val kasuya = r("builtin:kasuya", credit = "Tetsu Kasuya", name = "4:6 Method")
    private val official = r("builtin:ap", method = "aeropress", credit = "AeroPress Inc.")
    private val builtins = listOf(v60, kasuya, official)
    private val defaults: (String) -> String? = { m ->
        when (m) { "pourover" -> v60.id; "aeropress" -> official.id; else -> null }
    }

    // ── migration ────────────────────────────────────────────────────

    @Test fun `a dropped legacy starter rewrites the store and repoints its default`() {
        val legacy = r("recipe:old", name = "V60 classic")
        val kept = r("recipe:mine", name = "My V60")
        val env = RecipeFileStore.Envelope(
            recipes = listOf(legacy, kept),
            lastUsedByMethod = mapOf("pourover" to legacy.id, "aeropress" to "recipe:x"),
            hiddenBuiltins = setOf(kasuya.id),
        )
        var seen: RecipeLibrary? = null
        val core: (String) -> String = { input ->
            seen = CoreJson.decodeFromString(RecipeLibrary.serializer(), input)
            CoreJson.encodeToString(
                RecipeLibraryMigration.serializer(),
                RecipeLibraryMigration(
                    recipes = listOf(kept),
                    defaultByMethod = hashMapOf("pourover" to v60.id, "aeropress" to "recipe:x"),
                    droppedIds = listOf(legacy.id),
                ),
            )
        }
        val out = RecipeLibraryRules.migrate(env, core)!!
        // The core saw the whole stored library, pointers included.
        assertEquals(listOf(legacy.id, kept.id), seen!!.recipes.map { it.id })
        assertEquals(legacy.id, seen!!.defaultByMethod["pourover"])
        assertEquals(listOf(kept), out.recipes)
        assertEquals(v60.id, out.lastUsedByMethod["pourover"])
        // Hidden built-ins are untouched by the migration.
        assertEquals(setOf(kasuya.id), out.hiddenBuiltins)
    }

    @Test fun `an edited starter is kept and the store is not rewritten`() {
        val edited = r("recipe:old", name = "V60 classic").copy(doseG = 16f)
        val env = RecipeFileStore.Envelope(listOf(edited), mapOf("pourover" to edited.id))
        val core: (String) -> String = { input ->
            val lib = CoreJson.decodeFromString(RecipeLibrary.serializer(), input)
            CoreJson.encodeToString(
                RecipeLibraryMigration.serializer(),
                RecipeLibraryMigration(lib.recipes, lib.defaultByMethod, emptyList()),
            )
        }
        assertNull(RecipeLibraryRules.migrate(env, core))
        // A core failure never loses data either.
        assertNull(RecipeLibraryRules.migrate(env) { error("boom") })
    }

    // ── defaults + picker ────────────────────────────────────────────

    @Test fun `the default is the pointer when it resolves else the built-in else none`() {
        val mine = r("recipe:mine")
        assertEquals(v60, RecipeLibraryRules.defaultFor("pourover", listOf(mine), builtins, emptyMap(), defaults))
        assertEquals(mine, RecipeLibraryRules.defaultFor("pourover", listOf(mine), builtins, mapOf("pourover" to mine.id), defaults))
        assertEquals(kasuya, RecipeLibraryRules.defaultFor("pourover", listOf(mine), builtins, mapOf("pourover" to kasuya.id), defaults))
        // A pointer at a deleted recipe falls back to the built-in.
        val gone = r("recipe:gone", deleted = 5)
        assertEquals(v60, RecipeLibraryRules.defaultFor("pourover", listOf(gone), builtins, mapOf("pourover" to gone.id), defaults))
        // Espresso has no built-in and no recipe → none.
        assertNull(RecipeLibraryRules.defaultFor("espresso", listOf(mine), builtins, emptyMap(), defaults))
    }

    @Test fun `the picker lists visible built-ins first then the users recipes`() {
        val mine = r("recipe:mine")
        val ids = RecipeLibraryRules.pickerFor("pourover", listOf(mine, r("recipe:ap", method = "aeropress")), builtins, setOf(kasuya.id))
            .map { it.id }
        assertEquals(listOf(v60.id, mine.id), ids)
        // A hidden built-in that is the current selection stays listed.
        val kept = RecipeLibraryRules.pickerFor("pourover", listOf(mine), builtins, setOf(kasuya.id), keep = kasuya.id)
        assertEquals(listOf(v60.id, kasuya.id, mine.id), kept.map { it.id })
    }

    @Test fun `saving only becomes the default when the method has neither pointer nor built-in`() {
        assertFalse(RecipeLibraryRules.becomesDefaultOnSave("pourover", emptyMap(), defaults))
        assertTrue(RecipeLibraryRules.becomesDefaultOnSave("espresso", emptyMap(), defaults))
        assertFalse(RecipeLibraryRules.becomesDefaultOnSave("espresso", mapOf("espresso" to "recipe:e"), defaults))
    }

    @Test fun `duplicate hands the recipe to the core and decodes the copy`() {
        val copy = RecipeLibraryRules.duplicate(v60, "recipe:new", 9L) { json, id, now ->
            val src = CoreJson.decodeFromString(BrewRecipe.serializer(), json)
            CoreJson.encodeToString(
                BrewRecipe.serializer(),
                src.copy(id = id, name = "${src.name} (copy)", credit = "Adapted from ${src.credit}", createdAt = now, updatedAt = now),
            )
        }
        assertEquals("recipe:new", copy.id)
        assertEquals("1 Cup V60 (copy)", copy.name)
        assertEquals("Adapted from James Hoffmann", copy.credit)
        assertEquals(v60.sourceUrl, copy.sourceUrl)
        assertFalse(RecipeLibraryRules.isBuiltin(copy.id, builtins))
        assertTrue(RecipeLibraryRules.isBuiltin(v60.id, builtins))
    }

    // ── library list ─────────────────────────────────────────────────

    @Test fun `the library groups by method with built-ins first and hides hidden ones`() {
        val mine = r("recipe:mine", updated = 9)
        val list = visibleBrewRecipes(listOf(mine), "", builtins, hidden = setOf(kasuya.id))
        assertEquals(listOf(official.id, v60.id, mine.id), list.map { it.id })
        val all = visibleBrewRecipes(listOf(mine), "", builtins, hidden = setOf(kasuya.id), showHidden = true)
        assertEquals(listOf(official.id, v60.id, kasuya.id, mine.id), all.map { it.id })
        // Search reaches the credit line.
        assertEquals(listOf(kasuya.id), visibleBrewRecipes(listOf(mine), "kasuya", builtins).map { it.id })
    }

    @Test fun `only http links are opened`() {
        assertTrue(isOpenableSourceUrl("https://www.youtube.com/watch?v=1oB1oDrDkHM"))
        assertTrue(isOpenableSourceUrl("HTTP://example.com"))
        assertFalse(isOpenableSourceUrl("javascript:alert(1)"))
        assertFalse(isOpenableSourceUrl("intent://x"))
        assertFalse(isOpenableSourceUrl(null))
    }

    // ── methods + clock ──────────────────────────────────────────────

    @Test fun `the clock formats hours for a 12 hour steep`() {
        assertEquals("0:00", formatClock(0))
        assertEquals("3:05", formatClock(185_000))
        assertEquals("59:59", formatClock(3_599_000))
        assertEquals("1:00:00", formatClock(3_600_000))
        assertEquals("12:00:00", formatClock(43_200_000))
        assertEquals("0:00", formatClock(-5))
    }

    @Test fun `a new recipe is the core blank named with the UI label`() {
        val blank = newRecipeFor("chemex", 7L, newId = { "recipe:n" }) { m, id, now ->
            CoreJson.encodeToString(BrewRecipe.serializer(), r(id, method = m, name = "").copy(createdAt = now))
        }
        assertEquals("recipe:n", blank.id)
        assertEquals("chemex", blank.method)
        assertEquals("Chemex recipe", blank.name)
        assertEquals("Kalita Wave", methodLabel("kalita_wave"))
        assertEquals("waves", methodIcon("kalita_wave"))
        assertEquals("hourglass-simple", methodIcon("chemex"))
    }
}
