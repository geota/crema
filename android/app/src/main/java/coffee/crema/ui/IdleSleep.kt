package coffee.crema.ui

import coffee.crema.core.IdleActivity
import coffee.crema.core.IdleSleepInput
import coffee.crema.core.IdleSleepInputActivityInner
import coffee.crema.core.IdleSleepInputConfigureInner
import coffee.crema.core.IdleSleepInputLinkInner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The DE1 idle-sleep timer ("Sleep after"), shell side (web twin:
 * `lib/state/idle-sleep.ts`).
 *
 * The timer itself is core-owned (`CremaCore::idle_sleep`,
 * `de1_domain::idle_sleep`): it decides when to send Sleep, never re-sends a
 * sleep that landed, never sends while the link is down, retries a blocked
 * expiry on every tick, and pokes UserPresent only on real activity. This
 * file only feeds it — the setting, user activity, the link state and a
 * 30 s tick on `elapsedRealtime` (which keeps counting through deep sleep, so
 * a tick delayed by Doze fires on arrival instead of losing the time).
 */

/** The default "Sleep after", minutes — the core's `DEFAULT_IDLE_SLEEP_MIN`
 *  (30, as decaid's `sleepTimeoutMinutes`). */
const val DEFAULT_IDLE_SLEEP_MIN = 30

/** The tick cadence — the core's `IDLE_SLEEP_TICK_MS`. */
const val IDLE_SLEEP_TICK_MS = 30_000L

/** Touches arrive per pointer event; the timer needs at most one a second. */
const val IDLE_ACTIVITY_THROTTLE_MS = 1_000L

/** The "Sleep after" choices when the native core can't be asked (JVM
 *  tests, previews) — the core's `IDLE_SLEEP_CHOICES_MIN`. */
val IDLE_SLEEP_FALLBACK_CHOICES = listOf(0, 15, 30, 60, 90, 120)

/** The choices, from the core. */
fun idleSleepChoices(): List<Int> =
    runCatching { coffee.crema.core.idleSleepChoices().map { it.toInt() } }
        .getOrDefault(IDLE_SLEEP_FALLBACK_CHOICES)

/** How a choice reads in Settings: "Never", "15 min", … */
fun idleSleepLabel(minutes: Int): String = if (minutes <= 0) "Never" else "$minutes min"

/** The stepper position of [minutes] in [choices] (an off-list value snaps to the nearest). */
internal fun idleSleepIndex(minutes: Int, choices: List<Int>): Int =
    choices.indices.minByOrNull { kotlin.math.abs(choices[it] - minutes) } ?: 0

private val inputJson = Json { encodeDefaults = true }

/** One timer input as the JSON the core's `idle_sleep` facade reads. */
internal fun idleSleepInputJson(input: IdleSleepInput): String =
    inputJson.encodeToString(IdleSleepInput.serializer(), input)

internal fun configureInput(minutes: Int) =
    IdleSleepInput.Configure(IdleSleepInputConfigureInner(minutes.coerceAtLeast(0).toUInt()))

internal fun activityInput(kind: IdleActivity) =
    IdleSleepInput.Activity(IdleSleepInputActivityInner(kind))

internal fun linkInput(ready: Boolean) = IdleSleepInput.Link(IdleSleepInputLinkInner(ready))

/**
 * Feeds the core timer. [feed] hands one input + `now` to the core and routes
 * its output; [clock] is `elapsedRealtime`. Activity is throttled to
 * [IDLE_ACTIVITY_THROTTLE_MS] except for the kinds that matter on their own
 * (foreground return, mirror relay, navigation).
 */
internal class IdleSleepRunner(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val feed: (IdleSleepInput, Long) -> Unit,
    private val intervalMs: Long = IDLE_SLEEP_TICK_MS,
) {
    private var job: Job? = null
    private var lastTouchAt: Long? = null

    /** Start the periodic tick (idempotent). */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                tick()
            }
        }
    }

    /** Stop the tick (VM teardown). */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** The user's timeout, minutes (0 = Never). */
    fun configure(minutes: Int) = feed(configureInput(minutes), clock())

    /** The DE1 link became ready / went away. */
    fun link(ready: Boolean) = feed(linkInput(ready), clock())

    /** One check now — on the cadence, and whenever the app regains the CPU. */
    fun tick() = feed(IdleSleepInput.Tick, clock())

    /** The user did something. */
    fun activity(kind: IdleActivity) {
        val now = clock()
        if (kind == IdleActivity.Touch || kind == IdleActivity.Key) {
            val last = lastTouchAt
            if (last != null && now - last in 0 until IDLE_ACTIVITY_THROTTLE_MS) return
            lastTouchAt = now
        }
        feed(activityInput(kind), now)
    }
}

/**
 * Whether sleep-on-quit should send its own Sleep: not when the idle timer
 * sent one moments ago that the machine hasn't confirmed yet (the core adopts
 * an app Sleep the other way round), so the two never double-send.
 */
internal fun quitSleepAllowed(lastIdleSleepAtMs: Long, nowMs: Long, windowMs: Long): Boolean =
    lastIdleSleepAtMs == 0L || nowMs - lastIdleSleepAtMs >= windowMs
