package coffee.crema.ui

import coffee.crema.core.MachineRequestDrop
import coffee.crema.core.MachineRequestRefusal
import coffee.crema.core.MachineState

/** One sentence the user can act on, for `Event.MachineRequestRefused` (F16;
 *  web twin `lib/state/machine-requests.ts`). */
internal fun machineRequestRefusalText(state: MachineState, reason: MachineRequestRefusal): String =
    when (reason) {
        is MachineRequestRefusal.MachineNotIdle ->
            "${state.string} needs the DE1 idle or asleep (it is in ${reason.details.state.string})."
        MachineRequestRefusal.MachineStateUnknown ->
            "Wait for the DE1 to report its state, then start ${state.string} again."
    }

/** Whether the Settings "Run now" buttons may start a cycle: the core's rule
 *  (Idle or Sleep), the one `request_machine_state` enforces (F16). */
internal fun MainUiState.maintenanceRunAllowed(): Boolean =
    coffee.crema.core.maintenanceRequestAllowed(machineStateName?.string)

/** Shown while a request the user made waits for the profile to load. */
internal const val REQUEST_HELD_TEXT = "Waiting for the profile to load\u2026"

/** One sentence for `Event.MachineRequestDropped`: why the request never
 *  went out (web twin `machineRequestDropText`). */
internal fun machineRequestDropText(state: MachineState, reason: MachineRequestDrop): String =
    when (reason) {
        is MachineRequestDrop.StateChanged ->
            if (reason.details.to == MachineState.Espresso) "${state.string} cancelled: a shot started"
            else "${state.string} cancelled: the machine moved on (it is in ${reason.details.to.string})"
        MachineRequestDrop.TookTooLong -> "${state.string} cancelled: the profile is still loading \u2014 try again"
    }
