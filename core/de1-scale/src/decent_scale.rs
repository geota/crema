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
}
