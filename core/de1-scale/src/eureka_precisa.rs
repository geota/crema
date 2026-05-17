//! Eureka Precisa BLE codec (`scale_type` `eureka_precisa`).
//!
//! Also covers the Krell CFS-9002. The Solo Barista LSJ-001 is protocol-
//! identical and re-exports this module (see [`crate::solo_barista`]).
//! See `docs/06-scale-protocols.md` §8.

/// GATT service UUID (the generic `FFF0` service — scales sharing it are
/// disambiguated by advertised name during the BLE scan).
pub const SERVICE_UUID: &str = "0000fff0-0000-1000-8000-00805f9b34fb";
/// Characteristic the scale notifies weight on.
pub const STATUS_UUID: &str = "0000fff1-0000-1000-8000-00805f9b34fb";
/// Characteristic commands are written to.
pub const COMMAND_UUID: &str = "0000fff2-0000-1000-8000-00805f9b34fb";

/// Command: tare.
pub const TARE: [u8; 4] = [0xAA, 0x02, 0x31, 0x31];
/// Command: turn the scale off.
pub const TURN_OFF: [u8; 4] = [0xAA, 0x02, 0x32, 0x32];
/// Command: start the timer.
pub const TIMER_START: [u8; 4] = [0xAA, 0x02, 0x33, 0x33];
/// Command: stop the timer.
pub const TIMER_STOP: [u8; 4] = [0xAA, 0x02, 0x34, 0x34];
/// Command: reset the timer (also stops it).
pub const TIMER_RESET: [u8; 4] = [0xAA, 0x02, 0x35, 0x35];
/// Command: beep twice.
pub const BEEP: [u8; 4] = [0xAA, 0x02, 0x37, 0x37];
/// Command: set the display unit to grams.
pub const SET_UNIT_GRAMS: [u8; 4] = [0xAA, 0x03, 0x36, 0x00];

/// Decode a weight notification into grams.
///
/// A weight frame starts with the header bytes `AA 09 41`. Bytes 7–8 are an
/// unsigned little-endian 16-bit value in units of 0.1 g; byte 6 is the sign
/// (`1` marks a negative weight).
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    if data.len() < 9 || data[0] != 0xAA || data[1] != 0x09 || data[2] != 0x41 {
        return None;
    }
    let weight = u16::from_le_bytes([data[7], data[8]]);
    let grams = f32::from(weight) / 10.0;
    Some(if data[6] == 1 { -grams } else { grams })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_little_endian_weight_to_grams() {
        // Bytes 7–8 = 0xB4 0x00 -> 180 -> 18.0 g.
        let packet = [0xAA, 0x09, 0x41, 0, 0, 0, 0, 0xB4, 0x00];
        assert_eq!(parse_weight(&packet), Some(18.0));
    }

    #[test]
    fn decodes_a_negative_weight() {
        // Sign byte (offset 6) = 1.
        let packet = [0xAA, 0x09, 0x41, 0, 0, 0, 1, 0xB4, 0x00];
        assert_eq!(parse_weight(&packet), Some(-18.0));
    }

    #[test]
    fn decodes_a_zero_weight() {
        let packet = [0xAA, 0x09, 0x41, 0, 0, 0, 0, 0x00, 0x00];
        assert_eq!(parse_weight(&packet), Some(0.0));
    }

    #[test]
    fn decodes_the_maximum_unsigned_magnitude() {
        // Bytes 7–8 are an unsigned little-endian u16: 0xFFFF -> 6553.5 g.
        let packet = [0xAA, 0x09, 0x41, 0, 0, 0, 0, 0xFF, 0xFF];
        assert_eq!(parse_weight(&packet), Some(6553.5));
    }

    #[test]
    fn treats_only_a_sign_byte_of_one_as_negative() {
        // The sign rule is `data[6] == 1`; any other value is positive.
        let two = [0xAA, 0x09, 0x41, 0, 0, 0, 2, 0xB4, 0x00];
        assert_eq!(parse_weight(&two), Some(18.0));
        let high = [0xAA, 0x09, 0x41, 0, 0, 0, 0xFF, 0xB4, 0x00];
        assert_eq!(parse_weight(&high), Some(18.0));
    }

    #[test]
    fn rejects_a_frame_with_a_bad_header() {
        let packet = [0x00, 0x09, 0x41, 0, 0, 0, 0, 0xB4, 0x00];
        assert_eq!(parse_weight(&packet), None);
    }

    #[test]
    fn rejects_a_frame_with_a_wrong_length_byte() {
        // The header is AA 09 41; a length byte other than 0x09 is not weight.
        let packet = [0xAA, 0x08, 0x41, 0, 0, 0, 0, 0xB4, 0x00];
        assert_eq!(parse_weight(&packet), None);
    }

    #[test]
    fn rejects_a_frame_with_a_wrong_type_byte() {
        // The third header byte 0x41 marks a weight frame; 0x42 does not.
        let packet = [0xAA, 0x09, 0x42, 0, 0, 0, 0, 0xB4, 0x00];
        assert_eq!(parse_weight(&packet), None);
    }

    #[test]
    fn rejects_a_short_packet() {
        // A correct weight header but only eight bytes — one short of the minimum.
        assert_eq!(parse_weight(&[0xAA, 0x09, 0x41, 0, 0, 0, 0, 0xB4]), None);
    }
}
