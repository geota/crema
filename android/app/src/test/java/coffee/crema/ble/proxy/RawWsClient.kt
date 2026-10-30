package coffee.crema.ble.proxy

import java.io.Closeable
import java.io.DataInputStream
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * A minimal WebSocket client over a raw socket, for the acceptor's limits: it
 * can send any frame (fragments and continuations included) and read the
 * server's close code, which the regular clients don't expose.
 */
internal class RawWsClient(port: Int, path: String = LanRelayServer.PATH) : Closeable {
    private val socket = Socket("127.0.0.1", port).apply { soTimeout = 15_000 }
    private val out = socket.getOutputStream()
    private val inp = DataInputStream(socket.getInputStream())

    init {
        out.write(
            ("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").encodeToByteArray(),
        )
        out.flush()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) head.append(inp.readUnsignedByte().toChar())
        check(head.startsWith("HTTP/1.1 101")) { "no upgrade: $head" }
    }

    /** Send one (masked) frame with [opcode] (1 text, 2 binary, 0 continuation). */
    fun frame(opcode: Int, fin: Boolean, payload: ByteArray) {
        val header = java.io.ByteArrayOutputStream()
        header.write((if (fin) 0x80 else 0) or opcode)
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size < 65_536 -> { header.write(0x80 or 126); header.write(payload.size shr 8); header.write(payload.size and 0xFF) }
            else -> { header.write(0x80 or 127); for (i in 7 downTo 0) header.write(((payload.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        val mask = Random.nextBytes(4)
        header.write(mask)
        val masked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        runCatching {
            out.write(header.toByteArray())
            out.write(masked)
            out.flush()
        }
    }

    fun text(s: String) = frame(1, true, s.encodeToByteArray())

    /** Start a binary frame that declares [declaredLength] bytes but send only [sent] of them. */
    fun partialFrame(declaredLength: Long, sent: Int) {
        val header = java.io.ByteArrayOutputStream()
        header.write(0x80 or 2)
        header.write(0x80 or 127)
        for (i in 7 downTo 0) header.write(((declaredLength shr (8 * i)) and 0xFF).toInt())
        header.write(ByteArray(4)) // a zero mask
        runCatching {
            out.write(header.toByteArray())
            out.write(ByteArray(sent))
            out.flush()
        }
    }

    /** Read server frames until a close; its code, or null when the socket just ended. Binary
     *  messages seen on the way are counted in [binaries]. */
    fun awaitClose(timeoutMs: Int = 15_000): Int? {
        socket.soTimeout = timeoutMs
        try {
            while (true) {
                val b0 = inp.readUnsignedByte()
                val b1 = inp.readUnsignedByte()
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = inp.readUnsignedShort().toLong()
                else if (len == 127L) len = inp.readLong()
                val payload = ByteArray(len.toInt())
                inp.readFully(payload)
                when (b0 and 0x0F) {
                    8 -> return if (payload.size >= 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else 1005
                    2 -> binaries += payload
                    else -> Unit
                }
            }
        } catch (_: java.io.EOFException) {
            return null
        } catch (_: SocketTimeoutException) {
            return -1
        } catch (_: java.net.SocketException) {
            return null
        }
    }

    /** Binary messages read by [awaitClose]. */
    val binaries = mutableListOf<ByteArray>()

    override fun close() = runCatching { socket.close() }.let { }
}
