package coffee.crema.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-device reconnect timelines — the "why did it take so long to come back"
 * instrument.
 *
 * One [ReconnectTimeline] covers one reconnect EPISODE: it opens on a trigger
 * (an unexpected drop, the app returning to the foreground, Bluetooth coming
 * back on, a "Retry now" tap, …), collects a timestamped [PhaseMark] every
 * time the device's connect moves on (backoff wait → scan → GATT connect →
 * service discovery → subscriptions → READY → post-connect reads), and closes
 * with an [ReconnectOutcome]. A new trigger while an episode is open
 * SUPERSEDES it, so a foreground kick reads as its own clean
 * "foreground → READY in 2.1 s" record instead of being buried in the tail of
 * a 40-minute lurk.
 *
 * The last [ReconnectTimelineRecorder.capacity] episodes are kept in memory
 * (newest first) for the Settings → Advanced list and the diagnostics
 * snapshot; each finished episode is also rendered as one compact log line.
 *
 * Shell-side on purpose: the bookkeeping is a ring buffer and some
 * subtraction — no domain rule worth a typeshare type plus wasm and FFI
 * facades — and the timestamps it records are the shell's own BLE phases.
 */
enum class ReconnectTrigger(val label: String) {
    /** The link dropped unexpectedly (the supervisor's own reconnect). */
    DROP("drop"),

    /** The app came back to the foreground (or the screen was unlocked on it). */
    FOREGROUND("foreground"),

    /** The Bluetooth adapter came back on. */
    BLUETOOTH_ON("bt-on"),

    /** The companion-device presence callback saw the device nearby. */
    PRESENCE("presence"),

    /** The cold-start auto-connect to a remembered device. */
    LAUNCH("launch"),

    /** The user tapped "Retry now". */
    USER_RETRY("user-retry"),
}

/** One step of a (re)connect. Ordered roughly as a successful connect runs. */
enum class ReconnectPhase(val label: String) {
    /** Waiting out the backoff (or the slow lurk interval) before an attempt. */
    BACKOFF("backoff"),

    /** Idling while companion presence reports the device away (no scanning). */
    IDLE("idle"),

    /** A long-lived pending (autoConnect) connect, waiting for the device. */
    PENDING("pending"),

    /** Scanning / awaiting the device's advertisement. */
    SCAN("scan"),

    /** The GATT connect itself. */
    GATT_CONNECT("gatt"),

    /** Service discovery. */
    DISCOVER("discover"),

    /** Enabling notifications (CCCD writes). */
    SUBSCRIBE("subscribe"),

    /** The link is READY (closes the timed part of the episode). */
    READY("ready"),

    /** Post-connect seed reads finished (the DE1's firmware / state / settings). */
    POST_CONNECT("post-connect"),
}

enum class ReconnectOutcome(val label: String) {
    IN_PROGRESS("in progress"),
    READY("READY"),

    /** A newer trigger (e.g. a foreground kick) replaced this episode. */
    SUPERSEDED("superseded"),

    /** The user disconnected (or turned auto-connect off) mid-episode. */
    CANCELLED("cancelled"),

    /** The session ended without reconnecting (auto-reconnect off, torn down). */
    ABANDONED("abandoned"),
}

/** A phase entered at [atMs] (monotonic), during connect attempt [attempt]. */
data class PhaseMark(
    val phase: ReconnectPhase,
    val atMs: Long,
    val attempt: Int,
)

