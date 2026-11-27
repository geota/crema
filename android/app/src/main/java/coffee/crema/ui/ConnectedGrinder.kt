package coffee.crema.ui

import coffee.crema.beans.grinderCatalogue
import coffee.crema.beans.isLive
import coffee.crema.core.GrindSession
import coffee.crema.core.Grinder
import coffee.crema.core.GrinderLinkDeadReason

/*
 * The connected grinder as the Devices card shows it (SPEC grinder-ble §2.7,
 * §6.1, §7), and the pure rules behind it: the card's status, the "close the
 * vendor app first" messages, and which owned grinder record a connected
 * grinder is linked to. Read-only telemetry; no follow mode yet (G3).
 */

/** Where the connected-grinder link is, as the card says it. */
enum class GrinderLinkStatus {
    /** Nothing connected or wanted. */
    OFF,
    SCANNING,
    CONNECTING,
    /** Linked and handshaken; the first reading hasn't arrived. */
    WAITING,
    /** Readings are arriving. */
    LIVE,
    RECONNECTING,
    /** The scan found nothing. */
    NOT_FOUND,
    /** Connected but it never answered, or went quiet. */
    NO_ANSWER,
    /** It answered before, but [NO_DATA_GIVE_UP] reconnects in a row brought
     *  no reading: auto-reconnect stopped, the user reconnects. */
    NO_DATA,
    /** Its firmware is too old for the protocol. */
    UPDATE_FIRMWARE,
    /** A device with a grinder's name but not its service. */
    MISMATCH,
}

/** The connected-grinder slice of [MainUiState]. The 5 Hz readings live in
 *  [LiveTelemetry.grinderReading]. */
data class ConnectedGrinderUi(
    val status: GrinderLinkStatus = GrinderLinkStatus.OFF,
    /** The model id (`bookoo-motto-80`), once the core identified it. */
    val model: String? = null,
    /** The dial's unit from the grinder's capabilities ("μm"), or null. */
    val unit: String? = null,
    val firmware: String? = null,
    /** The serial's last four characters; the whole serial is never kept
     *  in the UI state, logged or persisted. */
    val serialTail: String? = null,
    /** What to tell the user (a "close the BOOKOO app" message), or null. */
    val message: String? = null,
    /** Several owned records could be this grinder: ask which (§6.1 rule 3). */
    val linkChoices: List<String> = emptyList(),
    /** No owned record for this model: offer to add one (§6.1 rule 4). */
    val offerAdd: Boolean = false,
    /** The last completed grind (not purge) this session. */
    val lastGrind: GrindSession? = null,
    /** Drops within [EARLY_DROP_MS] of connecting, this session (§2.7). */
    val earlyDrops: Int = 0,
)

/** After this many `NoFirstReading`s in a row on a grinder that answered
 *  before, stop reconnecting (web #551's `NO_DATA_GIVE_UP`): a link that comes
 *  back but never streams would otherwise recycle every 5–6 s forever. */
const val NO_DATA_GIVE_UP = 3

/** A drop this soon after connecting counts toward the firmware hint. */
const val EARLY_DROP_MS = 15_000L

/** The display name of a grinder model id: the catalogue's model, else the id. */
fun grinderModelName(model: String?): String =
    model?.let { id -> grinderCatalogue.firstOrNull { it.id == id }?.model ?: id } ?: "grinder"

/** The last four characters of a serial, for display. */
fun serialTail(serial: String?): String? = serial?.trim()?.takeIf { it.isNotEmpty() }?.takeLast(4)

/** SPEC §2.7's messages, by situation. [modelName] is "Motto 80". */
object GrinderMessages {
    fun notFound(modelName: String) =
        "Can't see your $modelName. Make sure it's on and not connected to the BOOKOO app or another phone, then try again."

    fun noAnswer(reason: GrinderLinkDeadReason, earlyDrops: Int): String {
        val base = when (reason) {
            GrinderLinkDeadReason.NoFirstReading ->
                "The grinder didn't answer. Close the BOOKOO app, turn the grinder off and on, and try again."
            GrinderLinkDeadReason.Silent -> "The grinder stopped answering. Reconnecting…"
            GrinderLinkDeadReason.Malformed -> "The grinder sent data Crema can't read. Reconnecting…"
        }
        return withRestartHint(base, earlyDrops)
    }

