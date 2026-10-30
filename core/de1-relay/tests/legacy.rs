//! AC-16: the minimal version check against v2/v3 peers, and no fallback.

mod common;

use common::*;
use de1_relay::{
    HubConnection, LEGACY_DENIAL, Phase, RelayEvent, RemoteConnection, WsIn,
    test_support::test_seed, ws_close,
};

/// The released v2 (0.0.9) hello, and the v3 golden fixtures
/// (`android/app/src/test/resources/proxy-golden/v3/frames/hello*.json`).
const V2_HELLO: &str = r#"{"type":"hello","v":2,"role":"secondary","clientId":"install-1","clientName":"Sam's phone"}"#;
const V3_HELLO: &str = r#"{"type":"hello","v":3,"role":"secondary","clientId":"install-1","clientName":"Sam's phone","nonce":"Y2xpZW50LW5vbmNl"}"#;
const V3_HELLO_BARE: &str = r#"{"type":"hello","v":3,"role":"secondary","clientId":"install-1","clientName":"Sam's phone"}"#;
const V3_CHALLENGE: &str = r#"{"type":"challenge","primaryId":"host-1","primaryName":"Kitchen tablet","nonce":"aG9zdC1ub25jZQ","hostProof":"aG9zdC1wcm9vZg"}"#;

#[test]
fn v2_v3_hellos_get_text_denial() {
    assert_eq!(
        LEGACY_DENIAL,
        r#"{"type":"denied","reason":"version mismatch"}"#
    );
    for (hello, version) in [(V2_HELLO, 2), (V3_HELLO, 3), (V3_HELLO_BARE, 3)] {
        let gate = new_gate();
        open_gate(&gate, T0); // even with pairing open
        let mut hub = HubConnection::new(
            &hub_keys(),
            gate,
            new_pins(vec![]),
            "Hub",
            test_seed("hub-conn", 0),
            T0,
        );
        let s = hub.on_ws(WsIn::Text(hello.as_bytes()), T0);
        assert_eq!(s.send.len(), 1);
        assert!(s.send[0].text);
        assert_eq!(s.send[0].bytes, LEGACY_DENIAL.as_bytes());
        assert_eq!(
            s.events,
            vec![RelayEvent::LegacyPeer {
                version,
                name: "Sam's phone".into()
            }]
        );
        assert_eq!(s.close.unwrap().code, ws_close::VERSION);
        assert_eq!(hub.phase(), Phase::Closed);
        // Nothing binary, ever, and nothing after.
        assert!(hub.on_ws(WsIn::Text(hello.as_bytes()), T0).is_empty());
        assert!(hub.tick(T0 + 60_000).is_empty());
    }
}

#[test]
fn remote_never_falls_back() {
    let (r, h) = (remote_keys(), hub_keys());
    for pairing in [false, true] {
        let make = || {
            if pairing {
                RemoteConnection::pair(&r, "Pixel 8", test_seed("remote-conn", 0), T0)
            } else {
                RemoteConnection::resume(&r, h.public(), test_seed("remote-conn", 0), T0)
            }
        };
        // A v2/v3 hub's denial: Refused{HubOutdated, false}, close 4426, nothing sent.
        let (mut remote, first) = make();
        assert_eq!(first.send.len(), 1);
        let s = remote.on_ws(WsIn::Text(LEGACY_DENIAL.as_bytes()), T0);
        assert!(s.send.is_empty());
        assert_eq!(
            s.events,
            vec![RelayEvent::Refused {
                reason: "HubOutdated".into(),
                authenticated: false
            }]
        );
        assert_eq!(s.close.unwrap().code, ws_close::VERSION);
        assert_eq!(remote.phase(), Phase::Closed);
        // A v3 challenge: a close, nothing sent.
        let (mut remote, _) = make();
        let s = remote.on_ws(WsIn::Text(V3_CHALLENGE.as_bytes()), T0);
        assert!(s.send.is_empty());
        assert!(s.close.is_some());
        assert_eq!(remote.phase(), Phase::Failed);
        // Binary garbage or silence: closes within 5 s, nothing sent.
        let (mut remote, _) = make();
        let s = remote.on_ws(WsIn::Binary(b"\x99garbage"), T0);
        assert!(s.send.is_empty() && s.close.is_some());
        let (mut remote, _) = make();
        let s = remote.tick(T0 + 5_000);
        assert!(s.send.is_empty() && s.close.is_some());
        // An old denied frame of another wording is still the legacy hub.
        let (mut remote, _) = make();
        let s = remote.on_ws(
            WsIn::Text(br#"{"type":"denied","reason":"not-paired"}"#),
            T0,
        );
        assert!(s.send.is_empty());
        assert_eq!(s.close.unwrap().code, ws_close::VERSION);
    }
}
