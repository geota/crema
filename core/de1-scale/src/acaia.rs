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
/// A whole frame is `METADATA_LEN + length` bytes.
const METADATA_LEN: usize = 5;
/// Minimum bytes needed before a frame's metadata can be read.
const MIN_MESSAGE_LEN: usize = 6;
/// Largest declared payload length accepted. A larger length byte is a
/// corrupt or desynced header: both references resync by two bytes past it
/// rather than wait for (or skip by) a length they have just rejected
/// (Decenza `MAX_ACAIA_PAYLOAD_LEN`, `acaiascale.cpp:420-440`; decaid
/// `_maxPayloadLength`). de1app uses the same 64-byte ceiling.
const MAX_PAYLOAD_LEN: usize = 64;
/// Bytes in a weight body: 24-bit LE value, one unused byte, the decimal
/// exponent, the sign.
const WEIGHT_BODY_LEN: usize = 6;
/// Event 5: the weight body follows the metadata directly.
const EVENT_WEIGHT: u8 = 5;
/// Event 11: a heartbeat reply. Byte 7 selects its body — `5` weight, `7`
/// timer, `8` button — and only a weight body follows at byte 8.
const EVENT_HEARTBEAT: u8 = 11;
/// Sanity cap on the receive buffer. With [`MAX_PAYLOAD_LEN`] bounding a
/// frame at 69 bytes, a buffer past this is a hostile stream — it is dropped
/// rather than retained unboundedly.
const MAX_BUFFER_LEN: usize = 256;

/// Stateful decoder for the Acaia framed protocol.
///
/// Feed every notification from the scale's status characteristic to
/// [`push`](Self::push); it returns `Some(grams)` once a complete weight frame
/// has been assembled.
///
/// Each frame is bounded by its declared length: a notification often
/// carries several frames back to back (a weight frame with a settings frame
/// behind it, say), so the decoder consumes exactly one frame at a time and
/// keeps the rest — partial trailing frame included — for the next pass.
/// Clearing the whole buffer after a weight frame, as this decoder used to,
/// dropped whatever followed (Decenza 8cab2f78, `acaiascale.cpp:363-373`;
/// decaid 07fdec67 / 1330cb4f: `_buffer.sublist(frameLength)`).
///
/// Only weight-bearing frames are decoded as weight: event 5, or event 11
/// whose selector byte is 5. Crema's 3 s heartbeat makes the scale answer
/// with event-11 frames constantly, and a timer- or button-bodied one read as
/// weight fed spikes into stop-at-weight (decaid issue #509: a shot stopped
/// on a projected 356 g against a 36.5 g target; `AI_BLE_NOTES.md`
/// "selector 5 = weight, 7 = timer").
///
/// The decoder also tracks the most recently observed battery percentage from
/// `msgType == 8` (settings) frames, on the same channel that carries weight;
/// see [`battery_percent`](Self::battery_percent).
#[derive(Debug, Default)]
pub struct AcaiaDecoder {
    buffer: Vec<u8>,
    battery_percent: Option<u8>,
}

impl AcaiaDecoder {
    /// Create an empty decoder.
    pub fn new() -> Self {
        Self::default()
    }

    /// The most recently observed battery percentage from a `msgType == 8`
    /// settings frame (`0..=100`), or `None` if no such frame has yet been
    /// seen.
    #[must_use]
    pub fn battery_percent(&self) -> Option<u8> {
        self.battery_percent
    }

    /// Feed one BLE notification. Returns `Some(grams)` when a complete weight
    /// frame has been decoded — the last one, if the notification completed
    /// several — otherwise `None` (more data may be needed).
    ///
    /// As a side-effect, a `msgType == 8` settings frame updates
    /// [`battery_percent`](Self::battery_percent).
    pub fn push(&mut self, data: &[u8]) -> Option<f32> {
        self.buffer.extend_from_slice(data);
        // A buffer past the sanity cap is a hostile or desynced stream that
        // will never assemble a frame — drop the stale partial buffer.
        if self.buffer.len() > MAX_BUFFER_LEN {
            self.buffer.clear();
            return None;
        }
        let mut weight = None;
        loop {
            let Some(start) = self.buffer.windows(2).position(|w| w == [0xEF, 0xDD]) else {
                // No header in flight. A lone trailing 0xEF may be the first
                // half of an EF DD header split at the notification boundary
                // — keep it so the next frame isn't dropped (review #32).
                if self.buffer.last() == Some(&0xEF) {
                    let keep_from = self.buffer.len() - 1;
                    self.buffer.drain(..keep_from);
                } else {
                    self.buffer.clear();
                }
                return weight;
            };
            self.buffer.drain(..start);
            if self.buffer.len() < MIN_MESSAGE_LEN {
                return weight;
            }
            let length = usize::from(self.buffer[3]);
            if length > MAX_PAYLOAD_LEN {
                // Resync past this header, not by the rejected length.
                self.buffer.drain(..2);
                continue;
            }
            let frame_len = METADATA_LEN + length;
            if self.buffer.len() < frame_len {
                // Frame not fully buffered yet — wait for the next notification.
                return weight;
            }
            let frame: Vec<u8> = self.buffer.drain(..frame_len).collect();
            if let Some(grams) = self.decode_frame(&frame) {
                weight = Some(grams);
            }
        }
    }

