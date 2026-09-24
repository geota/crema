package coffee.crema.ui

import coffee.crema.core.MmrRegister
import coffee.crema.core.ShotMachine
import coffee.crema.decent.DecentSync
import coffee.crema.decent.decentModelName
import coffee.crema.history.StoredShot
import coffee.crema.visualizer.SyncLogEntry
import coffee.crema.visualizer.VisualizerSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** A one-time offer to upload existing shots to a destination that just became able to take them. */
data class CatchUpOffer(val destination: UploadTargetId, val count: Int)

/**
 * Everything the Sharing / History UI reads about cloud destinations, derived
 * here so composables never call into the ViewModel to compute it.
 */
data class SharingUiState(
    /** Shown inline in Settings → Sharing; dismissed or acted on. */
    val catchUpOffer: CatchUpOffer? = null,
    /** True while a catch-up / "Upload N" pass runs (one shared drain). */
    val catchUpBusy: Boolean = false,
    /** Per shot id: the destinations + their state (menu row, pip, view / share links). */
    val uploadTargets: Map<String, List<UploadTarget>> = emptyMap(),
    /** Distinct shots missing from at least one enabled destination — the "Upload N" count. */
    val missingUploadTotal: Int = 0,
    /** Shot ids with an upload in flight to any destination. */
    val uploadingShotIds: Set<String> = emptySet(),
    /** The connected DE1's serial, once read. */
    val connectedSerial: String? = null,
    /** Is [connectedSerial] registered on the linked Decent account? Null = no DE1 serial or no account. */
    val connectedSerialOnDecent: Boolean? = null,
    /** "Recent activity" across destinations, newest first. */
    val syncLog: List<SyncLogEntry> = emptyList(),
)

/**
 * The sharing coordination across cloud destinations (Visualizer, the Decent
 * account) — split out of MainViewModel the way [LibraryController] is. Owns:
 * the capture-time fan-out + the one-notice-per-shot batch, the History menu
 * push, the cross-destination backlog ("Upload N") and the catch-up offer
 * (one shared drain lock), the derived [SharingUiState], and the connected
 * machine's identity for Decent.
 *
 * Destinations are driven through [UploadDestination], keyed by
 * [UploadTargetId] — adding one is a new entry in [destinations], not a new
 * branch here.
 */
