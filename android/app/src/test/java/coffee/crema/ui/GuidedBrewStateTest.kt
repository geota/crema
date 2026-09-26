package coffee.crema.ui

import coffee.crema.core.BrewRecipe
import coffee.crema.core.BrewStep
import coffee.crema.core.BrewStepKind
import coffee.crema.core.StepAdvance
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.toCommonSettings
import coffee.crema.settings.withCommonSettings
import coffee.crema.ui.brewlog.BrewCueDefaults
import coffee.crema.ui.brewlog.GuidedBrewSetup
import coffee.crema.ui.brewlog.GuidedSetupRules
import coffee.crema.ui.brewlog.LivePane
import coffee.crema.ui.brewlog.RecipeEditDraft
import coffee.crema.ui.brewlog.RecipeEditOwner
import coffee.crema.ui.brewlog.ScaleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedBrewStateTest {
    private fun recipe(id: String, method: String = "pourover", name: String = "R $id", deleted: Long? = null) = BrewRecipe(
        id = id,
        name = name,
        method = method,
        doseG = 15f,
        waterG = 250f,
        tempC = 96f,
        steps = listOf(
            BrewStep(kind = BrewStepKind.Bloom, targetWaterG = 45f, durationS = 45, advance = StepAdvance.Auto),
            BrewStep(kind = BrewStepKind.Pour, targetWaterG = 250f, advance = StepAdvance.Auto),
            BrewStep(kind = BrewStepKind.Drawdown, advance = StepAdvance.Manual),
        ),
        notes = null,
        favourite = false,
        createdAt = 1,
        updatedAt = 1,
        deletedAt = deleted,
    )

    private val template: (String) -> BrewRecipe = { m -> recipe("tpl-$m", method = m, name = "$m classic") }

    // ── Cue defaults ────────────────────────────────────────────────────

    @Test fun cueDefaultsAreHapticsOnSoundOff() {
        assertFalse(BrewCueDefaults.soundOn(null))
        assertTrue(BrewCueDefaults.hapticsOn(null))
        assertTrue(BrewCueDefaults.soundOn(true))
        assertFalse(BrewCueDefaults.hapticsOn(false))
    }

    @Test fun untouchedCueSettingsStayUnsetThroughCommonSettings() {
        // A user who never toggled keeps null (→ today's defaults) across a
        // persist / restore round-trip; an explicit choice survives as-is.
        val fresh = AppPrefs()
        assertNull(fresh.toCommonSettings().brewCueSound)
        assertNull(fresh.toCommonSettings().brewCueHaptics)
        assertNull(AppPrefs().withCommonSettings(fresh.toCommonSettings()).brewCueSound)
        val chosen = AppPrefs(brewCueSound = true, brewCueHaptics = false)
        val back = AppPrefs().withCommonSettings(chosen.toCommonSettings())
        assertEquals(true, back.brewCueSound)
        assertEquals(false, back.brewCueHaptics)
    }

    // ── Setup selections ────────────────────────────────────────────────

    @Test fun blankSetupOpensOnTheLastUsedRecipe() {
        val saved = recipe("a", method = "aeropress")
        val s = GuidedSetupRules.resolve(GuidedBrewSetup(), listOf(saved), mapOf("aeropress" to "a"), template)
        assertEquals("aeropress", s.method)
        assertEquals(saved, s.recipe)
        assertEquals(ScaleMode.WEIGH, s.scaleMode)
    }

    @Test fun blankSetupWithoutHistoryUsesTheTemplateAndKeepsItStable() {
        val s = GuidedSetupRules.resolve(GuidedBrewSetup(), emptyList(), emptyMap(), template)
        assertEquals("pourover", s.method)
        assertEquals("tpl-pourover", s.recipe?.id)
        // Resolving again (a recomposition / rotation) keeps the same object.
        assertSame(s, GuidedSetupRules.resolve(s, emptyList(), emptyMap(), template))
    }

    @Test fun anEditedRecipeReplacesTheStaleSelection() {
        val old = recipe("a")
        val edited = old.copy(name = "Renamed", updatedAt = 2)
        val s = GuidedBrewSetup(scaleMode = ScaleMode.BREW, method = "pourover", recipe = old)
        val r = GuidedSetupRules.resolve(s, listOf(edited), mapOf("pourover" to "a"), template)
        assertEquals("Renamed", r.recipe?.name)
        assertEquals(ScaleMode.BREW, r.scaleMode)
    }

    @Test fun aDeletedSelectionFallsBackToTheMethodDefault() {
        val s = GuidedBrewSetup(method = "pourover", recipe = recipe("a"))
        val r = GuidedSetupRules.resolve(s, listOf(recipe("a", deleted = 5)), emptyMap(), template)
        assertEquals("tpl-pourover", r.recipe?.id)
    }

    // ── Recipe editor draft ─────────────────────────────────────────────

    @Test fun draftRoundTripsTheRecipeAndKeepsIdentity() {
        val base = recipe("a")
        val d = RecipeEditDraft.of(RecipeEditOwner.SCALE, base, isNew = false)
        assertEquals("Edit recipe", d.heading)
        assertTrue(d.totalMatches)
        val out = d.copy(name = "  Morning V60 ", water = 300.0).addStep().toRecipe()
        assertEquals("a", out.id)
        assertEquals("Morning V60", out.name)
        assertEquals(300f, out.waterG)
        assertEquals(4, out.steps!!.size)
        assertEquals(300f, out.steps!!.last().targetWaterG)
    }

    @Test fun blankNameKeepsTheOriginalAndZeroTempClears() {
        val out = RecipeEditDraft.of(RecipeEditOwner.PROFILES, recipe("a"), false).copy(name = " ", temp = 0.0).toRecipe()
        assertEquals("R a", out.name)
        assertNull(out.tempC)
    }

    @Test fun methodSwitchReseedsOnlyANewRecipe() {
        val base = recipe("a")
        val editing = RecipeEditDraft.of(RecipeEditOwner.SCALE, base, isNew = false).withMethod("aeropress", template)
        assertEquals("aeropress", editing.method)
        assertEquals("R a", editing.name)
        val creating = RecipeEditDraft.of(RecipeEditOwner.PROFILES, base, isNew = true).withMethod("aeropress", template)
        assertEquals("aeropress classic", creating.name)
        assertEquals("New recipe", creating.heading)
    }

    @Test fun stepEditsAndPlannedTotal() {
        val d = RecipeEditDraft.of(RecipeEditOwner.SCALE, recipe("a"), false)
            .updateStep(1) { it.copy(targetWaterG = 240f, advance = StepAdvance.Manual) }
        assertEquals(240f, d.plannedTotal)
        assertFalse(d.totalMatches)
        assertEquals(StepAdvance.Manual, d.steps[1].advance)
        assertEquals(2, d.removeStep(0).steps.size)
    }

    // ── Live-session pane classes (PLAN §4) ─────────────────────────────

    @Test fun paneClassesFollowThePaneNotTheDevice() {
        assertEquals(LivePane.COMPACT, LivePane.of(427f, 700f)) // Pixel portrait
        assertEquals(LivePane.MEDIUM, LivePane.of(568f, 820f)) // 7" portrait: 600dp minus shell edges
        assertEquals(LivePane.COMPACT, LivePane.of(440f, 820f)) // widest phones
        assertEquals(LivePane.MEDIUM, LivePane.of(800f, 1100f)) // medium tablet portrait
        assertEquals(LivePane.COCKPIT, LivePane.of(780f, 330f)) // Pixel landscape, minus rail + header
        assertEquals(LivePane.COCKPIT, LivePane.of(860f, 300f))
        assertEquals(LivePane.COCKPIT, LivePane.of(870f, 450f)) // 7" landscape
        assertEquals(LivePane.TWO_COLUMN, LivePane.of(1350f, 780f)) // large tablet landscape
    }

    @Test fun clockScalesWithHeightWithinBounds() {
        assertEquals(48f, LivePane.clockSp(200f))
        assertEquals(96f, LivePane.clockSp(2000f))
        val mid = LivePane.clockSp(700f)
        assertTrue(mid in 48f..96f)
    }
}
