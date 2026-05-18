package coffee.crema.ble

import android.content.Context
import android.util.Log
import coffee.crema.core.NotificationSource
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records a live BLE session to a file so it can be replayed offline through
 * the Rust core (`cargo run -p de1-app --example replay`) — letting the core's
 * decode logic be validated with no DE1, no scale, and no Bluetooth.
 *
 * One recorder instance is shared per *session*: [MainViewModel] owns it and
 * hands the same instance to both [De1BleManager] and [ScaleBleManager], so a
 * session with a DE1 and/or a Bookoo scale produces ONE interleaved capture
 * file. Replaying that file reconstructs the whole timeline through a single
 * `CremaCore`, which matters for scale-aware behaviour like shot-start
 * auto-tare. A connection counter opens the file on the first device connect
 * and closes it on the last disconnect — see [open] / [close].
 *
 * ## Capture format — JSON Lines, one BLE message per line, no header
 *
 * Each line is `{"t": <u64 ms>, "dir": "in"|"out", "src": "<string>", "hex":
 * "<lowercase hex payload>"}`:
 *  - `t` — millisecond timestamp; here `SystemClock.elapsedRealtime()`, the
 *    same monotonic value passed to [coffee.crema.core.CremaBridge.onNotification].
 *  - `dir` — `"in"` is a notification received from a device (DE1 or scale);
 *    `"out"` is a command written to one.
 *  - `src` — for `dir:"in"`, a [NotificationSource] enum name (`DE1_STATE`,
 *    `DE1_SHOT_SAMPLE`, `DE1_WATER_LEVELS`, `SCALE_WEIGHT`) or the `SCALE_FF12`
 *    label for Bookoo command-characteristic notifications (the replay tool
 *    maps it to the `SCALE_COMMAND` source); an arbitrary string is still
 *    allowed for any future inbound traffic the core does not model; for
 *    `dir:"out"`, a short label for the characteristic (e.g. `SCALE_COMMAND`
 *    for a tare/timer write).
 *  - `hex` — raw bytes, lowercase hex, no separators.
 *
 * The Rust replay tool (`core/de1-app/examples/replay.rs`) parses the same
 * format; keep the two in sync.
 *
 * Recording is a debug aid: file IO failures are logged and swallowed, never
 * propagated, so a recording problem can never crash the app. Notifications
 * arrive on a binder thread, so appends are synchronized; each line is flushed
 * immediately since the volume is low (~4–10 Hz).
 */
class BleSessionRecorder(private val context: Context) {

    /** Guards [writer], [connections] and all file IO; appends arrive from a binder thread. */
    private val lock = Any()

    /** The open capture file's writer, or `null` when not recording. */
    private var writer: BufferedWriter? = null

    /**
     * How many devices ([De1BleManager] / [ScaleBleManager]) are currently
     * connected. The capture file opens on the 0→1 transition and closes on the
     * 1→0 transition, so one file spans a whole session regardless of how many
     * devices join or leave it.
     */
    private var connections = 0

    /** Absolute path of the current capture file, or `null` when not recording. */
    var filePath: String? = null
        private set

    /**
     * Register a device connection. On the first connection (0→1) this opens a
     * new capture file under `getExternalFilesDir(null)/captures/` and begins
     * recording; later connections only bump the counter so they join the same
     * file. Returns the capture file path if this call opened the file (so the
     * caller can surface it once via status), or `null` otherwise.
     */
    fun open(): String? {
        synchronized(lock) {
            connections++
            if (connections > 1) return null
            start()
            return filePath
        }
    }

    /**
     * Deregister a device connection. On the last disconnection (1→0) this
     * flushes and closes the capture file; earlier disconnections only
     * decrement the counter so the file stays open for the remaining devices.
     */
    fun close() {
        synchronized(lock) {
            if (connections == 0) return
            connections--
            if (connections == 0) stop()
        }
    }

    /**
     * Open a new capture file under `getExternalFilesDir(null)/captures/` and
     * begin recording. A no-op if a recording is already in progress. On any IO
     * failure the recorder simply stays inactive — it never throws. Callers go
     * through [open]; this is the connection-counter's 0→1 action.
     */
    private fun start() {
        synchronized(lock) {
            if (writer != null) return
            try {
                val dir = File(context.getExternalFilesDir(null), "captures")
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "Could not create captures directory ${dir.absolutePath}")
                    return
                }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(dir, "session-$stamp.jsonl")
                writer = BufferedWriter(FileWriter(file))
                filePath = file.absolutePath
                Log.i(TAG, "Recording BLE session to ${file.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Could not start BLE session recording", e)
                writer = null
                filePath = null
            }
        }
    }

    /**
     * Append one `dir:"in"` line for a notification received from a device.
     *
     * [source] is the [NotificationSource] — a DE1 characteristic
     * (`DE1_STATE`, `DE1_SHOT_SAMPLE`, `DE1_WATER_LEVELS`) or the scale's
     * `SCALE_WEIGHT`. [tMs] must be the same timestamp handed to
     * [coffee.crema.core.CremaBridge.onNotification] for this notification, so
     * the capture and the live decode agree exactly.
     */
    fun recordInbound(source: NotificationSource, data: ByteArray, tMs: Long) {
        recordInbound(source.name, data, tMs)
    }

    /**
     * Append one `dir:"in"` line for a notification received from a device,
     * tagged with an arbitrary [src] label rather than a [NotificationSource].
     *
     * This is used for notifications from the Bookoo command characteristic
     * `ff12`, recorded as `src:"SCALE_FF12"`; the replay tool maps that label
     * to the core's `SCALE_COMMAND` source. It also remains available for any
     * future inbound traffic the core does not model. The capture line is
     * otherwise identical to the [NotificationSource]-typed overload, which now
     * delegates here. [tMs] must be the same `elapsedRealtime()` timestamp the
     * notification was stamped with at delivery.
     */
    fun recordInbound(src: String, data: ByteArray, tMs: Long) {
        append(tMs, "in", src, data)
    }

    /**
     * Append one `dir:"out"` line for a command written to a device. [label] is
     * a short characteristic label — e.g. `SCALE_COMMAND` for a Bookoo
     * tare/timer write.
     */
    fun recordOutbound(label: String, data: ByteArray, tMs: Long) {
        append(tMs, "out", label, data)
    }

    /** Flush and close the capture file. A no-op if not recording. */
    private fun stop() {
        synchronized(lock) {
            try {
                writer?.flush()
                writer?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing BLE session recording", e)
            } finally {
                writer = null
                filePath = null
            }
        }
    }

    /** Write one capture line; logs and swallows any IO failure. */
    private fun append(tMs: Long, dir: String, src: String, data: ByteArray) {
        synchronized(lock) {
            val w = writer ?: return
            try {
                w.write("""{"t":$tMs,"dir":"$dir","src":"$src","hex":"${data.toHex()}"}""")
                w.newLine()
                w.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Error writing BLE capture line", e)
            }
        }
    }

    /** Lowercase hex with no separators — the capture format's `hex` field. */
    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            sb.append(HEX[(b.toInt() ushr 4) and 0xF])
            sb.append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "BleSessionRecorder"
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
