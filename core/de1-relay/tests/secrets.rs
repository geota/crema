//! AC-51: our secret types wipe themselves on drop, and their `Debug` prints
//! no key or seed bytes.

mod common;

use common::{hex, hub_keys, remote_keys};
use de1_relay::{
    Seed, StaticKeypair, SubSeed,
    pake::{self, Code, Psk, SpakeKey},
    test_support::{psk_from_bytes, sub_seed, test_seed},
};
use rand_chacha::ChaCha20Rng;
use rand_core::SeedableRng;
use zeroize::ZeroizeOnDrop;

const fn assert_zeroize_on_drop<T: ZeroizeOnDrop>() {}

#[test]
fn zeroize_on_drop() {
    // Compile-time: each of these implements ZeroizeOnDrop.
    const _: () = {
        assert_zeroize_on_drop::<StaticKeypair>();
        assert_zeroize_on_drop::<Psk>();
        assert_zeroize_on_drop::<SpakeKey>();
        assert_zeroize_on_drop::<SubSeed>();
        assert_zeroize_on_drop::<Seed>();
        assert_zeroize_on_drop::<Code>();
        assert_zeroize_on_drop::<zeroize::Zeroizing<[u8; 32]>>();
    };
    // And the storage copy is a Zeroizing buffer.
    let k = remote_keys();
    let stored: zeroize::Zeroizing<[u8; 32]> = k.private_for_storage();
    assert_eq!(
        StaticKeypair::from_private(stored.as_ref())
            .unwrap()
            .public(),
        k.public()
    );
}

#[test]
fn debug_redacts() {
    for k in [remote_keys(), hub_keys()] {
        let private = hex(k.private_for_storage().as_ref());
        let shown = format!("{k:?} {k:#?}");
        assert!(!shown.contains(&private), "{shown}");
        assert!(shown.contains(&k.device_id().to_hex()));
    }
    let seed = test_seed("remote-static", 0);
    let seed_hex = hex(&seed.expose_for_tests());
    assert!(!format!("{seed:?}").contains(&seed_hex));
    let sub = sub_seed(&seed, b"static");
    assert_eq!(format!("{sub:?}"), "SubSeed([redacted])");
    let psk = psk_from_bytes([0xab; 32]);
    assert!(!format!("{psk:?}").contains("ab"));
    let code = Code::parse("482913").unwrap();
    assert!(!format!("{code:?}").contains("482913"));
    let (r, h) = (remote_keys().device_id(), hub_keys().device_id());
    let (a, msg_a) = pake::start_a(&code, &r, &h, ChaCha20Rng::from_seed([3; 32]));
    let (b, msg_b) = pake::start_b(&code, &r, &h, ChaCha20Rng::from_seed([4; 32]));
    assert!(!format!("{a:?}").contains("4829"));
    let key_a = pake::finish(a, &msg_b, pake::SIDE_B).unwrap();
    let key_b = pake::finish(b, &msg_a, pake::SIDE_A).unwrap();
    assert_eq!(format!("{key_a:?}"), "SpakeKey([redacted])");
    let psk_a = pake::derive_psk(&key_a, b"prologue", &r, &h);
    let psk_b = pake::derive_psk(&key_b, b"prologue", &r, &h);
    assert_eq!(psk_a.expose_for_tests(), psk_b.expose_for_tests());
    assert!(!format!("{psk_a:?}").contains(&hex(&psk_a.expose_for_tests())[..8]));
}

/// `Debug` of a `ShowCode` event, and of a Step carrying one, never prints the code.
#[test]
fn show_code_debug_is_redacted() {
    let gate = common::new_gate();
    common::open_gate(&gate, common::T0);
    let mut link = common::Link::pairing(
        &remote_keys(),
        &hub_keys(),
        gate,
        common::new_pins(vec![]),
        0,
        common::T0,
    );
    link.pump();
    let code = link.shown_code().unwrap();
    let step_debug = format!("{:?}", link.remote_steps);
    assert!(step_debug.contains("ShowCode") && step_debug.contains("[redacted]"));
    assert!(!step_debug.contains(&code), "{step_debug}");
    // Other events still read as their JSON.
    let ev = de1_relay::RelayEvent::Established {
        peer_id: "ab".into(),
    };
    assert_eq!(
        format!("{ev:?}"),
        r#"{"type":"Established","content":{"peer_id":"ab"}}"#
    );
    // A seed from the bridge's entropy is opaque too.
    let seed = Seed::from_entropy([0x5c; 32]);
    assert!(!format!("{seed:?}").contains("5c"));
}
