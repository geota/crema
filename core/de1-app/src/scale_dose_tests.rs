//! A Bookoo Ultra takes the shot's dose for its ratio mode (`0x0D`, Ultra beta
//! V3.2.4+ / release V4.0.0+; ratio = liquid / powder): sent when the firmware
//! becomes known and on every change of the dose, once per value on a link,
//! and again after a reconnect. Never to a Mini or unknown firmware.

use std::time::Duration;

use de1_domain::{BrewDefaults, BrewOverrides, CremaProfile};
use de1_scale::bookoo;

use crate::{Command, CoreOutput, CremaCore, Source};

/// A library profile whose own dose is `dose` g.
fn profile(dose: f32) -> CremaProfile {
    CremaProfile {
        id: "p".to_owned(),
        dose,
        ..de1_domain::blank_profile(&BrewDefaults::default())
    }
}

/// Quick Controls with the dose dialled to `g`.
fn dial(g: f32) -> BrewOverrides {
    BrewOverrides {
        dose: Some(g),
        ..BrewOverrides::default()
    }
}

/// A Bookoo `03 0c` serial reply reporting firmware `fw`.
fn serial(fw: u16) -> Vec<u8> {
    let mut frame = vec![0u8; 20];
    frame[..2].copy_from_slice(&[0x03, 0x0C]);
    frame[2..4].copy_from_slice(&fw.to_be_bytes());
    frame[4..18].copy_from_slice(b"SN2400d66a89e7");
    frame[19] = frame[..19].iter().fold(0, |a, &b| a ^ b);
    frame
}

/// Every `0x0D` powder-weight write in `out`.
fn dose_writes(out: &CoreOutput) -> Vec<Vec<u8>> {
    out.commands
        .iter()
        .filter_map(|c| match c {
            Command::WriteScale { data } if data.starts_with(&[0x03, 0x0A, 0x0D]) => {
                Some(data.clone())
            }
            _ => None,
        })
        .collect()
}

fn frame(tenths: u16) -> Vec<Vec<u8>> {
    vec![bookoo::set_powder_weight(tenths).to_vec()]
}

fn desire(core: &mut CremaCore, p: &CremaProfile, qc: &BrewOverrides) -> CoreOutput {
    core.set_desired_profile(Some(p), qc, Duration::ZERO)
}

/// A core with a Bookoo that has reported firmware `fw`.
fn bookoo_on(fw: u16) -> CremaCore {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.on_notification(Source::ScaleCommand, &serial(fw), 500);
    core
}

#[test]
fn an_ultra_reporting_v3_2_4_or_later_is_sent_the_dose_it_has() {
    for fw in [324, 400, 403] {
        let mut core = CremaCore::new();
        assert!(
            dose_writes(&desire(
                &mut core,
                &profile(18.0),
                &BrewOverrides::default()
            ))
            .is_empty()
        );
        core.connect_scale("BOOKOO_SC", &[]);
        let out = core.on_notification(Source::ScaleCommand, &serial(fw), 500);
        assert_eq!(dose_writes(&out), frame(180), "fw {fw}");
    }
}

#[test]
fn a_mini_an_older_ultra_and_unknown_firmware_are_never_sent_the_dose() {
    let mut unknown = CremaCore::new();
    unknown.connect_scale("BOOKOO_SC", &[]);
    assert!(dose_writes(&desire(&mut unknown, &profile(18.0), &dial(20.0))).is_empty());
    for fw in [141, 143, 312, 323] {
        let mut core = CremaCore::new();
        desire(&mut core, &profile(18.0), &BrewOverrides::default());
        core.connect_scale("BOOKOO_SC", &[]);
        let out = core.on_notification(Source::ScaleCommand, &serial(fw), 500);
        assert!(dose_writes(&out).is_empty(), "fw {fw}");
        assert!(dose_writes(&desire(&mut core, &profile(18.0), &dial(20.0))).is_empty());
    }
}

#[test]
fn the_dose_follows_the_dial_and_the_profile_and_an_unchanged_one_is_not_resent() {
    let mut core = bookoo_on(403);
    let p18 = profile(18.0);
    let none = BrewOverrides::default();
    assert_eq!(dose_writes(&desire(&mut core, &p18, &none)), frame(180));
    assert!(
        dose_writes(&desire(&mut core, &p18, &none)).is_empty(),
        "unchanged"
    );
    assert_eq!(
        dose_writes(&desire(&mut core, &p18, &dial(20.5))),
        frame(205)
    );
    assert!(dose_writes(&desire(&mut core, &p18, &dial(20.5))).is_empty());
    // The dial's Reset: back to the profile's dose.
    assert_eq!(dose_writes(&desire(&mut core, &p18, &none)), frame(180));
    // No dose at all, or one the scale can't take: nothing.
    assert!(dose_writes(&desire(&mut core, &profile(0.0), &none)).is_empty());
    assert!(dose_writes(&desire(&mut core, &profile(1500.0), &none)).is_empty());
    assert!(dose_writes(&core.set_desired_profile(None, &none, Duration::ZERO)).is_empty());
    // The scale still holds 18.0 g; the same dose again is not resent, nor on
    // a repeated serial reply.
    assert!(dose_writes(&desire(&mut core, &p18, &none)).is_empty());
    let out = core.on_notification(Source::ScaleCommand, &serial(403), 1_000);
    assert!(dose_writes(&out).is_empty());
}

#[test]
fn a_reconnected_ultra_is_sent_the_dose_again() {
    let mut core = bookoo_on(403);
    assert_eq!(
        dose_writes(&desire(
            &mut core,
            &profile(18.0),
            &BrewOverrides::default()
        )),
        frame(180)
    );
    core.disconnect_scale();
    core.connect_scale("BOOKOO_SC", &[]);
    let out = core.on_notification(Source::ScaleCommand, &serial(403), 2_000);
    assert_eq!(dose_writes(&out), frame(180));
}

/// A pick of another profile mid-shot leaves the running shot's dose on the
/// scale; the pick's dose goes out when the shot ends.
#[test]
fn a_dose_picked_mid_shot_goes_out_when_the_shot_ends() {
    use crate::scale_trust_tests::{bookoo, sample};
    let mut core = bookoo_on(403);
    let none = BrewOverrides::default();
    let p18 = profile(18.0);
    let p20 = CremaProfile {
        id: "p20".to_owned(),
        ..profile(20.0)
    };
    assert_eq!(dose_writes(&desire(&mut core, &p18, &none)), frame(180));
    core.on_notification(Source::De1State, &[2, 0], 10);
    core.on_notification(Source::De1State, &[4, 5], 100);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 150);
    core.on_notification(Source::De1ShotSample, &sample(20), 200);
    let out = core.set_desired_profile(Some(&p20), &none, Duration::from_millis(300));
    assert!(
        dose_writes(&out).is_empty(),
        "the running shot keeps its dose"
    );
    let mut ended = dose_writes(&core.on_notification(Source::De1State, &[2, 0], 5_000));
    ended.extend(dose_writes(&core.on_tick(9_000)));
    assert_eq!(ended, frame(200));
}
