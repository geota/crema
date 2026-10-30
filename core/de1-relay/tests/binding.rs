//! AC-9: the hellos are bound into the handshake; ids are checked against keys.

mod common;

use common::{evil::*, *};
use de1_relay::{
    ClientHello, HubConnection, Phase, RelayEvent, RemoteConnection, WsIn, kind,
    test_support::test_seed, ws_close,
};

/// Runs a pairing with a tamper filter, playing the shell honestly: enters the
/// shown code if asked, commits if the hub gets there.
fn pairing_with(tamper: Tamper) -> Link {
    let gate = new_gate();
    open_gate(&gate, T0);
    let mut link = Link::pairing(&remote_keys(), &hub_keys(), gate, new_pins(vec![]), 0, T0);
    link.set_tamper(tamper);
    link.pump();
    if let Some(code) = link.shown_code()
        && link.hub.phase() == Phase::AwaitCode
    {
        link.enter_code(&code);
    }
    if link.hub.phase() == Phase::AwaitCommit {
        link.commit();
    }
    link
}

fn resume_with(tamper: Tamper) -> Link {
    let r = remote_keys();
    let mut link = Link::resume(
        &r,
        &hub_keys(),
        new_gate(),
        new_pins(vec![r.public()]),
        1,
        T0,
    );
    link.set_tamper(tamper);
    link.pump();
    link
}

fn assert_pairing_failed(link: &Link, what: &str) {
    assert!(
        !has_event(&link.hub_events(), is_paired),
        "{what}: hub paired"
    );
    assert!(
        !has_event(&link.remote_events(), is_paired),
        "{what}: remote paired"
    );
    assert!(
        !link
            .hub_events()
            .iter()
            .any(|e| matches!(e, RelayEvent::PairPending { .. })),
        "{what}: pending"
    );
    assert!(
        link.hub_close().is_some() || link.remote_close().is_some(),
        "{what}: nobody closed"
    );
}

fn assert_resume_failed(link: &Link, what: &str) {
    assert!(
        !has_event(&link.hub_events(), is_established),
        "{what}: hub established"
    );
    assert!(
        !has_event(&link.remote_events(), is_established),
        "{what}: remote established"
    );
    assert!(link.hub_delivered().is_empty() && link.remote_delivered().is_empty());
}

fn edit_text(from: &'static str, to: &'static str) -> Tamper {
    Box::new(move |dir, mut m| {
        if dir == Dir::ToHub && m.text {
            let s = String::from_utf8(m.bytes).unwrap();
            assert!(s.contains(from), "{s}");
            m.bytes = s.replacen(from, to, 1).into_bytes();
        }
        Some(m)
    })
}

fn edit_server_hello(at: usize, xor: u8) -> Tamper {
    Box::new(move |dir, mut m| {
        if dir == Dir::ToRemote && m.bytes[0] == kind::SERVER_HELLO {
            m.bytes[at] ^= xor;
        }
        Some(m)
    })
}

#[test]
fn honest_runs_pass_through_the_harness() {
    let link = pairing_with(Box::new(|_, m| Some(m)));
    assert!(has_event(&link.remote_events(), is_paired));
    let link = resume_with(Box::new(|_, m| Some(m)));
    assert!(has_event(&link.remote_events(), is_established));
}

#[test]
fn client_hello_rewrites_fail_pairing() {
    let other_id = keys("remote-static", 7).device_id().to_hex();
    let mine = remote_keys().device_id().to_hex();
    let leaked: &'static str = Box::leak(other_id.into_boxed_str());
    let mine: &'static str = Box::leak(mine.into_boxed_str());
    for (what, from, to) in [
        ("clientId", mine, leaked),
        ("clientName", "Pixel 8", "Pixel 9"),
        ("intent", "\"pair\"", "\"resume\""),
        ("vmax", "\"vmax\":4", "\"vmax\":5"),
        ("vmin", "\"vmin\":4", "\"vmin\":3"),
    ] {
        let link = pairing_with(edit_text(from, to));
        assert_pairing_failed(&link, what);
    }
}

#[test]
fn client_hello_rewrites_fail_resume() {
    for (what, from, to) in [
        ("intent", "\"resume\"", "\"pair\""),
        ("vmax", "\"vmax\":4", "\"vmax\":5"),
        ("vmin", "\"vmin\":4", "\"vmin\":3"),
        ("v", "\"v\":4", "\"v\":5"),
    ] {
        let link = resume_with(edit_text(from, to));
        assert_resume_failed(&link, what);
    }
}

#[test]
fn server_hello_rewrites_fail_pairing() {
    // version, status, a hub id byte, a secs_left byte, a name byte.
    for (what, at, xor) in [
        ("version", 1, 0x01),
        ("status", 2, 0x03),
        ("hub id", 5, 0x40),
        ("secs_left", 20, 0x01),
        ("name", 23, 0x01),
    ] {
        let link = pairing_with(edit_server_hello(at, xor));
        assert_pairing_failed(&link, what);
    }
}

#[test]
fn server_hello_rewrites_fail_resume() {
    for (what, at, xor) in [
        ("version", 1, 0x01),
        ("status", 2, 0x03),
        ("hub id", 5, 0x40),
        ("secs_left", 20, 0x01),
        ("name", 23, 0x01),
    ] {
        let link = resume_with(edit_server_hello(at, xor));
        assert_resume_failed(&link, what);
    }
}

