package coffee.crema.firmware

import androidx.core.content.edit
import coffee.crema.core.FirmwareArtifact
import coffee.crema.core.FirmwareImageCheck
import coffee.crema.core.FlashBlocker
import coffee.crema.core.FlashPhase
import coffee.crema.core.FlashShellConditions
import coffee.crema.core.FlashStartError
import coffee.crema.core.FlashStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * The state behind Settings → Machine → "Update firmware…" (alpha) — the
 * twin of the web's `$lib/firmware/firmware-update.svelte.ts`:
 * alpha warning → choose a build → download from Decent and verify against
 * the core's pinned manifest → type UPDATE → flash (progress, confirmed
 * cancel) → power-cycle (confirmed exit) → result / retry. The flash itself is
 * the core's state machine driven by [FirmwareFlashDriver]. A persisted marker
 * brings the power-cycle step (and its write lock) back after the process is
 * killed (audit M3).
 *
 * Android has no CORS: the image is downloaded straight from Decent's own
 * channel URL (the file de1app syncs) and must match the manifest byte for
 * byte. Nothing is bundled.
 */

/** The visible step of the flow. */
enum class FlowStep { Closed, Warning, Choose, Source, Confirm, Flash }

/** Everything the dialog renders. */
data class FirmwareFlowUi(
    val step: FlowStep = FlowStep.Closed,
    val acknowledged: Boolean = false,
    val artifacts: List<FirmwareArtifact> = emptyList(),
    val selectedId: String? = null,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val sourceProblem: String? = null,
    val imageReady: Boolean = false,
    val blockers: List<FlashBlocker> = emptyList(),
    val status: FlashStatus? = null,
    val startProblem: String? = null,
    /** "Stop the update?" is showing. */
    val confirmingCancel: Boolean = false,
    /** "Exit without confirming the build?" is showing. */
    val confirmingExit: Boolean = false,
) {
    val selected: FirmwareArtifact? get() = artifacts.firstOrNull { it.id == selectedId }
    val running: Boolean get() = status?.phase in RUNNING_PHASES

    /** The flash holds the DE1 link: the shell skips its reconnect sweeps. */
    val holdsLink: Boolean get() = status?.locksWrites == true
}

/** Fetches a URL's bytes ([onProgress] = received / expected). */
fun interface FirmwareFetcher {
    suspend fun fetch(url: String, expected: Long, onProgress: (Float) -> Unit): ByteArray
}

/** The core's manifest + validation (free FFI functions in production). */
interface FirmwareCatalog {
    fun manifest(): List<FirmwareArtifact>
    fun validate(image: ByteArray): FirmwareImageCheck
}

/** Where the power-cycle marker lives (SharedPreferences in the app). */
interface AwaitingMarkerStore {
    /** `(build, unconfirmed)` or null. */
    fun read(): Pair<UInt, Boolean>?
    fun write(build: UInt, unconfirmed: Boolean)
    fun clear()
}

