package coffee.crema.ui.components

import coffee.crema.core.MaintenanceReadout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which status card Brew shows, and its words (brew messages PRD §1, web parity). */
class BrewStatusTest {

    private fun readout(filterOk: Boolean = true, descaleOk: Boolean = true, cleanOk: Boolean = true) =
        MaintenanceReadout(
            filterUsedLitres = 0.0, filterPercent = 100.0, filterOk = filterOk,
            descaleSinceLitres = 0.0, descaleOk = descaleOk,
            cleanSinceHours = 0, cleanOk = cleanOk,
            filterEnabled = true, descaleEnabled = true, cleanEnabled = true,
        )

    @Test
    fun `a machine error wins over a refused start and maintenance`() {
        val s = brewStatusOf("Water flow too fast", "Can’t start shot — no profile selected", readout(descaleOk = false), emptySet())
        assertEquals(BrewStatus.MachineError("Water flow too fast"), s)
        assertEquals("Machine error", s!!.title)
    }

    @Test
    fun `a refused start wins over maintenance`() {
        val s = brewStatusOf(null, "Shot not started", readout(descaleOk = false), emptySet())
        assertEquals(BrewStatus.StartBlocked("Shot not started"), s)
        assertEquals("Can't start shot", s!!.title)
    }

    @Test
    fun `maintenance lists every due category in the web's order`() {
        val s = brewStatusOf(null, null, readout(filterOk = false, descaleOk = false, cleanOk = false), emptySet())
        assertEquals(
            BrewStatus.MaintenanceDue("Filter due, Descale due, Clean due", setOf("filter", "descale", "clean")),
            s,
        )
        assertEquals("Maintenance due", s!!.title)
    }

    @Test
    fun `a dismissed category stays hidden and a newly due one still shows`() {
        assertNull(brewStatusOf(null, null, readout(descaleOk = false), setOf("descale")))
        assertEquals(
            BrewStatus.MaintenanceDue("Clean due", setOf("clean")),
            brewStatusOf(null, null, readout(descaleOk = false, cleanOk = false), setOf("descale")),
        )
    }

    @Test
    fun `nothing to show`() {
        assertNull(brewStatusOf(null, null, readout(), emptySet()))
        assertNull(brewStatusOf(null, null, null, emptySet()))
    }

    @Test
    fun `the start reason drops the title it repeats`() {
        assertEquals("No profile selected", startBlockerDetail("Can’t start shot — no profile selected"))
        assertEquals("Profile conversion failed", startBlockerDetail("Can't start shot - profile conversion failed"))
        assertEquals(
            "Shot not started — reconnecting the scale… try again in a moment",
            startBlockerDetail("Shot not started — reconnecting the scale… try again in a moment"),
        )
        assertEquals("Can’t start shot", startBlockerDetail("Can’t start shot"))
    }
}
