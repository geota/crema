//! AC-50: exact handshake message lengths, empty payloads, the client-finished
//! record, and `CLOSE(PROTOCOL)` / `CLOSE(NORMAL)` as non-trust-changing refusals.

mod common;

use common::{evil::*, *};
use de1_relay::{
    CloseReason, HubConnection, Phase, RelayEvent, RelayResolver, WsIn, inner, kind,
    test_support::{self, test_seed},
    ws_close,
};

const ABORT_MALFORMED: [u8; 3] = [kind::ABORT, 9, 0];

fn assert_malformed(step: &de1_relay::Step) {
    assert_eq!(
        step.close.as_ref().map(|c| c.code),
        Some(ws_close::PROTOCOL),
        "{step:?}"
    );
    assert_eq!(
        step.send.first().map(|m| m.bytes.clone()),
        Some(ABORT_MALFORMED.to_vec())
    );
}

fn resized(m: &[u8], len: usize) -> Vec<u8> {
    let mut v = m.to_vec();
    v.resize(1 + len, 0x42);
    v
}

/// Every `NOISE_HS` position refuses any length but its own.
#[test]
fn wrong_lengths_are_malformed() {
    let (r, h) = (remote_keys(), hub_keys());
    // XX #1 (48) and #3 (64) at the hub, #2 (96) at the remote.
    for (pos, exact) in [(0_usize, 48_usize), (2, 64)] {
        for len in [exact - 16, exact - 1, exact + 1, exact + 16, 0] {
            let gate = new_gate();
            open_gate(&gate, T0);
            let mut link = Link::pairing(&r, &h, gate.clone(), new_pins(vec![]), 0, T0);
            link.set_tamper(Box::new(move |dir, mut m| {
                if dir == Dir::ToHub && m.bytes[0] == kind::NOISE_HS {
                    let n = m.bytes.len() - 1;
                    let target = if pos == 0 { 48 } else { 64 };
                    if n == target {
                        m.bytes = resized(&m.bytes, len);
                    }
                }
                Some(m)
            }));
            link.pump();
            link.enter_code(&link.shown_code().unwrap());
            let failing = link.hub_steps.iter().find(|s| s.close.is_some()).unwrap();
            assert_malformed(failing);
            assert_eq!(gate.lock().unwrap().status(T0).failures, 0);
        }
    }
    for len in [80, 95, 97, 112] {
        let gate = new_gate();
        open_gate(&gate, T0);
        let mut link = Link::pairing(&r, &h, gate, new_pins(vec![]), 0, T0);
        link.set_tamper(Box::new(move |dir, mut m| {
            if dir == Dir::ToRemote && m.bytes[0] == kind::NOISE_HS {
                m.bytes = resized(&m.bytes, len);
            }
            Some(m)
        }));
        link.pump();
        link.enter_code(&link.shown_code().unwrap());
        let failing = link
            .remote_steps
            .iter()
            .find(|s| s.close.is_some())
            .unwrap();
        assert_malformed(failing);
    }
    // IK #1 (96) at the hub, IK #2 (48) at the remote.
    for len in [80, 95, 97, 112] {
        let mut link = Link::resume(&r, &h, new_gate(), new_pins(vec![r.public()]), 1, T0);
        link.set_tamper(Box::new(move |dir, mut m| {
            if dir == Dir::ToHub && m.bytes[0] == kind::NOISE_HS {
                m.bytes = resized(&m.bytes, len);
            }
            Some(m)
        }));
        link.pump();
        assert_malformed(link.hub_steps.iter().find(|s| s.close.is_some()).unwrap());
    }
    for len in [32, 47, 49, 64] {
        let mut link = Link::resume(&r, &h, new_gate(), new_pins(vec![r.public()]), 1, T0);
        link.set_tamper(Box::new(move |dir, mut m| {
            if dir == Dir::ToRemote && m.bytes[0] == kind::NOISE_HS {
                m.bytes = resized(&m.bytes, len);
            }
            Some(m)
        }));
        link.pump();
        assert_malformed(
            link.remote_steps
                .iter()
                .find(|s| s.close.is_some())
                .unwrap(),
        );
        assert!(!has_event(&link.hub_events(), is_established));
    }
}

