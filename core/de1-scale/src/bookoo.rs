//! Bookoo scale BLE codec (`scale_type` `bookoo`).
//!
//! Weight notifications arrive on [`NOTIFY_UUID`]; commands are written to
//! [`COMMAND_UUID`]. The commands are fixed byte sequences (the legacy app
//! hard-codes them, checksum included).
//!
//! # Weight notification packet
//!
//! The scale sends fixed 20-byte notifications on the weight characteristic.
//! The layout below was reverse-engineered from a real capture
//! (`session-20260517-151504.jsonl`, 606 packets) and cross-checked against the
//! legacy app's `bookoo_parse_response` in `de1plus/bluetooth.tcl` — which
//! decodes *only* the weight (bytes 6–9) and ignores everything else.
//!
//! ```text
//! [0-1]   03 0b      header (constant in the whole capture)
//! [2-4]   timer, 24-bit big-endian milliseconds (see TIMER below)
//! [5]     constant 0x01
//! [6]     "weight indicator" byte — 0x2b ('+') or 0x2d ('-'); see INDICATOR
//! [7-9]   weight, 24-bit big-endian, in units of 0.01 g
//! [10]    "flow indicator" byte — 0x2b ('+') or 0x2d ('-'); see INDICATOR
//! [11-12] flow rate, 16-bit big-endian; see FLOW
//! [13-18] live scale settings; see SETTINGS below
//! [19]    checksum: XOR of bytes [0..=18] (verified: 0 mismatches / 606)
//! ```
//!
//! ## SETTINGS bytes `[13]`–`[18]`
//!
//! Bytes `[13]`–`[18]` are **not** constant — they carry the scale's *live
//! settings*, echoed in every weight notification. An earlier revision of this
//! doc called them constant, because the capture it was decoded from never
//! changed a setting. The corrected map was verified by diffing a capture
//! (`session-20260517-215049.jsonl`) against a known action order — the
//! official BOOKOO app stepping volume, then standby, then two on/off toggles:
//!
//! ```text
//! [13]    battery %        — assumed; constant 0x64 (100) in every capture
//! [14-15] standby timeout  — u16 big-endian, in units of 0.1 min
//!                            (standby_minutes = value / 10): e.g. 0x0096 = 150
//!                            -> 15 min, 0x012C = 300 -> 30 min
//! [16]    beeper volume    — 0..=5, tracks the set_volume (0x02) command
//! [17]    flow smoothing   — boolean (0/1); CONFIRMED. A capture with a known
//!                            action order showed this byte flipping 0->1 when
//!                            flow smoothing was turned on and 1->0 when it was
//!                            turned off, in lockstep with set_flow_smoothing
//!                            (0x08). Exposed as `flow_smoothing: bool`.
//! [18]    auto-stop mode (0/1) — CONFIRMED. 0 = Flow-Stop, 1 = Cup-Removal.
//!                            This byte matches the `p1` of every set_auto_stop
//!                            (0x0B) write in the official-app HCI snoop (5/5,
//!                            both directions), so the scale echoes its live
//!                            auto-stop mode here. Exposed raw as `auto_stop`.
//! ```
//!
//! [`BookooPacket`] surfaces `[13]` as `battery_percent`, `[14-15]` as
//! `standby_minutes`, `[16]` as `volume`, `[17]` as the confirmed
//! `flow_smoothing` bool, and `[18]` as the confirmed `auto_stop` mode byte.
//! Byte `[5]` (constant `0x01` in every capture) is now the only unidentified
//! weight byte.
//!
//! ## INDICATOR bytes `[6]` and `[10]`
//!
//! The legacy app treats `[6]` as an ASCII sign for the weight (`'-'` →
//! negative). The capture contradicts a pure sign reading: a steady 608.70 g
//! object produced one run of 42 packets all carrying `[6] = '-'` and a
//! separate run of 34 packets all carrying `[6] = '+'`, with byte-identical
//! weight bytes — a static positive weight cannot be negative. `[6]` is
//! *constant within a single placement* and only flips across a
//! remove-and-replace cycle, so it is not a stability flag either (it does not
//! change when motion stops). Its exact meaning could not be pinned down from
//! this capture.
//!
//! `[10]` changes far more often (26 transitions vs 6 for `[6]`) and `'-'`
//! appears *only* on packets whose flow rate is non-zero (156/156), never on a
//! zero-flow packet — so `[10]` is plausibly the sign of the flow rate, but the
//! capture (a hand jiggling an object, not a real pour) is too noisy to confirm
//! the polarity.
//!
//! To stay faithful to the legacy app for the one field it decodes, the weight
//! still honours `[6]` as a sign. The raw indicator bytes are also exposed on
//! [`BookooPacket`] so callers (and the replay tool) can see them unmassaged.
//!
//! ## FLOW
//!
//! The flow rate is a 16-bit big-endian value at bytes `[11-12]`; its unit is
//! **0.01 g/s** — confirmed against a real pour capture, where a ~590 g pour
//! read a median ~4.2 g/s, matching the scale's own display. [`BookooPacket`]
//! exposes both the raw `flow_raw` and a `flow_g_per_s` computed as
//! `flow_raw / 100.0`.
//!
//! ## TIMER
//!
//! Bytes `[2-4]` are a 24-bit big-endian counter. Its unit is
//! **milliseconds** — confirmed against a real capture, where a 10 s scale
//! timer read 0 → 10000. [`BookooPacket`] reports `timer_ms` directly as that
//! 24-bit value; a 24-bit ms counter wraps at ~4.66 h, comfortably longer than
//! any espresso shot.
//!
//! # COMMANDS
//!
//! Every command written to [`COMMAND_UUID`] (`ff12`) is exactly **6 bytes**:
//!
//! ```text
//! [0]  0x03   constant header
//! [1]  0x0A   constant header
//! [2]  cmd    command id (see catalog below)
//! [3]  p1     parameter 1
//! [4]  p2     parameter 2
//! [5]  xor    checksum: XOR of bytes [0..=4]
//! ```
//!
//! The checksum is the XOR of the five preceding bytes. [`command`] computes it,
//! so every constant and builder below is checksum-correct by construction —
//! the hand-typed-checksum bug class (which had previously corrupted the three
//! timer commands) is structurally impossible.
//!
//! The whole catalog was reverse-engineered from a Bluetooth-HCI capture of the
//! official BOOKOO app.
//!
//! ## Command catalog
//!
//! ```text
//! cmd  builder                meaning
//! ---- ---------------------- --------------------------------------------
//! 0x01 TARE                   tare the scale
//! 0x02 set_volume             beeper volume, p2 = level 0..=5
//! 0x03 set_standby_minutes    auto-standby timeout, p2 = minutes 5..=30
//! 0x04 TIMER_START            start the timer
//! 0x05 TIMER_STOP             stop the timer
//! 0x06 TIMER_RESET            reset the timer
//! 0x08 set_flow_smoothing    flow smoothing on/off, p1 = 0|1
//! 0x0A QUERY_SERIAL           query serial number (scale replies 03 0c …)
//! 0x0B set_auto_stop_mode     auto-stop mode, p1 = 0|1 (see AutoStopMode)
//! 0x0E set_mode_enabled       enable/disable a display mode, p1 = mode index
//! 0x0F QUERY_SETTINGS         query settings (scale replies 03 0e …)
//! 0x10 set_anti_mistouch      anti-mistouch on/off, p1 = 0|1
//! ```
//!
//! Commands `0x08`, `0x0B` and `0x10` are single-byte settings whose feature
//! mapping was confirmed against a Bluetooth-HCI capture of the official BOOKOO
//! app with a known action order — see each builder's doc comment.

