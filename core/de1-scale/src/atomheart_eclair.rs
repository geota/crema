//! Atomheart Eclair BLE codec (`scale_type` `atomheart_eclair`).

// Raw integer weight fields are decoded into `f32` grams; precision loss past
// 2^23 is inherent to representing a wire reading as the codec's `f32` weight,
// not a defect, so the precision-loss lint is allowed module-wide here.
#![allow(clippy::cast_precision_loss)]

use std::time::Duration;

/// GATT service UUID.
///
/// **PR G — adopted reaprime's UUID.** Legacy de1app declares
/// `B905EAEA-2E63-0E04-7582-7913F10D8F81` (`de1plus/machine.tcl:93`);
/// reaprime declares `b905eaea-6c7e-4f73-b43d-2cdfcab29570`
/// (`reaprime/lib/src/models/device/impl/atomheart/atomheart_scale.dart:19`).
/// Both share the same first 32 bits (`b905eaea`) so vendor identity is
/// consistent — one is wrong on the lower 96 bits. Per PR G's "defer to
/// reaprime when the two disagree and reaprime isn't provably buggy" rule,
/// Crema adopts reaprime's UUID. Open question: needs sniffer verification
/// against a real Eclair.
pub const SERVICE_UUID: &str = "b905eaea-6c7e-4f73-b43d-2cdfcab29570";
/// Characteristic the scale notifies weight on.
///
/// Reaprime's data characteristic, paired with the reaprime SERVICE_UUID
/// above (`atomheart_scale.dart:21`): `b905eaeb-6c7e-4f73-b43d-2cdfcab29570`.
pub const NOTIFY_UUID: &str = "b905eaeb-6c7e-4f73-b43d-2cdfcab29570";
/// Characteristic commands are written to.
///
/// Reaprime's command characteristic, paired with the reaprime SERVICE_UUID
/// above (`atomheart_scale.dart:23`): `b905eaec-6c7e-4f73-b43d-2cdfcab29570`.
pub const COMMAND_UUID: &str = "b905eaec-6c7e-4f73-b43d-2cdfcab29570";

// Commands are an ASCII-mnemonic scheme, one letter + `01 01`: 'T'are,
// 'S'tart, 'E'nd, 'R'eset — per Decenza `atomhearteclairscale.cpp:247-262`,
// which cites de1app PR #349 (the Eclair gained a DEDICATED timer-reset
// opcode, distinct from tare). The previous constants here followed
// reaprime's `0x43`-multiplexed scheme (start `43 01 01`, stop `43 00 00`,
// reset = tare), which disagrees on every byte except tare — aligned to
// Decenza 2026-07-07 (see the local review notes); hardware-verify on a
// real Eclair when one is on the bench.
/// Command: tare — `'T' 01 01`.
pub const TARE: [u8; 3] = [0x54, 0x01, 0x01];
/// Command: start the timer — `'S' 01 01`.
pub const TIMER_START: [u8; 3] = [0x53, 0x01, 0x01];
/// Command: stop the timer — `'E' 01 01`.
pub const TIMER_STOP: [u8; 3] = [0x45, 0x01, 0x01];
/// Command: reset the timer to zero — `'R' 01 01` (does NOT tare).
pub const TIMER_RESET: [u8; 3] = [0x52, 0x01, 0x01];

/// Decode a weight notification into grams.
///
/// Byte 0 must be `'W'`. Bytes 1–4 are a signed little-endian 32-bit value in
/// milligrams; byte 9 is an XOR checksum over bytes 1–8 — a frame that fails
/// the checksum is rejected.
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    if !valid_frame(data) {
        return None;
    }
    let milligrams = i32::from_le_bytes([data[1], data[2], data[3], data[4]]);
    Some(milligrams as f32 / 1000.0)
}

