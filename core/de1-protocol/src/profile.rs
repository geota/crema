//! Espresso profile upload codec — header and shot frames.
//!
//! A profile is uploaded to the DE1 as one [`ShotHeader`] (to `HeaderWrite` /
//! `cuuid_0F`) followed by N frame packets (to `FrameWrite` / `cuuid_10`):
//! [`ShotFrame`]s (normal), optional [`ExtensionFrame`]s (advanced max-flow /
//! max-pressure limiting), and a final [`ShotTail`]. See `docs/02-ble-protocol.md` §5.
//!
//! This module is the **wire codec only**. The higher-level profile *model*
//! (recipe, metadata, JSON import) belongs in the domain layer.

use crate::error::ProtocolError;
use crate::fixed_point::{
    f8_1_7_decode, f8_1_7_encode, u8p1_decode, u8p1_encode, u8p4_decode, u8p4_encode, u10p0_decode,
    u10p0_encode,
};

/// Header version byte — always 1 in current firmware.
const HEADER_VERSION: u8 = 1;
/// A `FrameToWrite` byte at or above this value marks an extension frame.
const EXTENSION_FRAME_OFFSET: u8 = 32;

/// Wire length of a [`ShotHeader`] packet.
pub const SHOT_HEADER_LEN: usize = 5;
/// Wire length of any shot-frame packet ([`ShotFrame`] / [`ExtensionFrame`] / [`ShotTail`]).
pub const SHOT_FRAME_LEN: usize = 8;

/// Whether an 8-byte frame packet is an [`ExtensionFrame`] (vs. a [`ShotFrame`]
/// or [`ShotTail`]), determined by its `FrameToWrite` byte. Returns `false` for
/// an empty slice.
pub fn is_extension_frame(data: &[u8]) -> bool {
    data.first().is_some_and(|&b| b >= EXTENSION_FRAME_OFFSET)
}

/// The `FrameToWrite` byte from a `FrameWrite` echo / write-response, used to
/// match acks during a profile upload (`cuuid_10`).
///
/// The DE1 echoes each frame write back as a notification carrying the same
/// 8-byte payload; the first byte (`FrameToWrite`) identifies which frame was
/// acknowledged: `0..32` for a normal-frame ack or for the tail ack
/// (`FrameToWrite == frame_count`); `≥ 32` for an extension-frame ack
/// (`index = byte - 32`). Returns `None` for an empty slice; trailing bytes
/// are ignored.
///
/// See `docs/16-profile-upload-plan.md` §6 for the legacy reference (the Tcl
/// `parse_binary_shotframe` echo path).
pub fn ack_frame_byte(data: &[u8]) -> Option<u8> {
    data.first().copied()
}

/// The numeric offset added to an [`ExtensionFrame`]'s step index to produce
/// its `FrameToWrite` byte on the wire. Exposed so the orchestrator's ack
/// matcher can build the expected-ack sequence without re-deriving the magic
/// constant.
pub const EXTENSION_FRAME_INDEX_OFFSET: u8 = EXTENSION_FRAME_OFFSET;

/// The per-frame flag bits (`T_E_FrameFlags`). See protocol §5.4.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct FrameFlags {
    /// `CtrlF` — flow priority (`true`) vs. pressure priority (`false`).
    pub flow_priority: bool,
    /// `DoCompare` — enable this frame's early-exit comparison.
    pub do_compare: bool,
    /// `DC_GT` — comparison direction: exit when `>` (`true`) vs. `<` (`false`).
    pub compare_greater: bool,
    /// `DC_CompF` — compare against flow (`true`) vs. pressure (`false`).
    pub compare_flow: bool,
    /// `TMixTemp` — target the mix temperature instead of the basket temperature.
    pub target_mix_temp: bool,
    /// `Interpolate` — ramp smoothly to the target instead of jumping.
    pub interpolate: bool,
    /// `IgnoreLimit` — ignore the header's min-pressure / max-flow limits.
    pub ignore_limit: bool,
}

