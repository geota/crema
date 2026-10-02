//! Timemore Dot BLE codec (`scale_type` `timemore`).
//!
//! Protocol reverse-engineered from an HCI snoop of the official Timemore
//! app; two independent implementations agree byte-for-byte — Decenza
//! `timemorescale.cpp` (shipped) and de1app PR #345 (open at port time,
//! 2026-07-07). Commands are 9–10 bytes framed `A5 5A` with a trailing
//! checksum; the weight notification is opcode `0x01`, command `0x01`, with
//! a signed 16-bit big-endian value in tenths of a gram at bytes 8–9, framed
//! by a payload length at bytes 4–5 (see [`parse_weight`]).
//!
//! NOT the Timemore Black Mirror — that scale speaks the standard Weight
//! Scale service (`181D`/`2A9D`, see Beanconqueror `timemoreScale.ts`) and
//! is a separate codec if ever wanted.

/// GATT service UUID (the generic `FFF0` service — scales sharing it are
/// disambiguated by advertised name during the BLE scan).
pub const SERVICE_UUID: &str = "0000fff0-0000-1000-8000-00805f9b34fb";
/// Characteristic the scale notifies weight on.
pub const STATUS_UUID: &str = "0000fff1-0000-1000-8000-00805f9b34fb";
/// Characteristic commands are written to (write-without-response, matching
/// Decenza `timemorescale.cpp:165` `WriteType::WithoutResponse`).
pub const COMMAND_UUID: &str = "0000fff2-0000-1000-8000-00805f9b34fb";

/// The six-command init sequence that unlocks the hardware tare / timer
/// surface — without it the scale streams weight but ignores commands.
/// The official app (and Decenza `timemorescale.cpp:170-186`) sends the
/// whole sequence twice after enabling notifications.
pub const INIT_SEQUENCE: [[u8; 9]; 6] = [
    [0xA5, 0x5A, 0x02, 0x13, 0x00, 0x00, 0x00, 0x00, 0x00],
    [0xA5, 0x5A, 0x02, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00],
    [0xA5, 0x5A, 0x02, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00],
    [0xA5, 0x5A, 0x02, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00],
    [0xA5, 0x5A, 0x02, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00],
    [0xA5, 0x5A, 0x02, 0x0C, 0x00, 0x00, 0x00, 0x00, 0x00],
];

/// Command: tare (HCI-snoop confirmed; Decenza `timemorescale.cpp:26`).
pub const TARE: [u8; 10] = [0xA5, 0x5A, 0x03, 0x0D, 0x00, 0x02, 0x00, 0x00, 0x00, 0x71];
/// Command: start the on-scale timer.
pub const TIMER_START: [u8; 9] = [0xA5, 0x5A, 0x03, 0x02, 0x00, 0x01, 0x01, 0x00, 0x20];
/// Command: stop the on-scale timer.
pub const TIMER_STOP: [u8; 10] = [0xA5, 0x5A, 0x03, 0x02, 0x00, 0x01, 0x02, 0x00, 0xFF, 0xD0];
/// Command: reset the on-scale timer.
pub const TIMER_RESET: [u8; 10] = [0xA5, 0x5A, 0x03, 0x02, 0x00, 0x01, 0x03, 0x00, 0xFF, 0x81];

/// Keep-alive poll pair — the official app sends both every ~10-15 s; the
/// connection goes quiet without them (Decenza `timemorescale.cpp:193-199`
/// `sendKeepAlive`: `POLL_1`, then `POLL_2` right after).
pub const POLL_1: [u8; 9] = [0xA5, 0x5A, 0x02, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00];
/// Second half of the keep-alive pair.
pub const POLL_2: [u8; 10] = [0xA5, 0x5A, 0x03, 0x08, 0x00, 0x02, 0x01, 0x00, 0x00, 0x25];
/// Keep-alive cadence (Decenza polls every 10 s).
pub const HEARTBEAT_INTERVAL_MS: u64 = 10_000;

/// Frame header.
const HEADER: [u8; 2] = [0xA5, 0x5A];
/// Bytes before the payload: header, opcode, command id, payload length.
const FRAME_HEAD_LEN: usize = 6;
/// Shortest frame: the head plus a two-byte trailer.
const MIN_FRAME_LEN: usize = 8;
/// Opcode of a frame the scale reports.
const OPCODE_REPORT: u8 = 0x01;
/// Command id of a weight / flow / time report (`0x05` is battery).
const CMD_WEIGHT: u8 = 0x01;

