package coffee.crema.settings

import coffee.crema.machine.CommandOutcome
import coffee.crema.machine.MachineCommand
import coffee.crema.profiles.CremaProfile

/** A relayed edit shown on a remote until the hub answers (spec §7.3).
 *  [generation] orders edits: an answer settles only the latest one. */
data class PendingEdit(val command: MachineCommand.Config, val generation: Long)

/**
 * A remote's mirror slice (spec §7.2): the hub's values as last received,
 * held in memory only and never persisted. `MainUiState.mirror` is non-null
 * exactly while the device is a remote (I6), and the screens show the slice
 * laid over the device's own values (`MainUiState.effective()`).
 */
data class MirroredSettings(
    /** The last applied snapshot; null before the first one arrives. */
    val snapshot: ConfigSnapshot? = null,
    /** The hub's custom active profile, decoded for display only. */
    val profileOverlay: CremaProfile? = null,
    /** The raw JSON behind [profileOverlay], for adoption at T6. */
    val profileJson: String? = null,
    /** A one-line summary of the hub's active bean. */
    val beanSummary: String? = null,
    /** Relayed edits awaiting the hub's answer, by setting. */
    val pending: Map<Setting, PendingEdit> = emptyMap(),
    /** Edits the hub never answered: kept on screen until the next snapshot. */
    val unconfirmed: Set<Setting> = emptySet(),
    /** The link to the hub is redialing. */
    val reconnecting: Boolean = false,
    /** The hub's name, from its Welcome. */
    val hubName: String = "",
    /** Leaving the remote role (T6): frames and edits are dropped, the slice stays shown. */
    val frozen: Boolean = false,
    /** The `rev` of the last applied snapshot; reset on each Welcome. */
    val lastRev: Long? = null,
    /** The last pending edit's generation. */
    val generation: Long = 0L,
) {
    /** Apply a decoded hub snapshot. Edits the hub never answered make way for
     *  its values now; edits still awaiting an answer stay on top. */
    fun applied(cfg: ConfigSnapshot, overlay: CremaProfile?, overlayJson: String?): MirroredSettings = copy(
        snapshot = cfg,
        profileOverlay = overlay,
        profileJson = overlay?.let { overlayJson },
        beanSummary = cfg.activeBeanSummary,
        pending = pending - unconfirmed,
        unconfirmed = emptySet(),
        lastRev = cfg.rev,
    )

    /** Whether a snapshot with [rev] is older than, or the same as, the last
     *  applied one. Every v4 snapshot has a rev. */
    fun isStale(rev: Long): Boolean = lastRev != null && rev <= lastRev

    /** The link dropped: pending edits become unconfirmed, never resent (§7.3). */
    fun reconnectingNow(): MirroredSettings =
        copy(reconnecting = true, unconfirmed = unconfirmed + pending.keys)

    /** A new Welcome: the hub's `rev` counter may have started over. */
    fun welcomed(hubName: String): MirroredSettings =
        copy(reconnecting = false, lastRev = null, hubName = hubName.ifBlank { this.hubName })

    /** Show [cmd] until the hub answers; returns the slice and the edit's generation. */
    fun withPending(cmd: MachineCommand.Config): Pair<MirroredSettings, Long> {
        val gen = generation + 1
        val key = cmd.setting()
        return copy(generation = gen, pending = pending + (key to PendingEdit(cmd, gen)), unconfirmed = unconfirmed - key) to gen
    }

    /** The hub answered the edit of [key] made at [generation]. A later edit of
     *  the same key wins; an unknown outcome keeps the value as unconfirmed. */
    fun settled(key: Setting, generation: Long, outcome: CommandOutcome): MirroredSettings {
        if (pending[key]?.generation != generation) return this
        return when (outcome) {
            CommandOutcome.Accepted, is CommandOutcome.Refused -> copy(pending = pending - key)
            is CommandOutcome.Unknown -> copy(unconfirmed = unconfirmed + key)
        }
    }
}

/** Whether this snapshot carries [setting]'s value. A v4 hub sends every
 *  snapshot setting except the machine registers it may not have read yet
 *  (or that this machine doesn't have): those are known only when not null. */
fun ConfigSnapshot.carries(setting: Setting): Boolean = when (setting) {
    Setting.HEATER_VOLTAGE -> heaterVoltage != null
    Setting.FLOW_MULTIPLIER -> flowMultiplier != null
    Setting.CUP_WARMER_TEMP -> cupWarmerTempC != null
    // For the rest a null value (no profile, no bean, no override) means "none".
    else -> setting.mirroredVia == MirrorSource.SNAPSHOT
}