impl FrameFlags {
    /// Decode the frame flag byte.
    pub fn from_byte(byte: u8) -> FrameFlags {
        FrameFlags {
            flow_priority: byte & 0x01 != 0,
            do_compare: byte & 0x02 != 0,
            compare_greater: byte & 0x04 != 0,
            compare_flow: byte & 0x08 != 0,
            target_mix_temp: byte & 0x10 != 0,
            interpolate: byte & 0x20 != 0,
            ignore_limit: byte & 0x40 != 0,
        }
    }

    /// Encode to the frame flag byte.
    pub fn to_byte(self) -> u8 {
        u8::from(self.flow_priority)
            | (u8::from(self.do_compare) << 1)
            | (u8::from(self.compare_greater) << 2)
            | (u8::from(self.compare_flow) << 3)
            | (u8::from(self.target_mix_temp) << 4)
            | (u8::from(self.interpolate) << 5)
            | (u8::from(self.ignore_limit) << 6)
    }
}

/// The 5-byte profile header (`HeaderWrite` / `cuuid_0F`).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ShotHeader {
    /// Total number of frames in the profile.
    pub frame_count: u8,
    /// How many leading frames count as preinfusion.
    pub preinfuse_frame_count: u8,
    /// Minimum pressure allowed in flow-priority frames, bar.
    pub minimum_pressure: f32,
    /// Maximum flow allowed in pressure-priority frames, mL/s.
    pub maximum_flow: f32,
}

impl ShotHeader {
    /// Encode to the 5-byte `HeaderWrite` packet.
    pub fn encode(&self) -> [u8; SHOT_HEADER_LEN] {
        [
            HEADER_VERSION,
            self.frame_count,
            self.preinfuse_frame_count,
            u8p4_encode(self.minimum_pressure),
            u8p4_encode(self.maximum_flow),
        ]
    }

    /// Decode a `HeaderWrite` packet. Trailing bytes are ignored.
    ///
    /// # Errors
    ///
    /// [`ProtocolError::PacketTooShort`] if `data` has fewer than
    /// [`SHOT_HEADER_LEN`] bytes.
    pub fn decode(data: &[u8]) -> Result<ShotHeader, ProtocolError> {
        if data.len() < SHOT_HEADER_LEN {
            return Err(ProtocolError::PacketTooShort {
                packet: "ShotHeader",
                expected: SHOT_HEADER_LEN,
                got: data.len(),
            });
        }
        Ok(ShotHeader {
            frame_count: data[1],
            preinfuse_frame_count: data[2],
            minimum_pressure: u8p4_decode(data[3]),
            maximum_flow: u8p4_decode(data[4]),
        })
    }
}

/// A normal espresso shot frame (`FrameWrite` / `cuuid_10`).
#[derive(Debug, Clone, PartialEq)]
pub struct ShotFrame {
    /// Frame index (0-based).
    pub index: u8,
    /// Frame control flags.
    pub flags: FrameFlags,
    /// Target pressure (bar) or flow (mL/s), per [`FrameFlags::flow_priority`].
    pub set_value: f32,
    /// Target temperature, °C.
    pub temperature: f32,
    /// Frame duration, seconds.
    pub duration_seconds: f32,
    /// Early-exit comparison threshold (pressure or flow, per the flags).
    pub trigger_value: f32,
    /// Per-frame dispensed-volume limit, mL, range 0–1023 (0 = no limit).
    pub max_volume_ml: u16,
}

impl ShotFrame {
    /// Encode to the 8-byte normal-frame `FrameWrite` packet.
    pub fn encode(&self) -> [u8; SHOT_FRAME_LEN] {
        let max_vol = u10p0_encode(f32::from(self.max_volume_ml)).to_be_bytes();
        [
            self.index,
            self.flags.to_byte(),
            u8p4_encode(self.set_value),
            u8p1_encode(self.temperature),
            f8_1_7_encode(self.duration_seconds),
            u8p4_encode(self.trigger_value),
            max_vol[0],
            max_vol[1],
        ]
    }

