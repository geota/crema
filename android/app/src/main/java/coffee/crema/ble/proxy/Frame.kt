package coffee.crema.ble.proxy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Crema multi-device **proxy wire protocol** (M1 — LAN proxy transport).
 *
 * A secondary device with no BLE link of its own drives the DE1/scale through a
 * primary that owns the one real radio. The primary runs a relay; the secondary
 * implements [coffee.crema.ble.BleTransport] as a `ProxyTransport` whose
 * `connect/observe/read/write/connectionState` are network round-trips. These
 * [Frame]s are what crosses that network seam.
 *
 * ## Shape — flat, internally-tagged JSON, one frame per WebSocket message
 *
 * Each frame serialises to a single JSON object discriminated by a `"type"`
 * field (kotlinx's default `classDiscriminator`), with its payload fields
 * **inline** — e.g. `{"type":"notify","address":"…","char":"a00d","hex":"…",
 * "t":123}`. This is deliberately a superset of the [coffee.crema.ble.BleSessionRecorder]
 * capture line (`{"t","dir","src","hex"}`): a tee of the wire's `notify`/`write`
 * frames is a `session-*.jsonl` that replays through the existing `replay.rs`
 * and the same Rust core. GATT payloads ride as lowercase hex (see [Hex]) so the
 * format stays human-debuggable and capture-compatible; binary/short-UUID
 * compaction is a later optimisation.
 *
 * WebSocket already frames + orders + guarantees delivery within a connection,
 * so there is no manual length-prefixing. One multiplexed connection carries
 * **both** devices (DE1 + scale), addressed by [Frame] `address` — mirroring how
 * the real transport multiplexes both over one Nordic central.
 *
 * ## Addressing
 *
 * - `address` — the primary's Bluetooth address for a device; the stable
 *   identity the secondary's `ProxyTransport.DeviceHandle` carries.
 * - `service` / `char` — full lowercase 128-bit GATT UUIDs, the same the
 *   managers pass to `observe/read/write`. The routing key for a notification
 *   is (`address`, `char`); `service` rides along for symmetry with read/write.
 * - `id` — a requester-monotonic id correlating a request
 *   (`attach`/`read`/`write`) with its reply; push frames (`notify`/`state`/
 *   `roster`) are unsolicited and carry none.
 *
 * ## M1 scope
 *
 * M1 proves the **read/observe** half end-to-end (handshake, roster→scan,
 * attach+snapshot, lossless `notify` fan-out, `state`, `read`). The `write`
 * frames exist but the relay rejects them (`writeErr` — authority stays with the
 * primary); the high-level relayed-control path and the config-snapshot frame
 * are reserved for M2 (see the M1-PROTOCOL design doc).
 */
@Serializable
sealed class Frame {

    // ---- Session handshake ------------------------------------------------

    /**
     * First frame a secondary sends on connect. [v] is [PROXY_PROTOCOL_VERSION];
     * [clientId] is a stable per-device id (for TOFU remembering), [clientName]
     * a display name ("Adrian's phone"). [token] is reserved for the later
     * cloud-relay path; null on the LAN.
     */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val v: Int,
        val role: String,
        val clientId: String,
        val clientName: String,
        val token: String? = null,
    ) : Frame()

    /**
     * The primary's acceptance reply to [Hello]. Carries the [authority] holder
     * (always the primary in M1) and the current [roster] so the secondary can
     * populate `scan()` immediately without waiting for a change push.
     */
    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val v: Int,
        val primaryId: String,
        val primaryName: String,
        val authority: String,
        val roster: List<DeviceInfo>,
        /** The scope this peer was granted (issue 02 TOFU): `"control"` (may drive
         *  the machine) or `"mirror"` (view-only — its Control/Handoff are refused).
         *  Defaults to `"control"` so an older relay (pre-pairing) reads as full. */
        val scope: String = "control",
    ) : Frame()

    /** The primary's rejection reply to [Hello] (TOFU declined / version
     *  mismatch). The connection closes after this. */
    @Serializable
    @SerialName("denied")
    data class Denied(val reason: String) : Frame()

    // ---- Roster (drives scan + connectionState seed) ----------------------

    /**
     * The set of devices the primary currently holds, pushed whenever it
     * changes. Drives the secondary's `BleTransport.scan` (each [DeviceInfo]
     * whose [DeviceInfo.name] matches becomes a `ScanMatch`) and seeds each
     * device's `connectionState`.
     */
    @Serializable
    @SerialName("roster")
    data class Roster(val devices: List<DeviceInfo>) : Frame()

    // ---- Per-device attach / detach (connect / disconnect) ----------------

    /** Secondary → primary: attach to the primary's existing link for [address]
     *  and start streaming its notifications. Maps to `ProxyTransport.connect`. */
    @Serializable
    @SerialName("attach")
    data class Attach(val id: Long, val address: String) : Frame()

    /**
     * Primary → secondary: [Attach] succeeded; [state] is the device's current
     * connection state. Immediately followed by a **snapshot burst** of cached
     * latest-value `notify` frames (state/identity characteristics only — never
     * the counted sample streams) so the secondary's fresh core converges to the
     * machine's current state.
     */
    @Serializable
    @SerialName("attached")
    data class Attached(val id: Long, val address: String, val state: String) : Frame()

    /** Secondary → primary: stop streaming [address]. Maps to
     *  `ProxyTransport.disconnect`. */
    @Serializable
    @SerialName("detach")
    data class Detach(val id: Long, val address: String) : Frame()

    /** Primary → secondary: [Detach] acknowledged. */
    @Serializable
    @SerialName("detached")
    data class Detached(val id: Long, val address: String) : Frame()

    // ---- Read (allowed in M1) ---------------------------------------------

    /** Secondary → primary: GATT read of [char]. Maps to `ProxyTransport.read`;
     *  the primary may serve it from its last-value cache. */
    @Serializable
    @SerialName("read")
    data class Read(
        val id: Long,
        val address: String,
        val service: String,
        val char: String,
    ) : Frame()

    /** Primary → secondary: the [Read]'s result, [hex]-encoded. */
    @Serializable
    @SerialName("readOk")
    data class ReadOk(val id: Long, val hex: String) : Frame()

    /** Primary → secondary: the [Read] failed. */
    @Serializable
    @SerialName("readErr")
    data class ReadErr(val id: Long, val reason: String) : Frame()

    // ---- Write (rejected in M1; reserved for the M2 control path) ----------

    /**
     * Secondary → primary: GATT write of [hex] to [char]. Maps to
     * `ProxyTransport.write`. **In M1 the relay rejects this with [WriteErr]
     * (`not-authoritative`)** — the primary is the sole controller, so a
     * secondary's autonomous core writes (stop-on-weight, frame-skip) never
     * reach the machine. User-intent control becomes a relayed request in M2.
     */
    @Serializable
    @SerialName("write")
    data class Write(
        val id: Long,
        val address: String,
        val service: String,
        val char: String,
        val hex: String,
    ) : Frame()

    /** Primary → secondary: the [Write] completed on the real link. */
    @Serializable
    @SerialName("writeOk")
    data class WriteOk(val id: Long) : Frame()

    /** Primary → secondary: the [Write] was rejected or failed. */
    @Serializable
    @SerialName("writeErr")
    data class WriteErr(val id: Long, val reason: String) : Frame()

    // ---- Control (M2: relayed user intent → primary's command router) ------

    /**
     * Secondary → primary: relay a **user-intent** control action to the
     * primary's VM command router. A secondary's own core is a read-only mirror
     * (it cannot drive the machine), so a tap on its Brew controls crosses as
     * this frame; the primary — the sole authority — executes it on the real
     * link. [method] names the action (`"machineState"`, `"startShot"`,
     * `"tareScale"`, …) and [args] is its payload (e.g. a `MachineRequest` name,
     * or empty for a no-arg action). The action's *effect* returns asynchronously
     * as machine state over the mirrored [Notify] stream; this frame's reply only
     * confirms the primary **dispatched** it. `startShot` deliberately relays as
     * bare intent — the primary runs its own shot orchestration (profile upload
     * guard, QC bake), so that complexity never crosses the wire.
     */
    @Serializable
    @SerialName("control")
    data class Control(val id: Long, val method: String, val args: String = "") : Frame()

    /** Primary → secondary: the [Control] was dispatched on the real link. */
    @Serializable
    @SerialName("controlOk")
    data class ControlOk(val id: Long) : Frame()

    /** Primary → secondary: the [Control] was refused or failed to dispatch. */
    @Serializable
    @SerialName("controlErr")
    data class ControlErr(val id: Long, val reason: String) : Frame()

    // ---- Push streams (drive observe / connectionState) --------------------

    /**
     * Primary → secondary: one device notification, fanned out to every attached
     * secondary. Drives `ProxyTransport.observe`. [t] is the primary's
     * `elapsedRealtime()` stamped at delivery — **forwarded verbatim**, never
     * re-stamped, so the secondary's core decodes identically to a replayed
     * capture. [src] is an optional best-effort `NotificationSource` label for
     * capture symmetry; the routing key is [char].
     *
     * Losslessness is per-consumer: each secondary's inbound stream is lossless
     * for its core, but a secondary that falls behind is dropped and resyncs via
     * a fresh snapshot — the primary's own core is never back-pressured by a
     * slow remote.
     */
    @Serializable
    @SerialName("notify")
    data class Notify(
        val address: String,
        val service: String,
        val char: String,
        val hex: String,
        val t: Long,
        val src: String? = null,
    ) : Frame()

    /** Primary → secondary: a device's connection state changed. Drives
     *  `ProxyTransport.connectionState`. [state] is a
     *  `BleTransport.ConnState` name. */
    @Serializable
    @SerialName("state")
    data class State(val address: String, val state: String) : Frame()

    // ---- Config (M2 T2: single-owner session config) ----------------------

    /**
     * Primary → secondary: the primary's **session config** snapshot — pushed
     * once on attach (right after the BLE-char snapshot burst) and again whenever
     * it changes. Config has a single owner, the primary; a secondary mirrors it
     * and never holds independent config, so this is the settings-drift fix: on
     * every attach the secondary snaps to the primary's active profile / bean /
     * SAW / QC / units. [json] is an opaque app-level blob (a `ConfigSnapshot`),
     * ferried as a string so this wire layer stays decoupled from the shell's
     * config shape.
     */
    @Serializable
    @SerialName("config")
    data class Config(val json: String) : Frame()
}

