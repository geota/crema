//! Decent Scale BLE codec (`scale_type` `decentscale`).
//!
//! Weight notifications arrive on [`READ_NOTIFY_UUID`]; commands are written to
//! [`WRITE_UUID`]. See the protocol reference, `docs/02-ble-protocol.md` §8.1.

/// GATT service UUID.
pub const SERVICE_UUID: &str = "0000fff0-0000-1000-8000-00805f9b34fb";
/// Characteristic the scale notifies weight (and button/info) packets on.
pub const READ_NOTIFY_UUID: &str = "0000fff4-0000-1000-8000-00805f9b34fb";
/// Characteristic commands are written to.
pub const WRITE_UUID: &str = "000036f5-0000-1000-8000-00805f9b34fb";

/// Minimum length of a weight notification.
const WEIGHT_PACKET_LEN: usize = 7;

/// Decode a weight notification into grams.
///
/// Returns `None` if `data` is not a weight packet — the Decent Scale also
/// sends button, tare-acknowledgement and info packets on the same
/// characteristic, distinguished by the type byte.
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    if data.len() < WEIGHT_PACKET_LEN {
        return None;
    }
    // Byte 1 is the packet type; 0xCE and 0xCA carry weight.
    if data[1] != 0xCE && data[1] != 0xCA {
        return None;
    }
    // Weight is a big-endian signed 16-bit value in units of 0.1 g.
    let raw = i16::from_be_bytes([data[2], data[3]]);
    Some(f32::from(raw) / 10.0)
}

/// Build a 7-byte command — `03 <cmd> <payload…> <xor>` — with the XOR
/// checksum the scale expects over the first six bytes.
fn command(cmd: u8, payload: [u8; 4]) -> [u8; 7] {
    let mut packet = [0x03, cmd, payload[0], payload[1], payload[2], payload[3], 0];
    packet[6] = packet[..6].iter().fold(0u8, |acc, &b| acc ^ b);
    packet
}

/// Command: tare the scale. `counter` should be incremented by the caller on
/// each tare so the scale can tell repeated requests apart.
pub fn tare(counter: u8) -> [u8; 7] {
    command(0x0F, [counter, 0x00, 0x00, 0x01])
}

/// Command: start the scale's timer.
pub fn timer_start() -> [u8; 7] {
    command(0x0B, [0x03, 0x00, 0x00, 0x00])
}

/// Command: stop the scale's timer.
pub fn timer_stop() -> [u8; 7] {
    command(0x0B, [0x00, 0x00, 0x00, 0x00])
}

/// Command: reset the scale's timer to zero.
pub fn timer_reset() -> [u8; 7] {
    command(0x0B, [0x02, 0x00, 0x00, 0x00])
}

/// Command: turn the display on and select grams.
pub fn display_on_grams() -> [u8; 7] {
    command(0x0A, [0x01, 0x01, 0x00, 0x01])
}

/// Command: enable the on-scale LCD in grams mode.
///
/// Byte 5 (`0x01`) is the "send heartbeat" flag — once set, the scale expects
/// periodic [`HEARTBEAT`] writes from the host or it puts its display to sleep
/// after a few seconds of silence. The trailing `0x08` is the XOR of bytes
/// `[0..=5]` (the same checksum scheme [`command`] computes). This is the same
/// byte sequence the existing [`display_on_grams`] builder produces — kept
/// here as a named constant so the LCD-enable / LCD-disable / heartbeat
/// surface (`docs/27`) reads as one coherent group.
pub const LCD_ENABLE_GRAMS: [u8; 7] = [0x03, 0x0A, 0x01, 0x01, 0x00, 0x01, 0x08];

/// Command: disable the on-scale LCD (display off).
pub const LCD_DISABLE: [u8; 7] = [0x03, 0x0A, 0x00, 0x00, 0x00, 0x00, 0x09];

/// Command: heartbeat write.
///
/// The Decent Scale's spec allows up to 5 s between heartbeats; the legacy
/// app ships one every 1 s and reaprime every 4 s. Crema uses
/// [`HEARTBEAT_INTERVAL_MS`] (2 s), comfortably under the spec ceiling and
/// quieter than the legacy 1 s cadence.
pub const HEARTBEAT: [u8; 7] = [0x03, 0x0A, 0x03, 0xFF, 0xFF, 0x00, 0x0A];

