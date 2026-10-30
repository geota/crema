//! AC-10 and AC-11 (resume, replay of IK message 1).

mod common;

use common::*;
use de1_relay::{HubConnection, Phase, RelayEvent, WsIn, kind, test_support::test_seed, ws_close};

#[test]
fn pinned_remote_resumes() {
    let (r, h) = (remote_keys(), hub_keys());
    let mut link = established(&r, &h, 1);
    assert_eq!(link.hub.phase(), Phase::Established);
    assert_eq!(link.remote.phase(), Phase::Established);
    assert!(link.hub_events().contains(&RelayEvent::Established {
        peer_id: r.device_id().to_hex()
    }));
    assert!(link.remote_events().contains(&RelayEvent::Established {
        peer_id: h.device_id().to_hex()
    }));
    let binding = link.hub.channel_binding().unwrap();
    assert_eq!(Some(binding), link.remote.channel_binding());
    assert_eq!(link.hub.peer_public_key(), Some(r.public()));
    assert_eq!(link.remote.peer_public_key(), Some(h.public()));
    // The wire: text hello, ServerHello, IK #1 (96), IK #2 (48), then only records.
    let kinds: Vec<(bool, usize)> = link
        .wire
        .iter()
        .map(|w| (w.msg.text, w.msg.bytes.len()))
        .collect();
    assert_eq!(kinds[2], (false, 1 + 96));
    assert_eq!(kinds[3], (false, 1 + 48));
    assert_eq!(link.wire[4].msg.bytes[0], kind::RECORD);
    // Frames flow both ways.
    link.hub_send(b"{\"type\":\"welcome\"}");
    link.remote_send(b"{\"type\":\"attach\"}");
    assert_eq!(
        link.remote_delivered(),
        vec![b"{\"type\":\"welcome\"}".to_vec()]
    );
    assert_eq!(
        link.hub_delivered(),
        vec![b"{\"type\":\"attach\"}".to_vec()]
    );
    // Different sessions, different bindings.
    let other = established(&r, &h, 2);
    assert_ne!(other.hub.channel_binding(), Some(binding));
}

#[test]
fn unknown_remote_gets_authenticated_refusal() {
    let (r, h) = (remote_keys(), hub_keys());
    let stranger = keys("remote-static", 1);
    let mut link = Link::resume(&r, &h, new_gate(), new_pins(vec![stranger.public()]), 1, T0);
    link.pump();
    // The hub wrote IK #2, then a CLOSE record, and closed 4403.
    let to_remote = link.sent(Dir::ToRemote);
    assert_eq!(to_remote.len(), 3);
    assert_eq!(to_remote[1].bytes[0], kind::NOISE_HS);
    assert_eq!(to_remote[2].bytes[0], kind::RECORD);
    assert_eq!(link.hub_close(), Some(ws_close::REFUSED));
    assert!(link.hub_events().contains(&RelayEvent::UnknownRemote {
        remote_id: r.device_id().to_hex()
    }));
    assert!(!has_event(&link.hub_events(), is_established));
    // The remote decrypts it as an authenticated NotPaired.
    assert!(link.remote_events().contains(&RelayEvent::Refused {
        reason: "NotPaired".into(),
        authenticated: true
    }));
    assert_eq!(link.remote.phase(), Phase::Closed);
    assert!(link.remote_delivered().is_empty());
}

#[test]
fn hub_id_mismatch_sends_nothing() {
    let (r, h) = (remote_keys(), hub_keys());
    let impostor = keys("hub-static", 1);
    // The remote pinned `h`; `impostor` answers.
    let hub = HubConnection::new(
        &impostor,
        new_gate(),
        new_pins(vec![r.public()]),
        "Kitchen tablet",
        test_seed("hub-conn", 1),
        T0,
    );
    let (remote, first) =
        de1_relay::RemoteConnection::resume(&r, h.public(), test_seed("remote-conn", 1), T0);
    let mut link = Link::with(hub, remote, first, new_gate(), new_pins(vec![]), T0);
    link.pump();
    let to_hub = link.sent(Dir::ToHub);
    // Only the hello and an ABORT(HOST_MISMATCH): no Noise message.
    assert_eq!(to_hub.len(), 2);
    assert!(to_hub[0].text);
    assert_eq!(to_hub[1].bytes, vec![kind::ABORT, 7, 0]);
    assert!(
        !to_hub
            .iter()
            .any(|m| m.bytes.first() == Some(&kind::NOISE_HS))
    );
    assert!(link.remote_events().contains(&RelayEvent::Refused {
        reason: "HostMismatch".into(),
        authenticated: false
    }));
    assert_eq!(link.remote_close(), Some(ws_close::AUTH));
}