    /// Decode one complete frame. Every read is bounded by `frame`, never by
    /// the buffer behind it: a frame whose length byte is too short for the
    /// body it claims must not borrow its neighbour's bytes as weight.
    fn decode_frame(&mut self, frame: &[u8]) -> Option<f32> {
        let msg_type = frame[2];
        let event_type = frame[4];
        match msg_type {
            12 if event_type == EVENT_WEIGHT => decode_weight(frame, METADATA_LEN),
            12 if event_type == EVENT_HEARTBEAT && frame.get(7) == Some(&EVENT_WEIGHT) => {
                decode_weight(frame, METADATA_LEN + 3)
            }
            8 => {
                // Settings frame: byte 4 is the battery, the top bit a
                // charging flag (Decenza `acaiascale.cpp:466-497` after
                // #1670, decaid `_processFrame`, pyacaia `payload[1] & 0x7F`
                // all read byte 4). A value above 100 means the byte isn't a
                // battery on this model — ignored, as both references do.
                let battery = frame[4] & 0x7F;
                if battery <= 100 {
                    self.battery_percent = Some(battery);
                }
                None
            }
            _ => None,
        }
    }
}

/// Decode the weight body at `offset` within one `frame`. Returns `None` if
/// the frame ends before the six-byte body does (mirroring the references,
/// which silently ignore it).
fn decode_weight(frame: &[u8], offset: usize) -> Option<f32> {
    let body = frame.get(offset..offset + WEIGHT_BODY_LEN)?;
    // Weight is a little-endian 24-bit value; byte 4 is the decimal exponent.
    let value = (u32::from(body[2]) << 16) | (u32::from(body[1]) << 8) | u32::from(body[0]);
    let unit = body[4];
    let grams = value as f32 / 10f32.powi(i32::from(unit));
    Some(if body[5] > 1 { -grams } else { grams })
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
    fn a_header_split_after_an_unknown_message_is_not_dropped() {
        // Review #32: an unknown message followed by the next frame's
        // header splitting exactly at the notification boundary (…EF | DD…)
        // used to lose the trailing 0xEF — and with it the weight frame.
        let mut d = AcaiaDecoder::new();
        // A complete unknown-type message (msgType 5) then a lone 0xEF.
        let mut first = vec![0xEF, 0xDD, 0x05, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
        first.push(0xEF);
        assert_eq!(d.push(&first), None);
        // The rest of a real weight frame follows in the next notification.
        let frame = weight_frame(180, 0);
        assert_eq!(d.push(&frame[1..]), Some(18.0));
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

    /// decaid's captured weight frame (`acaia_scale_test.dart`
    /// `_realWeightFrame`): event 5, 0x06DF = 1759, exponent 1 → 175.9 g.
    const REAL_WEIGHT_FRAME: [u8; 17] = [
        0xEF, 0xDD, 0x0C, 0x0C, 0x05, 0xDF, 0x06, 0x00, 0x00, 0x01, 0x00, 0x07, 0x00, 0x00, 0x02,
        0xF3, 0x0D,
    ];
    /// decaid's captured settings frame (`_realSettingsFrame`): battery
    /// 0x5D = 93 %.
    const REAL_SETTINGS_FRAME: [u8; 14] = [
        0xEF, 0xDD, 0x08, 0x09, 0x5D, 0x02, 0x02, 0x01, 0x00, 0x01, 0x01, 0x00, 0x0D, 0x60,
    ];
    const WEIGHT_BODY: [u8; 6] = [0xDF, 0x06, 0x00, 0x00, 0x01, 0x00];

    /// decaid's `_frame` builder: `EF DD <cmd> <len> <payload> <even> <odd>`.
    fn frame(command: u8, payload: &[u8]) -> Vec<u8> {
        let mut body = vec![u8::try_from(payload.len() + 1).unwrap()];
        body.extend_from_slice(payload);
        let (mut even, mut odd) = (0u8, 0u8);
        for (i, b) in body.iter().enumerate() {
            if i % 2 == 0 {
                even = even.wrapping_add(*b);
            } else {
                odd = odd.wrapping_add(*b);
            }
        }
        let mut out = vec![0xEF, 0xDD, command];
        out.extend(body);
        out.extend([even, odd]);
        out
    }

    fn event_frame(event: u8, payload: &[u8]) -> Vec<u8> {
        let mut p = vec![event];
        p.extend_from_slice(payload);
        frame(0x0C, &p)
    }

    fn heartbeat_weight_frame() -> Vec<u8> {
        let mut p = vec![0, 0, 5];
        p.extend_from_slice(&WEIGHT_BODY);
        event_frame(11, &p)
    }

    fn heartbeat_timer_frame() -> Vec<u8> {
        event_frame(11, &[0, 0, 7, 0x01, 0x1E, 0x05])
    }

    fn heartbeat_button_frame() -> Vec<u8> {
        event_frame(11, &[0, 0, 8, 0])
    }

    #[test]
    fn decodes_captured_weight_and_settings_frames() {
        let mut d = AcaiaDecoder::new();
        assert_eq!(d.push(&REAL_WEIGHT_FRAME), Some(175.9));
        assert_eq!(d.push(&REAL_SETTINGS_FRAME), None);
        assert_eq!(d.battery_percent(), Some(93));
    }

    #[test]
    fn heartbeat_frames_yield_weight_only_for_selector_5() {
        let mut d = AcaiaDecoder::new();
        assert_eq!(d.push(&heartbeat_weight_frame()), Some(175.9));
        // A timer body (01 1E 05 …) used to decode as weight — the spike
        // decaid #509 stopped a shot on. A button body is not weight either.
        assert_eq!(d.push(&heartbeat_timer_frame()), None);
        assert_eq!(d.push(&heartbeat_button_frame()), None);
        assert!(d.buffer.is_empty());
    }

    #[test]
    fn coalesced_frames_are_each_decoded_and_nothing_is_dropped() {
        // weight | timer | settings | weight in one notification, then a
        // partial weight frame split across the next two.
        let mut d = AcaiaDecoder::new();
        let second = {
            let mut p = vec![5];
            p.extend_from_slice(&[0xA4, 0x01, 0x00, 0x00, 0x01, 0x00]);
            frame(0x0C, &p)
        };
        let mut combined = REAL_WEIGHT_FRAME.to_vec();
        combined.extend(heartbeat_timer_frame());
        combined.extend(frame(0x08, &[61, 0]));
        combined.extend(&second);
        // The last weight in the notification wins; the timer frame between
        // them contributes nothing.
        assert_eq!(d.push(&combined), Some(42.0));
        assert_eq!(d.battery_percent(), Some(61));
        assert!(d.buffer.is_empty());
        // A weight frame followed by the first half of the next: the first
        // decodes, the tail is kept and completed by the next notification.
        let mut split = REAL_WEIGHT_FRAME.to_vec();
        split.extend(&second[..7]);
        assert_eq!(d.push(&split), Some(175.9));
        assert_eq!(d.push(&second[7..]), Some(42.0));
    }

    #[test]
    fn a_frame_too_short_for_its_body_does_not_read_its_neighbour() {
        // decaid "rejected frames": a weight event whose declared length
        // ends inside the body. Followed by a real frame whose bytes must
        // not be borrowed as weight.
        let mut d = AcaiaDecoder::new();
        let mut stream = vec![0xEF, 0xDD, 0x0C, 0x02, 0x05, 0xAA, 0xBB];
        stream.extend(heartbeat_timer_frame());
        assert_eq!(d.push(&stream), None);
        // And the stream is still in step: a good frame decodes next.
        assert_eq!(d.push(&REAL_WEIGHT_FRAME), Some(175.9));
    }

    #[test]
    fn an_oversized_length_resyncs_by_two_bytes() {
        // A corrupt length byte (> 64) must not park the buffer or skip a
        // real frame that starts inside the bogus "payload".
        let mut d = AcaiaDecoder::new();
        let mut stream = vec![0xEF, 0xDD, 0x0C, 0xFF, 0x05, 0x00];
        stream.extend(REAL_WEIGHT_FRAME);
        assert_eq!(d.push(&stream), Some(175.9));
    }

    #[test]
    fn an_out_of_range_battery_byte_is_ignored() {
        let mut d = AcaiaDecoder::new();
        d.push(&frame(0x08, &[0x5D, 0]));
        assert_eq!(d.battery_percent(), Some(93));
        // 0x80 is the charging bit: 0xDD & 0x7F = 93 again.
        d.push(&frame(0x08, &[0xDD, 0]));
        assert_eq!(d.battery_percent(), Some(93));
        // 0x7F = 127: not a battery byte, keep the last good value.
        d.push(&frame(0x08, &[0x7F, 0]));
        assert_eq!(d.battery_percent(), Some(93));
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
