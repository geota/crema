package coffee.crema.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Profiles saved before the DE1's 20-step limit (cohort review 2026-10-28, #6 #6, #2 N6). */
class StepCapTest {

    @Test
    fun `a profile within the limit has no note or refusal and the editors stop adding at 20`() {
        assertNull(StepCap.note(20, limit = 20))
        assertNull(StepCap.refusal("Twenty", 20, limit = 20))
        assertTrue(StepCap.canAdd(19, limit = 20))
        assertFalse(StepCap.canAdd(20, limit = 20), "the phone editor's Add phase / Duplicate gate")
    }

    @Test
    fun `a 24-step profile says how many steps to remove`() {
        assertEquals(4, StepCap.excess(24, limit = 20))
        assertEquals("24 steps — the DE1 runs at most 20. Remove 4 to brew it.", StepCap.note(24, limit = 20))
        assertEquals(
            "“Long” has 24 steps; the DE1 runs at most 20. Remove 4 in the profile editor to brew it.",
            StepCap.refusal("Long", 24, limit = 20),
        )
    }
}
