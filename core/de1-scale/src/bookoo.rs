//! Bookoo scale BLE codec (`scale_type` `bookoo`).
//!
//! Weight notifications arrive on [`NOTIFY_UUID`]; commands are written to
//! [`COMMAND_UUID`]. The commands are fixed byte sequences (the legacy app
//! hard-codes them, checksum included).

/// GATT service UUID.
pub const SERVICE_UUID: &str = "00000ffe-0000-1000-8000-00805f9b34fb";
/// Characteristic the scale notifies weight on.
pub const NOTIFY_UUID: &str = "0000ff11-0000-1000-8000-00805f9b34fb";
/// Characteristic commands are written to.
pub const COMMAND_UUID: &str = "0000ff12-0000-1000-8000-00805f9b34fb";

/// Command: tare the scale.
pub const TARE: [u8; 6] = [0x03, 0x0A, 0x01, 0x00, 0x00, 0x08];
/// Command: start the timer.
pub const TIMER_START: [u8; 6] = [0x03, 0x0A, 0x04, 0x00, 0x00, 0x0A];
/// Command: stop the timer.
pub const TIMER_STOP: [u8; 6] = [0x03, 0x0A, 0x05, 0x00, 0x00, 0x0D];
/// Command: reset the timer.
pub const TIMER_RESET: [u8; 6] = [0x03, 0x0A, 0x06, 0x00, 0x00, 0x0C];

/// Minimum length of a weight notification.
const WEIGHT_PACKET_LEN: usize = 10;

/// Decode a weight notification into grams.
///
/// Bytes 7–9 are a big-endian 24-bit magnitude in units of 0.01 g; byte 6 is
/// an ASCII sign byte (`'-'` marks a negative weight).
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

#[cfg(test)]
mod tests {
    use super::*;

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
}
