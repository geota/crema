package coffee.crema.ble.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An in-memory [FrameLink] pair that carries **wire text**, not [Frame]
 * objects: every frame is encoded with [FrameCodec] on send and decoded on
 * receipt, as [KtorWsFrameLink] does, so an in-process test exercises the
 * codec too. A text the receiver can't decode fails its [incoming] flow, as
 * on a socket.
 *
 * Each end has a [faults] plan for the frames it sends (drop, delay,
 * duplicate, or hold one back until the next frame: a reorder), and
 * [halfOpen] silences the connection both ways without either end seeing a
 * close, like a TCP that died without a FIN. Delays run on the pair's scope,
 * so a test scope gives virtual time. [sentText] is every text this end put
 * on the wire, in order; [sendRaw] puts text on it that no [Frame] encodes
 * to (a golden fixture, an unknown frame type).
 */
internal class CodecFrameLink private constructor(
    private val scope: CoroutineScope,
    private val outbound: Channel<String>,
    private val inbound: Channel<String>,
    private val connection: Connection,
) : FrameLink {

    /** State both ends share. */
    private class Connection {
        @Volatile var halfOpen = false
    }

    /** What this end does to the frames it sends. */
    val faults = Faults()

    /** Every text this end put on the wire, in order (dropped frames excluded). */
    val sentText: MutableList<String> = CopyOnWriteArrayList()

    /** Frames held back by [Faults.holdUntilNext], sent after the next frame. */
    private val held = ArrayDeque<String>()
    private val wireLock = Mutex()

    override suspend fun send(frame: Frame) {
        val text = FrameCodec.encode(frame)
        when (val fault = faults.take(frame)) {
            null -> put(text)
            Fault.Drop -> Unit
            Fault.Duplicate -> { put(text); put(text) }
            is Fault.Delay -> scope.launch { delay(fault.ms); put(text) }
            Fault.HoldUntilNext -> wireLock.withLock { held.addLast(text) }
        }
    }

    /** Put [text] on the wire as is, past the fault plan. */
    suspend fun sendRaw(text: String) = put(text)

    private suspend fun put(text: String) = wireLock.withLock {
        if (connection.halfOpen) return@withLock
        sentText += text
        outbound.send(text)
        while (held.isNotEmpty()) {
            val next = held.removeFirst()
            sentText += next
            outbound.send(next)
        }
    }

    override fun incoming(): Flow<Frame> =
        inbound.receiveAsFlow().filter { !connection.halfOpen }.map { FrameCodec.decode(it) }

    override suspend fun close() {
        outbound.close()
    }

    /** From now on nothing crosses in either direction, and neither end sees
     *  a close: its incoming flow just stays quiet. */
    fun halfOpen() {
        connection.halfOpen = true
    }

    /** One way to mistreat a frame. */
    sealed interface Fault {
        data object Drop : Fault
        data object Duplicate : Fault
        data class Delay(val ms: Long) : Fault
        data object HoldUntilNext : Fault
    }

    /** The fault plan for one end: rules tried in the order they were added;
     *  each applies to the first [times] frames it matches. */
    class Faults {
        private class Rule(val fault: Fault, var remaining: Int, val match: (Frame) -> Boolean)

        private val rules = mutableListOf<Rule>()

        fun drop(times: Int = 1, match: (Frame) -> Boolean) = add(Fault.Drop, times, match)
        fun duplicate(times: Int = 1, match: (Frame) -> Boolean) = add(Fault.Duplicate, times, match)
        fun delay(ms: Long, times: Int = 1, match: (Frame) -> Boolean) = add(Fault.Delay(ms), times, match)
        fun holdUntilNext(times: Int = 1, match: (Frame) -> Boolean) = add(Fault.HoldUntilNext, times, match)

        private fun add(fault: Fault, times: Int, match: (Frame) -> Boolean) {
            synchronized(rules) { rules += Rule(fault, times, match) }
        }

        internal fun take(frame: Frame): Fault? = synchronized(rules) {
            val rule = rules.firstOrNull { it.remaining > 0 && it.match(frame) } ?: return null
            rule.remaining--
            rule.fault
        }
    }

    companion object {
        /** A connected `(clientEnd, serverEnd)` pair; delayed frames are sent
         *  from [scope]. */
        fun pair(scope: CoroutineScope): Pair<CodecFrameLink, CodecFrameLink> {
            val toServer = Channel<String>(Channel.UNLIMITED)
            val toClient = Channel<String>(Channel.UNLIMITED)
            val connection = Connection()
            return CodecFrameLink(scope, outbound = toServer, inbound = toClient, connection) to
                CodecFrameLink(scope, outbound = toClient, inbound = toServer, connection)
        }
    }
}
