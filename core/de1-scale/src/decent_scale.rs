//! Decent Scale BLE codec (`scale_type` `decentscale`).
//!
//! Weight notifications arrive on [`READ_NOTIFY_UUID`]; commands are written to
//! [`WRITE_UUID`].

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
/// surface reads as one coherent group.
pub const LCD_ENABLE_GRAMS: [u8; 7] = [0x03, 0x0A, 0x01, 0x01, 0x00, 0x01, 0x08];

/// Command: enable the on-scale LCD in ounces mode — same role as
/// [`LCD_ENABLE_GRAMS`] but switches the on-scale display unit. Use when the
/// user's [`crate::scale::WeightUnit`] pref is ounces.
///
/// The legacy app sends this packet (`0A 01 01 01 01`) instead of the grams
/// variant (`0A 01 01 00 01`) when `enable_fluid_ounces == 1`
/// (de1plus/bluetooth.tcl:1272-1278). Byte `[4]` (the unit byte, `0x01`)
/// is the only difference from [`LCD_ENABLE_GRAMS`]; byte `[5]` (`0x01`)
/// still arms the heartbeat requirement, and the trailing `0x09` is the
/// XOR checksum over the first six bytes.
pub const LCD_ENABLE_OUNCES: [u8; 7] = [0x03, 0x0A, 0x01, 0x01, 0x01, 0x01, 0x09];

/// Command: disable the on-scale LCD (display off).
pub const LCD_DISABLE: [u8; 7] = [0x03, 0x0A, 0x00, 0x00, 0x00, 0x00, 0x09];

/// Command: power the scale off.
///
/// Wire bytes from the legacy app (`de1plus/bluetooth.tcl:1289`):
/// `[decent_scale_make_command 0A 02]`, padded by the builder to a 7-byte
/// frame with the XOR checksum (`0x0B`). Sent unconditionally — older
/// firmware versions (v1.0 / v1.1) silently no-op on this byte sequence
/// rather than erroring, so there is no harm in always emitting it.
pub const POWER_OFF: [u8; 7] = [0x03, 0x0A, 0x02, 0x00, 0x00, 0x00, 0x0B];

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

/// The Decent Scale's firmware version, as observed at runtime.
///
/// Parsed from the `0x0A` LCD / heartbeat reply (see
/// [`parse_command_response`]) and surfaced diagnostically — the core no
/// longer gates any behaviour on the version, but the value is still
/// useful as a connection-info field and for future telemetry. Older
/// firmware versions silently no-op on commands they don't understand.
///
/// (The legacy app *also* double-sends every command write regardless of
/// firmware version — the original `bluetooth.tcl:1327-1330` comment
/// pinned that on a v1.0 bug, but a closer read of every Decent-Scale
/// proc shows the duplicate is applied uniformly because the scale's
/// command buffer occasionally drops the next write when the previous
/// hasn't finished. The double-send therefore happens unconditionally in
/// [`crate::Scale`] / `de1-app`; it is *not* gated on this enum.)
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum DecentScaleFirmwareVersion {
    /// v1.0 — original firmware; no remote power-off.
    V1_0,
    /// v1.1 — minor revision; still no remote power-off.
    V1_1,
    /// v1.2+ — added the [`POWER_OFF`] command.
    V1_2,
    /// The firmware reply has not yet been observed (the scale answers a
    /// `0x0A` LCD-enable write with a frame carrying battery and version,
    /// so the version becomes known shortly after the first LCD-enable —
    /// see [`parse_command_response`]). Treated conservatively: no remote
    /// power-off.
    Unknown,
}

impl DecentScaleFirmwareVersion {
    /// Map a raw firmware-version byte (`data6` of a `0x0A` reply, per
    /// `de1plus/bluetooth.tcl:2740`) to a [`DecentScaleFirmwareVersion`].
    ///
    /// The legacy app stores the raw byte and uses it diagnostically only;
    /// here it's bucketed into the three variants the core actually gates
    /// behaviour on. Unrecognised bytes fall back to [`Self::Unknown`] so
    /// the conservative double-send and no-power-off defaults apply.
    #[must_use]
    pub const fn from_raw_byte(byte: u8) -> Self {
        match byte {
            0x10 => Self::V1_0,
            0x11 => Self::V1_1,
            // v1.2 and any later released firmware all carry the v1.2-era
            // behaviour (power-off works, writes don't need duplicating);
            // bucket them together so a future v1.3 / v2.0 firmware
            // doesn't fall through to Unknown.
            0x12..=0xFE => Self::V1_2,
            // 0x00 / 0xFF are reserved sentinel-ish values the firmware
            // never sets — fall through to Unknown.
            _ => Self::Unknown,
        }
    }
}

