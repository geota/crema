//! Arbitrary relayed controls: a method, a NUL, then the arguments.
#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    de1_relay_app::fuzzing::decode(data);
});
