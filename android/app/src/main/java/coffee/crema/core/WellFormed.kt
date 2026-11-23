package coffee.crema.core

/**
 * This text with each lone surrogate replaced by [replacement], so it can
 * cross to the core as UTF-8 (UniFFI's string lowering throws on one). A
 * well-formed string is returned as it is.
 */
internal fun String.wellFormed(replacement: Char = '�'): String {
    if (none { it.isSurrogate() }) return this
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            out.append(c).append(this[i + 1])
            i += 2
            continue
        }
        out.append(if (c.isSurrogate()) replacement else c)
        i++
    }
    return out.toString()
}