/// A decoded notification from the Decent Scale's read characteristic that
/// is *not* a weight packet.
///
/// The Decent Scale notifies on a single characteristic ([`READ_NOTIFY_UUID`])
/// for both the live weight stream and replies to commands the host has
/// written; weight packets are handled by [`parse_weight`], while a `0x0A`
/// (LCD / heartbeat) reply carries battery and firmware-version fields and
/// is decoded here. The legacy app reads `data5` as the battery byte and
/// `data6` as the firmware-version byte
/// (`de1plus/bluetooth.tcl:2738-2749`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CommandResponse {
    /// A `0x0A` LCD / heartbeat reply.
    ///
    /// The packet is 7 bytes; bytes `[5]` and `[6]` are the battery
    /// percentage and the firmware-version sentinel byte respectively.
    /// The same reply arrives both to an LCD-enable write and to a
    /// heartbeat write, so the host learns the firmware version shortly
    /// after the first LCD-enable.
    LcdAck {
        /// Battery percentage `0..=100` (legacy `data5`). The scale
        /// reports a value greater than 100 when on USB power; the legacy
        /// app clamps to 100 and stores `scale_usb_powered`. Returned
        /// here raw so callers can preserve that signal.
        battery_raw: u8,
        /// Firmware-version sentinel byte (legacy `data6`), bucketed into
        /// the three variants the core gates on. See
        /// [`DecentScaleFirmwareVersion::from_raw_byte`].
        firmware_version: DecentScaleFirmwareVersion,
    },
}

/// Decode a non-weight notification from the Decent Scale's read
/// characteristic.
///
/// Returns `Some` for a recognised reply (today: only the `0x0A` LCD /
/// heartbeat ack); `None` for anything else (a weight packet, a button
/// packet, a tare-ack `0xFE`, or a packet too short to classify). The
/// shell-side path is therefore:
///
/// 1. Try [`parse_weight`]; if it returns `Some`, surface the weight.
/// 2. Else try [`parse_command_response`]; if it returns `Some`, record
///    the firmware version on the [`DecentScale`] state.
/// 3. Else drop the notification (button presses, tare acks, etc.).
#[must_use]
pub fn parse_command_response(data: &[u8]) -> Option<CommandResponse> {
    // The Decent Scale's 0x0A reply is a 7-byte frame; anything shorter
    // can't carry the firmware-version byte we want.
    if data.len() < WEIGHT_PACKET_LEN {
        return None;
    }
    // Byte [0] is always 0x03 (the same `command` header byte every
    // builder emits); byte [1] is the command id. 0x0A is the LCD /
    // heartbeat command, and that's the only response shape we model
    // here today.
    if data[0] != 0x03 || data[1] != 0x0A {
        return None;
    }
    Some(CommandResponse::LcdAck {
        battery_raw: data[5],
        firmware_version: DecentScaleFirmwareVersion::from_raw_byte(data[6]),
    })
}

/// Decent-Scale-specific state the [`crate::Scale`] wrapper carries.
///
/// Parallels the Bookoo's [`crate::ScaleCapabilities`]: a small struct that
/// holds runtime fields ([`tare_counter`](Self::next_tare),
/// [`firmware_version`](Self::record_firmware_version)) and exposes
/// capability-check helpers so the [`crate::Scale`] wrapper stays plain
/// dispatch.
///
/// The tare counter starts at `253` (matching legacy
/// `de1plus/bluetooth.tcl:1230-1235`) and wraps `255` → `0` on each tare;
/// the scale rejects a duplicate counter, so a stable rolling value is
/// what makes the tare command deduplicate-resistant across BLE retries.
#[derive(Debug, Clone)]
pub struct DecentScale {
    /// Tare counter — legacy starts at 253, increments per tare call,
    /// wraps `255` → `0`. The scale rejects a duplicate counter.
    tare_counter: u8,
    /// Firmware version, if known. Set by
    /// [`record_firmware_version`](Self::record_firmware_version) once the
    /// shell forwards the first `0x0A` reply; surfaced diagnostically only.
    firmware_version: Option<DecentScaleFirmwareVersion>,
}

impl DecentScale {
    /// The legacy starting value of the tare counter
    /// (`de1plus/bluetooth.tcl:1231`).
    const TARE_COUNTER_INIT: u8 = 253;

    /// Construct fresh state for a newly-connected Decent Scale.
    #[must_use]
    pub fn new() -> Self {
        Self {
            tare_counter: Self::TARE_COUNTER_INIT,
            firmware_version: None,
        }
    }

    /// Build the next tare command bytes and advance the rolling counter.
    ///
    /// The counter wraps `255` → `0` (matching
    /// `de1plus/bluetooth.tcl:1232-1234`) so it cycles indefinitely — a
    /// duplicate-suppressing rolling identifier, not a sequence number.
    pub fn next_tare(&mut self) -> [u8; 7] {
        let bytes = tare(self.tare_counter);
        self.tare_counter = self.tare_counter.wrapping_add(1);
        bytes
    }

    /// Record the firmware version reported by a `0x0A` reply (see
    /// [`parse_command_response`]).
    pub fn record_firmware_version(&mut self, version: DecentScaleFirmwareVersion) {
        self.firmware_version = Some(version);
    }

    /// The firmware version, if observed.
    #[must_use]
    pub fn firmware_version(&self) -> Option<DecentScaleFirmwareVersion> {
        self.firmware_version
    }
}

impl Default for DecentScale {
    fn default() -> Self {
        Self::new()
    }
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

