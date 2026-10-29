package coffee.crema.ui

import coffee.crema.core.WriteTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The profile-write generation rule (cohort review 2026-10-28, #2 N2 / #6 #3):
 * a header write opens a generation; a superseded or failed batch's writes are
 * neither sent nor acked; the live batch's first failure is reported once.
 */
class ProfileWriteGenerationsTest {

    private val upload = listOf(WriteTarget.De1ProfileHeader, WriteTarget.De1ProfileFrame, WriteTarget.De1ProfileFrame)

    @Test
    fun `an output without profile writes opens no batch`() {
        val g = ProfileWriteGenerations()
        assertNull(g.begin(listOf(WriteTarget.De1RequestedState, WriteTarget.De1MmrWrite)))
        assertEquals(0L, g.generation)
    }

    @Test
    fun `a new header supersedes the older batch`() {
        val g = ProfileWriteGenerations()
        val a = assertNotNull(g.begin(upload))
        assertEquals(ProfileWriteGenerations.Outcome.ACK, g.settle(a, true))
        val b = assertNotNull(g.begin(upload))
        assertFalse(g.mayWrite(a))
        assertEquals(ProfileWriteGenerations.Outcome.DROP, g.settle(a, true), "a late write of A acks nothing")
        assertEquals(ProfileWriteGenerations.Outcome.DROP, g.settle(a, false), "nor reports a failure")
        assertEquals(ProfileWriteGenerations.Outcome.ACK, g.settle(b, true))
    }

    @Test
    fun `the first failure stops the batch and is reported once`() {
        val g = ProfileWriteGenerations()
        val a = assertNotNull(g.begin(upload))
        assertEquals(ProfileWriteGenerations.Outcome.FAILED, g.settle(a, false))
        assertFalse(g.mayWrite(a))
        assertEquals(ProfileWriteGenerations.Outcome.DROP, g.settle(a, false))
        val b = assertNotNull(g.begin(upload))
        assertTrue(g.mayWrite(b), "the re-upload starts clean")
    }
}
