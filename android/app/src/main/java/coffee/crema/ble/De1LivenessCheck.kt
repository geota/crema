package coffee.crema.ble

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/**
 * One check of the DE1 dead-link rule (geota/crema#65), run once a second by
 * [De1BleManager]'s liveness loop. Same rule and numbers as the web
 * `De1Liveness` (`web/src/lib/ble/de1-liveness.ts`).
 *
 * A healthy connected DE1 streams water-level notifications about twice a
 * second, so while inbound traffic is recent this does nothing (and so never
 * reads mid-shot). After [QUIET_MS] of silence it issues a passive StateInfo
 * read ([probe]), which the DE1 answers even while asleep; a success counts as
 * traffic. Only [MAX_FAILURES] failed probes in a row, with no traffic in
 * between, report the link [Verdict.DEAD].
 *
 * Paused while a firmware flash holds the link ([paused]), as on the web
 * (round-2 verification V2 P3-3): the erase hold and the verify polls can be
 * quiet for longer than the ~10 s window, and the flash owns its I/O and its
 * own link-loss handling. A flash that takes the link while a probe is out
 * does not count the probe either.
 *
 * Clock, inbound stamp, pause flag and probe are injected so the rule runs in
 * a JVM test on virtual time.
 */
internal class De1LivenessCheck(
    private val now: () -> Long,
    private val lastInboundMs: () -> Long,
    private val paused: () -> Boolean,
    /** The passive StateInfo read; throws (or hangs) on a dead link. */
    private val probe: suspend () -> Unit,
) {
    /** What one [check] found. */
    enum class Verdict {
        /** A firmware flash holds the link: nothing checked. */
        PAUSED,

        /** Traffic is recent, or arrived while the probe was out. */
        RECENT,

        /** The link was quiet and the probe read succeeded. */
        ALIVE,

        /** A probe failed; [failures] in a row so far. */
        PROBE_FAILED,

        /** [MAX_FAILURES] probes failed in a row: drop the link. */
        DEAD,
    }

    /** Failed probes in a row (no traffic in between). */
    var failures = 0
        private set

    /** How long the link had been quiet at the last probe (for the log). */
    var quietMs = 0L
        private set

    private var lastProbeOkMs = now()

    /** One check; the caller acts on the [Verdict]. */
    suspend fun check(): Verdict {
        if (paused()) {
            failures = 0
            return Verdict.PAUSED
        }
        val startedMs = now()
        quietMs = startedMs - maxOf(lastInboundMs(), lastProbeOkMs)
        if (quietMs < QUIET_MS) {
            failures = 0
            return Verdict.RECENT
        }
        val ok = runCatching { withTimeout(PROBE_TIMEOUT_MS) { probe() } }.isSuccess
        // The probe's own timeout is a cancellation runCatching swallows; the
        // loop being cancelled is not.
        currentCoroutineContext().ensureActive()
        // Traffic that arrived while the probe was out proves the link alive
        // (the read lost to a busy GATT queue); a flash that took the link
        // meanwhile owns it now. Neither counts.
        if (!ok && (lastInboundMs() > startedMs || paused())) {
            failures = 0
            return Verdict.RECENT
        }
        if (ok) {
            lastProbeOkMs = now()
            failures = 0
            return Verdict.ALIVE
        }
        failures += 1
        if (failures >= MAX_FAILURES) {
            failures = 0
            return Verdict.DEAD
        }
        return Verdict.PROBE_FAILED
    }

    companion object {
        /** How often the liveness loop runs a check. Same numbers as the web
         *  `de1-liveness.ts`: a dead link is dropped about 10 s after its last
         *  notification (final review 02 N8 — reaprime 10 s without a push
         *  frame, Decenza 5 s after an abandoned write; the old 30 s / 15 s /
         *  3-probe rule took 60–75 s). */
        const val CHECK_INTERVAL_MS = 1_000L

        /** Inbound-notification silence tolerated before a probe read. A
         *  healthy connected DE1 streams water levels ~2/s, well inside this
         *  window, so a probe only ever happens on a link that has genuinely
         *  gone quiet — never mid-shot, and it doesn't wake the DE1. A
         *  successful probe counts as traffic (one read per window while the
         *  DE1 sleeps silently). */
        const val QUIET_MS = 5_000L

        /** One probe read's ceiling. */
        const val PROBE_TIMEOUT_MS = 2_000L

        /** Failed probes in a row (no traffic in between) before the link is
         *  presumed dead and torn down. */
        const val MAX_FAILURES = 2
    }
}
