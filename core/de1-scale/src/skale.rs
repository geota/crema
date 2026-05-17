//! Skale II (Atomax) BLE codec (`scale_type` `atomaxskale`).
//!
//! Weight notifications arrive on [`WEIGHT_NOTIFY_UUID`]; single-byte commands
//! are written to [`COMMAND_UUID`]. See `docs/02-ble-protocol.md` §8.2.

/// GATT service UUID.
pub const SERVICE_UUID: &str = "0000ff08-0000-1000-8000-00805f9b34fb";
/// Characteristic single-byte commands are written to.
pub const COMMAND_UUID: &str = "0000ef80-0000-1000-8000-00805f9b34fb";
/// Characteristic the scale notifies weight on.
pub const WEIGHT_NOTIFY_UUID: &str = "0000ef81-0000-1000-8000-00805f9b34fb";
/// Characteristic the scale notifies button presses on.
pub const BUTTON_NOTIFY_UUID: &str = "0000ef82-0000-1000-8000-00805f9b34fb";

/// Command byte: switch the scale into grams mode.
pub const CMD_ENABLE_GRAMS: u8 = 0x03;
/// Command byte: show the current weight on the LCD.
pub const CMD_DISPLAY_WEIGHT: u8 = 0xEC;
/// Command byte: tare.
pub const CMD_TARE: u8 = 0x10;
/// Command byte: start the timer.
pub const CMD_TIMER_START: u8 = 0xDD;
/// Command byte: reset the timer.
pub const CMD_TIMER_RESET: u8 = 0xD0;
/// Command byte: stop the timer.
pub const CMD_TIMER_STOP: u8 = 0xD1;
/// Command byte: turn the screen on.
pub const CMD_SCREEN_ON: u8 = 0xED;
/// Command byte: turn the screen off.
pub const CMD_SCREEN_OFF: u8 = 0xEE;

/// Minimum length of a weight notification.
const WEIGHT_PACKET_LEN: usize = 3;

/// Decode a weight notification (characteristic `EF81`) into grams.
///
/// The Skale's weight is a **little-endian** signed 16-bit value in units of
/// 0.1 g — note this differs from the DE1 and the Decent Scale, which are
/// big-endian.
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    if data.len() < WEIGHT_PACKET_LEN {
        return None;
    }
    // Byte 0 is a marker; bytes 1–2 are the little-endian signed weight.
    let raw = i16::from_le_bytes([data[1], data[2]]);
    Some(f32::from(raw) / 10.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_a_little_endian_weight_to_grams() {
        // Bytes 1–2 = 0xC8 0x00 -> little-endian 200 -> 20.0 g.
        assert_eq!(parse_weight(&[0x10, 0xC8, 0x00]), Some(20.0));
    }

    #[test]
    fn decodes_a_negative_weight() {
        // 0x38 0xFF -> little-endian 0xFF38 = -200 -> -20.0 g.
        assert_eq!(parse_weight(&[0x10, 0x38, 0xFF]), Some(-20.0));
    }

    #[test]
    fn decodes_a_zero_weight() {
        assert_eq!(parse_weight(&[0x10, 0x00, 0x00]), Some(0.0));
    }

    #[test]
    fn decodes_the_signed_extremes() {
        // 0xFF 0x7F -> little-endian 0x7FFF = i16::MAX -> 3276.7 g.
        assert_eq!(parse_weight(&[0x10, 0xFF, 0x7F]), Some(3276.7));
        // 0x00 0x80 -> little-endian 0x8000 = i16::MIN -> -3276.8 g.
        assert_eq!(parse_weight(&[0x10, 0x00, 0x80]), Some(-3276.8));
    }

    #[test]
    fn ignores_the_marker_byte() {
        // Byte 0 is only a marker; any value decodes the same weight.
        assert_eq!(parse_weight(&[0x00, 0xC8, 0x00]), Some(20.0));
        assert_eq!(parse_weight(&[0xFF, 0xC8, 0x00]), Some(20.0));
    }

    #[test]
    fn accepts_a_packet_at_the_exact_minimum_length() {
        // Exactly three bytes is the boundary — two bytes is rejected below.
        assert_eq!(parse_weight(&[0x10, 0xC8, 0x00]), Some(20.0));
    }

    #[test]
    fn rejects_a_short_packet() {
        assert_eq!(parse_weight(&[0x10, 0xC8]), None);
    }
}
