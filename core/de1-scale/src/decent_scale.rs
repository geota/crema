//! Decent Scale BLE codec (`scale_type` `decentscale`).
//!
//! Weight notifications arrive on [`READ_NOTIFY_UUID`]; commands are written to
//! [`WRITE_UUID`].
//!
//! Weight arrives in one of two exact frame shapes, told apart by length
//! alone (a notification carries exactly one frame):
//!
//! - **7 bytes** — `03 <CE|CA> <weight i16 BE> <b4> <b5> <xor>`, the
//!   original v1.0/v1.1 firmware and the Half Decent Scale (HDS).
//! - **10 bytes** — the original scale's v1.2 firmware: the same header and
//!   weight, then `minutes seconds tenths` of the scale's timer, two unused
//!   bytes, and the XOR moved to byte 9 (de1app
//!   `decent_scale_weight_read_spec_v12`, `binary.tcl:350-364`; Decenza
//!   `decentscaleprotocol.h` `V12WeightFrameLength`, #1919; decaid
//!   `parseDecentWeightFrame`). Checking byte 6 of a 10-byte frame — the old
//!   rule here — dropped every v1.2 weight frame, so the scale showed no
//!   weight at all.

/// GATT service UUID.
pub const SERVICE_UUID: &str = "0000fff0-0000-1000-8000-00805f9b34fb";
/// Characteristic the scale notifies weight (and button/info) packets on.
pub const READ_NOTIFY_UUID: &str = "0000fff4-0000-1000-8000-00805f9b34fb";
/// Characteristic commands are written to.
pub const WRITE_UUID: &str = "000036f5-0000-1000-8000-00805f9b34fb";

/// Length of a standard frame — every command, the `0x0A` reply, and the
/// v1.0/v1.1/HDS weight notification.
const STANDARD_FRAME_LEN: usize = 7;

/// Length of the original scale's v1.2 timestamped weight notification.
const V12_WEIGHT_FRAME_LEN: usize = 10;

/// Every frame opens with this header byte.
const PACKET_HEADER: u8 = 0x03;

/// XOR of every byte of `frame` except the last.
fn xor_of_body(frame: &[u8]) -> u8 {
    frame[..frame.len() - 1].iter().fold(0u8, |acc, &b| acc ^ b)
}

/// One decoded weight notification.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct WeightFrame {
    /// Weight, grams.
    pub weight_g: f32,
    /// The scale's own timer, milliseconds — `Some` only for the v1.2
    /// 10-byte frame, whose bytes 4-6 carry `minutes seconds tenths`
    /// (decaid `parseDecentWeightFrame`: `(m*600 + s*10 + t) * 100`).
    /// Its presence is also proof of v1.2 firmware.
    pub timestamp_ms: Option<u32>,
}

/// Decode a weight notification, keeping the frame shape.
///
/// Returns `None` if `data` is not a complete weight packet — the Decent
/// Scale also sends button, tare-acknowledgement and info packets on the
/// same characteristic, distinguished by the type byte. Only an exact 7- or
/// 10-byte frame is decoded; any other length is ignored for weight.
pub fn parse_weight_frame(data: &[u8]) -> Option<WeightFrame> {
    let timestamped = match data.len() {
        STANDARD_FRAME_LEN => false,
        V12_WEIGHT_FRAME_LEN => true,
        _ => return None,
    };
    // Byte 1 is the packet type; 0xCE and 0xCA carry weight.
    if data[0] != PACKET_HEADER || (data[1] != 0xCE && data[1] != 0xCA) {
        return None;
    }
    // The packet carries a trailing XOR of every byte before it — byte 6 of
    // a 7-byte frame, byte 9 of a 10-byte frame. Verify it before trusting
    // the value: a corrupted frame whose type byte still reads as weight
    // must not inject a spurious spike into stop-at-weight (review #32).
    if xor_of_body(data) != data[data.len() - 1] {
        return None;
    }
    // Weight is a big-endian signed 16-bit value in units of 0.1 g, at bytes
    // 2-3 in both shapes.
    let raw = i16::from_be_bytes([data[2], data[3]]);
    let timestamp_ms = timestamped
        .then(|| (u32::from(data[4]) * 600 + u32::from(data[5]) * 10 + u32::from(data[6])) * 100);
    Some(WeightFrame {
        weight_g: f32::from(raw) / 10.0,
        timestamp_ms,
    })
}