/// A replayed IK message 1 gets message 2, never `Established`, delivers
/// nothing, and times out (4408).
#[test]
fn replayed_msg1_never_admitted() {
    let (r, h) = (remote_keys(), hub_keys());
    let pins = new_pins(vec![r.public()]);
    let original = Link::resume(&r, &h, new_gate(), pins.clone(), 1, T0);
    let mut original = original;
    original.pump();
    let flights = original.sent(Dir::ToHub);
    let (hello, msg1) = (&flights[0], &flights[1]);
    assert_eq!(msg1.bytes[0], kind::NOISE_HS);

    let mut hub = HubConnection::new(
        &h,
        new_gate(),
        pins,
        "Kitchen tablet",
        test_seed("hub-conn", 50),
        T0 + 60_000,
    );
    let s1 = hub.on_ws(ws(hello), T0 + 60_000);
    let s2 = hub.on_ws(ws(msg1), T0 + 60_000);
    assert_eq!(s1.send[0].bytes[0], kind::SERVER_HELLO);
    assert_eq!(s2.send.len(), 1);
    assert_eq!(s2.send[0].bytes[0], kind::NOISE_HS);
    assert!(s2.events.is_empty() && s2.deliver.is_empty());
    assert_eq!(hub.phase(), Phase::AwaitClientFinished);
    // The replayer can't produce the client-finished record: a guess fails.
    let mut forged = vec![kind::RECORD];
    forged.extend([0_u8; 18]);
    let mut hub2 = HubConnection::new(
        &h,
        new_gate(),
        new_pins(vec![r.public()]),
        "Kitchen tablet",
        test_seed("hub-conn", 51),
        T0,
    );
    hub2.on_ws(ws(hello), T0);
    hub2.on_ws(ws(msg1), T0);
    let s = hub2.on_ws(WsIn::Binary(&forged), T0);
    assert_eq!(s.close.unwrap().code, ws_close::AUTH);
    assert!(!s.events.iter().any(is_established));
    // Silence: timeout 5 s after the ServerHello, nothing admitted.
    assert_eq!(
        hub.next_deadline_ms(),
        Some(T0 + 60_000 + de1_relay::RESUME_DEADLINE_MS)
    );
    let s = hub.tick(T0 + 60_000 + de1_relay::RESUME_DEADLINE_MS);
    assert_eq!(s.close.unwrap().code, ws_close::TIMEOUT);
    assert!(s.send.is_empty());
    assert!(!s.events.iter().any(is_established));
    assert_eq!(hub.phase(), Phase::Failed);
}

/// Replayed while the real remote's session is live: that session keeps
/// delivering, and no second session is admitted.
#[test]
fn replay_during_live_session_is_harmless() {
    let (r, h) = (remote_keys(), hub_keys());
    let pins = new_pins(vec![r.public()]);
    let gate = new_gate();
    let mut live = Link::resume(&r, &h, gate.clone(), pins.clone(), 1, T0);
    live.pump();
    assert_eq!(live.hub.phase(), Phase::Established);
    let flights = live.sent(Dir::ToHub);

    let mut replay = HubConnection::new(
        &h,
        gate,
        pins,
        "Kitchen tablet",
        test_seed("hub-conn", 60),
        T0,
    );
    replay.on_ws(ws(&flights[0]), T0);
    replay.on_ws(ws(&flights[1]), T0);
    // Even the captured client-finished record fails on the replay connection.
    let s = replay.on_ws(ws(&flights[2]), T0);
    assert_eq!(s.close.unwrap().code, ws_close::AUTH);
    assert!(!s.events.iter().any(is_established));

    live.remote_send(b"still here");
    live.hub_send(b"and here");
    assert_eq!(live.hub_delivered(), vec![b"still here".to_vec()]);
    assert_eq!(live.remote_delivered(), vec![b"and here".to_vec()]);
    assert_eq!(live.hub.phase(), Phase::Established);
}