// The 24-bit raw weight fields are decoded into `f32` grams; representing a
// wire reading as the codec's `f32` weight is inherent to the format, not a
// defect, so the precision-loss lint is allowed module-wide here.
#![allow(clippy::cast_precision_loss)]

/// GATT service UUID.
pub const SERVICE_UUID: &str = "00000ffe-0000-1000-8000-00805f9b34fb";
/// Characteristic the scale notifies weight on.
pub const NOTIFY_UUID: &str = "0000ff11-0000-1000-8000-00805f9b34fb";
/// Characteristic commands are written to.
pub const COMMAND_UUID: &str = "0000ff12-0000-1000-8000-00805f9b34fb";

/// Build a 6-byte Bookoo `ff12` command `[0x03, 0x0A, cmd, p1, p2, xor]`.
///
/// `xor` is computed as the XOR of the five preceding bytes, so the checksum is
/// always correct by construction — see the [`COMMANDS`](self#commands) section
/// of the module docs. Every constant and builder in this module is defined in
/// terms of this function; checksums are never hand-typed.
///
/// # Why Crema's XOR is the source of truth — not legacy de1app or reaprime
///
/// **Both upstream apps ship the wrong XOR for the three timer commands.**
/// Legacy de1app hand-types `0A`/`0D`/`0C` for `TIMER_START`/`TIMER_STOP`/
/// `TIMER_RESET` (`de1plus/bluetooth.tcl:471-503`), and reaprime mirrors the
/// same wrong bytes (`reaprime/lib/src/models/device/impl/bookoo/miniscale.dart:131-149`).
/// The structurally-correct XOR per this builder is `0D`/`0C`/`0F`. The
/// Bookoo's firmware appears to either ignore the XOR or has been silently
/// dropping the timer commands in both apps for years (the documented
/// open question for years).
///
/// Crema's `const fn command()` derives the XOR structurally — see the test
/// `timer_constants_match_the_captured_bytes`. Crema is the source of truth
/// here; do NOT "fix" Crema's bytes down to match either upstream app.
#[must_use]
pub const fn command(cmd: u8, p1: u8, p2: u8) -> [u8; 6] {
    let xor = 0x03 ^ 0x0A ^ cmd ^ p1 ^ p2;
    [0x03, 0x0A, cmd, p1, p2, xor]
}

/// Command: tare the scale.
pub const TARE: [u8; 6] = command(0x01, 0x00, 0x00);
/// Command: start the timer.
pub const TIMER_START: [u8; 6] = command(0x04, 0x00, 0x00);
/// Command: stop the timer.
pub const TIMER_STOP: [u8; 6] = command(0x05, 0x00, 0x00);
/// Command: reset the timer.
pub const TIMER_RESET: [u8; 6] = command(0x06, 0x00, 0x00);

