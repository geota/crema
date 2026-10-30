//! AC-15: every phase has a deadline, and passing it closes with the
//! specified code (and `ABORT(TIMEOUT)` where the handshake isn't done yet).

mod common;

use common::*;
use de1_relay::{
    ClientHello, HEARTBEAT_IDLE_MS, HubConnection, Intent, PAIR_FINISH_MS, Phase, RECV_TIMEOUT_MS,
    RESUME_DEADLINE_MS, RelayEvent, RemoteConnection, Step, WsIn, inner, kind,
    test_support::test_seed, ws_close,
};

const ABORT_TIMEOUT: [u8; 3] = [kind::ABORT, 5, 0];

fn assert_timeout(step: &Step, abort: bool) {
    assert_eq!(
        step.close.as_ref().map(|c| c.code),
        Some(ws_close::TIMEOUT),
        "{step:?}"
    );
    if abort {
        assert_eq!(step.send.len(), 1);
        assert_eq!(step.send[0].bytes, ABORT_TIMEOUT);
    } else {
        assert!(step.send.is_empty(), "{step:?}");
    }
}

/// Ticks one millisecond early (nothing happens) and then at the deadline.
fn expire_hub(hub: &mut HubConnection) -> Step {
    let d = hub
        .next_deadline_ms()
        .expect("every live phase has a deadline");
    assert!(hub.tick(d - 1).close.is_none());
    hub.tick(d)
}

fn expire_remote(remote: &mut RemoteConnection) -> Step {
    let d = remote
        .next_deadline_ms()
        .expect("every live phase has a deadline");
    assert!(remote.tick(d - 1).close.is_none());
    remote.tick(d)
}

#[test]
fn hub_waits_5s_for_the_hello() {
    let mut hub = HubConnection::new(
        &hub_keys(),
        new_gate(),
        new_pins(vec![]),
        "Hub",
        test_seed("hub-conn", 0),
        T0,
    );
    assert_eq!(hub.next_deadline_ms(), Some(T0 + 5_000));
    assert_timeout(&expire_hub(&mut hub), true);
    assert_eq!(hub.phase(), Phase::Failed);
    assert_eq!(hub.next_deadline_ms(), None);
}

#[test]
fn hub_pairing_phases_time_out() {
    let (r, h) = (remote_keys(), hub_keys());
    // AwaitCode, no PAKE_A: 5 s.
    let gate = new_gate();
    open_gate(&gate, T0);
    let mut hub = HubConnection::new(
        &h,
        gate.clone(),
        new_pins(vec![]),
        "Hub",
        test_seed("hub-conn", 0),
        T0,
    );
    let hello = ClientHello {
        vmin: 4,
        vmax: 4,
        intent: Intent::Pair,
        client_id: Some(r.device_id()),
        client_name: "R".into(),
    };
    hub.on_ws(WsIn::Text(&hello.encode()), T0);
    assert_eq!(hub.phase(), Phase::AwaitCode);
    assert_eq!(hub.next_deadline_ms(), Some(T0 + 5_000));
    assert_timeout(&expire_hub(&mut hub), true);

    // AwaitCode with PAKE_A: until the window ends (covered in gate.rs); Handshake: PAIR_FINISH_MS.
    let mut link = Link::pairing(&r, &h, gate.clone(), new_pins(vec![]), 1, T0);
    link.pump();
    link.set_tamper(Box::new(|dir, m| (dir == Dir::ToRemote).then_some(m)));
    link.enter_code(&link.shown_code().unwrap());
    assert_eq!(link.hub.phase(), Phase::Handshake);
    assert_eq!(link.hub.next_deadline_ms(), Some(T0 + PAIR_FINISH_MS));
    assert_timeout(&expire_hub(&mut link.hub), true);

    // AwaitCommit: the shell never commits.
    let mut link = Link::pairing(&r, &h, gate, new_pins(vec![]), 2, T0);
    link.pump();
    link.enter_code(&link.shown_code().unwrap());
    assert_eq!(link.hub.phase(), Phase::AwaitCommit);
    assert_eq!(link.hub.next_deadline_ms(), Some(T0 + PAIR_FINISH_MS));
    let s = expire_hub(&mut link.hub);
    assert_timeout(&s, true);
    assert!(s.events.iter().any(|e| matches!(e, RelayEvent::PairingFailed { reason, failures: 0, .. } if reason == "Timeout")));
}

#[test]
fn hub_resume_phases_time_out() {
    let (r, h) = (remote_keys(), hub_keys());
    // Handshake: no IK #1 within 5 s of the ServerHello.
    let mut hub = HubConnection::new(
        &h,
        new_gate(),
        new_pins(vec![r.public()]),
        "Hub",
        test_seed("hub-conn", 0),
        T0,
    );
    hub.on_ws(WsIn::Text(&ClientHello::resume().encode()), T0 + 1_000);
    assert_eq!(
        hub.next_deadline_ms(),
        Some(T0 + 1_000 + RESUME_DEADLINE_MS)
    );
    assert_timeout(&expire_hub(&mut hub), true);

    // AwaitClientFinished: the handshake is done on our side, so no plaintext ABORT.
    let mut link = Link::resume(&r, &h, new_gate(), new_pins(vec![r.public()]), 1, T0);
    link.set_tamper(Box::new(|dir, m| {
        (dir == Dir::ToRemote || m.bytes.first() != Some(&kind::RECORD)).then_some(m)
    }));
    link.pump();
    assert_eq!(link.hub.phase(), Phase::AwaitClientFinished);
    assert_eq!(link.hub.next_deadline_ms(), Some(T0 + RESUME_DEADLINE_MS));
    assert_timeout(&expire_hub(&mut link.hub), false);
}

