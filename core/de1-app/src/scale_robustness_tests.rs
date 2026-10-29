//! Scale-path regression tests from the 2026-10-25 cohort review (report 01
//! P2-4, the scales appendix F-03 / F-04 / F-12 / F-14). Kept out of
//! `lib.rs` so the batch touches that file as little as possible.

use de1_scale::{acaia, decent_scale, difluid};

use crate::{Command, CoreOutput, CremaCore, Event, Source};

fn scale_writes(out: &CoreOutput) -> Vec<Vec<u8>> {
    out.commands
        .iter()
        .filter_map(|c| match c {
            Command::WriteScale { data } => Some(data.clone()),
            Command::WriteCharacteristic { .. } => None,
        })
        .collect()
}

fn readings(out: &CoreOutput) -> Vec<f32> {
    out.events
        .iter()
        .filter_map(|e| match e {
            Event::ScaleReading { weight, .. } => Some(*weight),
            _ => None,
        })
        .collect()
}

/// 01 P2-4: the Skale's `mantissa × 10^exponent` frame decodes to `inf`
/// (exponent `0x7F`) or `1e20` (`0x14`). With a weight target armed and
/// inside the early-flow window, the untared-cup guard ran before the spike
/// gate and emitted `ScaleReading { weight: inf }` (`null` over JSON). Both
/// frames are now dropped at intake, at rest and mid-shot.
#[test]
fn implausible_skale_weights_never_reach_the_shells() {
    let inf_frame = [0x00, 0x01, 0x00, 0x00, 0x7F];
    let huge_frame = [0x00, 0x01, 0x00, 0x00, 0x14];

    // At rest.
    let mut core = CremaCore::new();
    core.connect_scale("Skale", &[]);
    for frame in [inf_frame, huge_frame] {
        let out = core.on_notification(Source::ScaleWeight, &frame, 500);
        assert!(readings(&out).is_empty(), "{frame:02X?} at rest: {out:?}");
    }
    // A real reading still flows: mantissa 185, exponent -1 = 18.5 g.
    let out = core.on_notification(Source::ScaleWeight, &[0x00, 0xB9, 0x00, 0x00, 0xFF], 600);
    assert_eq!(readings(&out), vec![18.5]);

    // Mid-shot, weight target armed, inside the untared-cup window.
    let mut core = CremaCore::new();
    core.connect_scale("Skale", &[]);
    core.set_profile_target_weight(Some(30.0));
    core.on_notification(Source::De1State, &[4, 5], 0);
    for (frame, at) in [(inf_frame, 1_600u64), (huge_frame, 2_000)] {
        let out = core.on_notification(Source::ScaleWeight, &frame, at);
        assert!(
            readings(&out)
                .iter()
                .all(|w| w.is_finite() && w.abs() <= 10_000.0),
            "{frame:02X?} mid-shot: {out:?}"
        );
        assert!(readings(&out).is_empty(), "{frame:02X?} mid-shot: {out:?}");
    }
}

/// F-03: an original Decent Scale on unknown or v1.0 firmware occasionally
/// drops a command, so tare and timer frames go twice (the same frame — the
/// tare counter does not advance), like de1app and reaprime's conservative
/// profile. A scale proven to be v1.2 or an HDS gets one copy.
#[test]
fn decent_tare_and_timer_double_send_while_firmware_is_unproven() {
    let mut core = CremaCore::new();
    core.connect_scale("Decent Scale", &[]);
    let writes = scale_writes(&core.tare_scale());
    assert_eq!(writes.len(), 2, "{writes:02X?}");
    assert_eq!(writes[0], writes[1], "same frame, same counter");
    let writes = scale_writes(&core.start_timer());
    assert_eq!(writes, vec![decent_scale::timer_start().to_vec(); 2]);

    // A timestamped 10-byte weight frame proves v1.2: one copy from then on.
    let mut v12 = [0x03, 0xCE, 0x00, 0xB9, 0x00, 0x05, 0x03, 0x00, 0x00, 0x00];
    v12[9] = v12[..9].iter().fold(0, |a, b| a ^ b);
    core.on_notification(Source::ScaleWeight, &v12, 100);
    assert_eq!(scale_writes(&core.tare_scale()).len(), 1);
    assert_eq!(scale_writes(&core.stop_timer()).len(), 1);
}

/// F-14: the Decent tare counter used to restart at 0 on every reconnect, so
/// a first tare could repeat the previous session's last counter and be
/// deduplicated by the scale. The core now carries it across reconnects,
/// seeded at 253 like de1app.
#[test]
fn decent_tare_counter_survives_a_reconnect() {
    let counter = |out: &CoreOutput| scale_writes(out)[0][2];
    let mut core = CremaCore::new();
    core.connect_scale("Decent Scale", &[]);
    assert_eq!(counter(&core.tare_scale()), 253);
    assert_eq!(counter(&core.tare_scale()), 254);
    core.disconnect_scale();
    core.connect_scale("Decent Scale", &[]);
    assert_eq!(counter(&core.tare_scale()), 255);
    assert_eq!(counter(&core.tare_scale()), 0);
}

/// F-12: both upstreams follow a Difluid timer stop with the start/reset
/// frame; Crema sent the stop alone.
#[test]
fn difluid_timer_stop_also_resets() {
    let mut core = CremaCore::new();
    core.connect_scale("Microbalance", &[]);
    assert_eq!(
        scale_writes(&core.stop_timer()),
        vec![difluid::TIMER_STOP.to_vec(), difluid::TIMER_RESET.to_vec()]
    );
    assert_eq!(
        scale_writes(&core.start_timer()),
        vec![difluid::TIMER_START.to_vec()]
    );
}

/// F-04: the Acaia ident / config handshake was sent once at connect; a
/// dropped or too-early ident left the scale silent until the stale-scale
/// watchdog. Each heartbeat now re-sends it until the first weight frame,
/// at most ten times.
#[test]
fn acaia_handshake_rides_the_heartbeat_until_the_first_weight() {
    let handshake = [acaia::IDENT.to_vec(), acaia::CONFIG.to_vec()];
    let heartbeat = acaia::HEARTBEAT.to_vec();

    let mut core = CremaCore::new();
    core.connect_scale("PEARLS", &[]);
    let writes = scale_writes(&core.scale_heartbeat().expect("acaia heartbeat"));
    assert_eq!(writes[..2], handshake[..], "{writes:02X?}");
    assert_eq!(writes.last(), Some(&heartbeat));

    // The first weight frame ends the retries.
    let weight = [
        0xEF, 0xDD, 0x0C, 0x08, 0x05, 0xB9, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
    ];
    let out = core.on_notification(Source::ScaleWeight, &weight, 100);
    assert!(!readings(&out).is_empty(), "fixture decodes: {out:?}");
    assert_eq!(
        scale_writes(&core.scale_heartbeat().unwrap()),
        vec![heartbeat.clone()]
    );

    // A scale that never streams stops getting the handshake after ten tries.
    let mut core = CremaCore::new();
    core.connect_scale("PEARLS", &[]);
    for _ in 0..10 {
        assert_eq!(scale_writes(&core.scale_heartbeat().unwrap()).len(), 3);
    }
    assert_eq!(
        scale_writes(&core.scale_heartbeat().unwrap()),
        vec![heartbeat]
    );
}
