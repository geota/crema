//! Difluid Microbalance BLE codec (`scale_type` `difluid`).
//!
//! See `docs/06-scale-protocols.md` §9.
//!
//! **Caution — unverified.** The legacy Tcl parser is hex-string based and the
//! byte offset (bytes 5–8) and lack of sign handling were flagged as needing
//! confirmation against real hardware. This module follows the documented
//! behaviour (big-endian `u32`, no sign) but should be checked against a
//! physical scale before being relied upon.

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
    fn rejects_a_short_packet() {
        assert_eq!(parse_weight(&[0u8; 18]), None);
    }
}
