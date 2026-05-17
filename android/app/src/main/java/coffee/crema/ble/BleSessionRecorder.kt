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
 * Records a live DE1 BLE session to a file so it can be replayed offline
 * through the Rust core (`cargo run -p de1-app --example replay`) — letting the
 * core's decode logic be validated with no DE1 and no Bluetooth.
 *
 * ## Capture format — JSON Lines, one BLE message per line, no header
 *
 * Each line is `{"t": <u64 ms>, "dir": "in"|"out", "src": "<string>", "hex":
 * "<lowercase hex payload>"}`:
 *  - `t` — millisecond timestamp; here `SystemClock.elapsedRealtime()`, the
 *    same monotonic value passed to [coffee.crema.core.CremaBridge.onNotification].
 *  - `dir` — `"in"` is a notification received from the DE1; `"out"` is a
 *    command written to it.
 *  - `src` — for `dir:"in"`, the [NotificationSource] enum name (`DE1_STATE`,
 *    `DE1_SHOT_SAMPLE`, `DE1_WATER_LEVELS`); for `dir:"out"`, a short label for
 *    the characteristic.
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

    /** Guards [writer] and all file IO; appends arrive from a binder thread. */
    private val lock = Any()

    /** The open capture file's writer, or `null` when not recording. */
    private var writer: BufferedWriter? = null

    /** Absolute path of the current capture file, or `null` when not recording. */
    var filePath: String? = null
        private set

    /**
     * Open a new capture file under `getExternalFilesDir(null)/captures/` and
     * begin recording. A no-op if a recording is already in progress. On any IO
     * failure the recorder simply stays inactive — it never throws.
     */
    fun start() {
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
     * Append one `dir:"in"` line for a notification received from the DE1.
     *
     * [tMs] must be the same timestamp handed to
     * [coffee.crema.core.CremaBridge.onNotification] for this notification, so
     * the capture and the live decode agree exactly.
     */
    fun recordInbound(source: NotificationSource, data: ByteArray, tMs: Long) {
        append(tMs, "in", source.name, data)
    }

    /**
     * Append one `dir:"out"` line for a command written to the DE1. [label] is
     * a short characteristic label. No caller wires this up yet; it is here for
     * future machine-control writes.
     */
    fun recordOutbound(label: String, data: ByteArray, tMs: Long) {
        append(tMs, "out", label, data)
    }

    /** Flush and close the capture file. A no-op if not recording. */
    fun stop() {
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
