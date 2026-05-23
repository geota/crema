//! `ShotSample` telemetry packet (`cuuid_0D` / `A00D`).

use crate::error::ProtocolError;
use crate::fixed_point::{u8p0_decode, u8p4_decode, u16p8_decode, u24p16_decode};

/// Wire length of the current ("new BLE spec") [`ShotSample`] packet.
pub const SHOT_SAMPLE_LEN: usize = 19;

/// One decoded telemetry sample from the DE1, notified at ~4–10 Hz during a
/// shot (see protocol §3).
///
/// Temperatures are °C, pressure is bar, flow is ml/s. This type is `Clone` but
/// deliberately not `Copy`: it is large enough (a dozen fields) that implicit
/// copies are not a good default — pass it by reference.
#[derive(Debug, Clone, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct ShotSample {
    /// Free-running 16-bit AC half-cycle counter; wraps at 65536 (protocol §3.3).
    pub sample_time: u16,
    /// Measured group pressure, bar.
    pub group_pressure: f32,
    /// Measured group flow, ml/s.
    pub group_flow: f32,
    /// Measured group head temperature, °C.
    pub head_temp: f32,
    /// Measured mix temperature, °C.
    pub mix_temp: f32,
    /// Target mix temperature, °C.
    pub set_mix_temp: f32,
    /// Target head temperature, °C.
    pub set_head_temp: f32,
    /// Target group pressure, bar.
    pub set_group_pressure: f32,
    /// Target group flow, ml/s.
    pub set_group_flow: f32,
    /// Index of the profile frame currently executing.
    pub frame_number: u8,
    /// Steam heater temperature, °C.
    pub steam_temp: f32,
}

impl ShotSample {
    /// Parse a `ShotSample` notification (current 19-byte layout, big-endian).
    ///
    /// Trailing bytes are ignored. The legacy pre-1.0 firmware layout is not
    /// supported — Crema targets current firmware (see protocol §3.2).
    ///
    /// # Errors
    ///
    /// Returns [`ProtocolError::PacketTooShort`] if `data` has fewer than
    /// [`SHOT_SAMPLE_LEN`] bytes.
    ///
    /// # Examples
    ///
    /// ```
    /// # use de1_protocol::ShotSample;
    /// let packet = [
    ///     0x12, 0x34, 0x90, 0x00, 0x28, 0x00, 0x5C, 0x80, 0x00, 0x58,
    ///     0x00, 0x5A, 0x00, 0x5D, 0x00, 0x60, 0x40, 0x03, 0x96,
    /// ];
    /// let sample = ShotSample::parse(&packet).unwrap();
    /// assert_eq!(sample.group_pressure, 9.0);
    /// assert_eq!(sample.frame_number, 3);
    /// ```
    pub fn parse(data: &[u8]) -> Result<ShotSample, ProtocolError> {
        if data.len() < SHOT_SAMPLE_LEN {
            return Err(ProtocolError::PacketTooShort {
                packet: "ShotSample",
                expected: SHOT_SAMPLE_LEN,
                got: data.len(),
            });
        }
        let u16_be = |i: usize| u16::from_be_bytes([data[i], data[i + 1]]);
        // Byte layout per the legacy de1app TCL `spec` (de1_de1.tcl:712-726)
        // and verified against reaprime (unified_de1.parsing.dart:8-21). MixTemp
        // is a 2-byte Short at bytes 6-7; HeadTemp is a 3-byte 24-bit at
        // bytes 8-10. Pre-2026-05-22 Crema had these byte offsets swapped —
        // it read HeadTemp from bytes 6-8 and MixTemp from bytes 9-10,
        // producing corrupted temps. docs/22 §1.1 + docs/02 §3.2 carry the
        // fix history.
        Ok(ShotSample {
            sample_time: u16_be(0),
            // GroupPressure / GroupFlow are 16-bit values scaled by 4096 (4.12).
            group_pressure: u16_be(2) as f32 / 4096.0,
            group_flow: u16_be(4) as f32 / 4096.0,
            mix_temp: u16p8_decode(u16_be(6)),
            head_temp: u24p16_decode([data[8], data[9], data[10]]),
            set_mix_temp: u16p8_decode(u16_be(11)),
            set_head_temp: u16p8_decode(u16_be(13)),
            set_group_pressure: u8p4_decode(data[15]),
            set_group_flow: u8p4_decode(data[16]),
            frame_number: data[17],
            steam_temp: u8p0_decode(data[18]),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A 19-byte packet whose values were chosen so every field decodes
    /// exactly. Byte order matches the canonical layout from legacy de1app
    /// TCL `spec` and reaprime's parser — MixTemp before HeadTemp.
    fn sample_packet() -> [u8; SHOT_SAMPLE_LEN] {
        [
            0x12, 0x34, // bytes 0-1  · SampleTime    = 0x1234 = 4660
            0x90, 0x00, // bytes 2-3  · GroupPressure = 36864 / 4096 = 9.0 bar
            0x28, 0x00, // bytes 4-5  · GroupFlow     = 10240 / 4096 = 2.5 ml/s
            0x58, 0x00, // bytes 6-7  · MixTemp       = 22528 / 256  = 88.0 °C
            0x5C, 0x80, 0x00, // bytes 8-10 · HeadTemp = 92 + 128/256 = 92.5 °C
            0x5A, 0x00, // bytes 11-12 · SetMixTemp   = 23040 / 256 = 90.0 °C
            0x5D, 0x00, // bytes 13-14 · SetHeadTemp  = 23808 / 256 = 93.0 °C
            0x60, // byte 15 · SetGroupPressure = 96 / 16 = 6.0 bar
            0x40, // byte 16 · SetGroupFlow     = 64 / 16 = 4.0 ml/s
            0x03, // byte 17 · FrameNumber      = 3
            0x96, // byte 18 · SteamTemp        = 150 °C
        ]
    }

    mod parse {
        use super::*;

        #[test]
        fn decodes_every_field_of_a_valid_packet() {
            let s = ShotSample::parse(&sample_packet()).unwrap();
            assert_eq!(s.sample_time, 4660);
            assert_eq!(s.group_pressure, 9.0);
            assert_eq!(s.group_flow, 2.5);
            assert_eq!(s.head_temp, 92.5);
            assert_eq!(s.mix_temp, 88.0);
            assert_eq!(s.set_mix_temp, 90.0);
            assert_eq!(s.set_head_temp, 93.0);
            assert_eq!(s.set_group_pressure, 6.0);
            assert_eq!(s.set_group_flow, 4.0);
            assert_eq!(s.frame_number, 3);
            assert_eq!(s.steam_temp, 150.0);
        }

        #[test]
        fn ignores_bytes_past_the_fixed_layout() {
            let mut buf = sample_packet().to_vec();
            buf.push(0xFF);
            assert!(ShotSample::parse(&buf).is_ok());
        }

        #[test]
        fn rejects_a_packet_shorter_than_nineteen_bytes() {
            assert_eq!(
                ShotSample::parse(&[0; 7]),
                Err(ProtocolError::PacketTooShort {
                    packet: "ShotSample",
                    expected: 19,
                    got: 7,
                })
            );
        }
    }
}
