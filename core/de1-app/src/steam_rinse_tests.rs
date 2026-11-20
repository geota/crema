//! A steam rinse this core asked for stays a rinse when the firmware reports
//! it as `Steam` (DE1 build 1359; Decenza `063464fb`): no steam session, no
//! clog check, no post-steam purge. A `Steam` nobody asked for is steam.

use de1_protocol::{MachineState, requested_state};

use crate::{CoreOutput, CremaCore, Event, Source};

const IDLE: [u8; 2] = [2, 0];
const STEAM: [u8; 2] = [5, 7];
const STEAM_RINSE: [u8; 2] = [16, 0];

/// An idle core with the post-steam purge on.
fn idle_core() -> CremaCore {
    let mut core = CremaCore::new_with_model(1);
    core.set_start_settings(false, false, true);
    let _ = core.on_notification(Source::De1State, &IDLE, 1_000);
    core
}

/// Feed the state reports `(packet, at_ms)` and collect every output.
fn run(core: &mut CremaCore, reports: &[([u8; 2], u64)]) -> Vec<Event> {
    let mut events = Vec::new();
    for (packet, at) in reports {
        events.extend(core.on_notification(Source::De1State, packet, *at).events);
    }
    events
}

fn steam_events(events: &[Event]) -> usize {
    events
        .iter()
        .filter(|e| {
            matches!(
                e,
                Event::SteamSessionStarted
                    | Event::SteamSessionCompleted { .. }
                    | Event::SteamClogSuspected { .. }
            )
        })
        .count()
}

fn states(events: &[Event]) -> Vec<MachineState> {
    events
        .iter()
        .filter_map(|e| match e {
            Event::MachineStateChanged { state, .. } => Some(*state),
            _ => None,
        })
        .collect()
}

/// Whether a purge (HotWaterRinse) goes out on the ticks after `from_ms`.
fn purges(core: &mut CremaCore, from_ms: u64) -> bool {
    let rinse = requested_state(MachineState::HotWaterRinse);
    [from_ms + 1_500, from_ms + 3_000, from_ms + 10_000]
        .iter()
        .any(|t| core.on_tick(*t).state_requests().contains(&rinse))
}

fn request_rinse(core: &mut CremaCore) -> CoreOutput {
    let out = core.request_machine_state(MachineState::SteamRinse);
    assert_eq!(
        out.state_requests(),
        vec![requested_state(MachineState::SteamRinse)]
    );
    out
}

/// Build 1359: the request is answered with Steam, then Idle.
#[test]
fn a_rinse_reported_as_steam_is_a_rinse() {
    let mut core = idle_core();
    let _ = request_rinse(&mut core);
    let events = run(&mut core, &[(STEAM, 1_300), (STEAM, 3_000), (IDLE, 6_000)]);
    assert_eq!(steam_events(&events), 0, "{events:?}");
    assert_eq!(
        states(&events),
        vec![MachineState::SteamRinse, MachineState::Idle]
    );
    assert!(
        !purges(&mut core, 6_000),
        "no post-steam purge after a rinse"
    );
}

/// The other order: a preliminary Idle report before the machine moves.
#[test]
fn a_preliminary_idle_keeps_the_rinse() {
    let mut core = idle_core();
    let _ = request_rinse(&mut core);
    let events = run(&mut core, &[([2, 1], 1_100), (STEAM, 1_400), (IDLE, 6_000)]);
    assert_eq!(steam_events(&events), 0, "{events:?}");
    assert!(!purges(&mut core, 6_000));
}

/// Builds that report SteamRinse are unchanged.
#[test]
fn a_rinse_reported_as_a_rinse_is_unchanged() {
    let mut core = idle_core();
    let _ = request_rinse(&mut core);
    let events = run(&mut core, &[(STEAM_RINSE, 1_300), (IDLE, 6_000)]);
    assert_eq!(steam_events(&events), 0);
    assert_eq!(
        states(&events),
        vec![MachineState::SteamRinse, MachineState::Idle]
    );
    assert!(!purges(&mut core, 6_000));
}

