//! Any message encoded into Motto 80 frames reassembles to itself.
#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| de1_grinder::fuzzing::motto_round_trip(data));
