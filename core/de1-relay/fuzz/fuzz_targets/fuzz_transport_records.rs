//! Records into an established pair, including fuzzed inner flags, types and payloads.
#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| de1_relay::fuzzing::transport_records(data));
