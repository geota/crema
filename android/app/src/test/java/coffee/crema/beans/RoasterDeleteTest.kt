package coffee.crema.beans

import coffee.crema.core.RoasterDeletePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The roaster delete (web `RoasterDeleteSplit`): applying the core's
 * `plan_roaster_delete` to the library (detach vs cascade, active-bag cleanup)
 * and when "also delete on Visualizer" is offered. The plan itself is pinned
 * by the core tests; the native core isn't loadable on the JVM, so the plans
 * here are hand-built the way the core shapes them.
 */
class RoasterDeleteTest {
    private val sey = newRoaster("Sey", 1).copy(id = "roaster:sey", visualizerId = "vz-r")
    private val onyx = newRoaster("Onyx", 1).copy(id = "roaster:onyx")
    private fun bag(id: String, roaster: String?, vid: String? = null) =
        newBean(id, roaster, null, null, 1).copy(id = id, visualizerId = vid)
    private val a = bag("bean:a", sey.id, vid = "vz-a")
    private val b = bag("bean:b", sey.id)
    private val c = bag("bean:c", onyx.id)
    private val lib = BeanLibrary(beans = listOf(a, b, c), roasters = listOf(sey, onyx), activeBeanId = a.id)

    @Test
    fun `detach keeps the bags, clears their roaster and stamps them`() {
        val plan = RoasterDeletePlan(sey.id, emptyList(), listOf(a.id, b.id), emptyList(), "vz-r")
        val out = applyRoasterDelete(lib, plan, nowMs = 99)
        assertEquals(listOf(onyx.id), out.roasters.map { it.id })
        assertEquals(listOf(a.id, b.id, c.id), out.beans.map { it.id })
        assertTrue(out.beans.filter { it.id != c.id }.all { it.roasterId == null && it.updatedAt == 99L })
        assertEquals(onyx.id, out.beans.single { it.id == c.id }.roasterId)
        assertEquals(a.id, out.activeBeanId)
    }

    @Test
    fun `cascade deletes the bags and clears an active bag that went with them`() {
        val plan = RoasterDeletePlan(sey.id, listOf(a.id, b.id), emptyList(), listOf("vz-a"), "vz-r")
        val out = applyRoasterDelete(lib, plan, nowMs = 99)
        assertEquals(listOf(c.id), out.beans.map { it.id })
        assertEquals(listOf(onyx.id), out.roasters.map { it.id })
        assertNull(out.activeBeanId)
    }

    @Test
    fun `the Visualizer option shows only when the chosen scope has a synced copy`() {
        // Sey is synced itself.
        assertTrue(roasterRemoteDeleteAvailable(sey, lib.beans, cascade = false))
        // Onyx isn't, and neither is its bag.
        assertFalse(roasterRemoteDeleteAvailable(onyx, lib.beans, cascade = true))
        // An unsynced roaster with a synced bag: only the cascade has something to delete remotely.
        val local = sey.copy(visualizerId = null)
        assertFalse(roasterRemoteDeleteAvailable(local, lib.beans, cascade = false))
        assertTrue(roasterRemoteDeleteAvailable(local, lib.beans, cascade = true))
    }
}