/// Decode a weight notification into grams.
///
/// A frame is `A5 5A <opcode> <cmd> <len u16 BE> <payload[len]> <crc u16>`
/// (Beanconqueror `timemoreDotScale.ts` `parseStatusUpdate`). A weight frame
/// is opcode `0x01`, command `0x01`, with a signed 16-bit big-endian value in
/// tenths of a gram at bytes 8–9 (Decenza `timemorescale.cpp:140-159`,
/// de1app PR #345 `bluetooth.tcl:1660`).
///
/// One notification can carry several frames back to back, so every frame
/// is walked by its declared length (Beanconqueror 6ce29ae0) and the last
/// weight wins; a frame of any other command — battery (`0x05`) included —
/// is skipped rather than read as weight. The decoder used to parse only the
/// first frame and never checked the command byte.
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    let mut weight = None;
    let mut offset = 0;
    while offset + MIN_FRAME_LEN <= data.len() {
        if data[offset..offset + 2] != HEADER {
            offset += 1;
            continue;
        }
        let frame = &data[offset..];
        let payload_len = usize::from(u16::from_be_bytes([frame[4], frame[5]]));
        if payload_len > frame.len() - FRAME_HEAD_LEN {
            // Truncated: the declared payload runs past the notification.
            break;
        }
        // The weight (bytes 8-9) must lie inside this frame's payload — a
        // short frame never borrows the next frame's bytes.
        if frame[2] == OPCODE_REPORT && frame[3] == CMD_WEIGHT && payload_len >= 4 {
            let raw = i16::from_be_bytes([frame[8], frame[9]]);
            weight = Some(f32::from(raw) / 10.0);
        }
        offset += (FRAME_HEAD_LEN + payload_len + 2).max(MIN_FRAME_LEN);
    }
    weight
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A weight report as Beanconqueror frames it: `A5 5A 01 01 00 0C`, a
    /// 12-byte payload (weight i32 BE in tenths, flow, time) and a 2-byte
    /// CRC (not verified — Beanconqueror 6ce29ae0 dropped the check too).
    fn weight_frame(tenths: i16) -> Vec<u8> {
        let w = i32::from(tenths).to_be_bytes();
        let mut f = vec![0xA5, 0x5A, 0x01, 0x01, 0x00, 0x0C];
        f.extend_from_slice(&w);
        f.extend_from_slice(&[0; 8]);
        f.extend_from_slice(&[0xAB, 0xCD]);
        f
    }

    /// A battery report: command `0x05`, payload `[?, level]`.
    fn battery_frame(level: u8) -> Vec<u8> {
        vec![0xA5, 0x5A, 0x01, 0x05, 0x00, 0x02, 0x00, level, 0x12, 0x34]
    }

    #[test]
    fn decodes_big_endian_tenths_to_grams() {
        // Bytes 8-9 = 0x00 0xB4 -> 180 -> 18.0 g.
        assert_eq!(parse_weight(&weight_frame(180)), Some(18.0));
    }

    #[test]
    fn decodes_a_negative_weight() {
        // 0xFF 0x4C = -180 as i16 -> -18.0 g (a lifted cup after tare).
        assert_eq!(parse_weight(&weight_frame(-180)), Some(-18.0));
    }

    #[test]
    fn decodes_a_zero_weight() {
        assert_eq!(parse_weight(&weight_frame(0)), Some(0.0));
    }

    #[test]
    fn rejects_a_wrong_header_a_wrong_type_and_a_short_frame() {
        let mut bad_header = weight_frame(180);
        bad_header[..2].copy_from_slice(&[0x5A, 0xA5]);
        assert_eq!(parse_weight(&bad_header), None);
        // Opcode 0x02 frames are settings echoes, not weight.
        let mut wrong_type = weight_frame(180);
        wrong_type[2] = 0x02;
        assert_eq!(parse_weight(&wrong_type), None);
        // The payload runs past the notification.
        assert_eq!(parse_weight(&weight_frame(180)[..12]), None);
    }

    #[test]
    fn checks_the_command_byte() {
        // A battery report (cmd 0x05) has bytes at 8-9 too; they are not
        // weight.
        assert_eq!(parse_weight(&battery_frame(80)), None);
        let mut other = weight_frame(180);
        other[3] = 0x02;
        assert_eq!(parse_weight(&other), None);
    }

    #[test]
    fn splits_coalesced_frames_and_keeps_the_last_weight() {
        // Beanconqueror 6ce29ae0: battery | weight 18.0 | weight 18.5 in one
        // notification (a larger MTU coalesces them).
        let mut combined = battery_frame(80);
        combined.extend(weight_frame(180));
        combined.extend(weight_frame(185));
        assert_eq!(parse_weight(&combined), Some(18.5));
        // Noise before a header is skipped.
        let mut noisy = vec![0x00, 0x11];
        noisy.extend(weight_frame(180));
        assert_eq!(parse_weight(&noisy), Some(18.0));
        // A weight frame then a truncated one: the complete one decodes.
        let mut truncated = weight_frame(180);
        truncated.extend(&weight_frame(185)[..9]);
        assert_eq!(parse_weight(&truncated), Some(18.0));
    }

    #[test]
    fn a_frame_too_short_for_the_weight_does_not_borrow_the_next_frame() {
        // A weight-typed frame with a 0-byte payload, followed by a battery
        // frame whose bytes would otherwise sit at 8-9.
        let mut stream = vec![0xA5, 0x5A, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00];
        stream.extend(battery_frame(80));
        assert_eq!(parse_weight(&stream), None);
    }

    #[test]
    fn command_bytes_match_the_hci_snoop_hex() {
        // Pin the wire bytes against the published hex strings
        // (de1app PR #345's table / Decenza's fromHex literals).
        fn hex(bytes: &[u8]) -> String {
            bytes.iter().map(|b| format!("{b:02X}")).collect()
        }
        assert_eq!(hex(&TARE), "A55A030D000200000071");
        assert_eq!(hex(&TIMER_START), "A55A03020001010020");
        assert_eq!(hex(&TIMER_STOP), "A55A030200010200FFD0");
        assert_eq!(hex(&TIMER_RESET), "A55A030200010300FF81");
        assert_eq!(hex(&POLL_1), "A55A02080000000000");
        assert_eq!(hex(&POLL_2), "A55A0308000201000025");
        assert_eq!(hex(&INIT_SEQUENCE[0]), "A55A02130000000000");
        assert_eq!(hex(&INIT_SEQUENCE[5]), "A55A020C0000000000");
    }
}