class SharingController(
    private val scope: CoroutineScope,
    visualizer: VisualizerSync,
    decent: DecentSync,
    /** The live UI snapshot (history, the destination mirrors, the DE1 info). */
    private val uiFlow: StateFlow<MainUiState>,
    /** Synchronously update the UI snapshot (the VM's `_ui.update`). */
    private val updateUi: ((MainUiState) -> MainUiState) -> Unit,
    /** Surface a user-facing message (the VM's snackbar channel). */
    private val notify: (String) -> Unit,
    /** Model-register → name (core `machineModelName`), injectable for tests. */
    private val modelName: (UInt?) -> String? = { decentModelName(it) },
) {
    val destinations: List<UploadDestination> = listOf(visualizer, decent)

    private fun destination(id: UploadTargetId): UploadDestination = destinations.first { it.id == id }

    private val outcomes = UploadOutcomeCollector(scope) { _, message -> notify(message) }

    /** Settings catch-up and History "Upload N" share this: one drain at a time. */
    private val drainLock = Mutex()

    init {
        for (d in destinations) {
            d.onUploadOutcome = { shotId, outcome -> outcomes.report(shotId, d.id, outcome) }
        }
    }

    private fun sharing(transform: (SharingUiState) -> SharingUiState) =
        updateUi { it.copy(sharing = transform(it.sharing)) }

    // ── Derived state ───────────────────────────────────────────────────────

    private data class Inputs(
        val history: List<StoredShot>,
        val visualizer: VisualizerSync.UiState,
        val decent: DecentSync.UiState,
        val serial: String?,
    )

    private data class Derived(
        val targets: Map<String, List<UploadTarget>>,
        val missing: Int,
        val uploading: Set<String>,
        val serial: String?,
        val serialOnDecent: Boolean?,
        val log: List<SyncLogEntry>,
    )

    /** Keep [SharingUiState]'s derived fields current. Call once from the VM's init. */
    fun start() {
        scope.launch {
            uiFlow
                .map { Inputs(it.history, it.visualizer, it.decent, connectedSerial(it)) }
                // History by identity: it is replaced, never mutated, and a deep
                // compare of every shot on each telemetry tick would cost more
                // than the recompute it saves.
                .distinctUntilChanged { a, b ->
                    a.history === b.history && a.visualizer == b.visualizer && a.decent == b.decent && a.serial == b.serial
                }
                .map { derive(it) }
                .flowOn(Dispatchers.Default)
                .collect { d ->
                    sharing {
                        it.copy(
                            uploadTargets = d.targets,
                            missingUploadTotal = d.missing,
                            uploadingShotIds = d.uploading,
                            connectedSerial = d.serial,
                            connectedSerialOnDecent = d.serialOnDecent,
                            syncLog = d.log,
                        )
                    }
                }
        }
    }

    private fun derive(i: Inputs): Derived = Derived(
        targets = i.history.associate { it.id to uploadTargetsFor(it, destinations) },
        missing = missingUploadTotal(i.history, destinations),
        uploading = i.visualizer.uploadingShotIds + i.decent.uploadingShotIds,
        serial = i.serial,
        serialOnDecent = if (i.serial == null || !i.decent.linked) null else i.serial in i.decent.serials,
        log = (i.visualizer.log + i.decent.log).sortedByDescending { it.at }.take(LOG_CAP),
    )

    private fun connectedSerial(ui: MainUiState): String? =
        ui.de1MachineInfo[MmrRegister.SerialNumber]?.takeIf { it != 0u }?.toString()

    /**
     * The connected DE1's identity (#84): serial from the connect-time MMR
     * sweep, the decoded firmware string, the model name. Null until the
     * serial has been read.
     */
    fun liveMachine(): ShotMachine? {
        val ui = uiFlow.value
        val serial = connectedSerial(ui) ?: return null
        val info = ui.de1MachineInfo
        return ShotMachine(
            serialNumber = serial,
            firmwareVersion = ui.de1Firmware ?: info[MmrRegister.FirmwareVersion]?.toString(),
            model = modelName(info[MmrRegister.MachineModel]),
        )
    }

    // ── Pushes ──────────────────────────────────────────────────────────────

    /** Capture-time push to every armed destination; ONE notice for those that actually fired. */
    fun autoUpload(shot: StoredShot, fullSamples: List<TelemetrySample>?) {
        outcomes.open(shot.id)
        val fired = destinations.filter { it.maybeAutoUpload(shot, fullSamples) }.map { it.id }
        outcomes.seal(shot.id, fired)
    }

    /** Push one shot to the destinations the menu picked (a re-upload where it already is) — one notice. */
    fun uploadShotTo(shot: StoredShot, targets: List<UploadTarget>) {
        outcomes.open(shot.id)
        val fired = targets.filter { t -> destination(t.id).pushShot(shot, replace = t.uploaded) }.map { it.id }
        outcomes.seal(shot.id, fired)
    }

    // ── Catch-up offer (Settings → Sharing) ─────────────────────────────────

    /**
     * Offer the backlog to a destination that just became able to take it (a
     * sign-in, the auto-upload toggle). Reads the destination's own persisted
     * state, so it is right even before the UI mirror catches up.
     */
    fun offerCatchUp(id: UploadTargetId) {
        val d = destination(id)
        if (!d.enabled) return
        val count = d.unsent(uiFlow.value.history).size
        if (count > 0) sharing { it.copy(catchUpOffer = CatchUpOffer(id, count)) }
    }

    fun dismissCatchUp() = sharing { it.copy(catchUpOffer = null) }

    /** A destination's auto-upload toggle; turning it on offers the backlog once it is stored. */
    fun setAutoUpload(id: UploadTargetId, enabled: Boolean) {
        scope.launch {
            destination(id).setAutoUploadNow(enabled)
            if (enabled) offerCatchUp(id)
        }
    }

    /** Run the offered catch-up for its destination, then clear the offer. */
    fun runCatchUp() {
        val offer = uiFlow.value.sharing.catchUpOffer ?: return
        drain(setOf(offer.destination), clearOffer = true)
    }

    /** Push the whole backlog to every enabled destination, one summary notice. */
    fun uploadMissing() {
        drain(missingUploadCounts(uiFlow.value.history, destinations).keys, clearOffer = false)
    }

    private fun drain(ids: Set<UploadTargetId>, clearOffer: Boolean) {
        if (ids.isEmpty() || !drainLock.tryLock()) return
        sharing { it.copy(catchUpBusy = true) }
        scope.launch {
            try {
                val parts = mutableListOf<String>()
                for (d in destinations.filter { it.id in ids }) {
                    // Each pass reads the history fresh: an earlier destination's
                    // pass may have stamped ids the next one should see.
                    val r = d.uploadUnsentNow(uiFlow.value.history)
                    if (r.attempted > 0 || r.stopped != null) parts += summarize(d.id, r)
                }
                if (parts.isNotEmpty()) notify("Uploaded ${parts.joinToString(", ")}")
            } finally {
                sharing { it.copy(catchUpBusy = false, catchUpOffer = if (clearOffer) null else it.catchUpOffer) }
                drainLock.unlock()
            }
        }
    }

    private fun summarize(id: UploadTargetId, r: DrainResult): String = buildString {
        append("${r.uploaded} to ${id.displayName}")
        if (r.failed > 0) append(" (${r.failed} failed)")
        if (r.skipped > 0) append(" (${r.skipped} skipped)")
        r.stopped?.let { append(" — stopped: $it") }
    }

    private companion object {
        const val LOG_CAP = 20
    }
}