/// Decode a weight notification into grams — [`parse_weight_frame`]
/// without the frame shape.
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    parse_weight_frame(data).map(|frame| frame.weight_g)
}

/// Build a 7-byte command — `03 <cmd> <payload…> <xor>` — with the XOR
/// checksum the scale expects over the first six bytes.
fn command(cmd: u8, payload: [u8; 4]) -> [u8; 7] {
    let mut packet = [0x03, cmd, payload[0], payload[1], payload[2], payload[3], 0];
    packet[6] = packet[..6].iter().fold(0u8, |acc, &b| acc ^ b);
    packet
}

/// Command: tare the scale, carrying `counter` in byte 2.
///
/// The counter must change from one logical tare to the next: the scale
/// treats a repeat of the last counter as the same request, so a fixed
/// counter can see every tare after the first deduplicated. de1app rolls it
/// (`tare_counter_incr`, `bluetooth.tcl:1259-1279`) and decaid does the same
/// (`scale.dart:551-556`: `_tareCounter` from 0, `& 0xFF`). Byte 5 is
/// `0x00` as in decaid and the official HDS API doc's `030F000000000C`
/// example (de1app sets it to `0x01`). [`DecentScale::next_tare`] owns the
/// counter; a retry of the same tare must resend the same frame rather than
/// call it again. (This replaces the fixed-counter frame Crema copied from
/// Decenza `decentscale.cpp:372-374` on 2026-07-07.)
#[must_use]
pub fn tare(counter: u8) -> [u8; 7] {
    command(0x0F, [counter, 0x00, 0x00, 0x00])
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

/// The original Decent Scale's firmware version, as observed at runtime.
///
/// Parsed from the `0x0A` LCD / heartbeat reply (see
/// [`parse_command_response`]), or inferred as v1.2 from a timestamped
/// 10-byte weight frame (see [`parse_weight_frame`]), and surfaced
/// diagnostically — the core no
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
    /// Map the original scale's firmware marker (byte index 5 of a `0x0A`
    /// reply) to a [`DecentScaleFirmwareVersion`].
    ///
    /// The marker table is `0xFE` → v1.0, `0x02` → v1.1, `0x03` → v1.2 —
    /// decaid `profile.dart` `_originalFirmwareVersions` (d5e2c482, from
    /// the public `pydecentscale` client). The previous `0x10`/`0x11`/
    /// `0x12..` table here matched no reference. Any other byte is
    /// [`Self::Unknown`] — including every HDS reply, whose byte 5 is the
    /// BCD major of [`HdsFirmwareVersion`] instead.
    #[must_use]
    pub const fn from_raw_byte(byte: u8) -> Self {
        match byte {
            0xFE => Self::V1_0,
            0x02 => Self::V1_1,
            0x03 => Self::V1_2,
            _ => Self::Unknown,
        }
    }
}

/// The Half Decent Scale's firmware version, decoded from bytes 5-6 of the
/// `0x0A` reply: byte 5 is the BCD-packed major (`00..=99`), byte 6 is
/// `(minor << 4) | patch` — openscale `include/ble.h:730-731`, decoded the
/// same way by Decenza (`decodeHdsFirmwareVersion`) and decaid
/// (`DecentHdsFirmwareVersion.fromBcd`). Wire `03 1E` is `3.1.14`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct HdsFirmwareVersion {
    /// Major version.
    pub major: u8,
    /// Minor version (one nibble).
    pub minor: u8,
    /// Patch version (one nibble).
    pub patch: u8,
}

