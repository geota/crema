package coffee.crema.settings

import coffee.crema.ui.BrewParams
import coffee.crema.ui.MainUiState
import coffee.crema.ui.adopt
import coffee.crema.ui.adoptableChanges
import kotlin.test.Test
import kotlin.test.assertEquals

/** Remote → standalone adoption, pure (spec §7.5, AC-24). */
class AdoptTest {

    private val own = MainUiState(qcSteamTempC = 140f, stopOnWeight = false, steamEco = false, activeBeanId = "bean:p", qcGrind = 10f, themeMode = "light")
    private val cfg = v4Snapshot(own).copy(
        qcSteamTempC = 150f, stopOnWeight = true, steamEco = true, activeBeanId = "bean:s", qcGrind = 15f,
        brewDoseG = 20.0, brewYieldG = 40.0, brewTempC = 94.0,
    )

    @Test
    fun `only adoptable settings the snapshot carries and that differ change`() {
        val changes = adoptableChanges(own, cfg)
        assertEquals(true, Setting.QC_STEAM_TEMP in changes && Setting.STEAM_ECO in changes && Setting.BREW_PARAMS in changes)
        assertEquals(emptySet(), changes.filter { !it.adoptable }.toSet())
        assertEquals(false, Setting.THEME_MODE in changes)
    }

    @Test
    fun `adopting takes the hub's values and keeps personal ones`() {
        val adopted = adopt(own, cfg, adoptableChanges(own, cfg), hasBean = { it == "bean:s" })
        assertEquals(150f, adopted.qcSteamTempC)
        assertEquals(true, adopted.stopOnWeight)
        assertEquals(true, adopted.steamEco)
        assertEquals(15f, adopted.qcGrind)
        assertEquals("bean:s", adopted.activeBeanId)
        assertEquals(BrewParams(20.0, 40.0, 94.0, null), adopted.brewParams)
        assertEquals("light", adopted.themeMode)
    }

    @Test
    fun `a bean this library doesn't have is cleared`() {
        val adopted = adopt(own, cfg, adoptableChanges(own, cfg), hasBean = { false })
        assertEquals(null, adopted.activeBeanId)
    }

    /** Relay v4 R3b: a v4 snapshot carries every adoptable setting. */
    @Test
    fun `a v4 snapshot carries every adoptable setting`() {
        assertEquals(emptyList(), Setting.entries.filter { it.adoptable && !cfg.carries(it) })
    }
}
