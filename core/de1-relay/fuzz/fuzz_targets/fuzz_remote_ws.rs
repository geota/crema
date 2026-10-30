//! The same into a RemoteConnection, against an honest hub.
#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| de1_relay::fuzzing::remote_ws(data));
