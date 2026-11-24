package coffee.crema.ui

import coffee.crema.settings.GrinderSnapshot
import coffee.crema.settings.MirroredSettings
import coffee.crema.settings.Setting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G3b (grinder-ble §6.2, owner decision 5): a remote shows the hub's
 * grinder read-only. While the hub follows it, Brew says "from Motto 80 on
 * ‹hub›" and the grind dial is locked with the words the hub would refuse it
 * with; otherwise the dial is the remote's as before.
 */
class RemoteGrinderFollowTest {
    private fun remote(follow: String?): MainUiState {
        val snapshot = coreJson.decodeFromString(
            coffee.crema.settings.ConfigSnapshot.serializer(),
            coffee.crema.ble.proxy.Golden.require("config-snapshot/schema.json"),
        ).copy(grinder = follow?.let { GrinderSnapshot(connected = true, model = "Motto 80", follow = it, setting = "480", grinderName = "Motto 80") })
        return MainUiState(mirror = MirroredSettings(hubName = "Kitchen tablet").applied(snapshot, null, null), mirroringPrimaryName = "Kitchen tablet")
    }

    @Test
    fun `the hub's following grinder shows and locks the remote's dial`() {
        val ui = remote("Following")
        assertEquals("from Motto 80 on Kitchen tablet", ui.grindFollowNote())
        val gate = ui.gate(Setting.QC_GRIND)
        assertEquals(false, gate.editable)
        assertEquals("The grind follows Motto 80 on Kitchen tablet", gate.note)
        assertTrue(ui.grindHeaderText().endsWith("· from Motto 80 on Kitchen tablet"), ui.grindHeaderText())
    }

    @Test
    fun `paused or no grinder leaves the dial to the remote`() {
        for (ui in listOf(remote("Paused"), remote(null))) {
            assertNull(ui.grindFollowNote())
            assertTrue(ui.gate(Setting.QC_GRIND).note != "The grind follows Motto 80 on Kitchen tablet")
        }
    }
}
