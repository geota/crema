//! AC-6: the pairing gate.

mod common;

use common::*;
use de1_relay::{
    ClientHello, HubConnection, Intent, PAIR_LOCKOUT_MS, PAIR_WINDOW_MS, PairingGate, RelayError,
    RelayEvent, ServerHello, WsIn, kind, test_support::test_seed, ws_close,
};

fn pair_hello() -> Vec<u8> {
    ClientHello {
        vmin: 4,
        vmax: 4,
        intent: Intent::Pair,
        client_id: Some(remote_keys().device_id()),
        client_name: "Phone".into(),
    }
    .encode()
}

fn status_of(step: &de1_relay::Step) -> de1_relay::HelloStatus {
    ServerHello::parse(&step.send[0].bytes).unwrap().status
}

fn wrong_attempt(
    gate: &std::sync::Arc<std::sync::Mutex<PairingGate>>,
    n: u32,
    now: u64,
) -> Vec<RelayEvent> {
    let mut link = Link::pairing(
        &remote_keys(),
        &hub_keys(),
        gate.clone(),
        new_pins(vec![]),
        n,
        now,
    );
    link.pump();
    let code = link.shown_code().unwrap();
    link.enter_code(&wrong_code(&code));
    link.hub_events()
}

#[test]
fn second_request_is_busy() {
    let gate = new_gate();
    open_gate(&gate, T0);
    let pins = new_pins(vec![]);
    let h = hub_keys();
    let mut first = HubConnection::new(
        &h,
        gate.clone(),
        pins.clone(),
        "Hub",
        test_seed("hub-conn", 0),
        T0,
    );
    let s = first.on_ws(WsIn::Text(&pair_hello()), T0);
    assert_eq!(status_of(&s), de1_relay::HelloStatus::AwaitingCode);
    assert!(gate.lock().unwrap().status(T0).pending);
    let mut second = HubConnection::new(
        &h,
        gate.clone(),
        pins.clone(),
        "Hub",
        test_seed("hub-conn", 1),
        T0,
    );
    let s = second.on_ws(WsIn::Text(&pair_hello()), T0);
    assert_eq!(status_of(&s), de1_relay::HelloStatus::Busy);
    assert_eq!(s.close.unwrap().code, ws_close::REFUSED);
    // When the first goes away, the slot is free again.
    drop(first);
    let mut third = HubConnection::new(&h, gate.clone(), pins, "Hub", test_seed("hub-conn", 2), T0);
    let s = third.on_ws(WsIn::Text(&pair_hello()), T0);
    assert_eq!(status_of(&s), de1_relay::HelloStatus::AwaitingCode);
}

#[test]
fn three_failures_lock_out() {
    let gate = new_gate();
    open_gate(&gate, T0);
    for n in 0..2 {
        let events = wrong_attempt(&gate, n, T0);
        assert!(events.iter().any(|e| matches!(e, RelayEvent::PairingFailed { failures, locked: false, .. } if *failures == u8::try_from(n + 1).unwrap())));
        assert!(gate.lock().unwrap().status(T0).open);
    }
    let events = wrong_attempt(&gate, 2, T0);
    assert!(events.iter().any(|e| matches!(
        e,
        RelayEvent::PairingFailed {
            failures: 3,
            locked: true,
            ..
        }
    )));
    let status = gate.lock().unwrap().status(T0);
    assert!(!status.open);
    assert_eq!(status.locked_secs_left, 60);
    // New requests get LOCKED_OUT; open() is refused with the seconds left.
    let mut hub = HubConnection::new(
        &hub_keys(),
        gate.clone(),
        new_pins(vec![]),
        "Hub",
        test_seed("hub-conn", 3),
        T0 + 18_000,
    );
    let s = hub.on_ws(WsIn::Text(&pair_hello()), T0 + 18_000);
    let sh = ServerHello::parse(&s.send[0].bytes).unwrap();
    assert_eq!(sh.status, de1_relay::HelloStatus::LockedOut);
    assert_eq!(sh.secs_left, 42);
    assert_eq!(
        gate.lock().unwrap().open(T0 + 18_000),
        Err(RelayError::LockedOut { secs_left: 42 })
    );
    // After 60 s pairing can be opened again, with a fresh count.
    let after = T0 + PAIR_LOCKOUT_MS;
    let status = gate.lock().unwrap().open(after).unwrap();
    assert!(status.open && status.failures == 0 && status.locked_secs_left == 0);
}

