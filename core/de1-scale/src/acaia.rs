//! Acaia scale codec — gen1/IPS and Pyxis (`acaiascale` / `acaiapyxis`).
//!
//! Acaia uses a proprietary **framed protocol**: a message is delimited by an
//! `EF DD` header and a single weight frame may span several BLE
//! notifications. Decoding is therefore stateful — feed every notification to
//! an [`AcaiaDecoder`]. See `docs/06-scale-protocols.md` §5–6.
//!
//! gen1 and Pyxis share this protocol; they differ only in their UUIDs (and,
//! in the shell, their connection handshake).

// Raw integer weight fields are decoded into `f32` grams; precision loss past
// 2^23 is inherent to representing a wire reading as the codec's `f32` weight,
// not a defect, so the precision-loss lint is allowed module-wide here.
#![allow(clippy::cast_precision_loss)]

// --- gen1 / IPS UUIDs ---

/// gen1: GATT service UUID.
pub const GEN1_SERVICE_UUID: &str = "00001820-0000-1000-8000-00805f9b34fb";
/// gen1: characteristic used for both notifications and command writes.
pub const GEN1_NOTIFY_COMMAND_UUID: &str = "00002a80-0000-1000-8000-00805f9b34fb";

// --- Pyxis UUIDs ---

/// Pyxis: GATT service UUID.
pub const PYXIS_SERVICE_UUID: &str = "49535343-fe7d-4ae5-8fa9-9fafd205e455";
/// Pyxis: characteristic the scale notifies weight/status on.
pub const PYXIS_STATUS_UUID: &str = "49535343-1e4d-4bd9-ba61-23c647249616";
/// Pyxis: characteristic commands are written to.
pub const PYXIS_COMMAND_UUID: &str = "49535343-8841-43f4-a8d4-ecbe34729bb3";

// --- Commands (opaque framed payloads with baked-in checksums) ---

/// Command: tare — `EF DD 04` followed by 17 zero bytes.
pub const TARE: [u8; 20] = [
    0xEF, 0xDD, 0x04, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
];
/// Handshake: identify the app to the scale (sent after connect).
pub const IDENT: [u8; 20] = [
    0xEF, 0xDD, 0x0B, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x30, 0x31, 0x32,
    0x33, 0x34, 0x9A, 0x6D,
];
/// Handshake: configure which notifications the scale sends.
pub const CONFIG: [u8; 14] = [
    0xEF, 0xDD, 0x0C, 0x09, 0x00, 0x01, 0x01, 0x02, 0x02, 0x01, 0x03, 0x04, 0x11, 0x06,
];
/// Handshake: keep-alive heartbeat.
pub const HEARTBEAT: [u8; 7] = [0xEF, 0xDD, 0x00, 0x02, 0x00, 0x02, 0x00];

/// Number of metadata bytes in a frame: `EF DD <type> <length> <event_type>`.
const METADATA_LEN: usize = 5;
/// Minimum bytes needed before a frame's metadata can be read.
const MIN_MESSAGE_LEN: usize = 6;
/// Sanity cap on the receive buffer. A complete Acaia frame is at most ~75
/// bytes (5 metadata + a 64-byte max payload + a little slack), so a buffer
/// that grows past this is a hostile or desynced stream that will never
/// assemble a frame — it is dropped rather than retained unboundedly.
const MAX_BUFFER_LEN: usize = 256;

/// Stateful decoder for the Acaia framed protocol.
///
/// Feed every notification from the scale's status characteristic to
/// [`push`](Self::push); it returns `Some(grams)` once a complete weight frame
/// has been assembled.
#[derive(Debug, Default)]
pub struct AcaiaDecoder {
    buffer: Vec<u8>,
}

/// Outcome of scanning the receive buffer for a message.
enum Scan {
    /// No `EF DD` header found — the buffer holds no message.
    NoMessage,
    /// A header was found but more bytes are needed before it can be decoded.
    NeedMoreData,
    /// A weight message's metadata was found.
    Weight {
        event_type: u8,
        msg_start: usize,
        length: usize,
    },
}

impl AcaiaDecoder {
    /// Create an empty decoder.
    pub fn new() -> Self {
        Self::default()
    }