    /// XOR of the first six bytes — every Decent Scale command carries this
    /// as the trailing checksum byte (see [`command`]).
    fn xor_checksum(bytes: [u8; 7]) -> u8 {
        bytes[..6].iter().fold(0u8, |acc, &b| acc ^ b)
    }

    #[test]
    fn lcd_enable_grams_matches_the_documented_wire_bytes() {
        // LCD-enable-with-heartbeat packet; byte 5 is the "send heartbeat" flag.
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

    #[test]
    fn lcd_enable_ounces_matches_the_documented_wire_bytes() {
        // Confirmed against the legacy
        // `decentscale_enable_lcd` builder (`decent_scale_make_command 0A
        // 01 01 01 01`) at `de1plus/bluetooth.tcl:1277`. Byte [4] (`0x01`)
        // is the ounces / grams unit flag; byte [5] still arms the
        // heartbeat requirement.
        assert_eq!(LCD_ENABLE_OUNCES, [0x03, 0x0A, 0x01, 0x01, 0x01, 0x01, 0x09]);
        assert_eq!(LCD_ENABLE_OUNCES[6], xor_checksum(LCD_ENABLE_OUNCES));
        // The only byte that differs from the grams variant is [4].
        let mut grams = LCD_ENABLE_GRAMS;
        grams[4] = 0x01;
        grams[6] = xor_checksum(grams);
        assert_eq!(LCD_ENABLE_OUNCES, grams);
    }

    #[test]
    fn power_off_matches_the_documented_wire_bytes() {
        // Documented at `de1plus/bluetooth.tcl:1289` as
        // `[decent_scale_make_command 0A 02]`; the trailing four bytes are
        // the builder's zero-padding and XOR checksum.
        assert_eq!(POWER_OFF, [0x03, 0x0A, 0x02, 0x00, 0x00, 0x00, 0x0B]);
        assert_eq!(POWER_OFF[6], xor_checksum(POWER_OFF));
    }

    #[test]
    fn decent_scale_tare_counter_wraps_255_to_0() {
        // The legacy app starts the counter at 253 and wraps 255 → 0
        // (`de1plus/bluetooth.tcl:1230-1234`); the rolling identifier
        // shouldn't ever stall at a fixed value, otherwise the scale
        // would reject the duplicate counter.
        let mut state = DecentScale::new();
        // 253 -> 254 -> 255 -> 0 -> 1 -> 2 -> ... over six tares.
        let counters: Vec<u8> = (0..6).map(|_| state.next_tare()[2]).collect();
        assert_eq!(counters, [253, 254, 255, 0, 1, 2]);
    }

    #[test]
    fn decent_scale_tare_command_matches_the_existing_builder() {
        // The stateful `next_tare` wraps the same `tare()` builder; one
        // call must produce the same bytes as a direct `tare(253)` (the
        // initial counter value).
        let mut state = DecentScale::new();
        assert_eq!(state.next_tare(), tare(253));
    }

    #[test]
    fn firmware_version_from_raw_byte_buckets_correctly() {
        use DecentScaleFirmwareVersion::{Unknown, V1_0, V1_1, V1_2};
        assert_eq!(DecentScaleFirmwareVersion::from_raw_byte(0x10), V1_0);
        assert_eq!(DecentScaleFirmwareVersion::from_raw_byte(0x11), V1_1);
        assert_eq!(DecentScaleFirmwareVersion::from_raw_byte(0x12), V1_2);
        // Any later released firmware buckets into v1.2 behaviour.
        assert_eq!(DecentScaleFirmwareVersion::from_raw_byte(0x13), V1_2);
        assert_eq!(DecentScaleFirmwareVersion::from_raw_byte(0x20), V1_2);
        // Sentinels fall back to Unknown.
        assert_eq!(DecentScaleFirmwareVersion::from_raw_byte(0x00), Unknown);
        assert_eq!(DecentScaleFirmwareVersion::from_raw_byte(0xFF), Unknown);
    }

    #[test]
    fn parse_command_response_decodes_a_0x0a_lcd_ack() {
        // A 7-byte 0x0A reply with battery = 78%, firmware sentinel = 0x12
        // (v1.2). Bytes [2-4] are the raw command payload echoed back;
        // bytes [5-6] carry the battery and firmware bytes per the legacy
        // app's `data5` / `data6` mapping.
        let frame = [0x03, 0x0A, 0x01, 0x01, 0x00, 0x4E, 0x12, 0x00];
        let response = parse_command_response(&frame).expect("0x0A reply");
        match response {
            CommandResponse::LcdAck {
                battery_raw,
                firmware_version,
            } => {
                assert_eq!(battery_raw, 0x4E);
                assert_eq!(firmware_version, DecentScaleFirmwareVersion::V1_2);
            }
        }
    }

    #[test]
    fn parse_command_response_ignores_a_weight_packet() {
        // A 0xCE weight packet isn't a command response.
        let frame = [0x03, 0xCE, 0x00, 0xC8, 0, 0, 0];
        assert_eq!(parse_command_response(&frame), None);
    }

    #[test]
    fn parse_command_response_ignores_a_short_frame() {
        assert_eq!(parse_command_response(&[0x03, 0x0A]), None);
    }
}