    /// Decode an 8-byte normal-frame `FrameWrite` packet. Trailing bytes ignored.
    ///
    /// # Errors
    ///
    /// [`ProtocolError::PacketTooShort`] if `data` has fewer than
    /// [`SHOT_FRAME_LEN`] bytes.
    pub fn decode(data: &[u8]) -> Result<ShotFrame, ProtocolError> {
        require_frame_len(data, "ShotFrame")?;
        Ok(ShotFrame {
            index: data[0],
            flags: FrameFlags::from_byte(data[1]),
            set_value: u8p4_decode(data[2]),
            temperature: u8p1_decode(data[3]),
            duration_seconds: f8_1_7_decode(data[4]),
            trigger_value: u8p4_decode(data[5]),
            max_volume_ml: u10p0_decode(u16::from_be_bytes([data[6], data[7]])),
        })
    }
}

/// An extension frame — "advanced" max-flow / max-pressure limiting (§5.3).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ExtensionFrame {
    /// Index of the frame this extends (0-based; encoded on the wire as `index + 32`).
    pub index: u8,
    /// Limit value — flow if the frame is pressure-priority, or vice versa.
    pub max_flow_or_pressure: f32,
    /// Tolerance band around the limit.
    pub max_fop_range: f32,
}

impl ExtensionFrame {
    /// Encode to the 8-byte extension-frame `FrameWrite` packet.
    pub fn encode(&self) -> [u8; SHOT_FRAME_LEN] {
        [
            self.index.wrapping_add(EXTENSION_FRAME_OFFSET),
            u8p4_encode(self.max_flow_or_pressure),
            u8p4_encode(self.max_fop_range),
            0,
            0,
            0,
            0,
            0,
        ]
    }

    /// Decode an 8-byte extension-frame `FrameWrite` packet. Trailing bytes ignored.
    ///
    /// # Errors
    ///
    /// [`ProtocolError::PacketTooShort`] if `data` has fewer than
    /// [`SHOT_FRAME_LEN`] bytes.
    pub fn decode(data: &[u8]) -> Result<ExtensionFrame, ProtocolError> {
        require_frame_len(data, "ExtensionFrame")?;
        Ok(ExtensionFrame {
            index: data[0].wrapping_sub(EXTENSION_FRAME_OFFSET),
            max_flow_or_pressure: u8p4_decode(data[1]),
            max_fop_range: u8p4_decode(data[2]),
        })
    }
}

/// The tail frame, appended after all normal and extension frames (§5.5).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ShotTail {
    /// `FrameToWrite` for the tail — equal to the profile's frame count.
    pub frame_count: u8,
    /// Whole-shot dispensed-volume limit, mL, range 0–1023 (0 = no limit).
    pub max_total_volume_ml: u16,
}

impl ShotTail {
    /// Encode to the 8-byte tail `FrameWrite` packet.
    pub fn encode(&self) -> [u8; SHOT_FRAME_LEN] {
        let max_vol = u10p0_encode(f32::from(self.max_total_volume_ml)).to_be_bytes();
        [self.frame_count, max_vol[0], max_vol[1], 0, 0, 0, 0, 0]
    }

    /// Decode an 8-byte tail `FrameWrite` packet. Trailing bytes ignored.
    ///
    /// # Errors
    ///
    /// [`ProtocolError::PacketTooShort`] if `data` has fewer than
    /// [`SHOT_FRAME_LEN`] bytes.
    pub fn decode(data: &[u8]) -> Result<ShotTail, ProtocolError> {
        require_frame_len(data, "ShotTail")?;
        Ok(ShotTail {
            frame_count: data[0],
            max_total_volume_ml: u10p0_decode(u16::from_be_bytes([data[1], data[2]])),
        })
    }
}

