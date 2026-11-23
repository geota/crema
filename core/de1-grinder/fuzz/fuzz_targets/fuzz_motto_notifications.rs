//! Arbitrary notification streams through a connected Motto 80 driver.
#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| de1_grinder::fuzzing::motto_notifications(data));
