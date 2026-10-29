package coffee.crema.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cohort review 02 P2-2: the DE1 writes of one `CoreOutput` run strictly in
 * order. Before, each write had its own coroutine, so an Idle bounce that
 * failed and was retried 500 ms later could land AFTER the Espresso request
 * that followed it, stopping the shot that just started.
 */
class De1WriteOrderTest {

    @Test
    fun `a retried write still lands before the next one`() = runTest {
        val landed = mutableListOf<String>()
        launch {
            executeInOrder(listOf("Idle", "Espresso")) { state ->
                if (state == "Idle") {
                    // First attempt fails; the one retry follows 500 ms later.
                    delay(500)
                }
                landed += state
            }
        }.join()
        assertEquals(listOf("Idle", "Espresso"), landed)
    }

    @Test
    fun `a failed write does not stop the rest of the batch`() = runTest {
        val landed = mutableListOf<Int>()
        executeInOrder(listOf(0, 1, 2)) { frame ->
            // deliverDe1Write logs failures and returns; model that here.
            runCatching { if (frame == 1) error("GATT failure") else landed += frame }
        }
        assertEquals(listOf(0, 2), landed)
    }
}