/// A handshake message whose payload decrypts non-empty is refused: written by
/// a real Noise initiator with a one-byte payload (which also changes its length).
#[test]
fn non_empty_payloads_are_malformed() {
    let r = remote_keys();
    let h = hub_keys();
    let mut hub = HubConnection::new(
        &h,
        new_gate(),
        new_pins(vec![r.public()]),
        "Hub",
        test_seed("hub-conn", 1),
        T0,
    );
    let hello = de1_relay::ClientHello::resume().encode();
    let sh = hub.on_ws(WsIn::Text(&hello), T0).send[0].bytes.clone();
    let prologue = test_support::resume_prologue(&hello, &sh);
    let private: [u8; 32] = *r.private_for_storage();
    let mut hs = test_support::build_handshake(
        false,
        true,
        &private,
        Some(&h.public()),
        &prologue,
        None,
        RelayResolver::scripted_for_tests(vec![vec![7; 32]]),
    )
    .unwrap();
    let mut buf = [0_u8; 256];
    let n = hs.write_message(b"0-RTT", &mut buf).unwrap();
    assert_eq!(n, 96 + 5);
    let s = hub.on_ws(WsIn::Binary(&wire(kind::NOISE_HS, &buf[..n])), T0);
    assert_malformed(&s);
}

/// The first record after IK #2 must be a HEARTBEAT; anything else closes 4400.
#[test]
fn first_record_must_be_heartbeat() {
    let r = remote_keys();
    let h = hub_keys();
    for (flags, ty, payload) in [
        (0, inner::FRAME, b"{}".as_slice()),
        (0, inner::CLOSE, &[5, 0][..]),
        (inner::FLAG_MORE, inner::FRAME, &[0; 65_517][..]),
    ] {
        let mut hub = HubConnection::new(
            &h,
            new_gate(),
            new_pins(vec![r.public()]),
            "Hub",
            test_seed("hub-conn", 1),
            T0,
        );
        let hello = de1_relay::ClientHello::resume().encode();
        let sh = hub.on_ws(WsIn::Text(&hello), T0).send[0].bytes.clone();
        let prologue = test_support::resume_prologue(&hello, &sh);
        let private: [u8; 32] = *r.private_for_storage();
        let mut hs = test_support::build_handshake(
            false,
            true,
            &private,
            Some(&h.public()),
            &prologue,
            None,
            RelayResolver::scripted_for_tests(vec![vec![7; 32]]),
        )
        .unwrap();
        let mut buf = vec![0_u8; 70_000];
        let n = hs.write_message(&[], &mut buf).unwrap();
        let s = hub.on_ws(WsIn::Binary(&wire(kind::NOISE_HS, &buf[..n])), T0);
        let mut out = [0_u8; 64];
        hs.read_message(&s.send[0].bytes[1..], &mut out).unwrap();
        let mut t = hs.into_transport_mode().unwrap();
        let mut plain = vec![flags, ty];
        plain.extend_from_slice(payload);
        let n = t.write_message(&plain, &mut buf).unwrap();
        let s = hub.on_ws(WsIn::Binary(&wire(kind::RECORD, &buf[..n])), T0);
        assert_eq!(s.close.unwrap().code, ws_close::PROTOCOL);
        assert!(
            s.send.is_empty(),
            "no plaintext ABORT after our handshake finished"
        );
        assert!(!s.events.iter().any(is_established));
        assert_eq!(hub.phase(), Phase::Failed);
    }
}

/// `CLOSE(PROTOCOL)` and `CLOSE(NORMAL)` arrive as authenticated refusals with
/// their own reasons (the shell keeps role and pin for them, unlike Revoked/NotPaired).
#[test]
fn close_protocol_and_normal_keep_role() {
    let mut link = established(&remote_keys(), &hub_keys(), 1);
    let s = link.hub.close(CloseReason::Normal, T0);
    assert_eq!(s.close.as_ref().unwrap().code, ws_close::NORMAL);
    link.hub_step(s);
    link.pump();
    assert!(link.remote_events().contains(&RelayEvent::Refused {
        reason: "Normal".into(),
        authenticated: true
    }));
    assert_eq!(link.remote_close(), Some(ws_close::NORMAL));

    let mut link = established(&remote_keys(), &hub_keys(), 1);
    let s = link
        .hub
        .send_raw_record_for_tests(0, inner::CLOSE, &[4, 0], T0)
        .unwrap();
    link.hub_step(s);
    link.pump();
    assert!(link.remote_events().contains(&RelayEvent::Refused {
        reason: "Protocol".into(),
        authenticated: true
    }));
    assert_eq!(link.remote_close(), Some(ws_close::PROTOCOL));

    let mut link = established(&remote_keys(), &hub_keys(), 1);
    let s = link.hub.close(CloseReason::HubStopped, T0);
    link.hub_step(s);
    link.pump();
    assert!(link.remote_events().contains(&RelayEvent::Refused {
        reason: "HubStopped".into(),
        authenticated: true
    }));
    for reason in ["Revoked", "NotPaired"] {
        assert!(
            !link
                .remote_events()
                .iter()
                .any(|e| matches!(e, RelayEvent::Refused { reason: r, .. } if r == reason))
        );
    }
}

