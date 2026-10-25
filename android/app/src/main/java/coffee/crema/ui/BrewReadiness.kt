package coffee.crema.ui

import coffee.crema.core.MachineState

/**
 * What the Brew hero may claim about the machine. One rule for every surface
 * that says "ready": a link is not ready until the DE1 has *told us* its
 * state. A GATT link that came up at the edge of range can discover and
 * subscribe and then answer nothing — the connect-time StateInfo read times
 * out and the state stays unknown. Reading that as "Ready to brew" was the
 * reported bug: a hollow link presented as a working machine. Mirrors the
 * web, where `de1State` only becomes `ready` once the state seed read lands.
 */
enum class BrewReadiness {
    /** No DE1 link. */
    NOT_CONNECTED,
    /** Linked and subscribed, but the DE1 has not reported a state yet. */
    SYNCING,
    ASLEEP,
    HEATING,
    READY,
}

fun brewReadiness(connected: Boolean, state: MachineState?, substate: String?): BrewReadiness = when {
    !connected -> BrewReadiness.NOT_CONNECTED
    state == null -> BrewReadiness.SYNCING
    state == MachineState.Sleep -> BrewReadiness.ASLEEP
    substate == "Heating" -> BrewReadiness.HEATING
    else -> BrewReadiness.READY
}
