//! AC-47 and AC-48: the live pin set and the two-phase pairing commit.

mod common;

use common::*;
use de1_relay::{Phase, RelayError, RelayEvent, kind, ws_close};

fn not_paired_anywhere(link: &Link) -> bool {
    !link
        .hub_events()
        .iter()
        .any(|e| matches!(e, RelayEvent::UnknownRemote { .. }))
        && !link
            .remote_events()
            .iter()
            .any(|e| matches!(e, RelayEvent::Refused { reason, .. } if reason == "NotPaired"))
}

/// Pairing, then an immediate resume, with the hub shell's pin write delayed
/// by 500 ms: the resume reaches `Established` and `NOT_PAIRED` is never sent.
#[test]
fn resume_right_after_pairing() {
    let (r, h) = (remote_keys(), hub_keys());
    let gate = new_gate();
    open_gate(&gate, T0);
    let pins = new_pins(vec![]);
    let mut pairing = Link::pairing(&r, &h, gate.clone(), pins.clone(), 0, T0);
    pairing.pump();
    let code = pairing.shown_code().unwrap();
    pairing.enter_code(&code);
    assert_eq!(pairing.hub.phase(), Phase::AwaitCommit);
    // The shell's write takes 500 ms; meanwhile the remote has no PAIR_COMPLETE.
    pairing.now = T0 + 500;
    assert!(pairing.remote.phase() == Phase::AwaitConfirm);
    pairing.commit();
    assert!(has_event(&pairing.remote_events(), is_paired));

    // The remote resumes at once, against the same live pin set.
    let pinned_hub = pairing.remote.peer_public_key().unwrap();
    assert_eq!(pinned_hub, h.public());
    let hub = de1_relay::HubConnection::new(
        &h,
        gate.clone(),
        pins.clone(),
        "Kitchen tablet",
        de1_relay::test_support::test_seed("hub-conn", 10),
        T0 + 500,
    );
    let (remote, first) = de1_relay::RemoteConnection::resume(
        &r,
        pinned_hub,
        de1_relay::test_support::test_seed("remote-conn", 10),
        T0 + 500,
    );
    let mut resume = Link::with(hub, remote, first, gate, pins, T0 + 500);
    resume.pump();
    assert_eq!(resume.hub.phase(), Phase::Established);
    assert_eq!(resume.remote.phase(), Phase::Established);
    assert!(not_paired_anywhere(&resume));
}

/// `PAIR_COMPLETE` waits for `confirm_paired`, which in turn waits for the pin
/// to be in the live set.
#[test]
fn no_pair_complete_before_confirm() {
    let (r, h) = (remote_keys(), hub_keys());
    let gate = new_gate();
    open_gate(&gate, T0);
    let mut link = Link::pairing(&r, &h, gate, new_pins(vec![]), 0, T0);
    link.pump();
    let code = link.shown_code().unwrap();
    link.enter_code(&code);
    assert!(
        link.hub_events()
            .iter()
            .any(|e| matches!(e, RelayEvent::PairPending { .. }))
    );
    // No record has gone to the remote, and time passing (within the deadline) sends none.
    assert!(
        !link
            .sent(Dir::ToRemote)
            .iter()
            .any(|m| m.bytes[0] == kind::RECORD)
    );
    let s = link.hub.tick(T0 + 9_999);
    assert!(s.is_empty());
    assert_eq!(link.remote.phase(), Phase::AwaitConfirm);
    // Confirming before the pin is in the live set is refused, state unchanged.
    assert_eq!(
        link.hub.confirm_paired(T0 + 9_999),
        Err(RelayError::InvalidState)
    );
    assert_eq!(link.hub.phase(), Phase::AwaitCommit);
    link.now = T0 + 9_999;
    link.commit();
    let records: Vec<_> = link
        .sent(Dir::ToRemote)
        .into_iter()
        .filter(|m| m.bytes[0] == kind::RECORD)
        .collect();
    assert_eq!(records.len(), 1);
    assert!(has_event(&link.remote_events(), is_paired));
}

