//! Firmware over-the-air (OTA) update codec.
//!
//! The DE1 accepts new CPU-board firmware over BLE in three steps: erase the
//! target firmware slot, stream the firmware image into it 16 bytes at a time,
//! then ask the machine which byte (if any) failed to verify. This module
//! provides the wire codecs for those steps:
//!
//! - [`FWMapRequest`] — the `FWMapRequest` characteristic packet (`cuuid_09`),
//!   used to erase a firmware slot, select the active slot, and request /
//!   receive the first-error address.
//! - [`firmware_write_frame`] — packs one 16-byte chunk of the firmware image
//!   into the 20-byte `WriteToMMR` packet (`cuuid_06`) the DE1 streams firmware
//!   through.
//!
//! ## Scope boundary
//!
//! This is **the codec only**. Loading the firmware image from disk, the
//! upload loop, retry/timeout policy, and progress reporting are the *shell's*
//! responsibility — the shell drives these codecs. The core, being sans-IO,
//! has no clock and no filesystem and so owns none of that orchestration.
//!
//! See `docs/02-ble-protocol.md` §6.2.

use crate::error::ProtocolError;
use crate::fixed_point::{u24p0_decode, u24p0_encode};

/// Wire length of a [`FWMapRequest`] packet (`maprequest_spec`).
pub const FW_MAP_REQUEST_LEN: usize = 7;

/// Wire length of a firmware-frame upload packet — a `WriteToMMR` packet.
pub const FIRMWARE_FRAME_LEN: usize = 20;

/// Number of firmware-image bytes carried by one [`firmware_write_frame`]
/// packet.
pub const FIRMWARE_FRAME_DATA_LEN: usize = 16;

/// `FirstError` sentinel the app writes to *request* the first-error address
/// from the DE1 (`de1_comms.tcl:487-489`).
pub const FIRST_ERROR_REQUEST: u32 = 0xFF_FFFF;

/// `FirstError` sentinel the DE1 replies with to mean "no error — the upload
/// verified" (`de1_comms.tcl:487-489`).
pub const FIRST_ERROR_NONE: u32 = 0xFF_FFFD;

/// A `FWMapRequest` packet (`cuuid_09` / `A009`).
///
/// The same 7-byte layout erases a firmware slot, selects the slot to map, and
/// carries the first-error address — both as a request from the app and as the
/// DE1's reply. Build one with [`erase`](Self::erase), [`map`](Self::map), or
/// [`request_first_error`](Self::request_first_error), then
/// [`encode`](Self::encode) it; decode the DE1's notification with
/// [`decode`](Self::decode) and inspect [`first_error`](Self::first_error).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FWMapRequest {
    /// Auto-increment window size for the mapping operation.
    pub window_increment: u16,
    /// Firmware slot to erase (`1` = erase, `0` = do not erase).
    pub fw_to_erase: u8,
    /// Firmware slot to map (make active).
    pub fw_to_map: u8,
    /// First-error address — a 24-bit value carried big-endian across the
    /// `FirstError1..3` bytes. [`FIRST_ERROR_REQUEST`] when the app is asking;
    /// [`FIRST_ERROR_NONE`] in a reply that verified; otherwise the byte
    /// offset into the image that failed to verify.
    pub first_error: u32,
}

impl FWMapRequest {
    /// A request to erase firmware slot `slot` before an upload.
    pub fn erase(slot: u8) -> FWMapRequest {
        FWMapRequest {
            window_increment: 0,
            fw_to_erase: slot,
            fw_to_map: 0,
            first_error: FIRST_ERROR_REQUEST,
        }
    }

    /// A request to map (make active) firmware slot `slot` after an upload.
    pub fn map(slot: u8) -> FWMapRequest {
        FWMapRequest {
            window_increment: 0,
            fw_to_erase: 0,
            fw_to_map: slot,
            first_error: FIRST_ERROR_REQUEST,
        }
    }

