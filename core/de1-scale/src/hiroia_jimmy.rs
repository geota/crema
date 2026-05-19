//! Hiroia Jimmy BLE codec (`scale_type` `hiroiajimmy`).
//!
//! See `docs/06-scale-protocols.md` §12.
//!
//! **Caution — unverified.** The legacy parser never validates the 4-byte
//! header, so any status notification is treated as weight. Header semantics
//! should be confirmed against real hardware.

// Raw integer weight fields are decoded into `f32` grams; precision loss past
// 2^23 is inherent to representing a wire reading as the codec's `f32` weight,
// not a defect, so the precision-loss lint is allowed module-wide here.
#![allow(clippy::cast_precision_loss)]

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
    fn decodes_a_zero_weight() {
        assert_eq!(parse_weight(&[0, 0, 0, 0, 0x00, 0x00, 0x00]), Some(0.0));
    }

    #[test]
    fn treats_the_value_just_below_the_sign_threshold_as_positive() {
        // 0x7FFFFF is the largest value below 0x800000 -> positive.
        assert_eq!(
            parse_weight(&[0, 0, 0, 0, 0xFF, 0xFF, 0x7F]),
            Some(0x7F_FFFF as f32 / 10.0)
        );
    }

    #[test]
    fn treats_the_value_at_the_sign_threshold_as_negative() {
        // 0x800000 is the first value the codec reads as negative; the legacy
        // formula `-(0xFFFFFF - raw)` makes it -0x7FFFFF counts.
        assert_eq!(
            parse_weight(&[0, 0, 0, 0, 0x00, 0x00, 0x80]),
            Some(-(0x7F_FFFF as f32) / 10.0)
        );
    }

    /// The parser performs no header validation, so a non-weight status
    /// notification is (incorrectly) decoded as weight — documented in the
    /// module-level caution. This test pins that known behaviour.
    #[test]
    fn does_not_validate_the_header() {
        // An arbitrary 4-byte header is ignored; bytes 4–6 still decode.
        assert_eq!(
            parse_weight(&[0xDE, 0xAD, 0xBE, 0xEF, 0xB4, 0x00, 0x00]),
            Some(18.0)
        );
    }

    #[test]
    fn accepts_a_packet_at_the_exact_minimum_length() {
        // Exactly seven bytes is the boundary — six bytes is rejected below.
        assert_eq!(parse_weight(&[0, 0, 0, 0, 0xB4, 0x00, 0x00]), Some(18.0));
    }

    #[test]
    fn rejects_a_short_packet() {
        assert_eq!(parse_weight(&[0, 0, 0, 0, 0, 0]), None);
    }
}