#[test]
fn remote_phases_time_out() {
    let (r, h) = (remote_keys(), hub_keys());
    // AwaitHello: 5 s, and nothing is sent (the hub may be a legacy one).
    let (mut remote, _) = RemoteConnection::resume(&r, h.public(), test_seed("remote-conn", 0), T0);
    assert_eq!(remote.next_deadline_ms(), Some(T0 + 5_000));
    let s = expire_remote(&mut remote);
    assert_timeout(&s, false);
    assert!(s.events.contains(&RelayEvent::Refused {
        reason: "Timeout".into(),
        authenticated: false
    }));

    // Pake: the code's lifetime (min(120 s, secs_left)).
    let gate = new_gate();
    open_gate(&gate, T0);
    let mut link = Link::pairing(&r, &h, gate.clone(), new_pins(vec![]), 0, T0);
    link.pump();
    assert_eq!(link.remote.phase(), Phase::Pake);
    let expires = link.remote_events().iter().find_map(|e| match e {
        RelayEvent::ShowCode { expires_at_ms, .. } => Some(*expires_at_ms),
        _ => None,
    });
    assert_eq!(expires, Some(T0 + 120_000));
    assert_eq!(link.remote.next_deadline_ms(), expires);
    let s = expire_remote(&mut link.remote);
    assert_timeout(&s, true);
    assert_eq!(link.remote.code(), None);
    drop(link); // frees the hub's pairing slot

    // Handshake (pair) and AwaitConfirm: PAIR_FINISH_MS after PAKE_B.
    let mut link2 = Link::pairing(&r, &h, gate.clone(), new_pins(vec![]), 2, T0);
    link2.pump();
    link2.set_tamper(Box::new(|dir, m| {
        (dir == Dir::ToRemote || m.bytes[0] != kind::NOISE_HS).then_some(m)
    }));
    link2.enter_code(&link2.shown_code().unwrap());
    assert_eq!(link2.remote.phase(), Phase::Handshake);
    assert_eq!(link2.remote.next_deadline_ms(), Some(T0 + PAIR_FINISH_MS));
    assert_timeout(&expire_remote(&mut link2.remote), true);
    drop(link2);

    let mut link3 = Link::pairing(&r, &h, gate, new_pins(vec![]), 3, T0);
    link3.pump();
    link3.enter_code(&link3.shown_code().unwrap());
    assert_eq!(link3.remote.phase(), Phase::AwaitConfirm);
    let s = expire_remote(&mut link3.remote);
    assert_timeout(&s, true);
    assert!(s.events.iter().any(
        |e| matches!(e, RelayEvent::PairingFailed { reason, .. } if reason == "NotConfirmed")
    ));

    // Handshake (resume): no IK #2 within 5 s.
    let mut link = Link::resume(&r, &h, new_gate(), new_pins(vec![r.public()]), 1, T0);
    link.set_tamper(Box::new(|dir, m| {
        (dir == Dir::ToHub || m.bytes[0] != kind::NOISE_HS).then_some(m)
    }));
    link.pump();
    assert_eq!(link.remote.phase(), Phase::Handshake);
    assert_eq!(
        link.remote.next_deadline_ms(),
        Some(T0 + RESUME_DEADLINE_MS)
    );
    let s = expire_remote(&mut link.remote);
    assert_timeout(&s, true);
}

/// Established: an idle sender heartbeats after 10 s; 30 s without a complete
/// message or heartbeat closes with 4408.
#[test]
fn heartbeats_and_receive_timeout() {
    let mut link = established(&remote_keys(), &hub_keys(), 1);
    assert_eq!(link.hub.next_deadline_ms(), Some(T0 + HEARTBEAT_IDLE_MS));
    // Both heartbeat; each side's heartbeat keeps the other alive.
    for k in 1..=5 {
        link.tick(T0 + k * HEARTBEAT_IDLE_MS);
        assert_eq!(link.hub.phase(), Phase::Established);
        assert_eq!(link.remote.phase(), Phase::Established);
    }
    let heartbeats = link
        .sent(Dir::ToRemote)
        .iter()
        .filter(|m| m.bytes[0] == kind::RECORD)
        .count();
    assert_eq!(heartbeats, 5);

    // The hub goes silent (its records are dropped): the remote closes 30 s after the last one.
    let last = link.now;
    link.set_tamper(Box::new(|dir, m| (dir == Dir::ToHub).then_some(m)));
    link.tick(last + RECV_TIMEOUT_MS - 1);
    assert_eq!(link.remote.phase(), Phase::Established);
    let s = link.remote.tick(last + RECV_TIMEOUT_MS);
    assert_eq!(s.close.unwrap().code, ws_close::TIMEOUT);
    assert!(s.events.contains(&RelayEvent::Failed {
        error: "Timeout".into()
    }));
    let _ = inner::HEARTBEAT;
}

/// The code lives `min(120 s, secs_left)`: with 30 s left in the hub's window
/// the remote's code expires after 30 s, not 120.
#[test]
fn code_lifetime_is_capped_by_the_window() {
    let gate = new_gate();
    open_gate(&gate, T0);
    let late = T0 + 90_000;
    let mut link = Link::pairing(&remote_keys(), &hub_keys(), gate, new_pins(vec![]), 0, late);
    link.pump();
    let expires = link.remote_events().iter().find_map(|e| match e {
        RelayEvent::ShowCode { expires_at_ms, .. } => Some(*expires_at_ms),
        _ => None,
    });
    assert_eq!(expires, Some(late + 30_000));
    assert_eq!(link.remote.next_deadline_ms(), Some(late + 30_000));
    let s = expire_remote(&mut link.remote);
    assert_timeout(&s, true);
}
