//! The hot-water dose in the core and the packet sync (hot-water modes spec
//! AC-HW2, HW6, HW8b, HW9, HW10b, HW11–HW17, HW29–HW31). The connect-sweep
//! halves live in `sweep.rs`, the rule itself in `de1_domain::hot_water`.

use de1_domain::{HotWaterDose, HotWaterMode};
use de1_protocol::{MachineState, ShotSettings, requested_state};

use crate::{
    Command, CoreOutput, CremaCore, Event, SCALE_STALE_TIMEOUT, Source, SteamHotWaterTargets,
    StopReason, WriteTarget,
};

fn ratio(r: f32) -> HotWaterDose {
    HotWaterDose {
        mode: HotWaterMode::Ratio,
        ratio: r,
        ..HotWaterDose::default()
    }
}

fn volume(ml: f32) -> HotWaterDose {
    HotWaterDose {
        mode: HotWaterMode::Volume,
        volume_ml: ml,
        ..HotWaterDose::default()
    }
}

fn time(s: f32) -> HotWaterDose {
    HotWaterDose {
        mode: HotWaterMode::Time,
        time_s: s,
        ..HotWaterDose::default()
    }
}

fn targets(dose: HotWaterDose) -> SteamHotWaterTargets {
    SteamHotWaterTargets {
        steam_temp_c: 148.0,
        steam_timeout_s: 12.0,
        hot_water_temp_c: 80.0,
        hot_water: dose,
    }
}

/// The DE1 reports a state: `[state, substate]`.
fn state(core: &mut CremaCore, s: u8, sub: u8, now_ms: u64) -> CoreOutput {
    core.on_notification(Source::De1State, &[s, sub], now_ms)
}

/// A connected DE1 after its on-ready sweep, Idle, holding `dose`.
fn connected(dose: HotWaterDose) -> CremaCore {
    let mut core = CremaCore::new();
    core.link.settled = true;
    let _ = state(&mut core, 2, 0, 0);
    let _ = core.set_steam_hotwater_targets(&targets(dose));
    core
}

/// The packets an output writes, decoded.
fn packets(out: &CoreOutput) -> Vec<ShotSettings> {
    out.commands
        .iter()
        .filter_map(|c| match c {
            Command::WriteCharacteristic {
                target: WriteTarget::De1ShotSettings,
                data,
            } => ShotSettings::decode(data).ok(),
            _ => None,
        })
        .collect()
}

fn target_events(out: &CoreOutput) -> Vec<de1_domain::HotWaterTarget> {
    out.events
        .iter()
        .filter_map(|e| match e {
            Event::HotWaterTarget { target } => Some(*target),
            _ => None,
        })
        .collect()
}

fn ml(core: &CremaCore) -> f32 {
    core.hot_water_target()
        .and_then(|t| t.volume_ml)
        .expect("an ml target")
}

/// A Bookoo weight frame of `centigrams`.
fn bookoo(centigrams: u32) -> [u8; 20] {
    let mut packet = [0u8; 20];
    let [_, hi, mid, lo] = centigrams.to_be_bytes();
    packet[..10].copy_from_slice(&[0x03, 0x0B, 0, 0, 0, 0, b'+', hi, mid, lo]);
    packet[19] = packet[..19].iter().fold(0, |a, &b| a ^ b);
    packet
}

fn weight(core: &mut CremaCore, grams: u32, now_ms: u64) -> CoreOutput {
    core.on_notification(Source::ScaleWeight, &bookoo(grams * 100), now_ms)
}

fn stopped_by_weight(out: &CoreOutput) -> bool {
    out.events.contains(&Event::StopTriggered {
        reason: StopReason::Weight,
    })
}

fn tared(out: &CoreOutput) -> bool {
    out.commands
        .iter()
        .any(|c| matches!(c, Command::WriteScale { .. }))
}