impl HdsFirmwareVersion {
    /// Decode the BCD pair. `None` when byte 5 is not valid BCD or the major
    /// exceeds 30 — decaid's guard against reading an original scale's
    /// marker (e.g. `0xFE`) as an HDS version.
    #[must_use]
    pub const fn from_bcd(packed_major: u8, packed_minor_patch: u8) -> Option<Self> {
        let tens = packed_major >> 4;
        let units = packed_major & 0x0F;
        if tens > 9 || units > 9 {
            return None;
        }
        let major = tens * 10 + units;
        if major > 30 {
            return None;
        }
        Some(Self {
            major,
            minor: packed_minor_patch >> 4,
            patch: packed_minor_patch & 0x0F,
        })
    }
}

impl std::fmt::Display for HdsFirmwareVersion {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}.{}.{}", self.major, self.minor, self.patch)
    }
}

/// A decoded notification from the Decent Scale's read characteristic that
/// is *not* a weight packet.
///
/// The Decent Scale notifies on a single characteristic ([`READ_NOTIFY_UUID`])
/// for both the live weight stream and replies to commands the host has
/// written; weight packets are handled by [`parse_weight`], while a `0x0A`
/// (LCD / heartbeat) reply carries battery and firmware-version fields and
/// is decoded here. The references read the battery from byte index 4 and the
/// firmware version from byte index 5 — de1app calls these `data5` / `data6`
/// (`de1plus/bluetooth.tcl:2738-2749`), but its 1-based-ish field spec
/// (`binary.tcl:299-309`) makes `data5` == byte index 4; reaprime reads battery
/// at `data[4]` (`scale.dart:252`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CommandResponse {
    /// A `0x0A` LCD / heartbeat reply.
    ///
    /// The packet is 7 bytes; bytes `[4]` and `[5]` are the battery
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
        /// Byte 5 read as the original scale's firmware marker. See
        /// [`DecentScaleFirmwareVersion::from_raw_byte`].
        firmware_version: DecentScaleFirmwareVersion,
        /// Bytes 5-6 read as an HDS firmware triple, when they are valid
        /// BCD. The reply itself does not say which scale sent it (decaid
        /// only trusts HDS identity after a `0x22` voltage probe), so both
        /// readings are returned: an original v1.1/v1.2 marker (`0x02` /
        /// `0x03`) also decodes as an HDS major of 2 / 3.
        hds_firmware: Option<HdsFirmwareVersion>,
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
    // The Decent Scale's 0x0A reply is exactly one 7-byte frame (decaid
    // `parseDecentStatusFrame`; Decenza `notifiedFrameLengthExact`). A
    // 10-byte frame of this type has no known layout, so it is not guessed
    // at.
    if data.len() != STANDARD_FRAME_LEN {
        return None;
    }
    // Byte [0] is always 0x03 (the same `command` header byte every
    // builder emits); byte [1] is the command id. 0x0A is the LCD /
    // heartbeat command, and that's the only response shape we model
    // here today.
    if data[0] != PACKET_HEADER || data[1] != 0x0A {
        return None;
    }
    // Battery is byte index 4, firmware-version byte index 5. de1app's field
    // spec (binary.tcl:299-309) names them `data5` / `data6`, but in its
    // 1-based-ish scheme `data5` IS byte index 4 — reaprime confirms, reading
    // battery at `data[4]` (scale.dart:252). The old data[5]/data[6] read the
    // firmware byte as the battery and the XOR checksum as the firmware.
    Some(CommandResponse::LcdAck {
        battery_raw: data[4],
        firmware_version: DecentScaleFirmwareVersion::from_raw_byte(data[5]),
        hds_firmware: HdsFirmwareVersion::from_bcd(data[5], data[6]),
    })
}

