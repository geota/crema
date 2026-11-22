package coffee.crema.ui

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
    fun `the marker shows only when Brew's grind differs from the bag's grind for the active grinder`() {
        val b = bag(listOf(BagGrind(niche.id, niche.name, "4.2", starred = false)))
        val base = MainUiState(beans = rowViewOf(listOf(b)), activeBeanId = "b", grinders = rowViewOf(listOf(niche)), activeGrinderId = niche.id)
        assertNull(base.brewGrindView().marker, "no grind")
        assertNull(base.copy(qcGrind = "4.2").brewGrindView().marker, "same value")
        assertNull(base.copy(qcGrind = "4.20").brewGrindView().marker, "same number")
        assertEquals("Bag 4.2", base.copy(qcGrind = "4.3").brewGrindView().marker?.label)
        assertEquals("4.3", base.copy(qcGrind = "4.3").quickSavePlan().grind, "Save's grind part is the core's")
        assertEquals("Bag —", base.copy(qcGrind = "4.3", activeGrinderId = null).brewGrindView().marker?.label, "nothing saved for no grinder")
        assertNull(base.copy(qcGrind = "4.3", activeBeanId = null).brewGrindView().marker, "no bag, no marker")
    }

    /** REVIEW-FINAL S1 / S10, DESIGN "No grind set is its own state". */
    @Test
    fun `an unset grind is blank everywhere, with the bag's saved grind too, and starts mid-scale`() {
        val b = bag(listOf(BagGrind(motto.id, motto.name, "480", starred = true)))
        val s = MainUiState(beans = rowViewOf(listOf(b)), activeBeanId = "b", grinders = rowViewOf(listOf(motto)), activeGrinderId = motto.id)
        val view = s.brewGrindView()
        assertNull(view.value, "the shot records none")
        assertEquals("Grind —", view.headerText(), "the header doesn't claim the bag's 480")
        assertEquals("Grind —", coffee.crema.ui.phone.beanSpecLine(s), "nor the phone strip")
        assertNull(view.marker)
        assertNull(s.quickSavePlan().grind)
        assertEquals("480", view.saved, "the saved grind is still there for the ring and the tooltip")
        assertEquals(coffee.crema.ui.components.StepperBlank("—", 500.0), view.stepperBlank(), "Quick Controls: blank, the first step lands on 500 (no 4.2 seed)")
        assertTrue(coffee.crema.beans.grindChips(view.scale, view.value).isEmpty(), "no chips around nothing")
        val noGrinder = MainUiState().brewGrindView()
        assertEquals(10.0, noGrinder.startFromBlank, "the default scale's middle")
        assertEquals("Grind —", noGrinder.headerText())
    }

    @Test
    fun `a set grind reads the same in the header, the strip, the stepper and the shot`() {
        val s = MainUiState(grinders = rowViewOf(listOf(motto)), activeGrinderId = motto.id, qcGrind = "480")
        val view = s.brewGrindView()
        assertEquals("480", view.value)
        assertEquals("Grind 480 μm", view.headerText())
        assertEquals("Grind 480 μm", coffee.crema.ui.phone.beanSpecLine(s))
        assertEquals(480.0, view.dial)
        assertNull(view.stepperBlank())
    }

    /** REVIEW-FINAL N2, the core's text-grind rule. */
    @Test
    fun `a grind that isn't a plain number is Brew's grind, shown as typed`() {
        val b = bag(listOf(BagGrind(niche.id, niche.name, "2.1.3", starred = false)))
        val s = MainUiState(beans = rowViewOf(listOf(b)), activeBeanId = "b", grinders = rowViewOf(listOf(motto, niche)), activeGrinderId = niche.id, qcGrind = "2.1.3")
        val view = s.brewGrindView()
        assertEquals("2.1.3", view.value)
        assertEquals("Grind 2.1.3", view.headerText(), "no unit after text")
        assertNull(view.marker, "same as the bag's, as text")
        assertEquals(coffee.crema.ui.components.StepperBlank("2.1.3", 25.0), view.stepperBlank(), "no number: steps start mid-scale")
        assertEquals("Bag 2.1.3", s.copy(qcGrind = "2.1.4").brewGrindView().marker?.label)
    }

    @Test
    fun `chips are single steps around Brew's grind, inside the scale`() {
        assertEquals(listOf(478.0, 479.0, 480.0, 481.0, 482.0), coffee.crema.beans.grindChips(motto.scale, "480").map { it.value })
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0, 4.0), coffee.crema.beans.grindChips(niche.scale, "0").map { it.value }, "pinned at the bottom")
        assertEquals(listOf("4", "4.1", "4.2", "4.3", "4.4"), coffee.crema.beans.grindChips(coffee.crema.beans.DEFAULT_GRIND_SCALE, "4.2").map { it.text })
        assertTrue(coffee.crema.beans.grindChips(niche.scale, "2.1.3").isEmpty(), "none around text")
    }

    /** REVIEW-FINAL M2: one formatter on the scale's step, no one-decimal rounding. */
    @Test
    fun `a stepped grind is text on the active scale`() {
        val fine = Grinder(id = "grinder:fine", name = "Fine", scale = GrindScale(0f, 10f, 0.25f, ""), updatedAt = 1L)
        val s = MainUiState(grinders = rowViewOf(listOf(fine)), activeGrinderId = fine.id)
        // What the stepper's setQcGrind sends: the number as text on the active scale.
        fun stepped(state: MainUiState, v: Float) =
            coffee.crema.machine.MachineCommand.SetGrind(coffee.crema.beans.grindText(v.toDouble(), state.activeGrindScale())).applyTo(state).qcGrind
        assertEquals("2.25", stepped(s, 2.25f), "not 2.2 or 2.3")
        assertEquals("4.2", stepped(MainUiState(), 4.2f), "no float noise")
        assertEquals("15", stepped(MainUiState(), 15f), "not 15.0")
        assertEquals("2.1.3", coffee.crema.machine.MachineCommand.SetGrind("2.1.3").applyTo(s).qcGrind, "a text grind as it is")
    }

    @Test
    fun `grinds compare as numbers, else as text`() {
        assertTrue(coffee.crema.beans.sameGrind("14", "14.0"))
        assertFalse(coffee.crema.beans.sameGrind("14", "15"))
        assertFalse(coffee.crema.beans.sameGrind("2.5.3", "2.5.4"))
    }

    @Test
    fun `Devices refuses a scale by the core's rule`() {
        assertEquals("Enter a number in each field", coffee.crema.beans.scaleRefusal(null, 10f, 1f))
        assertEquals("Lowest must be below Highest", coffee.crema.beans.scaleRefusal(10f, 10f, 1f))
        assertEquals("Step must be more than 0", coffee.crema.beans.scaleRefusal(0f, 10f, 0f))
        assertEquals("Step must fit between Lowest and Highest", coffee.crema.beans.scaleRefusal(0f, 10f, 11f))
        assertNull(coffee.crema.beans.scaleRefusal(0f, 999f, 1f, "μm"))
    }

    @Test
    fun `the newest grind per grinder on a bag, by id and by name`() {
        fun shot(id: String, at: Long, grind: String?, gid: String?, name: String?, bean: String = "b") =
            StoredShot(id = id, completedAtMs = at, durationMs = 1L, grindSetting = grind, grinderId = gid, grinderModel = name, bean = ShotBean(beanId = bean, name = "x"))
        val out = lastGrindsForBag(
            "b",
            listOf(
                shot("1", 1L, "14", niche.id, "Niche Zero"),
                shot("2", 3L, "15", niche.id, "Niche Zero"),
                shot("3", 2L, "30", null, "DF64"),
                shot("4", 4L, "99", niche.id, "Niche Zero", bean = "other"),
                shot("5", 5L, null, motto.id, "Motto 80"),
                shot("6", 6L, "18 clicks", df64.id, "DF64"),
            ),
        )
        assertEquals(listOf("18 clicks" to df64.id, "15" to niche.id, "30" to null), out.map { it.grind to it.grinderId }, "a text grind counts as typed")
    }

    /** #488 follow-up 38: the screens show Brew's grinder, never the retired settings `grinderModel`. */
    @Test
    fun `no screen shows the settings grinder model`() {
        val shown = coffee.crema.MainSources.kotlinFiles()
            .filter { (path, _) -> path.startsWith("coffee/crema/ui/screens/") || path.startsWith("coffee/crema/ui/phone/") }
            .flatMap { (path, text) -> text.lines().mapIndexedNotNull { i, line -> if (Regex("""\bui\.grinderModel\b""").containsMatchIn(line)) "$path:${i + 1}" else null } }
        assertEquals(emptyList(), shown)
        assertEquals("Brew uses Niche Zero. Your grinders and their grind scales are on Devices.", grindersSettingsLine(niche.name))
        assertEquals("No grinder on Brew. Your grinders and their grind scales are on Devices.", grindersSettingsLine(null))
    }

    /** Review 499: a catalogue entry with no verified range says where to set it. */
    @Test
    fun `catalogue offers say whether the range comes with it`() {
        // #501: every listed entry has a scale, verified or typical; hidden
        // entries (names only) are never in the list.
        val listed = coffee.crema.beans.grinderCatalogue
        assertTrue(listed.all { it.scale != null })
        val typical = listed.filter { it.tier == coffee.crema.core.ScaleTier.Typical }.map { it.id }
        assertTrue("comandante-c40" in typical && "fellow-opus" in typical, "typical ranges are still offered: $typical")
        // #501: a typical range says to check the dial, where a verified one comes with its scale.
        listed.forEach {
            val line = if (it.id in typical) "Adds it to your grinders · Typical range — check your dial" else "Adds it to your grinders, with its scale"
            assertEquals(line, catalogueOfferLine(it.id), it.id)
        }
        assertTrue(listed.none { it.id == "df64" }, "a hidden entry is never offered")
        assertEquals("Adds it to your grinders · set its range on Devices", catalogueOfferLine("df64"))
    }

    @Test
    fun `Devices labels a typical preset and a verified one (#501)`() {
        val catalogue = coffee.crema.beans.grinderCatalogue
        val typical = catalogue.first { it.id == "comandante-c40" }
        val verified = catalogue.first { it.tier == coffee.crema.core.ScaleTier.Verified && it.scale != null }
        assertEquals("Typical range — check your dial", presetLabel(typical))
        assertEquals("From the ${verified.name} preset", presetLabel(verified))
    }

    @Test
    fun `a thirds scale shows its step as a third and reads one typed (#488)`() {
        val ode = coffee.crema.beans.grinderCatalogue.first { it.id == "fellow-ode" }.scale!!
        assertTrue(coffee.crema.beans.countsInThirds(ode))
        assertEquals("⅓", scaleStepField(ode))
        assertEquals("⅓", coffee.crema.beans.grindStepText(ode))
        assertEquals("0.1", scaleStepField(GrindScale(0f, 20f, 0.1f, "")))
        assertEquals("0.05", coffee.crema.beans.grindStepText(GrindScale(0f, 10f, 0.05f, "")))
        // The field's "⅓" reads back as the stored step: nothing changed.
        assertEquals(ode.step, scaleFieldNumber("⅓"))
        assertEquals(ode.step, scaleFieldNumber("1/3"))
        assertEquals(0.5f, scaleFieldNumber("0,5"))
        assertNull(scaleFieldNumber("a"))
    }

    @Test
    fun `grinds read through the core, thirds as numbers and exponents or huge values as text (#488)`() {
        val third = 1.0 / 3.0
        assertEquals(4 + third, coffee.crema.beans.grindNumber("4⅓"))
        assertEquals(4 + third, coffee.crema.beans.grindNumber("4 1/3"))
        assertEquals(4 + 2 * third, coffee.crema.beans.grindNumber(" 4⅔ "))
        assertEquals(2.5, coffee.crema.beans.grindNumber("2,5"))
        listOf("41/3", "2.1.3", "1e3", "1e999999999", "1000001", "Infinity", "", "0." + "1".repeat(31)).forEach {
            assertNull(coffee.crema.beans.grindNumber(it), it)
        }
        assertNull(coffee.crema.beans.grindNumber(null))
        val ode = coffee.crema.beans.grinderCatalogue.first { it.id == "fellow-ode" }.scale!!
        assertEquals("4⅓", coffee.crema.beans.grindText(coffee.crema.beans.grindNumber("4 1/3")!!, ode))
    }

    @Test
    fun `a grind field opens the full keyboard only on a thirds scale`() {
        val ode = coffee.crema.beans.grinderCatalogue.first { it.id == "fellow-ode" }.scale!!
        assertTrue(coffee.crema.beans.grindFullKeyboard(ode))
        assertEquals(androidx.compose.ui.text.input.KeyboardType.Text, coffee.crema.ui.components.editKeyboardType(coffee.crema.beans.grindFullKeyboard(ode)))
        for (scale in listOf(coffee.crema.beans.DEFAULT_GRIND_SCALE, GrindScale(0f, 999f, 1f, "μm"), GrindScale(0f, 10f, 0.05f, ""))) {
            assertFalse(coffee.crema.beans.grindFullKeyboard(scale), scale.toString())
            assertEquals(androidx.compose.ui.text.input.KeyboardType.Decimal, coffee.crema.ui.components.editKeyboardType(coffee.crema.beans.grindFullKeyboard(scale)))
        }
    }

    @Test
    fun `a thirds stepper lands on thirds`() {
        val ode = coffee.crema.beans.grinderCatalogue.first { it.id == "fellow-ode" }.scale!!
        val step = ode.step.toDouble()
        val places = coffee.crema.ui.components.stepperPlaces(step)
        assertEquals(6, places)
        val up = coffee.crema.ui.components.roundTo(4.0 + step, places)
        assertEquals("4⅓", coffee.crema.beans.grindText(up, ode))
        assertEquals("4⅔", coffee.crema.beans.grindText(coffee.crema.ui.components.roundTo(up + step, places), ode))
        // A step on the 0.01 grid keeps today's two decimals.
        assertEquals(2, coffee.crema.ui.components.stepperPlaces(0.1))
        assertEquals(2, coffee.crema.ui.components.stepperPlaces(1.0))
        assertEquals(2, coffee.crema.ui.components.stepperPlaces(0.05))
        assertEquals(18.1, coffee.crema.ui.components.roundTo(18.0 + 0.1, 2))
    }

    @Test
    fun `a held stepper speeds up to a floor`() {
        var wait = REPEAT_FIRST_MS
        repeat(40) { wait = nextRepeatMs(wait) }
        assertEquals(REPEAT_FASTEST_MS, wait)
        assertTrue(nextRepeatMs(REPEAT_FIRST_MS) < REPEAT_FIRST_MS)
    }
}
