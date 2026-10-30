//! AC-13: canonical fragmentation and its limits.

mod common;

use common::*;
use de1_relay::{
    MAX_MESSAGE_BYTES, MAX_RECORD_PAYLOAD, Phase, RECV_TIMEOUT_MS, RelayError, WS_MAX_MESSAGE,
    WsIn, inner, ws_close,
};

fn records_for(link: &mut Link, len: usize) -> Vec<Vec<u8>> {
    let msg: Vec<u8> = (0..len).map(|i| u8::try_from(i % 253).unwrap()).collect();
    let step = link.hub.send(&msg, T0).unwrap();
    step.send.into_iter().map(|m| m.bytes).collect()
}

#[test]
fn sizes_round_trip() {
    for len in [
        0,
        1,
        MAX_RECORD_PAYLOAD,
        MAX_RECORD_PAYLOAD + 1,
        200_000,
        MAX_MESSAGE_BYTES,
    ] {
        let mut link = established(&remote_keys(), &hub_keys(), 1);
        let records = records_for(&mut link, len);
        let expected_records = len.div_ceil(MAX_RECORD_PAYLOAD).max(1);
        assert_eq!(records.len(), expected_records, "{len}");
        for (i, r) in records.iter().enumerate() {
            assert!(r.len() <= WS_MAX_MESSAGE);
            // kind + flags + type + payload + tag: every non-final one is full.
            if i + 1 < records.len() {
                assert_eq!(
                    r.len(),
                    1 + 2 + MAX_RECORD_PAYLOAD + 16,
                    "{len} fragment {i}"
                );
            }
        }
        let mut delivered = Vec::new();
        for r in &records {
            let s = link.remote.on_ws(WsIn::Binary(r), T0);
            delivered.extend(s.deliver);
        }
        let msg: Vec<u8> = (0..len).map(|i| u8::try_from(i % 253).unwrap()).collect();
        assert_eq!(delivered, vec![msg], "{len}");
    }
}

#[test]
fn oversize_is_refused_by_send() {
    let mut link = established(&remote_keys(), &hub_keys(), 1);
    let too_big = vec![0_u8; MAX_MESSAGE_BYTES + 1];
    assert_eq!(
        link.hub.send(&too_big, T0),
        Err(RelayError::MessageTooLarge)
    );
    assert_eq!(
        link.remote.send(&too_big, T0),
        Err(RelayError::MessageTooLarge)
    );
    // Nothing changed: the session still works.
    link.hub_send(b"ok");
    assert_eq!(link.remote_delivered(), vec![b"ok".to_vec()]);
}

#[test]
fn oversize_is_refused_by_reassembly() {
    let mut link = established(&remote_keys(), &hub_keys(), 1);
    let full = vec![1_u8; MAX_RECORD_PAYLOAD];
    // 16 full fragments = 1 048 272 bytes; the 17th full one would exceed 1 MiB.
    for _ in 0..16 {
        let s = link
            .hub
            .send_raw_record_for_tests(inner::FLAG_MORE, inner::FRAME, &full, T0)
            .unwrap();
        let r = link.remote.on_ws(WsIn::Binary(&s.send[0].bytes), T0);
        assert!(r.close.is_none());
        assert!(link.remote.buffered_for_tests() <= MAX_MESSAGE_BYTES);
    }
    // A final fragment of 4 305 bytes would make it 1 048 577.
    let last = vec![1_u8; MAX_MESSAGE_BYTES + 1 - 16 * MAX_RECORD_PAYLOAD];
    let s = link
        .hub
        .send_raw_record_for_tests(0, inner::FRAME, &last, T0)
        .unwrap();
    let r = link.remote.on_ws(WsIn::Binary(&s.send[0].bytes), T0);
    assert_eq!(r.close.unwrap().code, ws_close::PROTOCOL);
    assert!(r.deliver.is_empty());
    assert_eq!(link.remote.buffered_for_tests(), 0);
}

