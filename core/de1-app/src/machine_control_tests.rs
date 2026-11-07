//! Characterisation tests of the machine-control bugs in
//! `.scratch/machine-control/DESIGN.md` §7 (issues #314 to #320).
//!
//! Each test asserts what the core does **today**, so a change to any of it is
//! a deliberate change. `Flips in PR n` names the PR that inverts the test;
//! that PR rewrites it to the "after" in the note. A test with no such line is
//! expected to stay, and `Fixed in PR n` marks one that has already flipped.
#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_precision_loss,
    clippy::cast_sign_loss
)]

use crate::*;
use de1_domain::HotWaterMode;
use std::time::Duration;

fn bookoo(g: f32) -> [u8; 20] {
    let c = (g * 100.0).round() as u32;
    let mut p = [0u8; 20];
    p[..10].copy_from_slice(&[
        3,
        0x0B,
        0,
        0,
        0,
        0,
        b'+',
        (c >> 16) as u8,
        (c >> 8) as u8,
        c as u8,
    ]);
    p[19] = p[..19].iter().fold(0, |a, &b| a ^ b);
    p
}
fn state_writes(o: &CoreOutput) -> Vec<u8> {
    o.commands
        .iter()
        .filter_map(|c| match c {
            Command::WriteCharacteristic {
                target: WriteTarget::De1RequestedState,
                data,
            } => Some(data[0]),
            _ => None,
        })
        .collect()
}
const SAMPLE: [u8; 19] = [
    0x12, 0x34, 0x90, 0x00, 0x28, 0x00, 0x5C, 0x80, 0x00, 0x58, 0x00, 0x5A, 0x00, 0x5D, 0x00, 0x60,
    0x40, 0x03, 0x96,
];
fn sample(t: u16) -> [u8; 19] {
    let mut s = SAMPLE;
    s[0..2].copy_from_slice(&t.to_be_bytes());
    s
}

fn steam_targets(mode: HotWaterMode) -> SteamHotWaterTargets {
    SteamHotWaterTargets {
        steam_temp_c: 150.0,
        steam_timeout_s: 60.0,
        hot_water_temp_c: 85.0,
        hot_water: de1_domain::HotWaterDose {
            mode,
            time_s: 30.0,
            time_no_limit: false,
            volume_ml: 120.0,
            ratio: 3.0,
        },
    }
}

/// P1: the hot-water weight stop is one write: lost, it is never repeated.
/// Flips in PR 6: retries until the machine confirms.
#[test]
fn p1_hot_water_stop_is_never_reenforced() {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 0);
    core.set_auto_tare(false);
    core.set_steam_hotwater_targets(&steam_targets(HotWaterMode::Volume));
    core.on_notification(Source::De1State, &[2, 0], 10);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 20);
    core.on_notification(Source::De1State, &[6, 5], 100); // HotWater
    assert!(core.hot_water_stop_target_g.is_some(), "armed");
    let mut stops = vec![];
    let mut t = 200;
    let mut g = 0.0;
    for _ in 0..60 {
        g += 4.0;
        t += 500;
        let o = core.on_notification(Source::ScaleWeight, &bookoo(g), t);
        stops.extend(state_writes(&o));
        let o = core.on_notification(Source::De1ShotSample, &sample((t / 8) as u16), t + 10);
        stops.extend(state_writes(&o));
    }
    assert_eq!(stops.len(), 1, "exactly one write, no retry");
}

/// P2: scale stalls mid-pour: nothing bounds the pour but the 250 ml packet.
/// Flips in PR 8: stops near the target on the estimate.
#[test]
fn p2_hot_water_scale_stall_has_no_fallback() {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 0);
    core.set_auto_tare(false);
    core.connect_sweep(
        &de1_domain::ConnectSweepSettings {
            ..Default::default()
        },
        1,
    );
    core.set_steam_hotwater_targets(&steam_targets(HotWaterMode::Volume));
    core.on_notification(Source::De1State, &[2, 0], 10);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 20);
    core.on_notification(Source::De1State, &[6, 5], 100);
    core.on_notification(Source::ScaleWeight, &bookoo(10.0), 300);
    let mut stops = vec![];
    let mut events = vec![];
    for i in 1..200u64 {
        let t = 300 + i * 250;
        let o = core.on_tick(t);
        stops.extend(state_writes(&o));
        events.extend(
            o.events
                .iter()
                .filter(|e| matches!(e, Event::ScaleStale | Event::ScaleLinkDead { .. }))
                .map(|e| format!("{e:?}")),
        );
        let o = core.on_notification(Source::De1ShotSample, &sample((t / 8) as u16), t + 1);
        stops.extend(state_writes(&o));
    }
    assert!(stops.is_empty());
}

