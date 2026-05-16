//! Atomheart Eclair BLE codec (`scale_type` `atomheart_eclair`).
//!
//! See `docs/06-scale-protocols.md` §7.

/// GATT service UUID.
pub const SERVICE_UUID: &str = "b905eaea-2e63-0e04-7582-7913f10d8f81";
/// Characteristic the scale notifies weight on.
pub const NOTIFY_UUID: &str = "ad736c5f-bbc9-1f96-d304-cb5d5f41e160";
/// Characteristic commands are written to.
pub const COMMAND_UUID: &str = "4f9a45ba-8e1b-4e07-e157-0814d393b968";

/// Command: tare (also resets the timer).
pub const TARE: [u8; 3] = [0x54, 0x01, 0x01];
/// Command: start the timer.
pub const TIMER_START: [u8; 3] = [0x43, 0x01, 0x01];
/// Command: stop the timer.
pub const TIMER_STOP: [u8; 3] = [0x43, 0x00, 0x00];

/// Decode a weight notification into grams.
///
/// Byte 0 must be `'W'`. Bytes 1–4 are a signed little-endian 32-bit value in
/// milligrams; byte 9 is an XOR checksum over bytes 1–8 — a frame that fails
/// the checksum is rejected.
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    if data.len() < 10 || data[0] != b'W' {
        return None;
    }
    let checksum = data[1..9].iter().fold(0u8, |acc, &b| acc ^ b);
    if checksum != data[9] {
        return None;
    }
    let milligrams = i32::from_le_bytes([data[1], data[2], data[3], data[4]]);
    Some(milligrams as f32 / 1000.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A weight frame for 18000 mg (18.0 g) with a correct XOR checksum.
    fn frame() -> [u8; 10] {
        let mut f = [b'W', 0x50, 0x46, 0x00, 0x00, 0, 0, 0, 0, 0];
        f[9] = f[1..9].iter().fold(0u8, |acc, &b| acc ^ b);
        f
    }

    #[test]
    fn decodes_milligrams_to_grams() {
        assert_eq!(parse_weight(&frame()), Some(18.0));
    }

    #[test]
    fn rejects_a_frame_with_a_bad_checksum() {
        let mut bad = frame();
        bad[9] ^= 0xFF;
        assert_eq!(parse_weight(&bad), None);
    }

    #[test]
    fn rejects_a_frame_without_the_w_header() {
        let mut bad = frame();
        bad[0] = b'X';
        assert_eq!(parse_weight(&bad), None);
    }
}
