package coffee.crema.firmware

import coffee.crema.core.FirmwareArtifact
import coffee.crema.core.FirmwareImageCheck
import coffee.crema.core.FlashBlocker
import coffee.crema.core.FlashPhase
import coffee.crema.core.FlashShellConditions
import coffee.crema.core.FlashStartError
import coffee.crema.core.FlashStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * The state behind Settings → Machine → "Update firmware…" (alpha) — the
 * twin of the web's `$lib/firmware/firmware-update.svelte.ts`:
 * alpha warning → choose a build → download from Decent and verify against
 * the core's pinned manifest → type UPDATE → flash (progress, cancel) →
 * power-cycle → result / retry. The flash itself is the core's state machine
 * driven by [FirmwareFlashDriver].
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
) {
    val selected: FirmwareArtifact? get() = artifacts.firstOrNull { it.id == selectedId }
    val running: Boolean get() = status?.phase in RUNNING_PHASES
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

class FirmwareUpdateController(
    private val scope: CoroutineScope,
    private val driver: FirmwareFlashDriver,
    private val core: FlashCorePort,
    private val catalog: FirmwareCatalog,
    private val fetcher: FirmwareFetcher,
    /** Link, ready, guided brew, power — what the shell knows. */
    private val conditions: () -> FlashShellConditions,
    /** The installed build (MMR `0x800010`), when read. */
    private val installedBuild: () -> UInt?,
    private val log: (String) -> Unit = {},
) {
    private val _ui = MutableStateFlow(FirmwareFlowUi())
    val ui: StateFlow<FirmwareFlowUi> = _ui.asStateFlow()

    private var image: ByteArray? = null

    /** Called by the driver's status callback. */
    fun onStatus(status: FlashStatus?) = _ui.update { it.copy(status = status) }

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

    /** Close; a finished flash is forgotten. Ignored while one runs. */
    fun close() = scope.launch {
        val s = _ui.value
        if (s.running) return@launch
        if (s.status != null && s.status.phase != FlashPhase.AwaitingPowerCycle) driver.dismiss()
        _ui.update { it.copy(step = FlowStep.Closed) }
    }

    fun setAcknowledged(on: Boolean) = _ui.update { it.copy(acknowledged = on) }

    fun acceptWarning() = _ui.update { if (it.acknowledged) it.copy(step = FlowStep.Choose) else it }

    fun select(id: String) {
        image = null
        _ui.update { it.copy(selectedId = id, imageReady = false) }
    }

    fun back() = _ui.update {
        when (it.step) {
            FlowStep.Source, FlowStep.Confirm -> it.copy(step = FlowStep.Choose, sourceProblem = null)
            FlowStep.Choose -> it.copy(step = FlowStep.Warning)
            else -> it
        }
    }

    /** Download the chosen build from Decent and verify it. */
    fun download() = scope.launch {
        val artifact = _ui.value.selected ?: return@launch
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
                return@launch
            }
            break
        }
        _ui.update { it.copy(downloading = false, sourceProblem = problem) }
    }

    /** null when [bytes] is the chosen image, else why not. */
    private fun verify(artifact: FirmwareArtifact, bytes: ByteArray): String? {
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

    fun cancel() = scope.launch { driver.cancel() }

    /** After a failure: back to confirm with the same image. */
    fun retry() = scope.launch {
        driver.dismiss()
        if (image == null) {
            _ui.update { it.copy(step = FlowStep.Choose) }
        } else {
            toConfirm()
        }
    }

    /** The newest pinned build above the installed one, else the newest. */
    private fun defaultArtifact(artifacts: List<FirmwareArtifact>): FirmwareArtifact? {
        val installed = installedBuild()
        val byBuild = artifacts.sortedByDescending { it.build }
        return (installed?.let { i -> byBuild.firstOrNull { it.build > i } }) ?: byBuild.firstOrNull()
    }
}
