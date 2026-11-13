//! The stop targets come from the desired profile (`DESIGN.md` §3.10, #314,
//! #318): the yield is the Quick Controls yield or the profile's, the volume
//! limit is the profile's, and the dot turns back on for each new profile.
#![allow(clippy::cast_precision_loss)]

use std::time::Duration;

use de1_domain::{BrewDefaults, BrewOverrides, CremaProfile, HotWaterDose, HotWaterMode};

use crate::scale_trust_tests::{bookoo, sample};
use crate::{CremaCore, Event, Source, SteamHotWaterTargets};

/// A library profile `id` with its own `yield_g` and `volume_ml` limit.
fn profile(id: &str, yield_g: f32, volume_ml: u16) -> CremaProfile {
    CremaProfile {
        id: id.to_owned(),
        yield_out: yield_g,
        max_total_volume_ml: volume_ml,
        ..de1_domain::blank_profile(&BrewDefaults::default())
    }
}

/// Quick Controls with the yield dialled to `g`.
fn dial(g: f32) -> BrewOverrides {
    BrewOverrides {
        yield_g: Some(g),
        ..BrewOverrides::default()
    }
}

/// A core with a reporting scale.
fn with_scale() -> CremaCore {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 0);
    core
}

fn weight(core: &CremaCore) -> Option<f32> {
    core.stop_targets_projection().1.weight
}

/// #314: dial 40 g on a 36 g profile, then Reset the dial, then pick a 30 g
/// profile. The stop follows what the desired profile says each time, with no
/// separate push from the shell.
#[test]
fn the_weight_target_follows_the_desired_profile() {
    let mut core = with_scale();
    let p36 = profile("p36", 36.0, 0);
    core.set_desired_profile(Some(&p36), &dial(40.0), Duration::ZERO);
    assert_eq!(weight(&core), Some(40.0), "the dial wins");
    core.set_desired_profile(Some(&p36), &BrewOverrides::default(), Duration::ZERO);
    assert_eq!(weight(&core), Some(36.0), "Reset: the profile's yield");
    core.set_desired_profile(
        Some(&profile("p30", 30.0, 0)),
        &BrewOverrides::default(),
        Duration::ZERO,
    );
    assert_eq!(weight(&core), Some(30.0), "another profile");
    core.set_desired_profile(None, &BrewOverrides::default(), Duration::ZERO);
    assert_eq!(weight(&core), None, "no profile, no target");
}

/// The volume limit is the desired profile's; 0 is no limit.
#[test]
fn the_volume_limit_is_the_desired_profiles() {
    let mut core = CremaCore::new();
    core.set_desired_profile(
        Some(&profile("p", 36.0, 40)),
        &BrewOverrides::default(),
        Duration::ZERO,
    );
    assert_eq!(core.stop_targets_projection().1.volume, Some(40.0));
    core.set_desired_profile(
        Some(&profile("p", 36.0, 0)),
        &BrewOverrides::default(),
        Duration::ZERO,
    );
    assert_eq!(core.stop_targets_projection().1.volume, None);
}

/// A dial turned mid-shot moves the armed stop at once, not after the
/// upload's 500 ms settle (`DESIGN.md` §3.10).
#[test]
fn a_dial_turned_mid_shot_moves_the_armed_stop_at_once() {
    let mut core = with_scale();
    let p = profile("p", 36.0, 0);
    core.set_desired_profile(Some(&p), &BrewOverrides::default(), Duration::ZERO);
    core.on_notification(Source::De1State, &[2, 0], 10);
    core.on_notification(Source::De1State, &[4, 5], 100);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 150);
    core.on_notification(Source::De1ShotSample, &sample(20), 200);
    let armed = |core: &CremaCore| {
        core.auto_stop
            .as_ref()
            .and_then(|a| a.stop.targets().weight)
    };
    assert_eq!(armed(&core), Some(36.0));
    core.set_desired_profile(Some(&p), &dial(42.0), Duration::from_millis(300));
    assert_eq!(armed(&core), Some(42.0));
}

/// The dot is per profile: a dial change keeps it off, another profile turns
/// it back on. The target yield ignores it.
#[test]
fn another_profile_turns_the_dot_back_on() {
    let mut core = with_scale();
    let p = profile("p", 36.0, 0);
    core.set_desired_profile(Some(&p), &BrewOverrides::default(), Duration::ZERO);
    core.set_weight_target_disabled(true);
    core.set_desired_profile(Some(&p), &dial(38.0), Duration::ZERO);
    assert_eq!(weight(&core), None, "the same profile keeps the dot off");
    assert_eq!(
        core.effective_target_weight(),
        Some(38.0),
        "the yield ignores the dot"
    );
    core.set_desired_profile(
        Some(&profile("q", 30.0, 0)),
        &BrewOverrides::default(),
        Duration::ZERO,
    );
    assert_eq!(weight(&core), Some(30.0), "a new profile turns it on");
}

/// The hot-water ratio's basis is the desired yield too.
#[test]
fn the_hot_water_ratio_counts_from_the_desired_yield() {
    let mut core = CremaCore::new();
    let _ = core.set_steam_hotwater_targets(&SteamHotWaterTargets {
        steam_temp_c: 148.0,
        steam_timeout_s: 12.0,
        hot_water_temp_c: 80.0,
        hot_water: HotWaterDose {
            mode: HotWaterMode::Ratio,
            ratio: 3.0,
            ..HotWaterDose::default()
        },
    });
    let ml = |core: &CremaCore| core.hot_water_target().and_then(|t| t.volume_ml);
    core.set_desired_profile(Some(&profile("p", 30.0, 0)), &dial(40.0), Duration::ZERO);
    assert_eq!(ml(&core), Some(120.0));
    core.set_desired_profile(
        Some(&profile("p", 30.0, 0)),
        &BrewOverrides::default(),
        Duration::ZERO,
    );
    assert_eq!(ml(&core), Some(90.0));
}

/// A mirror holds a desired profile it never uploads: once the settle has
/// passed, a tick asks for no wake in the past, which a shell's timer would
/// answer at once, forever.
#[test]
fn a_settle_that_cannot_upload_asks_for_no_past_wake() {
    let mut core = CremaCore::new();
    core.set_read_only(true);
    core.link.settled = true;
    core.on_notification(Source::De1State, &[2, 0], 0);
    core.set_desired_profile(
        Some(&profile("p", 36.0, 0)),
        &BrewOverrides::default(),
        Duration::from_millis(10),
    );
    for now in [600u64, 601, 602, 1_600] {
        let past = core
            .on_tick(now)
            .events
            .iter()
            .any(|e| matches!(e, Event::WakeAt { at_ms } if *at_ms <= now as f64));
        assert!(!past, "a wake at or before {now} ms");
    }
}