/** One reconnect episode — see the file header. All `*AtMs` are monotonic. */
data class ReconnectTimeline(
    val id: Long,
    /** "DE1" / "Scale". */
    val device: String,
    val trigger: ReconnectTrigger,
    val startedAtMs: Long,
    /** Wall-clock start, for display only. */
    val startedWallMs: Long,
    val marks: List<PhaseMark> = emptyList(),
    /** Connect attempts started in this episode. */
    val attempts: Int = 0,
    val failures: Int = 0,
    val lastError: String? = null,
    val outcome: ReconnectOutcome = ReconnectOutcome.IN_PROGRESS,
    val readyAtMs: Long? = null,
    val postConnectAtMs: Long? = null,
    val endedAtMs: Long? = null,
) {
    /** Trigger → READY, or null when it never got there. */
    val timeToReadyMs: Long? get() = readyAtMs?.let { it - startedAtMs }

    /**
     * Total time spent in each phase, summed across attempts, in phase order.
     * A phase lasts until the next mark (or the episode's end / READY for the
     * last one). READY and POST_CONNECT are points, not spans.
     */
    fun phaseDurations(): List<Pair<ReconnectPhase, Long>> {
        val totals = LinkedHashMap<ReconnectPhase, Long>()
        marks.forEachIndexed { i, mark ->
            if (mark.phase == ReconnectPhase.READY || mark.phase == ReconnectPhase.POST_CONNECT) return@forEachIndexed
            val end = marks.getOrNull(i + 1)?.atMs ?: readyAtMs ?: endedAtMs ?: return@forEachIndexed
            totals[mark.phase] = (totals[mark.phase] ?: 0L) + (end - mark.atMs).coerceAtLeast(0L)
        }
        // The post-connect span runs from READY to the seed reads finishing.
        if (readyAtMs != null && postConnectAtMs != null) {
            totals[ReconnectPhase.POST_CONNECT] = (postConnectAtMs - readyAtMs).coerceAtLeast(0L)
        }
        return ReconnectPhase.entries.mapNotNull { p -> totals[p]?.let { p to it } }
    }

    /**
     * One compact line for the event log / diagnostics, e.g.
     * `reconnect DE1 · foreground → READY in 2.4s · 1 attempt · scan 0.8s · gatt 1.1s · discover 0.2s · subscribe 0.3s · post-connect 0.6s`.
     */
    fun compactLine(): String = buildString {
        append("reconnect ").append(device).append(" · ").append(trigger.label).append(" → ")
        val ready = timeToReadyMs
        when {
            ready != null -> append("READY in ").append(secs(ready))
            endedAtMs != null -> append(outcome.label).append(" after ").append(secs(endedAtMs - startedAtMs))
            else -> append(outcome.label)
        }
        append(" · ").append(attempts).append(if (attempts == 1) " attempt" else " attempts")
        if (failures > 0) append(" (").append(failures).append(" failed)")
        phaseDurations().forEach { (phase, ms) -> append(" · ").append(phase.label).append(' ').append(secs(ms)) }
        if (outcome != ReconnectOutcome.READY && lastError != null) append(" · last error: ").append(lastError)
    }

    private fun secs(ms: Long): String = String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
}

/**
 * Records [ReconnectTimeline]s for any number of devices. Thread-safe (the
 * managers call in from their own scopes); every mutator is a cheap no-op
 * when the device has no open episode, so a first connect (no trigger) leaves
 * no record.
 *
 * @param nowMs the monotonic clock (`elapsedRealtime` in the app; virtual in tests).
 * @param wallMs the wall clock, for display.
 * @param onFinished called once per finished episode with its compact line
 *   (the app routes it to the event log + DiagLog).
 */
