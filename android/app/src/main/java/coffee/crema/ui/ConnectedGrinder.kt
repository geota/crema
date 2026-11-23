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
