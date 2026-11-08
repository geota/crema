//! Scale trust, the volume rule, the step-weight gate, `Blind` and its
//! backstops (`.scratch/machine-control/DESIGN.md` §3.4, §3.5; PR 5).
#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_precision_loss,
    clippy::cast_sign_loss
)]

use de1_domain::HotWaterMode;
use de1_protocol::MachineState;

use crate::*;

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

/// A shot sample at 2.5 ml/s.
fn sample(t: u16) -> [u8; 19] {
    let mut s = [
        0x12, 0x34, 0x90, 0x00, 0x28, 0x00, 0x5C, 0x80, 0x00, 0x58, 0x00, 0x5A, 0x00, 0x5D, 0x00,
        0x60, 0x40, 0x03, 0x96,
    ];
    s[0..2].copy_from_slice(&t.to_be_bytes());
    s
}

fn stop_reasons(o: &CoreOutput) -> Vec<StopReason> {
    o.events
        .iter()
        .filter_map(|e| match e {
            Event::StopTriggered { reason } => Some(*reason),
            _ => None,
        })
        .collect()
}

/// A reporting scale, a 36 g target, and a shot seen from Idle. The tare at
/// first flow lands at 300 ms.
fn tared_shot() -> CremaCore {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_profile_target_weight(Some(36.0));
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 0);
    core.on_notification(Source::De1State, &[2, 0], 10);
    core.on_notification(Source::De1State, &[4, 5], 200);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 300);
    assert_eq!(core.stop_targets_projection().1.weight, Some(36.0));
    core
}

/// Pour samples every 100 ms from `from_ms` to `to_ms`, with an optional
/// scale reading at `grams(t)`; returns each stop with the pour at the time.
fn pour(
    core: &mut CremaCore,
    from_ms: u64,
    to_ms: u64,
    grams: impl Fn(u64) -> Option<f32>,
) -> Vec<(StopReason, f32)> {
    let mut stops = vec![];
    for t in (from_ms..to_ms).step_by(100) {
        let o = core.on_notification(Source::De1ShotSample, &sample((t / 8) as u16), t);
        let ml = core.volume_integrator.pour_ml();
        stops.extend(stop_reasons(&o).into_iter().map(|r| (r, ml)));
        if let Some(g) = grams(t) {
            let o = core.on_notification(Source::ScaleWeight, &bookoo(g), t + 1);
            stops.extend(stop_reasons(&o).into_iter().map(|r| (r, ml)));
        }
    }
    stops
}

/// §3.5: a scale lost before flow reached the cup leaves the shot `Blind`. No
/// limit, no estimate; once the link is gone the backstop stops the pour at
/// `max(limit, 60 + 1.5 × target)`, 114 ml at 36 g, reported as volume.
#[test]
fn a_scale_lost_before_the_cup_had_weight_stops_at_the_backstop() {
    let mut core = tared_shot();
    core.set_profile_volume_limit(Some(36.0));
    core.disconnect_scale();
    assert_eq!(core.weight_stop_blocked_by(), Some("scale-lost"));
    let stops = pour(&mut core, 400, 60_000, |_| None);
    let (reason, ml) = stops.first().copied().expect("a backstop stop");
    assert_eq!(reason, StopReason::Volume);
    assert!((114.0..117.0).contains(&ml), "stopped at {ml} ml");
}

/// I19: the backstop never fires while the link is only stale. A scale that
/// stops reporting is `Blind`, silently, until it's dead at 6 s.
#[test]
fn a_stale_scale_arms_no_backstop_and_shows_nothing() {
    let mut core = tared_shot();
    let stops = pour(&mut core, 400, 6_000, |_| None);
    assert!(stops.is_empty(), "{stops:?}");
    assert_eq!(core.stop_targets_projection().1.volume, None);
    assert_eq!(core.weight_stop_blocked_by(), None, "a blip shows nothing");
    // Dead: the backstop is armed and the user is asked for a hand.
    core.on_tick(6_400);
    assert_eq!(core.stop_targets_projection().1.volume, Some(114.0));
    assert_eq!(core.weight_stop_blocked_by(), Some("scale-lost"));
}

/// A blip mid-shot costs nothing: the reading that returns restores weight.
#[test]
fn a_scale_gap_returns_to_trusted_on_the_next_reading() {
    let mut core = tared_shot();
    core.set_profile_volume_limit(Some(36.0));
    let stops = pour(&mut core, 400, 1_600, |_| None);
    assert!(stops.is_empty());
    assert_eq!(core.stop_targets_projection().1.weight, None);
    core.on_notification(Source::ScaleWeight, &bookoo(2.0), 1_700);
    assert_eq!(core.stop_targets_projection().1.weight, Some(36.0));
    assert_eq!(core.stop_targets_projection().1.volume, None);
}

