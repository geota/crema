package coffee.crema.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coffee.crema.ui.theme.CremaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The tablet rail's Devices entry (brew-modes spec §1): Scale and Grinder when
 * the rail fits every item and both pips without scrolling, else one Devices
 * item opening the tab used last. The phone's bottom bar is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w1280dp-h900dp")
class RailSplitTest {

    @get:Rule val compose = createComposeRule()

    private var navs = mutableListOf<String>()
    private var tab by mutableStateOf("scale")
    private var active by mutableStateOf("brew")

    private fun rail(height: Dp) {
        compose.setContent {
            CremaTheme {
                Box(Modifier.height(height)) {
                    CremaNavigationRail(
                        active = active,
                        onNav = { navs += it; active = it },
                        machineConnected = false,
                        scaleConnected = false,
                        devicesTab = tab,
                        onOpenDevicesTab = { tab = it },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The node for a rail item: its label (regular) or its accessible name (compact). */
    private fun item(label: String, compact: Boolean): SemanticsNodeInteraction =
        if (compact) compose.onNodeWithContentDescription(label) else compose.onNodeWithText(label)

    /** The rail item [label]'s selectable node (the M3 item merges its label). */
    private fun selectable(label: String, compact: Boolean): SemanticsNodeInteraction =
        compose.onNode(
            (if (compact) hasContentDescription(label) else hasText(label)) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
            useUnmergedTree = false,
        )

    @Test
    fun `the item lists split Devices into Scale and Grinder`() {
        assertEquals(
            listOf("Brew", "Profiles", "Beans", "History", "Scale", "Grinder", "Settings"),
            railItems(split = true).map { it.label },
        )
        assertEquals(
            listOf("Brew", "Profiles", "Beans", "History", "Devices", "Settings"),
            railItems(split = false).map { it.label },
        )
        assertEquals(listOf("scale", "grinder"), railItems(true).mapNotNull { it.devicesTab })
        assertTrue(railItems(true).filter { it.devicesTab != null }.all { it.id == "scale" })
    }

    @Test
    fun `a split item lights only while Devices shows its tab`() {
        val (scale, grinder) = railItems(true).filter { it.devicesTab != null }
        assertTrue(railItemSelected(scale, "scale", "scale"))
        assertFalse(railItemSelected(grinder, "scale", "scale"))
        assertTrue(railItemSelected(grinder, "scale", "grinder"))
        assertFalse(railItemSelected(scale, "scale", "grinder"))
        // Another page: neither lights, whatever the remembered tab.
        assertFalse(railItemSelected(scale, "brew", "scale"))
        assertFalse(railItemSelected(grinder, "brew", "grinder"))
        // The single Devices item lights on either tab.
        val devices = railItems(false).first { it.label == "Devices" }
        assertTrue(railItemSelected(devices, "scale", "grinder"))
        assertTrue(railItemSelected(devices, "scale", "scale"))
    }

    @Test
    fun `the split thresholds sit inside each density`() {
        assertTrue(SPLIT_RAIL_FROM_DP > COMPACT_RAIL_BELOW_DP)
        assertTrue(SPLIT_COMPACT_RAIL_FROM_DP < COMPACT_RAIL_BELOW_DP)
        assertTrue(SPLIT_SHORT_RAIL_FROM_DP in COMPACT_RAIL_BELOW_DP..SPLIT_RAIL_FROM_DP)
        assertEquals(RailLayout(split = true), railLayout(RailDensity.Regular, SPLIT_RAIL_FROM_DP))
        // Under it, the split holds with ~64dp items (a 10" tablet in landscape, ≈730dp).
        assertEquals(RailLayout(split = true, itemHeightDp = SHORT_RAIL_ITEM_DP), railLayout(RailDensity.Regular, SPLIT_RAIL_FROM_DP - 0.5f))
        assertEquals(RailLayout(split = true, itemHeightDp = SHORT_RAIL_ITEM_DP), railLayout(RailDensity.Regular, 730f))
        assertEquals(RailLayout(split = true, itemHeightDp = SHORT_RAIL_ITEM_DP), railLayout(RailDensity.Regular, SPLIT_SHORT_RAIL_FROM_DP))
        assertFalse(railSplitsDevices(RailDensity.Regular, SPLIT_SHORT_RAIL_FROM_DP - 0.5f))
        assertTrue(railSplitsDevices(RailDensity.Regular, SPLIT_RAIL_FROM_DP))
        assertFalse(railSplitsDevices(RailDensity.Compact, SPLIT_COMPACT_RAIL_FROM_DP - 0.5f))
        assertTrue(railSplitsDevices(RailDensity.Compact, SPLIT_COMPACT_RAIL_FROM_DP))
    }

    /**
     * The split rail at its threshold: Settings, the last item, is drawn whole
     * (the scrolling item column doesn't clip it) and ends above the DE1 pip.
     */
    private fun assertFitsAt(height: Float, compact: Boolean) {
        rail(height.dp)
        item("Grinder", compact).assertExists()
        val settings = item("Settings", compact)
        val shown = settings.getBoundsInRoot()
        val whole = settings.getUnclippedBoundsInRoot()
        val pipLabelTop = compose.onNodeWithText("DE1", useUnmergedTree = true).getBoundsInRoot().top
        assertTrue(whole.bottom > whole.top, "Settings has a size")
        assertEquals(whole, shown, "Settings isn't clipped by the scrolling item column")
        assertTrue(whole.bottom <= pipLabelTop, "Settings (${whole.bottom}) ends above the DE1 pip ($pipLabelTop)")
    }

    @Test
    fun `the regular rail fits all seven items at its split height`() = assertFitsAt(SPLIT_RAIL_FROM_DP, compact = false)

    @Test
    fun `the regular rail with short items fits all seven at its split height`() {
        assertFitsAt(SPLIT_SHORT_RAIL_FROM_DP, compact = false)
        val h = item("Grinder", false).getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue(h.value <= SHORT_RAIL_ITEM_DP + 0.5f, "a short item is $h")
    }

    @Test
    fun `the compact rail fits all seven items at its split height`() =
        assertFitsAt(SPLIT_COMPACT_RAIL_FROM_DP, compact = true)

    @Test
    fun `a regular rail under the split height keeps one Devices item`() {
        rail((SPLIT_SHORT_RAIL_FROM_DP - 1f).dp)
        compose.onNodeWithText("Devices").assertExists()
        compose.onNodeWithText("Grinder").assertDoesNotExist()
    }

    @Test
    fun `a compact rail under its split height keeps one Devices item`() {
        rail((SPLIT_COMPACT_RAIL_FROM_DP - 1f).dp)
        compose.onNodeWithContentDescription("Devices").assertExists()
        compose.onNodeWithContentDescription("Grinder").assertDoesNotExist()
    }

    @Test
    fun `Grinder opens Devices on its tab and lights alone`() {
        rail(800.dp)
        selectable("Brew", false).assertIsSelected()
        item("Grinder", false).performClick()
        compose.waitForIdle()
        assertEquals(listOf("scale"), navs)
        assertEquals("grinder", tab)
        selectable("Grinder", false).assertIsSelected()
        selectable("Scale", false).assertIsNotSelected()
        selectable("Brew", false).assertIsNotSelected()

        item("Scale", false).performClick()
        compose.waitForIdle()
        assertEquals(listOf("scale", "scale"), navs)
        assertEquals("scale", tab)
        selectable("Scale", false).assertIsSelected()
        selectable("Grinder", false).assertIsNotSelected()
    }

    @Test
    fun `switching the tab inside Devices moves the highlight`() {
        active = "scale"
        rail(800.dp)
        selectable("Scale", false).assertIsSelected()
        compose.runOnIdle { tab = "grinder" }
        selectable("Grinder", false).assertIsSelected()
        selectable("Scale", false).assertIsNotSelected()
    }

    @Test
    fun `the single Devices item opens the last tab and lights on either`() {
        tab = "grinder"
        rail(600.dp)
        item("Devices", false).performClick()
        compose.waitForIdle()
        assertEquals(listOf("scale"), navs)
        assertEquals("grinder", tab, "the remembered tab is kept")
        selectable("Devices", false).assertIsSelected()
    }

    @Test
    fun `compact split items open their tab too`() {
        rail(SPLIT_COMPACT_RAIL_FROM_DP.dp)
        item("Grinder", true).performClick()
        compose.waitForIdle()
        assertEquals("grinder", tab)
        selectable("Grinder", true).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }
}
