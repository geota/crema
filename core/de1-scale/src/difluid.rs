//! Difluid Microbalance BLE codec (`scale_type` `difluid`).
//!
//! **Caution — unverified.** Decoding follows reaprime (signed big-endian
//! `i32`, gated on `data[3] == 0`) and de1app, but the byte offsets (weight at
//! bytes 5–8) are still best confirmed against physical hardware. The scale
//! streams no weight until [`ENABLE_NOTIFICATIONS`] is written at connect.

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
/// Bytes 5–8 are a **signed** big-endian 32-bit value in units of 0.1 g, so a
/// below-tare reading decodes as a small negative (reaprime
/// `difluid_scale.dart:158` reads `getInt32` big-endian; de1app reads the same
/// field). Gated on `data[3] == 0`: weight and command share one characteristic,
/// so a non-weight ack (`data[3] != 0`) must not be mis-read as a weight
/// (reaprime `difluid_scale.dart:143`). Requires at least 19 bytes.
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    if data.len() < 19 || data[3] != 0 {
        return None;
    }
    let raw = i32::from_be_bytes([data[5], data[6], data[7], data[8]]);
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
    if data.len() < 19 || data[3] != 0 {
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
    fn rejects_a_non_weight_frame() {
        // data[3] != 0 marks a command ack on the shared characteristic — not a
        // weight, even at full length (reaprime difluid_scale.dart:143).
        let mut packet = [0u8; 19];
        packet[3] = 0x01;
        assert_eq!(parse_weight(&packet), None);
        assert_eq!(is_grams_unit(&packet), None);
    }

    #[test]
    fn rejects_a_short_packet() {
        assert_eq!(parse_weight(&[0u8; 18]), None);
    }

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
    fn decodes_a_negative_below_tare_reading() {
        // Signed: bytes 5–8 = 0xFFFFFFFF (-1) → -0.1 g, a real below-tare
        // reading — not the ~4.29e8 g garbage an unsigned decode produced.
        let mut packet = [0u8; 19];
        packet[5] = 0xFF;
        packet[6] = 0xFF;
        packet[7] = 0xFF;
        packet[8] = 0xFF;
        assert_eq!(parse_weight(&packet), Some(-0.1));
    }
}