/// An unseen shot with a weight target and no limit or max time gets the same
/// backstop, counted from attach.
#[test]
fn an_unseen_shot_with_no_limits_stops_at_the_backstop() {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_profile_target_weight(Some(36.0));
    core.on_notification(Source::De1State, &[4, 5], 0);
    assert_eq!(core.stop_targets_projection().1.volume, Some(114.0));
    let stops = pour(&mut core, 100, 60_000, |_| Some(250.0));
    assert_eq!(stops.first().map(|s| s.0), Some(StopReason::Volume));
    // With a limit set, the limit stands instead.
    let mut core = CremaCore::new();
    core.set_profile_target_weight(Some(36.0));
    core.set_profile_volume_limit(Some(40.0));
    core.on_notification(Source::De1State, &[4, 5], 0);
    assert_eq!(core.stop_targets_projection().1.volume, Some(40.0));
}

/// Mutation 9: a scale whose cup was never tared can't take a step's exit,
/// though it's connected and reporting.
#[test]
fn an_untared_cup_skips_no_step() {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_auto_tare(false);
    core.set_profile_target_weight(Some(36.0));
    let mut p = de1_domain::cold_maintenance_profile();
    p.steps[0].weight = Some(3.0);
    let s = p.steps[0].clone();
    p.steps.push(s.clone());
    p.steps.push(s);
    p.steps[1].weight = None;
    p.steps[2].weight = None;
    core.set_active_profile(Some(&p));
    core.on_notification(Source::De1State, &[2, 0], 0);
    core.on_notification(Source::De1State, &[4, 5], 100);
    let mut skips = 0;
    let mut suppressed = false;
    for i in 0..40u64 {
        let t = 200 + i * 100;
        let mut f = sample(100 + i as u16 * 12);
        f[17] = 0;
        core.on_notification(Source::De1ShotSample, &f, t);
        // A heavy reading that never settles: the untared-cup guard trips.
        let g = 80.0 + (i % 2) as f32 * 6.0;
        let o = core.on_notification(Source::ScaleWeight, &bookoo(g), t + 1);
        suppressed |= o
            .events
            .iter()
            .any(|e| matches!(e, Event::SawSuppressedUntaredCup { .. }));
        skips += o
            .commands
            .iter()
            .filter(|c| {
                matches!(c, Command::WriteCharacteristic {
                    target: WriteTarget::De1RequestedState,
                    data,
                } if data == &[MachineState::SkipToNext as u8])
            })
            .count();
    }
    assert!(suppressed, "the guard tripped");
    assert_eq!(skips, 0);
    assert_eq!(core.weight_stop_blocked_by(), Some("untared-cup"));
}

/// Staleness is noticed on DE1 samples too, with no tick.
#[test]
fn a_stale_scale_is_noticed_on_a_sample() {
    let mut core = tared_shot();
    let o = core.on_notification(Source::De1ShotSample, &sample(1), 1_400);
    assert!(o.events.contains(&Event::ScaleStale));
}

/// Require-scale counts a silent scale as none.
#[test]
fn require_scale_refuses_a_scale_that_isnt_reporting() {
    let mut core = CremaCore::new_with_model(1);
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_start_settings(false, true, false);
    core.on_notification(Source::De1State, &[2, 0], 1_000);
    let mut p = de1_domain::blank_profile(&de1_domain::BrewDefaults::default());
    p.yield_out = 36.0;
    let r = core.start_shot(
        &p,
        &de1_domain::BrewOverrides::default(),
        std::time::Duration::from_millis(2_000),
    );
    assert_eq!(r.refusal, Some(StartRefusal::ScaleRequired));
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 2_100);
    let r = core.start_shot(
        &p,
        &de1_domain::BrewOverrides::default(),
        std::time::Duration::from_millis(2_200),
    );
    assert!(r.accepted);
}

/// The core gives the note with the reason, so both shells say the same.
#[test]
fn the_projection_carries_the_blocked_note() {
    let mut core = tared_shot();
    core.disconnect_scale();
    let json: serde_json::Value =
        serde_json::from_str(&core.stop_targets_projection_json()).expect("json");
    assert_eq!(json["weightBlocked"], "scale-lost");
    assert_eq!(
        json["weightBlockedNote"],
        "Scale stopped reporting. Stop this shot by hand."
    );
}

fn hot_water_core() -> CremaCore {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_auto_tare(false);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 0);
    core.connect_sweep(&de1_domain::ConnectSweepSettings::default(), 1);
    core.set_steam_hotwater_targets(&SteamHotWaterTargets {
        steam_temp_c: 150.0,
        steam_timeout_s: 60.0,
        hot_water_temp_c: 85.0,
        hot_water: de1_domain::HotWaterDose {
            mode: HotWaterMode::Volume,
            time_s: 30.0,
            time_no_limit: false,
            volume_ml: 120.0,
            ratio: 3.0,
        },
    });
    core
}