fn protocol_breach(build: impl Fn(&mut Link) -> Vec<Vec<u8>>) {
    let mut link = established(&remote_keys(), &hub_keys(), 1);
    let records = build(&mut link);
    let mut last = None;
    for r in &records {
        let s = link.remote.on_ws(WsIn::Binary(r), T0);
        assert!(s.deliver.is_empty());
        if s.close.is_some() {
            last = s.close;
            break;
        }
    }
    assert_eq!(last.map(|c| c.code), Some(ws_close::PROTOCOL));
    assert_eq!(link.remote.phase(), Phase::Failed);
    assert_eq!(link.remote.buffered_for_tests(), 0);
}

fn raw(link: &mut Link, flags: u8, ty: u8, payload: &[u8]) -> Vec<u8> {
    link.hub
        .send_raw_record_for_tests(flags, ty, payload, T0)
        .unwrap()
        .send
        .remove(0)
        .bytes
}

#[test]
fn inner_type_between_fragments_closes() {
    protocol_breach(|l| {
        vec![
            raw(
                l,
                inner::FLAG_MORE,
                inner::FRAME,
                &vec![0; MAX_RECORD_PAYLOAD],
            ),
            raw(l, 0, inner::HEARTBEAT, &[]),
        ]
    });
}

#[test]
fn short_non_final_fragment_closes() {
    protocol_breach(|l| {
        vec![raw(
            l,
            inner::FLAG_MORE,
            inner::FRAME,
            &vec![0; MAX_RECORD_PAYLOAD - 1],
        )]
    });
    protocol_breach(|l| vec![raw(l, inner::FLAG_MORE, inner::FRAME, &[])]);
}

#[test]
fn empty_final_fragment_closes() {
    protocol_breach(|l| {
        vec![
            raw(
                l,
                inner::FLAG_MORE,
                inner::FRAME,
                &vec![0; MAX_RECORD_PAYLOAD],
            ),
            raw(l, 0, inner::FRAME, &[]),
        ]
    });
}

#[test]
fn more_on_control_types_closes() {
    for ty in [inner::HEARTBEAT, inner::CLOSE, inner::PAIR_COMPLETE] {
        protocol_breach(|l| vec![raw(l, inner::FLAG_MORE, ty, &[])]);
    }
}

#[test]
fn reserved_flag_bits_close() {
    for bit in 1..8 {
        protocol_breach(|l| vec![raw(l, 1 << bit, inner::FRAME, b"x")]);
    }
}

#[test]
fn unknown_inner_type_and_payloads_close() {
    protocol_breach(|l| vec![raw(l, 0, 0x05, b"")]);
    protocol_breach(|l| vec![raw(l, 0, 0x00, b"")]);
    protocol_breach(|l| vec![raw(l, 0, inner::HEARTBEAT, b"x")]);
    protocol_breach(|l| vec![raw(l, 0, inner::CLOSE, &[9, 0])]);
    protocol_breach(|l| vec![raw(l, 0, inner::CLOSE, &[1, 5, b'a'])]);
}

/// Fragments alone don't count as liveness: full `MORE` fragments every 5 s
/// still let the session time out 30 s after the last complete message.
#[test]
fn endless_fragments_time_out() {
    let mut link = established(&remote_keys(), &hub_keys(), 1);
    let full = vec![2_u8; MAX_RECORD_PAYLOAD];
    for k in 1..6_u64 {
        let now = T0 + k * 5_000;
        let s = link
            .hub
            .send_raw_record_for_tests(inner::FLAG_MORE, inner::FRAME, &full, now)
            .unwrap();
        let r = link.remote.on_ws(WsIn::Binary(&s.send[0].bytes), now);
        assert!(r.close.is_none() && r.deliver.is_empty());
    }
    let s = link.remote.tick(T0 + RECV_TIMEOUT_MS - 1);
    assert!(s.close.is_none(), "only heartbeats before 30 s");
    let s = link.remote.tick(T0 + RECV_TIMEOUT_MS);
    assert_eq!(s.close.unwrap().code, ws_close::TIMEOUT);
    assert_eq!(link.remote.buffered_for_tests(), 0);
}