/// Decode the scale's built-in timer from a weight notification.
///
/// Bytes 5–8 are a little-endian unsigned 32-bit millisecond count
/// representing the scale's running stopwatch. A reading of `0` means the
/// timer is not running and yields `None` — matches reaprime's behaviour
/// (`atomheart_scale.dart:parseFrame`). Same header + checksum gates as
/// [`parse_weight`]: a bad frame returns `None` for both channels.
///
/// Pre-2026-05-22 Crema dropped this field on the floor.
pub fn parse_timer(data: &[u8]) -> Option<Duration> {
    if !valid_frame(data) {
        return None;
    }
    let ms = u32::from_le_bytes([data[5], data[6], data[7], data[8]]);
    if ms == 0 {
        None
    } else {
        Some(Duration::from_millis(u64::from(ms)))
    }
}

/// Header + length + XOR-checksum gate shared by every Atomheart frame
/// channel. Centralises the validation so weight + timer decoders can't
/// drift in what they consider a usable frame.
fn valid_frame(data: &[u8]) -> bool {
    if data.len() < 10 || data[0] != b'W' {
        return false;
    }
    let checksum = data[1..9].iter().fold(0u8, |acc, &b| acc ^ b);
    checksum == data[9]
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

    /// Build a weight frame for an arbitrary signed milligram value with a
    /// correct XOR checksum.
    fn frame_for(milligrams: i32) -> [u8; 10] {
        let mg = milligrams.to_le_bytes();
        let mut f = [b'W', mg[0], mg[1], mg[2], mg[3], 0, 0, 0, 0, 0];
        f[9] = f[1..9].iter().fold(0u8, |acc, &b| acc ^ b);
        f
    }

    #[test]
    fn decodes_milligrams_to_grams() {
        assert_eq!(parse_weight(&frame()), Some(18.0));
    }

    #[test]
    fn decodes_a_negative_weight() {
        // -18000 mg is a signed little-endian i32 -> -18.0 g.
        assert_eq!(parse_weight(&frame_for(-18_000)), Some(-18.0));
    }

    #[test]
    fn decodes_a_zero_weight() {
        assert_eq!(parse_weight(&frame_for(0)), Some(0.0));
    }

    #[test]
    fn rejects_a_short_packet() {
        // One byte short of the 10-byte minimum, header and checksum aside.
        assert_eq!(parse_weight(&[b'W', 0, 0, 0, 0, 0, 0, 0, 0]), None);
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

    // ── Timer decode ───────────────────────────────────────────────────

    /// Build a frame with explicit weight + timer milliseconds; XOR
    /// checksum re-derived so the frame validates.
    fn frame_with_timer(weight_mg: i32, timer_ms: u32) -> [u8; 10] {
        let w = weight_mg.to_le_bytes();
        let t = timer_ms.to_le_bytes();
        let mut f = [b'W', w[0], w[1], w[2], w[3], t[0], t[1], t[2], t[3], 0];
        f[9] = f[1..9].iter().fold(0u8, |acc, &b| acc ^ b);
        f
    }

    #[test]
    fn decodes_timer_milliseconds() {
        // Reaprime's `parseFrame` fixture — weight 1500 mg, timer 5000 ms.
        let frame = frame_with_timer(1500, 5000);
        assert_eq!(parse_weight(&frame), Some(1.5));
        assert_eq!(parse_timer(&frame), Some(Duration::from_millis(5000)));
    }

    #[test]
    fn zero_timer_returns_none() {
        // Reaprime: a 0-ms timer means "not running" and surfaces as null.
        let frame = frame_with_timer(2000, 0);
        assert_eq!(parse_weight(&frame), Some(2.0));
        assert_eq!(parse_timer(&frame), None);
    }

    #[test]
    fn timer_rejects_a_short_packet() {
        assert_eq!(parse_timer(&[b'W', 0, 0, 0, 0, 0, 0, 0, 0]), None);
    }

    #[test]
    fn timer_rejects_a_bad_header() {
        let mut bad = frame_with_timer(1500, 5000);
        bad[0] = b'X';
        assert_eq!(parse_timer(&bad), None);
    }

    #[test]
    fn timer_rejects_a_bad_checksum() {
        let mut bad = frame_with_timer(1500, 5000);
        bad[9] ^= 0xFF;
        assert_eq!(parse_timer(&bad), None);
    }
}
