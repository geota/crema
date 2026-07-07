//! Acaia scale codec — gen1/IPS and Pyxis (`acaiascale` / `acaiapyxis`).
//!
//! Acaia uses a proprietary **framed protocol**: a message is delimited by an
//! `EF DD` header and a single weight frame may span several BLE
//! notifications. Decoding is therefore stateful — feed every notification to
//! an [`AcaiaDecoder`].
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
/// How often the shell should send [`HEARTBEAT`] — the Acaia stops
/// streaming without a periodic keep-alive (Decenza `acaiascale.cpp:264-277`
/// runs the same 3 s cadence for the whole session).
pub const HEARTBEAT_INTERVAL_MS: u64 = 3_000;

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
///
/// The decoder also tracks the most recently observed battery percentage from
/// `msgType == 8` notifications (the same channel that carries weight); see
/// [`battery_percent`](Self::battery_percent). Reaprime parses this field
/// (`acaia_scale.dart:355-357`); legacy de1app drops it on the floor.
#[derive(Debug, Default)]
pub struct AcaiaDecoder {
    buffer: Vec<u8>,
    battery_percent: Option<u8>,
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
    /// A `msgType == 8` battery message was found at `msg_start`; the battery
    /// byte sits at `msg_start + 4` (the `event_type` slot — reaprime's
    /// `acaia_scale.dart:355-357` reads `_commandBuffer[4]` here, then advances
    /// past the message). Crema mirrors that behaviour: skip past the message
    /// and surface the byte on the decoder state.
    Battery { msg_start: usize, length: usize },
}

impl AcaiaDecoder {
    /// Create an empty decoder.
    pub fn new() -> Self {
        Self::default()
    }

    /// The most recently observed battery percentage from a `msgType == 8`
    /// notification (`0..=100`), or `None` if no such notification has yet
    /// been seen. Reaprime decodes the same field (`acaia_scale.dart:355-357`);
    /// legacy de1app drops it on the floor.
    #[must_use]
    pub fn battery_percent(&self) -> Option<u8> {
        self.battery_percent
    }

    /// Feed one BLE notification. Returns `Some(grams)` when a complete weight
    /// frame has been decoded, otherwise `None` (more data may be needed).
    ///
    /// As a side-effect, a `msgType == 8` battery notification updates
    /// [`battery_percent`](Self::battery_percent) and is consumed (returning
    /// `None`) — the caller can read the latest battery on every weight
    /// notification.
    pub fn push(&mut self, data: &[u8]) -> Option<f32> {
        self.buffer.extend_from_slice(data);
        // A buffer past the sanity cap is a hostile or desynced stream that
        // will never assemble a frame — drop the stale partial buffer.
        if self.buffer.len() > MAX_BUFFER_LEN {
            self.buffer.clear();
            return None;
        }
        // Walk the buffer: a single notification may carry a battery message
        // *and* a weight message, so we keep scanning after consuming a
        // non-weight (battery) frame. Stop as soon as a weight frame
        // decodes, or when no more messages can be processed.
        loop {
            if self.buffer.len() < MIN_MESSAGE_LEN {
                return None;
            }
            match scan(&self.buffer) {
                Scan::NeedMoreData => return None,
                Scan::NoMessage => {
                    self.buffer.clear();
                    return None;
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
                    return grams;
                }
                Scan::Battery { msg_start, length } => {
                    // Reaprime reads `_commandBuffer[4]` as the battery byte
                    // (the `event_type` slot inside the metadata) and advances
                    // past the full message — `_metadataLen + length` bytes
                    // (`acaia_scale.dart:355-357, 367-372`).
                    if self.buffer.len() < msg_start + METADATA_LEN {
                        // Not even the metadata is fully buffered — wait.
                        return None;
                    }
                    // Mask the charging bit + clamp to a percentage (Decenza
                    // acaiascale.cpp:352-357 does `& 0x7F` and range-checks).
                    // NOTE the byte OFFSET is disputed: reaprime (followed
                    // here) reads metadata byte [4]; Decenza reads payload
                    // byte [5] and calls [4] "unknown" — hardware-verify, see
                    // .scratch/decenza-review/HARDWARE-VERIFY-FIXES.md.
                    self.battery_percent = Some((self.buffer[msg_start + 4] & 0x7F).min(100));
                    let claimed_end = msg_start + METADATA_LEN + length;
                    let consume_end = claimed_end.min(self.buffer.len());
                    self.buffer.drain(..consume_end);
                    // Continue: there may be a weight frame after this.
                }
            }
        }
    }
}

/// Scan a buffer for the first weight or battery message, skipping unknown
/// non-weight messages.
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
            // msgType == 8: battery notification. Reaprime reads
            // `_commandBuffer[4]` (the event_type slot) as the battery byte
            // and advances past the message — see `acaia_scale.dart:355-357`.
            if msg_type == 8 && length <= 64 {
                return Scan::Battery {
                    msg_start: i,
                    length,
                };
            }
            // Not a weight or battery message — skip the whole message and
            // keep scanning.
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
    fn decodes_a_battery_notification() {
        // Reaprime parses `msgType == 8` battery notifications and stores
        // byte `[4]` as the battery percentage (`acaia_scale.dart:355-357`).
        let mut d = AcaiaDecoder::new();
        assert_eq!(d.battery_percent(), None);
        // EF DD <type=8> <length=1> <battery=78> <payload byte>
        assert_eq!(d.push(&[0xEF, 0xDD, 8, 1, 78, 0]), None);
        assert_eq!(d.battery_percent(), Some(78));
    }

    #[test]
    fn battery_notification_does_not_block_a_following_weight_frame() {
        // The decoder must consume the battery message in place and then keep
        // scanning the remainder for a weight frame.
        let mut d = AcaiaDecoder::new();
        // A 6-byte battery frame (metadata + 1 payload byte) followed by an
        // 11-byte weight frame. The decoder consumes the battery message and
        // re-scans the remainder for weight.
        let battery = [0xEF, 0xDD, 8, 1, 91, 0];
        let weight = weight_frame(180, 0);
        let mut combined = Vec::new();
        combined.extend_from_slice(&battery);
        combined.extend_from_slice(&weight);
        assert_eq!(d.push(&combined), Some(18.0));
        assert_eq!(d.battery_percent(), Some(91));
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
