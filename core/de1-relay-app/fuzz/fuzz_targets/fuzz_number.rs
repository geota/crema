//! Arbitrary number texts through Kotlin's toDoubleOrNull and back.
#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| de1_relay_app::fuzzing::number(data));