/// A scale write that tares a Bookoo.
fn tares(o: &CoreOutput) -> usize {
    o.commands
        .iter()
        .filter(|c| matches!(c, Command::WriteScale { data } if data.starts_with(&[3, 0x0A, 1])))
        .count()
}

/// A connected scale and a 36 g target on a fresh core, with 20 g on the scale.
fn core_with_cup() -> CremaCore {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_profile_target_weight(Some(36.0));
    core.on_notification(Source::ScaleWeight, &bookoo(20.0), 0);
    core
}

/// P3: first state seen mid-pour: the cup keeps its weight.
#[test]
fn p3_late_join_mid_pour_leaves_the_cup_alone() {
    let mut core = core_with_cup();
    let o = core.on_notification(Source::De1State, &[4, 5], 100);
    assert_eq!(tares(&o), 0);
    assert!(core.tare_gate.is_none());
    assert!(!core.auto_tare_pending);

    // Kept session: the link dropped at Idle, a shot started at the GHC meanwhile.
    let mut core = core_with_cup();
    core.on_notification(Source::De1State, &[2, 0], 10);
    core.de1_link_lost();
    let o = core.on_notification(Source::De1State, &[4, 5], 9000);
    assert_eq!(tares(&o), 0);
    assert!(core.tare_gate.is_none());
}

/// A shot first seen flowing runs on volume and time: its scale reading says
/// nothing about the cup, so no weight stop, however far past target it reads.
#[test]
fn an_unseen_shot_never_stops_on_weight() {
    let mut core = core_with_cup();
    core.set_max_shot_duration(Some(30.0));
    let mut out = core.on_notification(Source::De1State, &[4, 5], 100);
    let (armed, targets) = core.stop_targets_projection();
    assert!(armed);
    assert_eq!(targets.weight, None);
    assert_eq!(targets.max_time, Some(30.0));
    assert_eq!(core.weight_stop_blocked_by(), Some("began-unseen"));
    for i in 0..20u64 {
        let t = 200 + i * 100;
        out = core.on_notification(Source::ScaleWeight, &bookoo(60.0 + i as f32), t);
        assert!(state_writes(&out).is_empty(), "stopped at {t}");
    }
    assert!(
        out.events.iter().all(|e| !matches!(
            e,
            Event::SawAutoZeroed { .. } | Event::SawSuppressedUntaredCup { .. }
        )),
        "the untared-cup guard is for a shot that was tared"
    );
    let o = core.on_notification(Source::De1ShotSample, &sample(0), 31_000);
    assert_eq!(state_writes(&o), [MachineState::Idle as u8]);
}

/// The bytes of the scale timer's start, stop and reset, on a Bookoo.
fn timer_commands(core: &CremaCore) -> Vec<Command> {
    [core.start_timer(), core.stop_timer(), core.reset_timer()]
        .into_iter()
        .flat_map(|o| o.commands)
        .collect()
}

/// How many of `o`'s commands drive the scale's timer.
fn timer_writes(o: &CoreOutput, timer: &[Command]) -> usize {
    o.commands.iter().filter(|c| timer.contains(c)).count()
}

/// Feed a shot's first states, then a shot's worth of samples and readings,
/// then a drop and its end, counting every timer command on the way.
fn timer_writes_across_a_shot(core: &mut CremaCore) -> usize {
    let timer = timer_commands(core);
    assert_eq!(timer.len(), 3, "a Bookoo has a timer");
    let mut outs = vec![core.on_notification(Source::De1State, &[4, 5], 100)];
    outs.push(core.on_notification(Source::ScaleWeight, &bookoo(25.0), 200));
    outs.push(core.de1_link_lost());
    outs.push(core.on_notification(Source::De1State, &[4, 5], 300));
    outs.push(core.on_notification(Source::De1State, &[2, 0], 40_000));
    outs.iter().map(|o| timer_writes(o, &timer)).sum()
}