/// Recommended interval, in milliseconds, between [`HEARTBEAT`] writes — the
/// shell schedules the heartbeat clock; the core is sans-IO.
pub const HEARTBEAT_INTERVAL_MS: u64 = 2_000;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_a_weight_packet_to_grams() {
        // Type 0xCE, weight 0x00C8 = 200 -> 20.0 g.
        assert_eq!(parse_weight(&[0x03, 0xCE, 0x00, 0xC8, 0, 0, 0]), Some(20.0));
    }

    #[test]
    fn decodes_a_negative_weight() {
        // 0xFF38 as a signed 16-bit value is -200 -> -20.0 g.
        assert_eq!(
            parse_weight(&[0x03, 0xCA, 0xFF, 0x38, 0, 0, 0]),
            Some(-20.0)
        );
    }

    #[test]
    fn ignores_non_weight_packets() {
        // 0xAA is a button-press packet, not weight.
        assert_eq!(parse_weight(&[0x03, 0xAA, 0, 0, 0, 0, 0]), None);
    }

    #[test]
    fn rejects_a_short_packet() {
        assert_eq!(parse_weight(&[0x03, 0xCE]), None);
    }

    #[test]
    fn tare_command_carries_the_counter_and_checksum() {
        assert_eq!(tare(5), [0x03, 0x0F, 0x05, 0x00, 0x00, 0x01, 0x08]);
    }

    #[test]
    fn timer_commands_are_well_formed() {
        assert_eq!(timer_start(), [0x03, 0x0B, 0x03, 0x00, 0x00, 0x00, 0x0B]);
        assert_eq!(timer_stop(), [0x03, 0x0B, 0x00, 0x00, 0x00, 0x00, 0x08]);
        assert_eq!(timer_reset(), [0x03, 0x0B, 0x02, 0x00, 0x00, 0x00, 0x0A]);
    }

    /// XOR of the first six bytes — every Decent Scale command carries this
    /// as the trailing checksum byte (see [`command`]).
    fn xor_checksum(bytes: [u8; 7]) -> u8 {
        bytes[..6].iter().fold(0u8, |acc, &b| acc ^ b)
    }

    #[test]
    fn lcd_enable_grams_matches_the_documented_wire_bytes() {
        // Documented in `docs/27` appendix as the LCD-enable-with-heartbeat
        // packet; byte 5 is the "send heartbeat" flag.
        assert_eq!(LCD_ENABLE_GRAMS, [0x03, 0x0A, 0x01, 0x01, 0x00, 0x01, 0x08]);
        assert_eq!(LCD_ENABLE_GRAMS[6], xor_checksum(LCD_ENABLE_GRAMS));
        // The constant matches what the existing builder produces — kept in
        // lockstep so the LCD surface and the legacy `display_on_grams` builder
        // never drift apart.
        assert_eq!(LCD_ENABLE_GRAMS, display_on_grams());
    }

    #[test]
    fn lcd_disable_matches_the_documented_wire_bytes() {
        assert_eq!(LCD_DISABLE, [0x03, 0x0A, 0x00, 0x00, 0x00, 0x00, 0x09]);
        assert_eq!(LCD_DISABLE[6], xor_checksum(LCD_DISABLE));
    }

    #[test]
    fn heartbeat_matches_the_documented_wire_bytes() {
        assert_eq!(HEARTBEAT, [0x03, 0x0A, 0x03, 0xFF, 0xFF, 0x00, 0x0A]);
        assert_eq!(HEARTBEAT[6], xor_checksum(HEARTBEAT));
    }

    #[test]
    fn heartbeat_interval_matches_the_documented_2s_cadence() {
        // The Decent Scale spec allows up to 5 s between heartbeats. The
        // chosen cadence (2 s) is below that ceiling and above the legacy
        // app's 1 s; this assert pins the agreed value so an accidental
        // bump shows up here.
        assert_eq!(HEARTBEAT_INTERVAL_MS, 2_000);
    }
}
