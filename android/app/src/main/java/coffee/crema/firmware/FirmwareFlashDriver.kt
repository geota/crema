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
import java.util.concurrent.atomic.AtomicBoolean

/*
 * The Android I/O half of a DE1 firmware flash — the twin of the web's
 * `$lib/firmware/flash-driver.ts`.
 *
 * Every protocol decision lives in the Rust core (`de1_app::firmware_flash`):
 * which bytes, which characteristic, when a phase is over, when to give up, how
 * long the link stays locked. The core answers each input with a `FlashStep` —
 * actions plus a status snapshot. This driver performs the actions strictly one
 * at a time (one write-with-response in flight — de1app
 * `bluetooth.tcl:3303-3312`, decaid `unified_de1.firmware.dart:239`), reports
 * every ACK / failure / read back, routes A009 notifications and link events,
 * ticks the core every [TICK_MS] (also while an action is in flight), holds the
 * DE1 link exclusively while the core locks writes, waits for ordinary writes
 * already on the air before the first flash write (decaid's gate drains), and
 * asks for the keep-alive (foreground service + screen on) while a flash runs.
 *
 * All core calls happen under one [Mutex], so a tick, a notification and an ACK
 * never interleave. Queued actions carry the generation they were issued in; a
 * cancel, failure or link loss starts a new one, so nothing queued before it is
 * written after it (audit L1).
 */

/** The core's flash clock cadence (decaid polls A009 every 250 ms). */
const val TICK_MS = 250L

/** How often a running upload republishes its status (UI churn bound). */
const val STATUS_PUBLISH_MS = 250L

/** Longest wait for ordinary writes to drain before the first flash write. */
const val DRAIN_TIMEOUT_MS = 6_000L

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
    fun exit(): Boolean
    fun status(nowMs: Long): FlashStep
    fun restoreAwaiting(build: UInt, unconfirmed: Boolean, nowMs: Long): FlashStep
    fun restoreEraseHold(build: UInt, holdMsLeft: Long, nowMs: Long): FlashStep
}

/** The DE1 link surface the driver needs ([coffee.crema.ble.De1BleManager]). */
interface FlashIoPort {
    /** One flash write with response, allowed through the lock; throws on failure. */
    suspend fun write(target: WriteTarget, data: ByteArray)

    /** A fresh GATT read of A009. */
    suspend fun readFwMap(): ByteArray

    /** Enable A009 notifications, delivering frames to [onFrame]. */
    suspend fun subscribeFwMap(onFrame: (ByteArray) -> Unit)

    /** Wait for ordinary writes already on the air; false if they didn't drain. */
    suspend fun awaitQuiet(): Boolean

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
    override fun exit(): Boolean = bridge.firmwareFlashExit()
    override fun status(nowMs: Long) = step(bridge.firmwareFlashStatus(nowMs.toULong()))
    override fun restoreAwaiting(build: UInt, unconfirmed: Boolean, nowMs: Long) =
        step(bridge.firmwareFlashRestoreAwaiting(build, unconfirmed, nowMs.toULong()))
    override fun restoreEraseHold(build: UInt, holdMsLeft: Long, nowMs: Long) =
        step(bridge.firmwareFlashRestoreEraseHold(build, holdMsLeft.toULong(), nowMs.toULong()))
}

/** Phases during which the flash must not be interrupted. */
internal val RUNNING_PHASES = setOf(
    FlashPhase.Preparing,
    FlashPhase.Erasing,
    FlashPhase.Uploading,
    FlashPhase.Verifying,
)

private data class Queued(val action: FlashAction, val gen: Int)