/// A plaintext ABORT once the session is established is a protocol error on
/// either side, with an event, never a quiet close.
#[test]
fn abort_after_established_is_a_protocol_error() {
    let mut link = established(&remote_keys(), &hub_keys(), 1);
    let s = link.hub.on_ws(WsIn::Binary(&[kind::ABORT, 4, 0]), T0);
    assert_eq!(s.close.as_ref().unwrap().code, ws_close::PROTOCOL);
    assert!(s.send.is_empty());
    assert!(s.events.contains(&RelayEvent::Failed {
        error: "UnexpectedMessage".into()
    }));
    assert_eq!(link.hub.phase(), Phase::Failed);

    let mut link = established(&remote_keys(), &hub_keys(), 1);
    let s = link.remote.on_ws(WsIn::Binary(&[kind::ABORT, 1, 0]), T0);
    assert_eq!(s.close.as_ref().unwrap().code, ws_close::PROTOCOL);
    assert!(s.events.contains(&RelayEvent::Failed {
        error: "UnexpectedMessage".into()
    }));
    assert!(
        !s.events
            .iter()
            .any(|e| matches!(e, RelayEvent::Refused { .. }))
    );
    assert_eq!(link.remote.phase(), Phase::Failed);
}

/// The remote's authenticated CLOSE reaches the hub as `PeerClosed`, with the
/// close code that goes with it.
#[test]
fn hub_reports_the_remotes_close() {
    for (reason, code, wire) in [
        (CloseReason::Normal, ws_close::NORMAL, "Normal"),
        (CloseReason::Revoked, ws_close::REFUSED, "Revoked"),
    ] {
        let mut link = established(&remote_keys(), &hub_keys(), 1);
        let s = link.remote.close(reason, T0);
        link.remote_step(s);
        link.pump();
        assert!(link.hub_events().contains(&RelayEvent::PeerClosed {
            reason: wire.into()
        }));
        assert_eq!(link.hub_close(), Some(code));
    }
    let mut link = established(&remote_keys(), &hub_keys(), 1);
    let s = link
        .remote
        .send_raw_record_for_tests(0, inner::CLOSE, &[4, 0], T0)
        .unwrap();
    link.remote_step(s);
    link.pump();
    assert!(link.hub_events().contains(&RelayEvent::PeerClosed {
        reason: "Protocol".into()
    }));
    assert_eq!(link.hub_close(), Some(ws_close::PROTOCOL));
}

/// A ServerHello too long to length-prefix in the prologue is refused, not
/// truncated into it.
#[test]
fn oversize_server_hello_is_not_truncated_into_the_prologue() {
    let r = remote_keys();
    let h = hub_keys();
    let (mut remote, _) =
        de1_relay::RemoteConnection::resume(&r, h.public(), test_seed("remote-conn", 1), T0);
    let sh = de1_relay::ServerHello {
        version: 4,
        status: de1_relay::HelloStatus::Ready,
        hub_id: h.device_id(),
        secs_left: 0,
        name: "Hub".into(),
        ext: vec![0; 65_509],
    }
    .encode();
    assert_eq!(sh.len(), de1_relay::WS_MAX_MESSAGE);
    let s = remote.on_ws(WsIn::Binary(&sh), T0);
    assert!(!s.send.iter().any(|m| m.bytes[0] == kind::NOISE_HS));
    assert_eq!(s.close.as_ref().unwrap().code, ws_close::PROTOCOL);
    assert_eq!(remote.phase(), Phase::Failed);
}
