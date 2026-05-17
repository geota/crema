//! Smartchef BLE codec (`scale_type` `smartchef`).
//!
//! Smartchef exposes **no software commands** — tare is physical-button only,
//! and there is no timer. Only weight decoding is implemented.
//! See `docs/06-scale-protocols.md` §10.

/// GATT service UUID (the generic `FFF0` service).
pub const SERVICE_UUID: &str = "0000fff0-0000-1000-8000-00805f9b34fb";
/// Characteristic the scale notifies weight on.
pub const STATUS_UUID: &str = "0000fff1-0000-1000-8000-00805f9b34fb";
/// Characteristic for command writes (unused — Smartchef has no commands).
pub const COMMAND_UUID: &str = "0000fff2-0000-1000-8000-00805f9b34fb";

/// Decode a weight notification into grams.
///
/// Bytes 5–6 are an unsigned big-endian 16-bit value in units of 0.1 g; the
/// weight is negative when byte 3 exceeds 10.
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    if data.len() < 7 {
        return None;
    }
    let weight = u16::from_be_bytes([data[5], data[6]]);
    let grams = f32::from(weight) / 10.0;
    Some(if data[3] > 10 { -grams } else { grams })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_big_endian_weight_to_grams() {
        // Bytes 5–6 = 0x00 0xB4 -> 180 -> 18.0 g.
        assert_eq!(parse_weight(&[0, 0, 0, 0, 0, 0x00, 0xB4]), Some(18.0));
    }

    #[test]
    fn treats_a_high_byte_three_as_negative() {
        assert_eq!(parse_weight(&[0, 0, 0, 11, 0, 0x00, 0xB4]), Some(-18.0));
    }

    #[test]
    fn treats_byte_three_at_the_threshold_as_positive() {
        // The sign rule is `data[3] > 10`: a value of exactly 10 is positive.
        assert_eq!(parse_weight(&[0, 0, 0, 10, 0, 0x00, 0xB4]), Some(18.0));
    }

    #[test]
    fn decodes_a_zero_weight() {
        assert_eq!(parse_weight(&[0, 0, 0, 0, 0, 0x00, 0x00]), Some(0.0));
    }

    #[test]
    fn decodes_the_maximum_unsigned_magnitude() {
        // Bytes 5–6 are an unsigned big-endian u16: 0xFFFF = 65535 -> 6553.5 g.
        assert_eq!(parse_weight(&[0, 0, 0, 0, 0, 0xFF, 0xFF]), Some(6553.5));
        // Same magnitude with the sign flag set negates it.
        assert_eq!(parse_weight(&[0, 0, 0, 11, 0, 0xFF, 0xFF]), Some(-6553.5));
    }

    #[test]
    fn accepts_a_packet_at_the_exact_minimum_length() {
        // Exactly seven bytes is the boundary — six bytes is rejected below.
        assert_eq!(parse_weight(&[0, 0, 0, 0, 0, 0x00, 0xB4]), Some(18.0));
    }

    #[test]
    fn rejects_a_short_packet() {
        assert_eq!(parse_weight(&[0, 0, 0, 0, 0, 0]), None);
    }
}
