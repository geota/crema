package coffee.crema.machine

/**
 * Whether a remote sends this command to its hub (spec §2): the shot dials,
 * grind, stop-at-weight and auto-tare, profile and bean selection, and every
 * machine operation. The Quick Controls steam, hot-water and flush values are
 * machine settings, read-only on a remote: it doesn't send them, and a hub
 * doesn't decode them ([MachineCommandCodec] has no wire form for them).
 * Exhaustive, so a new command has to answer.
 */
fun MachineCommand.Relayable.sentByRemotes(): Boolean = when (this) {
    MachineCommand.StartShot, is MachineCommand.StateRequest, MachineCommand.CancelMaintenance, MachineCommand.TareScale -> true
    // Save writes the hub's own bag and profile (review F8).
    is MachineCommand.QuickSave -> true
    is MachineCommand.SetActiveProfile, is MachineCommand.SetActiveBean, is MachineCommand.SetActiveGrinder, is MachineCommand.SetStopOnWeight,
    is MachineCommand.SetAutoTare, is MachineCommand.AdjustBrew, MachineCommand.ResetBrew -> true
    // Grind is a shot dial, sent as text (#488 round 2).
    is MachineCommand.SetGrind -> true
    // The steam, hot-water and flush values are machine settings.
    is MachineCommand.SetQc -> false
}
