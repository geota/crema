//! Varia Aku BLE codec (`scale_type` `varia_aku`).
//!
//! Covers AKU Pro / Mini / Plus / Micro. See `docs/06-scale-protocols.md` §13.

// Raw integer weight fields are decoded into `f32` grams; precision loss past
// 2^23 is inherent to representing a wire reading as the codec's `f32` weight,
// not a defect, so the precision-loss lint is allowed module-wide here.
#![allow(clippy::cast_precision_loss)]

/// GATT service UUID (the generic `FFF0` service).
pub const SERVICE_UUID: &str = "0000fff0-0000-1000-8000-00805f9b34fb";
/// Characteristic the scale notifies weight on.
pub const STATUS_UUID: &str = "0000fff1-0000-1000-8000-00805f9b34fb";
/// Characteristic commands are written to.
pub const COMMAND_UUID: &str = "0000fff2-0000-1000-8000-00805f9b34fb";

/// Command: tare. (The only command Varia Aku supports.)
pub const TARE: [u8; 5] = [0xFA, 0x82, 0x01, 0x01, 0x82];

/// Decode a weight notification into grams.
///
/// A weight frame has `command` byte `0x01` and `length` byte `0x03`. The
/// 3-byte payload is a big-endian 24-bit magnitude in units of 0.01 g; the
/// high nibble of the first payload byte is the sign (`1` marks negative).
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    // Bytes: [0] header, [1] command, [2] length, [3..] payload.
    if data.len() < 7 || data[1] != 0x01 || data[2] != 0x03 {
        return None;
    }
    let (w1, w2, w3) = (data[3], data[4], data[5]);
    let magnitude =
        ((u32::from(w1 & 0x0F) << 16) | (u32::from(w2) << 8) | u32::from(w3)) as f32 / 100.0;
    let negative = w1 & 0x10 != 0;
    Some(if negative { -magnitude } else { magnitude })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_a_24bit_weight_to_grams() {
        // Payload 0x00 0x07 0x08 -> 1800 -> 18.00 g; sign nibble clear.
        let packet = [0x00, 0x01, 0x03, 0x00, 0x07, 0x08, 0x00];
        assert_eq!(parse_weight(&packet), Some(18.0));
    }

    #[test]
    fn treats_the_sign_nibble_as_negative() {
        // First payload byte 0x10: sign nibble set, magnitude bits clear.
        let packet = [0x00, 0x01, 0x03, 0x10, 0x07, 0x08, 0x00];
        assert_eq!(parse_weight(&packet), Some(-18.0));
    }

    #[test]
    fn decodes_a_zero_weight() {
        let packet = [0x00, 0x01, 0x03, 0x00, 0x00, 0x00, 0x00];
        assert_eq!(parse_weight(&packet), Some(0.0));
    }

    #[test]
    fn decodes_the_maximum_magnitude() {
        // The magnitude is a 20-bit field (high nibble of byte 3 is the sign):
        // 0x0FFFFF = 1048575 counts of 0.01 g.
        let packet = [0x00, 0x01, 0x03, 0x0F, 0xFF, 0xFF, 0x00];
        assert_eq!(parse_weight(&packet), Some(0x0F_FFFF as f32 / 100.0));
        // Same magnitude with the sign nibble set.
        let neg = [0x00, 0x01, 0x03, 0x1F, 0xFF, 0xFF, 0x00];
        assert_eq!(parse_weight(&neg), Some(-(0x0F_FFFF as f32) / 100.0));
    }

    #[test]
    fn rejects_a_frame_with_a_wrong_length_byte() {
        // A weight frame must have length byte 0x03; 0x04 is not a weight frame.
        let packet = [0x00, 0x01, 0x04, 0x00, 0x07, 0x08, 0x00];
        assert_eq!(parse_weight(&packet), None);
    }

    #[test]
    fn rejects_a_non_weight_frame() {
        // command 0x85 is a battery frame, not weight.
        let packet = [0x00, 0x85, 0x01, 0x00, 0x00, 0x00, 0x00];
        assert_eq!(parse_weight(&packet), None);
    }

    #[test]
    fn rejects_a_short_packet() {
        // A correct weight header but only six bytes — one short of the minimum.
        assert_eq!(parse_weight(&[0x00, 0x01, 0x03, 0x00, 0x07, 0x08]), None);
    }
}