/// Decent-Scale-specific state the [`crate::Scale`] wrapper carries.
///
/// Parallels the Bookoo's [`crate::ScaleCapabilities`]: a small struct that
/// holds runtime fields (the rolling tare counter, the observed firmware)
/// so the [`crate::Scale`] wrapper stays plain dispatch.
#[derive(Debug, Clone)]
pub struct DecentScale {
    /// Firmware version, if known. Set by
    /// [`record_firmware_version`](Self::record_firmware_version) once the
    /// shell forwards the first `0x0A` reply, or by
    /// [`parse_weight_frame`](Self::parse_weight_frame) on the first
    /// timestamped (v1.2) weight frame; surfaced diagnostically only.
    firmware_version: Option<DecentScaleFirmwareVersion>,
    /// The HDS firmware triple from the last `0x0A` reply that decoded as
    /// one. Diagnostic only — see [`CommandResponse::LcdAck::hds_firmware`].
    hds_firmware: Option<HdsFirmwareVersion>,
    /// Counter byte for the next [`tare`] frame — starts at 0 and wraps,
    /// like decaid's `_tareCounter`.
    tare_counter: u8,
}

impl DecentScale {
    /// Construct fresh state for a newly-connected Decent Scale.
    #[must_use]
    pub fn new() -> Self {
        Self {
            firmware_version: None,
            hds_firmware: None,
            tare_counter: 0,
        }
    }

    /// The frame for the next logical tare, advancing the rolling counter.
    /// Call once per tare request; a retry of that request resends the
    /// returned frame.
    pub fn next_tare(&mut self) -> [u8; 7] {
        let frame = tare(self.tare_counter);
        self.tare_counter = self.tare_counter.wrapping_add(1);
        frame
    }

    /// Decode a weight notification ([`parse_weight_frame`]) and note the
    /// v1.2 firmware a timestamped frame proves (decaid
    /// `DecentScaleProfile.fromEvidence`: a timestamped frame → `1.2`).
    pub fn parse_weight_frame(&mut self, data: &[u8]) -> Option<WeightFrame> {
        let frame = parse_weight_frame(data)?;
        if frame.timestamp_ms.is_some() {
            self.firmware_version = Some(DecentScaleFirmwareVersion::V1_2);
        }
        Some(frame)
    }

    /// Absorb a `0x0A` reply (see [`parse_command_response`]): record its
    /// firmware readings. A marker that doesn't decode never overwrites a
    /// version already proven by a timestamped weight frame.
    pub fn absorb_command_response(&mut self, response: CommandResponse) {
        match response {
            CommandResponse::LcdAck {
                firmware_version,
                hds_firmware,
                ..
            } => {
                if firmware_version != DecentScaleFirmwareVersion::Unknown
                    || self.firmware_version.is_none()
                {
                    self.firmware_version = Some(firmware_version);
                }
                if hds_firmware.is_some() {
                    self.hds_firmware = hds_firmware;
                }
            }
        }
    }

    /// Record the firmware version reported by a `0x0A` reply (see
    /// [`parse_command_response`]).
    pub fn record_firmware_version(&mut self, version: DecentScaleFirmwareVersion) {
        self.firmware_version = Some(version);
    }

    /// The HDS firmware triple, if a `0x0A` reply carried one.
    #[must_use]
    pub fn hds_firmware(&self) -> Option<HdsFirmwareVersion> {
        self.hds_firmware
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
        // Trailing byte = XOR of the first six (review #32).
        assert_eq!(
            parse_weight(&[0x03, 0xCE, 0x00, 0xC8, 0, 0, 0x05]),
            Some(20.0)
        );
        // A corrupted frame with a stale checksum is rejected, not decoded.
        assert_eq!(parse_weight(&[0x03, 0xCE, 0x00, 0xC8, 0, 0, 0x00]), None);
    }

