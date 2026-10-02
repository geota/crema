//! Half Decent Scale (HDS) SoftSleep negotiation — decaid `f6c91efe`,
//! `38f5c0a8`, `46e8c224` (`decent_scale/profile.dart`, `scale.dart`).
//!
//! The Decent Scale family shares one service, so identity is *evidence*:
//!
//! - a `0x0A` status reply (`03 0A …`, 7 bytes — the answer to an LCD-enable
//!   or heartbeat write) proves a Decent Scale and carries the firmware bytes;
//! - only an answer to the voltage probe `22 00 00 00 00` (`03 22 …`, 7 bytes)
//!   proves an HDS. v2.5.8 introduced `0x22`; SoftSleep arrived later, so
//!   decaid grants SoftSleep only when HDS identity comes with a decoded
//!   firmware version of **major ≥ 3** (HDS before 3.0.1 reports no version).
//!
//! SoftSleep (`0A 04 01` enter, `0A 04 00` wake) keeps the BLE link and the
//! subscription alive with the display off, which is the point: it replaces
//! the disconnect / power-off that forced a reconnect (and Android GATT 133
//! churn) every time the DE1 slept. Scales without it keep crema's existing
//! LCD-off (`0A 00`, link retained) and opt-in power-off.
//!
//! The firmware major is decoded here from byte 5 of the `0x0A` reply (BCD,
//! decaid `DecentHdsFirmwareVersion.fromBcd`: both nibbles ≤ 9, major ≤ 30 so
//! an original scale's marker such as `0xFE` never reads as a version).
//! **Dependency:** PR #111 (`fix/upstream-scales`, not on this branch) adds
//! the full `decent_scale::HdsFirmwareVersion` decoder; once it lands,
//! [`hds_firmware_major`] should delegate to it. Until then this gate is
//! deliberately conservative — no decoded major, no SoftSleep.

/// Every Decent Scale frame opens with this byte.
const HEADER: u8 = 0x03;
/// The status / LCD reply opcode.
const STATUS_OPCODE: u8 = 0x0A;
/// The voltage-probe opcode.
const VOLTAGE_OPCODE: u8 = 0x22;
/// Every frame here is 7 bytes: header, opcode, 4 payload bytes, XOR.
const FRAME_LEN: usize = 7;

/// Lowest HDS firmware major that supports SoftSleep (decaid
/// `hdsFirmwareVersion.major >= 3`).
pub const SOFT_SLEEP_MIN_MAJOR: u8 = 3;

/// Command: the HDS voltage / capability probe, `22 00 00 00 00`.
pub const VOLTAGE_PROBE: [u8; FRAME_LEN] = [0x03, 0x22, 0x00, 0x00, 0x00, 0x00, 0x21];
/// Command: enter SoftSleep, `0A 04 01 00 00`.
pub const SOFT_SLEEP_ENTER: [u8; FRAME_LEN] = [0x03, 0x0A, 0x04, 0x01, 0x00, 0x00, 0x0C];
/// Command: wake from SoftSleep, `0A 04 00 00 00`.
pub const SOFT_SLEEP_EXIT: [u8; FRAME_LEN] = [0x03, 0x0A, 0x04, 0x00, 0x00, 0x00, 0x0D];

/// The HDS firmware major from a `0x0A` status reply, or `None` when `frame`
/// is not a 7-byte status reply or byte 5 is not a plausible BCD major.
#[must_use]
pub fn hds_firmware_major(frame: &[u8]) -> Option<u8> {
    if frame.len() != FRAME_LEN || frame[0] != HEADER || frame[1] != STATUS_OPCODE {
        return None;
    }
    let tens = frame[5] >> 4;
    let units = frame[5] & 0x0F;
    if tens > 9 || units > 9 {
        return None;
    }
    let major = tens * 10 + units;
    (major <= 30).then_some(major)
}

/// Whether `frame` is the 7-byte answer to [`VOLTAGE_PROBE`].
#[must_use]
pub fn is_voltage_reply(frame: &[u8]) -> bool {
    frame.len() == FRAME_LEN && frame[0] == HEADER && frame[1] == VOLTAGE_OPCODE
}

/// Per-connection negotiation and sleep state for a Decent Scale.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct HdsNegotiation {
    /// The voltage probe has been sent on this connection.
    probe_sent: bool,
    /// A voltage reply arrived — the scale is an HDS.
    voltage_accepted: bool,
    /// HDS firmware major decoded from the latest status reply.
    firmware_major: Option<u8>,
    /// The post-negotiation wake (`0A 04 00`) has been sent.
    wake_sent: bool,
    /// The scale was put into SoftSleep and not yet woken.
    soft_sleeping: bool,
}

impl HdsNegotiation {
    /// Fresh state for a newly-connected Decent Scale.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Absorb a non-weight notification; returns the writes it calls for.
    ///
    /// The first status reply records the firmware and sends the voltage
    /// probe (decaid `_negotiateProfile`: status evidence, then the probe).
    /// A voltage reply proves HDS; once SoftSleep is granted and the scale is
    /// awake, one `0A 04 00` makes sure it is not left in SoftSleep by a
    /// previous session (decaid `_exitSoftSleep` after negotiation).
    pub fn on_frame(&mut self, frame: &[u8]) -> Vec<[u8; FRAME_LEN]> {
        let mut writes = Vec::new();
        if frame.len() == FRAME_LEN && frame[0] == HEADER && frame[1] == STATUS_OPCODE {
            self.firmware_major = hds_firmware_major(frame);
            if !self.probe_sent {
                self.probe_sent = true;
                writes.push(VOLTAGE_PROBE);
            }
        } else if is_voltage_reply(frame) {
            self.voltage_accepted = true;
        }
        if self.supports_soft_sleep() && !self.wake_sent && !self.soft_sleeping {
            self.wake_sent = true;
            writes.push(SOFT_SLEEP_EXIT);
        }
        writes
    }

