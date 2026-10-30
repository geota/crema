//! AC-49: low-order X25519 keys, with the `low_order_keys` vectors.

mod common;

use common::{evil::*, *};
use curve25519_dalek::{constants::EIGHT_TORSION, montgomery::MontgomeryPoint};
use de1_relay::{
    DeviceId, HubConnection, Phase, PinSet, RelayError, RelayEvent, RemoteConnection, is_low_order,
    kind, test_support::test_seed, ws_close,
};
use serde_json::Value;

fn vectors() -> Vec<[u8; 32]> {
    let file: Value = serde_json::from_str(include_str!("vectors/v4.json")).unwrap();
    let keys: Vec<[u8; 32]> = file["low_order_keys"]
        .as_array()
        .unwrap()
        .iter()
        .map(|k| unhex(k.as_str().unwrap()).try_into().unwrap())
        .collect();
    assert_eq!(keys, de1_relay::test_vectors::low_order_keys());
    assert_eq!(keys.len(), 14);
    keys
}

/// Every vector is low-order: its X25519 with any clamped scalar is all zero,
/// and the canonical ones are exactly the u-coordinates of the 8 torsion points.
#[test]
fn vectors_are_the_low_order_points() {
    let keys = vectors();
    for k in &keys {
        assert!(is_low_order(k));
        for scalar in [
            [1_u8; 32],
            [0x77; 32],
            remote_keys()
                .private_for_storage()
                .as_ref()
                .to_owned()
                .try_into()
                .unwrap(),
        ] {
            assert_eq!(MontgomeryPoint(*k).mul_clamped(scalar).to_bytes(), [0; 32]);
        }
    }
    for t in EIGHT_TORSION {
        let u = t.to_montgomery().to_bytes();
        assert!(keys.contains(&u), "{}", hex(&u));
    }
    // Honest keys are not.
    assert!(!is_low_order(&remote_keys().public()) && !is_low_order(&hub_keys().public()));
}

#[test]
fn pin_set_refuses_low_order_keys() {
    for k in vectors() {
        assert_eq!(
            PinSet::new(vec![remote_keys().public(), k]).err(),
            Some(RelayError::LowOrderKey)
        );
        let mut pins = PinSet::new(vec![remote_keys().public()]).unwrap();
        assert_eq!(pins.insert(k), Err(RelayError::LowOrderKey));
        assert_eq!(pins.len(), 1);
    }
}

fn hub_for_pairing(
    n: u32,
) -> (
    HubConnection,
    std::sync::Arc<std::sync::Mutex<de1_relay::PairingGate>>,
    std::sync::Arc<std::sync::RwLock<PinSet>>,
) {
    let gate = new_gate();
    open_gate(&gate, T0);
    let pins = new_pins(vec![]);
    let hub = HubConnection::new(
        &hub_keys(),
        gate.clone(),
        pins.clone(),
        "Hub",
        test_seed("hub-conn", n),
        T0,
    );
    (hub, gate, pins)
}

fn handshake_failed(steps: &[de1_relay::Step]) -> bool {
    steps
        .iter()
        .any(|s| s.send.iter().any(|m| m.bytes == vec![kind::ABORT, 6, 0]))
        && steps
            .iter()
            .any(|s| s.close.as_ref().is_some_and(|c| c.code == ws_close::AUTH))
}

/// A remote that knows the code and presents a low-order XX static (with the
/// matching claimed id) fails the handshake at `se`; nothing is pinned or counted.
#[test]
fn low_order_xx_static_fails() {
    for (i, l) in vectors().into_iter().enumerate() {
        let (mut hub, gate, pins) = hub_for_pairing(u32::try_from(i).unwrap());
        let mut evil = EvilPairRemote::start(&mut hub, DeviceId::of(&l), "123456", T0);
        let steps = evil.handshake(&mut hub, &[9; 32], EvilResolver::new(Some(l), None, 1), T0);
        assert!(handshake_failed(&steps), "{}", hex(&l));
        let events: Vec<RelayEvent> = steps.iter().flat_map(|s| s.events.clone()).collect();
        assert!(
            !events
                .iter()
                .any(|e| matches!(e, RelayEvent::PairPending { .. }))
        );
        assert!(events.iter().any(|e| matches!(e, RelayEvent::PairingFailed { reason, failures: 0, .. } if reason == "HandshakeFailed")));
        assert_eq!(hub.peer_public_key(), None);
        assert!(pins.read().unwrap().is_empty());
        assert_eq!(gate.lock().unwrap().status(T0).failures, 0);
    }
}

