//! Smartchef BLE codec (`scale_type` `smartchef`).
//!
//! Smartchef exposes **no BLE commands** — there is no hardware tare command
//! and no timer. The unified [`crate::Scale`] surface still supports tare on
//! this scale via a **software-tare offset**: the most recent raw reading is
//! recorded at the moment the user presses Tare, and every later reading is
//! reported with that offset subtracted.
//!
//! See `docs/06-scale-protocols.md` §10.
//!
//! **Defer to reaprime.** Legacy de1app comments out its `smartchef_tare`
//! proc and pops a popup telling the user to press the physical button
//! (`de1plus/bluetooth.tcl:1429-1443`); reaprime implements software tare
//! via `_weightAtTare` (`reaprime/lib/src/models/device/impl/smartchef/
//! smartchef_scale.dart:91-95, 130`). Crema adopts reaprime's behaviour —
//! the user gets a tared reading even though the scale isn't aware.

/// GATT service UUID (the generic `FFF0` service).
pub const SERVICE_UUID: &str = "0000fff0-0000-1000-8000-00805f9b34fb";
/// Characteristic the scale notifies weight on.
pub const STATUS_UUID: &str = "0000fff1-0000-1000-8000-00805f9b34fb";
/// Characteristic for command writes (unused — Smartchef has no commands).
pub const COMMAND_UUID: &str = "0000fff2-0000-1000-8000-00805f9b34fb";

/// Decode a weight notification into grams.
///
/// Bytes 5–6 are an unsigned big-endian 16-bit value in units of 0.1 g; the
/// weight is negative when byte 3 exceeds 10.
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    if data.len() < 7 {
        return None;
    }
    let weight = u16::from_be_bytes([data[5], data[6]]);
    let grams = f32::from(weight) / 10.0;
    Some(if data[3] > 10 { -grams } else { grams })
}

/// Smartchef-specific state the [`crate::Scale`] wrapper carries.
///
/// The Smartchef firmware does not accept a tare command; this struct records
/// the most recent raw reading and reports `raw - weight_at_tare` on every
/// notification — see [`apply_offset`](Self::apply_offset).
///
/// Parallels the [`crate::decent_scale::DecentScale`] capability struct
/// established by PR-F: a small per-scale state owner kept inside the
/// `Scale::Inner` variant.
#[derive(Debug, Clone, Default)]
pub struct SmartchefScale {
    /// The raw weight at the most recent [`tare`](Self::tare) call. `0.0`
    /// before the first tare — matches reaprime's `_weightAtTare = 0.0`
    /// default (`smartchef_scale.dart:23`).
    weight_at_tare: f32,
    /// The raw weight from the most recent notification — recorded so a
    /// subsequent `tare()` knows what offset to apply.
    last_raw_weight: f32,
}

impl SmartchefScale {
    /// Construct fresh state for a newly-connected Smartchef.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Record the latest raw reading from a weight notification.
    ///
    /// Called by [`crate::Scale::parse_weight`] / `parse_reading` before the
    /// caller sees the value. The recorded raw weight is what a subsequent
    /// [`tare`](Self::tare) call will use as the offset.
    pub fn record_raw_weight(&mut self, raw: f32) {
        self.last_raw_weight = raw;
    }

    /// Capture the current raw weight as the new software-tare offset.
    ///
    /// Reaprime's `tare()` (`smartchef_scale.dart:91-95`) sets
    /// `_weightAtTare = _lastRawWeight`. The next notification will report
    /// `raw - weight_at_tare`, so the user-visible weight reads `~0 g`.
    pub fn tare(&mut self) {
        self.weight_at_tare = self.last_raw_weight;
    }

    /// Apply the current software-tare offset to a raw weight.
    ///
    /// Equivalent to `raw - weight_at_tare`; the user-visible reading after
    /// a recent tare hovers around zero until something is placed on the
    /// scale.
    #[must_use]
    pub fn apply_offset(&self, raw: f32) -> f32 {
        raw - self.weight_at_tare
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_big_endian_weight_to_grams() {
        // Bytes 5–6 = 0x00 0xB4 -> 180 -> 18.0 g.
        assert_eq!(parse_weight(&[0, 0, 0, 0, 0, 0x00, 0xB4]), Some(18.0));
    }

    #[test]
    fn treats_a_high_byte_three_as_negative() {
        assert_eq!(parse_weight(&[0, 0, 0, 11, 0, 0x00, 0xB4]), Some(-18.0));
    }

    #[test]
    fn treats_byte_three_at_the_threshold_as_positive() {
        // The sign rule is `data[3] > 10`: a value of exactly 10 is positive.
        assert_eq!(parse_weight(&[0, 0, 0, 10, 0, 0x00, 0xB4]), Some(18.0));
    }

    #[test]
    fn decodes_a_zero_weight() {
        assert_eq!(parse_weight(&[0, 0, 0, 0, 0, 0x00, 0x00]), Some(0.0));
    }

    #[test]
    fn decodes_the_maximum_unsigned_magnitude() {
        // Bytes 5–6 are an unsigned big-endian u16: 0xFFFF = 65535 -> 6553.5 g.
        assert_eq!(parse_weight(&[0, 0, 0, 0, 0, 0xFF, 0xFF]), Some(6553.5));
        // Same magnitude with the sign flag set negates it.
        assert_eq!(parse_weight(&[0, 0, 0, 11, 0, 0xFF, 0xFF]), Some(-6553.5));
    }

    #[test]
    fn accepts_a_packet_at_the_exact_minimum_length() {
        // Exactly seven bytes is the boundary — six bytes is rejected below.
        assert_eq!(parse_weight(&[0, 0, 0, 0, 0, 0x00, 0xB4]), Some(18.0));
    }

    #[test]
    fn rejects_a_short_packet() {
        assert_eq!(parse_weight(&[0, 0, 0, 0, 0, 0]), None);
    }

    #[test]
    fn software_tare_zeroes_the_most_recent_raw_weight() {
        let mut state = SmartchefScale::new();
        state.record_raw_weight(42.0);
        state.tare();
        assert_eq!(state.apply_offset(42.0), 0.0);
    }

    #[test]
    fn software_tare_offsets_later_readings() {
        let mut state = SmartchefScale::new();
        state.record_raw_weight(100.0);
        state.tare();
        // A later reading of 105 g shows up as 5 g.
        assert_eq!(state.apply_offset(105.0), 5.0);
    }

    #[test]
    fn no_tare_means_no_offset_is_applied() {
        let state = SmartchefScale::new();
        assert_eq!(state.apply_offset(42.5), 42.5);
    }

    #[test]
    fn a_second_tare_uses_the_latest_raw_weight() {
        let mut state = SmartchefScale::new();
        state.record_raw_weight(10.0);
        state.tare();
        state.record_raw_weight(25.0);
        state.tare();
        // After the second tare, what reads 25 should show as 0.
        assert_eq!(state.apply_offset(25.0), 0.0);
        assert_eq!(state.apply_offset(30.0), 5.0);
    }
}
