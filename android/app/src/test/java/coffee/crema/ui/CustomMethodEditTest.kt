package coffee.crema.ui

import coffee.crema.brew.BREW_METHOD_STYLES
import coffee.crema.brew.CUSTOM_METHOD_ICONS
import coffee.crema.brew.CustomMethods
import coffee.crema.brew.methodIcon
import coffee.crema.brew.methodLabel
import coffee.crema.brew.newRecipeFor
import coffee.crema.brew.styleIcon
import coffee.crema.brew.validateCustomMethodLabel
import coffee.crema.core.BrewMethodStyle
import coffee.crema.core.CustomBrewMethod
import coffee.crema.ui.brewlog.MethodEditDraft
import coffee.crema.ui.brewlog.MethodEditRules
import coffee.crema.ui.brewlog.MethodEditTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The brewing-method dialog's pure rules + the label / icon resolution (issue #10 feedback). */
class CustomMethodEditTest {
    /** Stand-in for the core's `brew_method_style_seeds` (the preset numbers it borrows). */
    private val seeds: (String) -> String = { style ->
        when (style) {
            "immersion" -> """{"id":"french_press","seedDoseG":30,"seedWaterG":500,"seedTempC":95}"""
            "cold" -> """{"id":"cold_brew","seedDoseG":60,"seedWaterG":700}"""
            else -> """{"id":"pourover","seedDoseG":15,"seedWaterG":250,"seedTempC":96}"""
        }
    }

    private val orb = CustomBrewMethod(
        id = "custom:orb",
        label = "ORB",
        style = BrewMethodStyle.Percolation,
        seedDoseG = 18f,
        createdAt = 1,
        updatedAt = 1,
    )

    @After fun reset() {
        CustomMethods.all = emptyList()
        CustomMethods.snapshotLabels = emptyMap()
    }

    @Test fun aNewDraftPrefillsFromTheStyleAndFollowsStyleChanges() {
        val d = MethodEditRules.open(MethodEditTarget.LOG, "history", 1, label = "ORB", core = seeds)
        assertEquals(Triple("15", "250", "96"), Triple(d.dose, d.water, d.temp))
        assertEquals("log-brew", d.parentRoute)
        val typed = MethodEditRules.withField(d, MethodEditDraft.DOSE, "20")
        val cold = MethodEditRules.withStyle(typed, BrewMethodStyle.Cold, seeds)
        // The typed dose is the user's; the rest follow the new style (cold: no temp).
        assertEquals(Triple("20", "700", ""), Triple(cold.dose, cold.water, cold.temp))
        val saved = MethodEditRules.toMethod(cold, "ORB", "custom:new", 5)
        assertEquals("custom:new", saved.id)
        assertEquals(20f, saved.seedDoseG)
        assertNull("untouched seeds keep following the style", saved.seedWaterG)
        assertNull(saved.seedTempC)
        assertEquals(BrewMethodStyle.Cold, saved.style)
        assertEquals(5L, saved.createdAt)
    }

    @Test fun editingKeepsIdentityAndExplicitSeeds() {
        val d = MethodEditRules.edit(MethodEditTarget.MANAGE, "profiles", 2, orb, seeds)
        assertEquals("18", d.dose)
        assertEquals("250", d.water) // style default shown, not stored
        assertNull(d.parentRoute)
        val renamed = MethodEditRules.toMethod(d.copy(label = "Orb v2"), "Orb v2", "custom:unused", 9)
        assertEquals("custom:orb", renamed.id)
        assertEquals(1L, renamed.createdAt)
        assertEquals(9L, renamed.updatedAt)
        assertEquals(18f, renamed.seedDoseG)
        assertNull(renamed.seedWaterG)
        // Clearing an explicit field hands it back to the style.
        val cleared = MethodEditRules.withField(d, MethodEditDraft.DOSE, "")
        assertNull(MethodEditRules.toMethod(cleared, "ORB", "x", 9).seedDoseG)
    }

    @Test fun tombstoneAndRestoreMerge() {
        val gone = MethodEditRules.tombstone(listOf(orb), "custom:orb", 7).single()
        assertEquals(7L, gone.deletedAt)
        val newer = orb.copy(label = "Renamed", updatedAt = 10)
        val other = orb.copy(id = "custom:b", label = "B", createdAt = 3)
        val (merged, added) = MethodEditRules.mergeRestored(listOf(orb), listOf(newer, other), wipe = false)
        assertEquals(1, added)
        assertEquals(listOf("Renamed", "B"), merged.map { it.label })
        val (kept, _) = MethodEditRules.mergeRestored(listOf(newer), listOf(orb), wipe = false)
        assertEquals("Renamed", kept.single().label) // older backup copy doesn't win
        assertEquals(listOf(orb), MethodEditRules.mergeRestored(listOf(newer), listOf(orb), wipe = true).first)
    }

