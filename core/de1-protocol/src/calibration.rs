//! Sensor calibration packets (`Calibration` / `cuuid_12`).
//!
//! The DE1 lets the app calibrate its flow, pressure, and temperature sensors:
//! tell the machine "you reported X while the true value was Y" and it adjusts.
//! The same 14-byte packet reads the current or factory calibration, writes a
//! new one, or resets a sensor to factory.

use typeshare::typeshare;

use crate::error::ProtocolError;
use crate::fixed_point::{s32p16_decode, s32p16_encode};

/// Wire length of a [`Calibration`] packet.
pub const CALIBRATION_LEN: usize = 14;

/// `WriteKey` magic for a calibration write or factory reset.
const WRITE_KEY_WRITE: u32 = 0xCAFE_F00D;
/// `WriteKey` magic for a calibration read request.
const WRITE_KEY_READ: u32 = 1;

/// Which sensor a calibration applies to. Under the `wasm-bindgen`
/// feature this enum doubles as the wasm boundary type, and
/// `de1-ffi` registers it via `#[uniffi::remote(Enum)]` — one Rust
/// source of truth for typeshare JSON, the wasm-bindgen numeric ABI,
/// and uniffi.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
#[cfg_attr(feature = "wasm-bindgen", wasm_bindgen::prelude::wasm_bindgen)]
#[repr(u8)]
pub enum CalTarget {
    /// The flow-rate sensor.
    Flow = 0,
    /// The pressure sensor.
    Pressure = 1,
    /// The temperature sensor.
    Temperature = 2,
}

impl TryFrom<u8> for CalTarget {
    type Error = ProtocolError;

    /// # Errors
    ///
    /// [`ProtocolError::UnknownCalTarget`] if `v` is not `0..=2`.
    fn try_from(v: u8) -> Result<CalTarget, ProtocolError> {
        match v {
            0 => Ok(CalTarget::Flow),
            1 => Ok(CalTarget::Pressure),
            2 => Ok(CalTarget::Temperature),
            other => Err(ProtocolError::UnknownCalTarget(other)),
        }
    }
}

/// What a calibration packet asks the DE1 to do.
///
/// `#[non_exhaustive]` so any future firmware-side calibration verb can
/// land here without breaking the FFI surface — this enum is the payload
/// of `Event::Calibration` alongside `CalTarget`.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
#[repr(u8)]
#[non_exhaustive]
pub enum CalCommand {
    /// Read the calibration currently in use.
    ReadCurrent = 0,
    /// Write a new calibration.
    Write = 1,
    /// Reset the sensor to its factory calibration.
    ResetToFactory = 2,
    /// Read the factory calibration.
    ReadFactory = 3,
}

impl TryFrom<u8> for CalCommand {
    type Error = ProtocolError;

    /// # Errors
    ///
    /// [`ProtocolError::UnknownCalCommand`] if `v` is not `0..=3`.
    fn try_from(v: u8) -> Result<CalCommand, ProtocolError> {
        match v {
            0 => Ok(CalCommand::ReadCurrent),
            1 => Ok(CalCommand::Write),
            2 => Ok(CalCommand::ResetToFactory),
            3 => Ok(CalCommand::ReadFactory),
            other => Err(ProtocolError::UnknownCalCommand(other)),
        }
    }
}

/// A sensor-calibration packet (`cuuid_12`).
///
/// Build one with [`read_request`](Self::read_request),
/// [`read_factory_request`](Self::read_factory_request),
/// [`write`](Self::write), or [`reset_to_factory`](Self::reset_to_factory),
/// then [`encode`](Self::encode) it. The DE1 answers with a packet that
/// [`decode`](Self::decode) turns back into a `Calibration`.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Calibration {
    /// What the packet asks for.
    pub command: CalCommand,
    /// Which sensor it targets.
    pub target: CalTarget,
    /// The value the DE1 reported. Meaningful on a reply and on a
    /// [`CalCommand::Write`]; sent as `0.0` in a read request.
    pub de1_reported: f32,
    /// The externally-measured true value. Meaningful on a
    /// [`CalCommand::Write`]; sent as `0.0` otherwise.
    pub measured: f32,
}

impl Calibration {
    /// A request to read `target`'s current (in-use) calibration.
    pub fn read_request(target: CalTarget) -> Calibration {
        Calibration {
            command: CalCommand::ReadCurrent,
            target,
            de1_reported: 0.0,
            measured: 0.0,
        }
    }

    /// A request to read `target`'s factory calibration.
    pub fn read_factory_request(target: CalTarget) -> Calibration {
        Calibration {
            command: CalCommand::ReadFactory,
            target,
            de1_reported: 0.0,
            measured: 0.0,
        }
    }

    /// A request to reset `target` to its factory calibration.
    pub fn reset_to_factory(target: CalTarget) -> Calibration {
        Calibration {
            command: CalCommand::ResetToFactory,
            target,
            de1_reported: 0.0,
            measured: 0.0,
        }
    }

    /// A request to write a new calibration for `target`: the DE1 reported
    /// `de1_reported` while the true value was `measured`.
    pub fn write(target: CalTarget, de1_reported: f32, measured: f32) -> Calibration {
        Calibration {
            command: CalCommand::Write,
            target,
            de1_reported,
            measured,
        }
    }

