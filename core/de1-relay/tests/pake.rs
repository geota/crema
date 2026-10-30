//! AC-18 (element check) and AC-20 (code generation).

mod common;

use common::{T0, hub_keys, new_gate, new_pins, open_gate, remote_keys, unhex};
use de1_relay::{
    HubConnection, Phase, RelayEvent, WsIn, kind,
    pake::{self, CODE_REJECT_BOUND, ElementRejection, SIDE_A, SIDE_B},
    test_support::test_seed,
    test_vectors::{bad_elements, rejection_name},
    ws_close,
};
use rand_chacha::ChaCha20Rng;
use rand_core::{RngCore, SeedableRng};
use serde_json::Value;

/// Every `bad_elements` vector is rejected, before `finish`, by the check named
/// in the vector; the checked-in file and the generator agree.
#[test]
fn rejects_bad_elements() {
    let file: Value = serde_json::from_str(include_str!("vectors/v4.json")).unwrap();
    let from_file = file["bad_elements"].as_array().unwrap();
    let generated = bad_elements();
    assert_eq!(from_file.len(), generated.len());
    assert!(
        generated.len() >= 15,
        "identity, p+1, p+3, 8 small-order, torsioned, side, off-curve, length"
    );
    for (v, b) in from_file.iter().zip(&generated) {
        let message = unhex(v["message"].as_str().unwrap());
        assert_eq!(message, b.message);
        assert_eq!(
            v["rejected_by"].as_str().unwrap(),
            rejection_name(b.rejected_by)
        );
        assert_eq!(
            pake::check_element(&message, SIDE_A),
            Err(b.rejected_by),
            "{}",
            b.name
        );

        // And through finish(): the hub's SPAKE2 never runs on it.
        let (r, h) = (remote_keys().device_id(), hub_keys().device_id());
        let code = pake::Code::parse("123456").unwrap();
        let (state, _) = pake::start_b(&code, &r, &h, ChaCha20Rng::from_seed([1; 32]));
        assert_eq!(
            pake::finish(state, &message, SIDE_A).map(|_| ()),
            Err(b.rejected_by),
            "{}",
            b.name
        );
    }
    // The spec's named cases are all there.
    let names: Vec<&str> = generated.iter().map(|b| b.name.as_str()).collect();
    for needle in [
        "identity",
        "p+1",
        "p+3",
        "small-order point 7",
        "torsioned",
        "wrong side",
    ] {
        assert!(names.iter().any(|n| n.contains(needle)), "{needle}");
    }
}

/// A bad `PAKE_A` on a live hub connection ends it with `ABORT(MALFORMED)`.
#[test]
fn hub_refuses_bad_pake_a() {
    for b in bad_elements() {
        let gate = new_gate();
        open_gate(&gate, T0);
        let mut hub = HubConnection::new(
            &hub_keys(),
            gate.clone(),
            new_pins(vec![]),
            "Hub",
            test_seed("hub-conn", 0),
            T0,
        );
        let hello = de1_relay::ClientHello {
            vmin: 4,
            vmax: 4,
            intent: de1_relay::Intent::Pair,
            client_id: Some(remote_keys().device_id()),
            client_name: "R".into(),
        };
        let s = hub.on_ws(WsIn::Text(&hello.encode()), T0);
        assert!(s.close.is_none());
        let mut msg = vec![kind::PAKE_A];
        msg.extend_from_slice(&b.message);
        let s = hub.on_ws(WsIn::Binary(&msg), T0);
        assert_eq!(
            s.close.as_ref().map(|c| c.code),
            Some(ws_close::PROTOCOL),
            "{}",
            b.name
        );
        assert_eq!(s.send.len(), 1);
        assert_eq!(s.send[0].bytes, vec![kind::ABORT, 9, 0]);
        assert!(s.events.iter().any(|e| matches!(e, RelayEvent::PairingFailed { reason, failures: 0, locked: false } if reason == "Malformed")));
        assert_eq!(hub.phase(), Phase::Failed);
        assert_eq!(gate.lock().unwrap().status(T0).failures, 0);
    }
}

/// 4 000 seeded honest elements (2 000 per side) pass the check.
#[test]
fn honest_elements_pass() {
    let (r, h) = (remote_keys().device_id(), hub_keys().device_id());
    let mut rng = ChaCha20Rng::from_seed([42; 32]);
    for i in 0..2_000_u32 {
        let code = pake::generate_code(&mut rng);
        let (_, a) = pake::start_a(&code, &r, &h, &mut rng);
        let (_, b) = pake::start_b(&code, &r, &h, &mut rng);
        assert_eq!(pake::check_element(&a, SIDE_A), Ok(()), "A {i}");
        assert_eq!(pake::check_element(&b, SIDE_B), Ok(()), "B {i}");
        // The sides are not interchangeable.
        assert_eq!(pake::check_element(&a, SIDE_B), Err(ElementRejection::Side));
    }
}

/// Codes are six ASCII digits and uniform: chi-square per digit position over
/// 10⁵ seeded draws, 9 degrees of freedom, p > 0.001 (statistic < 27.877).
#[test]
fn codes_are_uniform_six_digits() {
    let mut rng = ChaCha20Rng::from_seed(test_seed("codes", 0).expose_for_tests());
    let mut counts = [[0_u32; 10]; 6];
    let draws = 100_000;
    for _ in 0..draws {
        let code = pake::generate_code(&mut rng);
        let s = code.as_str();
        assert_eq!(s.len(), 6);
        for (pos, c) in s.bytes().enumerate() {
            assert!(c.is_ascii_digit());
            counts[pos][usize::from(c - b'0')] += 1;
        }
    }
    let expected = f64::from(draws) / 10.0;
    for (pos, row) in counts.iter().enumerate() {
        let chi2: f64 = row
            .iter()
            .map(|&n| (f64::from(n) - expected).powi(2) / expected)
            .sum();
        assert!(chi2 < 27.877, "position {pos}: chi-square {chi2}");
    }
}

/// A scripted RNG: 4 293 999 999 is accepted, 4 294 000 000 and above are redrawn.
#[test]
fn rejection_boundary() {
    struct Scripted(Vec<u32>);
    impl RngCore for Scripted {
        fn next_u32(&mut self) -> u32 {
            self.0.remove(0)
        }
        fn next_u64(&mut self) -> u64 {
            unimplemented!()
        }
        fn fill_bytes(&mut self, _: &mut [u8]) {
            unimplemented!()
        }
        fn try_fill_bytes(&mut self, _: &mut [u8]) -> Result<(), rand_core::Error> {
            unimplemented!()
        }
    }
    assert_eq!(CODE_REJECT_BOUND, 4_294_000_000);
    let mut s = Scripted(vec![4_293_999_999]);
    assert_eq!(pake::generate_code(&mut s).as_str(), "999999");
    let mut s = Scripted(vec![4_294_000_000, 5]);
    assert_eq!(pake::generate_code(&mut s).as_str(), "000005");
    let mut s = Scripted(vec![u32::MAX, 4_294_000_001, 123_456]);
    assert_eq!(pake::generate_code(&mut s).as_str(), "123456");
    assert!(s.0.is_empty());
}