    @Test fun labelsResolveLiveThenTombstonedThenSnapshot() {
        assertEquals("Custom method", methodLabel("custom:orb"))
        CustomMethods.snapshotLabels = mapOf("custom:orb" to "ORB (saved)")
        assertEquals("ORB (saved)", methodLabel("custom:orb"))
        CustomMethods.all = listOf(orb.copy(label = "Orb v2", deletedAt = 3))
        assertEquals("Orb v2", methodLabel("custom:orb")) // the method's own name wins, even deleted
        assertEquals("funnel", methodIcon("custom:orb"))
        CustomMethods.all = listOf(orb.copy(icon = "leaf"))
        assertEquals("leaf", methodIcon("custom:orb"))
        // Presets and free text are unchanged.
        assertEquals("Chemex", methodLabel("chemex"))
        assertEquals("Karlsbad kanne", methodLabel("karlsbad_kanne"))
    }

    @Test fun validatorMapsTheCoreVerdictToCopy() {
        val ok = validateCustomMethodLabel("  ORB ", null, emptyList()) { """{"label":"ORB","error":null}""" }
        assertEquals("ORB", ok.getOrNull())
        val dup = validateCustomMethodLabel("chemex", null, emptyList()) { input ->
            assertTrue("preset labels ride along", input.contains("V60 / pourover"))
            """{"label":"chemex","error":"duplicate"}"""
        }
        assertTrue(dup.exceptionOrNull()!!.message!!.contains("already have"))
    }

    @Test fun aCustomMethodsFirstRecipeStartsFromItsStyle() {
        CustomMethods.all = listOf(orb)
        var seenStyleCall = false
        val r = newRecipeFor(
            "custom:orb",
            5,
            newId = { "recipe:x" },
            core = { _, _, _ -> error("presets aren't consulted for a custom method") },
            styleCore = { json, id, now ->
                seenStyleCall = json.contains("\"label\":\"ORB\"")
                """{"id":"$id","name":"","method":"custom:orb","doseG":18,"waterG":250,"tempC":null,"steps":[{"kind":"pour","targetWaterG":250,"advance":"auto"}],"notes":null,"favourite":false,"createdAt":$now,"updatedAt":$now,"deletedAt":null}"""
            },
        )
        assertTrue(seenStyleCall)
        assertEquals("ORB recipe", r.name)
        assertEquals("custom:orb", r.method)
    }

    @Test fun theIconListHasNoDuplicatesAndMatchesTheWebOrder() {
        val web = listOf("funnel", "coffee", "cylinder", "snowflake", "drop", "flask", "fire", "leaf")
        assertEquals("no duplicates", CUSTOM_METHOD_ICONS.distinct(), CUSTOM_METHOD_ICONS)
        assertEquals("same order as the web's METHOD_ICON_KEYS", web, CUSTOM_METHOD_ICONS)
        BREW_METHOD_STYLES.forEach { assertTrue("${it.string}'s default is in the list", styleIcon(it) in CUSTOM_METHOD_ICONS) }
    }

    @Test fun theIconListMatchesTheWebSource() {
        // Guards against the two lists drifting: read the web's own constant when the repo is whole.
        val src = listOf("../../web/src/lib/brew/custom-methods.svelte.ts", "../web/src/lib/brew/custom-methods.svelte.ts")
            .map(::File).firstOrNull { it.isFile }
        assumeTrue("web source not checked out alongside", src != null)
        val block = Regex("""METHOD_ICON_KEYS\s*=\s*\[([^\]]*)\]""").find(src!!.readText())!!.groupValues[1]
        val web = Regex("""'([a-z]+)'""").findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(web, CUSTOM_METHOD_ICONS)
    }

    @Test fun theIconFollowsTheStyleUntilPickedAndSavesTheKey() {
        val d = MethodEditRules.open(MethodEditTarget.LOG, "history", 1, core = seeds)
        assertEquals("funnel", d.icon)
        assertFalse(d.iconPicked)
        val immersion = MethodEditRules.withStyle(d, BrewMethodStyle.Immersion, seeds)
        assertEquals("the highlight moves with the style", "coffee", immersion.icon)
        // Saving with no pick stores the style's key — what the web stores.
        assertEquals("coffee", MethodEditRules.toMethod(immersion, "ORB", "custom:n", 1).icon)
        val picked = MethodEditRules.withIcon(immersion, "leaf")
        assertEquals("leaf", MethodEditRules.withStyle(picked, BrewMethodStyle.Cold, seeds).icon)
        // Picking the style's own key is still a pick: it stays put on a style change.
        val pickedFunnel = MethodEditRules.withIcon(d, "funnel")
        assertEquals("funnel", MethodEditRules.withStyle(pickedFunnel, BrewMethodStyle.Cold, seeds).icon)
    }

    @Test fun editingAMethodWithoutAPickFollowsItsStyle() {
        val d = MethodEditRules.edit(MethodEditTarget.MANAGE, "profiles", 2, orb, seeds)
        assertEquals("funnel", d.icon)
        assertEquals("snowflake", MethodEditRules.withStyle(d, BrewMethodStyle.Cold, seeds).icon)
        val leafy = MethodEditRules.edit(MethodEditTarget.MANAGE, "profiles", 2, orb.copy(icon = "leaf"), seeds)
        assertEquals("leaf", MethodEditRules.withStyle(leafy, BrewMethodStyle.Cold, seeds).icon)
    }
}
