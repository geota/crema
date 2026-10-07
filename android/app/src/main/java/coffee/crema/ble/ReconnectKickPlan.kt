package coffee.crema.ble

/**
 * What a reconnect kick (foreground, "Retry now", Bluetooth on, presence)
 * should do for one remembered device — the pure decision behind
 * [coffee.crema.ui.ConnectionController.kickReconnect], pinned by
 * `ReconnectKickPlanTest`.
 */
enum class KickAction {
    /** Leave it alone: not remembered / user-disconnected / READY / mid-handshake. */
    NONE,

    /** Interrupt the live session's backoff / lurk wait ([ReconnectKicker]). */
    KICK_SESSION,

    /** No live session: start a fresh scan-free direct connect by address. */
    DIRECT_CONNECT,
}

/** Coarse device state, collapsed from the two managers' identical enums. */
enum class LinkState { IDLE, SCANNING, CONNECTING, READY, DISCONNECTED }

/**
 * @param remembered the device has a remembered address (its Auto-connect is ON).
 * @param userDisconnected the user ended the session — it must stay ended.
 * @param phase the reconnect supervisor's phase (IDLE = no session).
 * @param link the manager's coarse state.
 * @param scanAgeMs how long a cold-start scan want for it has been pending,
 *   or null when none is.
 */
fun planKick(
    remembered: Boolean,
    userDisconnected: Boolean,
    phase: ReconnectKicker.Phase,
    link: LinkState,
    scanAgeMs: Long?,
): KickAction = when {
    !remembered || userDisconnected -> KickAction.NONE
    phase == ReconnectKicker.Phase.CONNECTED -> KickAction.NONE
    // The kicker itself turns a mid-attempt kick into a burst reset (never a
    // second attempt), so both live-session phases go to it.
    phase == ReconnectKicker.Phase.WAITING ||
        phase == ReconnectKicker.Phase.ATTEMPTING ||
        phase == ReconnectKicker.Phase.RETRYING -> KickAction.KICK_SESSION
    // No session from here on.
    link == LinkState.READY || link == LinkState.CONNECTING -> KickAction.NONE
    // A cold-start scan that only just started gets its chance; one that has
    // been parked a while (throttled in the background, or downgraded to
    // opportunistic after 30 min) is replaced by a direct connect.
    link == LinkState.SCANNING && scanAgeMs != null && scanAgeMs < FRESH_SCAN_MS -> KickAction.NONE
    else -> KickAction.DIRECT_CONNECT
}

/** A scan want younger than this is left to run (see [planKick]). */
const val FRESH_SCAN_MS = 10_000L

fun De1BleManager.State.toLinkState(): LinkState = when (this) {
    De1BleManager.State.IDLE -> LinkState.IDLE
    De1BleManager.State.SCANNING -> LinkState.SCANNING
    De1BleManager.State.CONNECTING,
    De1BleManager.State.DISCOVERING,
    De1BleManager.State.SUBSCRIBING -> LinkState.CONNECTING
    De1BleManager.State.READY -> LinkState.READY
    De1BleManager.State.DISCONNECTED -> LinkState.DISCONNECTED
}

fun ScaleBleManager.State.toLinkState(): LinkState = when (this) {
    ScaleBleManager.State.IDLE -> LinkState.IDLE
    ScaleBleManager.State.SCANNING -> LinkState.SCANNING
    ScaleBleManager.State.CONNECTING,
    ScaleBleManager.State.DISCOVERING,
    ScaleBleManager.State.SUBSCRIBING -> LinkState.CONNECTING
    ScaleBleManager.State.READY -> LinkState.READY
    ScaleBleManager.State.DISCONNECTED -> LinkState.DISCONNECTED
}

/**
 * Whether to show a device as "reconnecting" (with a "Retry now" action):
 * remembered, not ended by the user, not READY, and either a live session is
 * between re-attempts / mid re-attempt (not a plain first connect) or a
 * cold-start scan is hunting for it.
 */
fun showsReconnecting(
    remembered: Boolean,
    userDisconnected: Boolean,
    phase: ReconnectKicker.Phase,
    link: LinkState,
): Boolean = remembered && !userDisconnected && link != LinkState.READY && (
    phase == ReconnectKicker.Phase.WAITING ||
        phase == ReconnectKicker.Phase.RETRYING ||
        link == LinkState.SCANNING
    )
