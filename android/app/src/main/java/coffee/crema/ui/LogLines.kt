package coffee.crema.ui

/** The event log with [line] prepended, capped at [max] lines (newest first).
 *  Pure, so [MainViewModel]'s appendLog can apply it inside `_ui.update`. */
internal fun prependLogLine(log: List<String>, line: String, max: Int): List<String> =
    (listOf(line) + log).take(max)
