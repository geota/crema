//! Fixed-point number codecs for the DE1 BLE protocol.
//!
//! The DE1 firmware packs values in several fixed-point formats, named by the
//! convention `<sign><int-bits>P<frac-bits>`, where `P` marks the radix point.
//! Each `*_decode` turns a raw integer from a packet into an `f32`; each
//! `*_encode` does the reverse for packets the app sends.
//!
//! **Decoding is total** — every raw value maps to some `f32`.
//!
//! **Encoding clamps** its input to the format's representable range and
//! saturates, so the returned raw value always fits its integer type.
//!
//! ## Deliberate, audited deviation from the legacy Tcl
//!
//! The legacy encoders clamp the *input* to a round bound — `16` (`U8P4`),
//! `128` (`U8P1`), `256` (`U8P0` and `U16P8`), `65536` (`S32P16`) — each of
//! which, once scaled, lands one step past the target integer's maximum and
//! silently wraps to `0`. Crema instead clamps to the true representable
//! maximum ([`U8P4_MAX`] = `255/16`, [`U8P1_MAX`] = `255/2`, [`U8P0_MAX`],
//! [`U16P8_MAX`] = `65535/256`, [`S32P16_MAX`]/[`S32P16_MIN`]) and saturates.
//!
//! For every value that occurs in practice — pressures, flows and temperatures
//! well within range — the two produce **identical bytes**; they differ only
//! for out-of-range inputs the firmware never receives, where Crema saturates
//! rather than wrapping. (`F8_1_7` is unaffected: its `127` clamp matches the
//! legacy.) Verified against `binary.tcl` in the 2026-05-17 scalar audit.
//!
//! Encoders are needed by the write paths (RequestedState, profile upload) and
//! are included here to keep this primitive layer complete.

/// Largest value [`u8p4_encode`] can represent (`255 / 16`).
pub const U8P4_MAX: f32 = 255.0 / 16.0;
/// Largest value [`u8p1_encode`] can represent (`255 / 2`).
pub const U8P1_MAX: f32 = 255.0 / 2.0;
/// Largest value [`u8p0_encode`] can represent.
pub const U8P0_MAX: f32 = 255.0;
/// Largest value [`u16p8_encode`] can represent (`65535 / 256`).
pub const U16P8_MAX: f32 = 65535.0 / 256.0;
/// Largest value [`s32p16_encode`] can represent (`i32::MAX / 65536`).
pub const S32P16_MAX: f32 = i32::MAX as f32 / 65536.0;
/// Smallest value [`s32p16_encode`] can represent (`i32::MIN / 65536`).
pub const S32P16_MIN: f32 = i32::MIN as f32 / 65536.0;

// --- U8P4 — unsigned 4.4 fixed-point, one byte. Pressure/flow setpoints. ---

/// Decode `U8P4`: `value = raw / 16`.
pub fn u8p4_decode(raw: u8) -> f32 {
    raw as f32 / 16.0
}

/// Encode `U8P4`. Input is clamped to `0.0..=`[`U8P4_MAX`].
pub fn u8p4_encode(value: f32) -> u8 {
    (value.clamp(0.0, U8P4_MAX) * 16.0).round() as u8
}

// --- U8P1 — unsigned 7.1 fixed-point, one byte. Frame temperatures. ---

/// Decode `U8P1`: `value = raw / 2` (0.5-degree steps).
pub fn u8p1_decode(raw: u8) -> f32 {
    raw as f32 / 2.0
}

/// Encode `U8P1`. Input is clamped to `0.0..=`[`U8P1_MAX`].
pub fn u8p1_encode(value: f32) -> u8 {
    (value.clamp(0.0, U8P1_MAX) * 2.0).round() as u8
}

// --- U8P0 — unsigned 8-bit integer carried as a "fixed-point" value. ---

/// Decode `U8P0`: `value = raw`.
pub fn u8p0_decode(raw: u8) -> f32 {
    raw as f32
}

/// Encode `U8P0`. Input is clamped to `0.0..=`[`U8P0_MAX`].
pub fn u8p0_encode(value: f32) -> u8 {
    value.clamp(0.0, U8P0_MAX).round() as u8
}

// --- U16P8 — unsigned 8.8 fixed-point, two bytes big-endian. Temperatures. ---

/// Decode `U16P8`: `value = raw / 256`.
pub fn u16p8_decode(raw: u16) -> f32 {
    raw as f32 / 256.0
}

