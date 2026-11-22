package coffee.crema.ui

import coffee.crema.beans.newBean
import coffee.crema.core.BagGrind
import coffee.crema.core.GrindScale
import coffee.crema.core.Grinder
import coffee.crema.core.Roaster
import coffee.crema.core.SelectionChange
import coffee.crema.core.SetTo
import coffee.crema.settings.MirroredSettings
import coffee.crema.settings.v4Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A remote's own library against the hub's (#488 batch 7): Brew and a
 * remote's mirror copy of the hub's shot read the hub's bag (REVIEW-FINAL-2
 * N5), the controls that edit only this device's library say so (N4), and a
 * relayed Undo says what it couldn't send (#50).
 */
class RemoteLibraryTest {
    private val niche = Grinder(id = "grinder:niche-zero", name = "Niche Zero", scale = GrindScale(0f, 50f, 1f, ""), updatedAt = 1L)
    private val roaster = Roaster(id = "r", name = "Old Roaster", notes = "", metadata = kotlinx.serialization.json.JsonObject(emptyMap()), createdAt = 0L, updatedAt = 0L)

    /** This device's own copy of bag "b": an old name, its own roaster. */
    private val ownCopy = newBean("Old name", null, null, null, 0L).copy(id = "b", roasterId = "r")

    private fun own(): MainUiState = MainUiState(
        beans = rowViewOf(listOf(ownCopy)),
        roasters = rowViewOf(listOf(roaster)),
        grinders = rowViewOf(listOf(niche)),
    )

    /** This device as a remote of "Kitchen tablet", whose Brew has bag [bagId] named [bagName]. */
    private fun remote(bagId: String?, bagName: String?): MainUiState = own().copy(
        mirror = MirroredSettings(
            snapshot = v4Snapshot().copy(
                activeBeanId = bagId, activeBagName = bagName,
                activeBagGrind = BagGrind(niche.id, niche.name, "14", starred = true),
                activeGrinderId = niche.id, activeGrinderName = niche.name, activeGrinderScale = niche.scale,
                qcGrind = "15",
            ),
            hubName = "Kitchen tablet",
        ),
        mirroringPrimaryName = "Kitchen tablet",
    ).effective()

    @Test
    fun `a remote's shot names the hub's bag, never its own copy`() {
        val sel = shotSelections(remote("b", "New name"))
        val bean = assertNotNull(sel.bean)
        assertEquals("b", bean.beanId)
        assertEquals("New name", bean.name, "the hub's name, not this device's old copy")
        assertNull(bean.roasterName, "not this device's copy's roaster")
        assertEquals(niche.id, sel.grinderId)
        assertEquals("15", sel.grind)
    }

    @Test
    fun `a remote's shot names the hub's bag when this device lacks it`() {
        val bean = assertNotNull(shotSelections(remote("hub-only", "Hub bag")).bean)
        assertEquals("hub-only", bean.beanId)
        assertEquals("Hub bag", bean.name)
        assertNull(shotSelections(remote(null, null)).bean, "no bag on the hub, none on the shot")
    }

    @Test
    fun `a device on its own names its own bag, with its roaster`() {
        val bean = assertNotNull(shotSelections(own().copy(activeBeanId = "b")).bean)
        assertEquals("Old name", bean.name)
        assertEquals("Old Roaster", bean.roasterName)
    }

    @Test
    fun `a remote's own-library controls say Brew uses the hub's`() {
        val s = remote("b", "New name")
        assertEquals("Brew uses Kitchen tablet’s grinder and grind scale. These grinders are this device’s own.", s.remoteGrindersNote())
        assertTrue(s.remoteAutoSaveNote()!!.startsWith("Brew uses Kitchen tablet’s setting"), s.remoteAutoSaveNote())
        assertEquals("Brew uses Kitchen tablet’s saved grinds. These are this device’s own.", s.remoteBagGrindsNote())
        val alone = own()
        assertNull(alone.remoteGrindersNote())
        assertNull(alone.remoteAutoSaveNote())
        assertNull(alone.remoteBagGrindsNote())
    }

    @Test
    fun `a relayed Undo says which parts the hub keeps`() {
        val hub = "Kitchen tablet"
        assertNull(relayedUndoLeftover(SelectionChange(profileId = SetTo("p"), grinderId = SetTo(null), grind = SetTo("15")), hub), "all of it went back")
        assertNull(relayedUndoLeftover(SelectionChange(), hub))
        assertEquals("Kitchen tablet keeps its profile: a remote’s Undo can’t clear it.", relayedUndoLeftover(SelectionChange(profileId = SetTo(null)), hub))
        assertEquals("Kitchen tablet keeps its grind: a remote’s Undo can’t clear it.", relayedUndoLeftover(SelectionChange(grind = SetTo(null), profileId = SetTo("p")), hub))
        assertEquals(
            "Kitchen tablet keeps its profile and grind: a remote’s Undo can’t clear them.",
            relayedUndoLeftover(SelectionChange(profileId = SetTo(null), grind = SetTo(null)), hub),
        )
    }

    /** REVIEW-FINAL-2 N3: the delete says how many bags lose their link. */
    @Test
    fun `the profile delete confirm says how many bags prefer it`() {
        val p = "profile:p"
        val bags = listOf(
            ownCopy.copy(id = "1", linkedProfileId = p),
            ownCopy.copy(id = "2", linkedProfileId = p),
            ownCopy.copy(id = "3", linkedProfileId = "other"),
            ownCopy.copy(id = "4", linkedProfileId = p, deletedAt = 5L),
        )
        assertEquals(2, bags.preferringProfileCount(p), "a deleted bag doesn't count")
        assertEquals("“Blooming” will be removed. 2 bags prefer it; their links will be cleared. This can’t be undone.", profileDeleteBody("Blooming", 2))
        assertEquals("“Blooming” will be removed. 1 bag prefers it; that link will be cleared. This can’t be undone.", profileDeleteBody("Blooming", 1))
        assertEquals("“Blooming” will be removed. This can’t be undone.", profileDeleteBody("Blooming", 0))
    }
}
