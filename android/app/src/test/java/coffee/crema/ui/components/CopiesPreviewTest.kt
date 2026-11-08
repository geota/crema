package coffee.crema.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lowering the Drive copies (#311 review): the count comes from today's
 * listing, and the next backup adds a copy that can push one more out, so the
 * preview never promises an exact count or that nothing goes.
 */
class CopiesPreviewTest {
    @Test fun aCountIsAtLeast() {
        assertTrue(copiesPreviewBody(3, 2).contains("at least 2 older automatic copies"))
        assertTrue(copiesPreviewBody(3, 1).contains("at least 1 older automatic copy "))
    }

    @Test fun noneTodayDoesntPromiseNothingGoes() {
        assertFalse(copiesPreviewBody(3, 0).contains("Nothing is deleted"))
        assertTrue(copiesPreviewBody(3, 0).contains("may delete the oldest"))
    }
}
