package coffee.crema.ui

import coffee.crema.core.ShotBean
import coffee.crema.history.StoredShot
import coffee.crema.ui.brewlog.BrewLogDraft
import coffee.crema.ui.brewlog.BrewLogOwner
import coffee.crema.ui.brewlog.BrewLogSeeds
import coffee.crema.ui.brewlog.parseBrewDurationMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The VM-held Log-brew draft (issue #10): seeding, re-templating, parsing. */
class BrewLogDraftTest {
    private fun brew(id: String, method: String, beanId: String?, dose: Float, water: Float, at: Long = 1) = StoredShot(
        id = id,
        completedAtMs = at,
        durationMs = 185_000,
        doseG = dose,
        waterG = water,
        brewMethod = method,
        brewTempC = 94f,
        bean = beanId?.let { ShotBean(beanId = it, name = it) },
    )

    @Test fun seedsFromTheBagsOwnLastBrewOfTheMethod() {
        val history = listOf(
            brew("a", "pourover", "bean:other", 20f, 320f),
            brew("b", "pourover", "bean:1", 16f, 260f),
        )
        val d = BrewLogSeeds.open(BrewLogOwner.HISTORY, history, activeBeanId = "bean:1")
        assertEquals("pourover", d.method)
        assertEquals("bean:1", d.beanId)
        assertEquals(16.0, d.dose, 1e-6)
        assertEquals(260.0, d.water, 1e-6)
        assertEquals(BrewLogOwner.HISTORY, d.owner)
        assertFalse(d.journalOpen)
    }

    @Test fun theBeanDetailDoorWinsOverTheActiveBag() {
        val d = BrewLogSeeds.open(BrewLogOwner.BEANS, emptyList(), activeBeanId = "bean:active", prefillBeanId = "bean:detail")
        assertEquals("bean:detail", d.beanId)
        assertEquals(BrewLogOwner.BEANS, d.owner)
        // No history → the V60 preset seeds.
        assertEquals(15.0, d.dose, 1e-6)
        assertEquals(250.0, d.water, 1e-6)
    }

    @Test fun logAgainRefillsFromThatBrew() {
        val prior = brew("p", "aeropress", "bean:2", 14f, 220f)
        val d = BrewLogSeeds.open(BrewLogOwner.HISTORY, listOf(prior), activeBeanId = null, prefill = prior)
        assertEquals("aeropress", d.method)
        assertEquals("bean:2", d.beanId)
        assertEquals("3:05", d.timeStr)
    }

    @Test fun aMethodChipReTemplatesTheSeeds() {
        val d = BrewLogSeeds.open(BrewLogOwner.HISTORY, emptyList(), activeBeanId = null)
        val esp = BrewLogSeeds.reseed(d, emptyList(), "espresso")
        assertEquals(18.0, esp.dose, 1e-6)
        assertEquals(36.0, esp.water, 1e-6)
        assertTrue(esp.espresso)
        val other = BrewLogSeeds.reseed(esp, emptyList(), BrewLogDraft.OTHER)
        assertTrue(other.isCustom)
        assertEquals(18.0, other.dose, 1e-6)
    }

    @Test fun doseIsRequiredOnlyWhenABagIsDebited() {
        val d = BrewLogSeeds.open(BrewLogOwner.HISTORY, emptyList(), activeBeanId = null).copy(dose = 0.0)
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