    fun updateFirmware(minimum: String) =
        "Update the grinder in the BOOKOO app (it needs firmware $minimum or later), then connect again."

    /** Repeated drops right after connect: the October 2026 firmware fix. */
    fun withRestartHint(base: String, earlyDrops: Int): String =
        if (earlyDrops >= 2) "$base If it keeps restarting, update its firmware in the BOOKOO app." else base

    const val NO_DATA =
        "Grinder not sending data. Crema stopped reconnecting: check it's on and not connected to another app, then reconnect."

    const val MISMATCH = "This doesn't look like a Motto 80."
    const val NOT_YOUR_LINKED = "This isn't your linked grinder. Choose which grinder it is."
}

/** What to do about the owned-record link when a grinder becomes ready (§6.1). */
sealed interface GrinderLinkPlan {
    /** Already linked to this live record. */
    data class Keep(val id: String) : GrinderLinkPlan

    /** Exactly one record fits: link it, and say so. */
    data class LinkNew(val id: String) : GrinderLinkPlan

    /** Several fit, or another grinder holds the link: ask. */
    data class Choose(val ids: List<String>, val notYours: Boolean) : GrinderLinkPlan

    /** None fits: offer to add one from the catalogue. */
    data object OfferAdd : GrinderLinkPlan
}

/**
 * Which owned grinder record the connected grinder at [address] is. The link
 * is per device, stored with the address it belongs to (decision 7: the
 * grinder's identity is kept like a scale's — by Bluetooth address — not as a
 * serial hash in library data):
 *  1. linked to this address and the record is live → keep it;
 *  2. a link to another grinder exists → ask (never silently follow a
 *     different grinder);
 *  3. exactly one live record from this model's catalogue entry → link it;
 *  4. several → ask; none → offer to add one.
 */
fun planGrinderLink(
    model: String,
    address: String,
    linkedAddress: String?,
    linkedId: String?,
    grinders: List<Grinder>,
): GrinderLinkPlan {
    val live = grinders.filter { it.isLive }
    if (linkedAddress.equals(address, ignoreCase = true) && linkedId != null && live.any { it.id == linkedId }) {
        return GrinderLinkPlan.Keep(linkedId)
    }
    val candidates = live.filter { it.presetId == model }.map { it.id }
    val otherLinked = linkedAddress != null && linkedId != null && !linkedAddress.equals(address, ignoreCase = true) &&
        live.any { it.id == linkedId }
    return when {
        otherLinked -> GrinderLinkPlan.Choose(if (candidates.isEmpty()) live.map { it.id } else candidates, notYours = true)
        candidates.size == 1 -> GrinderLinkPlan.LinkNew(candidates.single())
        candidates.size > 1 -> GrinderLinkPlan.Choose(candidates, notYours = false)
        else -> GrinderLinkPlan.OfferAdd
    }
}

/** The grinder record the connected grinder is linked to, when it's this one and live. */
fun MainUiState.linkedGrinder(): Grinder? {
    val id = grinderLinkedId ?: return null
    val address = connectedGrinderAddress ?: return null
    if (!address.equals(grinderLinkedAddress, ignoreCase = true)) return null
    return grinders.firstOrNull { it.id == id && it.isLive }
}

/** Catalogue models Crema connects to over Bluetooth (grinder-ble): today the Motto 80. */
val BLUETOOTH_GRINDER_MODELS: Set<String> = setOf(GrinderController.GRINDER_MODEL_FALLBACK)

/**
 * One of the user's grinders connects over Bluetooth: a live record of a
 * Bluetooth model, or the record a connected grinder is linked to. Never on a
 * remote, where the hub holds the grinder. Gates the "Show in Devices and the
 * rail" switch: someone with only a Niche Zero never sees it.
 */
val MainUiState.hasBluetoothGrinder: Boolean
    get() = proxyRole != "secondary" &&
        grinders.any { it.isLive && (it.presetId in BLUETOOTH_GRINDER_MODELS || it.id == grinderLinkedId) }

