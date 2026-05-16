//! Felicita Arc BLE codec (`scale_type` `felicita`).
//!
//! One characteristic is used for both weight notifications and commands.
//! See `docs/06-scale-protocols.md` §3.

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
}
