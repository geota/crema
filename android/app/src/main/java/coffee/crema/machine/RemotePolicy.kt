package coffee.crema.machine

/**
 * Whether a remote sends this command to its hub (spec §2): the shot dials,
 * grind, stop-at-weight and auto-tare, profile and bean selection, and every
 * machine operation. The Quick Controls steam, hot-water and flush values are
 * machine settings, read-only on a remote: it doesn't send them. Exhaustive,
 * so a new command has to answer.
 *
 * A hub still accepts the commands a remote no longer sends, from older
 * remotes ([RelayedCommandDispatcher] logs them).
 */
fun MachineCommand.Relayable.sentByRemotes(): Boolean = when (this) {
    MachineCommand.StartShot, is MachineCommand.StateRequest, MachineCommand.CancelMaintenance, MachineCommand.TareScale -> true
    is MachineCommand.SetActiveProfile, is MachineCommand.SetActiveBean, is MachineCommand.SetStopOnWeight,
    is MachineCommand.SetAutoTare, is MachineCommand.AdjustBrew, MachineCommand.ResetBrew -> true
    is MachineCommand.SetQc -> field.sentByRemotes()
}

/** Grind is a shot dial; the steam, hot-water and flush values are machine settings. */
fun QcField.sentByRemotes(): Boolean = when (this) {
    QcField.GRIND -> true
    QcField.STEAM_TIME, QcField.STEAM_FLOW, QcField.STEAM_TEMP, QcField.HOT_WATER_TEMP,
    QcField.HOT_WATER_VOLUME, QcField.FLUSH_TIME, QcField.FLUSH_TEMP -> false
}
