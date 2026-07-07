//! Timemore Dot BLE codec (`scale_type` `timemore`).
//!
//! Protocol reverse-engineered from an HCI snoop of the official Timemore
//! app; two independent implementations agree byte-for-byte — Decenza
//! `timemorescale.cpp` (shipped) and de1app PR #345 (open at port time,
//! 2026-07-07). Commands are 9–10 bytes framed `A5 5A` with a trailing
//! checksum; the weight notification is type `0x01` with a signed 16-bit
//! big-endian value in tenths of a gram at bytes 8–9.
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

/// Decode a weight notification into grams.
///
/// A weight frame is at least 10 bytes: header `A5 5A`, packet type at
/// byte 2 (`0x01` = weight), and a signed 16-bit big-endian value in tenths
/// of a gram at bytes 8–9 (Decenza `timemorescale.cpp:140-159`).
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    if data.len() < 10 || data[0] != 0xA5 || data[1] != 0x5A || data[2] != 0x01 {
        return None;
    }
    let raw = i16::from_be_bytes([data[8], data[9]]);
    Some(f32::from(raw) / 10.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_big_endian_tenths_to_grams() {
        // Bytes 8-9 = 0x00 0xB4 -> 180 -> 18.0 g.
        let packet = [0xA5, 0x5A, 0x01, 0, 0, 0, 0, 0, 0x00, 0xB4];
        assert_eq!(parse_weight(&packet), Some(18.0));
    }

    #[test]
    fn decodes_a_negative_weight() {
        // 0xFF 0x4C = -180 as i16 -> -18.0 g (a lifted cup after tare).
        let packet = [0xA5, 0x5A, 0x01, 0, 0, 0, 0, 0, 0xFF, 0x4C];
        assert_eq!(parse_weight(&packet), Some(-18.0));
    }

    #[test]
    fn decodes_a_zero_weight() {
        let packet = [0xA5, 0x5A, 0x01, 0, 0, 0, 0, 0, 0x00, 0x00];
        assert_eq!(parse_weight(&packet), Some(0.0));
    }

    #[test]
    fn rejects_a_wrong_header_a_wrong_type_and_a_short_frame() {
        let bad_header = [0x5A, 0xA5, 0x01, 0, 0, 0, 0, 0, 0x00, 0xB4];
        assert_eq!(parse_weight(&bad_header), None);
        // Type 0x02 frames are settings echoes, not weight.
        let wrong_type = [0xA5, 0x5A, 0x02, 0, 0, 0, 0, 0, 0x00, 0xB4];
        assert_eq!(parse_weight(&wrong_type), None);
        assert_eq!(parse_weight(&[0xA5, 0x5A, 0x01, 0, 0, 0, 0, 0, 0x00]), None);
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