/// H12's "no beep": a shot first seen mid-pour leaves the scale's timer alone,
/// including the stop, which would otherwise end a timer the user started.
#[test]
fn an_unseen_shot_leaves_the_scale_timer_alone() {
    let mut core = core_with_cup();
    assert_eq!(timer_writes_across_a_shot(&mut core), 0);
}

/// The same run on a shot the app saw from its start sends all of them, so the
/// test above can't pass for want of a timer. Reset, start, the drop's stop
/// and the end's stop.
#[test]
fn a_seen_shot_drives_the_scale_timer() {
    let mut core = core_with_cup();
    core.on_notification(Source::De1State, &[2, 0], 10);
    assert_eq!(timer_writes_across_a_shot(&mut core), 4);
}

/// Run an unseen shot past 30 s with a heavy cup on the scale, to its end.
fn finished_unseen_shot() -> (CremaCore, CoreOutput) {
    let mut core = core_with_cup();
    core.on_notification(Source::De1State, &[4, 5], 100);
    for i in 0..300u64 {
        let t = 200 + i * 100;
        core.on_notification(Source::De1ShotSample, &sample(i as u16 * 12), t);
        core.on_notification(Source::ScaleWeight, &bookoo(200.0 + i as f32 / 10.0), t + 1);
    }
    let end = core.on_notification(Source::De1State, &[2, 0], 31_000);
    (core, end)
}

/// A wrong yield is worse than none: an unseen shot's readings are the cup,
/// so it completes at once with no weight, not through the drip settle.
#[test]
fn an_unseen_shot_completes_with_no_weight() {
    let (core, end) = finished_unseen_shot();
    let Some(Event::ShotCompleted {
        peak_weight,
        final_weight,
        disposition,
        ..
    }) = end
        .events
        .iter()
        .find(|e| matches!(e, Event::ShotCompleted { .. }))
    else {
        panic!("completed on the state that ended it: {:?}", end.events);
    };
    assert_eq!(*peak_weight, None);
    assert_eq!(*final_weight, None);
    assert_eq!(*disposition, de1_domain::ShotDisposition::Record);
    assert!(core.post_shot_settle.is_none());
}

/// Every reader of a stored shot's yield falls back to its series, so the
/// series has to be clean too: the shot reads as one with no scale.
#[test]
fn an_unseen_shot_stores_no_yield_anywhere() {
    let (mut core, _) = finished_unseen_shot();
    let shot = core
        .take_stored_shot(de1_domain::ShotCaptureContext {
            completed_at: 1,
            ..de1_domain::ShotCaptureContext::default()
        })
        .expect("the shot is recorded");
    assert!(shot.record.samples.len() > 100);
    assert_eq!(shot.metadata.yield_out, None);
    assert!(shot.record.samples.iter().all(|t| t.scale_weight.is_none()));
    let peaks = shot.peaks();
    assert_eq!(peaks.peak_weight, None);
    assert_eq!(peaks.final_weight, None);

    let export: serde_json::Value =
        serde_json::from_str(&de1_domain::export_v2_json_shot(&shot).expect("exports"))
            .expect("JSON");
    let drink_weight = &export["app"]["data"]["settings"]["drink_weight"];
    assert!(
        drink_weight.is_null() || drink_weight.as_f64() == Some(0.0),
        "uploaded {drink_weight}"
    );

    let stats = de1_domain::history_stats(&[de1_domain::ShotStatInput {
        duration_ms: 30_000,
        final_weight_g: shot.metadata.yield_out,
        peak_weight_g: peaks.peak_weight,
        dose_g: Some(18.0),
        rating: None,
    }]);
    assert_eq!(stats.count, 1);
    assert_eq!(stats.avg_weight_g, None);
    assert_eq!(stats.avg_ratio, None);
}

/// The latch ends with the shot: the next one, seen from its start, weighs.
#[test]
fn the_shot_after_an_unseen_one_has_a_yield() {
    let (mut core, _) = finished_unseen_shot();
    core.on_notification(Source::De1State, &[4, 2], 40_000);
    core.on_notification(Source::De1State, &[4, 5], 41_000);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 41_100);
    for i in 0..20u64 {
        let t = 41_200 + i * 100;
        core.on_notification(Source::De1ShotSample, &sample(i as u16 * 12), t);
        core.on_notification(Source::ScaleWeight, &bookoo(i as f32), t + 1);
    }
    assert!(core.shot_metrics.final_weight.is_some());
}

