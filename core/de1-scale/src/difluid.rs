//! Difluid Microbalance BLE codec (`scale_type` `difluid`) — the original
//! Microbalance and the Microbalance Ti, which differ only in their service
//! UUID ([`SERVICE_UUID`] / [`SERVICE_UUID_TI`]).
//!
//! **Caution — unverified.** Decoding follows reaprime (signed big-endian
//! `i32`, gated on `data[3] == 0`) and de1app, but the byte offsets (weight at
//! bytes 5–8) are still best confirmed against physical hardware. The scale
//! streams no weight until [`ENABLE_NOTIFICATIONS`] is written at connect.

// Raw integer weight fields are decoded into `f32` grams; precision loss past
// 2^23 is inherent to representing a wire reading as the codec's `f32` weight,
// not a defect, so the precision-loss lint is allowed module-wide here.
#![allow(clippy::cast_precision_loss)]

/// GATT service UUID of the original Microbalance.
pub const SERVICE_UUID: &str = "000000ee-0000-1000-8000-00805f9b34fb";
/// GATT service UUID of the Microbalance Ti — same DF-DF protocol on the same
/// `AA01` characteristic, different service (DiFluid `protocolMicrobalance.md`,
/// Dec 2024; Decenza e8577ff2 `de1characteristics.h:497-515`). Matching only
/// `0x00EE` meant a Ti never connected.
pub const SERVICE_UUID_TI: &str = "000000dd-0000-1000-8000-00805f9b34fb";
/// Characteristic for both weight notifications and command writes (both
/// models).
pub const NOTIFY_COMMAND_UUID: &str = "0000aa01-0000-1000-8000-00805f9b34fb";

/// Length of one sensor (weight) frame.
const SENSOR_FRAME_LEN: usize = 19;
/// A sensor frame opens `DF DF 03 00`; the same characteristic also echoes
/// settings writes (func 1), which are 6-7 bytes.
const SENSOR_FRAME_HEADER: [u8; 4] = [0xDF, 0xDF, 0x03, 0x00];
/// Raw weights at or beyond ±2000.0 g are not real readings (Decenza
/// `difluidscale.cpp` range gate).
const MAX_RAW_WEIGHT: i32 = 20_000;

/// Command: enable automatic weight notifications. Must be sent after connect,
/// or the scale never pushes weight.
pub const ENABLE_NOTIFICATIONS: [u8; 7] = [0xDF, 0xDF, 0x01, 0x00, 0x01, 0x01, 0xC1];
/// Command: tare.
pub const TARE: [u8; 7] = [0xDF, 0xDF, 0x03, 0x02, 0x01, 0x01, 0xC5];
/// Command: start the timer.
pub const TIMER_START: [u8; 7] = [0xDF, 0xDF, 0x03, 0x02, 0x01, 0x00, 0xC4];
/// Command: stop the timer.
pub const TIMER_STOP: [u8; 7] = [0xDF, 0xDF, 0x03, 0x01, 0x01, 0x00, 0xC3];
/// Command: reset the timer (same frame as start).
pub const TIMER_RESET: [u8; 7] = TIMER_START;
/// Command: set the display unit to grams.
pub const SET_UNIT_GRAMS: [u8; 7] = [0xDF, 0xDF, 0x01, 0x04, 0x01, 0x00, 0xC4];

/// Decode a weight notification into grams.
///
/// A sensor frame is 19 bytes opening `DF DF 03 00`; bytes 5–8 are a
/// **signed** big-endian 32-bit value in units of 0.1 g, so a below-tare
/// reading decodes as a small negative (reaprime `difluid_scale.dart:158`
/// reads `getInt32` big-endian; de1app reads the same field). DiFluid's worked
/// example: `DF DF 03 00 0D 00 00 02 F8 …` is 76.0 g. Byte 3 must be 0 —
/// weight and command share one characteristic, so a non-weight ack must not
/// be mis-read as a weight (reaprime `difluid_scale.dart:143`).
///
/// The BLE stack can coalesce back-to-back notifications into one delivery,
/// so the notification is walked in 19-byte frames and the last valid weight
/// wins (Decenza 4b1ba6a9); the decoder used to read only the first 19 bytes.
/// A raw value at or beyond ±2000.0 g is dropped (Decenza's range gate).
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    let mut weight = None;
    for frame in sensor_frames(data) {
        let raw = i32::from_be_bytes([frame[5], frame[6], frame[7], frame[8]]);
        if raw.abs() < MAX_RAW_WEIGHT {
            weight = Some(raw as f32 / 10.0);
        }
    }
    weight
}

/// The leading run of complete sensor frames in `data`, 19 bytes apart. Stops
/// at the first chunk that isn't a sensor frame or is cut short (Decenza
/// `difluidscale.cpp` `onCharacteristicChanged`).
fn sensor_frames(data: &[u8]) -> impl Iterator<Item = &[u8]> {
    data.chunks(SENSOR_FRAME_LEN)
        .take_while(|f| f.len() == SENSOR_FRAME_LEN && f.starts_with(&SENSOR_FRAME_HEADER))
}

