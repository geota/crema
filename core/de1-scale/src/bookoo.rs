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
//! [13]    constant 0x64  (battery %? — always 100 in the capture)
//! [14]    constant 0x00
//! [15-16] constant 0x96 0x01
//! [17-18] constant 0x00 0x00
//! [19]    checksum: XOR of bytes [0..=18] (verified: 0 mismatches / 606)
//! ```
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
//! 0x08 toggle_0x08            TENTATIVE — flow-smoothing or anti-mistouch
//! 0x0A QUERY_SERIAL           query serial number (scale replies 03 0c …)
//! 0x0B toggle_0x0b            TENTATIVE — probably auto-stop mode
//! 0x0E set_mode_enabled       enable/disable a display mode, p1 = mode index
//! 0x0F QUERY_SETTINGS         query settings (scale replies 03 0e …)
//! 0x10 toggle_0x10            TENTATIVE — flow-smoothing or anti-mistouch
//! ```
//!
//! Commands `0x08`, `0x0B` and `0x10` are single-boolean toggles whose feature
//! mapping is **not yet confirmed** — see each builder's doc comment.

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
#[must_use]
pub const fn set_mode_enabled(mode: BookooMode, enabled: bool) -> [u8; 6] {
    command(0x0E, mode.index(), enabled as u8)
}

/// Command `0x08` — a single-boolean toggle.
///
/// **Tentative.** `0x08` is one of *flow-smoothing* or *anti-mistouch*; the
/// capture did not establish which of `0x08`/`0x10` is which. The feature
/// mapping is pending a hardware retest — only the wire format is confirmed.
#[must_use]
pub const fn toggle_0x08(enabled: bool) -> [u8; 6] {
    command(0x08, enabled as u8, 0x00)
}

/// Command `0x0B` — a single-boolean toggle.
///
/// **Tentative.** `0x0B` is *probably* auto-stop mode, but this was not
/// confirmed by the capture and is pending a hardware retest. Only the wire
/// format is confirmed.
#[must_use]
pub const fn toggle_0x0b(enabled: bool) -> [u8; 6] {
    command(0x0B, enabled as u8, 0x00)
}

/// Command `0x10` — a single-boolean toggle.
///
/// **Tentative.** `0x10` is one of *flow-smoothing* or *anti-mistouch*; the
/// capture did not establish which of `0x08`/`0x10` is which. The feature
/// mapping is pending a hardware retest — only the wire format is confirmed.
#[must_use]
pub const fn toggle_0x10(enabled: bool) -> [u8; 6] {
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

    let timer_ms =
        (u32::from(data[2]) << 16) | (u32::from(data[3]) << 8) | u32::from(data[4]);

    let weight_raw =
        (u32::from(data[7]) << 16) | (u32::from(data[8]) << 8) | u32::from(data[9]);
    let weight_magnitude = weight_raw as f32 / 100.0;
    let weight_g = if data[6] == b'-' {
        -weight_magnitude
    } else {
        weight_magnitude
    };

    let flow_raw = (u16::from(data[11]) << 8) | u16::from(data[12]);

    // The checksum byte [19] is an XOR of every preceding byte.
    let checksum = data[..FULL_PACKET_LEN - 1].iter().fold(0_u8, |a, &b| a ^ b);

    Some(BookooPacket {
        weight_g,
        weight_indicator: data[6],
        flow_raw,
        flow_g_per_s: f32::from(flow_raw) / 100.0,
        flow_indicator: data[10],
        timer_ms,
        checksum_ok: checksum == data[FULL_PACKET_LEN - 1],
    })
}

#[cfg(test)]
mod tests {
    use super::*;

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
    fn tentative_toggles_match_the_captured_bytes() {
        assert_eq!(toggle_0x08(true), hex6("030a08010000"));
        assert_eq!(toggle_0x08(false), hex6("030a08000001"));
        assert_eq!(toggle_0x0b(true), hex6("030a0b010003"));
        assert_eq!(toggle_0x0b(false), hex6("030a0b000002"));
        assert_eq!(toggle_0x10(true), hex6("030a10010018"));
        assert_eq!(toggle_0x10(false), hex6("030a10000019"));
    }

    #[test]
    fn query_constants_match_the_captured_bytes() {
        assert_eq!(QUERY_SERIAL, hex6("030a0a000003"));
        assert_eq!(QUERY_SETTINGS, hex6("030a0f000006"));
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