/// The first substate decides: a first state in a pre-flow substate is a start
/// the app did see, so it's tared and stops on weight.
#[test]
fn a_shot_first_seen_heating_is_tared_and_stops_on_weight() {
    let mut core = core_with_cup();
    let o = core.on_notification(Source::De1State, &[4, 2], 100);
    assert_eq!(core.weight_stop_blocked_by(), None);
    let o2 = core.on_notification(Source::De1State, &[4, 5], 200);
    assert_eq!(tares(&o) + tares(&o2), 1, "the fallback tare at first flow");
    assert_eq!(core.stop_targets_projection().1.weight, Some(36.0));
}

/// A drop mid-shot isn't a late join: the session outlives it (the #15 class).
#[test]
fn a_shot_seen_from_its_start_keeps_its_weight_stop_across_a_drop() {
    let mut core = core_with_cup();
    core.on_notification(Source::De1State, &[2, 0], 10);
    core.on_notification(Source::De1State, &[4, 5], 20);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 2_000);
    core.de1_link_lost();
    let o = core.on_notification(Source::De1State, &[4, 5], 3_000);
    assert_eq!(tares(&o), 0);
    assert_eq!(core.weight_stop_blocked_by(), None);
    assert_eq!(core.stop_targets_projection().1.weight, Some(36.0));
    let mut stops = vec![];
    for i in 0..60u64 {
        let g = i as f32;
        let o = core.on_notification(Source::ScaleWeight, &bookoo(g), 3_100 + i * 100);
        stops.extend(state_writes(&o));
    }
    assert_eq!(stops.first(), Some(&(MachineState::Idle as u8)));
}

/// `Unseen` ends with its session: the next shot is tared and stops on weight.
#[test]
fn the_shot_after_an_unseen_one_is_seen() {
    let mut core = core_with_cup();
    core.on_notification(Source::De1State, &[4, 5], 100);
    assert_eq!(core.weight_stop_blocked_by(), Some("began-unseen"));
    core.on_notification(Source::De1State, &[2, 0], 5_000);
    assert_eq!(core.weight_stop_blocked_by(), None);
    assert_eq!(core.stop_targets_projection().1.weight, Some(36.0));
    let o = core.on_notification(Source::De1State, &[4, 5], 9_000);
    assert_eq!(tares(&o), 1);
}

/// A teardown ends the session, so the same pour is a late join afterwards.
#[test]
fn a_reset_mid_shot_makes_the_pour_a_late_join() {
    let mut core = core_with_cup();
    core.on_notification(Source::De1State, &[2, 0], 10);
    core.on_notification(Source::De1State, &[4, 5], 20);
    core.reset();
    let o = core.on_notification(Source::De1State, &[4, 5], 3_000);
    assert_eq!(tares(&o), 0);
    assert_eq!(core.weight_stop_blocked_by(), Some("began-unseen"));
}

/// An outage is timed from its first drop, on the core's own clock, and a
/// teardown has none.
#[test]
fn the_link_records_when_an_outage_began() {
    let mut core = CremaCore::new();
    core.on_notification(Source::De1State, &[2, 0], 5_000);
    assert!(core.link.state_seen);
    core.de1_link_lost();
    assert!(!core.link.state_seen);
    assert_eq!(core.link.lost_at, Some(Duration::from_secs(5)));
    // A failed reconnect, with no state heard: the same outage.
    core.on_tick(9_000);
    core.de1_link_lost();
    assert_eq!(core.link.lost_at, Some(Duration::from_secs(5)));
    core.on_notification(Source::De1State, &[2, 0], 12_000);
    core.de1_link_lost();
    assert_eq!(core.link.lost_at, Some(Duration::from_secs(12)));
    core.reset();
    assert_eq!(core.link.lost_at, None);
}

/// P4: nothing armed at first flow; a scale arrives during Pouring.
/// Flips in PR 7: stops at 36 g.
#[test]
fn p4_scale_after_pouring_begins_never_arms_saw_with_default_settings() {
    let mut core = CremaCore::new();
    core.set_profile_target_weight(Some(36.0));
    // Default settings: max shot 0 = None; no volume limit.
    core.on_notification(Source::De1State, &[4, 1], 0);
    core.on_notification(Source::De1State, &[4, 5], 100);
    core.connect_scale("BOOKOO_SC", &[]);
    let mut stops = vec![];
    let mut g = 20.0;
    for i in 0..300u64 {
        g += 0.2;
        let o = core.on_notification(Source::ScaleWeight, &bookoo(g), 200 + i * 100);
        stops.extend(state_writes(&o));
        let o = core.on_notification(
            Source::De1ShotSample,
            &sample((i * 12) as u16),
            201 + i * 100,
        );
        stops.extend(state_writes(&o));
    }
    assert!(core.auto_stop.is_none());
    assert!(stops.is_empty());
}