/// Whether the Difluid is currently displaying grams.
///
/// Byte `[17]` of a weight notification is the unit flag — `0` for grams,
/// non-zero for ounces / pounds / ml. Returns `None` if `data` is too short
/// to carry the flag (matches the [`parse_weight`] length gate). Returns
/// `Some(false)` for a non-grams reading, which is the signal the
/// auto-recovery policy uses to re-send [`SET_UNIT_GRAMS`].
///
/// **Defer to reaprime.** Reaprime's parse path
/// (`reaprime/lib/src/models/device/impl/difluid/difluid_scale.dart:147-154`)
/// fires `SET_UNIT_GRAMS` whenever `data[17] != 0`; legacy de1app sends
/// `SET_UNIT_GRAMS` once at connect and never checks again. Crema mirrors
/// reaprime's behaviour at the shell layer (see `CremaCore::on_notification`).
#[must_use]
pub fn is_grams_unit(data: &[u8]) -> Option<bool> {
    sensor_frames(data).last().map(|frame| frame[17] == 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// DiFluid's worked example (`protocolMicrobalance.md`, quoted in Decenza
    /// `difluidscale.cpp`): 0x000002F8 = 760 → 76.0 g.
    const DOC_FRAME: [u8; 19] = [
        0xDF, 0xDF, 0x03, 0x00, 0x0D, 0x00, 0x00, 0x02, 0xF8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0A,
        0x27, 0xB0, 0x00, 0xA9,
    ];

    /// A sensor frame carrying `raw` tenths of a gram.
    fn frame(raw: i32) -> [u8; 19] {
        let mut f = DOC_FRAME;
        f[5..9].copy_from_slice(&raw.to_be_bytes());
        f
    }

    #[test]
    fn decodes_the_documented_frame() {
        assert_eq!(parse_weight(&DOC_FRAME), Some(76.0));
        assert_eq!(is_grams_unit(&DOC_FRAME), Some(true));
    }

    #[test]
    fn decodes_big_endian_weight_to_grams() {
        assert_eq!(parse_weight(&frame(180)), Some(18.0));
        assert_eq!(parse_weight(&frame(0)), Some(0.0));
    }

    #[test]
    fn decodes_a_negative_below_tare_reading() {
        // Signed: 0xFFFFFFFF (-1) → -0.1 g, a real below-tare reading — not
        // the ~4.29e8 g garbage an unsigned decode produced.
        assert_eq!(parse_weight(&frame(-1)), Some(-0.1));
    }

    #[test]
    fn rejects_a_non_weight_frame() {
        // data[3] != 0 marks a command ack on the shared characteristic — not a
        // weight, even at full length (reaprime difluid_scale.dart:143).
        let mut ack = DOC_FRAME;
        ack[3] = 0x01;
        assert_eq!(parse_weight(&ack), None);
        assert_eq!(is_grams_unit(&ack), None);
        // A 7-byte settings echo, and a headerless 19-byte frame.
        assert_eq!(parse_weight(&SET_UNIT_GRAMS), None);
        assert_eq!(parse_weight(&[0u8; 19]), None);
    }

    #[test]
    fn rejects_a_short_packet() {
        assert_eq!(parse_weight(&DOC_FRAME[..18]), None);
        assert_eq!(is_grams_unit(&DOC_FRAME[..18]), None);
    }

    #[test]
    fn splits_coalesced_frames_and_keeps_the_last_weight() {
        // Decenza 4b1ba6a9: two back-to-back frames in one delivery.
        let mut two = frame(180).to_vec();
        two.extend(frame(185));
        assert_eq!(parse_weight(&two), Some(18.5));
        // A complete frame then a truncated one: the complete one decodes.
        let mut cut = frame(180).to_vec();
        cut.extend(&frame(185)[..10]);
        assert_eq!(parse_weight(&cut), Some(18.0));
        // A complete frame then a settings echo: the echo is not weight.
        let mut echo = frame(180).to_vec();
        echo.extend(SET_UNIT_GRAMS);
        assert_eq!(parse_weight(&echo), Some(18.0));
    }

    #[test]
    fn drops_out_of_range_raw_weights() {
        // Decenza's gate: |raw| >= 20000 (2000.0 g) is not a reading.
        assert_eq!(parse_weight(&frame(19_999)), Some(1999.9));
        assert_eq!(parse_weight(&frame(20_000)), None);
        assert_eq!(parse_weight(&frame(-20_000)), None);
    }

    #[test]
    fn is_grams_unit_reads_byte_17() {
        // Reaprime's `data[17] != 0` check: byte [17] == 0 means grams; any
        // non-zero byte means the auto-recovery path re-sends SET_UNIT_GRAMS.
        let mut f = DOC_FRAME;
        assert_eq!(is_grams_unit(&f), Some(true));
        f[17] = 0x01;
        assert_eq!(is_grams_unit(&f), Some(false));
        f[17] = 0xFF;
        assert_eq!(is_grams_unit(&f), Some(false));
    }
}
