package coffee.crema.ui.screens

import coffee.crema.ui.fmt
import coffee.crema.core.MaintenanceReadout
import coffee.crema.core.MaintenanceState

/**
 * One maintenance-reminder row's derived display values (issue 30) — the
 * filter/descale/clean "burn-down" math that `SettingsScreen` and
 * `PhoneSettingsScreen` used to inline verbatim.
 *
 * [value]/[unit] stay split so the tablet's two-slot `MaintenanceRow` renders
 * them as-is, while the phone's `PMaintRow` combines them; [pct] is 0..1
 * progress toward the interval (the bar fill); [due] means the counter is past
 * its threshold (the amber treatment).
 */
data class MaintenanceRowReadout(
    val note: String,
    val value: String,
    val unit: String,
    val pct: Float,
    val due: Boolean,
)

/*
 * Pure burn-down for the three maintenance reminders, shared by both settings
 * shells. The DE1 exposes no cumulative water counter, so the shell integrates
 * group flow into a persisted [MaintenanceState] and the core FFI derives a
 * [MaintenanceReadout]; these turn those two into each row's note/value/pct/due.
 * Usage + ok flags come from the readout (`this`); intervals/capacity from [m].
 * The "Awaiting data." / "—" fallback for a null readout stays at the call site.
 */

fun MaintenanceReadout.filterRow(m: MaintenanceState) = MaintenanceRowReadout(
    note = "${fmt("%.1f",  filterUsedLitres)} L of ${m.filterCapacityLitres.toInt()} L used",
    value = "${filterPercent.toInt()}",
    unit = "%",
    pct = (filterPercent / 100.0).toFloat(),
    due = !filterOk,
)

fun MaintenanceReadout.descaleRow(m: MaintenanceState) = MaintenanceRowReadout(
    note = "${fmt("%.0f",  descaleSinceLitres)} L since last descale · every ${m.descaleIntervalLitres.toInt()} L",
    value = fmt("%.0f",  descaleSinceLitres),
    unit = "L",
    pct = if (m.descaleIntervalLitres > 0.0) (descaleSinceLitres / m.descaleIntervalLitres).toFloat() else 0f,
    due = !descaleOk,
)

fun MaintenanceReadout.cleanRow(m: MaintenanceState) = MaintenanceRowReadout(
    // cleanSinceHours is a Long — interpolate, never %f.
    note = "$cleanSinceHours h since last clean · every ${m.cleanIntervalHours.toInt()} h",
    value = "$cleanSinceHours",
    unit = "h",
    pct = if (m.cleanIntervalHours > 0.0) (cleanSinceHours.toDouble() / m.cleanIntervalHours).toFloat() else 0f,
    due = !cleanOk,
)
