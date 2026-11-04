package coffee.crema.ui

import coffee.crema.beans.beanDaysOffRoast
import coffee.crema.beans.isFrozen
import coffee.crema.core.MmrRegister
import coffee.crema.machine.ConfigEcho
import coffee.crema.machine.MachineCommand
import coffee.crema.profiles.CremaProfile
import coffee.crema.settings.ConfigSnapshot
import coffee.crema.settings.MirroredSettings
import coffee.crema.settings.Setting
import coffee.crema.settings.setting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The VM side of hub / remote config (MainViewModel breakup step 7): the
 * hub's numbered config snapshot for its remotes, a remote's mirror slice
 * (applied snapshots, pending relayed edits), the slice's lifetime across
 * role starts, and the switches between roles, with the T6 "use the hub's
 * settings?" adoption on leaving the remote role (spec §7.5). The transport,
 * relay and pairing stay in [ProxyController], which this drives.
 */
internal class RemoteConfigController(
    private val proxy: ProxyController,
    private val json: Json,
    private val scope: CoroutineScope,
    private val uiState: () -> MainUiState,
    private val updateUi: ((MainUiState) -> MainUiState) -> Unit,
    private val effectiveState: () -> MainUiState,
    private val appendLog: (String) -> Unit,
    private val notify: (String) -> Unit,
    /** Hand the core the inputs that follow settings, from a state (spec §7.4). */
    private val pushCoreInputs: (MainUiState) -> Unit,
    private val persistPrefsNow: suspend () -> Unit,
    private val profileJson: (String) -> String?,
    private val isBuiltinProfile: (String) -> Boolean,
    private val hasProfile: (String) -> Boolean,
    private val adoptCustomProfile: suspend (String) -> Unit,
    private val persistLibraryNow: suspend () -> Unit,
) {
    // ── The hub's snapshot ───────────────────────────────────────────────────

    /** A one-line roaster · name · freshness summary of the active bean for the
     *  config snapshot (issue 05) so a mirror can render the bean chip even when
     *  the bean isn't in its own library. Mirrors `beanLine` on the Brew screen. */
    private fun activeBeanSummaryLine(): String? {
        val ui = uiState()
        val bean = ui.beans.firstOrNull { it.id == ui.activeBeanId } ?: return null
        val roaster = ui.roasters.firstOrNull { it.id == bean.roasterId }?.name
        val days = beanDaysOffRoast(bean)
        return listOfNotNull(
            roaster,
            bean.name,
            when {
                bean.isFrozen -> "Frozen"
                days != null -> "${days}d off roast"
                else -> null
            },
        ).joinToString(" · ").ifBlank { null }
    }

    /** Build the primary's session-config snapshot JSON for a mirror (see
     *  [ConfigSnapshot]) — sourced from the live [MainUiState] so it reflects any
     *  unsaved in-session change. Fed to the relay as its config source (see [ProxyController]). */
    fun configSnapshotJson(): String = runCatching {
        // Read and numbered in one step, on Main (§6.1): a remote drops a
        // snapshot at or below the last rev it applied.
        val ui = uiState()
        val rev = configRev++
        json.encodeToString(
            ConfigSnapshot.serializer(),
            ConfigSnapshot(
                activeProfileId = ui.activeProfileId,
                // Custom profiles only (issue 05): a mirror already has every
                // built-in by id, so we don't ship those — keeps the frame lean
                // while still covering the profile a secondary couldn't otherwise show.
                activeProfileJson = ui.activeProfileId
                    ?.takeIf { !isBuiltinProfile(it) }
                    ?.let { profileJson(it) },
                activeBeanId = ui.activeBeanId,
                activeBeanSummary = activeBeanSummaryLine(),
                stopOnWeight = ui.stopOnWeight,
                autoTare = ui.autoTare,
                maxShotDurationS = ui.maxShotDurationS,
                grinderModel = ui.grinderModel,
                qcSteamTimeS = ui.qcSteamTimeS,
                qcSteamFlowMlS = ui.qcSteamFlowMlS,
                qcSteamTempC = ui.qcSteamTempC,
                qcHotWaterTempC = ui.qcHotWaterTempC,
                qcHotWaterVolumeMl = ui.qcHotWaterVolumeMl,
                qcHotWaterMode = ui.qcHotWaterMode,
                qcHotWaterTimeS = ui.qcHotWaterTimeS,
                qcHotWaterTimeNoLimit = ui.qcHotWaterTimeNoLimit,
                qcHotWaterRatio = ui.qcHotWaterRatio,
                hotWaterTarget = ui.hotWaterTarget,
                qcFlushTimeS = ui.qcFlushTimeS,
                qcFlushTempC = ui.qcFlushTempC,
                qcGrind = ui.qcGrind,
                // Live brew override so a mirror's targets track the dial (issue 06).
                brewDoseG = ui.brewParams?.dose,
                brewYieldG = ui.brewParams?.yieldOut,
                brewTempC = ui.brewParams?.brewTemp,
                brewPreinfuseS = ui.brewParams?.preinf,
                primaryName = deviceLabel(),
                authority = "primary",
                rev = rev,
                steamEco = ui.steamEco,
                steamTwoTap = ui.steamTwoTap,
                fanThresholdC = ui.fanThresholdC,
                waterRefillPointMm = ui.refillPointMm(),
                heaterVoltage = ui.de1MachineInfo[MmrRegister.HeaterVoltage]?.toInt(),
                flowMultiplier = ui.de1MachineInfo[MmrRegister.CalibrationFlowMultiplier]?.let { it.toInt() / 1000f },
                lineFrequencyHz = ui.lineFreqHz,
                lineFrequencyOverride = ui.lineFreqOverride,
                cupWarmerTempC = ui.de1MachineInfo[MmrRegister.CupWarmerTemp]?.toInt(),
                volumeStopWithScale = ui.volumeStopWithScale,
                requireScale = ui.requireScale,
                preFlush = ui.preFlush,
                steamPurge = ui.steamPurge,
                idleSleepMin = ui.idleSleepMin,
            ),
        )
    }.getOrDefault("")

    /** The next config snapshot's `rev`: a plain counter from 0 (Main only). */
    private var configRev = 0L

    // ── A remote's mirror slice ──────────────────────────────────────────────

    /**
     * Apply a hub's pushed [ConfigSnapshot] on this remote: it goes into the
     * mirror slice only, never into this device's own values, so nothing a
     * hub sends is ever persisted here (spec §7.2, I1). Dropped when there is
     * no slice (this device isn't a remote) or the slice is frozen. The
     * custom-profile overlay (issue 05) shows the hub's profile even when this
     * device never saved it.
     */
    fun applyRemoteConfig(jsonStr: String) {
        val mirror = uiState().mirror
        if (mirror == null || mirror.frozen) {
            appendLog("Config snapshot dropped: ${if (mirror == null) "not a remote" else "leaving the remote role"}")
            return
        }
        val cfg = runCatching { json.decodeFromString(ConfigSnapshot.serializer(), jsonStr) }.getOrElse {
            appendLog("Config snapshot parse failed: ${it.message}")
            return
        }
        if (mirror.isStale(cfg.rev)) {
            appendLog("Config snapshot ${cfg.rev} dropped: ${mirror.lastRev} already applied")
            return
        }
        val overlay = cfg.activeProfileJson?.let { raw ->
            runCatching { json.decodeFromString(CremaProfile.serializer(), raw) }.getOrElse {
                appendLog("Mirror profile overlay parse failed: ${it.message}")
                null
            }
        }
        updateUi { s ->
            val m = s.mirror?.takeUnless { it.frozen || it.isStale(cfg.rev) } ?: return@updateUi s
            s.copy(mirror = m.applied(cfg, overlay, cfg.activeProfileJson))
        }
        // The remote's core follows the hub's values (§7.4).
        pushCoreInputs(effectiveState())
        appendLog("Config from the hub applied (profile ${cfg.activeProfileId})")
    }

    /**
     * A role is starting ([ProxyController], before its transport is built):
     * the mirror slice exists exactly while this device is a remote (I6). A
     * re-find (a remote dialing its hub at a new address) keeps the slice,
     * marked reconnecting; any other start of the remote role gets an empty
     * one, and every other role drops it.
     */
    fun onRoleStarting(role: String, refind: Boolean) {
        val wasRemote = uiState().mirror != null
        // Any switch cancels an open T6 confirmation (a refusal, a re-find):
        // nothing is adopted (spec §7.5 step 3). The dialog goes, the slice
        // unfreezes and the re-find is released, so the user can leave again.
        val cancelled = pendingLeave != null || uiState().mirror?.frozen == true
        if (cancelled) {
            pendingLeave = null
            appendLog("Leaving the hub cancelled: another mode switch ran")
        }
        updateUi { s ->
            when {
                role != "secondary" -> s.copy(mirror = null, adoptPrompt = null)
                refind && s.mirror != null -> s.copy(mirror = s.mirror.reconnectingNow().copy(frozen = false), adoptPrompt = null)
                else -> s.copy(mirror = MirroredSettings(hubName = s.mirroringPrimaryName), adoptPrompt = null)
            }
        }
        if (cancelled) {
            // After this start returns: releasing the hold re-evaluates the re-find.
            scope.launch { proxy.holdRefind(false) }
            // A refusal explains itself; still a remote, say why nothing happened.
            if (role == "secondary") notify("Couldn't leave ${uiState().remoteHubLabel()} — try again")
        }
        // Leaving the remote role: the core goes back to this device's own values.
        if (wasRemote && role != "secondary") pushCoreInputs(effectiveState())
    }

    /**
     * Show a relayed config edit on this remote until the hub answers (spec
     * §7.3): it goes into the slice as pending, never into own values. The
     * returned echo settles it with the hub's answer.
     */
    fun showPending(cmd: MachineCommand.Config): ConfigEcho {
        uiState().mirror?.takeIf { it.frozen }?.let { return ConfigEcho.refused("Leaving ${uiState().remoteHubLabel()}") }
        var generation = 0L
        updateUi { s ->
            val m = s.mirror?.takeUnless { it.frozen } ?: return@updateUi s
            val (next, gen) = m.withPending(cmd)
            generation = gen
            s.copy(mirror = next)
        }
        if (generation == 0L) return ConfigEcho.NONE
        val key = cmd.setting()
        return ConfigEcho { outcome ->
            updateUi { s ->
                val m = s.mirror?.takeUnless { it.frozen } ?: return@updateUi s
                s.copy(mirror = m.settled(key, generation, outcome))
            }
        }
    }

    // ── Live mode switches (M2 — no restart) ─────────────────────────────────

    /** Become a standalone NORMAL device. On a remote this is Stop, which
     *  leaves the remote role like Connect directly. */
    fun switchToNormal() {
        if (proxy.state.value.role == "secondary") leaveRemote("normal") else proxy.switchToNormal()
    }

    /** Connect through the hub at [host]:[port]: the picker's "Connect through". */
    fun switchToSecondary(host: String, port: Int) = proxy.switchToSecondary(host, port)

    /** A remote whose hub is unreachable, or no longer holds the DE1, connects
     *  the machine itself. */
    fun connectDirectly() {
        if (proxy.state.value.role == "secondary") leaveRemote("normal")
    }

    /**
     * The Hub mode switch (Settings ▸ Multi-device). On makes this device the
     * hub; on a remote whose hub is gone it leaves the remote role first. Off
     * frees the machine: the hub steps down without reconnecting, so another
     * device can connect. Refused on a remote whose hub holds the DE1
     * ([hubModeBlockedReason]) and, by the controller, during a firmware flash.
     * A discovered hub's advertisement only warns ([discoveredHubWarning]):
     * the switch asks first, and [anyway] is the user's "Turn on anyway".
     */
    fun setHubMode(on: Boolean, anyway: Boolean = false) {
        val role = proxy.state.value.role
        if (!on) {
            if (role == "primary") proxy.setHubMode(false)
            return
        }
        uiState().hubModeBlockedReason()?.let { reason ->
            notify(reason)
            return
        }
        if (!anyway) {
            uiState().discoveredHubWarning()?.let { warning ->
                notify(warning)
                return
            }
        }
        when (role) {
            "primary" -> Unit
            "secondary" -> leaveRemote("primary")
            else -> proxy.setHubMode(true)
        }
    }

    /** A remote → standalone in progress, waiting on [adoptPrompt]'s answer. */
    private class PendingLeave(val next: String, val epoch: Long, val snapshot: ConfigSnapshot, val changes: Set<Setting>)

    private var pendingLeave: PendingLeave? = null

    /**
     * Leave the remote role for [next] (`"normal"` or `"primary"`): Stop,
     * Connect directly and Hub mode on with the hub gone all end here (T6,
     * spec §7.5).
     *
     * 1. Freeze the slice: it stays on screen, but frames, edits and the
     *    re-find stop. Only its last applied snapshot can be adopted.
     * 2. If that snapshot differs from this device's own adoptable values,
     *    ask ([adoptPrompt]); else go on with "keep mine".
     * 3. Commit under the mode lock, only if no other switch ran meanwhile
     *    (a refusal, a re-find): adopt and save, awaited, then drop the slice,
     *    re-push the core inputs and switch, so the first sweep writes the
     *    chosen values (I3).
     */
    private fun leaveRemote(next: String) {
        val m = uiState().mirror
        if (m == null) {
            if (next == "primary") proxy.setHubMode(true) else proxy.switchToNormal()
            return
        }
        if (m.frozen) return // already leaving
        val epoch = proxy.modeEpoch
        proxy.holdRefind(true)
        updateUi { s -> s.copy(mirror = s.mirror?.copy(frozen = true)) }
        val snapshot = m.snapshot
        val changes = snapshot?.let { adoptableChanges(uiState(), it) }.orEmpty()
        if (snapshot == null || changes.isEmpty()) {
            commitLeave(next, epoch, adopt = null, changes = emptySet())
            return
        }
        pendingLeave = PendingLeave(next, epoch, snapshot, changes)
        updateUi { it.copy(adoptPrompt = AdoptPrompt(it.remoteHubLabel(), changes.size)) }
    }

    /** The T6 confirmation's answer: "Use <hub>'s" ([useHub]) or "Keep mine"
     *  (also what dismissing it means). */
    fun resolveAdoption(useHub: Boolean) {
        // The dialog always closes, even when a switch already cancelled the leave.
        updateUi { it.copy(adoptPrompt = null) }
        val leave = pendingLeave ?: return
        pendingLeave = null
        commitLeave(leave.next, leave.epoch, if (useHub) leave.snapshot else null, leave.changes)
    }

    private fun commitLeave(next: String, epoch: Long, adopt: ConfigSnapshot?, changes: Set<Setting>) {
        val hub = uiState().remoteHubLabel()
        proxy.applyModeIfEpoch(
            next, epoch,
            before = {
                if (adopt != null) {
                    adoptHubSettings(adopt, changes)
                    persistPrefsNow()
                    persistLibraryNow()
                }
                updateUi { it.copy(mirror = null) }
                pushCoreInputs(effectiveState())
            },
            onStale = {
                // Another switch ran first, or a firmware flash refused this
                // one (R2): this device may still be a remote. Unless a switch
                // already cancelled the leave (onRoleStarting), unfreeze and
                // say so. After a flash refusal nothing else will.
                val stillLeaving = uiState().mirror?.frozen == true
                proxy.holdRefind(false)
                updateUi { s -> s.copy(mirror = s.mirror?.copy(frozen = false)) }
                if (stillLeaving && proxy.state.value.role == "secondary") notify("Couldn't leave $hub — try again")
            },
        )
    }

    /** Take the hub's [changes] from [cfg] as this device's own (spec §7.5
     *  step 4): a custom profile into the library first, the bean only if
     *  this library has it. */
    private suspend fun adoptHubSettings(cfg: ConfigSnapshot, changes: Set<Setting>) {
        if (Setting.ACTIVE_PROFILE in changes) {
            val id = cfg.activeProfileId
            val json = uiState().mirror?.profileJson
            if (id != null && !hasProfile(id) && json != null) adoptCustomProfile(json)
        }
        val profileKnown = cfg.activeProfileId?.let { hasProfile(it) } ?: true
        val take = if (profileKnown) changes else changes - Setting.ACTIVE_PROFILE
        updateUi { s -> adopt(s, cfg, take, hasBean = { id -> s.beans.any { it.id == id } }) }
        appendLog("Adopted ${take.size} settings from the hub")
    }
}
