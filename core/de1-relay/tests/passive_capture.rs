//! AC-7 and AC-8: what a passive observer of pairing and a session learns.

mod common;

use common::*;
use de1_relay::{
    DeviceId, kind,
    pake::{self, Code, SIDE_A, SIDE_B},
    test_support,
};
use hkdf::Hkdf;
use rand_chacha::ChaCha20Rng;
use rand_core::SeedableRng;
use sha2::{Digest, Sha256};

/// The same code and the same hellos with different SPAKE2 randomness give
/// different psks: the psk depends on the DH secret, not on the code.
#[test]
fn psk_depends_on_dh_not_code() {
    let (r, h) = (remote_keys().device_id(), hub_keys().device_id());
    let hello = de1_relay::ClientHello {
        vmin: 4,
        vmax: 4,
        intent: de1_relay::Intent::Pair,
        client_id: Some(r),
        client_name: "Pixel 8".into(),
    }
    .encode();
    let sh = de1_relay::ServerHello {
        version: 4,
        status: de1_relay::HelloStatus::AwaitingCode,
        hub_id: h,
        secs_left: 120,
        name: "Kitchen tablet".into(),
        ext: vec![],
    }
    .encode();
    let code = Code::parse("482913").unwrap();
    let mut psks = Vec::new();
    for seed in 0..4_u8 {
        let (a_state, a) = pake::start_a(&code, &r, &h, ChaCha20Rng::from_seed([seed; 32]));
        let (b_state, b) = pake::start_b(&code, &r, &h, ChaCha20Rng::from_seed([seed + 100; 32]));
        let prologue = test_support::pair_prologue(&hello, &sh, &a, &b);
        let psk_a = pake::derive_psk(
            &pake::finish(a_state, &b, SIDE_B).unwrap(),
            &prologue,
            &r,
            &h,
        )
        .expose_for_tests();
        let psk_b = pake::derive_psk(
            &pake::finish(b_state, &a, SIDE_A).unwrap(),
            &prologue,
            &r,
            &h,
        )
        .expose_for_tests();
        assert_eq!(psk_a, psk_b);
        psks.push(psk_a);
    }
    for i in 0..psks.len() {
        for j in i + 1..psks.len() {
            assert_ne!(psks[i], psks[j]);
        }
    }
}

/// With the captured transcript of a real pairing and the true code, every
/// derivation an attacker can run without a party's SPAKE2 scalar (fresh
/// SPAKE2 runs with the true code; the code hashed with the hellos or the
/// prologue) misses the session's psk, so message 3 and `PAIR_COMPLETE` stay
/// unreadable. Kills the mutation "psk = HKDF(code, hellos)".
#[test]
fn code_alone_decrypts_nothing() {
    let (r, h) = (remote_keys(), hub_keys());
    let link = paired_link(&r, &h, 0);
    let real = link.hub.psk_for_tests().unwrap();
    assert_eq!(link.remote.psk_for_tests(), Some(real));
    let code = link.shown_code().unwrap();
    let wire = link.wire.clone();
    let hello = wire[0].msg.bytes.clone();
    let sh = wire[1].msg.bytes.clone();
    let pake_a = wire[2].msg.bytes[1..].to_vec();
    let pake_b = wire[3].msg.bytes[1..].to_vec();
    let (rid, hid) = (r.device_id(), h.device_id());
    let prologue = test_support::pair_prologue(&hello, &sh, &pake_a, &pake_b);
    let hellos_only = test_support::pair_prologue(&hello, &sh, &[], &[]);
    let code_v = Code::parse(&code).unwrap();

    let mut candidates: Vec<[u8; 32]> = Vec::new();
    // Fresh SPAKE2 runs against the captured messages, with the true code.
    for seed in 0..8_u8 {
        let (a, _) = pake::start_a(&code_v, &rid, &hid, ChaCha20Rng::from_seed([seed; 32]));
        let (b, _) = pake::start_b(
            &code_v,
            &rid,
            &hid,
            ChaCha20Rng::from_seed([seed ^ 0x80; 32]),
        );
        for key in [
            pake::finish(a, &pake_b, SIDE_B).unwrap(),
            pake::finish(b, &pake_a, SIDE_A).unwrap(),
        ] {
            candidates.push(pake::derive_psk(&key, &prologue, &rid, &hid).expose_for_tests());
        }
    }
    // The code itself as key material, with every public input.
    let info = [b"CremaRelay v4 psk".as_slice(), &rid.0, &hid.0].concat();
    for salt_src in [&prologue, &hellos_only, &hello, &sh] {
        for ikm in [
            code.as_bytes().to_vec(),
            Sha256::digest(code.as_bytes()).to_vec(),
        ] {
            let mut okm = [0_u8; 32];
            Hkdf::<Sha256>::new(Some(&Sha256::digest(salt_src)), &ikm)
                .expand(&info, &mut okm)
                .unwrap();
            candidates.push(okm);
            Hkdf::<Sha256>::new(None, &[ikm.as_slice(), salt_src].concat())
                .expand(&info, &mut okm)
                .unwrap();
            candidates.push(okm);
        }
    }
    for c in &candidates {
        assert_ne!(*c, real);
    }
    assert!(candidates.len() >= 30);
    let _ = DeviceId::of(&r.public());
}

/// The capture holds neither static public key, no frame JSON, and no text
/// after the ClientHellos.
#[test]
fn transcript_leaks_no_plaintext() {
    let (r, h) = (remote_keys(), hub_keys());
    let pairing = paired_link(&r, &h, 0);
    let mut session = established(&r, &h, 1);
    let config =
        br#"{"type":"config","settings":{"steamTempC":160,"profile":"Blooming espresso"}}"#;
    session.hub_send(config);
    session.remote_send(br#"{"type":"control","method":"startShot"}"#);
    assert_eq!(session.remote_delivered(), vec![config.to_vec()]);

    let capture: Vec<Wire> = pairing
        .wire
        .iter()
        .chain(session.wire.iter())
        .cloned()
        .collect();
    let contains = |hay: &[u8], needle: &[u8]| hay.windows(needle.len()).any(|w| w == needle);
    let texts: Vec<&Wire> = capture.iter().filter(|w| w.msg.text).collect();
    assert_eq!(texts.len(), 2, "only the two ClientHellos are text");
    for w in &capture {
        let m = &w.msg.bytes;
        assert!(!contains(m, &r.public()) && !contains(m, &h.public()));
        assert!(
            !contains(m, b"steamTempC") && !contains(m, b"startShot") && !contains(m, b"config")
        );
        if !w.msg.text {
            assert!(matches!(
                m[0],
                kind::SERVER_HELLO | kind::PAKE_A | kind::PAKE_B | kind::NOISE_HS | kind::RECORD
            ));
        }
    }
    // The resume hello names nobody.
    assert!(!contains(&texts[1].msg.bytes, b"Pixel"));
}
