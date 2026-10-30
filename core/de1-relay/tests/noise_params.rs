//! AC-2: the Noise suites are exactly the spec's, and our resolver (with its
//! all-zero DH check) reproduces the cacophony vectors for both, driven by a
//! scripted RNG that returns the vectors' ephemerals.

mod common;

use common::unhex;
use de1_relay::{IK_PARAMS, RelayResolver, XX_PARAMS, test_support};
use serde_json::Value;

fn arr32(v: &Value) -> [u8; 32] {
    unhex(v.as_str().unwrap()).try_into().unwrap()
}

#[test]
fn suites_match_cacophony() {
    assert_eq!(XX_PARAMS, "Noise_XXpsk3_25519_ChaChaPoly_SHA256");
    assert_eq!(IK_PARAMS, "Noise_IK_25519_ChaChaPoly_SHA256");
    let file: Value = serde_json::from_str(include_str!("vectors/cacophony.json")).unwrap();
    let vectors = file["vectors"].as_array().unwrap();
    assert_eq!(vectors.len(), 2);
    for v in vectors {
        let name = v["protocol_name"].as_str().unwrap();
        let pairing = match name {
            n if n == XX_PARAMS => true,
            n if n == IK_PARAMS => false,
            other => panic!("unexpected suite {other}"),
        };
        let init_static = arr32(&v["init_static"]);
        let resp_static = arr32(&v["resp_static"]);
        let psk = |side: &str| {
            v[side]
                .as_array()
                .map(|p| test_support::psk_from_bytes(arr32(&p[0])))
        };
        let init_psk = psk("init_psks");
        let resp_psk = psk("resp_psks");
        let init_rs = v.get("init_remote_static").map(arr32);
        let mut init = test_support::build_handshake(
            pairing,
            true,
            &init_static,
            init_rs.as_ref(),
            &unhex(v["init_prologue"].as_str().unwrap()),
            init_psk.as_ref(),
            RelayResolver::scripted_for_tests(vec![unhex(v["init_ephemeral"].as_str().unwrap())]),
        )
        .unwrap();
        let mut resp = test_support::build_handshake(
            pairing,
            false,
            &resp_static,
            None,
            &unhex(v["resp_prologue"].as_str().unwrap()),
            resp_psk.as_ref(),
            RelayResolver::scripted_for_tests(vec![unhex(v["resp_ephemeral"].as_str().unwrap())]),
        )
        .unwrap();
        let messages = v["messages"].as_array().unwrap();
        let mut buf = vec![0_u8; 65_535];
        let mut out = vec![0_u8; 65_535];
        let mut i = 0;
        // Handshake messages alternate initiator, responder, ...
        while !init.is_handshake_finished() || !resp.is_handshake_finished() {
            let m = &messages[i];
            let payload = unhex(m["payload"].as_str().unwrap());
            let expected = unhex(m["ciphertext"].as_str().unwrap());
            let (w, r) = if i % 2 == 0 {
                (&mut init, &mut resp)
            } else {
                (&mut resp, &mut init)
            };
            let n = w.write_message(&payload, &mut buf).unwrap();
            assert_eq!(&buf[..n], &expected[..], "{name} handshake message {i}");
            let p = r.read_message(&buf[..n], &mut out).unwrap();
            assert_eq!(&out[..p], &payload[..], "{name} handshake payload {i}");
            i += 1;
        }
        assert_eq!(
            init.get_handshake_hash(),
            &unhex(v["handshake_hash"].as_str().unwrap())[..],
            "{name} h"
        );
        assert_eq!(resp.get_handshake_hash(), init.get_handshake_hash());
        let mut init = init.into_transport_mode().unwrap();
        let mut resp = resp.into_transport_mode().unwrap();
        // Transport messages keep alternating, starting with whoever's turn it is.
        for (j, m) in messages.iter().enumerate().skip(i) {
            let payload = unhex(m["payload"].as_str().unwrap());
            let expected = unhex(m["ciphertext"].as_str().unwrap());
            let (w, r) = if j % 2 == 0 {
                (&mut init, &mut resp)
            } else {
                (&mut resp, &mut init)
            };
            let n = w.write_message(&payload, &mut buf).unwrap();
            assert_eq!(&buf[..n], &expected[..], "{name} transport message {j}");
            let p = r.read_message(&buf[..n], &mut out).unwrap();
            assert_eq!(&out[..p], &payload[..]);
        }
    }
}

/// The exact handshake message lengths with empty payloads (§2.4 step 7): the
/// cacophony payload lengths subtracted from its ciphertexts.
#[test]
fn empty_payload_lengths() {
    let file: Value = serde_json::from_str(include_str!("vectors/cacophony.json")).unwrap();
    for v in file["vectors"].as_array().unwrap() {
        let lens: Vec<usize> = v["messages"].as_array().unwrap()[..if v["protocol_name"]
            == XX_PARAMS
        {
            3
        } else {
            2
        }]
            .iter()
            .map(|m| {
                (m["ciphertext"].as_str().unwrap().len() - m["payload"].as_str().unwrap().len()) / 2
            })
            .collect();
        if v["protocol_name"] == XX_PARAMS {
            assert_eq!(lens, de1_relay::XX_MESSAGE_LENS.to_vec());
        } else {
            assert_eq!(lens, de1_relay::IK_MESSAGE_LENS.to_vec());
        }
    }
}