/// AC-HW2: the basis is the effective target — the override wins, clearing
/// it falls back to the profile's.
#[test]
fn hot_water_basis_follows_effective_target() {
    let mut core = connected(ratio(3.0));
    core.set_profile_target_weight(Some(36.0));
    assert_eq!(ml(&core), 108.0);
    core.set_shot_target_weight(Some(40.0));
    assert_eq!(ml(&core), 120.0);
    let t = core.hot_water_target().unwrap();
    assert_eq!(t.basis_g, Some(40.0));
    core.set_shot_target_weight(None);
    assert_eq!(ml(&core), 108.0);
}

/// AC-HW2: a shot's actual output never moves the basis.
#[test]
fn hot_water_ignores_last_shot_weight() {
    let mut core = connected(ratio(3.0));
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_profile_target_weight(Some(36.0));
    let _ = state(&mut core, 4, 5, 1_000);
    let _ = weight(&mut core, 42, 20_000);
    let _ = state(&mut core, 2, 0, 30_000);
    let _ = weight(&mut core, 42, 35_000);
    assert_eq!(ml(&core), 108.0);
}

/// AC-HW6: entering HotWater with an operational scale arms the weight
/// stop at the target's ml and tares when auto-tare is on.
#[test]
fn hot_water_weight_stop_from_target() {
    let mut core = connected(ratio(3.0));
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_profile_target_weight(Some(50.0));
    let out = state(&mut core, 6, 5, 1_000);
    assert_eq!(core.hot_water_stop_target_g, Some(150.0));
    assert!(tared(&out));
    // Auto-tare off: the stop arms, no tare.
    let mut core = connected(volume(200.0));
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_auto_tare(false);
    let out = state(&mut core, 6, 5, 1_000);
    assert_eq!(core.hot_water_stop_target_g, Some(200.0));
    assert!(!tared(&out));
}

/// AC-HW8b: a profile load moves a ratio's amount and never the setting; a
/// volume-mode setting doesn't move.
#[test]
fn profile_load_moves_ratio_not_setting() {
    let mut core = connected(ratio(3.0));
    core.set_profile_target_weight(Some(36.0));
    assert_eq!(ml(&core), 108.0);
    core.set_profile_target_weight(Some(50.0));
    assert_eq!(ml(&core), 150.0);
    assert_eq!(
        core.steam_hotwater_targets.as_ref().unwrap().hot_water,
        ratio(3.0)
    );
    let mut core = connected(volume(200.0));
    core.set_profile_target_weight(Some(36.0));
    core.set_profile_target_weight(Some(50.0));
    assert_eq!(ml(&core), 200.0);
    assert_eq!(
        core.steam_hotwater_targets.as_ref().unwrap().hot_water,
        volume(200.0)
    );
}

/// AC-HW9 (blocker B1): with the DE1 dropped, a scale or profile change
/// writes and records nothing.
#[test]
fn no_packet_write_without_settled_link() {
    let mut core = connected(volume(150.0));
    core.connect_scale("BOOKOO_SC", &[]);
    let out = weight(&mut core, 0, 1_000);
    assert_eq!(packets(&out)[0].hot_water_volume_ml, 250.0);
    let _ = core.de1_link_lost();
    assert!(core.shot_settings_written.is_none());
    core.disconnect_scale();
    core.set_profile_target_weight(Some(50.0));
    let mut outs = vec![state(&mut core, 2, 0, 2_000), core.on_tick(3_000)];
    outs.push(core.set_steam_hotwater_targets(&targets(volume(180.0))));
    for out in &outs {
        assert!(packets(out).is_empty());
    }
    assert!(core.shot_settings_written.is_none());
}

/// AC-HW10b: `reset()` keeps the link session, one generation later.
#[test]
fn reset_carries_link_session() {
    let mut core = connected(volume(150.0));
    let before = core.link.generation;
    let _ = core.reset();
    assert!(core.link.generation > before);
    assert!(!core.link.settled);
    let _ = core.reset();
    assert_eq!(core.link.generation, before.wrapping_add(2));
}

