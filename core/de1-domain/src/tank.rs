//! Water-tank geometry — translating the DE1's sensor depth (mm) to the
//! tank's water volume (ml).
//!
//! The DE1's `WaterLevels` characteristic reports the sensor depth in
//! millimetres. The tank is not a straight cylinder, so a lookup table is
//! required to convert that depth to a volume. The table is the legacy
//! de1app's `water_tank_level_to_milliliters` (`de1plus/vars.tcl`),
//! identical to the DSx skin's, ported verbatim — every shell consumes the
//! same calibration here instead of carrying its own copy.

/// The DE1 tank-level → water-volume lookup table.
///
/// Index 0..67 maps to 0..2058 ml — one entry per integer mm of sensor
/// depth. Ported verbatim from the de1app's
/// `water_tank_level_to_milliliters` (`de1plus/vars.tcl`); the DSx skin
/// uses the same table.
pub const TANK_MM_TO_ML: [u16; 68] = [
    0, 16, 43, 70, 97, 124, 151, 179, 206, 233, 261, 288, 316, 343, 371, 398, 426, 453, 481, 509,
    537, 564, 592, 620, 648, 676, 704, 732, 760, 788, 816, 844, 872, 900, 929, 957, 985, 1013,
    1042, 1070, 1104, 1138, 1172, 1207, 1242, 1277, 1312, 1347, 1382, 1417, 1453, 1488, 1523, 1559,
    1594, 1630, 1665, 1701, 1736, 1772, 1808, 1843, 1879, 1915, 1951, 1986, 2022, 2058,
];

/// Convert a DE1 tank-level reading (mm of sensor depth) to the tank's
/// water volume in ml via [`TANK_MM_TO_ML`].
///
/// The depth is clamped to the table's range — a depth past the top reads
/// as the 2058 ml full ceiling, a negative depth (a sensor glitch) as the
/// 0 ml empty floor — matching the de1app / DSx behaviour. The mm reading
/// is truncated to an integer index (the DE1 reports sub-millimetre noise
/// at idle that would otherwise jitter the readout).
pub fn water_tank_ml(mm: f32) -> u16 {
    if !mm.is_finite() {
        return 0;
    }
    // Truncation policy: the table has 68 entries, so clamping to `[0,
    // 67]` first removes any precision loss in the cast — the f32 fits
    // in u8 by that point. `mm < 0.0` and `mm > 67.0` saturate to the
    // table's endpoints, matching the legacy de1app behaviour.
    if mm < 0.0 {
        return TANK_MM_TO_ML[0];
    }
    let truncated = mm.trunc();
    // 67.0 = `TANK_MM_TO_ML.len() - 1`. Hardcoded as a literal so clippy
    // doesn't trip on a `usize` → `f32` cast for the comparison (68 fits
    // in f32 exactly; the const assert below catches a mismatched table
    // length at compile time).
    const MAX_INDEX_F32: f32 = 67.0;
    const _: () = assert!(TANK_MM_TO_ML.len() == 68);
    if truncated >= MAX_INDEX_F32 {
        return TANK_MM_TO_ML[TANK_MM_TO_ML.len() - 1];
    }
    // `0.0 <= truncated < 67.0`, so the cast is lossless.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    let idx = truncated as usize;
    TANK_MM_TO_ML[idx]
}

/// The "full" fill of the DE1 tank, ml — the denominator for the percent
/// readout. 1104 ml = the geometry table at 40 mm, de1app's documented
/// `water_level_full_point` (`machine.tcl`), and the same denominator
/// Decenza's percent readout uses — so all the ecosystem's percent
/// readouts agree. The table extends past it (2058 ml at the sensor
/// ceiling) because the tank can be overfilled; anything at or above the
/// full point clamps to 100.
pub const TANK_FULL_ML: u16 = 1104;

/// Convert a DE1 tank-level reading (mm of sensor depth) to a whole
/// percentage of a typical full fill ([`TANK_FULL_ML`]), clamped to
/// `0..=100`.
pub fn water_tank_percent(mm: f32) -> u8 {
    let ml = f32::from(water_tank_ml(mm));
    let pct = (ml / f32::from(TANK_FULL_ML) * 100.0).round();
    // `water_tank_ml` is finite and >= 0, so only the top needs clamping.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    let pct = pct.min(100.0) as u8;
    pct
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_legacy_endpoints() {
        // First and last table entries — the clamped ends.
        assert_eq!(water_tank_ml(0.0), 0);
        assert_eq!(water_tank_ml(67.0), 2058);
    }

    #[test]
    fn clamps_out_of_range() {
        assert_eq!(water_tank_ml(-1.0), 0);
        assert_eq!(water_tank_ml(1000.0), 2058);
    }

    #[test]
    fn truncates_sub_millimetre_noise() {
        // 5.7 mm truncates to 5 → 124 ml (the table at index 5).
        assert_eq!(water_tank_ml(5.7), TANK_MM_TO_ML[5]);
        assert_eq!(water_tank_ml(5.0), TANK_MM_TO_ML[5]);
    }

    #[test]
    fn nan_yields_empty() {
        assert_eq!(water_tank_ml(f32::NAN), 0);
        assert_eq!(water_tank_ml(f32::INFINITY), 0);
    }

    #[test]
    fn percent_of_full_point() {
        assert_eq!(water_tank_percent(0.0), 0);
        // 36 mm → 985 ml → 89 % of the 1104 ml full point.
        assert_eq!(water_tank_percent(36.0), 89);
        // The de1app full point itself: 40 mm → 1104 ml → exactly 100 %.
        assert_eq!(water_tank_percent(40.0), 100);
        // Overfilled past the full point (table ceiling 2058 ml) — clamped.
        assert_eq!(water_tank_percent(67.0), 100);
        assert_eq!(water_tank_percent(1000.0), 100);
        assert_eq!(water_tank_percent(f32::NAN), 0);
    }
}
