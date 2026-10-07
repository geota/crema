package coffee.crema.ble.companion

import coffee.crema.ble.LurkPolicy
import coffee.crema.ble.chooseLurkPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Companion-device bookkeeping on top of a [CompanionGateway]: which
 * remembered devices are associated, keeping presence observation running
 * for them, removing an association, and the lurk policy that follows from
 * association + presence. Every gateway call is guarded — a misbehaving CDM
 * degrades to "not associated" (PR A's foreground kick + the pending connect
 * still cover the device), never a crash.
 */
class CompanionCoordinator(
    private val gateway: CompanionGateway,
    private val presence: StateFlow<Map<String, Boolean>>,
) {
    val isSupported: Boolean get() = runCatching { gateway.isSupported }.getOrDefault(false)

    private val _associated = MutableStateFlow<Map<String, Int?>>(emptyMap())

    /** Address → id of this app's associations, as last read from the system. */
    val associated: StateFlow<Map<String, Int?>> = _associated.asStateFlow()

    /** Re-read the system's associations (the user may have removed one in
     *  system settings) and (re)start presence observation for every one that
     *  belongs to a remembered device. Returns the current associations. */
    fun refresh(remembered: Collection<String?>): Map<String, Int?> {
        if (!isSupported) return emptyMap<String, Int?>().also { _associated.value = it }
        val now = runCatching { gateway.associations() }.getOrDefault(emptyMap())
            .mapKeys { normalizeAddress(it.key) }
        _associated.value = now
        val wanted = remembered.filterNotNull().map(::normalizeAddress).toSet()
        now.filterKeys { it in wanted }.forEach { (address, id) ->
            runCatching { gateway.startObserving(address, id) }
        }
        return now
    }

    fun isAssociated(address: String?): Boolean =
        address != null && normalizeAddress(address) in _associated.value

    /** The id stored for [address] (null pre-API 33 or when not associated). */
    fun associationId(address: String): Int? = _associated.value[normalizeAddress(address)]

    /** The system dialog created an association: record it and observe presence. */
    fun onAssociated(address: String, id: Int?): Boolean {
        val a = normalizeAddress(address)
        _associated.value = _associated.value + (a to id)
        return runCatching { gateway.startObserving(a, id) }.getOrDefault(false)
    }

    /** Stop observing and remove the association. */
    fun remove(address: String) {
        val a = normalizeAddress(address)
        val id = _associated.value[a]
        runCatching { gateway.stopObserving(a, id) }
        runCatching { gateway.disassociate(a, id) }
        _associated.value = _associated.value - a
        CompanionPresence.forget(a)
    }

    /** Last presence report for [address]: true / false / null (none yet). */
    fun presenceOf(address: String?): Boolean? = address?.let { presence.value[normalizeAddress(it)] }

    /** The lurk policy for the remembered device at [address]. */
    fun lurkPolicy(address: String?, pendingSupported: Boolean): LurkPolicy = chooseLurkPolicy(
        cdmUsable = isSupported,
        associated = isAssociated(address),
        present = presenceOf(address),
        pendingSupported = pendingSupported,
    )
}
