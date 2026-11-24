package coffee.crema.ble.proxy

import android.util.Log
import coffee.crema.core.RelayDeviceInfo
import coffee.crema.core.RelayFrame
import coffee.crema.core.RelayFrameDecoded
import coffee.crema.core.relayDecodeFrame
import coffee.crema.core.relayEncodeFrame
import coffee.crema.core.relayFrameTypes
import coffee.crema.core.wellFormed
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

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

    // ---- Session start ----------------------------------------------------

    /**
     * The hub's first frame on an admitted session (relay v4: the core's
     * handshake already authenticated both ends, spec §4.5). Carries the hub's
     * name, the [authority] holder (always the primary) and the current
     * [roster] so the secondary can populate `scan()` immediately.
     */
    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val primaryName: String,
        val authority: String,
        val roster: List<DeviceInfo>,
        /**
         * The scope the hub granted this remote at pairing: `"control"` (may
         * drive the machine) or `"mirror"` (view-only). Required, with no
         * default, and anything but `"control"` reads as view-only
         * ([grantsControl]): a missing or unknown scope never fails open (R6).
         */
        val scope: String,
    ) : Frame() {
        /** Whether this Welcome grants control; any value but `"control"` is view-only. */
        val grantsControl: Boolean get() = scope == SCOPE_CONTROL
    }

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

    /**
     * Primary → secondary: the [Control] was dispatched on the real link.
     * [result] is what the command answers, an opaque app-level JSON like
     * [Config.json]: a bag pick's `BagSelection` (#488 review F6), so the
     * remote that picked shows its message and its own Undo. Off the wire
     * when null, so every other reply keeps its bytes.
     */
    @Serializable
    @SerialName("controlOk")
    data class ControlOk(val id: Long, val result: String? = null) : Frame()

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

    /**
     * Primary → secondary: the hub's **grinder block** on its own (a
     * `GrinderUpdate`: the block and a `rev` from the [Config] snapshots'
     * counter), pushed when only the grinder changed (up to 2 Hz while a
     * collar turns), so the active custom profile isn't re-sent each time
     * (grinder-ble §6.2). Every [Config] still carries the block too. A remote
     * that doesn't know this type skips it (spec §4.5) and keeps the grinder
     * of the last snapshot until the next one.
     */
    @Serializable
    @SerialName("grinder")
    data class Grinder(val json: String) : Frame()

    // ---- Multi-controller "who's driving" (issue 11) ----------------------

    /** Primary → secondary, **display-only**: a transient "<who> <did what>" notice
     *  fanned to the OTHER mirrors when a secondary drives the machine (shot
     *  start/stop, steam, …) — the "loose" multi-controller model. Surfaced as a
     *  snackbar; never touches state. */
    @Serializable
    @SerialName("event")
    data class Event(val text: String) : Frame()
}

/**
 * A device the primary holds, as advertised in the [Frame.Roster] /
 * [Frame.Welcome]. [name] is the BLE advertised name — the secondary's managers
 * re-apply their own `isDe1Name` rule (DE1) or `scaleScanUuids()` UUID match
 * (scales, post-AND6), re-deriving the scale codec/UUIDs locally via
 * `bridge.connectScale(name)`, so no scale
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

/** Current proxy wire-protocol version, carried in the relay ClientHello and
 *  NSD (spec §4.5). 2: + Frame.Event (issue 11 who's-driving); 3: pairing
 *  secret (removed); 4: the encrypted relay (Noise, the core's hellos). */
const val PROXY_PROTOCOL_VERSION: Int = 4

/** The WebSocket message cap on both ends (spec §4.3, Appendix A
 *  `WS_MAX_MESSAGE`): every relay message is at most one Noise record plus its
 *  kind byte. Larger application messages are fragmented by the core, which
 *  caps a reassembled message at 1 MiB (`MAX_MESSAGE_BYTES`). */
const val WS_MAX_MESSAGE: Long = 65_536L

/** The [Frame.Welcome] scope that grants control; anything else is view-only. */
const val SCOPE_CONTROL: String = "control"

/** The view-only scope. */
const val SCOPE_MIRROR: String = "mirror"

/**
 * Encodes/decodes [Frame]s to/from the JSON wire text: the core's codec
 * (`de1_relay_app::frame`), byte for byte what kotlinx wrote here before and
 * read as it did (the shared fixtures in
 * `core/de1-relay-app/tests/golden/v4/frames/` pin the wire). [Frame] keeps
 * its kotlinx annotations as the schema's description; nothing serializes
 * through them.
 *
 * - A field at its default (`args` empty, `result` or `src` null) stays off
 *   the wire.
 * - An unknown field is ignored, and an unknown `type` is skipped by
 *   [decodeTolerant] (spec §4.5).
 * - Strings cross to the core as UTF-8: a lone surrogate is encoded as `?`,
 *   the byte this shell's UTF-8 encoder wrote for one, and an escaped lone
 *   surrogate decodes as U+FFFD.
 */
object FrameCodec {
    fun encode(frame: Frame): String = relayEncodeFrame(frame.toCore())

    /** Decode a frame; an unknown `type` fails, as kotlinx's sealed decode did. */
    fun decode(text: String): Frame = when (val decoded = relayDecodeFrame(text.wellFormed())) {
        is RelayFrameDecoded.Frame -> decoded.frame.toFrame()
        is RelayFrameDecoded.Unknown ->
            throw SerializationException("Serializer for subclass '${decoded.typeName}' is not found in the polymorphic scope of 'Frame'")
        else -> throw decoded.failure()
    }

