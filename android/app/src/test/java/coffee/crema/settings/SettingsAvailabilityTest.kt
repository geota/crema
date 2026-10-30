package coffee.crema.settings

import coffee.crema.ble.proxy.Golden
import coffee.crema.machine.MachineCommand
import coffee.crema.machine.QcField
import coffee.crema.ui.MainUiState
import coffee.crema.ui.SettingAvailability
import coffee.crema.ui.SettingAvailability.EDITABLE
import coffee.crema.ui.SettingAvailability.OFFLINE
import coffee.crema.ui.SettingAvailability.PENDING
import coffee.crema.ui.SettingAvailability.READ_ONLY
import coffee.crema.ui.SettingAvailability.UNCONFIRMED
import coffee.crema.ui.SettingAvailability.UNKNOWN
import coffee.crema.ui.SettingAvailability.VIA_HUB
import coffee.crema.ui.SettingAvailability.VIEW_ONLY
import coffee.crema.ui.availability
import coffee.crema.ui.availabilityNote
import coffee.crema.ui.components.RowGate
import coffee.crema.ui.coreJson
import coffee.crema.ui.gate
import coffee.crema.ui.resetPreferencesTitle
import coffee.crema.ui.withEchoes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * AC-29: `availability()` returns the spec's §7.6 table for every setting in
 * every role, including the scale rows A16–A21 (UNKNOWN until the scale's
 * echo arrives; A19 and A20 always), and the row notes that go with it.
 */
class SettingsAvailabilityTest {

    private val v4 = coreJson.decodeFromString(ConfigSnapshot.serializer(), Golden.require("config-snapshot/schema.json"))

    /** A hub that hasn't read its machine registers yet. */
    private val unread = v4.copy(heaterVoltage = null, flowMultiplier = null, cupWarmerTempC = null)

    /** The scale settings a Bookoo's weight stream reports (A16–A18, A21). */
    private val echoed = setOf(Setting.SCALE_VOLUME, Setting.SCALE_STANDBY, Setting.SCALE_FLOW_SMOOTHING, Setting.SCALE_AUTO_STOP)

    private fun remote(snapshot: ConfigSnapshot? = v4, scale: Set<Setting> = echoed) = MainUiState(
        proxyRole = "secondary", mirroringPrimaryName = "Kitchen tablet", scaleEchoed = scale,
        mirror = MirroredSettings(snapshot = snapshot, hubName = "Kitchen tablet"),
    )

    /** Whether a remote knows [s], from the registry's mirror source alone. */
    private fun known(s: Setting, snapshot: ConfigSnapshot?, scale: Set<Setting>) = when (s.mirroredVia) {
        MirrorSource.NONE -> true
        MirrorSource.SNAPSHOT -> snapshot != null && snapshot.carries(s)
        MirrorSource.SCALE_ECHO -> s in scale
        MirrorSource.NONE_AVAILABLE -> false
    }

    /** The §7.6 table, row by row. */
    private fun expected(s: Setting, viewOnly: Boolean, reconnecting: Boolean, snapshot: ConfigSnapshot?, scale: Set<Setting>): SettingAvailability = when {
        s.group == SettingGroup.C_PERSONAL -> EDITABLE
        !known(s, snapshot, scale) -> UNKNOWN
        s.onRemote == OnRemote.READ_ONLY -> READ_ONLY
        viewOnly -> VIEW_ONLY
        reconnecting -> OFFLINE
        else -> VIA_HUB
    }

    @Test
    fun `everything is editable on a normal device or a hub`() {
        for (role in listOf("normal", "primary")) {
            val ui = MainUiState(proxyRole = role)
            for (s in Setting.entries) {
                assertEquals(EDITABLE, ui.availability(s), "$role $s")
                assertNull(ui.availabilityNote(s))
                assertSame(RowGate.Open, ui.gate(s))
            }
        }
    }

    @Test
    fun `a control remote follows the table`() {
        val ui = remote()
        for (s in Setting.entries) assertEquals(expected(s, false, false, v4, echoed), ui.availability(s), "$s")
        // Spot checks against the table's words, independent of the helper above.
        assertEquals(VIA_HUB, ui.availability(Setting.QC_GRIND))
        assertEquals(VIA_HUB, ui.availability(Setting.STOP_ON_WEIGHT))
        assertEquals(READ_ONLY, ui.availability(Setting.QC_STEAM_TEMP))
        assertEquals(READ_ONLY, ui.availability(Setting.MAX_SHOT_DURATION))
        assertEquals(READ_ONLY, ui.availability(Setting.SCALE_VOLUME))
        assertEquals(EDITABLE, ui.availability(Setting.WEIGHT_UNIT))
        assertEquals("Change on Kitchen tablet", ui.availabilityNote(Setting.QC_STEAM_TEMP))
        assertNull(ui.availabilityNote(Setting.QC_GRIND))
        assertEquals(RowGate(editable = false, unknown = false, note = "Change on Kitchen tablet"), ui.gate(Setting.STEAM_ECO))
        assertEquals(RowGate(editable = true, unknown = false, note = null), ui.gate(Setting.AUTO_TARE))
    }

    @Test
    fun `a view-only remote can't edit relayed settings`() {
        val ui = remote().copy(mirrorViewOnly = true)
        for (s in Setting.entries) assertEquals(expected(s, true, false, v4, echoed), ui.availability(s), "$s")
        assertEquals(VIEW_ONLY, ui.availability(Setting.AUTO_TARE))
        assertEquals(READ_ONLY, ui.availability(Setting.PRE_FLUSH))
        assertEquals(false, ui.gate(Setting.AUTO_TARE).editable)
        assertEquals("View only — Kitchen tablet hasn’t allowed changes from this device", ui.availabilityNote(Setting.AUTO_TARE))
    }

