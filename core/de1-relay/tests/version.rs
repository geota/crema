//! AC-17: version ranges and the downgrade rule.

mod common;

use common::*;
use de1_relay::{
    HubConnection, Phase, RelayEvent, ServerHello, WsIn, kind, test_support::test_seed, ws_close,
};

fn hello_with(vmin: u8, vmax: u8, resume: bool) -> Vec<u8> {
    let (intent, id) = if resume {
        ("resume", String::new())
    } else {
        ("pair", remote_keys().device_id().to_hex())
    };
    let name = if resume { "" } else { "R" };
    format!(r#"{{"type":"hello","v":4,"vmin":{vmin},"vmax":{vmax},"role":"secondary","clientId":"{id}","clientName":"{name}","intent":"{intent}"}}"#).into_bytes()
}

#[test]
fn four_meets_four() {
    let link = established(&remote_keys(), &hub_keys(), 1);
    let sh = ServerHello::parse(&link.sent(Dir::ToRemote)[0].bytes).unwrap();
    assert_eq!(sh.version, 4);
    assert_eq!(link.hub.phase(), Phase::Established);
}

#[test]
fn wider_client_range_picks_four() {
    let mut hub = HubConnection::new(
        &hub_keys(),
        new_gate(),
        new_pins(vec![]),
        "Hub",
        test_seed("hub-conn", 0),
        T0,
    );
    let s = hub.on_ws(WsIn::Text(&hello_with(4, 9, true)), T0);
    assert_eq!(ServerHello::parse(&s.send[0].bytes).unwrap().version, 4);
    let mut hub = HubConnection::new(
        &hub_keys(),
        new_gate(),
        new_pins(vec![]),
        "Hub",
        test_seed("hub-conn", 0),
        T0,
    );
    let s = hub.on_ws(WsIn::Text(&hello_with(1, 4, true)), T0);
    assert_eq!(ServerHello::parse(&s.send[0].bytes).unwrap().version, 4);
}

#[test]
fn client_range_above_four_gets_abort_version() {
    for (vmin, vmax, resume) in [(5, 5, true), (5, 7, false), (3, 3, true)] {
        let mut hub = HubConnection::new(
            &hub_keys(),
            new_gate(),
            new_pins(vec![]),
            "Hub",
            test_seed("hub-conn", 0),
            T0,
        );
        let s = hub.on_ws(WsIn::Text(&hello_with(vmin, vmax, resume)), T0);
        assert_eq!(s.send.len(), 1);
        assert_eq!(
            s.send[0].bytes,
            vec![kind::ABORT, 8, 1, 4],
            "carries the hub's max"
        );
        assert_eq!(s.close.unwrap().code, ws_close::VERSION);
        assert_eq!(hub.phase(), Phase::Closed);
    }
}

/// Rewriting the ServerHello's version fails the handshake: the remote refuses
/// it, never retries lower, and nothing is established.
#[test]
fn server_hello_version_rewrite_fails() {
    for v in [3_u8, 5, 0, 255] {
        let r = remote_keys();
        let mut link = Link::resume(
            &r,
            &hub_keys(),
            new_gate(),
            new_pins(vec![r.public()]),
            1,
            T0,
        );
        link.set_tamper(Box::new(move |dir, mut m| {
            if dir == Dir::ToRemote && m.bytes[0] == kind::SERVER_HELLO {
                m.bytes[1] = v;
            }
            Some(m)
        }));
        link.pump();
        assert!(!has_event(&link.remote_events(), is_established));
        assert!(link.remote_events().contains(&RelayEvent::Refused {
            reason: "Version".into(),
            authenticated: false
        }));
        assert_eq!(link.remote_close(), Some(ws_close::VERSION));
        // The remote's only messages: its hello and ABORT(VERSION); no second hello.
        let to_hub = link.sent(Dir::ToHub);
        assert_eq!(to_hub.len(), 2);
        assert_eq!(to_hub[1].bytes, vec![kind::ABORT, 8, 1, 4]);
    }
}

/// A remote that gets ABORT(VERSION) reports it and stops.
#[test]
fn remote_stops_on_abort_version() {
    let (mut remote, _) = de1_relay::RemoteConnection::resume(
        &remote_keys(),
        hub_keys().public(),
        test_seed("remote-conn", 0),
        T0,
    );
    let s = remote.on_ws(WsIn::Binary(&[kind::ABORT, 8, 1, 5]), T0);
    assert!(s.send.is_empty());
    assert_eq!(
        s.events,
        vec![RelayEvent::Refused {
            reason: "Version".into(),
            authenticated: false
        }]
    );
    assert_eq!(remote.phase(), Phase::Closed);
}