/// P5: a connected scale that never reports: volume stays demoted, no watchdog.
/// Flips in PR 5: stops at 40 ml, `ScaleSilent`.
#[test]
fn p5_silent_scale_demotes_volume_and_is_never_flagged() {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_profile_target_weight(Some(36.0));
    core.set_profile_volume_limit(Some(40.0));
    core.on_notification(Source::De1State, &[2, 0], 0);
    core.on_notification(Source::De1State, &[4, 5], 0);
    let mut stops = vec![];
    let mut watchdog = vec![];
    for i in 1..1500u64 {
        let t = i * 100;
        let o = core.on_notification(Source::De1ShotSample, &sample((i * 12) as u16), t);
        stops.extend(state_writes(&o));
        let o = core.on_tick(t + 1);
        stops.extend(state_writes(&o));
        watchdog.extend(
            o.events
                .iter()
                .filter(|e| matches!(e, Event::ScaleStale | Event::ScaleLinkDead { .. }))
                .map(|e| format!("{e:?}")),
        );
    }
    assert!(core.volume_integrator.pour_ml() > 40.0);
    assert!(stops.is_empty());
    assert!(watchdog.is_empty());
}

fn run_shot(core: &mut CremaCore) -> u64 {
    core.set_profile_target_weight(Some(36.0));
    core.on_notification(Source::De1State, &[4, 1], 0);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 10);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 20);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 30);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 40);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 50);
    core.on_notification(Source::De1State, &[4, 5], 100);
    let mut t = 200;
    let mut g = 0.0;
    while t < 20_000 {
        core.on_notification(Source::ScaleWeight, &bookoo(g), t);
        core.on_notification(Source::De1ShotSample, &sample((t / 8) as u16), t + 1);
        g += 0.15;
        t += 100;
    }
    t
}

/// P6/P7: a link teardown in the settle window, a scale drop near the end.
/// Flips in PR 11: the shot is kept.
#[test]
fn p6_reset_in_post_shot_settle_loses_the_shot() {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    let t = run_shot(&mut core);
    let o = core.on_notification(Source::De1State, &[2, 0], t);
    let done = o
        .events
        .iter()
        .any(|e| matches!(e, Event::ShotCompleted { .. }));
    let settling = core.post_shot_settle.is_some();
    let o2 = core.reset();
    let done2 = o2
        .events
        .iter()
        .any(|e| matches!(e, Event::ShotCompleted { .. }));
    assert!(settling && !done && !done2 && core.pending_shot.is_none());
}

/// Flips in PR 11: the yield is kept.
#[test]
fn p7_scale_drop_in_settle_wipes_yield() {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    let t = run_shot(&mut core);
    core.on_notification(Source::De1State, &[2, 0], t);
    core.disconnect_scale();
    let o = core.on_tick(t + 3100);
    let fw: Vec<_> = o
        .events
        .iter()
        .filter_map(|e| match e {
            Event::ShotCompleted {
                final_weight,
                peak_weight,
                ..
            } => Some((*final_weight, *peak_weight)),
            _ => None,
        })
        .collect();
    assert_eq!(fw, vec![(None, None)]);
}

/// P9: an unknown substate byte used to drop the whole state report.
/// Fixed in PR 1.
#[test]
fn p9_unknown_substate_still_ends_the_shot() {
    let mut core = CremaCore::new();
    core.on_notification(Source::De1State, &[4, 5], 0);
    let o = core.on_notification(Source::De1State, &[2, 21], 1000); // Idle + a substate this build lacks
    assert!(
        o.events
            .iter()
            .any(|e| matches!(e, Event::ShotCompleted { .. }))
    );
    assert!(core.shot_started.is_none());
}