class FirmwareUpdateController(
    private val scope: CoroutineScope,
    private val core: FlashCorePort,
    private val catalog: FirmwareCatalog,
    private val fetcher: FirmwareFetcher,
    /** Link, ready, guided brew, power — what the shell knows. */
    private val conditions: () -> FlashShellConditions,
    /** The installed build (MMR `0x800010`), when read. */
    private val installedBuild: () -> UInt?,
    private val log: (String) -> Unit = {},
    private val markers: AwaitingMarkerStore? = null,
    /** The flash let go of the DE1 link (the shell runs what it skipped). */
    private val onRelease: () -> Unit = {},
    driverFactory: ((FlashStatus?) -> Unit) -> FirmwareFlashDriver,
) {
    private val _ui = MutableStateFlow(FirmwareFlowUi())
    val ui: StateFlow<FirmwareFlowUi> = _ui.asStateFlow()

    private val driver: FirmwareFlashDriver = driverFactory(::onStatus)
    private var image: ByteArray? = null
    private var downloadJob: Job? = null

    /** Called by the driver's status callback. */
    private fun onStatus(status: FlashStatus?) {
        val wasHeld = _ui.value.holdsLink
        _ui.update { it.copy(status = status) }
        persist(status)
        if (wasHeld && status?.locksWrites != true) onRelease()
    }

    private fun persist(s: FlashStatus?) {
        val store = markers ?: return
        runCatching {
            when {
                s?.phase == FlashPhase.AwaitingPowerCycle -> store.write(s.targetBuild, s.verifyUnconfirmed)
                s == null || s.phase == FlashPhase.Succeeded -> store.clear()
            }
        }
    }

    /** After a process restart: bring back a flash that was waiting for the power-cycle. */
    fun restore() = scope.launch {
        val (build, unconfirmed) = markers?.read() ?: return@launch
        if (driver.restoreAwaiting(build, unconfirmed) != null) {
            log("Firmware: still waiting for the DE1 to restart on build $build")
        }
    }

    /** Open — straight to the flash view when one exists. */
    fun open() = scope.launch {
        val status = driver.refresh()
        if (status != null) {
            _ui.update { it.copy(step = FlowStep.Flash, status = status) }
            return@launch
        }
        val artifacts = runCatching { catalog.manifest() }.getOrDefault(emptyList())
        image = null
        _ui.value = FirmwareFlowUi(
            step = FlowStep.Warning,
            artifacts = artifacts,
            selectedId = defaultArtifact(artifacts)?.id,
        )
    }

    /**
     * Close. Ignored while a transfer runs; on the power-cycle step it only
     * hides (the lock stays, reopening returns there); a finished, unlocked
     * flash is forgotten.
     */
    fun close() = scope.launch {
        val s = _ui.value
        if (s.running) return@launch
        if (s.status != null && !s.holdsLink) driver.dismiss()
        _ui.update { it.copy(step = FlowStep.Closed, confirmingCancel = false, confirmingExit = false) }
    }

    fun setAcknowledged(on: Boolean) = _ui.update { it.copy(acknowledged = on) }

    fun acceptWarning() = _ui.update { if (it.acknowledged) it.copy(step = FlowStep.Choose) else it }

    fun select(id: String) {
        image = null
        _ui.update { it.copy(selectedId = id, imageReady = false) }
    }

    fun back() {
        downloadJob?.cancel()
        _ui.update {
            when (it.step) {
                FlowStep.Source, FlowStep.Confirm -> it.copy(step = FlowStep.Choose, sourceProblem = null, downloading = false)
                FlowStep.Choose -> it.copy(step = FlowStep.Warning)
                else -> it
            }
        }
    }

    /** Download the chosen build from Decent and verify it. */
    fun download() {
        downloadJob?.cancel()
        downloadJob = scope.launch { downloadNow() }
    }

    private suspend fun downloadNow() {
        val artifact = _ui.value.selected ?: return
        _ui.update { it.copy(step = FlowStep.Source, downloading = true, downloadProgress = 0f, sourceProblem = null) }
        var problem: String? = "No download URL."
        for (url in artifact.urls) {
            val bytes = try {
                fetcher.fetch(url, artifact.byteLength.toLong()) { p -> _ui.update { it.copy(downloadProgress = p) } }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                log("Firmware: download from $url failed: ${e.message}")
                problem = "Couldn’t download the firmware from Decent (${e.message ?: "network error"})."
                continue
            }
            problem = verify(artifact, bytes)
            if (problem == null) {
                image = bytes
                toConfirm()
                return
            }
            break
        }
        _ui.update { it.copy(downloading = false, sourceProblem = problem) }
    }

    /** null when [bytes] is the chosen image, else why not. */
    private fun verify(artifact: FirmwareArtifact, bytes: ByteArray): String? {
        if (bytes.size.toLong() > artifact.byteLength.toLong() * 2) {
            return "Decent is serving a file far too large to be DE1 firmware."
        }
        val check = catalog.validate(bytes)
        val found = check.artifact
        return when {
            !check.ok || found == null -> imageProblem(check)
            found.id != artifact.id ->
                "This file is build ${found.build}, not build ${artifact.build}."
            else -> null
        }
    }

    private fun toConfirm() {
        val blockers = core.blockers(conditions())
        _ui.update { it.copy(step = FlowStep.Confirm, downloading = false, imageReady = true, blockers = blockers) }
    }

    fun refreshBlockers() = _ui.update { it.copy(blockers = core.blockers(conditions())) }

    /** Start (or retry) with the verified image. */
    fun start() = scope.launch {
        val img = image ?: return@launch
        val blockers = core.blockers(conditions())
        if (blockers.isNotEmpty()) {
            _ui.update { it.copy(step = FlowStep.Confirm, blockers = blockers) }
            return@launch
        }
        when (val err = driver.start(img, conditions())) {
            null -> _ui.update { it.copy(step = FlowStep.Flash, startProblem = null) }
            is FlashStartError.Blocked ->
                _ui.update { it.copy(step = FlowStep.Confirm, blockers = err.content.blockers) }
            is FlashStartError.InvalidImage ->
                _ui.update { it.copy(step = FlowStep.Confirm, startProblem = "The firmware file failed verification.") }
        }
    }

    /** Ask before cancelling (only while the core still allows it). */
    fun requestCancel() = _ui.update { if (it.status?.canCancel == true) it.copy(confirmingCancel = true) else it }

    fun keepUpdating() = _ui.update { it.copy(confirmingCancel = false) }

    fun confirmCancel() = scope.launch {
        _ui.update { it.copy(confirmingCancel = false) }
        driver.cancel()
    }

    /** Ask before exiting the power-cycle step without a confirmed build. */
    fun requestExit() = _ui.update { if (it.status?.canExit == true) it.copy(confirmingExit = true) else it }

    fun keepWaiting() = _ui.update { it.copy(confirmingExit = false) }

    fun confirmExit() = scope.launch {
        _ui.update { it.copy(confirmingExit = false) }
        if (driver.exit()) _ui.update { it.copy(step = FlowStep.Closed) }
    }

    /** After a failure: back to confirm with the same image (once the link is free). */
    fun retry() = scope.launch {
        if (_ui.value.holdsLink) return@launch
        driver.dismiss()
        if (image == null) {
            _ui.update { it.copy(step = FlowStep.Choose) }
        } else {
            toConfirm()
        }
    }

    /** Link events from the BLE manager's state. */
    fun onLinkLost() = driver.onLinkLost()
    fun onLinkRestored() = driver.onLinkRestored()

    /** The newest pinned build above the installed one, else the newest. */
    private fun defaultArtifact(artifacts: List<FirmwareArtifact>): FirmwareArtifact? {
        val installed = installedBuild()
        val byBuild = artifacts.sortedByDescending { it.build }
        return (installed?.let { i -> byBuild.firstOrNull { it.build > i } }) ?: byBuild.firstOrNull()
    }
}

/** [AwaitingMarkerStore] over a private SharedPreferences file. */
class SharedPrefsAwaitingMarkers(context: android.content.Context) : AwaitingMarkerStore {
    private val prefs = context.getSharedPreferences("crema.firmware", android.content.Context.MODE_PRIVATE)

    override fun read(): Pair<UInt, Boolean>? {
        val build = prefs.getLong(KEY_BUILD, -1L)
        if (build !in 0L..UInt.MAX_VALUE.toLong()) return null
        return build.toUInt() to prefs.getBoolean(KEY_UNCONFIRMED, false)
    }

    override fun write(build: UInt, unconfirmed: Boolean) {
        prefs.edit { putLong(KEY_BUILD, build.toLong()).putBoolean(KEY_UNCONFIRMED, unconfirmed) }
    }

    override fun clear() {
        prefs.edit { remove(KEY_BUILD).remove(KEY_UNCONFIRMED) }
    }

    private companion object {
        const val KEY_BUILD = "awaitingPowerCycle.v1.build"
        const val KEY_UNCONFIRMED = "awaitingPowerCycle.v1.unconfirmed"
    }
}