/// AC-HW11: a target-yield change while Idle gives exactly one write, on the
/// next notification with no tick in between — or on the next tick.
#[test]
fn packet_follows_target_yield_on_notification() {
    let mut core = connected(ratio(3.0));
    core.set_profile_target_weight(Some(36.0));
    assert_eq!(
        packets(&state(&mut core, 2, 0, 100))[0].hot_water_volume_ml,
        108.0
    );
    core.set_shot_target_weight(Some(50.0));
    let out = core.on_notification(Source::De1WaterLevels, &[0, 0, 0, 0], 200);
    let w = packets(&out);
    assert_eq!(w.len(), 1);
    assert_eq!(w[0].hot_water_volume_ml, 150.0);
    assert!(packets(&state(&mut core, 2, 0, 300)).is_empty());
    // The tick is the backstop.
    core.set_shot_target_weight(Some(40.0));
    let w = packets(&core.on_tick(1_000));
    assert_eq!(w.len(), 1);
    assert_eq!(w[0].hot_water_volume_ml, 120.0);
}

/// AC-HW11: the dedupe compares the encoded bytes, not the dose.
#[test]
fn packet_dedupes_on_encoded_bytes() {
    let mut core = connected(ratio(3.0));
    core.set_profile_target_weight(Some(40.0));
    assert_eq!(packets(&core.on_tick(100)).len(), 1);
    core.set_profile_target_weight(Some(40.0));
    assert!(packets(&core.on_tick(200)).is_empty());
    // A different dose with the same bytes (1:3 of 40 g = 120 ml) writes
    // nothing; a different amount does.
    assert!(packets(&core.set_steam_hotwater_targets(&targets(volume(120.0)))).is_empty());
    assert_eq!(
        packets(&core.set_steam_hotwater_targets(&targets(volume(160.0))))[0].hot_water_volume_ml,
        160.0
    );
}

/// AC-HW12: scale connect, `ScaleStale`, recovery and disconnect while Idle
/// each rewrite the packet, adding or removing the backstop.
#[test]
fn packet_follows_operational_scale() {
    let mut core = connected(volume(150.0));
    core.connect_scale("BOOKOO_SC", &[]);
    let w = packets(&weight(&mut core, 0, 1_000));
    assert_eq!(w.len(), 1, "connect");
    assert_eq!(w[0].hot_water_volume_ml, 250.0);
    let stale_ms = 1_000 + u64::try_from(SCALE_STALE_TIMEOUT.as_millis()).unwrap() + 10;
    let out = core.on_tick(stale_ms);
    assert!(out.events.contains(&Event::ScaleStale));
    let w = packets(&out);
    assert_eq!(w.len(), 1, "stale");
    assert_eq!(w[0].hot_water_volume_ml, 150.0);
    let w = packets(&weight(&mut core, 0, stale_ms + 100));
    assert_eq!(w.len(), 1, "recovery");
    assert_eq!(w[0].hot_water_volume_ml, 250.0);
    core.disconnect_scale();
    let w = packets(&state(&mut core, 2, 0, stale_ms + 200));
    assert_eq!(w.len(), 1, "disconnect");
    assert_eq!(w[0].hot_water_volume_ml, 150.0);
}

