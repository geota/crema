//! The request gate, `Command::Stop` and `CancelStop`, and `Start.from`
//! (`.scratch/machine-control/DESIGN.md` §3.6, §3.7; PR 4).

use std::time::Duration;

use de1_domain::{BrewDefaults, BrewOverrides, ConnectSweepSettings, CremaProfile, blank_profile};
use de1_protocol::{MachineState, requested_state};

use crate::machine_requests::Initiator;
use crate::{Command, CoreOutput, CremaCore, Event, Source, StartEnd, WriteTarget};

const IDLE: [u8; 2] = [2, 0];
const POURING: [u8; 2] = [4, 5];
const HOT_WATER: [u8; 2] = [6, 5];
const STEAM: [u8; 2] = [5, 7];

/// The plain requested-state writes in `out`, as bytes. A `Command::Stop` is
/// not one of them.
fn plain_writes(out: &CoreOutput) -> Vec<u8> {
    out.commands
        .iter()
        .filter_map(|c| match c {
            Command::WriteCharacteristic {
                target: WriteTarget::De1RequestedState,
                data,
            } => data.first().copied(),
            _ => None,
        })
        .collect()
}

fn stops(out: &CoreOutput) -> usize {
    out.commands
        .iter()
        .filter(|c| matches!(c, Command::Stop))
        .count()
}

fn cancels(out: &CoreOutput) -> usize {
    out.commands
        .iter()
        .filter(|c| matches!(c, Command::CancelStop))
        .count()
}

fn byte(state: MachineState) -> u8 {
    requested_state(state)
}

/// A connected, idle, settled core with stop-on-weight off.
fn connected() -> CremaCore {
    let mut core = CremaCore::new_with_model(1);
    core.set_stop_on_weight(false);
    core.on_notification(Source::De1State, &IDLE, 1_000);
    core.connect_sweep(&ConnectSweepSettings::default(), 1_000);
    core
}

fn report(core: &mut CremaCore, packet: [u8; 2], at_ms: u64) -> CoreOutput {
    core.on_notification(Source::De1State, &packet, at_ms)
}

/// The shells' Stop byte is the core's Idle request: both hard-code `0x02`.
#[test]
fn a_stop_writes_the_idle_byte() {
    assert_eq!(byte(MachineState::Idle), 0x02);
}

/// A Stop tap during a flow is `Command::Stop`, the urgent lane; with no flow
/// it's a plain Idle write.
#[test]
fn the_users_stop_is_a_stop_command_only_while_a_flow_runs() {
    let mut core = connected();
    report(&mut core, POURING, 2_000);
    let out = core.request_machine_state(MachineState::Idle);
    assert_eq!(stops(&out), 1);
    assert!(plain_writes(&out).is_empty());

    let mut core = connected();
    let out = core.request_machine_state(MachineState::Idle);
    assert_eq!(stops(&out), 0);
    assert_eq!(plain_writes(&out), [byte(MachineState::Idle)]);
}

/// The core's own stop and its retries are `Command::Stop` too, hot water
/// included.
#[test]
fn an_auto_stop_and_its_retry_are_stop_commands() {
    let mut core = connected();
    core.set_max_shot_duration(Some(3.0));
    report(&mut core, POURING, 2_000);
    let sample = |t: u16| {
        let mut s = [0u8; 19];
        s[0..2].copy_from_slice(&t.to_be_bytes());
        s
    };
    core.on_notification(Source::De1ShotSample, &sample(0), 2_100);
    let out = core.on_notification(Source::De1ShotSample, &sample(100), 5_200);
    assert_eq!(stops(&out), 1, "{:?}", out.commands);
    assert!(plain_writes(&out).is_empty());
    let out = core.on_notification(Source::De1ShotSample, &sample(200), 6_300);
    assert!(
        out.events
            .iter()
            .any(|e| matches!(e, Event::StopRetried { .. }))
    );
    assert_eq!(stops(&out), 1);
}

/// The Idle bounce before a flow is a plain write, never the urgent stop.
#[test]
fn the_idle_before_a_flow_is_a_plain_write() {
    let mut core = connected();
    report(&mut core, STEAM, 2_000);
    let out = core.request_machine_state(MachineState::HotWater);
    assert_eq!(stops(&out), 0);
    assert_eq!(
        plain_writes(&out),
        [byte(MachineState::Idle), byte(MachineState::HotWater)]
    );
}

