package coffee.crema.firmware

import coffee.crema.core.CremaBridge
import coffee.crema.core.FlashAction
import coffee.crema.core.FlashBlocker
import coffee.crema.core.FlashPhase
import coffee.crema.core.FlashShellConditions
import coffee.crema.core.FlashStartError
import coffee.crema.core.FlashStatus
import coffee.crema.core.FlashStep
import coffee.crema.core.WriteTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * The Android I/O half of a DE1 firmware flash — the twin of the web's
 * `$lib/firmware/flash-driver.ts`.
 *
 * Every protocol decision lives in the Rust core (`de1_app::firmware_flash`):
 * which bytes, which characteristic, when a phase is over, when to give up.
 * The core answers each input with a `FlashStep` — actions plus a status
 * snapshot. This driver performs the actions strictly one at a time (one
 * write-with-response in flight — de1app `bluetooth.tcl:3303-3312`, decaid
 * `unified_de1.firmware.dart:239`), reports every ACK / failure / read back,
 * routes A009 notifications and link events, ticks the core every [TICK_MS],
 * holds the DE1 link exclusively while the core locks writes and asks for the
 * keep-alive (foreground service + screen on) while a flash runs. All core
 * calls happen under one [Mutex], so a tick, a notification and an ACK never
 * interleave.
 */

/** The core's flash clock cadence (decaid polls A009 every 250 ms). */
const val TICK_MS = 250L

/** How often a running upload republishes its status (UI churn bound). */
const val STATUS_PUBLISH_MS = 250L

/** The core surface the driver needs. [BridgeFlashCore] is the real one. */
interface FlashCorePort {
    fun blockers(conditions: FlashShellConditions): List<FlashBlocker>
    fun start(image: ByteArray, conditions: FlashShellConditions, nowMs: Long): FlashStep
    fun onWriteAck(nowMs: Long): FlashStep
    fun onWriteFailed(message: String, nowMs: Long): FlashStep
    fun onFwMap(data: ByteArray, nowMs: Long): FlashStep
    fun onReadFailed(nowMs: Long): FlashStep
    fun tick(nowMs: Long): FlashStep
    fun linkRestored(nowMs: Long): FlashStep
    fun cancel(nowMs: Long): FlashStep
    fun dismiss(): Boolean
    fun status(nowMs: Long): FlashStep
}

/** The DE1 link surface the driver needs ([coffee.crema.ble.De1BleManager]). */
interface FlashIoPort {
    /** One write with response; throws on failure. */
    suspend fun write(target: WriteTarget, data: ByteArray)

    /** A fresh GATT read of A009. */
    suspend fun readFwMap(): ByteArray

    /** Enable A009 notifications, delivering frames to [onFrame]. */
    suspend fun subscribeFwMap(onFrame: (ByteArray) -> Unit)

    /** Issue the core's MMR `0x800010` read through the normal path. */
    suspend fun readBuild()

    /** Drop every non-flash DE1 write while [on]. */
    fun setExclusive(on: Boolean)
}

internal val flashJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** [FlashCorePort] over the UniFFI [CremaBridge] (JSON in, typed out). */
class BridgeFlashCore(private val bridge: CremaBridge) : FlashCorePort {
    private fun step(raw: String): FlashStep = flashJson.decodeFromString(FlashStep.serializer(), raw)
    private fun cond(c: FlashShellConditions): String =
        flashJson.encodeToString(FlashShellConditions.serializer(), c)

    override fun blockers(conditions: FlashShellConditions): List<FlashBlocker> =
        flashJson.decodeFromString(
            ListSerializer(FlashBlocker.serializer()),
            bridge.firmwareFlashBlockers(cond(conditions)),
        )

    override fun start(image: ByteArray, conditions: FlashShellConditions, nowMs: Long) =
        step(bridge.firmwareFlashStart(image, cond(conditions), nowMs.toULong()))

    override fun onWriteAck(nowMs: Long) = step(bridge.firmwareFlashOnWriteAck(nowMs.toULong()))
    override fun onWriteFailed(message: String, nowMs: Long) =
        step(bridge.firmwareFlashOnWriteFailed(message, nowMs.toULong()))
    override fun onFwMap(data: ByteArray, nowMs: Long) = step(bridge.firmwareFlashOnFwMap(data, nowMs.toULong()))
    override fun onReadFailed(nowMs: Long) = step(bridge.firmwareFlashOnReadFailed(nowMs.toULong()))
    override fun tick(nowMs: Long) = step(bridge.firmwareFlashTick(nowMs.toULong()))
    override fun linkRestored(nowMs: Long) = step(bridge.firmwareFlashLinkRestored(nowMs.toULong()))
    override fun cancel(nowMs: Long) = step(bridge.firmwareFlashCancel(nowMs.toULong()))
    override fun dismiss(): Boolean = bridge.firmwareFlashDismiss()
    override fun status(nowMs: Long) = step(bridge.firmwareFlashStatus(nowMs.toULong()))
}

/** Phases during which the flash must not be interrupted. */
internal val RUNNING_PHASES = setOf(
    FlashPhase.Preparing,
    FlashPhase.Erasing,
    FlashPhase.Uploading,
    FlashPhase.Verifying,
)

