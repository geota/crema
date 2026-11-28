package coffee.crema.ui

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The local network gate (API 37+): who asks, when an action runs, and the denied states. */
class LocalNetworkGateTest {
    private var granted = false
    private val published = mutableListOf<LocalNetworkAccess>()
    private val messages = mutableListOf<String>()
    private var grantedCalls = 0

    private fun gate() = LocalNetworkGate(
        isGranted = { granted },
        publish = { published += it },
        notify = { messages += it },
        onGranted = { grantedCalls++ },
    )

    @Test
    fun `only API 37 and up needs the permission`() {
        assertContentEquals(emptyArray(), localNetworkPermissions(31))
        assertContentEquals(emptyArray(), localNetworkPermissions(36))
        assertContentEquals(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"), localNetworkPermissions(37))
        assertContentEquals(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"), localNetworkPermissions(38))
    }

    @Test
    fun `allowed already runs the action at once without asking`() {
        granted = true
        val g = gate()
        var ran = 0
        g.withAccess { ran++ }
        assertEquals(1, ran)
        assertFalse(g.requests.value)
        assertEquals(LocalNetworkAccess.GRANTED, g.access)
        assertEquals(listOf(LocalNetworkAccess.GRANTED), published)
        // Nothing failed earlier, so there is nothing to restart.
        assertEquals(0, grantedCalls)
    }

    @Test
    fun `not allowed asks, then runs the action once on yes`() {
        val g = gate()
        assertEquals(LocalNetworkAccess.NOT_ASKED, g.access)
        var ran = 0
        g.withAccess { ran++ }
        assertEquals(0, ran)
        assertTrue(g.requests.value)

        g.onRequestShown()
        assertFalse(g.requests.value)
        granted = true
        g.onResult(granted = true, canAskAgain = false)
        assertEquals(1, ran)
        assertEquals(LocalNetworkAccess.GRANTED, g.access)
        assertEquals(1, grantedCalls)
        assertTrue(messages.isEmpty())

        // A second answer (none was asked for) runs nothing again.
        g.onResult(granted = true, canAskAgain = false)
        assertEquals(1, ran)
        assertEquals(1, grantedCalls)
    }

    @Test
    fun `a soft no keeps the action from running and lets the user ask again`() {
        val g = gate()
        var ran = 0
        g.withAccess { ran++ }
        g.onRequestShown()
        g.onResult(granted = false, canAskAgain = true)
        assertEquals(0, ran)
        assertEquals(LocalNetworkAccess.DENIED, g.access)
        assertEquals(listOf(localNetworkDeniedMessage(LocalNetworkAccess.DENIED)), messages)
        assertEquals("Allow", localNetworkRowAction(g.access))

        // The picker's Allow asks again; a yes now runs nothing stale.
        g.ask()
        assertTrue(g.requests.value)
        granted = true
        g.onResult(granted = true, canAskAgain = false)
        assertEquals(0, ran)
        assertEquals(LocalNetworkAccess.GRANTED, g.access)
        assertEquals(1, grantedCalls)
    }

    @Test
    fun `a permanent no points to Settings`() {
        val g = gate()
        g.withAccess { error("must not run") }
        g.onRequestShown()
        g.onResult(granted = false, canAskAgain = false)
        assertEquals(LocalNetworkAccess.BLOCKED, g.access)
        assertTrue(messages.single().contains("Settings"))
        assertEquals("Open settings", localNetworkRowAction(g.access))
        assertTrue(localNetworkRowCopy(g.access).contains("Settings"))
    }

    @Test
    fun `a second tap while the dialog is up asks once and runs only the newest action`() {
        val g = gate()
        val ran = mutableListOf<String>()
        g.withAccess { ran += "hub" }
        g.onRequestShown()
        g.withAccess { ran += "pair" }
        // No second dialog while the first one is open.
        assertFalse(g.requests.value)
        granted = true
        g.onResult(granted = true, canAskAgain = false)
        assertEquals(listOf("pair"), ran)
    }

    @Test
    fun `an answer that says no while the grant is there counts as yes`() {
        val g = gate()
        var ran = 0
        g.withAccess { ran++ }
        g.onRequestShown()
        granted = true
        g.onResult(granted = false, canAskAgain = false)
        assertEquals(1, ran)
        assertEquals(LocalNetworkAccess.GRANTED, g.access)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `refresh follows a change made in Settings`() {
        val g = gate()
        g.ask()
        g.onRequestShown()
        g.onResult(granted = false, canAskAgain = false)
        assertEquals(LocalNetworkAccess.BLOCKED, g.access)

        // Allowed in Settings: NSD starts again.
        granted = true
        g.refresh()
        assertEquals(LocalNetworkAccess.GRANTED, g.access)
        assertEquals(1, grantedCalls)
        g.refresh()
        assertEquals(1, grantedCalls)

        // Revoked in Settings: the next LAN action asks again.
        granted = false
        g.refresh()
        assertEquals(LocalNetworkAccess.NOT_ASKED, g.access)
        g.withAccess {}
        assertTrue(g.requests.value)
    }

    @Test
    fun `refresh leaves a refusal as it is`() {
        val g = gate()
        g.ask()
        g.onRequestShown()
        g.onResult(granted = false, canAskAgain = true)
        g.refresh()
        assertEquals(LocalNetworkAccess.DENIED, g.access)
        assertEquals(
            listOf(LocalNetworkAccess.NOT_ASKED, LocalNetworkAccess.DENIED),
            published,
        )
    }

    @Test
    fun `the row names the missing access`() {
        assertEquals("Find other Crema devices", localNetworkRowTitle(LocalNetworkAccess.NOT_ASKED))
        assertEquals("Local network access is off", localNetworkRowTitle(LocalNetworkAccess.DENIED))
        assertEquals("Local network access is off", localNetworkRowTitle(LocalNetworkAccess.BLOCKED))
    }
}