#[test]
fn success_closes_window() {
    let link = paired_link(&remote_keys(), &hub_keys(), 0);
    let status = link.gate.lock().unwrap().status(link.now);
    assert!(!status.open && !status.pending && status.failures == 0);
    let mut hub = HubConnection::new(
        &hub_keys(),
        link.gate.clone(),
        new_pins(vec![]),
        "Hub",
        test_seed("hub-conn", 9),
        T0,
    );
    let s = hub.on_ws(WsIn::Text(&pair_hello()), T0);
    assert_eq!(status_of(&s), de1_relay::HelloStatus::PairingClosed);
}

/// Cancels (either side), a closed gate, timeouts and the window's end free
/// the slot without counting a failure.
#[test]
fn cancel_is_not_a_failure() {
    let gate = new_gate();
    open_gate(&gate, T0);
    let (r, h) = (remote_keys(), hub_keys());

    // Hub user cancels.
    let mut link = Link::pairing(&r, &h, gate.clone(), new_pins(vec![]), 0, T0);
    link.pump();
    let s = link.hub.cancel_pairing(T0);
    assert_eq!(s.send[0].bytes, vec![kind::ABORT, 4, 0]);
    link.hub_step(s);
    link.pump();
    assert!(link.remote_events().iter().any(|e| matches!(e, RelayEvent::Refused { reason, authenticated: false } if reason == "Cancelled")));

    // Remote user cancels while showing the code.
    let mut link = Link::pairing(&r, &h, gate.clone(), new_pins(vec![]), 1, T0);
    link.pump();
    let s = link.remote.close(de1_relay::CloseReason::Normal, T0);
    assert_eq!(s.send[0].bytes, vec![kind::ABORT, 4, 0]);
    link.remote_step(s);
    link.pump();
    assert!(link.hub_events().iter().any(|e| matches!(e, RelayEvent::PairingFailed { reason, failures: 0, .. } if reason == "Cancelled")));

    // The hub user closes the window: the slot holder aborts on its next call.
    let mut link = Link::pairing(&r, &h, gate.clone(), new_pins(vec![]), 2, T0);
    link.pump();
    gate.lock().unwrap().close();
    let s = link.hub.tick(T0);
    assert_eq!(s.send[0].bytes, vec![kind::ABORT, 4, 0]);
    assert_eq!(s.close.unwrap().code, ws_close::REFUSED);
    open_gate(&gate, T0);

    // No PAKE_A within 5 s.
    let mut hub = HubConnection::new(
        &h,
        gate.clone(),
        new_pins(vec![]),
        "Hub",
        test_seed("hub-conn", 3),
        T0,
    );
    hub.on_ws(WsIn::Text(&pair_hello()), T0);
    let s = hub.tick(T0 + 5_000);
    assert_eq!(s.send[0].bytes, vec![kind::ABORT, 5, 0]);

    // The window ends while waiting for the code.
    let mut link = Link::pairing(&r, &h, gate.clone(), new_pins(vec![]), 4, T0);
    link.pump();
    let until = link.hub.next_deadline_ms().unwrap();
    assert_eq!(until, T0 + PAIR_WINDOW_MS);
    let s = link.hub.tick(until);
    assert_eq!(s.send[0].bytes, vec![kind::ABORT, 5, 0]);

    let status = gate.lock().unwrap().status(T0);
    assert_eq!(status.failures, 0);
    assert!(status.open);
}

/// A slot whose connection vanished without releasing it can't block pairing
/// past its lease.
#[test]
fn leaked_slot_expires() {
    let gate = new_gate();
    open_gate(&gate, T0);
    let h = hub_keys();
    let mut leaked = HubConnection::new(
        &h,
        gate.clone(),
        new_pins(vec![]),
        "Hub",
        test_seed("hub-conn", 0),
        T0,
    );
    leaked.on_ws(WsIn::Text(&pair_hello()), T0);
    std::mem::forget(leaked);
    // While the lease runs, others are busy.
    let mut busy = HubConnection::new(
        &h,
        gate.clone(),
        new_pins(vec![]),
        "Hub",
        test_seed("hub-conn", 1),
        T0 + 1,
    );
    assert_eq!(
        status_of(&busy.on_ws(WsIn::Text(&pair_hello()), T0 + 1)),
        de1_relay::HelloStatus::Busy
    );
    // The window ends on its own (no close()), the user opens a new one.
    let later = T0 + PAIR_WINDOW_MS;
    open_gate(&gate, later);
    let mut next = HubConnection::new(
        &h,
        gate.clone(),
        new_pins(vec![]),
        "Hub",
        test_seed("hub-conn", 2),
        later,
    );
    let s = next.on_ws(WsIn::Text(&pair_hello()), later);
    assert_eq!(status_of(&s), de1_relay::HelloStatus::AwaitingCode);
}
