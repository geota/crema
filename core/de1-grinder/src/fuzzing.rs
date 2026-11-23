//! Entry points for the cargo-fuzz targets in `fuzz/`, also replayed over the
//! checked-in corpus by `tests/fuzz_corpus.rs` on stable.

use crate::GrinderDriver;
use crate::bookoo_motto80::{self, FrameReassembler};

/// Arbitrary bytes as a stream of notifications to a connected Motto 80
/// (each chunk led by its length byte, then a time step byte), through the
/// decoder, the capture redactor and the timers. Panics only on a bug.
pub fn motto_notifications(data: &[u8]) {
    let Some(mut driver) = GrinderDriver::from_model(bookoo_motto80::MODEL) else {
        return;
    };
    let mut now = 0_u64;
    driver.connect_writes(20, now);
    let mut rest = data;
    while let [len, step, tail @ ..] = rest {
        let len = usize::from(*len).min(tail.len());
        let (chunk, next) = tail.split_at(len);
        now += u64::from(*step) * 20;
        driver.on_notification(chunk, now);
        for frame in driver.redact_for_capture(chunk, now) {
            assert!(frame.len() <= bookoo_motto80::MAX_PACKET);
        }
        driver.tick(now);
        rest = next;
    }
}

/// Encode then reassemble any 1..=4096-byte message at a packet size from
/// the first byte: it must come back whole, from the last fragment only.
pub fn motto_round_trip(data: &[u8]) {
    let [size, seq, message @ ..] = data else {
        return;
    };
    if message.is_empty() || message.len() > bookoo_motto80::MESSAGE_MAX_LEN {
        return;
    }
    let max_packet = 9 + usize::from(*size) * 2;
    let frames = bookoo_motto80::encode(message, u16::from(*seq), max_packet);
    let mut reassembler = FrameReassembler::default();
    let last = frames.len() - 1;
    for (i, frame) in frames.iter().enumerate() {
        assert!(frame.len() <= max_packet.min(bookoo_motto80::MAX_PACKET));
        let out = reassembler.feed(frame, 0).expect("own frames are valid");
        if i == last {
            assert_eq!(out.as_deref(), Some(message));
        } else {
            assert_eq!(out, None);
        }
    }
}