    /// Feed one BLE notification. Returns `Some(grams)` when a complete weight
    /// frame has been decoded, otherwise `None` (more data may be needed).
    pub fn push(&mut self, data: &[u8]) -> Option<f32> {
        self.buffer.extend_from_slice(data);
        // A buffer past the sanity cap is a hostile or desynced stream that
        // will never assemble a frame — drop the stale partial buffer.
        if self.buffer.len() > MAX_BUFFER_LEN {
            self.buffer.clear();
            return None;
        }
        if self.buffer.len() < MIN_MESSAGE_LEN {
            return None;
        }
        match scan(&self.buffer) {
            Scan::NeedMoreData => None,
            Scan::NoMessage => {
                self.buffer.clear();
                None
            }
            Scan::Weight {
                event_type,
                msg_start,
                length,
            } => {
                if msg_start + METADATA_LEN + length > self.buffer.len() {
                    // Frame not fully buffered yet — wait for the next notification.
                    return None;
                }
                // Payload sits after the metadata; event type 11 has 3 extra bytes.
                let payload_offset =
                    msg_start + METADATA_LEN + if event_type == 11 { 3 } else { 0 };
                let grams = decode_weight(&self.buffer, payload_offset);
                self.buffer.clear();
                grams
            }
        }
    }
}

/// Scan a buffer for the first weight message, skipping non-weight messages.
fn scan(buf: &[u8]) -> Scan {
    let mut i = 0;
    while i + 1 < buf.len() {
        if buf[i] == 0xEF && buf[i + 1] == 0xDD {
            if buf.len() - i < MIN_MESSAGE_LEN {
                return Scan::NeedMoreData;
            }
            let msg_type = buf[i + 2];
            let length = buf[i + 3] as usize;
            let event_type = buf[i + 4];
            if msg_type == 12 && (event_type == 5 || event_type == 11) && length <= 64 {
                return Scan::Weight {
                    event_type,
                    msg_start: i,
                    length,
                };
            }
            // Not a weight message — skip the whole message and keep scanning.
            i += METADATA_LEN + length;
            continue;
        }
        i += 1;
    }
    Scan::NoMessage
}

/// Decode the weight payload at `payload_offset`. Returns `None` if the payload
/// is too short (mirroring the legacy parser, which silently ignores it).
fn decode_weight(buf: &[u8], payload_offset: usize) -> Option<f32> {
    let payload = buf.get(payload_offset..)?;
    if payload.len() < 6 {
        return None;
    }
    // Weight is a little-endian 24-bit value; byte 4 is the decimal exponent.
    let value =
        (u32::from(payload[2]) << 16) | (u32::from(payload[1]) << 8) | u32::from(payload[0]);
    let unit = payload[4];
    let grams = value as f32 / 10f32.powi(i32::from(unit));
    Some(if payload[5] > 1 { -grams } else { grams })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A complete event-type-5 weight frame: value 180, unit 1 -> 18.0 g.
    /// Layout: `EF DD <type=12> <length=6> <event=5>` + 6 payload bytes.
    fn weight_frame(value_lsb: u8, sign: u8) -> [u8; 11] {
        [0xEF, 0xDD, 12, 6, 5, value_lsb, 0, 0, 0, 1, sign]
    }

    #[test]
    fn decodes_a_complete_weight_frame() {
        let mut d = AcaiaDecoder::new();
        assert_eq!(d.push(&weight_frame(180, 0)), Some(18.0));
    }

    #[test]
    fn decodes_a_negative_weight() {
        let mut d = AcaiaDecoder::new();
        // Sign byte > 1 marks a negative weight.
        assert_eq!(d.push(&weight_frame(180, 2)), Some(-18.0));
    }

    #[test]
    fn reassembles_a_frame_split_across_notifications() {
        let frame = weight_frame(180, 0);
        let mut d = AcaiaDecoder::new();
        assert_eq!(d.push(&frame[..4]), None); // partial — need more
        assert_eq!(d.push(&frame[4..]), Some(18.0)); // completed
    }

    #[test]
    fn ignores_a_buffer_with_no_header() {
        let mut d = AcaiaDecoder::new();
        assert_eq!(d.push(&[0x01, 0x02, 0x03, 0x04, 0x05, 0x06]), None);
    }

    #[test]
    fn a_hostile_oversized_stream_is_dropped_not_retained() {
        let mut d = AcaiaDecoder::new();
        // A header followed by a length claiming far more than will ever
        // arrive keeps the decoder waiting; a hostile peer then floods bytes.
        // The buffer must not grow without bound — it is cleared past the cap.
        d.push(&[0xEF, 0xDD, 12, 0xFF, 5]); // Weight metadata, length 255
        for _ in 0..20 {
            assert_eq!(d.push(&[0u8; 64]), None);
        }
        assert!(d.buffer.len() <= MAX_BUFFER_LEN);
        // After the drop the decoder still works for a fresh, valid frame.
        assert_eq!(d.push(&weight_frame(180, 0)), Some(18.0));
    }
}
