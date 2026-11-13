package coffee.crema.ble.proxy

/**
 * Moves a primary's notification times onto this device's clock (#319).
 *
 * The primary stamps each notification with its own `elapsedRealtime`, but a
 * mirror's core judges staleness against its own. Two devices' uptimes differ
 * by hours, so forwarded times made every watchdog fire (or never fire) on a
 * mirror.
 *
 * The offset is the smallest `local - remote` seen this session: the frame
 * that travelled fastest. A first frame can't be trusted alone, because the
 * attach snapshot replays cached values that keep their old times. With the
 * smallest offset, a rebased time is never later than the moment the frame
 * arrived, and live frames keep their spacing. Output never goes backwards, so
 * a lowered offset can't make time run back for the core.
 *
 * [reset] starts a new session after a (re)handshake: the primary may have
 * restarted, and then its clock starts again from boot.
 */
internal class MirrorClock(private val now: () -> Long) {
    private var offset: Long? = null
    private var last = Long.MIN_VALUE

    @Synchronized
    fun rebase(remoteMs: Long): Long {
        val seen = now() - remoteMs
        val o = offset?.let { minOf(it, seen) } ?: seen
        offset = o
        last = maxOf(last, remoteMs + o)
        return last
    }

    @Synchronized
    fun reset() {
        offset = null
    }
}