    @Test
    fun `a reconnecting remote shows relayed settings offline`() {
        for (ui in listOf(remote().copy(mirrorReconnecting = true), remote().let { it.copy(mirror = it.mirror!!.reconnectingNow()) })) {
            for (s in Setting.entries) assertEquals(expected(s, false, true, v4, echoed), ui.availability(s), "$s")
            assertEquals(OFFLINE, ui.availability(Setting.QC_GRIND))
            assertEquals(READ_ONLY, ui.availability(Setting.QC_STEAM_TIME))
            assertEquals(false, ui.gate(Setting.QC_GRIND).editable)
            assertEquals("Reconnecting to Kitchen tablet…", ui.availabilityNote(Setting.QC_GRIND))
        }
    }

    @Test
    fun `pending and unconfirmed edits`() {
        val (slice, gen) = MirroredSettings(snapshot = v4, hubName = "Kitchen tablet").withPending(MachineCommand.SetQc(QcField.GRIND, 18f))
        val (both, _) = slice.withPending(MachineCommand.SetAutoTare(false))
        val ui = remote().copy(mirror = both.settled(Setting.QC_GRIND, gen, coffee.crema.machine.CommandOutcome.Unknown("no reply")))
        assertEquals(PENDING, ui.availability(Setting.AUTO_TARE))
        assertEquals("Sending to Kitchen tablet…", ui.availabilityNote(Setting.AUTO_TARE))
        assertEquals(UNCONFIRMED, ui.availability(Setting.QC_GRIND))
        assertEquals("Kitchen tablet didn’t answer — this may not have been applied", ui.availabilityNote(Setting.QC_GRIND))
        assertEquals(true, ui.gate(Setting.QC_GRIND).editable, "an unconfirmed value can be edited again")
        assertEquals(VIA_HUB, ui.availability(Setting.STOP_ON_WEIGHT))
    }

    @Test
    fun `before the first snapshot every machine and session setting is unknown`() {
        val ui = remote(snapshot = null, scale = emptySet())
        for (s in Setting.entries) {
            assertEquals(if (s.group == SettingGroup.C_PERSONAL) EDITABLE else UNKNOWN, ui.availability(s), "$s")
        }
        assertEquals("Waiting for Kitchen tablet", ui.availabilityNote(Setting.STOP_ON_WEIGHT))
        assertEquals(RowGate(editable = false, unknown = true, note = "Waiting for Kitchen tablet"), ui.gate(Setting.QC_STEAM_TEMP))
    }

    /** Relay v4 R3b: only the machine registers a hub hasn't read can be
     *  missing, and they wait for the hub (no "update the hub" copy). */
    @Test
    fun `unread machine registers are unknown, each on its own row`() {
        val ui = remote(snapshot = unread)
        val missing = Setting.entries.filter { it.mirroredVia == MirrorSource.SNAPSHOT && !unread.carries(it) }
        assertEquals(setOf(Setting.HEATER_VOLTAGE, Setting.FLOW_MULTIPLIER, Setting.CUP_WARMER_TEMP), missing.toSet())
        for (s in Setting.entries) assertEquals(expected(s, false, false, unread, echoed), ui.availability(s), "$s")
        for (s in missing) {
            assertEquals(UNKNOWN, ui.availability(s))
            assertEquals("Waiting for Kitchen tablet", ui.availabilityNote(s), "$s")
        }
        assertEquals(VIA_HUB, ui.availability(Setting.STOP_ON_WEIGHT), "a key it carries")
    }

    /** A16–A21: the scale's own settings, which no snapshot carries. */
    @Test
    fun `scale rows are unknown until their echo, and A19 and A20 always`() {
        val none = remote(scale = emptySet())
        for (s in echoed) {
            assertEquals(UNKNOWN, none.availability(s), "$s before its echo")
            assertEquals("Waiting for Kitchen tablet", none.availabilityNote(s))
        }
        val volumeOnly = remote(scale = emptySet<Setting>().withEchoes(volume = true, standby = false, smoothing = false, autoStop = false))
        assertEquals(READ_ONLY, volumeOnly.availability(Setting.SCALE_VOLUME))
        assertEquals(UNKNOWN, volumeOnly.availability(Setting.SCALE_STANDBY))
        val all = remote()
        for (s in echoed) assertEquals(READ_ONLY, all.availability(s), "$s after its echo")
        for (ui in listOf(none, all, all.copy(mirrorViewOnly = true))) {
            for (s in listOf(Setting.SCALE_ANTI_MISTOUCH, Setting.SCALE_MODE)) {
                assertEquals(UNKNOWN, ui.availability(s), "$s")
                assertEquals("Change on Kitchen tablet", ui.availabilityNote(s))
            }
        }
    }

    @Test
    fun `scale echoes accumulate without churning the state`() {
        val one = emptySet<Setting>().withEchoes(volume = true, standby = false, smoothing = true, autoStop = false)
        assertEquals(setOf(Setting.SCALE_VOLUME, Setting.SCALE_FLOW_SMOOTHING), one)
        assertSame(one, one.withEchoes(volume = true, standby = false, smoothing = false, autoStop = false), "nothing new: the same set")
        assertEquals(echoed, one.withEchoes(volume = false, standby = true, smoothing = false, autoStop = true))
    }

    /** §7.6: on a remote, Reset resets only this device's own preferences, and says so. */
    @Test
    fun `reset preferences names whose they are`() {
        assertEquals("Reset preferences", resetPreferencesTitle(MainUiState()))
        assertEquals("Reset preferences", resetPreferencesTitle(MainUiState(proxyRole = "primary")))
        assertEquals("Reset this device's preferences", resetPreferencesTitle(remote()))
    }
}