/// AC-HW13: inputs and explicit edits in a flow state or an unknown state
/// write nothing; one write follows the first non-flow state. A running
/// pour's weight stop doesn't change.
#[test]
fn packet_deferred_in_flow_and_unknown_states() {
    // Unknown state.
    let mut core = CremaCore::new();
    core.link.settled = true;
    assert!(packets(&core.set_steam_hotwater_targets(&targets(volume(150.0)))).is_empty());
    assert!(packets(&core.on_tick(100)).is_empty());
    assert_eq!(packets(&state(&mut core, 2, 0, 200)).len(), 1);
    // Espresso, Steam, HotWater, HotWaterRinse.
    for flow in [4u8, 5, 6, 15] {
        let mut core = connected(ratio(3.0));
        core.connect_scale("BOOKOO_SC", &[]);
        core.set_profile_target_weight(Some(36.0));
        let _ = weight(&mut core, 0, 50);
        let _ = state(&mut core, flow, 5, 100);
        let armed = core.hot_water_stop_target_g;
        core.set_profile_target_weight(Some(50.0));
        let mut outs = vec![core.on_tick(200), weight(&mut core, 0, 300)];
        outs.push(core.set_steam_hotwater_targets(&targets(ratio(2.0))));
        outs.push(core.set_desired_profile(
            Some(&de1_domain::blank_profile(
                &de1_domain::BrewDefaults::default(),
            )),
            &de1_domain::BrewOverrides::default(),
            std::time::Duration::from_millis(400),
        ));
        for out in &outs {
            assert!(packets(out).is_empty(), "state {flow}");
        }
        assert_eq!(core.hot_water_stop_target_g, armed, "state {flow}");
        let w = packets(&state(&mut core, 2, 0, 1_000));
        assert_eq!(w.len(), 1, "state {flow}");
        assert_eq!(
            w[0].hot_water_volume_ml, 250.0,
            "state {flow}: the backstop"
        );
    }
}

/// AC-HW14: a hot-water request from Espresso with a pending change writes
/// the Idle bounce, the packet, then HotWater; from Idle the packet, then
/// HotWater.
#[test]
fn hot_water_request_orders_bounce_packet_state() {
    let request = |data: &Command| -> Option<u8> {
        match data {
            Command::WriteCharacteristic {
                target: WriteTarget::De1RequestedState,
                data,
            } => Some(data[0]),
            _ => None,
        }
    };
    let order = |out: &CoreOutput| -> Vec<String> {
        out.commands
            .iter()
            .map(|c| match request(c) {
                Some(s) => format!("state {s}"),
                None => "packet".to_owned(),
            })
            .collect()
    };
    let idle = requested_state(MachineState::Idle);
    let hot_water = requested_state(MachineState::HotWater);
    // (Connected with no target yield, 1:3 poured the 150 ml volume value.)
    let mut core = connected(ratio(3.0));
    let _ = state(&mut core, 4, 5, 100);
    core.set_profile_target_weight(Some(40.0));
    let out = core.request_machine_state(MachineState::HotWater);
    assert_eq!(
        order(&out),
        vec![
            format!("state {idle}"),
            "packet".to_owned(),
            format!("state {hot_water}")
        ]
    );
    assert_eq!(packets(&out)[0].hot_water_volume_ml, 120.0);
    let mut core = connected(ratio(3.0));
    core.set_profile_target_weight(Some(40.0));
    let out = core.request_machine_state(MachineState::HotWater);
    assert_eq!(
        order(&out),
        vec!["packet".to_owned(), format!("state {hot_water}")]
    );
    // Nothing pending: just the request.
    let out = core.request_machine_state(MachineState::HotWater);
    assert_eq!(order(&out), vec![format!("state {hot_water}")]);
}

/// AC-HW15: a read-only (remote) core never writes or records a packet.
#[test]
fn remote_never_writes_or_records_packet() {
    let mut core = CremaCore::new();
    core.set_read_only(true);
    core.link.settled = true;
    let mut outs = vec![state(&mut core, 2, 0, 0)];
    outs.push(core.set_steam_hotwater_targets(&targets(ratio(3.0))));
    core.set_profile_target_weight(Some(50.0));
    core.connect_scale("BOOKOO_SC", &[]);
    outs.push(weight(&mut core, 0, 100));
    outs.push(core.on_tick(200));
    outs.push(core.request_machine_state(MachineState::HotWater));
    for out in &outs {
        assert!(packets(out).is_empty());
    }
    assert!(core.shot_settings_written.is_none());
}

