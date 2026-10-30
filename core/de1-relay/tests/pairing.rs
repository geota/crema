//! AC-3 – AC-6, AC-11 (pairing replay), AC-20 (code entry format).

mod common;

use common::*;
use de1_relay::{
    DeviceId, HubConnection, Phase, RelayError, RelayEvent, WsIn, kind, test_support::test_seed,
    ws_close,
};

fn failures(link: &Link) -> u8 {
    link.gate.lock().unwrap().status(link.now).failures
}

#[test]
fn pairs_with_matching_code() {
    let (r, h) = (remote_keys(), hub_keys());
    let link = paired_link(&r, &h, 0);
    let hub_events = link.hub_events();
    let remote_events = link.remote_events();
    assert!(hub_events.contains(&RelayEvent::Paired {
        peer_id: r.device_id().to_hex(),
        peer_name: "Pixel 8".into(),
    }));
    assert!(remote_events.contains(&RelayEvent::Paired {
        peer_id: h.device_id().to_hex(),
        peer_name: "Kitchen tablet".into(),
    }));
    // Each holds the other's static key, whose id matched the hello's id.
    assert_eq!(link.hub.peer_public_key(), Some(r.public()));
    assert_eq!(link.remote.peer_public_key(), Some(h.public()));
    assert_eq!(
        DeviceId::of(&link.hub.peer_public_key().unwrap()),
        r.device_id()
    );
    assert_eq!(
        DeviceId::of(&link.remote.peer_public_key().unwrap()),
        h.device_id()
    );
    assert_eq!(link.hub.phase(), Phase::Paired);
    assert_eq!(link.remote.phase(), Phase::Paired);
    assert_eq!(link.hub_close(), Some(ws_close::NORMAL));
    assert_eq!(link.remote_close(), Some(ws_close::NORMAL));
    assert_eq!(link.hub.channel_binding(), link.remote.channel_binding());
    assert_eq!(link.hub.psk_for_tests(), link.remote.psk_for_tests());
    // PairPending came first, then Paired after the commit.
    let pending = hub_events
        .iter()
        .position(|e| matches!(e, RelayEvent::PairPending { .. }))
        .unwrap();
    let paired = hub_events.iter().position(is_paired).unwrap();
    assert!(pending < paired);
    // Success closes the window.
    assert!(!link.gate.lock().unwrap().status(link.now).open);
}

#[test]
fn wrong_code_fails_both_sides() {
    let (r, h) = (remote_keys(), hub_keys());
    let gate = new_gate();
    open_gate(&gate, T0);
    let mut link = Link::pairing(&r, &h, gate, new_pins(vec![]), 0, T0);
    link.pump();
    let code = link.shown_code().unwrap();
    link.enter_code(&wrong_code(&code));
    let hub_events = link.hub_events();
    let remote_events = link.remote_events();
    assert!(hub_events.iter().any(|e| matches!(e, RelayEvent::PairingFailed { reason, failures: 1, locked: false } if reason == "WrongCode")));
    assert!(remote_events.iter().any(
        |e| matches!(e, RelayEvent::PairingFailed { reason, .. } if reason == "NotConfirmed")
    ));
    assert!(!has_event(&hub_events, is_paired) && !has_event(&remote_events, is_paired));
    assert!(!has_event(&hub_events, |e| matches!(
        e,
        RelayEvent::PairPending { .. }
    )));
    assert!(link.hub_delivered().is_empty() && link.remote_delivered().is_empty());
    // The hub's last message is ABORT(HANDSHAKE_FAILED).
    let last = link.sent(Dir::ToRemote).last().unwrap().bytes.clone();
    assert_eq!(last, vec![kind::ABORT, 6, 0]);
    assert_eq!(link.hub_close(), Some(ws_close::AUTH));
    assert_eq!(failures(&link), 1);
    assert!(link.pins.read().unwrap().is_empty());
}

#[test]
fn code_is_single_use() {
    let (r, h) = (remote_keys(), hub_keys());
    let gate = new_gate();
    open_gate(&gate, T0);
    let mut first = Link::pairing(&r, &h, gate.clone(), new_pins(vec![]), 0, T0);
    first.pump();
    let code1 = first.shown_code().unwrap();
    assert_eq!(first.remote.code(), Some(code1.clone()));
    first.enter_code(&wrong_code(&code1));
    // Spent: the remote no longer has a code, and a second ServerHello can't restart SPAKE2.
    assert_eq!(first.remote.code(), None);
    let pake_a1 = first.sent(Dir::ToHub)[1].bytes.clone();
    let sh = first.sent(Dir::ToRemote)[0].bytes.clone();
    let again = first.remote.on_ws(WsIn::Binary(&sh), T0);
    assert!(again.send.is_empty());
    assert!(
        !again
            .events
            .iter()
            .any(|e| matches!(e, RelayEvent::ShowCode { .. }))
    );

    // The next attempt is a new connection with a new seed: new code, new PAKE_A.
    let mut second = Link::pairing(&r, &h, gate, new_pins(vec![]), 1, T0);
    second.pump();
    let code2 = second.shown_code().unwrap();
    assert_ne!(code1, code2);
    assert_ne!(second.sent(Dir::ToHub)[1].bytes, pake_a1);
    assert_eq!(second.sent(Dir::ToHub)[1].bytes[0], kind::PAKE_A);
}

