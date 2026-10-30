package coffee.crema.settings

import coffee.crema.MainSources
import coffee.crema.machine.CommandOutcome
import coffee.crema.machine.MachineCommand
import coffee.crema.machine.QcField
import coffee.crema.ui.MainUiState
import coffee.crema.ui.effective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The mirror slice and the effective projection, pure (spec §7.2, AC-33). */
class MirroredSettingsTest {

    private val own = MainUiState(stopOnWeight = false, qcGrind = 10f, qcSteamTempC = 140f, weightUnit = "oz", themeMode = "light")
    private val snap = v4Snapshot(own).copy(stopOnWeight = true, qcGrind = 15f, qcSteamTempC = 150f)

    @Test
    fun `effective is the identity without a slice`() {
        assertSame(own, own.effective())
    }

    @Test
    fun `effective is memoised on the source instance`() {
        val remote = own.copy(mirror = MirroredSettings(snapshot = snap))
        val first = remote.effective()
        assertSame(first, remote.effective(), "the same source projects to the same instance")
        val other = remote.copy(qcGrind = 11f)
        assertNotSame(first, other.effective())
    }

    @Test
    fun `the ui pipeline projects before the screensaver gate`() {
        val vm = MainSources.kotlinFiles().toMap().getValue("coffee/crema/ui/MainViewModel.kt")
        assertTrue(
            Regex("""val ui: StateFlow<MainUiState> = _ui\s*\.map \{ it\.effective\(\) }\s*\.distinctUntilChanged\(::hiddenBySaver\)""").containsMatchIn(vm),
            "ui = _ui.map { effective() }.distinctUntilChanged(::hiddenBySaver)…",
        )
    }

    @Test
    fun `a snapshot shows the hub's machine and session values and keeps personal ones`() {
        val shown = own.copy(mirror = MirroredSettings(snapshot = snap)).effective()
        assertEquals(true, shown.stopOnWeight)
        assertEquals(15f, shown.qcGrind)
        assertEquals(150f, shown.qcSteamTempC)
        assertEquals("oz", shown.weightUnit, "units are personal now")
        assertEquals("light", shown.themeMode)
    }

    @Test
    fun `a pending edit shows over the snapshot until it settles`() {
        val edit = MachineCommand.SetQc(QcField.GRIND, 18f)
        val (slice, gen) = MirroredSettings(snapshot = snap).withPending(edit)
        assertEquals(18f, own.copy(mirror = slice).effective().qcGrind)
        val accepted = slice.settled(Setting.QC_GRIND, gen, CommandOutcome.Accepted)
        assertEquals(15f, own.copy(mirror = accepted).effective().qcGrind, "accepted: the hub's value (pushed before its reply)")
        val refused = slice.settled(Setting.QC_GRIND, gen, CommandOutcome.Refused("no"))
        assertEquals(15f, own.copy(mirror = refused).effective().qcGrind)
    }

    @Test
    fun `only the latest edit of a key settles it`() {
        val (one, gen1) = MirroredSettings(snapshot = snap).withPending(MachineCommand.SetQc(QcField.GRIND, 16f))
        val (two, gen2) = one.withPending(MachineCommand.SetQc(QcField.GRIND, 17f))
        val stale = two.settled(Setting.QC_GRIND, gen1, CommandOutcome.Accepted)
        assertEquals(17f, own.copy(mirror = stale).effective().qcGrind, "an older answer doesn't drop the newer edit")
        assertEquals(15f, own.copy(mirror = stale.settled(Setting.QC_GRIND, gen2, CommandOutcome.Accepted)).effective().qcGrind)
    }

    @Test
    fun `an unanswered edit stays as unconfirmed until the next snapshot`() {
        val (slice, gen) = MirroredSettings(snapshot = snap).withPending(MachineCommand.SetStopOnWeight(false))
        val unknown = slice.settled(Setting.STOP_ON_WEIGHT, gen, CommandOutcome.Unknown("no reply"))
        assertEquals(setOf(Setting.STOP_ON_WEIGHT), unknown.unconfirmed)
        assertEquals(false, own.copy(mirror = unknown).effective().stopOnWeight, "kept on screen")
        val next = unknown.applied(snap, null, null)
        assertTrue(next.unconfirmed.isEmpty() && next.pending.isEmpty())
        assertEquals(true, own.copy(mirror = next).effective().stopOnWeight, "the next snapshot shows the hub's value")
    }

    @Test
    fun `a snapshot doesn't override an edit still awaiting its answer`() {
        val (slice, _) = MirroredSettings(snapshot = snap).withPending(MachineCommand.SetQc(QcField.GRIND, 18f))
        val next = slice.applied(snap.copy(qcGrind = 12f), null, null)
        assertEquals(18f, own.copy(mirror = next).effective().qcGrind)
    }

    @Test
    fun `a reconnect makes pending edits unconfirmed, never resent`() {
        val (slice, _) = MirroredSettings(snapshot = snap).withPending(MachineCommand.SetAutoTare(false))
        val dropped = slice.reconnectingNow()
        assertTrue(dropped.reconnecting)
        assertEquals(setOf(Setting.AUTO_TARE), dropped.unconfirmed)
        assertEquals(slice.pending, dropped.pending, "kept on screen")
    }

    /** AC-42: a snapshot at or below the last applied rev is stale until a
     *  new Welcome. Every v4 snapshot has a rev; none always applies. */
    @Test
    fun staleRevDropped() {
        val slice = MirroredSettings().applied(snap.copy(rev = 5), null, null)
        assertTrue(slice.isStale(5) && slice.isStale(4))
        assertTrue(!slice.isStale(6))
        assertTrue(!slice.welcomed("Kitchen tablet").isStale(1), "a restarted hub counts from 0 again")
    }
}