/// The gate's rows for the core: another flow is never bounced, Sleep never
/// cuts a session, and a maintenance cycle needs Idle or Sleep.
#[test]
fn the_core_alone_never_bounces_a_flow_or_sleeps_a_session() {
    let mut core = connected();
    report(&mut core, STEAM, 2_000);
    let mut out = CoreOutput::default();
    for state in [
        MachineState::HotWaterRinse,
        MachineState::Espresso,
        MachineState::Sleep,
        MachineState::Descale,
    ] {
        assert!(
            !core.request_state(state, Initiator::Core, &mut out),
            "{state:?}"
        );
    }
    assert!(out.commands.is_empty(), "{:?}", out.commands);
    // The user may do each of the first three (the power button for Sleep).
    for state in [
        MachineState::HotWaterRinse,
        MachineState::Espresso,
        MachineState::Sleep,
    ] {
        assert!(
            core.request_state(state, Initiator::User, &mut out),
            "{state:?}"
        );
    }
    // A start may bounce only its own flush or the flow at the tap.
    assert!(!core.request_state(
        MachineState::Espresso,
        Initiator::Start,
        &mut CoreOutput::default()
    ));
    // From Idle the core may.
    let mut core = connected();
    let mut out = CoreOutput::default();
    assert!(core.request_state(MachineState::HotWaterRinse, Initiator::Core, &mut out));
    assert!(core.request_state(MachineState::Sleep, Initiator::Core, &mut out));
    assert!(core.request_state(MachineState::Descale, Initiator::Core, &mut out));
}

/// Mutation 14: a mirror sends nothing that drives the machine, even for its
/// user's tap.
#[test]
fn a_mirror_sends_no_machine_state_request() {
    let mut core = connected();
    core.set_read_only(true);
    report(&mut core, POURING, 2_000);
    for state in [MachineState::Idle, MachineState::Steam, MachineState::Sleep] {
        let out = core.request_machine_state(state);
        assert!(out.commands.is_empty(), "{state:?}: {:?}", out.commands);
    }
}

/// A user's request held by the guard goes out as the user's: tapped Steam
/// while hot water pours and a profile uploads, it bounces the pour once the
/// guard clears, as an unheld tap would.
#[test]
fn a_users_held_request_goes_out_as_tapped() {
    let mut core = connected();
    let upload = core
        .upload_profile(
            &de1_domain::to_wire(&blank_profile(&BrewDefaults::default())),
            Duration::from_millis(2_000),
        )
        .expect("an upload");
    report(&mut core, HOT_WATER, 2_100);
    let out = core.request_machine_state(MachineState::Steam);
    assert!(plain_writes(&out).is_empty(), "held during the upload");
    assert!(
        out.events
            .iter()
            .any(|e| matches!(e, Event::MachineRequestHeld { .. }))
    );
    ack(&mut core, &upload, 2_200);
    let out = core.on_tick(3_000);
    assert_eq!(
        plain_writes(&out),
        [byte(MachineState::Idle), byte(MachineState::Steam)]
    );
}

/// A held request whose machine moved on is dropped, and the user hears.
#[test]
fn a_held_request_is_dropped_if_the_machine_moves_on() {
    let mut core = connected();
    let upload = core
        .upload_profile(
            &de1_domain::to_wire(&blank_profile(&BrewDefaults::default())),
            Duration::from_millis(2_000),
        )
        .expect("an upload");
    core.request_machine_state(MachineState::Steam);
    report(&mut core, HOT_WATER, 2_100);
    ack(&mut core, &upload, 2_200);
    let out = core.on_tick(3_000);
    assert!(plain_writes(&out).is_empty());
    assert!(
        out.events
            .iter()
            .any(|e| matches!(e, Event::MachineRequestDropped { .. }))
    );
}

fn profile(yield_out: f32) -> CremaProfile {
    let mut p = blank_profile(&BrewDefaults::default());
    p.yield_out = yield_out;
    p
}

/// Ack every profile write in `writes`.
fn ack(core: &mut CremaCore, writes: &CoreOutput, at_ms: u64) {
    for c in &writes.commands {
        if let Command::WriteCharacteristic { target, data } = c {
            let source = match target {
                WriteTarget::De1ProfileHeader => Source::De1HeaderAck,
                WriteTarget::De1ProfileFrame => Source::De1FrameAck,
                _ => continue,
            };
            core.on_notification(source, data, at_ms);
        }
    }
}

/// A core holding `p` on the DE1, idle again after a shot, so a second start
/// needs no upload.
fn holding(p: &CremaProfile) -> CremaCore {
    let mut core = connected();
    let r = core.start_shot(p, &BrewOverrides::default(), Duration::from_millis(2_000));
    ack(&mut core, &r.output, 2_100);
    core.on_tick(3_000);
    report(&mut core, POURING, 4_000);
    report(&mut core, IDLE, 9_000);
    assert!(core.profile_sync_status().holds_desired());
    core
}

fn ends(out: &CoreOutput) -> Vec<StartEnd> {
    out.events
        .iter()
        .filter_map(|e| match e {
            Event::ShotStartEnded { end, .. } => Some(end.clone()),
            _ => None,
        })
        .collect()
}

