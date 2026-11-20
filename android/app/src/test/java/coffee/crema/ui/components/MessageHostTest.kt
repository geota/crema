package coffee.crema.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The temporary messages keep their meaning (brew messages PRD §2): the host
 * dismisses on timeout and on the ✕ through the same [androidx.compose.material3.SnackbarData.dismiss],
 * and the action through `performAction`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageHostTest {

    @Test
    fun `Undo on the aborted-shot message restores the shot`() = runTest {
        val host = SnackbarHostState()
        val calls = mutableListOf<String>()
        launch { host.showDiscardMessage("Shot discarded", onUndo = { calls += "undo" }, onDrop = { calls += "drop" }) }
        runCurrent()
        val data = assertNotNull(host.currentSnackbarData)
        assertEquals("Undo", data.visuals.actionLabel)
        assertEquals(SnackbarDuration.Long, data.visuals.duration)
        data.performAction()
        runCurrent()
        assertEquals(listOf("undo"), calls)
    }

    @Test
    fun `a timeout or the close button on the aborted-shot message drops the shot`() = runTest {
        val host = SnackbarHostState()
        val calls = mutableListOf<String>()
        launch { host.showDiscardMessage("Shot discarded", onUndo = { calls += "undo" }, onDrop = { calls += "drop" }) }
        runCurrent()
        // What both the host's timeout and the ✕ call.
        assertNotNull(host.currentSnackbarData).dismiss()
        runCurrent()
        assertEquals(listOf("drop"), calls)
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun `the kept-aside notice is pinned until Share or close`() = runTest {
        val host = SnackbarHostState()
        val calls = mutableListOf<String>()
        launch { host.showKeptAsideMessage("Kept aside", onShare = { calls += "share" }, onDismiss = { calls += "dismiss" }) }
        runCurrent()
        val data = assertNotNull(host.currentSnackbarData)
        assertEquals(SnackbarDuration.Indefinite, data.visuals.duration)
        assertNull(messageTimeoutMs(data.visuals, null), "pinned: no timeout, no countdown line")
        data.performAction()
        runCurrent()
        launch { host.showKeptAsideMessage("Kept aside", onShare = { calls += "share" }, onDismiss = { calls += "dismiss" }) }
        runCurrent()
        assertNotNull(host.currentSnackbarData).dismiss()
        runCurrent()
        assertEquals(listOf("share", "dismiss"), calls)
    }

    @Test
    fun `the countdown runs as long as Material's timeout`() {
        assertEquals(4_000L, messageTimeoutMs(CremaMessageVisuals("Saved"), null))
        assertEquals(10_000L, messageTimeoutMs(CremaMessageVisuals("x", "Undo", duration = SnackbarDuration.Long), null))
        // A plain showSnackbar(message) message is Short, as before.
        assertEquals(SnackbarDuration.Short, CremaMessageVisuals("Saved").duration)
    }
}
