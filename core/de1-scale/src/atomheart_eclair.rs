//! Atomheart Eclair BLE codec (`scale_type` `atomheart_eclair`).
//!
//! Weight arrives on [`NOTIFY_UUID`] as an exact 10-byte `'W'` frame;
//! commands go to [`COMMAND_UUID`], which also notifies the battery as a
//! `'B'` frame (see [`parse_battery`]).

// Raw integer weight fields are decoded into `f32` grams; precision loss past
// 2^23 is inherent to representing a wire reading as the codec's `f32` weight,
// not a defect, so the precision-loss lint is allowed module-wide here.
#![allow(clippy::cast_precision_loss)]

use std::time::Duration;

/// GATT service UUID — de1app's hardware-tested set
/// (`de1plus/machine.tcl:92-94`).
///
/// Crema used to carry reaprime's `b905eaea-6c7e-4f73-b43d-2cdfcab29570`
/// set (PR G's "defer to reaprime when the two disagree" rule), which shares
/// only the first 32 bits with de1app's. reaprime/decaid has since dropped
/// that set for de1app's — "use the current Eclair GATT" (decaid 5544ff39,
/// `atomheart_scale.dart:18-23`, with a scan-filter test that the old service
/// is gone) — so the old set is dropped here too, entirely (user decision,
/// 2026-10-01). With it the Eclair most likely never connected.
pub const SERVICE_UUID: &str = "b905eaea-2e63-0e04-7582-7913f10d8f81";
/// Characteristic the scale notifies weight on (de1app
/// `cuuid_atomheart_eclair`).
pub const NOTIFY_UUID: &str = "ad736c5f-bbc9-1f96-d304-cb5d5f41e160";
/// Characteristic commands are written to (de1app
/// `cuuid_atomheart_eclair_cmd`). It also notifies the battery frame — decaid
/// subscribes to it for battery and treats a failed subscription as
/// non-fatal (`_registerConfigNotifications`, d6994ea0).
pub const COMMAND_UUID: &str = "4f9a45ba-8e1b-4e07-e157-0814d393b968";

/// Length of a weight frame: `'W'`, weight i32 LE, timer u32 LE, XOR.
const WEIGHT_FRAME_LEN: usize = 10;

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
/// The frame must be exactly 10 bytes and byte 0 must be `'W'`. Bytes 1–4
/// are a signed little-endian 32-bit value in milligrams; byte 9 is an XOR
/// checksum over bytes 1–8 — a frame that fails the checksum is rejected.
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
///
/// The length is exact, as in de1app's parser and decaid 57bc5793: a 9-byte
/// `57 00 … 00` let the last timer byte double as the checksum and
/// validated as a zero weight, and a longer frame isn't this shape.
fn valid_frame(data: &[u8]) -> bool {
    if data.len() != WEIGHT_FRAME_LEN || data[0] != b'W' {
        return false;
    }
    xor_of_body(data) == data[WEIGHT_FRAME_LEN - 1]
}

/// XOR of every byte between the header and the trailing checksum.
fn xor_of_body(data: &[u8]) -> u8 {
    data[1..data.len() - 1].iter().fold(0u8, |acc, &b| acc ^ b)
}

/// Decode a battery notification from the command characteristic into a
/// percentage.
///
/// `'B' <level> <xor>` — 3 bytes on current firmware, or the legacy 5-byte
/// `'B' <level> <x> <y> <xor>` — where the XOR covers every byte between the
/// header and the checksum (decaid `parseBatteryFrame`, d6994ea0). A level
/// above 100 is rejected.
#[must_use]
pub fn parse_battery(data: &[u8]) -> Option<u8> {
    if !matches!(data.len(), 3 | 5) || data[0] != b'B' {
        return None;
    }
    if xor_of_body(data) != data[data.len() - 1] {
        return None;
    }
    (data[1] <= 100).then_some(data[1])
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
        // decaid 57bc5793: `57 00 … 00` (9 bytes) XOR-validates if the last
        // timer byte is read as the checksum — it must not decode as 0 g.
        assert_eq!(parse_weight(&[b'W', 0, 0, 0, 0, 0, 0, 0, 0]), None);
    }

    #[test]
    fn rejects_an_over_long_packet() {
        // A valid frame with a trailing extra byte is not a weight frame.
        let mut long = frame().to_vec();
        long.push(0);
        assert_eq!(parse_weight(&long), None);
        assert_eq!(parse_timer(&long), None);
    }

    #[test]
    fn uses_de1apps_gatt_uuids_only() {
        // de1app machine.tcl:92-94 (decaid 5544ff39 switched to the same).
        assert_eq!(SERVICE_UUID, "b905eaea-2e63-0e04-7582-7913f10d8f81");
        assert_eq!(NOTIFY_UUID, "ad736c5f-bbc9-1f96-d304-cb5d5f41e160");
        assert_eq!(COMMAND_UUID, "4f9a45ba-8e1b-4e07-e157-0814d393b968");
        for uuid in [SERVICE_UUID, NOTIFY_UUID, COMMAND_UUID] {
            assert!(!uuid.contains("6c7e-4f73"), "old reaprime set: {uuid}");
        }
    }

    #[test]
    fn decodes_battery_frames() {
        // decaid "publishes canonical battery notifications": 42 4B 4B.
        assert_eq!(parse_battery(&[0x42, 75, 75]), Some(75));
        // "accepts the legacy five-byte battery frame": 63, A5, 5A + XOR.
        assert_eq!(
            parse_battery(&[0x42, 63, 0xA5, 0x5A, 63 ^ 0xA5 ^ 0x5A]),
            Some(63)
        );
        // "rejects malformed and out-of-range battery frames".
        assert_eq!(parse_battery(&[0x42, 0, 0]), Some(0));
        assert_eq!(parse_battery(&[0x42, 75, 0]), None);
        assert_eq!(parse_battery(&[0x42, 101, 101]), None);
        assert_eq!(parse_battery(&[0x42, 0xFF, 0xFF]), None);
        assert_eq!(parse_battery(&[0x42, 75]), None);
        assert_eq!(parse_battery(&[0x42, 75, 0, 75]), None);
        // A weight frame is not a battery frame.
        assert_eq!(parse_battery(&frame()), None);
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