/// Encode `U16P8`. Input is clamped to `0.0..=`[`U16P8_MAX`].
pub fn u16p8_encode(value: f32) -> u16 {
    (value.clamp(0.0, U16P8_MAX) * 256.0).round() as u16
}

// --- S32P16 — signed 16.16 fixed-point, four bytes big-endian. Calibration. ---

/// Decode `S32P16`: `value = raw / 65536`.
pub fn s32p16_decode(raw: i32) -> f32 {
    raw as f32 / 65536.0
}

/// Encode `S32P16`. Input is clamped to [`S32P16_MIN`]`..=`[`S32P16_MAX`].
pub fn s32p16_encode(value: f32) -> i32 {
    (value.clamp(S32P16_MIN, S32P16_MAX) * 65536.0).round() as i32
}

// --- F8_1_7 — the DE1's custom one-byte "1/7-bit float". Frame length. ---

/// Decode `F8_1_7` (frame length, seconds). Bit 7 selects the regime:
/// clear → `raw / 10` (0.1 s steps, 0.0–12.7 s); set → `raw & 0x7F`
/// (whole-second steps, 0–127 s).
pub fn f8_1_7_decode(raw: u8) -> f32 {
    if raw & 0x80 == 0 {
        raw as f32 / 10.0
    } else {
        (raw & 0x7F) as f32
    }
}

/// Encode `F8_1_7`. Values `>= 12.75` use the whole-second regime and lose
/// sub-second precision (the round-trip is intentionally lossy there). Input is
/// clamped to `0.0..=127.0`.
pub fn f8_1_7_encode(value: f32) -> u8 {
    let v = value.clamp(0.0, 127.0);
    if v >= 12.75 {
        (v.round() as u8) | 0x80
    } else {
        (v * 10.0).round() as u8
    }
}

// --- U10P0 — a 10-bit value in a 16-bit field; bit 10 is an "enabled" flag. ---

/// Decode the value carried in a `U10P0` field (its bottom 10 bits, 0–1023).
pub fn u10p0_decode(raw: u16) -> u16 {
    raw & 0x03FF
}

/// Whether a `U10P0` field has its "enabled" flag (bit 10, `0x400`) set.
/// A field with the flag clear means "ignore this limit".
pub fn u10p0_enabled(raw: u16) -> bool {
    raw & 0x0400 != 0
}

/// Encode a `U10P0` value (0–1023) with the "enabled" flag set.
pub fn u10p0_encode(value: f32) -> u16 {
    (value.clamp(0.0, 1023.0).round() as u16 & 0x03FF) | 0x0400
}

// --- U24 — 24-bit values carried as three bytes (Tcl has no 24-bit int). ---

/// Decode `U24P16` (unsigned 8.16 fixed-point, 3 bytes hi→lo). The DE1's
/// `HeadTemp` in the ShotSample packet.
pub fn u24p16_decode(bytes: [u8; 3]) -> f32 {
    bytes[0] as f32 + bytes[1] as f32 / 256.0 + bytes[2] as f32 / 65536.0
}

/// Decode `U24P0` (unsigned 24-bit integer, 3 bytes big-endian). MMR addresses.
pub fn u24p0_decode(bytes: [u8; 3]) -> u32 {
    (bytes[0] as u32) << 16 | (bytes[1] as u32) << 8 | bytes[2] as u32
}

