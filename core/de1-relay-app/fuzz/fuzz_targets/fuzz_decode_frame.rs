//! Arbitrary relay messages through the frame decoder and back.
#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| de1_relay_app::fuzzing::frame(data));