/// AC-HW16: a foreign change is restored once per generation; a second is
/// reported and left alone.
#[test]
fn foreign_packet_restored_once() {
    let mut core = connected(volume(150.0));
    // The seed read's reply came first, as at every connect.
    let mine = core.desired_shot_settings().unwrap();
    let _ = core.on_notification(Source::De1ShotSettings, &mine.encode(), 10);
    let foreign = ShotSettings {
        hot_water_volume_ml: 90.0,
        ..mine.clone()
    }
    .encode();
    let out = core.on_notification(Source::De1ShotSettings, &foreign, 20);
    assert!(
        out.events
            .contains(&Event::ShotSettingsForeignChange { restored: true })
    );
    assert_eq!(packets(&out), vec![mine.clone()], "restored at once");
    let out = core.on_notification(Source::De1ShotSettings, &foreign, 30);
    assert!(
        out.events
            .contains(&Event::ShotSettingsForeignChange { restored: false })
    );
    assert!(packets(&out).is_empty());
    assert!(packets(&core.on_tick(1_000)).is_empty(), "no loop");
    // An exact echo of Crema's write is not foreign.
    let mut core = connected(volume(150.0));
    let mine = core.desired_shot_settings().unwrap();
    let _ = core.on_notification(Source::De1ShotSettings, &mine.encode(), 10);
    let out = core.on_notification(Source::De1ShotSettings, &mine.encode(), 20);
    assert!(
        !out.events
            .iter()
            .any(|e| matches!(e, Event::ShotSettingsForeignChange { .. }))
    );
}

/// AC-HW16: the reply to the connect-time seed read, landing after the
/// sweep's write with the old bytes, isn't foreign — a later real foreign
/// change is still restored.
#[test]
fn own_read_reply_is_not_foreign() {
    let mut core = connected(volume(150.0));
    let _ = core.de1_link_lost();
    let _ = state(&mut core, 2, 0, 100);
    let sweep = core.connect_sweep(
        &de1_domain::ConnectSweepSettings {
            hot_water: volume(150.0),
            ..de1_domain::ConnectSweepSettings::default()
        },
        200,
    );
    let mine = packets(&sweep).pop().expect("the sweep writes");
    let old = ShotSettings {
        steam_temp_c: 140.0,
        hot_water_volume_ml: 60.0,
        ..mine.clone()
    }
    .encode();
    let out = core.on_notification(Source::De1ShotSettings, &old, 300);
    assert!(
        !out.events
            .iter()
            .any(|e| matches!(e, Event::ShotSettingsForeignChange { .. }))
    );
    assert!(packets(&out).is_empty());
    let out = core.on_notification(Source::De1ShotSettings, &old, 400);
    assert!(
        out.events
            .contains(&Event::ShotSettingsForeignChange { restored: true })
    );
    assert_eq!(packets(&out), vec![mine]);
}

/// AC-HW17: `HotWaterTarget` comes exactly when the target changes, and its
/// wire fields and stop equal the packet written and the stop armed.
#[test]
fn hot_water_target_event_matches_packet() {
    let mut core = CremaCore::new();
    core.link.settled = true;
    let _ = state(&mut core, 2, 0, 0);
    core.set_profile_target_weight(Some(50.0));
    let out = core.set_steam_hotwater_targets(&targets(ratio(3.0)));
    let events = target_events(&out);
    assert_eq!(events.len(), 1);
    let w = packets(&out);
    assert_eq!(events[0].wire_volume_ml, w[0].hot_water_volume_ml);
    assert_eq!(events[0].wire_timeout_s, w[0].hot_water_timeout_s);
    assert!(target_events(&core.on_tick(100)).is_empty(), "unchanged");
    core.connect_scale("BOOKOO_SC", &[]);
    let out = weight(&mut core, 0, 200);
    let events = target_events(&out);
    assert_eq!(events.len(), 1);
    let w = packets(&out);
    assert_eq!(events[0].wire_volume_ml, w[0].hot_water_volume_ml);
    assert_eq!(events[0].stop_weight_g, Some(150.0));
    let _ = state(&mut core, 6, 5, 300);
    assert_eq!(core.hot_water_stop_target_g, events[0].stop_weight_g);
    assert_eq!(
        core.hot_water_target_json(),
        serde_json::to_string(&events[0]).unwrap()
    );
    assert_eq!(CremaCore::new().hot_water_target_json(), "null");
}

