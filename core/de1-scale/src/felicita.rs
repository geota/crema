//! Felicita Arc BLE codec (`scale_type` `felicita`).
//!
//! One characteristic is used for both weight notifications and commands.

// Raw integer weight fields are decoded into `f32` grams; precision loss past
// 2^23 is inherent to representing a wire reading as the codec's `f32` weight,
// not a defect, so the precision-loss lint is allowed module-wide here.
#![allow(clippy::cast_precision_loss)]

/// GATT service UUID.
pub const SERVICE_UUID: &str = "0000ffe0-0000-1000-8000-00805f9b34fb";
/// Characteristic for both weight notifications and command writes.
pub const NOTIFY_COMMAND_UUID: &str = "0000ffe1-0000-1000-8000-00805f9b34fb";

/// Command byte: tare (ASCII `'T'`).
pub const TARE: u8 = b'T';
/// Command byte: start the timer (ASCII `'R'`).
pub const TIMER_START: u8 = b'R';
/// Command byte: stop the timer (ASCII `'S'`).
pub const TIMER_STOP: u8 = b'S';
/// Command byte: reset the timer (ASCII `'C'`).
pub const TIMER_RESET: u8 = b'C';

/// Decode a weight notification into grams.
///
/// The Felicita weight is unusual: bytes 3–8 are six **ASCII decimal digits**,
/// not a binary integer. Byte 0 must be `1` and byte 1 must be `2`; byte 2 is
/// an ASCII sign (`'-'` marks a negative weight).
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    if data.len() < 9 || data[0] != 1 || data[1] != 2 {
        return None;
    }
    let digits = core::str::from_utf8(&data[3..9]).ok()?;
    let value: i32 = digits.trim().parse().ok()?;
    let grams = value as f32 / 100.0;
    Some(if data[2] == b'-' { -grams } else { grams })
}

/// Minimum raw battery byte value — the scale reports `[15]` between
/// `MIN_BATTERY_RAW` (empty) and `MAX_BATTERY_RAW` (full). Reaprime
/// (`arc.dart:122-138`) interpolates `[15]` from this range into a 0–100
/// percentage; legacy de1app reads it raw without rescaling. Crema mirrors
/// reaprime: the user-facing field is a percentage.
const MIN_BATTERY_RAW: u8 = 129;
/// Maximum raw battery byte value — see [`MIN_BATTERY_RAW`].
const MAX_BATTERY_RAW: u8 = 158;

/// Decode the Felicita battery percentage from a weight notification.
///
/// Reaprime extracts byte `[15]` of an 18-byte frame and linearly maps it
/// from the scale's `[129..=158]` reporting range to `0..=100`
/// (`arc.dart:122-138`). Legacy de1app reads the same byte raw without
/// rescaling — Crema adopts reaprime's rescaling so the percentage matches
/// what the scale's own indicator shows.
///
/// Returns `None` for a packet that is not at least 18 bytes (i.e. doesn't
/// carry the battery byte) or that fails the [`parse_weight`] header gate.
#[must_use]
pub fn parse_battery_percent(data: &[u8]) -> Option<u8> {
    if data.len() < 18 || data[0] != 1 || data[1] != 2 {
        return None;
    }
    let raw = data[15];
    let clamped = raw.clamp(MIN_BATTERY_RAW, MAX_BATTERY_RAW);
    let range = u32::from(MAX_BATTERY_RAW - MIN_BATTERY_RAW);
    let percent = u32::from(clamped - MIN_BATTERY_RAW) * 100 / range;
    u8::try_from(percent).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_ascii_digit_weight_to_grams() {
        // "001800" -> 1800 -> 18.00 g.
        let packet = [1, 2, b'+', b'0', b'0', b'1', b'8', b'0', b'0'];
        assert_eq!(parse_weight(&packet), Some(18.0));
    }

    #[test]
    fn decodes_a_negative_weight() {
        let packet = [1, 2, b'-', b'0', b'0', b'1', b'8', b'0', b'0'];
        assert_eq!(parse_weight(&packet), Some(-18.0));
    }

    #[test]
    fn rejects_a_packet_with_a_bad_header() {
        let packet = [9, 9, b'+', b'0', b'0', b'1', b'8', b'0', b'0'];
        assert_eq!(parse_weight(&packet), None);
    }

    #[test]
    fn rejects_a_short_packet() {
        assert_eq!(parse_weight(&[1, 2, b'+']), None);
    }

    /// Build an 18-byte weight+battery frame with a known battery raw byte at
    /// offset 15. Mirrors a real Felicita notification.
    fn frame_with_battery(battery_raw: u8) -> [u8; 18] {
        let mut f = [0u8; 18];
        f[0] = 1;
        f[1] = 2;
        f[2] = b'+';
        f[3..9].copy_from_slice(b"001800");
        f[15] = battery_raw;
        f
    }

    #[test]
    fn battery_minimum_raw_decodes_to_0_percent() {
        assert_eq!(
            parse_battery_percent(&frame_with_battery(MIN_BATTERY_RAW)),
            Some(0)
        );
    }

    #[test]
    fn battery_maximum_raw_decodes_to_100_percent() {
        assert_eq!(
            parse_battery_percent(&frame_with_battery(MAX_BATTERY_RAW)),
            Some(100)
        );
    }

    #[test]
    fn battery_mid_range_decodes_to_about_half() {
        // (143 - 129) / 29 * 100 ≈ 48.
        assert_eq!(parse_battery_percent(&frame_with_battery(143)), Some(48));
    }

    #[test]
    fn battery_below_minimum_clamps_to_0() {
        // A reading below the documented range clamps to "empty".
        assert_eq!(parse_battery_percent(&frame_with_battery(0)), Some(0));
    }

    #[test]
    fn battery_above_maximum_clamps_to_100() {
        // A reading above the documented range clamps to "full".
        assert_eq!(parse_battery_percent(&frame_with_battery(255)), Some(100));
    }

    #[test]
    fn battery_rejects_a_short_frame() {
        // A 9-byte frame carries weight but not the battery byte at [15].
        assert_eq!(
            parse_battery_percent(&[1, 2, b'+', b'0', b'0', b'1', b'8', b'0', b'0']),
            None
        );
    }

    #[test]
    fn battery_rejects_a_bad_header() {
        let mut bad = frame_with_battery(143);
        bad[0] = 9;
        assert_eq!(parse_battery_percent(&bad), None);
    }
}