/// After the rinse, a group-head steam is steam again, with its purge; and
/// a second rinse is a rinse again.
#[test]
fn steam_after_the_rinse_is_steam_and_a_second_rinse_a_rinse() {
    let mut core = idle_core();
    let _ = request_rinse(&mut core);
    let _ = run(&mut core, &[(STEAM, 1_300), (IDLE, 6_000)]);
    let events = run(&mut core, &[(STEAM, 20_000), (IDLE, 40_000)]);
    assert_eq!(steam_events(&events), 2, "started + completed: {events:?}");
    assert!(purges(&mut core, 40_000), "a real steam session purges");
    let _ = run(&mut core, &[(IDLE, 50_000)]);
    let _ = request_rinse(&mut core);
    let events = run(&mut core, &[(STEAM, 50_300), (IDLE, 55_000)]);
    assert_eq!(steam_events(&events), 0, "{events:?}");
}

/// A Stop mid-rinse: a late Steam still belongs to the rinse until the
/// machine reports Idle.
#[test]
fn a_stop_keeps_a_late_steam_a_rinse() {
    let mut core = idle_core();
    let _ = request_rinse(&mut core);
    let _ = core.request_machine_state(MachineState::Idle);
    let events = run(&mut core, &[(STEAM, 1_300), (IDLE, 1_800)]);
    assert_eq!(steam_events(&events), 0, "{events:?}");
    let events = run(&mut core, &[(STEAM, 2_500), (IDLE, 9_000)]);
    assert_eq!(steam_events(&events), 2, "the Idle ended the rinse");
}

/// A rinse cancelled before the machine reported anything lapses with its
/// window: a group-head Steam minutes later is steam (review of #491).
#[test]
fn a_cancelled_rinse_that_never_started_lapses() {
    let mut core = idle_core();
    let _ = request_rinse(&mut core);
    let _ = core.request_machine_state(MachineState::Idle);
    let events = run(&mut core, &[(STEAM, 120_000), (IDLE, 130_000)]);
    assert_eq!(
        states(&events),
        vec![MachineState::Steam, MachineState::Idle]
    );
    assert_eq!(steam_events(&events), 2, "{events:?}");
}

/// A Stop written while the rinse runs: the rinse's Steam reports stay a
/// rinse until the machine reports Idle, then Steam is steam again.
#[test]
fn a_stop_written_while_the_rinse_runs_keeps_it_until_idle() {
    let mut core = idle_core();
    let _ = request_rinse(&mut core);
    let _ = run(&mut core, &[(STEAM, 1_300)]);
    let _ = core.request_machine_state(MachineState::Idle);
    let events = run(&mut core, &[(STEAM, 20_000), (IDLE, 20_500)]);
    assert_eq!(steam_events(&events), 0, "{events:?}");
    assert_eq!(
        states(&events),
        vec![MachineState::Idle],
        "Steam reads as the same SteamRinse"
    );
    let events = run(&mut core, &[(STEAM, 30_000), (IDLE, 40_000)]);
    assert_eq!(steam_events(&events), 2, "{events:?}");
}

/// A disconnect forgets the rinse: the next link's Steam is steam.
#[test]
fn a_disconnect_forgets_the_rinse() {
    let mut core = idle_core();
    let _ = request_rinse(&mut core);
    let _ = core.de1_link_lost();
    let events = run(&mut core, &[(IDLE, 1_200), (STEAM, 1_300), (IDLE, 8_000)]);
    assert_eq!(steam_events(&events), 2, "{events:?}");
}

/// A request the machine never acted on lapses: a Steam after the window is
/// steam.
#[test]
fn a_rinse_that_never_started_lapses() {
    let mut core = idle_core();
    let _ = request_rinse(&mut core);
    let events = run(&mut core, &[(IDLE, 2_000), (STEAM, 7_000), (IDLE, 12_000)]);
    assert_eq!(steam_events(&events), 2, "{events:?}");
}

/// Another request supersedes the rinse.
#[test]
fn another_request_supersedes_the_rinse() {
    let mut core = idle_core();
    let _ = request_rinse(&mut core);
    let _ = core.request_machine_state(MachineState::Steam);
    let events = run(&mut core, &[(STEAM, 1_300), (IDLE, 8_000)]);
    assert_eq!(steam_events(&events), 2, "{events:?}");
}
