//! Difluid Microbalance BLE codec (`scale_type` `difluid`).
//!
//! **Caution — unverified.** The legacy Tcl parser is hex-string based and the
//! byte offset (bytes 5–8) and lack of sign handling were flagged as needing
//! confirmation against real hardware. This module follows the documented
//! behaviour (big-endian `u32`, no sign) but should be checked against a
//! physical scale before being relied upon.

// Raw integer weight fields are decoded into `f32` grams; precision loss past
// 2^23 is inherent to representing a wire reading as the codec's `f32` weight,
// not a defect, so the precision-loss lint is allowed module-wide here.
#![allow(clippy::cast_precision_loss)]

/// GATT service UUID.
pub const SERVICE_UUID: &str = "000000ee-0000-1000-8000-00805f9b34fb";
/// Characteristic for both weight notifications and command writes.
pub const NOTIFY_COMMAND_UUID: &str = "0000aa01-0000-1000-8000-00805f9b34fb";

/// Command: enable automatic weight notifications. Must be sent after connect,
/// or the scale never pushes weight.
pub const ENABLE_NOTIFICATIONS: [u8; 7] = [0xDF, 0xDF, 0x01, 0x00, 0x01, 0x01, 0xC1];
/// Command: tare.
pub const TARE: [u8; 7] = [0xDF, 0xDF, 0x03, 0x02, 0x01, 0x01, 0xC5];
/// Command: start the timer.
pub const TIMER_START: [u8; 7] = [0xDF, 0xDF, 0x03, 0x02, 0x01, 0x00, 0xC4];
/// Command: stop the timer.
pub const TIMER_STOP: [u8; 7] = [0xDF, 0xDF, 0x03, 0x01, 0x01, 0x00, 0xC3];
/// Command: reset the timer (same frame as start).
pub const TIMER_RESET: [u8; 7] = TIMER_START;
/// Command: set the display unit to grams.
pub const SET_UNIT_GRAMS: [u8; 7] = [0xDF, 0xDF, 0x01, 0x04, 0x01, 0x00, 0xC4];

/// Decode a weight notification into grams.
///
/// Bytes 5–8 are read as an unsigned big-endian 32-bit value in units of
/// 0.1 g. The legacy parser has no sign handling, so negative weights are not
/// represented. Requires at least 19 bytes.
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    if data.len() < 19 {
        return None;
    }
    let raw = u32::from_be_bytes([data[5], data[6], data[7], data[8]]);
    Some(raw as f32 / 10.0)
}

/// Whether the Difluid is currently displaying grams.
///
/// Byte `[17]` of a weight notification is the unit flag — `0` for grams,
/// non-zero for ounces / pounds / ml. Returns `None` if `data` is too short
/// to carry the flag (matches the [`parse_weight`] length gate). Returns
/// `Some(false)` for a non-grams reading, which is the signal the
/// auto-recovery policy uses to re-send [`SET_UNIT_GRAMS`].
///
/// **Defer to reaprime.** Reaprime's parse path
/// (`reaprime/lib/src/models/device/impl/difluid/difluid_scale.dart:147-154`)
/// fires `SET_UNIT_GRAMS` whenever `data[17] != 0`; legacy de1app sends
/// `SET_UNIT_GRAMS` once at connect and never checks again. Crema mirrors
/// reaprime's behaviour at the shell layer (see `CremaCore::on_notification`).
#[must_use]
pub fn is_grams_unit(data: &[u8]) -> Option<bool> {
    if data.len() < 19 {
        return None;
    }
    Some(data[17] == 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_big_endian_weight_to_grams() {
        // Bytes 5–8 = 0x000000B4 = 180 -> 18.0 g.
        let mut packet = [0u8; 19];
        packet[8] = 0xB4;
        assert_eq!(parse_weight(&packet), Some(18.0));
    }

    #[test]
    fn decodes_a_zero_weight() {
        // An all-zero payload is a valid zero reading.
        assert_eq!(parse_weight(&[0u8; 19]), Some(0.0));
    }

    #[test]
    fn accepts_a_packet_at_the_exact_minimum_length() {
        // Exactly 19 bytes is the boundary — one fewer is rejected.
        let packet = [0u8; 19];
        assert_eq!(parse_weight(&packet), Some(0.0));
    }

    #[test]
    fn decodes_a_large_unsigned_weight() {
        // The codec reads bytes 5–8 as an unsigned big-endian u32: a value
        // with the top bit set stays positive (no two's-complement sign).
        let mut packet = [0u8; 19];
        packet[5] = 0x80;
        let raw = 0x8000_0000u32;
        assert_eq!(parse_weight(&packet), Some(raw as f32 / 10.0));
    }

    #[test]
    fn rejects_a_short_packet() {
        assert_eq!(parse_weight(&[0u8; 18]), None);
    }

    /// The Difluid protocol has no sign field — bytes 5–8 are an unsigned
    /// `u32`, so a negative weight cannot be represented and the largest count
    /// `0xFFFFFFFF` decodes as a large positive number rather than wrapping.
    #[test]
    fn is_grams_unit_true_for_zero_byte_17() {
        // Reaprime's `data[17] != 0` check: byte [17] == 0 means grams.
        let packet = [0u8; 19];
        assert_eq!(is_grams_unit(&packet), Some(true));
    }

    #[test]
    fn is_grams_unit_false_for_a_non_grams_unit_byte() {
        // Any non-zero byte means the scale is in a non-grams unit and the
        // auto-recovery path should re-send SET_UNIT_GRAMS.
        let mut packet = [0u8; 19];
        packet[17] = 0x01;
        assert_eq!(is_grams_unit(&packet), Some(false));
        packet[17] = 0xFF;
        assert_eq!(is_grams_unit(&packet), Some(false));
    }

    #[test]
    fn is_grams_unit_rejects_a_short_packet() {
        assert_eq!(is_grams_unit(&[0u8; 18]), None);
    }

    #[test]
    fn the_protocol_cannot_represent_a_negative_weight() {
        let mut packet = [0u8; 19];
        packet[5] = 0xFF;
        packet[6] = 0xFF;
        packet[7] = 0xFF;
        packet[8] = 0xFF;
        let decoded = parse_weight(&packet).unwrap();
        assert!(decoded > 0.0, "an all-ones payload must decode as positive");
        assert_eq!(decoded, u32::MAX as f32 / 10.0);
    }
}