    /// The `WriteKey` magic this packet uses — fixed by the command (a read
    /// magic for the read commands, a write magic for write and reset).
    fn write_key(self) -> u32 {
        match self.command {
            CalCommand::ReadCurrent | CalCommand::ReadFactory => WRITE_KEY_READ,
            CalCommand::Write | CalCommand::ResetToFactory => WRITE_KEY_WRITE,
        }
    }

    /// Encode to the 14-byte calibration packet.
    pub fn encode(&self) -> [u8; CALIBRATION_LEN] {
        let mut packet = [0u8; CALIBRATION_LEN];
        packet[0..4].copy_from_slice(&self.write_key().to_be_bytes());
        packet[4] = self.command as u8;
        packet[5] = self.target as u8;
        packet[6..10].copy_from_slice(&s32p16_encode(self.de1_reported).to_be_bytes());
        packet[10..14].copy_from_slice(&s32p16_encode(self.measured).to_be_bytes());
        packet
    }

    /// Decode a calibration packet. The `WriteKey` is informational on a
    /// reply and is not retained. Trailing bytes are ignored.
    ///
    /// # Errors
    ///
    /// [`ProtocolError::PacketTooShort`] if `data` is shorter than
    /// [`CALIBRATION_LEN`]; [`ProtocolError::UnknownCalCommand`] or
    /// [`ProtocolError::UnknownCalTarget`] on an unrecognised command/target.
    pub fn decode(data: &[u8]) -> Result<Calibration, ProtocolError> {
        if data.len() < CALIBRATION_LEN {
            return Err(ProtocolError::PacketTooShort {
                packet: "Calibration",
                expected: CALIBRATION_LEN,
                got: data.len(),
            });
        }
        Ok(Calibration {
            command: CalCommand::try_from(data[4])?,
            target: CalTarget::try_from(data[5])?,
            de1_reported: s32p16_decode(i32::from_be_bytes([data[6], data[7], data[8], data[9]])),
            measured: s32p16_decode(i32::from_be_bytes([data[10], data[11], data[12], data[13]])),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn read_request_uses_the_read_magic_key() {
        let packet = Calibration::read_request(CalTarget::Pressure).encode();
        assert_eq!(&packet[0..4], &1u32.to_be_bytes());
        assert_eq!(packet[4], 0); // ReadCurrent
        assert_eq!(packet[5], 1); // Pressure
    }

    #[test]
    fn write_uses_the_write_magic_key() {
        let packet = Calibration::write(CalTarget::Flow, 1.0, 1.05).encode();
        assert_eq!(&packet[0..4], &0xCAFE_F00Du32.to_be_bytes());
        assert_eq!(packet[4], 1); // Write
        assert_eq!(packet[5], 0); // Flow
    }

    #[test]
    fn write_encodes_to_the_exact_wire_bytes() {
        // A temperature-write: DE1 reported 92.5, true value 93.0.
        let packet = Calibration::write(CalTarget::Temperature, 92.5, 93.0).encode();
        assert_eq!(
            packet,
            [
                // WriteKey magic 0xCAFEF00D, big-endian.
                0xCA, 0xFE, 0xF0, 0x0D, //
                0x01, // command = Write
                0x02, // target = Temperature
                // de1_reported 92.5 as S32P16: 92.5 * 65536 = 0x005C8000.
                0x00, 0x5C, 0x80, 0x00, //
                // measured 93.0 as S32P16: 93.0 * 65536 = 0x005D0000.
                0x00, 0x5D, 0x00, 0x00,
            ]
        );
    }

    #[test]
    fn read_request_encodes_to_the_exact_wire_bytes() {
        // A read request carries the read magic and zeroed value fields.
        let packet = Calibration::read_request(CalTarget::Flow).encode();
        assert_eq!(
            packet,
            [
                0x00, 0x00, 0x00, 0x01, // WriteKey magic = 1 (read)
                0x00, // command = ReadCurrent
                0x00, // target = Flow
                0x00, 0x00, 0x00, 0x00, // de1_reported = 0.0
                0x00, 0x00, 0x00, 0x00, // measured = 0.0
            ]
        );
    }

    #[test]
    fn calibration_round_trips() {
        let cal = Calibration::write(CalTarget::Temperature, 92.5, 93.0);
        assert_eq!(Calibration::decode(&cal.encode()), Ok(cal));
    }

    #[test]
    fn reset_and_read_factory_round_trip() {
        for cal in [
            Calibration::reset_to_factory(CalTarget::Pressure),
            Calibration::read_factory_request(CalTarget::Flow),
        ] {
            assert_eq!(Calibration::decode(&cal.encode()), Ok(cal));
        }
    }

    #[test]
    fn decode_rejects_a_short_packet() {
        assert!(Calibration::decode(&[0u8; 13]).is_err());
    }

    #[test]
    fn decode_rejects_an_unknown_target() {
        let mut packet = Calibration::read_request(CalTarget::Flow).encode();
        packet[5] = 9;
        assert_eq!(
            Calibration::decode(&packet),
            Err(ProtocolError::UnknownCalTarget(9)),
        );
    }
}
