package coffee.crema.ui

import coffee.crema.beans.newBean
import coffee.crema.core.GrindScale
import coffee.crema.core.Grinder
import coffee.crema.ui.components.TypedValue
import coffee.crema.ui.components.typedValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Brew's grind field takes a typed text grind (#488 follow-up 28, the core's
 * text-grind rule N2): a typed value the core doesn't read as a number is
 * Brew's grind as typed, and typing on a text grind starts from its text.
 */
class TypedGrindTest {
    private val grind: (String) -> Double? = { coffee.crema.beans.grindNumber(it) }

    @Test
    fun `a typed grind is a number when the core reads one, else the text as typed`() {
        assertEquals(TypedValue.Number(14.5), typedValue("14,5", grind, acceptText = true))
        assertEquals(4.0 + 1.0 / 3, (typedValue("4 1/3", grind, acceptText = true) as TypedValue.Number).value, 1e-9)
        assertEquals(TypedValue.Text("2.1.3"), typedValue(" 2.1.3 ", grind, acceptText = true))
        assertEquals(TypedValue.Text("6 + a tooth"), typedValue("6 + a tooth", grind, acceptText = true))
        assertEquals(TypedValue.Text("1e3"), typedValue("1e3", grind, acceptText = true), "an exponent is text (REVIEW-513 2)")
        assertNull(typedValue("   ", grind, acceptText = true), "blank changes nothing")
        assertNull(typedValue("2.1.3", grind, acceptText = false), "a field without a text path drops it")
        assertNull(typedValue("abc", null, acceptText = true), "a plain number field never takes text")
    }

    @Test
    fun `typing on a text grind starts from it, on an unset one from the scale`() {
        val g = Grinder(id = "grinder:g", name = "G", scale = GrindScale(0f, 20f, 0.5f, ""), updatedAt = 1L)
        val bag = newBean("B", null, null, null, 0L).copy(id = "b")
        val s = MainUiState(beans = rowViewOf(listOf(bag)), activeBeanId = "b", grinders = rowViewOf(listOf(g)), activeGrinderId = g.id)
        val text = s.copy(qcGrind = "2.1.3").brewGrindView().stepperBlank()
        assertEquals("2.1.3", text?.text)
        assertEquals("2.1.3", text?.editText)
        val unset = s.brewGrindView().stepperBlank()
        assertEquals("—", unset?.text)
        assertNull(unset?.editText, "unset: the field starts from the scale's start")
        assertNull(s.copy(qcGrind = "14").brewGrindView().stepperBlank(), "a number has the stepper")
    }
}