    /// SoftSleep is granted: HDS proven by the voltage reply AND firmware
    /// major ≥ [`SOFT_SLEEP_MIN_MAJOR`].
    #[must_use]
    pub fn supports_soft_sleep(&self) -> bool {
        self.voltage_accepted
            && self
                .firmware_major
                .is_some_and(|major| major >= SOFT_SLEEP_MIN_MAJOR)
    }

    /// Whether the scale is in SoftSleep (link kept, not streaming).
    #[must_use]
    pub fn is_soft_sleeping(&self) -> bool {
        self.soft_sleeping
    }

    /// The writes that put the scale to sleep when the DE1 sleeps:
    /// `Some([SOFT_SLEEP_ENTER])` when granted, else `None` (the caller keeps
    /// its LCD-off / power-off path).
    pub fn enter_sleep(&mut self) -> Option<Vec<[u8; FRAME_LEN]>> {
        if !self.supports_soft_sleep() {
            return None;
        }
        self.soft_sleeping = true;
        Some(vec![SOFT_SLEEP_ENTER])
    }

    /// The wake write after SoftSleep (`0A 04 00`; the caller follows with
    /// its LCD-enable, as decaid does). `None` when not soft-sleeping.
    pub fn wake(&mut self) -> Option<[u8; FRAME_LEN]> {
        if !self.soft_sleeping {
            return None;
        }
        self.soft_sleeping = false;
        Some(SOFT_SLEEP_EXIT)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn xor(frame: &[u8; FRAME_LEN]) -> u8 {
        frame[..FRAME_LEN - 1].iter().fold(0, |a, b| a ^ b)
    }

    /// A status reply carrying `fw` in bytes 5-6 (checksum not checked here).
    fn status(fw_major_bcd: u8, fw_minor_patch: u8) -> [u8; FRAME_LEN] {
        [0x03, 0x0A, 0x01, 0x01, 0x64, fw_major_bcd, fw_minor_patch]
    }

    const VOLTAGE: [u8; FRAME_LEN] = [0x03, 0x22, 0x00, 0x29, 0x00, 0x00, 0x08];

    #[test]
    fn frames_match_decaids_commands() {
        assert_eq!(&VOLTAGE_PROBE[..6], &[0x03, 0x22, 0x00, 0x00, 0x00, 0x00]);
        assert_eq!(
            &SOFT_SLEEP_ENTER[..6],
            &[0x03, 0x0A, 0x04, 0x01, 0x00, 0x00]
        );
        assert_eq!(&SOFT_SLEEP_EXIT[..6], &[0x03, 0x0A, 0x04, 0x00, 0x00, 0x00]);
        for frame in [VOLTAGE_PROBE, SOFT_SLEEP_ENTER, SOFT_SLEEP_EXIT] {
            assert_eq!(frame[6], xor(&frame));
        }
    }

    #[test]
    fn decodes_the_bcd_major_and_rejects_original_markers() {
        assert_eq!(hds_firmware_major(&status(0x03, 0x1E)), Some(3)); // 3.1.14
        assert_eq!(hds_firmware_major(&status(0x02, 0x58)), Some(2));
        assert_eq!(hds_firmware_major(&status(0x12, 0x00)), Some(12));
        assert_eq!(hds_firmware_major(&status(0xFE, 0x00)), None);
        assert_eq!(hds_firmware_major(&status(0x31, 0x00)), None); // > 30
        assert_eq!(hds_firmware_major(&status(0x03, 0x1E)[..6]), None);
    }

    #[test]
    fn firmware_3_hds_gets_soft_sleep_after_the_probe() {
        let mut hds = HdsNegotiation::new();
        assert_eq!(hds.on_frame(&status(0x03, 0x1E)), vec![VOLTAGE_PROBE]);
        assert!(!hds.supports_soft_sleep(), "HDS not proven yet");
        // The probe goes out once per connection.
        assert!(hds.on_frame(&status(0x03, 0x1E)).is_empty());
        assert_eq!(hds.on_frame(&VOLTAGE), vec![SOFT_SLEEP_EXIT]);
        assert!(hds.supports_soft_sleep());
        assert_eq!(hds.enter_sleep(), Some(vec![SOFT_SLEEP_ENTER]));
        assert!(hds.is_soft_sleeping());
        assert_eq!(hds.wake(), Some(SOFT_SLEEP_EXIT));
        assert_eq!(hds.wake(), None);
    }

    #[test]
    fn pre_3_hds_and_original_scales_get_no_soft_sleep() {
        // HDS 2.x: voltage reply, but major 2.
        let mut hds = HdsNegotiation::new();
        let _ = hds.on_frame(&status(0x02, 0x58));
        assert!(hds.on_frame(&VOLTAGE).is_empty());
        assert!(!hds.supports_soft_sleep());
        assert_eq!(hds.enter_sleep(), None);
        // Original v1.2 (marker 0x03 reads as major 3) never answers 0x22.
        let mut original = HdsNegotiation::new();
        let _ = original.on_frame(&status(0x03, 0x00));
        assert!(!original.supports_soft_sleep());
        assert_eq!(original.enter_sleep(), None);
        // Unknown scale, nothing heard.
        assert!(!HdsNegotiation::new().supports_soft_sleep());
    }
}