    /**
     * Decode a relay v4 `FRAME` payload, tolerating an unknown frame `type`
     * (spec §4.5): it is logged and skipped (null), so a later v4.x can add
     * frames without a version bump. Malformed JSON, a missing `type` or a
     * known type with bad fields still throws, which closes the session.
     */
    fun decodeTolerant(text: String): Frame? = when (val decoded = relayDecodeFrame(text.wellFormed())) {
        is RelayFrameDecoded.Frame -> decoded.frame.toFrame()
        is RelayFrameDecoded.Unknown -> {
            Log.i(TAG, "Skipping a frame of unknown type \"${decoded.typeName.take(32)}\"")
            null
        }
        else -> throw decoded.failure()
    }

    /** The `type` names the core's codec knows. */
    val TYPES: Set<String> by lazy { relayFrameTypes().toSet() }

    /** The exception kotlinx threw for the same input: not an object was an
     *  [IllegalArgumentException], the rest [SerializationException]s. */
    private fun RelayFrameDecoded.failure(): Exception = when (this) {
        RelayFrameDecoded.NotAnObject -> IllegalArgumentException("a frame must be a JSON object")
        RelayFrameDecoded.NoType -> SerializationException("frame without a type")
        is RelayFrameDecoded.Malformed -> SerializationException(detail)
        is RelayFrameDecoded.Frame, is RelayFrameDecoded.Unknown -> IllegalStateException("not a failure")
    }

    private fun String.out(): String = wellFormed('?')

    private fun List<DeviceInfo>.toCore(): List<RelayDeviceInfo> =
        map { RelayDeviceInfo(it.address.out(), it.name.out(), it.kind.out(), it.state.out()) }

    private fun List<RelayDeviceInfo>.toDevices(): List<DeviceInfo> = map { DeviceInfo(it.address, it.name, it.kind, it.state) }

    private fun Frame.toCore(): RelayFrame = when (this) {
        is Frame.Welcome -> RelayFrame.Welcome(primaryName.out(), authority.out(), roster.toCore(), scope.out())
        is Frame.Roster -> RelayFrame.Roster(devices.toCore())
        is Frame.Attach -> RelayFrame.Attach(id, address.out())
        is Frame.Attached -> RelayFrame.Attached(id, address.out(), state.out())
        is Frame.Detach -> RelayFrame.Detach(id, address.out())
        is Frame.Detached -> RelayFrame.Detached(id, address.out())
        is Frame.Read -> RelayFrame.Read(id, address.out(), service.out(), char.out())
        is Frame.ReadOk -> RelayFrame.ReadOk(id, hex.out())
        is Frame.ReadErr -> RelayFrame.ReadErr(id, reason.out())
        is Frame.Write -> RelayFrame.Write(id, address.out(), service.out(), char.out(), hex.out())
        is Frame.WriteOk -> RelayFrame.WriteOk(id)
        is Frame.WriteErr -> RelayFrame.WriteErr(id, reason.out())
        is Frame.Control -> RelayFrame.Control(id, method.out(), args.out())
        is Frame.ControlOk -> RelayFrame.ControlOk(id, result?.out())
        is Frame.ControlErr -> RelayFrame.ControlErr(id, reason.out())
        is Frame.Notify -> RelayFrame.Notify(address.out(), service.out(), char.out(), hex.out(), t, src?.out())
        is Frame.State -> RelayFrame.State(address.out(), state.out())
        is Frame.Config -> RelayFrame.Config(json.out())
        is Frame.Grinder -> RelayFrame.Grinder(json.out())
        is Frame.Event -> RelayFrame.Event(text.out())
    }

    private fun RelayFrame.toFrame(): Frame = when (this) {
        is RelayFrame.Welcome -> Frame.Welcome(primaryName, authority, roster.toDevices(), scope)
        is RelayFrame.Roster -> Frame.Roster(devices.toDevices())
        is RelayFrame.Attach -> Frame.Attach(id, address)
        is RelayFrame.Attached -> Frame.Attached(id, address, state)
        is RelayFrame.Detach -> Frame.Detach(id, address)
        is RelayFrame.Detached -> Frame.Detached(id, address)
        is RelayFrame.Read -> Frame.Read(id, address, service, char)
        is RelayFrame.ReadOk -> Frame.ReadOk(id, hex)
        is RelayFrame.ReadErr -> Frame.ReadErr(id, reason)
        is RelayFrame.Write -> Frame.Write(id, address, service, char, hex)
        is RelayFrame.WriteOk -> Frame.WriteOk(id)
        is RelayFrame.WriteErr -> Frame.WriteErr(id, reason)
        is RelayFrame.Control -> Frame.Control(id, method, args)
        is RelayFrame.ControlOk -> Frame.ControlOk(id, result)
        is RelayFrame.ControlErr -> Frame.ControlErr(id, reason)
        is RelayFrame.Notify -> Frame.Notify(address, service, char, hex, t, src)
        is RelayFrame.State -> Frame.State(address, state)
        is RelayFrame.Config -> Frame.Config(json)
        is RelayFrame.Grinder -> Frame.Grinder(json)
        is RelayFrame.Event -> Frame.Event(text)
    }

    private const val TAG = "FrameCodec"
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