    /// A request asking the DE1 for the first byte that failed to verify after
    /// an upload. The DE1 replies on the same characteristic; decode it with
    /// [`decode`](Self::decode) and read [`first_error`](Self::first_error).
    pub fn request_first_error() -> FWMapRequest {
        FWMapRequest {
            window_increment: 0,
            fw_to_erase: 0,
            fw_to_map: 0,
            first_error: FIRST_ERROR_REQUEST,
        }
    }

    /// `true` if this packet's [`first_error`](Self::first_error) is the
    /// "no error — upload verified" sentinel ([`FIRST_ERROR_NONE`]).
    pub fn verified(self) -> bool {
        self.first_error == FIRST_ERROR_NONE
    }

    /// Encode to the 7-byte `FWMapRequest` packet.
    ///
    /// `window_increment` is big-endian; `first_error` occupies the three
    /// `FirstError1..3` bytes, big-endian, and is masked to 24 bits.
    pub fn encode(&self) -> [u8; FW_MAP_REQUEST_LEN] {
        let mut packet = [0u8; FW_MAP_REQUEST_LEN];
        packet[0..2].copy_from_slice(&self.window_increment.to_be_bytes());
        packet[2] = self.fw_to_erase;
        packet[3] = self.fw_to_map;
        packet[4..7].copy_from_slice(&u24p0_encode(self.first_error & 0xFF_FFFF));
        packet
    }

    /// Decode a `FWMapRequest` packet. Trailing bytes are ignored.
    ///
    /// # Errors
    ///
    /// [`ProtocolError::PacketTooShort`] if `data` is shorter than
    /// [`FW_MAP_REQUEST_LEN`].
    pub fn decode(data: &[u8]) -> Result<FWMapRequest, ProtocolError> {
        if data.len() < FW_MAP_REQUEST_LEN {
            return Err(ProtocolError::PacketTooShort {
                packet: "FWMapRequest",
                expected: FW_MAP_REQUEST_LEN,
                got: data.len(),
            });
        }
        Ok(FWMapRequest {
            window_increment: u16::from_be_bytes([data[0], data[1]]),
            fw_to_erase: data[2],
            fw_to_map: data[3],
            first_error: u24p0_decode([data[4], data[5], data[6]]),
        })
    }
}