/// P11: scale flap around the target.
/// Flips in PR 8: stops near 36 g.
#[test]
fn p11_scale_flap_at_target_turns_saw_off() {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_auto_tare(false);
    core.set_profile_target_weight(Some(36.0));
    core.on_notification(Source::De1State, &[4, 5], 0);
    let mut t = 100;
    let mut g = 0.0;
    while g < 30.0 {
        core.on_notification(Source::ScaleWeight, &bookoo(g), t);
        g += 0.2;
        t += 100;
    }
    core.disconnect_scale();
    t += 2500;
    g += 8.0; // 38 g, 2.5 s later
    core.connect_scale("BOOKOO_SC", &[]);
    let mut stops = vec![];
    let mut ev = vec![];
    for _ in 0..200 {
        let o = core.on_notification(Source::ScaleWeight, &bookoo(g), t);
        stops.extend(state_writes(&o));
        ev.extend(
            o.events
                .iter()
                .filter(|e| {
                    matches!(
                        e,
                        Event::SawSuppressedUntaredCup { .. } | Event::SawAutoZeroed { .. }
                    )
                })
                .map(|e| format!("{e:?}")),
        );
        g += 0.2;
        t += 100;
    }
    assert!(stops.is_empty());
}

/// D1: the web pushes this pair for a profile with yieldOut 0 + a QC yield.
/// Flips in PR 10: `weight == 36`.
#[test]
fn d1_web_disabled_flag_beats_qc_yield() {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_weight_target_disabled(true); // profile.yieldOut <= 0 (web only)
    core.set_shot_target_weight(Some(36.0));
    assert_eq!(core.stop_targets_projection().1.weight, None);
}

/// P8: a step-weight skip used to repeat with no telemetry since the first
/// skip. Fixed in PR 2.
#[test]
fn p8_no_skip_retry_without_fresh_frame_info() {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_auto_tare(false);
    let mut p = de1_domain::cold_maintenance_profile();
    p.steps[0].weight = Some(3.0);
    let s = p.steps[0].clone();
    p.steps.push(s.clone());
    p.steps.push(s);
    p.steps[1].weight = None;
    p.steps[2].weight = None;
    core.set_active_profile(Some(&p));
    core.on_notification(Source::De1State, &[4, 5], 0);
    let mut f = sample(100);
    f[17] = 0;
    core.on_notification(Source::De1ShotSample, &f, 50);
    let mut skips = 0;
    let mut t = 100;
    let mut g = 2.0;
    for _ in 0..12 {
        // 1.2 s of weight with telemetry silent
        let o = core.on_notification(Source::ScaleWeight, &bookoo(g), t);
        skips += state_writes(&o).iter().filter(|b| **b == 14).count();
        g += 0.3;
        t += 100;
    }
    assert_eq!(skips, 1);
}

/// Q1: eco used to light a steam heater the user turned off. Fixed in PR 2.
#[test]
fn q1_eco_leaves_a_disabled_steam_heater_off() {
    let mut core = CremaCore::new();
    core.on_notification(Source::De1State, &[2, 0], 0);
    let s = de1_domain::ConnectSweepSettings {
        steam_temp_c: 0.0,
        steam_eco: true,
        ..Default::default()
    };
    core.connect_sweep(&s, 10);
    let pkt = |o: &CoreOutput| {
        o.commands
            .iter()
            .filter_map(|c| match c {
                Command::WriteCharacteristic {
                    target: WriteTarget::De1ShotSettings,
                    data,
                } => Some(data.clone()),
                _ => None,
            })
            .collect::<Vec<_>>()
    };
    let o = core.on_tick(10 + 601_000);
    assert!(
        o.events
            .iter()
            .any(|e| matches!(e, Event::SteamEcoModeChanged { eco: true }))
    );
    assert!(pkt(&o).iter().all(|packet| packet[1] == 0), "stays at 0 °C");
    assert_eq!(
        core.desired_shot_settings().map(|p| p.steam_temp_c),
        Some(0.0)
    );
}

/// Q3: mirror: notifications carry the hub's clock, ticks the remote's.
/// Flips in PR 12: becomes a Kotlin `ProxyTransport` test.
#[test]
fn q3_mirror_clock_mismatch_kills_scale_every_tick() {
    let mut core = CremaCore::new();
    core.set_read_only(true);
    core.connect_scale("BOOKOO_SC", &[]);
    let hub = 1_000_000u64; // hub booted 17 minutes ago
    let remote = 9_000_000u64; // remote booted 2.5 hours ago
    let mut dead = 0;
    for i in 0..10u64 {
        for j in 0..10u64 {
            core.on_notification(Source::ScaleWeight, &bookoo(0.0), hub + i * 1000 + j * 100);
        }
        let o = core.on_tick(remote + i * 1000 + 950);
        dead += o
            .events
            .iter()
            .filter(|e| matches!(e, Event::ScaleLinkDead { .. }))
            .count();
    }
    assert_eq!(dead, 10);
}

