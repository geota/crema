//! A substate byte this build doesn't know must not hide a machine state
//! change (#318).

use crate::{CoreOutput, CremaCore, Event, Source};

fn report(core: &mut CremaCore, packet: [u8; 2], at_ms: u64) -> CoreOutput {
    core.on_notification(Source::De1State, &packet, at_ms)
}

fn unknown_bytes(out: &CoreOutput) -> Vec<u8> {
    out.events
        .iter()
        .filter_map(|e| match e {
            Event::UnknownMachineByte { byte } => Some(*byte),
            _ => None,
        })
        .collect()
}

#[test]
fn a_shot_ends_when_idle_arrives_with_an_unknown_substate() {
    let mut core = CremaCore::new();
    report(&mut core, [4, 5], 0);
    let out = report(&mut core, [2, 21], 1_000);
    assert!(core.shot_started.is_none());
    assert!(
        out.events
            .iter()
            .any(|e| matches!(e, Event::ShotCompleted { .. })),
        "{:?}",
        out.events
    );
}

#[test]
fn an_unknown_substate_mid_pour_keeps_the_shot_running() {
    let mut core = CremaCore::new();
    report(&mut core, [4, 5], 0);
    let out = report(&mut core, [4, 21], 1_000);
    assert!(core.shot_started.is_some());
    assert!(
        !out.events.iter().any(|e| matches!(
            e,
            Event::ShotPhaseChanged { .. } | Event::ShotCompleted { .. }
        )),
        "{:?}",
        out.events
    );
}

#[test]
fn each_new_byte_is_reported_once_even_across_a_reset() {
    let mut core = CremaCore::new();
    assert_eq!(unknown_bytes(&report(&mut core, [4, 21], 0)), vec![21]);
    assert!(unknown_bytes(&report(&mut core, [4, 21], 1)).is_empty());
    assert_eq!(unknown_bytes(&report(&mut core, [4, 22], 2)), vec![22]);
    let _ = core.reset();
    assert!(unknown_bytes(&report(&mut core, [4, 21], 3)).is_empty());
}

#[test]
fn a_known_substate_reports_nothing() {
    let mut core = CremaCore::new();
    assert!(unknown_bytes(&report(&mut core, [4, 5], 0)).is_empty());
}

#[test]
fn an_unknown_state_byte_is_a_decode_error_and_the_last_state_holds() {
    let mut core = CremaCore::new();
    report(&mut core, [4, 5], 0);
    let out = report(&mut core, [99, 5], 1_000);
    assert!(
        out.events
            .iter()
            .any(|e| matches!(e, Event::DecodeError { .. }))
    );
    assert!(core.shot_started.is_some());
    assert_eq!(
        core.last_state.map(|s| s.state),
        Some(de1_protocol::MachineState::Espresso)
    );
}