/// A static key whose id differs from the hello's: `PeerIdMismatch` on either side.
#[test]
fn static_with_other_id_is_peer_id_mismatch() {
    // A remote claiming someone else's id (it knows the code; the psk matches).
    let gate = new_gate();
    open_gate(&gate, T0);
    let pins = new_pins(vec![]);
    let mut hub = HubConnection::new(
        &hub_keys(),
        gate.clone(),
        pins.clone(),
        "Hub",
        test_seed("hub-conn", 0),
        T0,
    );
    let liar = keys("remote-static", 5);
    let mut evil = EvilPairRemote::start(&mut hub, remote_keys().device_id(), "123456", T0);
    let private: [u8; 32] = *liar.private_for_storage();
    let steps = evil.handshake(&mut hub, &private, EvilResolver::new(None, None, 6), T0);
    let last = steps.last().unwrap();
    assert!(last.events.iter().any(|e| matches!(e, RelayEvent::PairingFailed { reason, failures: 0, .. } if reason == "PeerIdMismatch")));
    assert_eq!(last.close.as_ref().unwrap().code, ws_close::AUTH);
    assert!(pins.read().unwrap().is_empty());
    assert_eq!(gate.lock().unwrap().status(T0).failures, 0);

    // A hub claiming another hub's id.
    let (mut remote, first) =
        RemoteConnection::pair(&remote_keys(), "Pixel 8", test_seed("remote-conn", 0), T0);
    let impostor_private: [u8; 32] = *keys("hub-static", 5).private_for_storage();
    let steps = evil_pair_hub(
        &mut remote,
        &first,
        hub_keys().device_id(),
        &impostor_private,
        EvilResolver::new(None, None, 7),
        T0,
    );
    let last = steps.last().unwrap();
    assert!(last.events.iter().any(
        |e| matches!(e, RelayEvent::PairingFailed { reason, .. } if reason == "PeerIdMismatch")
    ));
    assert_eq!(remote.phase(), Phase::Failed);
    assert!(!last.send.iter().any(|m| m.bytes[0] == kind::NOISE_HS));
}

/// Names with é, emoji and right-to-left text pair, show sanitized, and are bound.
#[test]
fn non_ascii_names_pair_and_bind() {
    let name = "Café ☕ \u{202e}עברית";
    let gate = new_gate();
    open_gate(&gate, T0);
    let pins = new_pins(vec![]);
    let hub = HubConnection::new(
        &hub_keys(),
        gate.clone(),
        pins.clone(),
        "Küche 🍵",
        test_seed("hub-conn", 0),
        T0,
    );
    let (remote, first) =
        RemoteConnection::pair(&remote_keys(), name, test_seed("remote-conn", 0), T0);
    let mut link = Link::with(hub, remote, first, gate, pins, T0);
    link.pump();
    assert!(link.hub_events().contains(&RelayEvent::PairRequest {
        remote_id: remote_keys().device_id().to_hex(),
        remote_name: name.into()
    }));
    let code = link.shown_code().unwrap();
    link.enter_code(&code);
    link.commit();
    assert!(link.remote_events().contains(&RelayEvent::Paired {
        peer_id: hub_keys().device_id().to_hex(),
        peer_name: "Küche 🍵".into()
    }));
    assert!(link.hub_events().contains(&RelayEvent::Paired {
        peer_id: remote_keys().device_id().to_hex(),
        peer_name: name.into()
    }));

    // Rewriting one character of the name in transit breaks the pairing.
    let link = pairing_with(edit_text("Pixel 8", "Pixél 8"));
    assert_pairing_failed(&link, "non-ASCII rewrite");
}

#[test]
fn invalid_utf8_hello_is_malformed() {
    let mut text = ClientHello::resume().encode();
    let i = text.iter().position(|&b| b == b'h').unwrap();
    text[i] = 0xff;
    let mut hub = HubConnection::new(
        &hub_keys(),
        new_gate(),
        new_pins(vec![]),
        "Hub",
        test_seed("hub-conn", 0),
        T0,
    );
    let s = hub.on_ws(WsIn::Text(&text), T0);
    assert_eq!(
        s.send,
        vec![de1_relay::Outgoing {
            text: false,
            bytes: vec![kind::ABORT, 9, 0]
        }]
    );
    assert_eq!(s.close.unwrap().code, ws_close::PROTOCOL);
    assert!(s.events.contains(&RelayEvent::Failed {
        error: "Malformed".into()
    }));
    assert_eq!(hub.phase(), Phase::Failed);
}

#[test]
fn resume_hello_has_empty_name() {
    let (_, first) = RemoteConnection::resume(
        &remote_keys(),
        hub_keys().public(),
        test_seed("remote-conn", 1),
        T0,
    );
    let text = String::from_utf8(first.send[0].bytes.clone()).unwrap();
    assert_eq!(
        text,
        r#"{"type":"hello","v":4,"vmin":4,"vmax":4,"role":"secondary","clientId":"","clientName":"","intent":"resume"}"#
    );
    // The hub refuses a resume hello that names itself.
    let named = text.replace("\"clientName\":\"\"", "\"clientName\":\"Pixel 8\"");
    let mut hub = HubConnection::new(
        &hub_keys(),
        new_gate(),
        new_pins(vec![]),
        "Hub",
        test_seed("hub-conn", 0),
        T0,
    );
    let s = hub.on_ws(WsIn::Text(named.as_bytes()), T0);
    assert_eq!(s.close.unwrap().code, ws_close::PROTOCOL);
}
