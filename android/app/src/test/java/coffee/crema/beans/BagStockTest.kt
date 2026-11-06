package coffee.crema.beans

import coffee.crema.core.BagStock
import coffee.crema.core.BagStockLowInner
import coffee.crema.settings.MirroredSettings
import coffee.crema.ui.MainUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Android side of the stock rule (#302), through the host build of the core. */
class BagStockTest {
    private fun bag(remaining: Float, size: Float) =
        newBean("Gesha", null, null, null, 0L).copy(id = "bean:1", remaining = remaining, bagSize = size)

    private fun state(remaining: Float, size: Float, shotInProgress: Boolean = false) =
        MainUiState(beans = listOf(bag(remaining, size)), activeBeanId = "bean:1", shotInProgress = shotInProgress)

    @Test
    fun `an unsized bag at 0 is untracked`() {
        assertEquals(BagStock.Untracked, bagStock(bag(0f, 0f)))
    }

    @Test
    fun `a sized bag at 0 is empty`() {
        assertEquals(BagStock.Empty, bagStock(bag(0f, 250f)))
    }

    @Test
    fun `a bag with brews to spare is ok`() {
        assertEquals(BagStock.Ok, bagStock(bag(100f, 250f), listOf(18f)))
    }

    @Test
    fun `low counts in the bag's own doses`() {
        assertEquals(BagStock.Low(BagStockLowInner(1u)), bagStock(bag(45f, 250f), listOf(30f)))
    }

    @Test
    fun `the banner shows on an empty active bag`() {
        assertEquals("Gesha", state(0f, 250f).emptyBagName())
    }

    @Test
    fun `the banner keeps out of the way of a running shot`() {
        assertNull(state(0f, 250f, shotInProgress = true).emptyBagName())
    }

    @Test
    fun `the banner never shows on an untracked bag`() {
        assertNull(state(0f, 0f).emptyBagName())
    }

    @Test
    fun `the banner stays away from a bag with grams`() {
        assertNull(state(5f, 250f).emptyBagName())
    }

    @Test
    fun `no active bag, no banner`() {
        assertNull(state(0f, 250f).copy(activeBeanId = null).emptyBagName())
    }

    @Test
    fun `a remote goes by the hub's word over its own copy`() {
        val remote = state(100f, 250f).copy(mirror = MirroredSettings(), mirroredBagEmpty = true)
        assertEquals("Gesha", remote.emptyBagName())
    }

    @Test
    fun `a remote's empty copy of a bag the hub still has shows nothing`() {
        assertNull(state(0f, 250f).copy(mirror = MirroredSettings()).emptyBagName())
    }

    @Test
    fun `a remote without the bag names the hub's summary`() {
        val remote = MainUiState(
            mirror = MirroredSettings(),
            activeBeanId = "bean:hub",
            mirroredBagEmpty = true,
            mirroredBeanSummary = "Onyx · Gesha",
        )
        assertEquals("Onyx · Gesha", remote.emptyBagName())
    }

    @Test
    fun `the banner's text names the bag`() {
        assertEquals("Gesha is at 0 g. Shots still log to it.", emptyBagBannerText("Gesha"))
    }

    @Test
    fun `the banner's text covers a bag with no name`() {
        assertEquals("This bag is at 0 g. Shots still log to it.", emptyBagBannerText(" "))
    }
}