/**
 * A device the primary holds, as advertised in the [Frame.Roster] /
 * [Frame.Welcome]. [name] is the BLE advertised name — the secondary's managers
 * re-apply their own `isDe1Name` / `isBookooName` rule to it and, for a scale,
 * re-derive the codec/UUIDs locally via `bridge.connectScale(name)`, so no scale
 * UUIDs need cross the wire. [kind] (`"de1"` / `"scale"`) is an informational
 * hint; [state] is a `BleTransport.ConnState` name.
 */
@Serializable
data class DeviceInfo(
    val address: String,
    val name: String,
    val kind: String,
    val state: String,
)

/** Current proxy wire-protocol version. The handshake ([Frame.Hello] /
 *  [Frame.Welcome]) carries it so peers can refuse a mismatch; bump on any
 *  breaking frame change. */
const val PROXY_PROTOCOL_VERSION: Int = 1

/**
 * Encodes/decodes [Frame]s to/from the JSON wire text. Always (de)serialises via
 * the sealed [Frame] serializer so the `"type"` discriminator is written and
 * read; encoding a concrete subtype directly would omit it.
 *
 * - `encodeDefaults = false` — optional nulls (`token`, `src`) and other
 *   defaults stay off the wire, keeping frames lean and capture-line-like.
 * - `ignoreUnknownKeys = true` — forward compatibility: a newer peer adding a
 *   field does not break an older decoder.
 */
object FrameCodec {
    val json: Json = Json {
        classDiscriminator = "type"
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    fun encode(frame: Frame): String = json.encodeToString(Frame.serializer(), frame)

    fun decode(text: String): Frame = json.decodeFromString(Frame.serializer(), text)
}

/**
 * Lowercase, separator-free hex — the encoding GATT payloads use on the wire,
 * identical to [coffee.crema.ble.BleSessionRecorder]'s `hex` field so a wire log
 * and a capture file are byte-for-byte interchangeable.
 */
object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(DIGITS[(b.toInt() ushr 4) and 0xF])
            sb.append(DIGITS[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have an even length: '${hex.length}'" }
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val hi = Character.digit(hex[i], 16)
            val lo = Character.digit(hex[i + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex digit near index $i" }
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }
}