#[test]
fn one_spake_run_per_connection() {
    let (r, h) = (remote_keys(), hub_keys());
    let gate = new_gate();
    open_gate(&gate, T0);
    let mut link = Link::pairing(&r, &h, gate, new_pins(vec![]), 0, T0);
    link.pump();
    let code = link.shown_code().unwrap();
    let first = link.hub.enter_code(&code, T0).unwrap();
    assert_eq!(first.send.len(), 1);
    assert_eq!(first.send[0].bytes[0], kind::PAKE_B);
    assert_eq!(
        link.hub.enter_code(&code, T0),
        Err(RelayError::InvalidState)
    );
    assert_eq!(
        link.hub.enter_code(&wrong_code(&code), T0),
        Err(RelayError::InvalidState)
    );
    // Also after a wrong code ended the connection.
    let gate = new_gate();
    open_gate(&gate, T0);
    let mut link = Link::pairing(&r, &h, gate, new_pins(vec![]), 1, T0);
    link.pump();
    let code = link.shown_code().unwrap();
    link.enter_code(&wrong_code(&code));
    assert_eq!(
        link.hub.enter_code(&code, T0),
        Err(RelayError::InvalidState)
    );
}

/// Three connections with a malformed `PAKE_A` count nothing and leave the
/// window open; a real pairing still succeeds afterwards.
#[test]
fn malformed_pake_a_is_not_a_failure() {
    let (r, h) = (remote_keys(), hub_keys());
    let gate = new_gate();
    open_gate(&gate, T0);
    let pins = new_pins(vec![]);
    for n in 0..3 {
        let mut hub = HubConnection::new(
            &h,
            gate.clone(),
            pins.clone(),
            "Hub",
            test_seed("hub-conn", n),
            T0,
        );
        let hello = de1_relay::ClientHello {
            vmin: 4,
            vmax: 4,
            intent: de1_relay::Intent::Pair,
            client_id: Some(r.device_id()),
            client_name: "Attacker".into(),
        };
        let s = hub.on_ws(WsIn::Text(&hello.encode()), T0);
        assert!(
            s.events
                .iter()
                .any(|e| matches!(e, RelayEvent::PairRequest { .. }))
        );
        let mut identity = vec![kind::PAKE_A, b'A', 1];
        identity.extend([0_u8; 31]);
        let s = hub.on_ws(WsIn::Binary(&identity), T0);
        assert_eq!(s.close.unwrap().code, ws_close::PROTOCOL);
        assert_eq!(s.send[0].bytes, vec![kind::ABORT, 9, 0]);
        let status = gate.lock().unwrap().status(T0);
        assert_eq!(status.failures, 0);
        assert!(status.open && !status.pending);
    }
    let mut link = Link::pairing(&r, &h, gate, pins, 5, T0);
    link.pump();
    let code = link.shown_code().unwrap();
    link.enter_code(&code);
    link.commit();
    assert!(has_event(&link.remote_events(), is_paired));
}

/// Code entered, `PAKE_B` sent, then no message 3 within `PAIR_FINISH_MS`: not a failure.
#[test]
fn timeout_after_code_entry_is_not_a_failure() {
    let (r, h) = (remote_keys(), hub_keys());
    let gate = new_gate();
    open_gate(&gate, T0);
    let mut link = Link::pairing(&r, &h, gate, new_pins(vec![]), 0, T0);
    link.pump();
    let code = link.shown_code().unwrap();
    // The remote's handshake never arrives.
    link.set_tamper(Box::new(|dir, m| {
        (dir == Dir::ToRemote || m.bytes[0] != kind::NOISE_HS).then_some(m)
    }));
    link.enter_code(&code);
    assert_eq!(link.hub.phase(), Phase::Handshake);
    assert_eq!(
        link.hub.next_deadline_ms(),
        Some(T0 + de1_relay::PAIR_FINISH_MS)
    );
    let step = link.hub.tick(T0 + de1_relay::PAIR_FINISH_MS);
    assert_eq!(step.send[0].bytes, vec![kind::ABORT, 5, 0]);
    assert_eq!(step.close.unwrap().code, ws_close::TIMEOUT);
    assert!(step.events.iter().any(|e| matches!(e, RelayEvent::PairingFailed { reason, failures: 0, .. } if reason == "Timeout")));
    let status = link
        .gate
        .lock()
        .unwrap()
        .status(T0 + de1_relay::PAIR_FINISH_MS);
    assert_eq!(status.failures, 0);
    assert!(status.open && !status.pending);
}

