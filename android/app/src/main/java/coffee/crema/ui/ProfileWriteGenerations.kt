package coffee.crema.ui

import coffee.crema.core.WriteTarget

/**
 * Which profile writes may still reach the DE1 and be acknowledged to the core
 * (cohort review 2026-10-28, #2 N2 / #6 #3). Mirrors the web
 * `ProfileWriteGenerations`.
 *
 * The shell synthesises every profile ack itself, so it is the one place that
 * knows which upload a write belonged to. Without that, a superseded upload's
 * remaining frames still went out after the new upload's header, their acks
 * failed the new upload (`HeaderNotAcked`), and the automatic re-upload turned
 * it into a cascade through the re-upload budget; a batch also carried on
 * writing after one of its frames failed.
 *
 * - The profile writes of one `CoreOutput` form a batch. A batch with a header
 *   write opens a new generation; the others join the current one.
 * - A write goes out only while its batch is the current generation and no
 *   earlier write of the batch failed ([mayWrite]).
 * - [settle] says what follows a write: nothing (superseded, or the batch
 *   already failed), or the batch's first failure, which the caller reports
 *   to the core (`profileWriteFailed`) so the upload fails at once.
 *
 * [begin] runs on the core lane, the rest on the batch's coroutine: the
 * methods are synchronized.
 */
internal class ProfileWriteGenerations {
    class Batch internal constructor(val generation: Long) {
        internal var failed = false
    }

    enum class Outcome { ACK, DROP, FAILED }

    private var current = 0L

    /** The live generation (tests, logs). */
    @get:Synchronized
    val generation: Long get() = current

    /** The batch for one output's DE1 write [targets], or null with no profile write. */
    @Synchronized
    fun begin(targets: List<WriteTarget>): Batch? {
        if (targets.none(::isProfileWriteTarget)) return null
        if (WriteTarget.De1ProfileHeader in targets) current += 1
        return Batch(current)
    }

    /** Whether a write of [batch] may go out (or be acked) now. */
    @Synchronized
    fun mayWrite(batch: Batch): Boolean = batch.generation == current && !batch.failed

    /** Record a write's result: ACK, DROP, or the batch's first FAILED. */
    @Synchronized
    fun settle(batch: Batch, delivered: Boolean): Outcome = when {
        batch.generation != current || batch.failed -> Outcome.DROP
        delivered -> Outcome.ACK
        else -> {
            batch.failed = true
            Outcome.FAILED
        }
    }
}

/** Whether [target] is a profile-upload write. */
internal fun isProfileWriteTarget(target: WriteTarget): Boolean =
    target == WriteTarget.De1ProfileHeader || target == WriteTarget.De1ProfileFrame