/// `reject_pairing` sends `ABORT(CANCELLED)` and pins nothing.
#[test]
fn reject_pins_nothing() {
    let (r, h) = (remote_keys(), hub_keys());
    let gate = new_gate();
    open_gate(&gate, T0);
    let pins = new_pins(vec![]);
    let mut link = Link::pairing(&r, &h, gate, pins.clone(), 0, T0);
    link.pump();
    let code = link.shown_code().unwrap();
    link.enter_code(&code);
    let s = link.hub.reject_pairing(T0);
    assert_eq!(s.send.len(), 1);
    assert_eq!(s.send[0].bytes, vec![kind::ABORT, 4, 0]);
    assert_eq!(s.close.as_ref().unwrap().code, ws_close::REFUSED);
    link.hub_step(s);
    link.pump();
    assert!(pins.read().unwrap().is_empty());
    assert!(!has_event(&link.hub_events(), is_paired));
    assert!(!has_event(&link.remote_events(), is_paired));
    assert!(
        link.remote.peer_public_key().is_some(),
        "the remote saw the hub's key, but never pinned it"
    );
    assert_eq!(link.remote.phase(), Phase::Failed);
    // And a later resume from it is refused as unknown.
    let mut resume = Link::resume(&r, &h, new_gate(), pins, 11, T0);
    resume.pump();
    assert!(resume.remote_events().iter().any(|e| matches!(e, RelayEvent::Refused { reason, authenticated: true } if reason == "NotPaired")));
}

/// A Forget between IK message 1 and the client-finished record: not admitted,
/// and an authenticated `CLOSE(REVOKED)`.
#[test]
fn forget_between_msg1_and_admission() {
    let (r, h) = (remote_keys(), hub_keys());
    let pins = new_pins(vec![r.public()]);
    let mut link = Link::resume(&r, &h, new_gate(), pins.clone(), 1, T0);
    assert!(link.deliver_to_hub()); // ClientHello
    assert!(link.deliver_to_remote()); // ServerHello
    assert!(link.deliver_to_hub()); // IK #1
    assert_eq!(link.hub.phase(), Phase::AwaitClientFinished);
    // The hub user taps Forget: the shell removes the key from the live set first.
    pins.write().unwrap().remove(&r.public());
    link.pump();
    assert!(!has_event(&link.hub_events(), is_established));
    assert_eq!(link.hub.phase(), Phase::Closed);
    assert_eq!(link.hub_close(), Some(ws_close::REFUSED));
    assert!(link.remote_events().contains(&RelayEvent::Refused {
        reason: "Revoked".into(),
        authenticated: true
    }));
    assert!(link.hub_delivered().is_empty() && link.remote_delivered().is_empty());
}

/// A link in `AwaitCommit` with the remote's key already in the live pin set,
/// as the shell leaves it just before `confirm_paired`.
fn at_commit(n: u32) -> Link {
    let gate = new_gate();
    open_gate(&gate, T0);
    let mut link = Link::pairing(&remote_keys(), &hub_keys(), gate, new_pins(vec![]), n, T0);
    link.pump();
    link.enter_code(&link.shown_code().unwrap());
    assert_eq!(link.hub.phase(), Phase::AwaitCommit);
    let key = link.hub.peer_public_key().unwrap();
    link.pins.write().unwrap().insert(key).unwrap();
    link
}

/// After a failed `confirm_paired`, nothing confirmed the pairing: no record
/// went to the remote, nobody emitted `Paired`, and the gate counted nothing.
fn assert_not_confirmed(link: &Link) {
    assert!(
        !link
            .sent(Dir::ToRemote)
            .iter()
            .any(|m| m.bytes[0] == kind::RECORD)
    );
    assert!(!has_event(&link.hub_events(), is_paired));
    assert!(!has_event(&link.remote_events(), is_paired));
    assert_ne!(link.hub.phase(), Phase::Paired);
    assert_eq!(link.gate.lock().unwrap().status(link.now).failures, 0);
}

