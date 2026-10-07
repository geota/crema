package coffee.crema.ble.companion

/**
 * Crema's slice of Android's CompanionDeviceManager (CDM), behind an
 * interface so the decision logic ([CompanionCoordinator]) is JVM-testable
 * with a fake. The real one is [AndroidCompanionGateway].
 *
 * Addresses are normalised upper-case MACs ([normalizeAddress]). The
 * association id is null on Android 12 / 12L (API 31–32), where the platform
 * identifies an association only by address; from API 33 it is the
 * `AssociationInfo.id`.
 */
interface CompanionGateway {
    /** `FEATURE_COMPANION_DEVICE_SETUP` is present (the CDM can be used at all). */
    val isSupported: Boolean

    /** This app's current associations: address → id (null pre-API 33). */
    fun associations(): Map<String, Int?>

    /** Start presence observation; false if the platform refused (not associated …). */
    fun startObserving(address: String, id: Int?): Boolean

    /** Stop presence observation (best-effort). */
    fun stopObserving(address: String, id: Int?)

    /** Remove the association (best-effort). */
    fun disassociate(address: String, id: Int?)
}

/** Upper-case MAC, the one form addresses are compared in. */
fun normalizeAddress(address: String): String = address.trim().uppercase()

/** Which remembered device an address belongs to. */
enum class CompanionDevice { DE1, SCALE }

/** The device a presence report is about, or null when it's neither remembered one. */
fun presenceTarget(address: String, rememberedDe1: String?, rememberedScale: String?): CompanionDevice? {
    val a = normalizeAddress(address)
    return when (a) {
        rememberedDe1?.let(::normalizeAddress) -> CompanionDevice.DE1
        rememberedScale?.let(::normalizeAddress) -> CompanionDevice.SCALE
        else -> null
    }
}

/** What a presence report asks of the reconnect machinery. */
enum class PresenceAction {
    /** Appeared: reconnect that device now (trigger `presence`). */
    KICK,

    /** Disappeared: nothing now — its lurk tier idles until it appears again. */
    IDLE,

    /** Not a remembered device, or it was disconnected by the user. */
    IGNORE,
}

/** Pure presence → action rule (pinned by `CompanionCoordinatorTest`). */
fun presenceAction(target: CompanionDevice?, present: Boolean, userDisconnected: Boolean): PresenceAction = when {
    target == null || userDisconnected -> PresenceAction.IGNORE
    present -> PresenceAction.KICK
    else -> PresenceAction.IDLE
}
