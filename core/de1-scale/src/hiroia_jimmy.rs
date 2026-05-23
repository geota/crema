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

/// Command: tare. (The only stateful command Hiroia Jimmy supports — no timer.)
pub const TARE: [u8; 2] = [0x07, 0x00];

/// Command: toggle the on-scale display unit (grams ↔ oz / ml).
///
/// **Defer to reaprime.** The Hiroia firmware does not accept a "set unit
/// directly to grams" command; the only available control is a
/// toggle-to-next-unit. Reaprime's `_sendToggleUnit()` ships this
/// `[0x0B, 0x00]` byte sequence and fires it from the weight-notification
/// path whenever the first byte (`mode`) exceeds `0x08`, which signals the
/// scale has booted in a non-grams unit
/// (`reaprime/lib/src/models/device/impl/hiroia/hiroia_scale.dart:120-128`).
/// Legacy de1app does not expose this command.
pub const TOGGLE_UNIT: [u8; 2] = [0x0B, 0x00];

/// Whether the Hiroia is currently displaying a non-grams unit.
///
/// Byte `[0]` of a weight notification is the mode byte. Reaprime treats
/// `mode > 0x08` as "scale is in oz / ml" and the auto-recovery path fires
/// [`TOGGLE_UNIT`] to cycle back toward grams
/// (`hiroia_scale.dart:131-139`). Returns `None` for a packet too short
/// to carry the mode byte.
#[must_use]
pub fn is_non_grams_mode(data: &[u8]) -> Option<bool> {
    if data.is_empty() {
        return None;
    }
    Some(data[0] > 0x08)
}

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

    #[test]
    fn toggle_unit_carries_the_documented_bytes() {
        // Reaprime hiroia_scale.dart:120-128 — `[0x0b, 0x00]`.
        assert_eq!(TOGGLE_UNIT, [0x0B, 0x00]);
    }

    #[test]
    fn is_non_grams_mode_true_when_mode_byte_above_8() {
        // Reaprime's `mode > 0x08` heuristic — the scale has booted into a
        // non-grams unit (oz / ml).
        assert_eq!(is_non_grams_mode(&[0x09, 0, 0, 0, 0, 0, 0]), Some(true));
        assert_eq!(is_non_grams_mode(&[0xFF, 0, 0, 0, 0, 0, 0]), Some(true));
    }

    #[test]
    fn is_non_grams_mode_false_when_mode_byte_in_grams_range() {
        assert_eq!(is_non_grams_mode(&[0x00, 0, 0, 0, 0, 0, 0]), Some(false));
        assert_eq!(is_non_grams_mode(&[0x08, 0, 0, 0, 0, 0, 0]), Some(false));
    }

    #[test]
    fn is_non_grams_mode_rejects_empty_data() {
        assert_eq!(is_non_grams_mode(&[]), None);
    }
}