class FirmwareFlashDriver(
    private val core: FlashCorePort,
    private val io: FlashIoPort,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val onStatus: (FlashStatus?) -> Unit,
    private val onLog: (String) -> Unit = {},
    /** `true` while the flash must not be interrupted (FGS + screen on). */
    private val keepAlive: (Boolean) -> Unit = {},
) {
    private val lock = Mutex()
    private val queue = ArrayDeque<FlashAction>()
    private var pumpJob: Job? = null
    private var tickJob: Job? = null
    private var lastPublish = 0L
    private var uploadStartedAt: Long? = null

    /** The latest status snapshot (null with no flash). */
    @Volatile
    var current: FlashStatus? = null
        private set

    /** Start flashing [image]; returns the core's refusal, or null once under way. */
    suspend fun start(image: ByteArray, conditions: FlashShellConditions): FlashStartError? {
        val step = lock.withLock { core.start(image, conditions, clock()) }
        step.error?.let {
            lock.withLock { apply(step, force = true) }
            return it
        }
        attach()
        onLog("Firmware: flashing build ${step.status?.targetBuild} (${step.status?.chunksTotal} writes)")
        lock.withLock { apply(step, force = true) }
        return null
    }

    /** Cancel while the core still allows it. */
    suspend fun cancel() = lock.withLock {
        queue.clear()
        apply(core.cancel(clock()), force = true)
    }

    /** Forget a finished flash and stop the clock. */
    suspend fun dismiss(): Boolean {
        val cleared = lock.withLock { core.dismiss() }
        if (cleared) {
            detach()
            current = null
            keepAlive(false)
            io.setExclusive(false)
            onStatus(null)
        }
        return cleared
    }

    /** Re-read the status (the flow reopening) and re-attach if a flash exists. */
    suspend fun refresh(): FlashStatus? {
        val step = lock.withLock { core.status(clock()) }
        if (step.status != null) attach()
        lock.withLock { apply(step, force = true) }
        return current
    }

    /** The DE1 link dropped (the core already heard it via `de1LinkLost`). */
    fun onLinkLost() {
        scope.launch {
            lock.withLock {
                queue.clear()
                apply(core.status(clock()), force = true)
            }
        }
    }

    /** The DE1 link is back. */
    fun onLinkRestored() {
        scope.launch { lock.withLock { apply(core.linkRestored(clock()), force = true) } }
    }

    /** Stop the clock (the core keeps the flash). */
    fun dispose() = detach()

    private fun attach() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                // Skip while an action is in flight; timeouts are timestamp-based.
                if (lock.isLocked || pumpJob?.isActive == true) continue
                lock.withLock { apply(core.tick(clock())) }
            }
        }
    }

    private fun detach() {
        tickJob?.cancel()
        tickJob = null
    }

    /** Fold a step: publish, queue actions, keep the link. Caller holds [lock]. */
    private fun apply(step: FlashStep, force: Boolean = false) {
        val prev = current
        val status = step.status
        current = status
        io.setExclusive(status?.locksWrites == true)
        keepAlive(status != null && status.phase in RUNNING_PHASES)
        trackUpload(prev, status)
        val now = clock()
        val changed = prev?.phase != status?.phase || prev?.failure != status?.failure
        if (force || changed || now - lastPublish >= STATUS_PUBLISH_MS) {
            lastPublish = now
            onStatus(status)
        }
        if (changed && status != null && status.phase !in RUNNING_PHASES) {
            onLog("Firmware: ${status.phase.string}${status.failure?.let { " (${it::class.simpleName})" } ?: ""}")
        }
        if (step.actions.isNotEmpty()) {
            queue.addAll(step.actions)
            if (pumpJob?.isActive != true) pumpJob = scope.launch { pump() }
        }
    }

    private fun trackUpload(prev: FlashStatus?, next: FlashStatus?) {
        if (next?.phase == FlashPhase.Uploading && prev?.phase != FlashPhase.Uploading) uploadStartedAt = clock()
        val started = uploadStartedAt
        if (prev?.phase == FlashPhase.Uploading && next?.phase != FlashPhase.Uploading && started != null) {
            val n = next?.chunksAcked?.toLong() ?: 0L
            val ms = clock() - started
            if (n > 0) onLog("Firmware: $n writes in ${ms / 1000} s (${"%.1f".format(ms.toDouble() / n)} ms per write)")
            uploadStartedAt = null
        }
    }

    private suspend fun pump() {
        while (true) {
            val action = lock.withLock { queue.removeFirstOrNull() } ?: return
            perform(action)
        }
    }

    private suspend fun perform(action: FlashAction) {
        when (action) {
            is FlashAction.Write -> {
                val bytes = action.content.data.map { it.toByte() }.toByteArray()
                val failure = try {
                    io.write(action.content.target, bytes)
                    null
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    e
                }
                lock.withLock {
                    if (failure != null) {
                        queue.clear()
                        apply(core.onWriteFailed(failure.message ?: failure.toString(), clock()), force = true)
                    } else {
                        apply(core.onWriteAck(clock()))
                    }
                }
            }
            FlashAction.SubscribeFwMap -> runCatching {
                // Best effort: the poll covers a failed subscribe (decaid).
                io.subscribeFwMap { frame ->
                    scope.launch { lock.withLock { apply(core.onFwMap(frame, clock())) } }
                }
            }.onFailure { if (it is CancellationException) throw it else onLog("Firmware: A009 subscribe failed: ${it.message}") }
            FlashAction.ReadFwMap -> {
                val bytes = try {
                    io.readFwMap()
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    null
                }
                lock.withLock {
                    apply(if (bytes == null) core.onReadFailed(clock()) else core.onFwMap(bytes, clock()))
                }
            }
            FlashAction.ReadBuild -> {
                runCatching { io.readBuild() }.onFailure { if (it is CancellationException) throw it }
                // The reply lands via the normal notification path, which feeds
                // the core's flash; publish whatever it decided.
                lock.withLock { apply(core.status(clock()), force = true) }
            }
        }
    }
}
