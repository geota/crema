package coffee.crema.ui

import android.os.SystemClock
import coffee.crema.core.Command
import coffee.crema.core.CoreOutput
import coffee.crema.core.Event
import coffee.crema.core.WriteTarget
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The core lane: the single-threaded home for applying every `CoreOutput` and
 * for the heavy FFI calls (review #28), plus the command path that carries an
 * output's writes to the DE1 and the scale (MainViewModel breakup step 1).
 *
 * The generated bridge is synchronous blocking JNA and the VM's scope runs on
 * Main — so the profile upload chain janked the UI thread at the most
 * latency-sensitive moment, and event application ran concurrently from three
 * threads (the two BLE manager scopes + Main), racing the ticker
 * check-then-act guards. All output now applies on [dispatcher] (FIFO per
 * sender), and the heavy paths (startShot, the heartbeat clock) run their
 * bridge calls there instead of Main.
 *
 * What each [Event] does is registered with [on] by whoever owns the state it
 * changes; this class only routes. A field a handler writes is lane-owned:
 * any other thread changes it through [post], never directly (cohort review
 * 2026-10-25, Android P2-3).
 */
internal class CoreLane(
    private val scope: CoroutineScope,
    private val json: Json,
    private val log: (String) -> Unit,
    /** One DE1 write ([coffee.crema.ble.De1BleManager.writeCharacteristic]):
     *  target, bytes, the urgent (stop) lane, and the ack's upload id. */
    private val writeDe1: suspend (WriteTarget, ByteArray, Boolean, () -> UInt?) -> Boolean,
    /** One scale write ([coffee.crema.ble.ScaleBleManager.writeCommand]). */
    private val writeScale: suspend (ByteArray) -> Unit,
    /** Whether a shot is running: a machine-state write then IS the stop. */
    private val shotRunning: () -> Boolean,
    /** The core's output for a failed profile write of upload [UInt]. */
    private val profileWriteFailed: (UInt) -> String,
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    /** Event type → its handlers, in registration order. */
    private val handlers = ConcurrentHashMap<KClass<out Event>, List<(Event) -> Unit>>()

    /** Set by the first applied output; registering after it fails fast. */
    @Volatile
    private var applying = false

    /**
     * The outstanding stop write, if any (issue #15 follow-up). Two rules:
     * stop writes COALESCE (the enforcer re-commands every second, but
     * stacking identical Idle bytes behind a wedged op adds nothing — at most
     * one is ever queued/waiting), and the survivor is CANCELLED the moment
     * the shot ends ([cancelStaleStop]) — a stale Idle delivered seconds late
     * is not harmless: it wakes a sleeping machine and kills a steam/flush the
     * user starts right after the shot.
     */
    private val pendingStopWrite = AtomicReference<Job?>(null)

    /** Which profile writes may still go out and be acked (cohort review 2026-10-28, #2 N2). */
    private val profileWrites = ProfileWriteGenerations()

    /**
     * Run [handler] on the lane for every [E] an applied output carries, after
     * the handlers registered before it. Register while the owner is built,
     * before any output applies: a handler added later could miss events
     * already on their way.
     */
    inline fun <reified E : Event> on(noinline handler: (E) -> Unit) =
        register(E::class) { handler(it as E) }

    fun register(type: KClass<out Event>, handler: (Event) -> Unit) {
        check(!applying) { "Core event handler for ${type.simpleName} registered after output began to apply" }
        handlers.merge(type, listOf(handler)) { old, new -> old + new }
    }

    /** Run [block] on the lane, FIFO behind any queued output application.
     *  Every write to lane-owned state from another thread goes through here:
     *  a plain Main-thread write is not ordered against a queued lane task
     *  (cohort review 2026-10-25, Android P2-3). */
    fun post(block: () -> Unit) {
        scope.launch(dispatcher) { block() }
    }

    /**
     * The FFI/BLE seam: a JSON `CoreOutput` string came back from the bridge.
     * Confine ALL event/command application to the lane (review #28): callers
     * arrive from Main, the DE1 manager scope, and the scale manager scope —
     * launches enqueue FIFO per sender onto the single thread, so the
     * handlers' check-then-act state (tickers, pending flags) is
     * single-threaded by construction. The guard also keeps one bad payload
     * from killing the lane.
     */
    fun deliver(raw: String) {
        scope.launch(dispatcher) {
            runCatching { applyJson(raw) }
                .onFailure { log("Core output application failed: ${it.message}") }
        }
    }

    /** Route a core-output-producing machine write through the shared command
     *  path, logging (not throwing) on failure. */
    fun route(label: String, block: () -> String) {
        val raw = runCatching { block() }.getOrElse {
            log("$label failed: ${it.message}")
            return
        }
        deliver(raw)
    }

    /** Apply [raw] now, on the caller — which must already be on the lane. */
    fun applyJson(raw: String) {
        val output: CoreOutput = runCatching {
            json.decodeFromString(CoreOutput.serializer(), raw)
        }.getOrElse {
            log("JSON parse error: ${it.message}")
            return
        }
        applyOutput(output)
    }

    /** Apply [output] now, on the caller — which must already be on the lane. */
    fun applyOutput(output: CoreOutput) {
        applying = true
        output.events.forEach { event -> handlers[event::class]?.forEach { it(event) } }
        // DE1 writes of one output go out IN ORDER on one coroutine (cohort
        // review 02 P2-2): a write retried after a failure must not land
        // after the writes that followed it — an Idle bounce retried behind
        // its Espresso would stop the shot that just started, and a retried
        // profile frame would arrive out of order. Scale writes are another
        // device and keep their own coroutines.
        val de1Writes = output.commands.filterIsInstance<Command.WriteCharacteristic>()
        output.commands.filterIsInstance<Command.WriteScale>().forEach(::executeCommand)
        if (de1Writes.isNotEmpty()) executeDe1Writes(de1Writes, startedUploadId(output.events))
    }

    /** The machine stopped: cancel any stop write still queued or waiting on
     *  the GATT mutex. Delivered late, that same Idle byte would wake a
     *  sleeping machine or kill a steam/flush the user starts next (issue #15
     *  follow-up). */
    fun cancelStaleStop() {
        pendingStopWrite.getAndSet(null)?.let { job ->
            if (job.isActive) {
                job.cancel(kotlinx.coroutines.CancellationException("machine already stopped"))
                log("Stale stop write cancelled — machine already stopped")
            }
        }
    }

    /**
     * Centralised command execution: every `CoreOutput` — from the DE1 manager
     * AND the scale manager — funnels its `commands` here. This single path
     * makes both the manual Tare button and the core's automatic shot-start
     * auto-tare work, because both surface as a [Command.WriteScale].
     *
     * A scale write is dispatched on [scope]: `writeCommand` is a
     * `suspend fun`, so it awaits the BLE write on a coroutine rather than
     * blocking the BLE binder thread this may be called from.
     */
    private fun executeCommand(command: Command) {
        when (command) {
            is Command.WriteScale -> {
                // The core hands over the exact bytes as a List<UByte>.
                val bytes = command.content.data
                    .map { it.toByte() }
                    .toByteArray()
                // Fire-and-forget: a BLE write can fail (peer dropped, transient
                // stack error → Nordic throws OperationFailedException). Log it here
                // rather than let it escape the launch and crash the app; the
                // reconnect supervisor handles an actual disconnect on its own.
                scope.launch {
                    try {
                        writeScale(bytes)
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        log("Scale write failed: ${e.message}")
                    }
                }
            }
            is Command.WriteCharacteristic -> executeDe1Writes(listOf(command))
        }
    }

    /**
     * Write [writes] (the DE1 commands of ONE `CoreOutput`) in order on a single
     * coroutine — each waits for the previous, including its one retry — so a
     * retry can never reorder them (cohort review 02 P2-2; reaprime awaits
     * each write in sequence, de1app runs one FIFO). A failed write is logged
     * and the rest still go out, as before. The BLE manager maps each target
     * to a UUID and writes; a profile header / frame write also gets its
     * synthesized ack. This is the machine-control path (AND5).
     */
    private fun executeDe1Writes(writes: List<Command.WriteCharacteristic>, uploadId: UInt? = null) {
        // A machine-state write during a shot IS the stop (issue #15).
        val shotRunning = shotRunning()
        fun isStop(c: Command.WriteCharacteristic) =
            c.content.target == WriteTarget.De1RequestedState && shotRunning
        // Coalesce stop writes: if one is already queued/waiting, a second
        // copy of the same Idle byte buys nothing — and every extra copy is a
        // stale-write risk after the machine stops.
        val batch = writes.filter { c ->
            if (isStop(c) && pendingStopWrite.get()?.isActive == true) {
                log("Stop write already in flight — not stacking another")
                false
            } else {
                true
            }
        }
        if (batch.isEmpty()) return
        // The profile writes of this output: a header write supersedes every
        // older upload's remaining writes (cohort review 2026-10-28, #2 N2).
        val profileBatch = profileWrites.begin(batch.map { it.content.target }, uploadId)
        if (profileWrites.unnamed(profileBatch)) log(UNNAMED_PROFILE_BATCH_LOG)
        val job = scope.launch {
            executeInOrder(batch) { c ->
                val profile = profileBatch?.takeIf { isProfileWriteTarget(c.content.target) }
                if (profile == null) {
                    deliverDe1Write(c, stopWrite = isStop(c))
                    return@executeInOrder
                }
                // Superseded, or an earlier write of this upload failed:
                // nothing more of it reaches the DE1.
                if (!profileWrites.mayWrite(profile)) return@executeInOrder
                val delivered = deliverDe1Write(c, stopWrite = false) {
                    profile.uploadId.takeIf { profileWrites.mayWrite(profile) }
                }
                if (profileWrites.settle(profile, delivered) == ProfileWriteGenerations.Outcome.FAILED) {
                    // Fail the upload now rather than on the ack watchdog.
                    log("Profile write failed — stopping this upload")
                    // Named by its upload: this runs on Main, so a shot start
                    // on the core lane may already have begun a newer upload,
                    // which the core must leave alone (round-2 V3 #1).
                    profile.uploadId?.let { id -> deliver(profileWriteFailed(id)) }
                }
            }
        }
        if (batch.any(::isStop)) {
            pendingStopWrite.set(job)
            job.invokeOnCompletion { pendingStopWrite.compareAndSet(job, null) }
        }
    }

    /** One DE1 write from [executeDe1Writes]; failures are logged, never thrown.
     *  Returns whether the write reached the DE1. [ackUploadId] names a profile
     *  write's upload for its synthesized ack, null when superseded (see [ProfileWriteGenerations]). */
    private suspend fun deliverDe1Write(
        command: Command.WriteCharacteristic,
        stopWrite: Boolean,
        ackUploadId: () -> UInt? = { null },
    ): Boolean {
        val bytes = command.content.data
            .map { it.toByte() }
            .toByteArray()
        // Time a stop write, so a field log shows exactly how long the stop
        // took to reach the machine (queue contention, retries, a wedged link
        // all show up here as milliseconds).
        val queuedAt = if (stopWrite) SystemClock.elapsedRealtime() else 0L
        // A failed machine write (e.g. the keep-alive poke as the DE1 drops)
        // must not become an uncaught crash.
        // A write's timeout or preemption arrives as De1WriteFailedException
        // (an IOException, logged here); only the batch's own teardown is a
        // cancellation (cohort review 2026-10-28, Android P2-1).
        return deliverOrLog(
            write = {
                // Stop writes ride the urgent lane (issue #15): they preempt any
                // in-flight non-critical GATT op instead of queuing behind it on
                // the global op mutex.
                val written = writeDe1(command.content.target, bytes, stopWrite, ackUploadId)
                if (stopWrite && written) {
                    log("Stop write delivered in ${SystemClock.elapsedRealtime() - queuedAt} ms")
                }
                written
            },
            log = { e ->
                log(
                    if (stopWrite) {
                        "STOP WRITE FAILED after ${SystemClock.elapsedRealtime() - queuedAt} ms: ${e.message}"
                    } else {
                        "DE1 write failed (${command.content.target}): ${e.message}"
                    },
                )
            },
        )
    }
}