    #[test]
    fn decodes_a_negative_weight() {
        // 0xFF38 as a signed 16-bit value is -200 -> -20.0 g.
        assert_eq!(
            parse_weight(&[0x03, 0xCA, 0xFF, 0x38, 0, 0, 0x0E]),
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

    /// decaid's v1.2 fixture (`decent_scale_profile_test.dart`, "parses
    /// original v1.2 timestamped weight frames"): 50.0 g at 1 min 2.3 s,
    /// XOR `0x38` in byte 9.
    const V12_FRAME: [u8; 10] = [0x03, 0xCE, 0x01, 0xF4, 0x01, 0x02, 0x03, 0x00, 0x00, 0x38];

    #[test]
    fn decodes_a_v12_ten_byte_timestamped_frame() {
        assert_eq!(
            parse_weight_frame(&V12_FRAME),
            Some(WeightFrame {
                weight_g: 50.0,
                timestamp_ms: Some(62_300),
            })
        );
        // The old rule — XOR of bytes 0-5 against byte 6 — fails on this
        // frame, which is why every v1.2 weight frame used to be dropped.
        assert_ne!(V12_FRAME[..6].iter().fold(0u8, |a, &b| a ^ b), V12_FRAME[6]);
        // A 7-byte frame carries no timestamp.
        assert_eq!(
            parse_weight_frame(&[0x03, 0xCE, 0x01, 0xF4, 0x00, 0x00, 0x38]),
            Some(WeightFrame {
                weight_g: 50.0,
                timestamp_ms: None,
            })
        );
    }

    #[test]
    fn rejects_a_corrupt_v12_frame() {
        // decaid "rejects corrupt capability evidence checksums".
        assert_eq!(
            parse_weight(&[0x03, 0xCE, 0x00, 0x64, 0, 0, 0, 0, 0, 0]),
            None
        );
        let mut corrupt = V12_FRAME;
        corrupt[3] ^= 0x10;
        assert_eq!(parse_weight(&corrupt), None);
    }

    #[test]
    fn decodes_weight_only_from_exact_seven_or_ten_byte_frames() {
        // decaid "weight frames require a verified command and complete
        // length": both types, both lengths, -10.0 g.
        for wtype in [0xCE, 0xCA] {
            for len in [7usize, 10] {
                let mut frame = vec![0x03, wtype, 0xFF, 0x9C];
                frame.resize(len - 1, 0);
                frame.push(xor_of_body(&[frame.clone(), vec![0]].concat()));
                assert_eq!(parse_weight(&frame), Some(-10.0), "{wtype:#x} len {len}");
            }
        }
        // Any other length is ignored — even with a trailing XOR that would
        // validate — as is a wrong header byte.
        for len in [6usize, 8, 9, 11, 12] {
            let mut frame = vec![0x03, 0xCE, 0x00, 0x64];
            frame.resize(len - 1, 0);
            frame.push(xor_of_body(&[frame.clone(), vec![0]].concat()));
            assert_eq!(parse_weight(&frame), None, "len {len}");
        }
        assert_eq!(parse_weight(&[0x04, 0xCE, 0x00, 0x64, 0, 0, 0xAE]), None);
    }

    #[test]
    fn a_timestamped_frame_proves_v12_firmware() {
        let mut state = DecentScale::new();
        assert_eq!(state.firmware_version(), None);
        state.parse_weight_frame(&[0x03, 0xCE, 0x01, 0xF4, 0x00, 0x00, 0x38]);
        assert_eq!(state.firmware_version(), None);
        assert_eq!(
            state.parse_weight_frame(&V12_FRAME).map(|f| f.weight_g),
            Some(50.0)
        );
        assert_eq!(
            state.firmware_version(),
            Some(DecentScaleFirmwareVersion::V1_2)
        );
        // A later reply whose marker is unknown does not erase that proof.
        state.absorb_command_response(
            parse_command_response(&[0x03, 0x0A, 0x01, 0x01, 0x50, 0xAA, 0x00]).unwrap(),
        );
        assert_eq!(
            state.firmware_version(),
            Some(DecentScaleFirmwareVersion::V1_2)
        );
    }

    #[test]
    fn tare_counter_rolls_once_per_logical_tare() {
        // decaid scale.dart:551-556: counter from 0, `& 0xFF`, frame
        // `03 0F <counter> 00 00 00 <xor>`.
        let mut state = DecentScale::new();
        assert_eq!(
            state.next_tare(),
            [0x03, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x0C]
        );
        assert_eq!(
            state.next_tare(),
            [0x03, 0x0F, 0x01, 0x00, 0x00, 0x00, 0x0D]
        );
        assert_eq!(
            state.next_tare(),
            [0x03, 0x0F, 0x02, 0x00, 0x00, 0x00, 0x0E]
        );
        for _ in 3..=255 {
            state.next_tare();
        }
        // After 0xFF the counter wraps to 0.
        assert_eq!(state.next_tare(), tare(0x00));
        // The first frame is the official HDS API doc's `030F000000000C`.
        assert_eq!(tare(0), [0x03, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x0C]);
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
        assert_eq!(
            LCD_ENABLE_OUNCES,
            [0x03, 0x0A, 0x01, 0x01, 0x01, 0x01, 0x09]
        );
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
    fn firmware_marker_table_matches_decaid() {
        // decaid profile.dart `_originalFirmwareVersions` (d5e2c482).
        use DecentScaleFirmwareVersion::{Unknown, V1_0, V1_1, V1_2};
        assert_eq!(DecentScaleFirmwareVersion::from_raw_byte(0xFE), V1_0);
        assert_eq!(DecentScaleFirmwareVersion::from_raw_byte(0x02), V1_1);
        assert_eq!(DecentScaleFirmwareVersion::from_raw_byte(0x03), V1_2);
        // The old 0x10/0x11/0x12 table matched no reference.
        for byte in [0x00, 0x01, 0x10, 0x11, 0x12, 0x20, 0xFF] {
            assert_eq!(DecentScaleFirmwareVersion::from_raw_byte(byte), Unknown);
        }
    }

    #[test]
    fn hds_firmware_decodes_bcd_major_and_nibble_minor_patch() {
        // decaid "decodes packed HDS firmware minor and patch nibbles" and
        // Decenza's `FW: 3.0.9` → `03 09` example.
        let v = |a, b| HdsFirmwareVersion::from_bcd(a, b).map(|v| v.to_string());
        assert_eq!(v(0x03, 0x1E).as_deref(), Some("3.1.14"));
        assert_eq!(v(0x02, 0x1D).as_deref(), Some("2.1.13"));
        assert_eq!(v(0x03, 0x09).as_deref(), Some("3.0.9"));
        assert_eq!(v(0x02, 0x58).as_deref(), Some("2.5.8"));
        // Not BCD, or a major above 30: not an HDS version.
        assert_eq!(v(0xA1, 0x00), None);
        assert_eq!(v(0x1F, 0x1D), None);
        assert_eq!(v(0xFE, 0x00), None);
        assert_eq!(v(0x31, 0x00), None);
    }

    #[test]
    fn parse_command_response_decodes_a_0x0a_lcd_ack() {
        // A 7-byte 0x0A reply with battery = 78% and marker 0x03 (v1.2).
        // Byte [4] is the battery, byte [5] the firmware marker (the
        // references' `data5` / `data6`).
        let frame = [0x03, 0x0A, 0x01, 0x01, 0x4E, 0x03, 0x4E];
        assert_eq!(
            parse_command_response(&frame),
            Some(CommandResponse::LcdAck {
                battery_raw: 0x4E,
                firmware_version: DecentScaleFirmwareVersion::V1_2,
                hds_firmware: HdsFirmwareVersion::from_bcd(0x03, 0x4E),
            })
        );
        // decaid's modern-HDS status fixture: 80 %, firmware 2.5.8.
        let hds = parse_command_response(&[0x03, 0x0A, 0x00, 0x00, 80, 0x02, 0x58]);
        let Some(CommandResponse::LcdAck {
            battery_raw,
            hds_firmware,
            ..
        }) = hds
        else {
            panic!("0x0A reply");
        };
        assert_eq!(battery_raw, 80);
        assert_eq!(
            hds_firmware.map(|v| v.to_string()).as_deref(),
            Some("2.5.8")
        );
        // Exactly seven bytes: a 10-byte 0x0A frame has no known layout.
        assert_eq!(
            parse_command_response(&[0x03, 0x0A, 0, 0, 80, 0x02, 0x58, 0, 0, 0]),
            None
        );
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