#[test]
fn reject_is_not_a_failure() {
    let (r, h) = (remote_keys(), hub_keys());
    let gate = new_gate();
    open_gate(&gate, T0);
    let mut link = Link::pairing(&r, &h, gate, new_pins(vec![]), 0, T0);
    link.pump();
    let code = link.shown_code().unwrap();
    link.enter_code(&code);
    assert_eq!(link.hub.phase(), Phase::AwaitCommit);
    let step = link.hub.reject_pairing(T0);
    assert_eq!(step.send.len(), 1);
    assert_eq!(step.send[0].bytes, vec![kind::ABORT, 4, 0]);
    assert!(step.events.iter().any(|e| matches!(e, RelayEvent::PairingFailed { reason, failures: 0, .. } if reason == "Cancelled")));
    link.hub_step(step);
    link.pump();
    assert_eq!(failures(&link), 0);
    assert!(link.pins.read().unwrap().is_empty());
    assert!(!has_event(&link.remote_events(), is_paired));
    let remote_events = link.remote_events();
    assert!(remote_events.iter().any(|e| matches!(e, RelayEvent::Refused { reason, authenticated: false } if reason == "Cancelled")));
    assert!(remote_events.iter().any(
        |e| matches!(e, RelayEvent::PairingFailed { reason, .. } if reason == "NotConfirmed")
    ));
    assert!(link.gate.lock().unwrap().status(T0).open);
}

/// The remote's flights of a complete pairing, replayed against a fresh hub
/// connection with the same code, fail.
#[test]
fn replayed_pairing_fails() {
    let (r, h) = (remote_keys(), hub_keys());
    let original = paired_link(&r, &h, 0);
    let code = original.shown_code().unwrap();
    let flights = original.sent(Dir::ToHub);
    assert_eq!(flights.len(), 4); // ClientHello, PAKE_A, NOISE_HS #1, #3

    let gate = new_gate();
    open_gate(&gate, T0);
    let pins = new_pins(vec![]);
    let mut hub = HubConnection::new(
        &h,
        gate.clone(),
        pins.clone(),
        "Kitchen tablet",
        test_seed("hub-conn", 77),
        T0,
    );
    let mut steps = vec![
        hub.on_ws(ws(&flights[0]), T0),
        hub.on_ws(ws(&flights[1]), T0),
    ];
    steps.push(hub.enter_code(&code, T0).unwrap());
    steps.push(hub.on_ws(ws(&flights[2]), T0));
    steps.push(hub.on_ws(ws(&flights[3]), T0));
    let events: Vec<RelayEvent> = steps.iter().flat_map(|s| s.events.clone()).collect();
    assert!(!events.iter().any(|e| matches!(
        e,
        RelayEvent::PairPending { .. } | RelayEvent::Paired { .. }
    )));
    assert!(
        events
            .iter()
            .any(|e| matches!(e, RelayEvent::PairingFailed { .. }))
    );
    assert_eq!(hub.peer_public_key(), None);
    assert!(pins.read().unwrap().is_empty());
    // An ended attempt never confirms.
    assert_eq!(hub.confirm_paired(T0), Err(RelayError::Closed));
}

/// `enter_code` refuses anything but six ASCII digits with `CodeFormat`, without
/// a state change or an attempt; the right code still works afterwards.
#[test]
fn bad_code_format_is_not_an_attempt() {
    let (r, h) = (remote_keys(), hub_keys());
    let gate = new_gate();
    open_gate(&gate, T0);
    let mut link = Link::pairing(&r, &h, gate, new_pins(vec![]), 0, T0);
    link.pump();
    let code = link.shown_code().unwrap();
    for bad in [
        "",
        "12345",
        "1234567",
        "12345a",
        " 12345",
        "12 345",
        "١٢٣٤٥٦",
        "１２３４５６",
        "-12345",
        "123456\n",
    ] {
        assert_eq!(
            link.hub.enter_code(bad, T0),
            Err(RelayError::CodeFormat),
            "{bad:?}"
        );
        assert_eq!(link.hub.phase(), Phase::AwaitCode);
    }
    assert_eq!(failures(&link), 0);
    link.enter_code(&code);
    link.commit();
    assert!(has_event(&link.remote_events(), is_paired));
}

/// `enter_code` on an ended attempt is an `Err`, like `confirm_paired`.
#[test]
fn enter_code_on_an_ended_attempt_errs() {
    let gate = new_gate();
    open_gate(&gate, T0);
    let mut link = Link::pairing(&remote_keys(), &hub_keys(), gate, new_pins(vec![]), 0, T0);
    link.pump();
    let code = link.shown_code().unwrap();
    let end = link.hub.next_deadline_ms().unwrap();
    assert_eq!(link.hub.enter_code(&code, end), Err(RelayError::Timeout));
    let held = link.hub.tick(end);
    assert_eq!(held.send[0].bytes, vec![kind::ABORT, 5, 0]);
    assert_eq!(link.hub.enter_code(&code, end), Err(RelayError::Closed));
}