/// AC-HW29: a pour started at the group head — a bare state notification —
/// gets the app's weight stop, with the tare.
#[test]
fn ghc_hot_water_ratio_stops_at_target_weight() {
    let mut core = connected(ratio(3.0));
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_profile_target_weight(Some(50.0));
    let out = state(&mut core, 6, 5, 0);
    assert!(tared(&out), "auto-tare on the group-head start");
    assert_eq!(core.hot_water_stop_target_g, Some(150.0));
    assert!(!stopped_by_weight(&weight(&mut core, 100, 2_000)));
    // 149 g at a brisk rate: the sensor-lag lead crosses 150.
    let out = weight(&mut core, 149, 4_000);
    assert!(stopped_by_weight(&out));
}

/// AC-HW29: time mode arms no weight stop, whoever starts it.
#[test]
fn ghc_hot_water_time_mode_arms_nothing() {
    let mut core = connected(time(45.0));
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_profile_target_weight(Some(50.0));
    let _ = state(&mut core, 6, 5, 0);
    assert_eq!(core.hot_water_stop_target_g, None);
    assert!(!stopped_by_weight(&weight(&mut core, 300, 2_000)));
}

/// AC-HW30: the dose survives a reset, so a group-head pour after one and
/// before the sweep still arms its weight stop.
#[test]
fn hot_water_dose_survives_reset_for_ghc_pour() {
    let mut core = connected(ratio(3.0));
    core.set_profile_target_weight(Some(50.0));
    core.connect_scale("BOOKOO_SC", &[]);
    let _ = core.reset();
    core.connect_scale("BOOKOO_SC", &[]);
    let _ = state(&mut core, 6, 5, 1_000);
    assert_eq!(core.hot_water_stop_target_g, Some(150.0));
}

/// AC-HW31: an espresso shot started at the group head arms the same rules
/// as one Crema starts — the auto-tare, stop-at-weight, max time and the
/// volume limit — before and after a reset.
#[test]
fn ghc_espresso_arms_same_rules_as_app_start() {
    let setup = || {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        core.set_profile_target_weight(Some(36.0));
        core.set_max_shot_duration(Some(80.0));
        core.set_profile_volume_limit(Some(60.0));
        core.set_volume_stop_with_scale(true);
        let _ = state(&mut core, 2, 0, 0);
        core
    };
    let start = |core: &mut CremaCore, app: bool| -> (bool, crate::StopTargets) {
        let mut out = CoreOutput::default();
        if app {
            out = core.request_machine_state(MachineState::Espresso);
        }
        let started = state(core, 4, 5, 1_000);
        out.commands.extend(started.commands);
        let targets = core
            .auto_stop
            .as_ref()
            .expect("an auto-stop is armed")
            .targets();
        (tared(&out), targets)
    };
    let (app_tare, app_targets) = start(&mut setup(), true);
    let (ghc_tare, ghc_targets) = start(&mut setup(), false);
    assert!(app_tare && ghc_tare);
    assert_eq!(ghc_targets, app_targets);
    assert_eq!(ghc_targets.weight, Some(36.0));
    assert_eq!(ghc_targets.max_time, Some(80.0));
    assert_eq!(ghc_targets.volume, Some(60.0));
    let mut after_reset = setup();
    let _ = after_reset.reset();
    let _ = state(&mut after_reset, 2, 0, 500);
    let (reset_tare, reset_targets) = start(&mut after_reset, false);
    assert!(reset_tare);
    assert_eq!(reset_targets, app_targets);
}
