package coffee.crema.ui

import coffee.crema.core.GrindScale
import coffee.crema.core.Grinder
import coffee.crema.core.GrinderLinkDeadReason
import coffee.crema.core.GrinderReading
import coffee.crema.core.GrinderSettings
import coffee.crema.core.GrinderState
import coffee.crema.core.ObservedSetting
import coffee.crema.core.PresetSlot
import coffee.crema.ui.screens.grinderReadout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The connected grinder's pure rules: the §6.1 link plan, the §2.7 messages, the readout. */
class ConnectedGrinderTest {
    private val scale = GrindScale(0f, 999f, 1f, "μm")
    private fun motto(id: String, archived: Boolean = false) =
        Grinder(id = id, name = id.removePrefix("grinder:"), scale = scale, presetId = MODEL, updatedAt = 1L, archivedAt = if (archived) 2L else null)
    private val niche = Grinder(id = "grinder:niche", name = "Niche", scale = GrindScale(0f, 50f, 1f, ""), presetId = "niche-zero", updatedAt = 1L)
    private val here = "AA:BB:CC:DD:EE:01"
    private val other = "AA:BB:CC:DD:EE:02"

    @Test
    fun aLinkToThisGrinderIsKept() {
        val plan = planGrinderLink(MODEL, here, here.lowercase(), "grinder:a", listOf(motto("grinder:a"), motto("grinder:b")))
        assertEquals(GrinderLinkPlan.Keep("grinder:a"), plan)
    }

    @Test
    fun oneMatchingRecordIsLinkedSilently() {
        assertEquals(GrinderLinkPlan.LinkNew("grinder:a"), planGrinderLink(MODEL, here, null, null, listOf(motto("grinder:a"), niche)))
        // An archived one doesn't count, nor a link to a record since archived.
        assertEquals(
            GrinderLinkPlan.LinkNew("grinder:b"),
            planGrinderLink(MODEL, here, here, "grinder:a", listOf(motto("grinder:a", archived = true), motto("grinder:b"))),
        )
    }

    @Test
    fun severalAreAChoiceAndNoneIsAnOffer() {
        assertEquals(
            GrinderLinkPlan.Choose(listOf("grinder:a", "grinder:b"), notYours = false),
            planGrinderLink(MODEL, here, null, null, listOf(motto("grinder:a"), motto("grinder:b"), niche)),
        )
        assertEquals(GrinderLinkPlan.OfferAdd, planGrinderLink(MODEL, here, null, null, listOf(niche)))
        assertEquals(GrinderLinkPlan.OfferAdd, planGrinderLink(MODEL, here, null, null, emptyList()))
    }

    @Test
    fun anotherGrinderHoldingTheLinkIsNeverFollowedSilently() {
        val plan = planGrinderLink(MODEL, here, other, "grinder:a", listOf(motto("grinder:a")))
        assertEquals(GrinderLinkPlan.Choose(listOf("grinder:a"), notYours = true), plan)
    }

    @Test
    fun theMessagesAreTheSpecs() {
        assertEquals(
            "Can't see your Motto 80. Make sure it's on and not connected to the BOOKOO app or another phone, then try again.",
            GrinderMessages.notFound("Motto 80"),
        )
        assertEquals(
            "The grinder didn't answer. Close the BOOKOO app, turn the grinder off and on, and try again.",
            GrinderMessages.noAnswer(GrinderLinkDeadReason.NoFirstReading, earlyDrops = 0),
        )
        assertTrue(GrinderMessages.noAnswer(GrinderLinkDeadReason.NoFirstReading, earlyDrops = 2).endsWith("If it keeps restarting, update its firmware in the BOOKOO app."))
        assertEquals(
            "Update the grinder in the BOOKOO app (it needs firmware 1.2.19.0820 or later), then connect again.",
            GrinderMessages.updateFirmware("1.2.19.0820"),
        )
    }

    @Test
    fun onlyTheSerialsLastFourAreShown() {
        assertEquals("2355", serialTail("SN0000000MT12355"))
        assertNull(serialTail("  "))
        assertNull(serialTail(null))
    }

    @Test
    fun theReadoutUsesTheGrindersUnit() {
        val r = GrinderReading(
            at_ms = 1, state = GrinderState.Grinding, raw_state = "GRINDING",
            setting = ObservedSetting("480", 480f), grind_rpm = 900u, feed_rpm = 40u, humidity_pct = 30u,
            total_grinds = 120, preset = PresetSlot.Manual, settings = GrinderSettings(),
        )
        val lines = grinderReadout(r, "μm").toMap()
        assertEquals("Grinding", lines["State"])
        assertEquals("480 μm", lines["Setting"])
        assertEquals("900 rpm", lines["Speed"])
        assertEquals("Manual", lines["Mode"])
        assertEquals("120", lines["Lifetime grinds"])
        assertEquals("480", grinderReadout(r, null).toMap()["Setting"])
    }

    private companion object {
        const val MODEL = "bookoo-motto-80"
    }
}