/// Reject a frame packet shorter than the fixed 8-byte layout.
fn require_frame_len(data: &[u8], packet: &'static str) -> Result<(), ProtocolError> {
    if data.len() < SHOT_FRAME_LEN {
        Err(ProtocolError::PacketTooShort {
            packet,
            expected: SHOT_FRAME_LEN,
            got: data.len(),
        })
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    mod frame_flags {
        use super::*;

        #[test]
        fn decodes_individual_bits() {
            // 0x41 = IgnoreLimit (0x40) | CtrlF (0x01).
            let flags = FrameFlags::from_byte(0x41);
            assert!(flags.flow_priority);
            assert!(flags.ignore_limit);
            assert!(!flags.do_compare);
            assert!(!flags.interpolate);
        }

        #[test]
        fn round_trips_through_a_byte() {
            for byte in 0..=0x7Fu8 {
                assert_eq!(FrameFlags::from_byte(byte).to_byte(), byte);
            }
        }

        #[test]
        fn default_is_all_clear() {
            assert_eq!(FrameFlags::default().to_byte(), 0);
        }
    }

    mod shot_header {
        use super::*;

        #[test]
        fn encodes_to_the_expected_bytes() {
            let header = ShotHeader {
                frame_count: 4,
                preinfuse_frame_count: 2,
                minimum_pressure: 1.0,
                maximum_flow: 6.0,
            };
            // [HeaderV=1, frames, preinfuse, 1.0*16=0x10, 6.0*16=0x60]
            assert_eq!(header.encode(), [1, 4, 2, 0x10, 0x60]);
        }

        #[test]
        fn round_trips_through_encode_decode() {
            let header = ShotHeader {
                frame_count: 8,
                preinfuse_frame_count: 1,
                minimum_pressure: 2.0,
                maximum_flow: 8.0,
            };
            assert_eq!(ShotHeader::decode(&header.encode()), Ok(header));
        }

        #[test]
        fn rejects_a_short_packet() {
            assert!(ShotHeader::decode(&[1, 4, 2, 0x10]).is_err());
        }
    }

    mod shot_frame {
        use super::*;

        #[test]
        fn encodes_to_the_expected_bytes() {
            let frame = ShotFrame {
                index: 0,
                flags: FrameFlags {
                    flow_priority: true,
                    ignore_limit: true,
                    ..Default::default()
                },
                set_value: 9.0,
                temperature: 92.0,
                duration_seconds: 25.0,
                trigger_value: 4.0,
                max_volume_ml: 100,
            };
            // flags 0x41; 9.0->0x90; 92.0*2=0xB8; 25s F8_1_7 high regime 25|0x80=0x99;
            // 4.0->0x40; MaxVol 100|0x400=0x0464.
            assert_eq!(
                frame.encode(),
                [0, 0x41, 0x90, 0xB8, 0x99, 0x40, 0x04, 0x64]
            );
        }

        #[test]
        fn round_trips_through_encode_decode() {
            let frame = ShotFrame {
                index: 3,
                flags: FrameFlags {
                    do_compare: true,
                    compare_greater: true,
                    ..Default::default()
                },
                set_value: 6.0,
                temperature: 90.0,
                duration_seconds: 4.0,
                trigger_value: 2.0,
                max_volume_ml: 0,
            };
            assert_eq!(ShotFrame::decode(&frame.encode()), Ok(frame));
        }

        #[test]
        fn rejects_a_short_packet() {
            assert!(ShotFrame::decode(&[0; 7]).is_err());
        }
    }

    mod extension_frame {
        use super::*;

        #[test]
        fn encodes_the_index_with_the_offset() {
            let ext = ExtensionFrame {
                index: 3,
                max_flow_or_pressure: 8.0,
                max_fop_range: 0.5,
            };
            let bytes = ext.encode();
            assert_eq!(bytes[0], 3 + 32); // FrameToWrite = index + 32
            assert_eq!(&bytes[3..], &[0, 0, 0, 0, 0]); // padding
        }

        #[test]
        fn round_trips_through_encode_decode() {
            let ext = ExtensionFrame {
                index: 5,
                max_flow_or_pressure: 7.0,
                max_fop_range: 0.625,
            };
            assert_eq!(ExtensionFrame::decode(&ext.encode()), Ok(ext));
        }
    }

    mod shot_tail {
        use super::*;

        #[test]
        fn round_trips_through_encode_decode() {
            let tail = ShotTail {
                frame_count: 4,
                max_total_volume_ml: 200,
            };
            assert_eq!(ShotTail::decode(&tail.encode()), Ok(tail));
        }

        #[test]
        fn encodes_with_max_frame_count() {
            // 32 is the protocol's maximum step count; the tail's
            // `FrameToWrite` equals that count and must still encode as a
            // tail (i.e. < 32 + 32 is not relevant; the dispatcher in
            // legacy `parse_binary_shotframe` treats `FrameToWrite < 32`
            // as a normal frame, so the tail at exactly 32 would be
            // indistinguishable from an extension. Confirm the byte is
            // emitted as `32` regardless.)
            let tail = ShotTail {
                frame_count: 32,
                max_total_volume_ml: 0,
            };
            assert_eq!(tail.encode()[0], 32);
        }
    }

    mod ack_frame_byte_fn {
        use super::*;

        #[test]
        fn returns_none_for_empty() {
            assert_eq!(ack_frame_byte(&[]), None);
        }

        #[test]
        fn returns_first_byte_for_one_byte_slice() {
            assert_eq!(ack_frame_byte(&[7]), Some(7));
        }

        #[test]
        fn returns_first_byte_of_an_eight_byte_payload() {
            let payload = [33, 0, 0, 0, 0, 0, 0, 0];
            assert_eq!(ack_frame_byte(&payload), Some(33));
        }

        #[test]
        fn extension_frame_index_offset_matches_internal_const() {
            // The public constant is what the orchestrator imports; this
            // pins it to the same value `is_extension_frame` /
            // `ExtensionFrame::encode` use internally.
            assert_eq!(EXTENSION_FRAME_INDEX_OFFSET, 32);
        }
    }

    mod frame_flags_per_bit {
        use super::*;

        fn flags(bit_setter: impl FnOnce(&mut FrameFlags)) -> FrameFlags {
            let mut f = FrameFlags::default();
            bit_setter(&mut f);
            f
        }

        #[test]
        fn ctrl_f_is_bit_0() {
            assert_eq!(flags(|f| f.flow_priority = true).to_byte(), 0x01);
        }

        #[test]
        fn do_compare_is_bit_1() {
            assert_eq!(flags(|f| f.do_compare = true).to_byte(), 0x02);
        }

        #[test]
        fn compare_greater_is_bit_2() {
            assert_eq!(flags(|f| f.compare_greater = true).to_byte(), 0x04);
        }

        #[test]
        fn compare_flow_is_bit_3() {
            assert_eq!(flags(|f| f.compare_flow = true).to_byte(), 0x08);
        }

        #[test]
        fn target_mix_temp_is_bit_4() {
            assert_eq!(flags(|f| f.target_mix_temp = true).to_byte(), 0x10);
        }

        #[test]
        fn interpolate_is_bit_5() {
            assert_eq!(flags(|f| f.interpolate = true).to_byte(), 0x20);
        }

        #[test]
        fn ignore_limit_is_bit_6() {
            assert_eq!(flags(|f| f.ignore_limit = true).to_byte(), 0x40);
        }
    }

    mod extension_frame_boundaries {
        use super::*;

        #[test]
        fn index_zero_encodes_as_byte_32() {
            let ext = ExtensionFrame {
                index: 0,
                max_flow_or_pressure: 8.0,
                max_fop_range: 0.5,
            };
            assert_eq!(ext.encode()[0], 32);
        }

        #[test]
        fn index_31_encodes_as_byte_63() {
            let ext = ExtensionFrame {
                index: 31,
                max_flow_or_pressure: 8.0,
                max_fop_range: 0.5,
            };
            assert_eq!(ext.encode()[0], 63);
        }
    }

    #[test]
    fn extension_frames_are_distinguished_by_the_frame_byte() {
        let normal = ShotFrame {
            index: 2,
            flags: FrameFlags::default(),
            set_value: 6.0,
            temperature: 90.0,
            duration_seconds: 5.0,
            trigger_value: 0.0,
            max_volume_ml: 0,
        };
        let extension = ExtensionFrame {
            index: 2,
            max_flow_or_pressure: 8.0,
            max_fop_range: 0.5,
        };
        assert!(!is_extension_frame(&normal.encode()));
        assert!(is_extension_frame(&extension.encode()));
        assert!(!is_extension_frame(&[]));
    }
}
