package coffee.crema.ui

import coffee.crema.core.BrewLogSeedInput
import coffee.crema.core.ShotBean
import coffee.crema.history.StoredShot
import coffee.crema.ui.brewlog.BrewLogDraft
import coffee.crema.ui.brewlog.BrewLogOwner
import coffee.crema.ui.brewlog.BrewLogSeeds
import coffee.crema.ui.brewlog.parseBrewDurationMs
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The VM-held Log-brew draft (issue #10). The seed numbers are the core's
 * (`brew_log_seeds`, pinned by core tests); these cover the shell side — the
 * row projection handed to the core, applying the seeds, and the dirty-field
 * tracking — with the native call stood in by a recording fake.
 */
class BrewLogDraftTest {
    /** A fake `brewLogSeedsJson`: records the input, answers with [reply]. */
    private class FakeCore(var reply: String) {
        private val json = Json { ignoreUnknownKeys = true }
        val inputs = mutableListOf<BrewLogSeedInput>()
        fun call(raw: String): String {
            inputs += json.decodeFromString(BrewLogSeedInput.serializer(), raw)
            return reply
        }
    }

    private fun seeds(method: String, dose: Float, water: Float, grind: Float? = null, temp: Float? = null, ms: Long? = null) =
        """{"method":"$method","dose":$dose,"water":$water,"grind":${grind ?: "null"},"tempC":${temp ?: "null"},"durationMs":${ms ?: "null"}}"""

    private fun brew(id: String, method: String, beanId: String?, dose: Float, water: Float, grind: Float? = null) = StoredShot(
        id = id,
        completedAtMs = 1,
        durationMs = 185_000,
        doseG = dose,
        waterG = water,
        brewMethod = method,
        brewTempC = 94f,
        grindSetting = grind,
        bean = beanId?.let { ShotBean(beanId = it, name = it) },
    )

    @Test fun opensOnTheCoresMethodWithLastUsedAndBagGrindPassedThrough() {
        // Drift bugs 1 + 2: the core gets last-used + the bag's grinder setting.
        val core = FakeCore(seeds("aeropress", 14f, 220f, grind = 22f, temp = 90f))
        val history = listOf(brew("a", "pourover", "bean:1", 16f, 260f, grind = 18.5f))
        val d = BrewLogSeeds.open(
            BrewLogOwner.HISTORY, history, activeBeanId = "bean:1",
            lastUsedMethod = "aeropress", grinderOf = { "22 clicks" }, seedsJson = core::call,
        )
        val input = core.inputs.single()
        assertNull(input.method)
        assertEquals("aeropress", input.lastUsedMethod)
        assertEquals("bean:1", input.beanId)
        assertEquals("22 clicks", input.beanGrinderSetting)
        assertEquals("pourover", input.rows.single().brewMethod)
        assertEquals("18.5", input.rows.single().grinderSetting)
        assertEquals(185_000L, input.rows.single().durationMs)
        assertEquals("aeropress", d.method)
        assertEquals(14.0, d.dose, 1e-6)
        assertEquals(22.0, d.grind, 1e-6)
        assertEquals(90.0, d.temp, 1e-6)
        assertEquals(BrewLogOwner.HISTORY, d.owner)
        assertFalse(d.journalOpen)
    }

    @Test fun theBeanDetailDoorWinsOverTheActiveBag() {
        val core = FakeCore(seeds("pourover", 15f, 250f))
        val d = BrewLogSeeds.open(
            BrewLogOwner.BEANS, emptyList(), activeBeanId = "bean:active", prefillBeanId = "bean:detail",
            seedsJson = core::call,
        )
        assertEquals("bean:detail", d.beanId)
        assertEquals("bean:detail", core.inputs.single().beanId)
        assertEquals(BrewLogOwner.BEANS, d.owner)
    }

    @Test fun logAgainHandsTheBrewToTheCoreAsAPrefill() {
        val prior = brew("p", "aeropress", "bean:2", 14f, 220f, grind = 9f)
        val core = FakeCore(seeds("aeropress", 14f, 220f, ms = 185_000))
        val d = BrewLogSeeds.open(BrewLogOwner.HISTORY, listOf(prior), activeBeanId = null, prefill = prior, seedsJson = core::call)
        val pf = core.inputs.single().prefill!!
        assertEquals("aeropress", pf.method)
        assertEquals(14f, pf.doseG)
        assertEquals("9", pf.grinderSetting)
        assertEquals(185_000L, pf.durationMs)
        assertEquals("bean:2", d.beanId)
        assertEquals("3:05", d.timeStr)
        // The prefill rides the draft so a later reseed can pass it back.
        assertEquals(pf, d.prefill)
    }

    @Test fun aMethodSwitchReseedsOnlyUneditedFieldsIncludingTime() {
        // Drift bugs 3 + 4: time reseeds; user-edited fields survive.
        val core = FakeCore(seeds("pourover", 15f, 250f, ms = 185_000))
        val d = BrewLogSeeds.open(BrewLogOwner.HISTORY, emptyList(), activeBeanId = null, seedsJson = core::call)
            .edited(BrewLogDraft.DOSE).copy(dose = 21.0)
        core.reply = seeds("espresso", 18f, 36f, temp = 93f, ms = 28_000)
        val esp = BrewLogSeeds.reseed(d, emptyList(), "espresso", seedsJson = core::call)
        assertEquals("espresso", core.inputs.last().method)
        assertEquals(21.0, esp.dose, 1e-6) // edited → kept
        assertEquals(36.0, esp.water, 1e-6)
        assertEquals(93.0, esp.temp, 1e-6)
        assertEquals("0:28", esp.timeStr)
        assertTrue(esp.espresso)
    }

    @Test fun otherKeepsTheCurrentValuesWithoutAskingTheCore() {
        val core = FakeCore(seeds("espresso", 18f, 36f))
        val esp = BrewLogSeeds.open(BrewLogOwner.HISTORY, emptyList(), activeBeanId = null, seedsJson = core::call)
        val other = BrewLogSeeds.reseed(esp, emptyList(), BrewLogDraft.OTHER, seedsJson = core::call)
        assertTrue(other.isCustom)
        assertEquals(18.0, other.dose, 1e-6)
        assertEquals(1, core.inputs.size)
    }

    @Test fun missingSeedsBlankTheirFields() {
        val core = FakeCore(seeds("cold_brew", 60f, 700f))
        val d = BrewLogSeeds.open(BrewLogOwner.HISTORY, emptyList(), activeBeanId = null, seedsJson = core::call)
        assertEquals(0.0, d.grind, 1e-6)
        assertEquals(0.0, d.temp, 1e-6)
        assertEquals("", d.timeStr)
    }

    @Test fun doseIsRequiredOnlyWhenABagIsDebited() {
        val core = FakeCore(seeds("pourover", 15f, 250f))
        val d = BrewLogSeeds.open(BrewLogOwner.HISTORY, emptyList(), activeBeanId = null, seedsJson = core::call).copy(dose = 0.0)
        assertTrue(d.doseMissing(beanExists = true))
        assertFalse(d.doseMissing(beanExists = false))
    }

    @Test fun brewTimeParsesClockOrSeconds() {
        assertEquals(185_000L, parseBrewDurationMs("3:05"))
        assertEquals(42_000L, parseBrewDurationMs(" 42 "))
        assertNull(parseBrewDurationMs(""))
        assertNull(parseBrewDurationMs("abc"))
    }
}