/// Q5: hot water that began during an outage isn't tared and has no weight
/// stop, the same as a pour the app opened on.
#[test]
fn q5_hot_water_begun_during_outage_is_left_alone() {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 0);
    core.set_steam_hotwater_targets(&steam_targets(HotWaterMode::Volume));
    core.on_notification(Source::De1State, &[2, 0], 10);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 20);
    core.de1_link_lost();
    core.on_notification(Source::ScaleWeight, &bookoo(90.0), 30);
    let o = core.on_notification(Source::De1State, &[6, 5], 2000);
    assert_eq!(tares(&o), 0);
    assert!(core.tare_gate.is_none());
    assert_eq!(core.hot_water_stop_target_g, None);
}

/// Hot water first seen heating is a start the app saw.
#[test]
fn hot_water_first_seen_heating_arms_its_stop() {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 0);
    core.set_steam_hotwater_targets(&steam_targets(HotWaterMode::Volume));
    let o = core.on_notification(Source::De1State, &[6, 2], 10);
    assert_eq!(tares(&o), 1);
    assert!(core.hot_water_stop_target_g.is_some());
}

/// Q6: physical tare at 20 g of 36: the overshoot equals what was in the cup.
/// Deferred (§5): stays as a characterisation test.
#[test]
fn q6_physical_tare_mid_shot() {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_auto_tare(false);
    core.set_profile_target_weight(Some(36.0));
    core.on_notification(Source::De1State, &[2, 0], 0);
    core.on_notification(Source::De1State, &[4, 5], 0);
    let (mut t, mut real) = (100u64, 0.0f32);
    let mut stop_at = None;
    while real < 100.0 && stop_at.is_none() {
        let shown = if real > 20.0 { real - 20.0 } else { real };
        let o = core.on_notification(Source::ScaleWeight, &bookoo(shown), t);
        if !state_writes(&o).is_empty() {
            stop_at = Some(real);
        }
        real += 0.2;
        t += 100;
    }
    assert!(stop_at.unwrap() > 54.0);
}

/// Q7: a profile with no steps fails at once but reports a 15 s timeout.
/// Deferred (§5), unrelated to stops.
#[test]
fn q7_start_with_rejected_profile_waits_for_timeout() {
    let mut core = CremaCore::new_with_model(1);
    core.on_notification(Source::De1State, &[2, 0], 0);
    core.connect_sweep(&de1_domain::ConnectSweepSettings::default(), 1);
    let mut p = de1_domain::blank_profile(&de1_domain::BrewDefaults::default());
    p.segments.clear();
    let r = core.start_shot(
        &p,
        &de1_domain::BrewOverrides::default(),
        Duration::from_millis(2),
    );
    assert!(r.accepted);
    assert!(core.shot_starting());
    let o = core.on_tick(16_000);
    assert!(o.events.iter().any(|e| matches!(
        e,
        Event::ShotStartEnded {
            end: StartEnd::UploadTimedOut,
            ..
        }
    )));
}

/// R1: a lost pre-flush used to request Espresso at 30 s, bouncing whatever was
/// running. Fixed in PR 2: it ends 10 s after the write, with no write.
#[test]
fn r1_lost_flush_ends_the_start_and_leaves_steam_alone() {
    let mut core = CremaCore::new_with_model(1);
    core.on_notification(Source::De1State, &[2, 0], 0);
    core.connect_sweep(&de1_domain::ConnectSweepSettings::default(), 1);
    core.set_start_settings(true, false, false);
    let p = serde_json::from_str::<Vec<de1_domain::CremaProfile>>(
        &de1_domain::builtin_crema_profiles_json(),
    )
    .unwrap()
    .remove(0);
    let r = core.start_shot(
        &p,
        &de1_domain::BrewOverrides::default(),
        Duration::from_millis(2),
    );
    assert!(r.accepted);
    // finish the sync upload
    let mut t = 3;
    for c in &r.output.commands {
        if let Command::WriteCharacteristic { target, data } = c {
            match target {
                WriteTarget::De1ProfileHeader => {
                    core.on_notification(Source::De1HeaderAck, data, t);
                }
                WriteTarget::De1ProfileFrame => {
                    core.on_notification(Source::De1FrameAck, data, t);
                }
                _ => {}
            }
        }
    }
    t += 600;
    assert_eq!(
        state_writes(&core.on_tick(t)),
        vec![15],
        "the flush request"
    );
    // the rinse write was lost; the user starts steam at the group head
    core.on_notification(Source::De1State, &[5, 7], 5_000);
    assert!(core.shot_starting());
    let o = core.on_tick(t + 10_000);
    assert!(o.events.iter().any(|e| matches!(
        e,
        Event::ShotStartEnded {
            end: StartEnd::FlushNotEntered,
            ..
        }
    )));
    assert!(state_writes(&o).is_empty());
    assert!(state_writes(&core.on_tick(31_000)).is_empty());
}

