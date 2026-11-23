//! Fuzz entry points, shared by the cargo-fuzz targets in `fuzz/` and the
//! corpus replay in `tests/fuzz_corpus.rs`, so every PR runs the corpus on
//! stable.

use crate::{Decoded, decode_command, encode_command, refusal};

/// Decode a relayed control from arbitrary bytes: the method, a NUL, then
/// the arguments (lossy UTF-8, as a shell's JSON decode would hand them in).
///
/// Invariants, as asserts:
/// - no panic;
/// - an accepted command passes [`refusal`] and has a wire form;
/// - its wire form decodes to a command with the same wire form (the one
///   alias, `machineState ESPRESSO`, re-encodes as `startShot`).
pub fn decode(data: &[u8]) -> Decoded {
    let text = String::from_utf8_lossy(data);
    let (method, args) = text.split_once('\0').unwrap_or((&text, ""));
    let decoded = decode_command(method, args);
    if let Decoded::Valid(cmd) = &decoded {
        assert_eq!(refusal(cmd), None, "{cmd:?}");
        let wire = encode_command(cmd).expect("an accepted command has a wire form");
        let Decoded::Valid(again) = decode_command(&wire.method, &wire.args) else {
            panic!("{wire:?} doesn't decode");
        };
        assert_eq!(encode_command(&again).as_ref(), Some(&wire), "{cmd:?}");
    }
    decoded
}

/// The parse entry point for numbers: [`crate::jvm::parse_double`] and the
/// formatter agree, so a parsed finite double prints and parses back.
pub fn number(data: &[u8]) {
    let text = String::from_utf8_lossy(data);
    if let Some(v) = crate::jvm::parse_double(&text) {
        let back = crate::jvm::parse_double(&crate::jvm::double_to_string(v));
        if v.is_nan() {
            assert!(back.is_some_and(f64::is_nan));
        } else {
            assert_eq!(back.map(f64::to_bits), Some(v.to_bits()), "{text:?}");
        }
    }
}

/// Decode arbitrary bytes as a relay message (lossy UTF-8; the shells
/// reject invalid UTF-8 before decoding).
///
/// Invariants, as asserts: no panic, and a decoded frame encodes to text
/// that decodes to the same frame and re-encodes to the same text.
pub fn frame(data: &[u8]) {
    let text = String::from_utf8_lossy(data);
    if let Ok(crate::frame::Decoded::Frame(f)) = crate::decode_frame(&text) {
        let wire = crate::encode_frame(&f);
        assert_eq!(
            crate::decode_frame(&wire),
            Ok(crate::frame::Decoded::Frame(f)),
            "{wire}"
        );
        if let Ok(crate::frame::Decoded::Frame(again)) = crate::decode_frame(&wire) {
            assert_eq!(crate::encode_frame(&again), wire);
        }
    }
}