/// Command: query the scale's serial number.
///
/// This is a query: the scale answers with a `03 0c …` notification rather than
/// changing any setting.
pub const QUERY_SERIAL: [u8; 6] = command(0x0A, 0x00, 0x00);
/// Command: query the scale's settings.
///
/// This is a query: the scale answers with a `03 0e …` notification rather than
/// changing any setting.
pub const QUERY_SETTINGS: [u8; 6] = command(0x0F, 0x00, 0x00);

/// A display/behaviour mode that can be toggled with [`set_mode_enabled`].
///
/// The discriminant is the `p1` index the scale expects for command `0x0E`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BookooMode {
    /// Flow-rate display (mode index 0).
    FlowRate = 0,
    /// Timer display (mode index 1).
    Timer = 1,
    /// Auto mode (mode index 2).
    Auto = 2,
}

impl BookooMode {
    /// The `p1` index byte the scale uses for this mode in command `0x0E`.
    #[must_use]
    pub const fn index(self) -> u8 {
        self as u8
    }
}

/// Command: set the beeper volume.
///
/// `level` is clamped to the valid range `0..=5`.
#[must_use]
pub const fn set_volume(level: u8) -> [u8; 6] {
    let level = if level > 5 { 5 } else { level };
    command(0x02, 0x00, level)
}

/// Command: set the auto-standby timeout, in minutes.
///
/// `minutes` is clamped to the valid range `5..=30`.
#[must_use]
pub const fn set_standby_minutes(minutes: u8) -> [u8; 6] {
    let minutes = if minutes < 5 {
        5
    } else if minutes > 30 {
        30
    } else {
        minutes
    };
    command(0x03, 0x00, minutes)
}

/// Command: enable or disable one of the scale's display modes.
///
/// `0x0E` toggles modes *individually*; the scale keeps at least one mode
/// enabled at all times. To switch the *active* mode, use [`select_mode`].
#[must_use]
pub const fn set_mode_enabled(mode: BookooMode, enabled: bool) -> [u8; 6] {
    command(0x0E, mode.index(), enabled as u8)
}

/// The ordered command sequence that switches the scale to a single active
/// display mode.
///
/// Command `0x0E` only enables/disables modes *individually*, and the scale
/// requires **at least one mode enabled at all times**. Selecting a mode is
/// therefore three writes, and the order matters: the target is enabled
/// *first*, then the other two are disabled — so an enabled mode always exists,
/// even mid-sequence. Send the three commands in the returned order.
#[must_use]
pub const fn select_mode(target: BookooMode) -> [[u8; 6]; 3] {
    let (a, b) = match target {
        BookooMode::FlowRate => (BookooMode::Timer, BookooMode::Auto),
        BookooMode::Timer => (BookooMode::FlowRate, BookooMode::Auto),
        BookooMode::Auto => (BookooMode::FlowRate, BookooMode::Timer),
    };
    [
        set_mode_enabled(target, true),
        set_mode_enabled(a, false),
        set_mode_enabled(b, false),
    ]
}

/// Command `0x08` — enable or disable *flow smoothing*.
///
/// The feature mapping (`0x08` = flow smoothing) was confirmed against a
/// Bluetooth-HCI capture of the official BOOKOO app with a known action order.
#[must_use]
pub const fn set_flow_smoothing(enabled: bool) -> [u8; 6] {
    command(0x08, enabled as u8, 0x00)
}

/// The scale's auto-stop mode, set with [`set_auto_stop_mode`] (command `0x0B`).
///
/// A two-state setting; the wire form is `0x0B <p1>` with `p1` from the
/// discriminant below. Both values are confirmed against a capture of the
/// official BOOKOO app, cross-checked with the scale's reported current state:
/// `0x0B 00` = Flow-Stop, `0x0B 01` = Cup-Removal.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AutoStopMode {
    /// Flow-Stop auto-stop (`p1 = 0`): stop timing when the flow rate hits 0.
    FlowStop = 0,
    /// Cup-removal auto-stop (`p1 = 1`): stop timing when the cup is removed.
    CupRemoval = 1,
}

impl AutoStopMode {
    /// The `p1` byte the scale uses for this mode in command `0x0B`.
    #[must_use]
    pub const fn p1(self) -> u8 {
        self as u8
    }
}

/// Command `0x0B` — select the scale's auto-stop mode.
///
/// Confirmed against a Bluetooth-HCI capture of the official BOOKOO app with a
/// known action order. See [`AutoStopMode`] for the two states.
#[must_use]
pub const fn set_auto_stop_mode(mode: AutoStopMode) -> [u8; 6] {
    command(0x0B, mode.p1(), 0x00)
}

/// Command `0x10` — enable or disable *anti-mistouch*.
///
/// The feature mapping (`0x10` = anti-mistouch) was confirmed against a
/// Bluetooth-HCI capture of the official BOOKOO app with a known action order.
#[must_use]
pub const fn set_anti_mistouch(enabled: bool) -> [u8; 6] {
    command(0x10, enabled as u8, 0x00)
}

/// Minimum length of a weight notification needed to read the weight.
const WEIGHT_PACKET_LEN: usize = 10;

