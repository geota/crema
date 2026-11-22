package coffee.crema.ui

import coffee.crema.beans.grindDiffers
import coffee.crema.beans.lastGrindsForBag
import coffee.crema.beans.newBean
import coffee.crema.core.BagGrind
import coffee.crema.core.GrindScale
import coffee.crema.core.Grinder
import coffee.crema.core.LastGrind
import coffee.crema.core.ShotBean
import coffee.crema.history.StoredShot
import coffee.crema.ui.components.REPEAT_FASTEST_MS
import coffee.crema.ui.components.REPEAT_FIRST_MS
import coffee.crema.ui.components.nextRepeatMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Brew reads of grinders and bag grinds (#488): picker rows, the marker, chips. */
class GrinderStateTest {
    private val motto = Grinder(id = "grinder:motto", name = "Motto 80", scale = GrindScale(0f, 999f, 1f, "μm"), updatedAt = 1L)
    private val niche = Grinder(id = "grinder:niche", name = "Niche Zero", scale = GrindScale(0f, 50f, 1f, ""), updatedAt = 1L)
    private val df64 = Grinder(id = "grinder:df64", name = "DF64", scale = GrindScale(0f, 90f, 1f, ""), updatedAt = 1L)

    private fun bag(grinds: List<BagGrind>?) = newBean("Ethiopia Guji", null, null, null, 0L).copy(id = "b", grinds = grinds)

    @Test
    fun `picker rows say what each grinder has for the bag`() {
        val b = bag(listOf(BagGrind(niche.id, niche.name, "14", starred = true), BagGrind(motto.id, motto.name, "480", starred = false)))
        val rows = grinderPickRows(listOf(niche, motto, df64), b, listOf(LastGrind(grinderId = null, grinder = "df64", grind = "32", completedAt = 5L)))
        assertEquals(listOf("This bag's grinder · saved 14", "Saved 480 μm", "Nothing saved · last shot 32"), rows.map { it.meta })
        assertEquals(listOf(true, false, false), rows.map { it.starred })
        assertEquals("0–999 μm", grinderPickRows(listOf(motto), null, emptyList()).single().meta, "no bag: the range")
    }

    @Test
    fun `the marker shows only when the dial differs from the bag's grind for the active grinder`() {
        val b = bag(listOf(BagGrind(niche.id, niche.name, "4.2", starred = false)))
        val base = MainUiState(beans = rowViewOf(listOf(b)), activeBeanId = "b", grinders = rowViewOf(listOf(niche)), activeGrinderId = niche.id)
        assertNull(base.unsavedGrind(), "no dial")
        assertNull(base.copy(qcGrind = 4.2f).unsavedGrind(), "same value")
        assertEquals("Bag 4.2", base.copy(qcGrind = 4.3f).unsavedGrind()?.label)
        assertEquals("Bag —", base.copy(qcGrind = 4.3f, activeGrinderId = null).unsavedGrind()?.label, "nothing saved for no grinder")
        assertNull(base.copy(qcGrind = 4.3f, activeBeanId = null).unsavedGrind(), "no bag, no marker")
        assertEquals(4.2, base.grindSeed())
    }

    @Test
    fun `grinds compare as numbers, else as text`() {
        assertFalse(grindDiffers("14", "14.0"))
        assertTrue(grindDiffers("14", "15"))
        assertTrue(grindDiffers("2.5.3", "2.5.4"))
        assertFalse(grindDiffers(null, "4"))
    }

    @Test
    fun `chips sit around the value inside the scale`() {
        assertEquals(listOf(478.0, 479.0, 480.0, 481.0, 482.0), grindChips(480.0, motto.scale))
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0, 4.0), grindChips(0.0, niche.scale), "pinned at the bottom")
        assertEquals(listOf(4.0, 4.1, 4.2, 4.3, 4.4), grindChips(4.2, coffee.crema.beans.DEFAULT_GRIND_SCALE))
    }

    @Test
    fun `the newest grind per grinder on a bag, by id and by name`() {
        fun shot(id: String, at: Long, grind: Float?, gid: String?, name: String?, bean: String = "b") =
            StoredShot(id = id, completedAtMs = at, durationMs = 1L, grindSetting = grind, grinderId = gid, grinderModel = name, bean = ShotBean(beanId = bean, name = "x"))
        val out = lastGrindsForBag(
            "b",
            listOf(
                shot("1", 1L, 14f, niche.id, "Niche Zero"),
                shot("2", 3L, 15f, niche.id, "Niche Zero"),
                shot("3", 2L, 30f, null, "DF64"),
                shot("4", 4L, 99f, niche.id, "Niche Zero", bean = "other"),
                shot("5", 5L, null, motto.id, "Motto 80"),
            ),
        )
        assertEquals(listOf("15" to niche.id, "30" to null), out.map { it.grind to it.grinderId })
    }

    @Test
    fun `a held stepper speeds up to a floor`() {
        var wait = REPEAT_FIRST_MS
        repeat(40) { wait = nextRepeatMs(wait) }
        assertEquals(REPEAT_FASTEST_MS, wait)
        assertTrue(nextRepeatMs(REPEAT_FIRST_MS) < REPEAT_FIRST_MS)
    }
}
