//! Skale II (Atomax) BLE codec (`scale_type` `atomaxskale`).
//!
//! Weight notifications arrive on [`WEIGHT_NOTIFY_UUID`]; single-byte commands
//! are written to [`COMMAND_UUID`].

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

/// Decode a weight notification (characteristic `EF81`) into grams.
///
/// The length selects the layout (decaid `skale2_scale.dart`
/// `_parseWeightNotification`, abd9b73e / 720e22c6 / 4fdf986b):
///
/// - **5 bytes** — the Atomax SDK frame: a flag byte, a signed 24-bit
///   little-endian mantissa, and a signed base-10 exponent; grams =
///   `mantissa × 10^exponent`.
/// - **9 bytes** — R029-compatible firmware: the 5-byte frame followed by a
///   second weight block, which is ignored.
/// - **4 bytes** — the legacy frame: bytes 0-3 as a little-endian `i32`
///   ÷ 2560 (byte 0 is a sub-0.1 g fraction).
///
/// Any other length is not a weight frame. The old decoder here read bytes
/// 1-2 as an `i16` ÷ 10 from any frame of 3+ bytes, which is only right for
/// a 5-byte frame whose exponent is −1: a scale reporting at another
/// exponent (`… 00 FE` = hundredths, `… 00 01` = tens) was off by 10× or
/// worse.
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    let grams = match data.len() {
        5 | 9 => {
            // Sign-extend the 24-bit mantissa through an i32.
            let mantissa = i32::from_le_bytes([data[1], data[2], data[3], 0]) << 8 >> 8;
            let exponent = i32::from(data[4].cast_signed());
            f64::from(mantissa) * 10f64.powi(exponent)
        }
        4 => f64::from(i32::from_le_bytes([data[0], data[1], data[2], data[3]])) / 2560.0,
        _ => return None,
    };
    #[allow(clippy::cast_possible_truncation)]
    Some(grams as f32)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn close(got: Option<f32>, want: f32) -> bool {
        got.is_some_and(|g| (g - want).abs() < 0.001)
    }

    #[test]
    fn decodes_five_byte_mantissa_exponent_frames() {
        // decaid skale2_scale_test.dart fixtures.
        // 1234 × 10^-2 = 12.34 g.
        assert!(close(parse_weight(&[0x00, 0xD2, 0x04, 0x00, 0xFE]), 12.34));
        // -567 × 10^-1 = -56.7 g (24-bit sign extension).
        assert!(close(parse_weight(&[0x00, 0xC9, 0xFD, 0xFF, 0xFF]), -56.7));
        // 123 × 10^1 = 1230 g.
        assert!(close(parse_weight(&[0x00, 0x7B, 0x00, 0x00, 0x01]), 1230.0));
        // 1000 × 10^-1 = 100.0 g — the only case the old i16 ÷ 10 got right.
        assert!(close(parse_weight(&[0x00, 0xE8, 0x03, 0x00, 0xFF]), 100.0));
    }

    #[test]
    fn decodes_the_first_block_of_a_nine_byte_frame() {
        // decaid "uses first weight block from nine-byte notification".
        assert!(close(
            parse_weight(&[0x00, 0xE8, 0x03, 0x00, 0xFF, 0xD2, 0x04, 0x00, 0xFE]),
            100.0
        ));
    }

    #[test]
    fn decodes_the_legacy_four_byte_frame() {
        // decaid "parses legacy four-byte frame (00 0A 00 00 -> 1.0)".
        assert!(close(parse_weight(&[0x00, 0x0A, 0x00, 0x00]), 1.0));
        assert!(close(parse_weight(&[0x00, 0xE8, 0x03, 0x00]), 100.0));
        // Signed: 0xFFFFF600 / 2560 = -1.0.
        assert!(close(parse_weight(&[0x00, 0xF6, 0xFF, 0xFF]), -1.0));
    }

    #[test]
    fn ignores_other_lengths() {
        // decaid: a truncated 3-byte frame and a 6-byte frame emit nothing.
        assert_eq!(parse_weight(&[0x00, 0xD2, 0x04]), None);
        assert_eq!(parse_weight(&[0x00, 0xE8, 0x03, 0x00, 0xFF, 0x00]), None);
        for len in [0usize, 1, 2, 7, 8, 10] {
            assert_eq!(parse_weight(&vec![0u8; len]), None, "len {len}");
        }
    }
}
