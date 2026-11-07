//! Characterisation tests of the machine-control bugs in
//! `.scratch/machine-control/DESIGN.md` §7 (issues #314 to #320).
//!
//! Each test asserts what the core does **today**, so a change to any of it is
//! a deliberate change. `Flips in PR n` names the PR that inverts the test;
//! that PR rewrites it to the "after" in the note. A test with no such line is
//! expected to stay. The ones marked `#[ignore]` (P8, P9, Q1, R1) flip in PR 1
//! or PR 2, which branch from the same `main` and carry their own tests for the
//! fixed behaviour, so these must not block whichever lands second. Run them
//! with `cargo test -p de1-app --lib machine_control -- --ignored`.
//!
//! **Whichever of #338 and #339 lands second rewrites all four to the fixed
//! behaviour and removes `#[ignore]`.** CI never runs ignored tests, so left
//! alone they'd assert fixed bugs and nobody would notice.
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

/// P9: an unknown substate byte drops the whole state report.
/// Flips in PR 1: `ShotCompleted` fires.
#[ignore = "flips in PR 1"]
#[test]
fn p9_unknown_substate_misses_shot_end() {
    let mut core = CremaCore::new();
    core.on_notification(Source::De1State, &[4, 5], 0);
    let o = core.on_notification(Source::De1State, &[2, 21], 1000); // Idle + a substate this build lacks
    assert!(
        !o.events
            .iter()
            .any(|e| matches!(e, Event::ShotCompleted { .. }))
    );
    assert!(core.shot_started.is_some());
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

/// P8: step-weight skip repeats with no telemetry since the first skip.
/// Flips in PR 2: one skip.
#[ignore = "flips in PR 2"]
#[test]
fn p8_skip_retry_without_fresh_frame_info() {
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
    assert!(skips >= 2);
}

/// Q1: eco overrides a steam heater the user turned off.
/// Flips in PR 2: stays at 0 °C.
#[ignore = "flips in PR 2"]
#[test]
fn q1_eco_turns_a_disabled_steam_heater_on() {
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
    assert_eq!(pkt(&o)[0][1], 136);
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

/// R1: pre-flush never began; 30 s later the start bounces whatever is running.
/// Flips in PR 2: ends 10 s after the write with `FlushNotEntered`, no write.
#[ignore = "flips in PR 2"]
#[test]
fn r1_flush_ceiling_starts_espresso_over_steam() {
    let mut core = CremaCore::new_with_model(1);
    core.on_notification(Source::De1State, &[2, 0], 0);
    core.connect_sweep(&de1_domain::ConnectSweepSettings::default(), 1);
    core.set_start_settings(true, false, false);
    let p = de1_domain::CremaProfile {
        segments: vec![],
        ..serde_json::from_str::<Vec<de1_domain::CremaProfile>>(
            &de1_domain::builtin_crema_profiles_json(),
        )
        .unwrap()
        .remove(0)
    };
    let p = de1_domain::CremaProfile {
        segments: serde_json::from_str::<Vec<de1_domain::CremaProfile>>(
            &de1_domain::builtin_crema_profiles_json(),
        )
        .unwrap()
        .remove(0)
        .segments,
        ..p
    };
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
    core.on_tick(t);
    // the rinse write was lost; the user starts steam at the group head
    core.on_notification(Source::De1State, &[5, 7], 20_000);
    let o = core.on_tick(31_000);
    assert_eq!(state_writes(&o), vec![2, 4]);
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
