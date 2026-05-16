//! Hiroia Jimmy BLE codec (`scale_type` `hiroiajimmy`).
//!
//! See `docs/06-scale-protocols.md` §12.
//!
//! **Caution — unverified.** The legacy parser never validates the 4-byte
//! header, so any status notification is treated as weight. Header semantics
//! should be confirmed against real hardware.

/// GATT service UUID.
pub const SERVICE_UUID: &str = "06c31822-8682-4744-9211-febc93e3bece";
/// Characteristic the scale notifies weight on.
pub const STATUS_UUID: &str = "06c31824-8682-4744-9211-febc93e3bece";
/// Characteristic commands are written to.
pub const COMMAND_UUID: &str = "06c31823-8682-4744-9211-febc93e3bece";

/// Command: tare. (The only command Hiroia Jimmy supports — no timer.)
pub const TARE: [u8; 2] = [0x07, 0x00];

/// Decode a weight notification into grams.
///
/// Bytes 4–6 are a 24-bit little-endian value; values `>= 0x800000` are a
/// negative weight. The sign correction reproduces the legacy app's formula
/// exactly (`raw - 0xFFFFFF`), which differs by one count from textbook 24-bit
/// two's complement — kept as-is pending hardware verification.
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    if data.len() < 7 {
        return None;
    }
    let raw = u32::from(data[4]) | (u32::from(data[5]) << 8) | (u32::from(data[6]) << 16);
    let weight: i32 = if raw >= 0x80_0000 {
        -((0xFF_FFFF - raw) as i32)
    } else {
        raw as i32
    };
    Some(weight as f32 / 10.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_a_positive_weight() {
        // Bytes 4–6 = 0xB4 0x00 0x00 -> 180 -> 18.0 g.
        assert_eq!(parse_weight(&[0, 0, 0, 0, 0xB4, 0x00, 0x00]), Some(18.0));
    }

    #[test]
    fn decodes_a_negative_weight() {
        // 0xFFFF38 -> -(0xFFFFFF - 0xFFFF38) = -199 -> -19.9 g.
        assert_eq!(parse_weight(&[0, 0, 0, 0, 0x38, 0xFF, 0xFF]), Some(-19.9));
    }

    #[test]
    fn rejects_a_short_packet() {
        assert_eq!(parse_weight(&[0, 0, 0, 0, 0, 0]), None);
    }
}
