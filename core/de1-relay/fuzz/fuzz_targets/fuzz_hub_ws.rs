//! A sequence of text/binary inputs, ticks and shell calls into a HubConnection, against an honest remote (both intents).
#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| de1_relay::fuzzing::hub_ws(data));