/// Full length of a Bookoo weight notification.
const FULL_PACKET_LEN: usize = 20;

/// A fully decoded Bookoo weight notification.
///
/// See the [module documentation](self) for the packet layout and for the
/// fields whose meaning the capture could not pin down.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BookooPacket {
    /// Weight in grams. Negative when the `[6]` indicator byte is `'-'`, to
    /// match the legacy app — but see the module docs: `[6]` is not a reliable
    /// sign. Magnitude is bytes `[7-9]` (24-bit big-endian) ÷ 100.
    pub weight_g: f32,
    /// Raw `[6]` "weight indicator" byte — `0x2b` (`'+'`) or `0x2d` (`'-'`).
    /// Exposed unmassaged because its meaning is unconfirmed.
    pub weight_indicator: u8,
    /// Raw 16-bit big-endian flow value, bytes `[11-12]`.
    pub flow_raw: u16,
    /// Flow rate in g/s: `flow_raw / 100.0`. The 0.01 g/s unit is confirmed
    /// against a real pour capture — see the module docs.
    pub flow_g_per_s: f32,
    /// Raw `[10]` "flow indicator" byte — `0x2b` (`'+'`) or `0x2d` (`'-'`),
    /// plausibly the flow sign. Exposed unmassaged.
    pub flow_indicator: u8,
    /// Scale-timer reading in milliseconds, bytes `[2-4]` (24-bit big-endian).
    pub timer_ms: u32,
    /// Battery charge percentage, byte `[13]`.
    ///
    /// **Assumed mapping.** Byte `[13]` is `0x64` (100) in every capture
    /// available, so the battery interpretation has never been observed
    /// changing — see the module docs.
    pub battery_percent: u8,
    /// Auto-standby timeout in minutes, decoded from bytes `[14-15]`.
    ///
    /// Bytes `[14-15]` are a 16-bit big-endian value in units of 0.1 min;
    /// this field is that value divided by 10 (e.g. `0x012C` = 300 -> 30 min).
    pub standby_minutes: u8,
    /// Beeper volume `0..=5`, byte `[16]` — the scale's live volume setting.
    pub volume: u8,
    /// Whether flow smoothing is on, byte `[17]`.
    ///
    /// **Confirmed.** A capture with a known action order showed this byte
    /// flipping `0`→`1` when flow smoothing was turned on and `1`→`0` when it
    /// was turned off, in lockstep with [`set_flow_smoothing`]. See the module
    /// docs.
    pub flow_smoothing: bool,
    /// The scale's live auto-stop mode, raw byte `[18]` (`0` / `1`).
    ///
    /// **Confirmed.** `0` = Flow-Stop, `1` = Cup-Removal. This byte matches the
    /// `p1` of every [`set_auto_stop_mode`] (`0x0B`) write in the official-app
    /// HCI snoop (5/5, both directions), so the scale echoes its live auto-stop
    /// mode here. Exposed as the raw mode id to match [`AutoStopMode`]. See the
    /// module docs.
    pub auto_stop: u8,
    /// Whether the trailing checksum byte `[19]` matched the XOR of `[0..=18]`.
    pub checksum_ok: bool,
}

/// Decode a weight notification into grams.
///
/// Bytes 7–9 are a big-endian 24-bit magnitude in units of 0.01 g; byte 6 is
/// an ASCII sign byte (`'-'` marks a negative weight).
///
/// Kept for callers that only need the weight; [`parse_packet`] decodes the
/// whole notification.
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    if data.len() < WEIGHT_PACKET_LEN {
        return None;
    }
    let raw = (u32::from(data[7]) << 16) | (u32::from(data[8]) << 8) | u32::from(data[9]);
    let magnitude = raw as f32 / 100.0;
    Some(if data[6] == b'-' {
        -magnitude
    } else {
        magnitude
    })
}

/// Decode a full 20-byte Bookoo weight notification.
///
/// Returns `None` for anything that is not exactly [`FULL_PACKET_LEN`] bytes.
/// The decode never fails on field content: a bad checksum is reported via
/// [`BookooPacket::checksum_ok`] rather than rejecting the packet, so a caller
/// can still surface the (possibly corrupt) reading and decide what to do.
pub fn parse_packet(data: &[u8]) -> Option<BookooPacket> {
    if data.len() != FULL_PACKET_LEN {
        return None;
    }

    let timer_ms = (u32::from(data[2]) << 16) | (u32::from(data[3]) << 8) | u32::from(data[4]);

    let weight_raw = (u32::from(data[7]) << 16) | (u32::from(data[8]) << 8) | u32::from(data[9]);
    let weight_magnitude = weight_raw as f32 / 100.0;
    let weight_g = if data[6] == b'-' {
        -weight_magnitude
    } else {
        weight_magnitude
    };

    let flow_raw = (u16::from(data[11]) << 8) | u16::from(data[12]);

    // Bytes [14-15] are a 16-bit big-endian standby timeout in units of
    // 0.1 min; the scale only ever sets whole minutes in the documented
    // 5..=30 range, so dividing by 10 yields a small u8. A malformed packet
    // outside that range saturates rather than wrapping.
    let standby_raw = (u16::from(data[14]) << 8) | u16::from(data[15]);
    let standby_minutes = u8::try_from(standby_raw / 10).unwrap_or(u8::MAX);

    // The checksum byte [19] is an XOR of every preceding byte.
    let checksum = data[..FULL_PACKET_LEN - 1].iter().fold(0_u8, |a, &b| a ^ b);

    Some(BookooPacket {
        weight_g,
        weight_indicator: data[6],
        flow_raw,
        flow_g_per_s: f32::from(flow_raw) / 100.0,
        flow_indicator: data[10],
        timer_ms,
        battery_percent: data[13],
        standby_minutes,
        volume: data[16],
        flow_smoothing: data[17] != 0,
        auto_stop: data[18],
        checksum_ok: checksum == data[FULL_PACKET_LEN - 1],
    })
}

