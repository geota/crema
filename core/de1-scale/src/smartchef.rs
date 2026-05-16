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
    fn rejects_a_short_packet() {
        assert_eq!(parse_weight(&[0, 0, 0, 0, 0, 0]), None);
    }
}