/// `confirm_paired` is `Ok` only when it sends `PAIR_COMPLETE`; every attempt
/// that has ended is an `Err` (so the shell rolls back the pin it inserted),
/// and the ABORT the call caused comes out of the next `tick`.
#[test]
fn confirm_errs_on_every_ended_attempt() {
    // PAIR_FINISH_MS passed during a slow pin write.
    let mut link = at_commit(0);
    let late = T0 + de1_relay::PAIR_FINISH_MS;
    assert_eq!(link.hub.confirm_paired(late), Err(RelayError::Timeout));
    let held = link.hub.tick(late);
    assert_eq!(held.send[0].bytes, vec![kind::ABORT, 5, 0]);
    assert_eq!(held.close.as_ref().unwrap().code, ws_close::TIMEOUT);
    assert!(link.hub.tick(late).is_empty(), "held once");
    link.hub_step(held);
    link.pump();
    assert_not_confirmed(&link);

    // The hub user closed the window meanwhile.
    let mut link = at_commit(1);
    link.gate.lock().unwrap().close();
    assert_eq!(link.hub.confirm_paired(T0), Err(RelayError::PairingClosed));
    let held = link.hub.tick(T0);
    assert_eq!(held.send[0].bytes, vec![kind::ABORT, 4, 0]);
    link.hub_step(held);
    link.pump();
    assert_not_confirmed(&link);

    // ... or closed and reopened it (a new generation).
    let mut link = at_commit(2);
    link.gate.lock().unwrap().close();
    open_gate(&link.gate, T0 + 1);
    assert_eq!(
        link.hub.confirm_paired(T0 + 1),
        Err(RelayError::PairingClosed)
    );
    assert_not_confirmed(&link);

    // The remote aborted while the pin was being written.
    let mut link = at_commit(3);
    let s = link
        .hub
        .on_ws(de1_relay::WsIn::Binary(&[kind::ABORT, 4, 0]), T0);
    link.hub_step(s);
    assert_eq!(link.hub.confirm_paired(T0), Err(RelayError::Closed));
    assert_not_confirmed(&link);

    // The hub user cancelled.
    let mut link = at_commit(4);
    let s = link.hub.cancel_pairing(T0);
    link.hub_step(s);
    assert_eq!(link.hub.confirm_paired(T0), Err(RelayError::Closed));
    assert_not_confirmed(&link);

    // A tick already timed it out.
    let mut link = at_commit(5);
    let s = link.hub.tick(late);
    link.hub_step(s);
    assert_eq!(link.hub.confirm_paired(late), Err(RelayError::Closed));
    assert_not_confirmed(&link);

    // And the success path is still Ok, with PAIR_COMPLETE.
    let mut link = at_commit(6);
    let s = link
        .hub
        .confirm_paired(T0 + de1_relay::PAIR_FINISH_MS - 1)
        .unwrap();
    assert_eq!(s.send.len(), 1);
    assert_eq!(s.send[0].bytes[0], kind::RECORD);
}

/// A poisoned pin set fails the connection closed and frees the pairing slot.
#[test]
fn poisoned_pin_set_frees_the_slot() {
    let link = at_commit(7);
    let mut link = link;
    let pins = link.pins.clone();
    let _ = std::thread::spawn(move || {
        let _guard = pins.write().unwrap();
        panic!("poison the pin set");
    })
    .join();
    assert!(link.pins.is_poisoned());
    assert_eq!(link.hub.confirm_paired(T0), Err(RelayError::Poisoned));
    let held = link.hub.tick(T0);
    assert_eq!(held.close.as_ref().unwrap().code, ws_close::PROTOCOL);
    assert!(held.events.contains(&RelayEvent::Failed {
        error: "Poisoned".into()
    }));
    let status = link.gate.lock().unwrap().status(T0);
    assert!(status.open && !status.pending);
}