/// A decoded response from the Bookoo `ff12` command characteristic.
///
/// The `ff12` channel — which the app otherwise only *writes* commands to —
/// also has the NOTIFY property, and the scale pushes two 20-byte response
/// frames back on it, distinguished by their `[0-1]` header:
///
/// - **`03 0c`** ([`CommandResponse::Serial`]) — the reply to a `0x0a`
///   ([`QUERY_SERIAL`]) query, and to every `0x10` ([`set_anti_mistouch`])
///   write. Carries the firmware version, the 14-byte ASCII serial number, and
///   the live anti-mistouch state.
/// - **`03 0e`** ([`CommandResponse::Settings`]) — the reply to a `0x0f`
///   ([`QUERY_SETTINGS`]) query. Carries the active display-mode index and the
///   enabled-modes bitmask.
///
/// Both frames are 20 bytes with `[19]` an XOR checksum of `[0..=18]`, the same
/// scheme as the weight notification — see [`parse_command_response`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CommandResponse {
    /// A `03 0c` serial-number response, returned by a [`QUERY_SERIAL`] query
    /// and echoed after every [`set_anti_mistouch`] write.
    Serial {
        /// Firmware version, byte `[2-3]` (u16 big-endian). The encoding is
        /// `major × 100 + minor × 10 + patch` — e.g. firmware 1.4.1 is
        /// `0x008d` = `141`.
        firmware_version: u16,
        /// The scale's serial number, decoded from the 14 ASCII bytes
        /// `[4-17]`. Trailing NUL / whitespace padding is trimmed.
        serial: String,
        /// The scale's live anti-mistouch state, byte `[18]` (`0` / `1`).
        anti_mistouch: bool,
    },
    /// A `03 0e` settings response, returned by a [`QUERY_SETTINGS`] query.
    Settings {
        /// The active display-mode index, byte `[2]` — `0` = Flow-Rate,
        /// `1` = Timer, `2` = Auto (the [`BookooMode`] discriminants).
        active_mode: u8,
        /// The enabled-modes bitmask, byte `[3]` — a 3-bit mask, bit `n` set
        /// when the [`BookooMode`] of index `n` is enabled.
        enabled_modes: u8,
    },
}

/// Format the Bookoo's `u16` firmware version as a `"M.m.p"` string.
///
/// The scale encodes the firmware as `major × 100 + minor × 10 + patch`,
/// so e.g. `141` is firmware `1.4.1`. Centralised here so every shell
/// renders the version identically without duplicating the decode (web
/// used to carry its own `formatFirmware`; an Android shell would have
/// done the same).
#[must_use]
pub fn format_firmware_version(encoded: u16) -> String {
    let major = encoded / 100;
    let minor = (encoded / 10) % 10;
    let patch = encoded % 10;
    format!("{major}.{minor}.{patch}")
}

/// `[0-1]` header of a `03 0c` serial-number command response.
const RESPONSE_HEADER_SERIAL: [u8; 2] = [0x03, 0x0C];
/// `[0-1]` header of a `03 0e` settings command response.
const RESPONSE_HEADER_SETTINGS: [u8; 2] = [0x03, 0x0E];