/** The Devices sheet's grinder row and the rail's grinder dot show: the switch
 *  is on and a Bluetooth grinder is the user's. Otherwise both stay away. */
val MainUiState.showGrinderDevice: Boolean
    get() = grinderInDevices && hasBluetoothGrinder

/** The link's main action, as the grinder card and the Devices row offer it. */
enum class GrinderLinkAction { CONNECT, RECONNECT, DISCONNECT }

/** [GrinderLinkAction] for [status]; [address] is the connected grinder's
 *  ([MainUiState.connectedGrinderAddress]). A link that is up, or coming up,
 *  disconnects; one that stopped sending data reconnects; the rest connect. */
fun grinderLinkAction(status: GrinderLinkStatus, address: String?): GrinderLinkAction = when {
    status == GrinderLinkStatus.NO_DATA -> GrinderLinkAction.RECONNECT
    status in setOf(
        GrinderLinkStatus.OFF, GrinderLinkStatus.NOT_FOUND, GrinderLinkStatus.UPDATE_FIRMWARE, GrinderLinkStatus.MISMATCH,
    ) -> GrinderLinkAction.CONNECT
    status == GrinderLinkStatus.NO_ANSWER && address == null -> GrinderLinkAction.CONNECT
    else -> GrinderLinkAction.DISCONNECT
}

/** The grinder's status dot: green, amber or grey. */
enum class GrinderDot { CONNECTED, CONNECTING, OFF }

/** [GrinderDot] for [status]: green once linked, amber while it's coming up or
 *  coming back, grey otherwise. */
fun grinderDot(status: GrinderLinkStatus): GrinderDot = when (status) {
    GrinderLinkStatus.LIVE, GrinderLinkStatus.WAITING -> GrinderDot.CONNECTED
    GrinderLinkStatus.SCANNING, GrinderLinkStatus.CONNECTING, GrinderLinkStatus.RECONNECTING -> GrinderDot.CONNECTING
    else -> GrinderDot.OFF
}

/**
 * The Devices row's status line. While connected it's the grind
 * ("Connected · Grind 2.4": the followed value, else [liveDial], the reading's
 * dial), never the battery; otherwise short words for the link.
 */
fun grinderDeviceStatus(status: GrinderLinkStatus, followGrind: String?, liveDial: String?): String =
    when (status) {
        GrinderLinkStatus.LIVE, GrinderLinkStatus.WAITING -> {
            val grind = followGrind ?: liveDial
            if (grind != null) "Connected · Grind $grind" else "Connected"
        }
        GrinderLinkStatus.OFF -> "Not connected"
        GrinderLinkStatus.SCANNING -> "Scanning…"
        GrinderLinkStatus.CONNECTING -> "Connecting…"
        GrinderLinkStatus.RECONNECTING -> "Reconnecting…"
        GrinderLinkStatus.NOT_FOUND -> "Not found"
        GrinderLinkStatus.NO_ANSWER -> "Not answering"
        GrinderLinkStatus.NO_DATA -> "Not sending data"
        GrinderLinkStatus.UPDATE_FIRMWARE -> "Update firmware"
        GrinderLinkStatus.MISMATCH -> "Not a supported grinder"
    }

/** The name the Devices row shows: the linked record's, else the remembered
 *  or connected model's ("Motto 80"). Never the serial. */
fun MainUiState.grinderDeviceName(): String =
    linkedGrinder()?.name
        ?: grinderLinkedId?.let { id -> grinders.firstOrNull { it.id == id && it.isLive }?.name }
        ?: grinders.firstOrNull { it.isLive && it.presetId in BLUETOOTH_GRINDER_MODELS }?.name
        ?: grinderModelName(connectedGrinder.model ?: GrinderController.GRINDER_MODEL_FALLBACK)

/** The rail's grinder dot, or null for none (the rail stays as it was). */
fun MainUiState.grinderRailDot(): GrinderDot? = if (showGrinderDevice) grinderDot(connectedGrinder.status) else null