/// Encode a `U24P0` as 3 big-endian bytes; the top 8 bits of `value` are dropped.
pub fn u24p0_encode(value: u32) -> [u8; 3] {
    [(value >> 16) as u8, (value >> 8) as u8, value as u8]
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Assert two `f32`s are within a small tolerance, for non-exact ratios.
    fn close(a: f32, b: f32) {
        assert!((a - b).abs() < 1e-4, "{a} is not close to {b}");
    }

    mod u8p4 {
        use super::*;

        #[test]
        fn decode_divides_raw_by_sixteen() {
            assert_eq!(u8p4_decode(0x60), 6.0);
            assert_eq!(u8p4_decode(0xFF), 15.9375);
        }

        #[test]
        fn encode_multiplies_value_by_sixteen() {
            assert_eq!(u8p4_encode(6.0), 0x60);
            assert_eq!(u8p4_encode(0.0), 0);
        }

        #[test]
        fn encode_saturates_above_representable_max() {
            // The legacy Tcl wraps here; we saturate at the byte maximum.
            assert_eq!(u8p4_encode(16.0), 255);
            assert_eq!(u8p4_encode(99.0), 255);
        }

        #[test]
        fn encode_clamps_negative_input_to_zero() {
            assert_eq!(u8p4_encode(-1.0), 0);
        }
    }

    mod u8p1 {
        use super::*;

        #[test]
        fn decode_divides_raw_by_two() {
            assert_eq!(u8p1_decode(184), 92.0);
        }

        #[test]
        fn encode_multiplies_value_by_two() {
            assert_eq!(u8p1_encode(92.0), 184);
        }

        #[test]
        fn encode_saturates_above_representable_max() {
            assert_eq!(u8p1_encode(200.0), 255);
        }
    }

    mod u8p0 {
        use super::*;

        #[test]
        fn decode_returns_raw_as_float() {
            assert_eq!(u8p0_decode(150), 150.0);
        }

        #[test]
        fn encode_rounds_to_nearest_integer() {
            assert_eq!(u8p0_encode(150.4), 150);
        }

        #[test]
        fn encode_saturates_above_max() {
            assert_eq!(u8p0_encode(300.0), 255);
        }
    }

    mod u16p8 {
        use super::*;

        #[test]
        fn decode_divides_raw_by_256() {
            assert_eq!(u16p8_decode(23808), 93.0);
        }

        #[test]
        fn encode_multiplies_value_by_256() {
            assert_eq!(u16p8_encode(93.0), 23808);
        }

        #[test]
        fn encode_saturates_above_max() {
            assert_eq!(u16p8_encode(999.0), 65535);
        }
    }

    mod s32p16 {
        use super::*;

        #[test]
        fn decode_handles_negative_values() {
            assert_eq!(s32p16_decode(65536), 1.0);
            assert_eq!(s32p16_decode(-65536), -1.0);
        }

        #[test]
        fn encode_handles_negative_values() {
            assert_eq!(s32p16_encode(1.5), 98304);
            assert_eq!(s32p16_encode(-1.5), -98304);
        }
    }

    mod f8_1_7 {
        use super::*;

        #[test]
        fn decode_low_regime_is_tenths_of_a_second() {
            assert_eq!(f8_1_7_decode(0x00), 0.0);
            assert_eq!(f8_1_7_decode(0x05), 0.5);
            close(f8_1_7_decode(0x7F), 12.7);
        }

        #[test]
        fn decode_high_regime_is_whole_seconds_and_masks_flag_bit() {
            assert_eq!(f8_1_7_decode(0x80), 0.0);
            assert_eq!(f8_1_7_decode(0x8D), 13.0);
            assert_eq!(f8_1_7_decode(0xFF), 127.0);
        }

        #[test]
        fn encode_low_regime_keeps_tenths() {
            assert_eq!(f8_1_7_encode(0.0), 0x00);
            assert_eq!(f8_1_7_encode(0.5), 0x05);
            assert_eq!(f8_1_7_encode(12.7), 0x7F);
        }

        #[test]
        fn encode_at_or_above_12_75_uses_whole_second_regime() {
            assert_eq!(f8_1_7_encode(12.75), 0x8D);
            assert_eq!(f8_1_7_encode(13.0), 0x8D);
            assert_eq!(f8_1_7_encode(127.0), 0xFF);
        }

        #[test]
        fn high_regime_round_trip_is_lossy_by_design() {
            assert_eq!(f8_1_7_decode(f8_1_7_encode(12.75)), 13.0);
        }
    }

    mod u10p0 {
        use super::*;

        #[test]
        fn decode_returns_bottom_ten_bits() {
            assert_eq!(u10p0_decode(0x0464), 100);
        }

        #[test]
        fn enabled_flag_reflects_bit_ten() {
            assert!(u10p0_enabled(0x0464));
            assert!(!u10p0_enabled(0x0064));
        }

        #[test]
        fn encode_sets_the_enabled_flag() {
            assert_eq!(u10p0_encode(100.0), 0x0464);
            assert!(u10p0_enabled(u10p0_encode(100.0)));
        }
    }

    mod u24 {
        use super::*;

        #[test]
        fn u24p16_combines_three_bytes_as_fixed_point() {
            // 92 + 128/256 + 0/65536 = 92.5
            assert_eq!(u24p16_decode([92, 128, 0]), 92.5);
        }

        #[test]
        fn u24p0_round_trips_a_big_endian_integer() {
            assert_eq!(u24p0_decode([0x12, 0x34, 0x56]), 0x0012_3456);
            assert_eq!(u24p0_encode(0x0012_3456), [0x12, 0x34, 0x56]);
        }
    }
}