class ReconnectTimelineRecorder(
    private val nowMs: () -> Long,
    private val wallMs: () -> Long = { System.currentTimeMillis() },
    val capacity: Int = DEFAULT_CAPACITY,
    private val maxMarks: Int = DEFAULT_MAX_MARKS,
    @Volatile var onFinished: (ReconnectTimeline) -> Unit = {},
) {
    private val _timelines = MutableStateFlow<List<ReconnectTimeline>>(emptyList())

    /** Newest first; at most [capacity]. Includes open (in-progress) episodes. */
    val timelines: StateFlow<List<ReconnectTimeline>> = _timelines.asStateFlow()

    private var nextId = 1L

    /** Per device: the id of its open episode, if any. */
    private val open = HashMap<String, Long>()

    /** Per device: a READY episode still waiting for its post-connect mark. */
    private val awaitingPostConnect = HashMap<String, Long>()

    /** Open a new episode for [device]; a still-open one is closed as SUPERSEDED. */
    @Synchronized
    fun begin(device: String, trigger: ReconnectTrigger) {
        val now = nowMs()
        open[device]?.let { finish(it, ReconnectOutcome.SUPERSEDED, now) }
        flushPostConnect(device)
        val t = ReconnectTimeline(
            id = nextId++,
            device = device,
            trigger = trigger,
            startedAtMs = now,
            startedWallMs = wallMs(),
        )
        open[device] = t.id
        _timelines.value = (listOf(t) + _timelines.value).take(capacity)
    }

    /** Whether [device] has an open episode. */
    @Synchronized
    fun isOpen(device: String): Boolean = open.containsKey(device)

    /** [device] entered [phase] (during its current attempt). */
    @Synchronized
    fun mark(device: String, phase: ReconnectPhase) {
        val id = open[device] ?: return
        val now = nowMs()
        update(id) { t ->
            val attempt = t.attempts
            if (t.marks.size >= maxMarks) {
                // A days-long lurk would otherwise grow without bound: keep the
                // first marks (how it started) and the most recent ones.
                val keepHead = maxMarks / 4
                t.copy(marks = t.marks.take(keepHead) + t.marks.drop(t.marks.size - (maxMarks - keepHead - 1)) + PhaseMark(phase, now, attempt))
            } else {
                t.copy(marks = t.marks + PhaseMark(phase, now, attempt))
            }
        }
    }

    /** A connect attempt is starting. */
    @Synchronized
    fun attemptStarted(device: String) {
        val id = open[device] ?: return
        update(id) { it.copy(attempts = it.attempts + 1) }
    }

    /** The current attempt failed with [error]. */
    @Synchronized
    fun attemptFailed(device: String, error: String?) {
        val id = open[device] ?: return
        update(id) { it.copy(failures = it.failures + 1, lastError = error?.take(MAX_ERROR_CHARS)) }
    }

    /**
     * The link is READY. Closes the episode as READY; when [expectPostConnect]
     * the compact line waits for [postConnectDone] so it can include the
     * post-connect reads (flushed anyway by the next [begin]).
     */
    @Synchronized
    fun ready(device: String, expectPostConnect: Boolean = false) {
        val id = open.remove(device) ?: return
        val now = nowMs()
        update(id) {
            it.copy(
                marks = it.marks + PhaseMark(ReconnectPhase.READY, now, it.attempts),
                outcome = ReconnectOutcome.READY,
                readyAtMs = now,
                endedAtMs = now,
            )
        }
        if (expectPostConnect) awaitingPostConnect[device] = id else emit(id)
    }

    /** The post-connect reads for [device]'s last READY episode finished. */
    @Synchronized
    fun postConnectDone(device: String) {
        val id = awaitingPostConnect.remove(device) ?: return
        val now = nowMs()
        update(id) { it.copy(postConnectAtMs = now, marks = it.marks + PhaseMark(ReconnectPhase.POST_CONNECT, now, it.attempts)) }
        emit(id)
    }

    /** Close [device]'s open episode with [outcome] (CANCELLED / ABANDONED). */
    @Synchronized
    fun end(device: String, outcome: ReconnectOutcome) {
        val id = open[device] ?: return
        finish(id, outcome, nowMs())
    }

    private fun finish(id: Long, outcome: ReconnectOutcome, now: Long) {
        open.entries.removeAll { it.value == id }
        update(id) { it.copy(outcome = outcome, endedAtMs = now) }
        emit(id)
    }

    private fun flushPostConnect(device: String) {
        awaitingPostConnect.remove(device)?.let(::emit)
    }

    private fun emit(id: Long) {
        val t = _timelines.value.firstOrNull { it.id == id } ?: return
        runCatching { onFinished(t) }
    }

    private inline fun update(id: Long, f: (ReconnectTimeline) -> ReconnectTimeline) {
        _timelines.value = _timelines.value.map { if (it.id == id) f(it) else it }
    }

    companion object {
        const val DEFAULT_CAPACITY = 20
        const val DEFAULT_MAX_MARKS = 64
        private const val MAX_ERROR_CHARS = 160
    }
}