/// A low-order XX ephemeral fails `ee` when the hub writes message 2.
#[test]
fn low_order_xx_ephemeral_fails() {
    for (i, l) in vectors().into_iter().enumerate() {
        let (mut hub, _, pins) = hub_for_pairing(u32::try_from(i).unwrap());
        let me = keys("remote-static", 3);
        let mut evil = EvilPairRemote::start(&mut hub, me.device_id(), "123456", T0);
        let private: [u8; 32] = *me.private_for_storage();
        let steps = evil.handshake(&mut hub, &private, EvilResolver::new(None, Some(l), 2), T0);
        assert!(handshake_failed(&steps), "{}", hex(&l));
        assert!(pins.read().unwrap().is_empty());
        assert_eq!(hub.phase(), Phase::Failed);
    }
}

/// An IK static or ephemeral that is low-order fails reading message 1 (`ss`,
/// `es`): no message 2, no lookup, `ABORT(HANDSHAKE_FAILED)` and 4401.
#[test]
fn low_order_ik_keys_fail() {
    let r = remote_keys();
    let private: [u8; 32] = *r.private_for_storage();
    for l in vectors() {
        for (low_static, low_eph) in [(Some(l), None), (None, Some(l))] {
            let mut hub = HubConnection::new(
                &hub_keys(),
                new_gate(),
                new_pins(vec![r.public()]),
                "Hub",
                test_seed("hub-conn", 1),
                T0,
            );
            let (step, _) = evil_resume_msg1(
                &mut hub,
                &hub_keys().public(),
                &private,
                EvilResolver::new(low_static, low_eph, 3),
                T0,
            );
            assert_eq!(
                step.send,
                vec![de1_relay::Outgoing {
                    text: false,
                    bytes: vec![kind::ABORT, 6, 0]
                }],
                "{}",
                hex(&l)
            );
            assert_eq!(step.close.unwrap().code, ws_close::AUTH);
            assert!(
                !step
                    .events
                    .iter()
                    .any(|e| matches!(e, RelayEvent::UnknownRemote { .. }))
            );
        }
    }
}

/// The remote's side: a hub with a low-order XX ephemeral or static, or a
/// low-order IK ephemeral, fails on the remote; a low-order pinned hub key
/// fails before IK message 1 leaves.
#[test]
fn remote_refuses_low_order_hub_keys() {
    let r = remote_keys();
    let h = hub_keys();
    let hub_private: [u8; 32] = *h.private_for_storage();
    for (i, l) in vectors().into_iter().enumerate() {
        let n = u32::try_from(i).unwrap();
        for (low_static, low_eph, claimed) in [
            (None, Some(l), h.device_id()),
            (Some(l), None, DeviceId::of(&l)),
        ] {
            let (mut remote, first) =
                RemoteConnection::pair(&r, "Pixel 8", test_seed("remote-conn", n), T0);
            let steps = evil_pair_hub(
                &mut remote,
                &first,
                claimed,
                &hub_private,
                EvilResolver::new(low_static, low_eph, 4),
                T0,
            );
            let last = steps.last().unwrap();
            assert_eq!(
                last.send,
                vec![de1_relay::Outgoing {
                    text: false,
                    bytes: vec![kind::ABORT, 6, 0]
                }],
                "{}",
                hex(&l)
            );
            assert!(last.events.iter().any(|e| matches!(e, RelayEvent::PairingFailed { reason, .. } if reason == "HandshakeFailed")));
            assert_eq!(remote.phase(), Phase::Failed);
        }
        let (mut remote, first) =
            RemoteConnection::resume(&r, h.public(), test_seed("remote-conn", n), T0);
        let steps = evil_resume_hub(
            &mut remote,
            &first,
            &h,
            EvilResolver::new(None, Some(l), 5),
            T0,
        );
        assert!(steps[1].events.contains(&RelayEvent::Refused {
            reason: "HubUnverified".into(),
            authenticated: false
        }));
        assert_ne!(remote.phase(), Phase::Established);

        // A pinned hub key that is low-order: `es` fails while writing IK #1.
        let (mut remote, first) = RemoteConnection::resume(&r, l, test_seed("remote-conn", n), T0);
        let sh = de1_relay::ServerHello {
            version: 4,
            status: de1_relay::HelloStatus::Ready,
            hub_id: DeviceId::of(&l),
            secs_left: 0,
            name: "Hub".into(),
            ext: vec![],
        };
        let s = remote.on_ws(de1_relay::WsIn::Binary(&sh.encode()), T0);
        assert!(!s.send.iter().any(|m| m.bytes[0] == kind::NOISE_HS));
        assert_eq!(remote.phase(), Phase::Failed);
        drop(first);
    }
}
