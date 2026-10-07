package coffee.crema.ble.companion

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The process-wide companion-device presence hub. [CremaCompanionService]
 * reports here (the system binds it — starting the process if needed — when
 * an associated device appears or disappears); the connection controller
 * reads [presence] for the lurk policy and collects [events] to kick a
 * reconnect. Process-scoped because the service and the ViewModel have
 * independent lifetimes: a report that arrives before any ViewModel exists
 * (the process was started by the presence bind) is still in [presence] when
 * the app opens.
 */
object CompanionPresence {
    data class Event(val address: String, val present: Boolean)

    private val _presence = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Last report per normalised address. Missing = no report yet (unknown). */
    val presence: StateFlow<Map<String, Boolean>> = _presence.asStateFlow()

    private val _events = MutableSharedFlow<Event>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Each report, as it arrives. */
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun report(address: String, present: Boolean) {
        val a = normalizeAddress(address)
        _presence.update { it + (a to present) }
        _events.tryEmit(Event(a, present))
    }

    /** Forget a device's presence (its association was removed). */
    fun forget(address: String) {
        val a = normalizeAddress(address)
        _presence.update { it - a }
    }
}