/// Decode a 20-byte Bookoo `ff12` command-characteristic notification into a
/// [`CommandResponse`].
///
/// Returns `None` for anything that is not exactly [`FULL_PACKET_LEN`] bytes,
/// for a frame whose `[0-1]` header is neither of the two known responses, or
/// for a frame whose `[19]` XOR checksum (over `[0..=18]`) does not match — the
/// same checksum scheme [`parse_packet`] uses, here applied strictly because a
/// command response carries no field a corrupt frame could still surface.
pub fn parse_command_response(data: &[u8]) -> Option<CommandResponse> {
    if data.len() != FULL_PACKET_LEN {
        return None;
    }
    // The checksum byte [19] is an XOR of every preceding byte.
    let checksum = data[..FULL_PACKET_LEN - 1].iter().fold(0_u8, |a, &b| a ^ b);
    if checksum != data[FULL_PACKET_LEN - 1] {
        return None;
    }

    match [data[0], data[1]] {
        RESPONSE_HEADER_SERIAL => {
            let firmware_version = (u16::from(data[2]) << 8) | u16::from(data[3]);
            // Bytes [4-17] are the 14-byte ASCII serial; drop trailing NUL /
            // whitespace padding and any non-ASCII byte defensively.
            let serial: String = data[4..18]
                .iter()
                .copied()
                .take_while(|&b| b != 0)
                .filter(|b| b.is_ascii())
                .map(char::from)
                .collect::<String>()
                .trim()
                .to_owned();
            Some(CommandResponse::Serial {
                firmware_version,
                serial,
                anti_mistouch: data[18] != 0,
            })
        }
        RESPONSE_HEADER_SETTINGS => Some(CommandResponse::Settings {
            active_mode: data[2],
            enabled_modes: data[3],
        }),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn firmware_version_formatting() {
        // Standard cases from the Bookoo's own docs.
        assert_eq!(format_firmware_version(141), "1.4.1");
        assert_eq!(format_firmware_version(100), "1.0.0");
        assert_eq!(format_firmware_version(202), "2.0.2");
        // Zero — no version reported yet.
        assert_eq!(format_firmware_version(0), "0.0.0");
        // The encoding maxes out at 999 in practice; a 4-digit value
        // overflows the documented format but should still produce
        // *some* readable output (used to find debug-mode firmware).
        assert_eq!(format_firmware_version(1234), "12.3.4");
    }

    /// Decode a lowercase-hex string into a fixed 20-byte array.
    fn hex20(s: &str) -> [u8; 20] {
        let bytes: Vec<u8> = (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
            .collect();
        bytes.try_into().unwrap()
    }

    /// Decode a lowercase-hex string into a fixed 6-byte command array.
    fn hex6(s: &str) -> [u8; 6] {
        let bytes: Vec<u8> = (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
            .collect();
        bytes.try_into().unwrap()
    }

    #[test]
    fn decodes_a_big_endian_24bit_weight_to_grams() {
        // Bytes 7–9 = 0x0007D0 = 2000 -> 20.00 g; sign byte '+'.
        let packet = [0, 0, 0, 0, 0, 0, b'+', 0x00, 0x07, 0xD0];
        assert_eq!(parse_weight(&packet), Some(20.0));
    }

    #[test]
    fn decodes_a_negative_weight() {
        let packet = [0, 0, 0, 0, 0, 0, b'-', 0x00, 0x07, 0xD0];
        assert_eq!(parse_weight(&packet), Some(-20.0));
    }

    #[test]
    fn rejects_a_short_packet() {
        assert_eq!(parse_weight(&[0; 9]), None);
    }

    #[test]
    fn parse_packet_rejects_wrong_length() {
        assert_eq!(parse_packet(&[0; 19]), None);
        assert_eq!(parse_packet(&[0; 21]), None);
    }

    #[test]
    fn decodes_an_idle_packet_from_a_real_capture() {
        // Idle: weight 0, flow 0, timer 0.
        let packet = hex20("030b000000012b0000002b0000640096010000fa");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert_eq!(p.weight_g, 0.0);
        assert_eq!(p.flow_raw, 0);
        assert_eq!(p.flow_g_per_s, 0.0);
        assert_eq!(p.timer_ms, 0);
        assert_eq!(p.weight_indicator, b'+');
        assert_eq!(p.flow_indicator, b'+');
        assert!(p.checksum_ok);
    }

    #[test]
    fn decodes_a_settled_weight_from_a_real_capture() {
        // Bytes 7-9 = 0xEDC6 = 60870 -> 608.70 g; flow 0.
        let packet = hex20("030b000000012b00edc62b0000640096010000d1");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert!((p.weight_g - 608.70).abs() < 0.001, "weight {}", p.weight_g);
        assert_eq!(p.flow_raw, 0);
        assert_eq!(p.weight_indicator, b'+');
        assert!(p.checksum_ok);
    }

    #[test]
    fn the_indicator_byte_does_not_flip_the_weight_of_a_static_object() {
        // Same 0xEDC6 weight bytes, [6] = '-' instead of '+'. The legacy app
        // would read this as -608.70 g; the indicator byte is preserved raw so
        // callers can see it is unreliable (see module docs).
        let packet = hex20("030b000000012d00edc62b0000640096010000d7");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert_eq!(p.weight_indicator, b'-');
        // parse_weight / weight_g still honour [6] for legacy compatibility.
        assert!((p.weight_g + 608.70).abs() < 0.001, "weight {}", p.weight_g);
        assert!(p.checksum_ok);
    }

    #[test]
    fn decodes_flow_rate_from_a_real_capture() {
        // Bytes 7-9 = 0xC06C = 49260 -> 492.60 g; bytes 11-12 = 0x312F = 12591.
        let packet = hex20("030b000000012b00c06c2b312f64009601000048");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert!((p.weight_g - 492.60).abs() < 0.001, "weight {}", p.weight_g);
        assert_eq!(p.flow_raw, 0x312F);
        assert!((p.flow_g_per_s - 125.91).abs() < 0.001);
        assert!(p.checksum_ok);
    }

    #[test]
    fn decodes_another_flow_packet_from_a_real_capture() {
        // Bytes 7-9 = 0x6E13 = 28179 -> 281.79 g; bytes 11-12 = 0xA426 = 42022.
        let packet = hex20("030b000000012b006e132ba42664009601000005");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert!((p.weight_g - 281.79).abs() < 0.001, "weight {}", p.weight_g);
        assert_eq!(p.flow_raw, 0xA426);
        assert_eq!(p.flow_indicator, b'+');
        assert!(p.checksum_ok);
    }

    #[test]
    fn decodes_live_settings_from_a_standby_15_packet() {
        // Real capture packet whose [14-15] = 0x0096 = 150 -> 15 min standby.
        // Bytes [13-18] = 64 0096 01 00 00: battery 100, vol 1, flow smoothing
        // off, auto-stop mode 0 (Flow-Stop).
        let packet = hex20("030b000000012b0000002b0000640096010000fa");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert_eq!(p.battery_percent, 100);
        assert_eq!(p.standby_minutes, 15);
        assert_eq!(p.volume, 1);
        assert!(!p.flow_smoothing);
        assert_eq!(p.auto_stop, 0);
        assert!(p.checksum_ok);
    }

    #[test]
    fn decodes_live_settings_from_a_standby_30_packet() {
        // Real capture packet (session-20260517-215049): [14-15] = 0x012C =
        // 300 -> 30 min standby; [16] = 0x03 volume; [17]/[18] = 00/01.
        let packet = hex20("030b000000012b0000002b000264012c03000140");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert_eq!(p.battery_percent, 100);
        assert_eq!(p.standby_minutes, 30);
        assert_eq!(p.volume, 3);
        assert!(!p.flow_smoothing);
        assert_eq!(p.auto_stop, 1);
        assert!(p.checksum_ok);
    }

    #[test]
    fn decodes_live_settings_from_a_standby_16_volume_3_packet() {
        // Real capture packet: [14-15] = 0x00A0 = 160 -> 16 min standby.
        let packet = hex20("030b000000012b0000002b00026400a0030001cd");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert_eq!(p.standby_minutes, 16);
        assert_eq!(p.volume, 3);
        assert!(p.checksum_ok);
    }

    #[test]
    fn decodes_a_packet_where_flow_smoothing_is_on() {
        // Real capture packet recorded right after set_flow_smoothing(true):
        // [13-18] = 64 012C 03 01 01 — the [17] flow-smoothing byte flipped to
        // 1, confirming the [17] = flow-smoothing mapping.
        let packet = hex20("030b000000012b0000002b000064012c03010143");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert_eq!(p.standby_minutes, 30);
        assert_eq!(p.volume, 3);
        assert!(p.flow_smoothing);
        assert_eq!(p.auto_stop, 1);
        assert!(p.checksum_ok);
    }

    #[test]
    fn decodes_volume_0_from_a_real_capture() {
        // Real capture packet with volume 0 and a 10-minute standby
        // ([14-15] = 0x0064 = 100 -> 10 min).
        let packet = hex20("030b000000012b0000002b000064006400000108");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert_eq!(p.standby_minutes, 10);
        assert_eq!(p.volume, 0);
        assert!(p.checksum_ok);
    }

    #[test]
    fn command_computes_the_xor_checksum() {
        // XOR of 03 0a 01 00 00 = 08.
        assert_eq!(command(0x01, 0x00, 0x00), hex6("030a01000008"));
    }

    #[test]
    fn timer_constants_match_the_captured_bytes() {
        assert_eq!(TARE, hex6("030a01000008"));
        assert_eq!(TIMER_START, hex6("030a0400000d"));
        assert_eq!(TIMER_STOP, hex6("030a0500000c"));
        assert_eq!(TIMER_RESET, hex6("030a0600000f"));
    }

    #[test]
    fn set_volume_matches_the_captured_bytes() {
        assert_eq!(set_volume(1), hex6("030a0200010a"));
        assert_eq!(set_volume(2), hex6("030a02000209"));
        assert_eq!(set_volume(3), hex6("030a02000308"));
        assert_eq!(set_volume(5), hex6("030a0200050e"));
    }

    #[test]
    fn set_volume_clamps_to_the_valid_range() {
        // Out-of-range input clamps to 5.
        assert_eq!(set_volume(9), set_volume(5));
        assert_eq!(set_volume(255), set_volume(5));
    }

    #[test]
    fn set_standby_minutes_matches_the_captured_bytes() {
        assert_eq!(set_standby_minutes(11), hex6("030a03000b01"));
        assert_eq!(set_standby_minutes(12), hex6("030a03000c06"));
        assert_eq!(set_standby_minutes(17), hex6("030a0300111b"));
        assert_eq!(set_standby_minutes(18), hex6("030a03001218"));
        assert_eq!(set_standby_minutes(19), hex6("030a03001319"));
        assert_eq!(set_standby_minutes(20), hex6("030a0300141e"));
        assert_eq!(set_standby_minutes(21), hex6("030a0300151f"));
    }

    #[test]
    fn set_standby_minutes_clamps_to_the_valid_range() {
        assert_eq!(set_standby_minutes(2), set_standby_minutes(5));
        assert_eq!(set_standby_minutes(0), set_standby_minutes(5));
        assert_eq!(set_standby_minutes(40), set_standby_minutes(30));
    }

    #[test]
    fn set_mode_enabled_matches_the_captured_bytes() {
        assert_eq!(
            set_mode_enabled(BookooMode::FlowRate, true),
            hex6("030a0e000106")
        );
        assert_eq!(
            set_mode_enabled(BookooMode::FlowRate, false),
            hex6("030a0e000007")
        );
        assert_eq!(
            set_mode_enabled(BookooMode::Timer, false),
            hex6("030a0e010006")
        );
        assert_eq!(
            set_mode_enabled(BookooMode::Timer, true),
            hex6("030a0e010107")
        );
        assert_eq!(
            set_mode_enabled(BookooMode::Auto, true),
            hex6("030a0e020104")
        );
        assert_eq!(
            set_mode_enabled(BookooMode::Auto, false),
            hex6("030a0e020005")
        );
    }

    #[test]
    fn select_mode_enables_the_target_before_disabling_the_others() {
        // Target on first, then the other two off — never zero modes enabled.
        assert_eq!(
            select_mode(BookooMode::Timer),
            [
                hex6("030a0e010107"), // Timer    -> on
                hex6("030a0e000007"), // FlowRate -> off
                hex6("030a0e020005"), // Auto     -> off
            ]
        );
    }

    #[test]
    fn set_flow_smoothing_matches_the_captured_bytes() {
        assert_eq!(set_flow_smoothing(true), hex6("030a08010000"));
        assert_eq!(set_flow_smoothing(false), hex6("030a08000001"));
    }

    #[test]
    fn set_auto_stop_mode_matches_the_captured_bytes() {
        // 0x0B 00 = Flow-Stop, 0x0B 01 = Cup-Removal (confirmed by capture).
        assert_eq!(
            set_auto_stop_mode(AutoStopMode::FlowStop),
            hex6("030a0b000002")
        );
        assert_eq!(
            set_auto_stop_mode(AutoStopMode::CupRemoval),
            hex6("030a0b010003")
        );
    }

    #[test]
    fn set_anti_mistouch_matches_the_captured_bytes() {
        assert_eq!(set_anti_mistouch(true), hex6("030a10010018"));
        assert_eq!(set_anti_mistouch(false), hex6("030a10000019"));
    }

    #[test]
    fn query_constants_match_the_captured_bytes() {
        assert_eq!(QUERY_SERIAL, hex6("030a0a000003"));
        assert_eq!(QUERY_SETTINGS, hex6("030a0f000006"));
    }

    #[test]
    fn parse_command_response_decodes_a_serial_response_with_anti_mistouch_on() {
        // Real 03 0c response: fw 1.4.1 (0x008d = 141), serial "SN2400d66a89e7",
        // [18] = 0x01 -> anti-mistouch on.
        let frame = hex20("030c008d534e32343030643636613839653701ce");
        let response = parse_command_response(&frame).expect("a 03 0c response");
        assert_eq!(
            response,
            CommandResponse::Serial {
                firmware_version: 141,
                serial: "SN2400d66a89e7".to_owned(),
                anti_mistouch: true,
            }
        );
    }

    #[test]
    fn parse_command_response_decodes_a_serial_response_with_anti_mistouch_off() {
        // Same 03 0c response with [18] = 0x00 -> anti-mistouch off; the
        // checksum [19] flips from 0xce to 0xcf accordingly.
        let frame = hex20("030c008d534e32343030643636613839653700cf");
        let response = parse_command_response(&frame).expect("a 03 0c response");
        assert_eq!(
            response,
            CommandResponse::Serial {
                firmware_version: 141,
                serial: "SN2400d66a89e7".to_owned(),
                anti_mistouch: false,
            }
        );
    }

    #[test]
    fn parse_command_response_decodes_a_settings_response() {
        // Real 03 0e response: [2] = 0x02 -> Auto active, [3] = 0x04 -> only
        // the Auto mode (bit 2) enabled.
        let frame = hex20("030e02040000000000000000000000000000000b");
        let response = parse_command_response(&frame).expect("a 03 0e response");
        assert_eq!(
            response,
            CommandResponse::Settings {
                active_mode: 2,
                enabled_modes: 0b100,
            }
        );
    }

    #[test]
    fn parse_command_response_rejects_a_wrong_length_frame() {
        assert_eq!(parse_command_response(&[0; 19]), None);
        assert_eq!(parse_command_response(&[0; 21]), None);
    }

    #[test]
    fn parse_command_response_rejects_an_unknown_header() {
        // A 03 0b weight-notification header is not a command response.
        let frame = hex20("030b000000012b0000002b0000640096010000fa");
        assert_eq!(parse_command_response(&frame), None);
    }

    #[test]
    fn parse_command_response_rejects_a_corrupt_checksum() {
        // A command response carries no field a corrupt frame could still
        // surface, so a checksum mismatch rejects the frame outright.
        let mut frame = hex20("030e02040000000000000000000000000000000b");
        frame[19] ^= 0xFF;
        assert_eq!(parse_command_response(&frame), None);
    }

    #[test]
    fn a_corrupt_checksum_is_reported_not_rejected() {
        // Take a known-good packet and flip the checksum byte.
        let mut packet = hex20("030b000000012b0000002b0000640096010000fa");
        packet[19] ^= 0xFF;
        let p = parse_packet(&packet).expect("still a 20-byte packet");
        assert!(!p.checksum_ok);
        // The rest of the decode still works.
        assert_eq!(p.weight_g, 0.0);
    }
}