/// §3.5: hot water's `V` leg stands in only when the packet on the machine is
/// the 250 ml backstop. When the packet holds `V`, the DE1 owns the stop and
/// an app leg would race it.
#[test]
fn the_hot_water_volume_leg_stands_down_when_the_packet_holds_v() {
    let mut core = hot_water_core();
    core.disconnect_scale();
    // The packet is rewritten to V with no scale, before the pour.
    core.on_notification(Source::De1State, &[2, 0], 10);
    core.on_tick(20);
    assert_eq!(
        core.steam_hotwater_settings
            .as_ref()
            .map(|p| p.hot_water_volume_ml),
        Some(120.0)
    );
    core.on_notification(Source::De1State, &[6, 5], 100);
    let stops = pour(&mut core, 200, 80_000, |_| None);
    assert!(stops.is_empty(), "{stops:?}");
}

/// A frame with no valid weight (a Hiroia stuck in ounces) keeps the link
/// alive, so it's never torn down, but it's never fresh, so weight can't stop
/// the shot on it.
#[test]
fn unit_recovery_frames_are_alive_but_never_fresh() {
    let mut core = CremaCore::new();
    core.connect_scale("HIROIA JIMMY", &[]);
    core.set_profile_target_weight(Some(36.0));
    let mut ounces = [0u8; 16];
    ounces[0] = 0x09;
    let mut events = vec![];
    for t in (0..10_000u64).step_by(100) {
        events.extend(core.on_notification(Source::ScaleWeight, &ounces, t).events);
        events.extend(core.on_tick(t).events);
    }
    assert!(!core.scale_link().is_fresh());
    assert_eq!(core.stop_targets_projection().1.weight, None);
    assert!(
        !events
            .iter()
            .any(|e| matches!(e, Event::ScaleLinkDead { .. })),
        "alive: no teardown"
    );
}

/// Review of #369: auto-tare off, the packet at 250 ml, and the scale's last
/// reading 2 s old as the pour starts, so no weight target is armed. Fresh
/// readings then make the weight trusted, but with no weight stop armed the
/// `V` leg must stay live: the pour stops at 120 ml, not at 250.
#[test]
fn hot_water_with_no_armed_weight_stop_keeps_its_volume_leg() {
    let mut core = hot_water_core();
    core.on_notification(Source::De1State, &[2, 0], 10);
    assert_eq!(
        core.steam_hotwater_settings
            .as_ref()
            .map(|p| p.hot_water_volume_ml),
        Some(250.0),
        "the backstop packet"
    );
    core.on_notification(Source::De1State, &[6, 5], 2_000);
    assert_eq!(core.hot_water_stop_target_g, None, "stale at the start");
    let stops = pour(&mut core, 2_100, 62_000, |t| {
        Some((t.saturating_sub(2_100)) as f32 * 0.006)
    });
    let (reason, ml) = stops.first().copied().expect("an app stop");
    assert_eq!(reason, StopReason::Volume);
    assert!((120.0..130.0).contains(&ml), "stopped at {ml} ml");
}

/// Review of #369: a Half Decent Scale asleep while water flows isn't
/// dormant: its wake is due at state entry, and a lost one must reach the
/// dead timeout and the backstop.
#[test]
fn a_sleeping_hds_in_a_flowing_shot_reaches_the_dead_timeout() {
    let mut core = CremaCore::new();
    core.connect_scale("Decent Scale", &[]);
    core.set_profile_target_weight(Some(36.0));
    core.on_notification(Source::De1State, &[2, 0], 0);
    core.on_notification(
        Source::ScaleWeight,
        &[0x03, 0x0A, 0x01, 0x01, 0x50, 0x03, 0x1E],
        10,
    );
    core.on_notification(
        Source::ScaleWeight,
        &[0x03, 0x22, 0x00, 0x29, 0x00, 0x00, 0x08],
        20,
    );
    core.on_notification(Source::De1State, &[4, 5], 100);
    // The scale goes to SoftSleep and its wake is lost.
    let _ = core.hds.as_mut().and_then(HdsNegotiation::enter_sleep);
    core.session.as_mut().expect("a session").tared();
    assert_ne!(
        core.scale_link(),
        de1_domain::scale_trust::ScaleLink::Dormant
    );
    core.on_tick(7_000);
    assert!(core.scale_link().is_gone());
    assert_eq!(core.stop_targets_projection().1.volume, Some(114.0));
}