/// Pack one chunk of a firmware image into a 20-byte `WriteToMMR` packet
/// (`cuuid_06`) for upload.
///
/// The DE1 streams firmware through the memory-mapped-register write
/// characteristic: each packet is a length byte (always `0x10` = 16, the chunk
/// size), the 24-bit big-endian byte `offset` into the firmware image, then up
/// to [`FIRMWARE_FRAME_DATA_LEN`] image bytes (zero-padded if `chunk` is
/// shorter, truncated if longer).
///
/// The shell is responsible for slicing the image into 16-byte chunks and
/// advancing `offset` by 16 each step; this function only encodes one packet.
///
/// # Errors
///
/// [`ProtocolError::FirmwareOffsetOutOfRange`] if `offset` does not fit in the
/// 24-bit address field.
pub fn firmware_write_frame(
    offset: u32,
    chunk: &[u8],
) -> Result<[u8; FIRMWARE_FRAME_LEN], ProtocolError> {
    if offset > 0xFF_FFFF {
        return Err(ProtocolError::FirmwareOffsetOutOfRange(offset));
    }
    let mut packet = [0u8; FIRMWARE_FRAME_LEN];
    packet[0] = FIRMWARE_FRAME_DATA_LEN as u8;
    packet[1..4].copy_from_slice(&u24p0_encode(offset));
    let len = chunk.len().min(FIRMWARE_FRAME_DATA_LEN);
    packet[4..4 + len].copy_from_slice(&chunk[..len]);
    Ok(packet)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn erase_request_sets_the_erase_slot() {
        let packet = FWMapRequest::erase(1).encode();
        assert_eq!(&packet[0..2], &[0, 0]); // WindowIncrement
        assert_eq!(packet[2], 1); // FWToErase
        assert_eq!(packet[3], 0); // FWToMap
        assert_eq!(&packet[4..7], &[0xFF, 0xFF, 0xFF]); // FirstError request
    }

    #[test]
    fn map_request_sets_the_map_slot() {
        let packet = FWMapRequest::map(1).encode();
        assert_eq!(packet[2], 0); // FWToErase
        assert_eq!(packet[3], 1); // FWToMap
    }

    #[test]
    fn fw_map_request_round_trips() {
        let req = FWMapRequest {
            window_increment: 0x1234,
            fw_to_erase: 1,
            fw_to_map: 2,
            first_error: 0x00_ABCD,
        };
        assert_eq!(FWMapRequest::decode(&req.encode()), Ok(req));
    }

    #[test]
    fn request_first_error_uses_the_request_sentinel() {
        let req = FWMapRequest::request_first_error();
        assert_eq!(req.first_error, FIRST_ERROR_REQUEST);
        assert!(!req.verified());
    }

    #[test]
    fn verified_reply_is_recognised() {
        // A "no error" reply: FirstError = FF FF FD.
        let mut packet = FWMapRequest::request_first_error().encode();
        packet[4..7].copy_from_slice(&[0xFF, 0xFF, 0xFD]);
        let reply = FWMapRequest::decode(&packet).unwrap();
        assert_eq!(reply.first_error, FIRST_ERROR_NONE);
        assert!(reply.verified());
    }

    #[test]
    fn first_error_is_masked_to_24_bits_on_encode() {
        let req = FWMapRequest {
            window_increment: 0,
            fw_to_erase: 0,
            fw_to_map: 0,
            first_error: 0xAB12_3456,
        };
        // Only the low 24 bits reach the wire.
        assert_eq!(&req.encode()[4..7], &[0x12, 0x34, 0x56]);
    }

    #[test]
    fn fw_map_request_rejects_a_short_packet() {
        assert!(FWMapRequest::decode(&[0u8; 6]).is_err());
        assert_eq!(
            FWMapRequest::decode(&[0u8; 3]),
            Err(ProtocolError::PacketTooShort {
                packet: "FWMapRequest",
                expected: FW_MAP_REQUEST_LEN,
                got: 3,
            }),
        );
    }

    #[test]
    fn firmware_write_frame_encodes_len_offset_and_data() {
        let chunk = [0xAA; FIRMWARE_FRAME_DATA_LEN];
        let packet = firmware_write_frame(0x00_1230, &chunk).unwrap();
        assert_eq!(packet[0], 0x10); // length byte: always 16
        assert_eq!(&packet[1..4], &[0x00, 0x12, 0x30]); // big-endian offset
        assert_eq!(&packet[4..20], &chunk);
    }

    #[test]
    fn firmware_write_frame_zero_pads_a_short_final_chunk() {
        let packet = firmware_write_frame(0, &[1, 2, 3]).unwrap();
        assert_eq!(&packet[4..7], &[1, 2, 3]);
        assert_eq!(&packet[7..20], &[0u8; 13]);
    }

    #[test]
    fn firmware_write_frame_truncates_an_oversized_chunk() {
        let packet = firmware_write_frame(0, &[0xFF; 32]).unwrap();
        assert_eq!(&packet[4..20], &[0xFF; FIRMWARE_FRAME_DATA_LEN]);
    }

    #[test]
    fn firmware_write_frame_rejects_an_out_of_range_offset() {
        assert_eq!(
            firmware_write_frame(0x0100_0000, &[]),
            Err(ProtocolError::FirmwareOffsetOutOfRange(0x0100_0000)),
        );
        // The largest 24-bit offset is accepted.
        assert!(firmware_write_frame(0xFF_FFFF, &[]).is_ok());
    }
}
