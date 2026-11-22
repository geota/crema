package coffee.crema.brew

import coffee.crema.core.BrewMode
import coffee.crema.core.BrewPickInput
import coffee.crema.core.BrewPickLock
import coffee.crema.core.BrewPickLockInput
import coffee.crema.core.BrewPickLockReason
import coffee.crema.core.BrewSelection
import coffee.crema.core.BrewSelectionInput
import coffee.crema.core.BrewSelectionKind
import coffee.crema.core.BrewSelectionView
import coffee.crema.core.RecipeSummary
import coffee.crema.core.StoredBrewSelection
import coffee.crema.core.brewPickLockJson
import coffee.crema.core.brewSelectionViewJson
import coffee.crema.core.builtinBrewRecipesJson
import coffee.crema.core.pickBrewSelectionJson
import coffee.crema.core.recipeSummaryJson
import coffee.crema.ui.coreJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The brew-modes core exports (spec §2, §4) through the generated Kotlin
 * types and [coreJson], as the shell will call them: the stored selection
 * and its migration, a pick, the lock and a recipe's summary.
 */
class BrewSelectionCoreTest {

    private fun view(input: BrewSelectionInput): BrewSelectionView =
        coreJson.decodeFromString(brewSelectionViewJson(coreJson.encodeToString(BrewSelectionInput.serializer(), input)))

    @Test
    fun aStoredProfileFromBeforeBrewModesIsEspresso() {
        val v = view(BrewSelectionInput(activeProfileId = "p1", recipeLive = false))
        assertEquals(BrewSelection(BrewSelectionKind.Profile, "p1"), v.selection)
        assertEquals(BrewMode.Espresso, v.mode)
        val fresh = view(BrewSelectionInput(recipeLive = false))
        assertNull(fresh.selection)
        assertEquals(BrewMode.Espresso, fresh.mode)
    }

    @Test
    fun aRecipePickIsManualAndKeepsTheProfile() {
        val input = BrewPickInput(StoredBrewSelection(activeProfileId = "p1"), BrewSelection(BrewSelectionKind.Recipe, "recipe:a"))
        val stored: StoredBrewSelection = coreJson.decodeFromString(pickBrewSelectionJson(coreJson.encodeToString(BrewPickInput.serializer(), input)))
        assertEquals(StoredBrewSelection(activeProfileId = "p1", activeRecipeId = "recipe:a"), stored)
        val v = view(BrewSelectionInput(stored.activeProfileId, stored.activeRecipeId, recipeLive = true))
        assertEquals(BrewMode.Manual, v.mode)
    }

    @Test
    fun aShotOrAGuidedBrewLocksThePicker() {
        fun lock(shot: Boolean, brew: Boolean): BrewPickLock? =
            brewPickLockJson(coreJson.encodeToString(BrewPickLockInput.serializer(), BrewPickLockInput(shot, brew)))
                ?.let { coreJson.decodeFromString(it) }
        assertNull(lock(shot = false, brew = false))
        assertEquals(BrewPickLock(BrewPickLockReason.Shot, "Finish the current shot first"), lock(shot = true, brew = false))
        assertEquals(BrewPickLockReason.GuidedBrew, lock(shot = false, brew = true)?.reason)
    }

    @Test
    fun everyBuiltinRecipeSummarises() {
        val recipes = coreJson.parseToJsonElement(builtinBrewRecipesJson()) as JsonArray
        for (r in recipes) {
            val summary: RecipeSummary = coreJson.decodeFromString(recipeSummaryJson(r.toString()))
            assertEquals((r as JsonObject)["id"]!!.jsonPrimitive.content, summary.id)
        }
    }
}