/// Review: a 1 s scale gap past a 36 ml limit stops the shot at once, because
/// the volume leg rearms when the scale goes. Flips in PR 5: no volume stop.
#[test]
fn review_scale_gap_rearms_a_passed_volume_limit() {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_auto_tare(false);
    core.set_profile_target_weight(Some(36.0));
    core.set_profile_volume_limit(Some(36.0));
    core.on_notification(Source::De1State, &[2, 0], 0);
    core.on_notification(Source::De1State, &[4, 5], 0);
    let mut t = 100;
    let mut volume_stop = false;
    for i in 0..300u64 {
        t = 100 + i * 200;
        core.on_notification(Source::ScaleWeight, &bookoo(0.0), t);
        let o = core.on_notification(Source::De1ShotSample, &sample((i * 24) as u16), t + 1);
        assert!(state_writes(&o).is_empty(), "the scale holds the limit off");
        if core.volume_integrator.pour_ml() > 40.0 {
            break;
        }
    }
    core.disconnect_scale();
    for i in 1..20u64 {
        let o = core.on_notification(
            Source::De1ShotSample,
            &sample((t / 8 + i * 24) as u16),
            t + i * 200,
        );
        volume_stop |= o.events.iter().any(|e| {
            matches!(
                e,
                Event::StopTriggered {
                    reason: StopReason::Volume
                }
            )
        });
    }
    assert!(volume_stop);
}

/// Review: a stop owed across a 25 s DE1 outage goes out on the first sample
/// back. PR 6 must keep this: the stop is sent on return, weight stays on.
#[test]
fn review_owed_stop_survives_a_25_s_outage() {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_auto_tare(false);
    core.set_profile_target_weight(Some(36.0));
    core.on_notification(Source::De1State, &[2, 0], 0);
    core.on_notification(Source::De1State, &[4, 5], 0);
    let mut t = 0;
    let mut g = 0.0;
    let mut first = vec![];
    while first.is_empty() {
        t += 100;
        g += 1.0;
        assert!(g < 100.0, "the weight stop fires");
        first = state_writes(&core.on_notification(Source::ScaleWeight, &bookoo(g), t));
    }
    core.de1_link_lost();
    let back = t + 25_000;
    core.on_notification(Source::De1State, &[4, 5], back);
    let o = core.on_notification(Source::De1ShotSample, &sample(100), back + 100);
    assert_eq!(state_writes(&o), vec![2]);
}

/// Review: a pour first seen mid-flow never skips a step: the reading is the
/// cup, not the step's yield. PR 5 lists this, but it has to land with the
/// removed tare, or an untared cup would skip at once.
#[test]
fn review_unseen_pour_sends_no_skip_to_next() {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    let mut p = de1_domain::cold_maintenance_profile();
    p.steps[0].weight = Some(3.0);
    let s = p.steps[0].clone();
    p.steps.push(s.clone());
    p.steps.push(s);
    p.steps[1].weight = None;
    p.steps[2].weight = None;
    core.set_active_profile(Some(&p));
    core.on_notification(Source::De1State, &[4, 5], 0);
    let mut skips = 0;
    for i in 0..10u64 {
        let t = 100 + i * 100;
        let mut f = sample(100 + i as u16 * 12);
        f[17] = 0;
        core.on_notification(Source::De1ShotSample, &f, t);
        let o = core.on_notification(Source::ScaleWeight, &bookoo(20.0), t + 1);
        skips += state_writes(&o).iter().filter(|b| **b == 14).count();
    }
    assert_eq!(skips, 0);
}
