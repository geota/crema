//! AC-1: the seeded vectors reproduce byte for byte.

mod common;

use common::{Dir, Link, T0, hub_keys, new_gate, new_pins, open_gate, remote_keys, unhex};
use de1_relay::test_vectors;
use serde_json::Value;

fn vectors() -> Value {
    serde_json::from_str(include_str!("vectors/v4.json")).unwrap()
}

#[test]
fn vectors_reproduce() {
    let fresh = test_vectors::generate();
    let checked_in = include_str!("vectors/v4.json");
    assert!(
        fresh == checked_in,
        "tests/vectors/v4.json is stale: run `cargo run -p de1-relay --features test-seeds --example gen_vectors`"
    );
    let v = vectors();
    assert_eq!(v["pairing"]["paired"], Value::Bool(true));
    assert_eq!(v["resume"]["delivered"], Value::Bool(true));
    assert_eq!(v["rekey"]["delivered"], Value::Bool(true));
}

/// The vectors' pairing transcript is exactly what two fresh connections on
/// the same seeds put on the wire, independently of the generator.
#[test]
fn pairing_transcript_matches_vectors() {
    let v = vectors();
    let gate = new_gate();
    open_gate(&gate, T0);
    let mut link = Link::pairing(&remote_keys(), &hub_keys(), gate, new_pins(vec![]), 0, T0);
    link.pump();
    let code = link.shown_code().unwrap();
    assert_eq!(Value::String(code.clone()), v["pairing"]["code"]);
    link.enter_code(&code);
    link.commit();
    let names = [
        "client_hello",
        "server_hello",
        "pake_a",
        "pake_b",
        "noise_1",
        "noise_2",
        "noise_3",
        "pair_complete",
    ];
    assert_eq!(link.wire.len(), names.len());
    for (w, name) in link.wire.iter().zip(names) {
        assert_eq!(
            w.msg.bytes,
            unhex(v["pairing"][name].as_str().unwrap()),
            "{name}"
        );
    }
    assert_eq!(link.wire[0].dir, Dir::ToHub);
    assert!(link.wire[0].msg.text);
}

#[test]
fn resume_transcript_matches_vectors() {
    let v = vectors();
    let r = remote_keys();
    let mut link = Link::resume(
        &r,
        &hub_keys(),
        new_gate(),
        new_pins(vec![r.public()]),
        1,
        T0,
    );
    link.pump();
    let names = [
        "client_hello",
        "server_hello",
        "ik_1",
        "ik_2",
        "client_finished",
    ];
    for (w, name) in link.wire.iter().zip(names) {
        assert_eq!(
            w.msg.bytes,
            unhex(v["resume"][name].as_str().unwrap()),
            "{name}"
        );
    }
    let binding = link.hub.channel_binding().unwrap();
    assert_eq!(
        common::hex(&binding),
        v["resume"]["channel_binding"].as_str().unwrap()
    );
}