class FirmwareFlashDriver(
    private val core: FlashCorePort,
    private val io: FlashIoPort,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val onStatus: (FlashStatus?) -> Unit,
    private val onLog: (String) -> Unit = {},
    /** `true` while the flash must not be interrupted (FGS + screen on). */
    private val keepAlive: (Boolean) -> Unit = {},
    /** Logged at each start: the transport, the phone and the DE1 address. */
    private val transportLabel: () -> String = { "Android BLE" },
) {
    private val lock = Mutex()
    private val queue = ArrayDeque<Queued>()
    private var gen = 0
    private var pumpJob: Job? = null
    private var tickJob: Job? = null
    private val tickPending = AtomicBoolean(false)
    private var lastPublish = 0L
    private var uploadStartedAt: Long? = null
    private var lastKeepAlive: Boolean? = null

    /** Set by [start]: the next pump first waits for ordinary writes to drain. */
    @Volatile
    private var drainBeforeFirstWrite = false

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
        onLog("Firmware: flashing build ${step.status?.targetBuild} (${step.status?.chunksTotal} writes) over ${transportLabel()}")
        lock.withLock {
            gen++
            drainBeforeFirstWrite = true
            apply(step, force = true)
        }
        return null
    }

    /** Cancel while the core still allows it (its Sleep follows). */
    suspend fun cancel() = lock.withLock {
        gen++
        queue.clear()
        apply(core.cancel(clock()), force = true)
    }

    /** Forget a finished, unlocked flash and stop the clock. */
    suspend fun dismiss(): Boolean {
        val cleared = lock.withLock { core.dismiss() }
        if (cleared) released()
        return cleared
    }

    /** The user's explicit exit from the power-cycle step. */
    suspend fun exit(): Boolean {
        val cleared = lock.withLock { core.exit() }
        if (cleared) released()
        return cleared
    }

    private suspend fun released() {
        lock.withLock {
            gen++
            queue.clear()
        }
        detach()
        current = null
        setKeepAlive(false)
        io.setExclusive(false)
        onStatus(null)
    }

    /** Re-read the status (the flow reopening, a restore) and re-attach if a flash exists. */
    suspend fun refresh(): FlashStatus? {
        val step = lock.withLock { core.status(clock()) }
        if (step.status != null) attach()
        lock.withLock { apply(step, force = true) }
        return current
    }

    /** Bring back a power-cycle step persisted before a process restart. */
    suspend fun restoreAwaiting(build: UInt, unconfirmed: Boolean): FlashStatus? {
        lock.withLock { apply(core.restoreAwaiting(build, unconfirmed, clock()), force = true) }
        return refresh()
    }

    /** Bring back the lock of an erase that may still be running (persisted deadline). */
    suspend fun restoreEraseHold(build: UInt, holdMsLeft: Long): FlashStatus? {
        lock.withLock { apply(core.restoreEraseHold(build, holdMsLeft, clock()), force = true) }
        return refresh()
    }

    /** The DE1 link dropped (the core already heard it via `de1LinkLost`). */
    fun onLinkLost() {
        scope.launch {
            lock.withLock {
                gen++
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
                // Ticks reach the core while an action is in flight too (the
                // core's timeouts are timestamp-based); coalesced.
                if (!tickPending.compareAndSet(false, true)) continue
                try {
                    lock.withLock { apply(core.tick(clock())) }
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    // Never let one bad tick stop the clock while the lock is
                    // held: the erase hold and the timeouts depend on it
                    // (audit r2 LOW 5).
                    onLog("Firmware: tick error (clock keeps running): ${e.message}")
                } finally {
                    tickPending.set(false)
                }
            }
        }
    }

    private fun detach() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun setKeepAlive(on: Boolean) {
        if (lastKeepAlive == on) return
        lastKeepAlive = on
        keepAlive(on)
    }

    /** Fold a step: publish, queue actions, keep the link. Caller holds [lock]. */
    private fun apply(step: FlashStep, force: Boolean = false) {
        // The core's per-attempt diagnostic lines ([+mm:ss] stamped) go to the
        // event log, which the diagnostics export carries.
        step.log?.forEach { onLog("Firmware: $it") }
        val prev = current
        val status = step.status
        current = status
        io.setExclusive(status?.locksWrites == true)
        setKeepAlive(status != null && status.phase in RUNNING_PHASES)
        trackUpload(prev, status)
        val now = clock()
        val changed = prev?.phase != status?.phase || prev?.failure != status?.failure ||
            prev?.locksWrites != status?.locksWrites || prev?.restartedOnBuild != status?.restartedOnBuild
        if (force || changed || now - lastPublish >= STATUS_PUBLISH_MS) {
            lastPublish = now
            onStatus(status)
        }
        if (changed && status != null && status.phase !in RUNNING_PHASES) {
            onLog(
                "Firmware: ${status.phase.string}${status.failure?.let { " (${it::class.simpleName})" } ?: ""}" +
                    if (status.locksWrites) ", link held" else "",
            )
        }
        if (step.actions.isNotEmpty()) {
            step.actions.forEach { queue.addLast(Queued(it, gen)) }
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
        if (drainBeforeFirstWrite) {
            drainBeforeFirstWrite = false
            if (!io.awaitQuiet()) onLog("Firmware: ordinary writes still in flight after ${DRAIN_TIMEOUT_MS / 1000} s")
        }
        while (true) {
            val next = lock.withLock { queue.removeFirstOrNull() } ?: return
            try {
                perform(next)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                // A driver bug, not a GATT failure: fail the flash through the
                // core (which keeps any erase hold) instead of leaving it
                // stalled with the lock held (audit r2 LOW 5).
                onLog("Firmware: driver error: ${e.message}")
                lock.withLock {
                    gen++
                    queue.clear()
                    apply(core.onWriteFailed("driver error: ${e.message}", clock()), force = true)
                }
                return
            }
        }
    }

    private suspend fun perform(item: Queued) {
        // Issued before a cancel / failure / link loss: never performed after it.
        if (lock.withLock { item.gen != gen }) return
        when (val action = item.action) {
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
                        gen++
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
            is FlashAction.ReadBuild -> {
                // The MMR read request goes through the lock like the flash's own
                // writes; the reply arrives as an ordinary MMR notification.
                val bytes = action.content.data.map { it.toByte() }.toByteArray()
                runCatching { io.write(WriteTarget.De1MmrRequest, bytes) }
                    .onFailure { if (it is CancellationException) throw it }
                lock.withLock { apply(core.status(clock()), force = true) }
            }
        }
    }
}
