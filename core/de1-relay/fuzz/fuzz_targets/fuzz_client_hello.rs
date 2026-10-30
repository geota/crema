//! Arbitrary ClientHello texts and ServerHello bytes.
#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| de1_relay::fuzzing::client_hello(data));
