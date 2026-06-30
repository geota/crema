package coffee.crema.diag

import java.util.ArrayDeque

/**
 * A small process-wide ring buffer of recent log lines, bundled into the crash
 * report for context leading up to a failure.
 *
 * Distinct from the UI's `eventLog` (which dies with the process): the
 * uncaught-exception handler runs *inside* the dying process and snapshots this
 * before the OS tears it down. Bounded so it can never grow the heap;
 * `@Synchronized` because the handler can fire on any thread.
 */
object DiagLog {
    private const val MAX_LINES = 300
    private val lines = ArrayDeque<String>(MAX_LINES)

    @Synchronized
    fun add(line: String) {
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(line)
    }

    /** Oldest → newest. */
    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