/// Mutation 15: Start tapped while hot water pours stops the pour and starts
/// the shot, Idle then Espresso: the user asked.
#[test]
fn a_start_tapped_over_hot_water_bounces_it() {
    let p = profile(36.0);
    let mut core = holding(&p);
    report(&mut core, HOT_WATER, 10_000);
    let r = core.start_shot(&p, &BrewOverrides::default(), Duration::from_millis(11_000));
    assert!(r.accepted);
    assert_eq!(
        plain_writes(&r.output),
        [byte(MachineState::Idle), byte(MachineState::Espresso)]
    );
}

/// A flow begun after the tap ends the start, and the start never bounces it.
#[test]
fn a_flow_begun_after_the_tap_ends_the_start() {
    let p = profile(36.0);
    let mut core = connected();
    let r = core.start_shot(&p, &BrewOverrides::default(), Duration::from_millis(2_000));
    assert!(r.accepted, "syncing");
    let out = report(&mut core, STEAM, 2_050);
    assert_eq!(
        ends(&out),
        [StartEnd::OtherFlowStarted {
            state: MachineState::Steam
        }]
    );
    ack(&mut core, &r.output, 2_100);
    let out = core.on_tick(3_000);
    assert!(plain_writes(&out).is_empty());
    assert!(!core.shot_starting());
}

/// `Start.from` lapses when the machine leaves that flow: the same mode begun
/// again after the tap is a new flow.
#[test]
fn the_flow_at_the_tap_lapses_once_the_machine_leaves_it() {
    let p = profile(36.0);
    let mut core = connected();
    report(&mut core, HOT_WATER, 1_500);
    let r = core.start_shot(&p, &BrewOverrides::default(), Duration::from_millis(2_000));
    assert!(r.accepted, "syncing: the upload waits for the pour");
    report(&mut core, IDLE, 2_500);
    let out = report(&mut core, HOT_WATER, 2_600);
    assert_eq!(
        ends(&out),
        [StartEnd::OtherFlowStarted {
            state: MachineState::HotWater
        }]
    );
}

/// `CancelStop` goes out when a session closes, on link loss and on reset.
#[test]
fn cancel_stop_on_close_link_loss_and_reset() {
    let mut core = connected();
    assert_eq!(cancels(&report(&mut core, POURING, 2_000)), 0, "opening");
    assert_eq!(cancels(&report(&mut core, [4, 6], 3_000)), 0, "same mode");
    assert_eq!(cancels(&report(&mut core, IDLE, 4_000)), 1, "closed");
    assert_eq!(cancels(&report(&mut core, IDLE, 4_100)), 0);
    report(&mut core, POURING, 5_000);
    assert_eq!(cancels(&report(&mut core, STEAM, 6_000)), 1, "another mode");
    assert_eq!(cancels(&core.de1_link_lost()), 1);
    assert_eq!(cancels(&core.reset()), 1);
}

/// §3.7's source rule: outside tests, a requested-state write is built only in
/// the gate, bar the firmware flash's own Sleep (under the write lock) and the
/// UUID map.
#[test]
fn only_the_gate_builds_a_requested_state_write() {
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    let mut found = Vec::new();
    for entry in std::fs::read_dir(&dir).expect("src") {
        let path = entry.expect("entry").path();
        let name = path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or_default()
            .to_owned();
        if !name.ends_with(".rs") || name.ends_with("_tests.rs") {
            continue;
        }
        let text = std::fs::read_to_string(&path).expect("read");
        for (i, line) in non_test_lines(&text) {
            if line.contains("WriteTarget::De1RequestedState")
                && !line.trim_start().starts_with("//")
            {
                found.push(format!("{name}:{i}"));
            }
        }
    }
    let files: Vec<&str> = found
        .iter()
        .map(|f| f.split(':').next().unwrap_or_default())
        .collect();
    assert!(
        files
            .iter()
            .all(|f| ["machine_requests.rs", "firmware_flash.rs", "de1_uuids.rs"].contains(f)),
        "{found:?}"
    );
    assert_eq!(
        files
            .iter()
            .filter(|f| **f == "machine_requests.rs")
            .count(),
        1,
        "{found:?}"
    );
    assert_eq!(
        files.iter().filter(|f| **f == "firmware_flash.rs").count(),
        1,
        "{found:?}"
    );
}

/// `text`'s lines with their numbers, leaving out every top-level
/// `#[cfg(test)]` item.
fn non_test_lines(text: &str) -> Vec<(usize, &str)> {
    let mut kept = Vec::new();
    let mut skipping = false;
    let mut depth = 0usize;
    let mut opened = false;
    for (i, line) in text.lines().enumerate() {
        if !skipping && line == "#[cfg(test)]" {
            skipping = true;
            depth = 0;
            opened = false;
            continue;
        }
        if skipping {
            depth += line.matches('{').count();
            depth = depth.saturating_sub(line.matches('}').count());
            opened |= line.contains('{');
            if (opened && depth == 0) || (!opened && line.trim_end().ends_with(';')) {
                skipping = false;
            }
            continue;
        }
        kept.push((i + 1, line));
    }
    kept
}
