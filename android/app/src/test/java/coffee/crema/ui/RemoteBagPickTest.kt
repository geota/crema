package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.BagSelection
import coffee.crema.core.SelectionChange
import coffee.crema.core.SelectionNotice
import coffee.crema.core.SelectionNoticeKind
import coffee.crema.core.SetTo
import coffee.crema.ui.RemoteFixture.Companion.BEAN_P
import coffee.crema.ui.RemoteFixture.Companion.PROFILE_P
import coffee.crema.ui.RemoteFixture.Companion.PROFILE_S
import coffee.crema.ui.RemoteFixture.Companion.S
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A remote's bag pick (#488 review F6, REVIEW-FINAL-2 SF4): the hub answers
 * the relayed pick with the core's selection, and the remote that picked
 * shows its message and notices as they are, with its own Undo, which goes
 * back to the hub as ordinary commands. A real remote against a test hub.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RemoteBagPickTest {
    @org.junit.Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    private val store = ViewModelStore()
    private val fixture = RemoteFixture(store)

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
        fixture.kill()
    }

    /** The hub answers `setActiveBean` with [selection], first pushing [after] (what the pick changed there). */
    private fun hubAnswers(selection: BagSelection, after: coffee.crema.settings.ConfigSnapshot?) {
        fixture.dispatch = { method, _ ->
            if (method == "setActiveBean") {
                // On the hub's thread, before the reply, as a hub pushes it.
                after?.let {
                    fixture.hubConfig = coreJson.encodeToString(coffee.crema.settings.ConfigSnapshot.serializer(), it)
                    fixture.hub.pushConfig(fixture.hubConfig)
                }
                Result.success(coreJson.encodeToString(BagSelection.serializer(), selection))
            } else {
                Result.success(null)
            }
        }
    }

    @Test
    fun `the remote shows the hub's answer with its own Undo, which goes back to the hub`() {
        val vm = fixture.start()
        fixture.push(S.copy(rev = 1))
        val selection = BagSelection(
            change = SelectionChange(profileId = SetTo(PROFILE_P), grind = SetTo("18")),
            undo = SelectionChange(profileId = SetTo(PROFILE_S), grind = SetTo("15")),
            changed = true,
            message = "P bag: Blooming profile, grind 18",
        )
        hubAnswers(selection, after = S.copy(activeBeanId = BEAN_P, activeProfileId = PROFILE_P, qcGrind = "18", rev = 2))
        vm.setActiveBean(BEAN_P)
        VmHarness.idleUntil("the hub's answer") { vm.ui.value.bagSelectUndo != null }
        val undo = assertNotNull(vm.ui.value.bagSelectUndo)
        assertEquals("P bag: Blooming profile, grind 18", undo.message, "the core's words, as the hub sent them")
        val before = fixture.handled.size
        vm.undoBagSelection(undo.seq)
        VmHarness.idleUntil("the Undo at the hub") { fixture.handled.size >= before + 2 }
        VmHarness.idle()
        assertEquals(listOf("setActiveProfile" to PROFILE_S, "setQcGrind" to "15"), fixture.handled.drop(before))
        assertNull(vm.ui.value.bagSelectUndo)
    }

    @Test
    fun `a pick that changed nothing shows the hub's notice and no Undo`() {
        val vm = fixture.start()
        fixture.push(S.copy(rev = 1))
        val notice = "P bag’s linked profile no longer exists"
        hubAnswers(
            BagSelection(
                change = SelectionChange(),
                undo = SelectionChange(),
                changed = false,
                notices = listOf(SelectionNotice(SelectionNoticeKind.LinkedProfileMissing, notice)),
            ),
            after = null,
        )
        vm.setActiveBean(BEAN_P)
        VmHarness.idleUntil("the hub's notice") { notice in vm.ui.value.userMessages }
        VmHarness.idle()
        assertTrue(notice in vm.ui.value.userMessages)
        assertNull(vm.ui.value.bagSelectUndo, "nothing changed, nothing to undo")
    }
}
