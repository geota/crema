//! Maintenance requests (Descale / Clean / AirPurge) on firmware that drops
//! them while cold, and the descale progress schedule.
//!
//! Two pure halves, ported from Decenza `b1ceab8c` (`de1device.cpp`), which the
//! orchestrator (`de1-app`) drives sans-IO:
//!
//! 1. **The cold-maintenance gate.** DE1 firmware below build
//!    [`COLD_MAINTENANCE_MIN_FIRMWARE_BUILD`] silently DROPS a Descale, Clean
//!    or AirPurge request on a GHC machine while it is still heating — the
//!    button does nothing and reports nothing. The workaround (de1app
//!    `de1_send_pre_maintenance_profile` / `onestep_cold`, decaid
//!    `_prepareColdMaintenanceWorkaround`, Decenza
//!    `applyColdMaintenanceWorkaround`) loads a one-frame profile whose goal is
//!    1 °C with a 0 °C tank target, so the machine stops preheating, then sends
//!    the state. de1app and decaid wait a fixed second; Decenza waits for the
//!    machine to report it has left preheat — an event, not a timer, so a slow
//!    machine is not raced. Crema follows Decenza.
//!
//! 2. **The descale schedule.** The DE1 runs each descale step for a FIXED
//!    time — Decenza measured it on firmware 1358 over two full descales that
//!    agreed to a tenth of a second (720.1 s / 720.2 s):
//!
//!    | step              | seconds |
//!    |-------------------|---------|
//!    | `DescaleInit`     | 30      |
//!    | `DescaleFillGroup`| 30      |
//!    | `DescaleReturn`   | 120     |
//!    | `DescaleGroup`    | 120     |
//!    | `DescaleSteam`    | 420     |
//!
//!    So descale progress is a schedule resynced at every substate boundary,
//!    not an estimate. [`DescaleTracker`] mirrors Decenza's
//!    `updateDescaleProgress`: progress is capped below 1.0 so 100 % only
//!    shows once the machine actually leaves Descale.

use std::time::Duration;

use de1_protocol::{MachineState, StateInfo, SubState};
use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::profile::{BeverageType, Profile, ProfileStep, Pump, TempSensor, Transition};

/// First DE1 firmware build that honours a cold maintenance request (Decenza
/// `kColdMaintenanceMinFirmwareBuild`, decaid
/// `_kColdMaintenancePromotionMinFwBuild`). Below it the firmware drops
/// Descale / Clean / AirPurge while the machine is heating.
pub const COLD_MAINTENANCE_MIN_FIRMWARE_BUILD: u32 = 1356;

/// Title of the throwaway profile the workaround loads.
pub const COLD_MAINTENANCE_PROFILE_TITLE: &str = "Crema cold maintenance";

/// The three requestable states the cold workaround covers.
#[must_use]
pub fn is_maintenance_state(state: MachineState) -> bool {
    matches!(
        state,
        MachineState::Descale | MachineState::Clean | MachineState::AirPurge
    )
}

/// Whether the connected DE1's firmware drops a cold maintenance request.
///
/// **An unknown build counts as old** — the same call Decenza and decaid make
/// (Decenza `firmwareBuildNumber()` is 0 until the MMR read lands; decaid
/// `int.tryParse(version) ?? 0`). Not a coin flip: a machine new enough to
/// honour a cold request is new enough to report its build, so a missing
/// build is itself evidence of an old or unhealthy machine. And the costs are
/// lopsided: assuming new on an old machine reproduces the silent drop this
/// exists to remove, while assuming old on a new machine only loads a
/// throwaway profile and waits for preheat to end — the cycle still runs, and
/// the shell re-uploads the real profile afterwards.
#[must_use]
pub fn firmware_drops_cold_requests(firmware_build: Option<u32>) -> bool {
    firmware_build.is_none_or(|build| build < COLD_MAINTENANCE_MIN_FIRMWARE_BUILD)
}

/// True while the machine is still coming up to temperature — the window in
/// which old firmware discards a maintenance request (Decenza
/// `isMachineHeating`: state `Busy`, or substate `Heating` / `FinalHeating` /
/// `Stabilising`, which the machine also reports from Idle).
#[must_use]
pub fn is_machine_heating(info: StateInfo) -> bool {
    info.state == MachineState::Busy
        || matches!(
            info.substate,
            SubState::Heating | SubState::FinalHeating | SubState::Stabilising
        )
}

/// Whether a request for `requested` must go through the cold workaround
/// instead of a plain state write.
///
/// All of: a maintenance state, firmware that drops cold requests (unknown
/// counts as old — see [`firmware_drops_cold_requests`]), a Group Head
/// Controller fitted, and the machine currently heating. `ghc_info` is the raw
/// MMR `GhcInfo` word; any non-zero value is "fitted" (crema's reading, the
/// same both shells use). An unread GHC register counts as NOT fitted, the
/// default both Decenza (`m_isHeadless = true`) and decaid
/// (`groupHeadControllerPresent: false`) start from — the firmware defect is a
/// GHC-machine defect.
#[must_use]
pub fn needs_cold_workaround(
    requested: MachineState,
    firmware_build: Option<u32>,
    ghc_info: Option<u32>,
    current: Option<StateInfo>,
) -> bool {
    is_maintenance_state(requested)
        && firmware_drops_cold_requests(firmware_build)
        && ghc_info.is_some_and(|ghc| ghc != 0)
        && current.is_some_and(is_machine_heating)
}

/// The one-frame profile the workaround loads: goal 1 °C on the coffee
/// sensor, pressure 0, one second, tank target 0 — the frame de1app
/// (`binary.tcl` `onestep_cold`) and decaid (`_onestepColdProfile`) send.
#[must_use]
pub fn cold_maintenance_profile() -> Profile {
    Profile {
        id: String::new(),
        title: COLD_MAINTENANCE_PROFILE_TITLE.to_owned(),
        notes: "Loaded so old DE1 firmware accepts a cold Descale / Clean / AirPurge request."
            .to_owned(),
        steps: vec![ProfileStep {
            name: "cold maintenance".to_owned(),
            pump: Pump::Pressure,
            target: 0.0,
            temperature_c: 1.0,
            temp_sensor: TempSensor::Coffee,
            transition: Transition::Fast,
            duration_seconds: 1.0,
            exit: None,
            volume_limit_ml: 0,
            limiter: None,
            weight: None,
        }],
        preinfuse_step_count: 0,
        minimum_pressure: 0.0,
        maximum_flow: 0.0,
        max_total_volume_ml: 0,
        target_weight: 0.0,
        dose: 0.0,
        author: "crema".to_owned(),
        beverage_type: BeverageType::Cleaning,
        tank_temperature: 0.0,
        version: "2".to_owned(),
    }
}

/// The DE1's fixed descale schedule — see the module docs.
pub const DESCALE_SCHEDULE: [(SubState, u16); 5] = [
    (SubState::DescaleInit, 30),
    (SubState::DescaleFillGroup, 30),
    (SubState::DescaleReturn, 120),
    (SubState::DescaleGroup, 120),
    (SubState::DescaleSteam, 420),
];

/// Total length of one descale cycle, seconds (720).
pub const DESCALE_TOTAL_SECONDS: u16 = {
    let mut total = 0;
    let mut i = 0;
    while i < DESCALE_SCHEDULE.len() {
        total += DESCALE_SCHEDULE[i].1;
        i += 1;
    }
    total
};

/// Progress never reaches 1.0 from the schedule alone (Decenza caps at 0.999).
const PROGRESS_CAP: f32 = 0.999;

/// Seconds of the schedule completed before step `index` begins.
fn seconds_before(index: usize) -> u16 {
    DESCALE_SCHEDULE.iter().take(index).map(|(_, s)| s).sum()
}

/// 0-based position of `substate` in [`DESCALE_SCHEDULE`].
fn step_position(substate: SubState) -> Option<usize> {
    DESCALE_SCHEDULE.iter().position(|(s, _)| *s == substate)
}

/// One descale progress reading.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct DescaleProgress {
    /// 1-based step in the schedule (`1..=5`), `0` when not in a step.
    pub step_index: u8,
    /// Fraction done, `0.0..=0.999`.
    pub progress: f32,
    /// Whole seconds left in the cycle.
    pub seconds_remaining: u32,
    /// Which pass of the five steps this is (the firmware can run them more
    /// than once; a step number that goes backwards starts a new cycle).
    pub cycle: u8,
}

/// Descale step tracker — Decenza's `m_descaleTimer` / `m_descaleStepStartMs`
/// / `m_descaleCycle` as a sans-IO struct fed state notifications and read at
/// any timestamp.
#[derive(Debug, Clone, Default)]
pub struct DescaleTracker {
    /// When the machine entered Descale; `None` outside a descale.
    started: Option<Duration>,
    /// When the current step began.
    step_started: Duration,
    /// Pass counter, from 1.
    cycle: u8,
}

impl DescaleTracker {
    /// Fold one state transition (`prev` → `next`) observed at `now`.
    pub fn on_state(&mut self, prev: Option<StateInfo>, next: StateInfo, now: Duration) {
        let prev_state = prev.map(|p| p.state);
        if next.state == MachineState::Descale {
            if prev_state != Some(MachineState::Descale) {
                self.started = Some(now);
                self.step_started = now;
                self.cycle = 1;
            } else if let Some(prev) = prev
                && prev.substate != next.substate
                && self.started.is_some()
            {
                // Only a step-to-step move that does not advance counts as a
                // new cycle; the drop to Ready at the end is an ending.
                let wrapped = matches!(
                    (step_position(prev.substate), step_position(next.substate)),
                    (Some(from), Some(to)) if to <= from
                );
                if wrapped {
                    self.cycle = self.cycle.saturating_add(1);
                }
                self.step_started = now;
            }
        } else {
            self.started = None;
        }
    }

    /// Progress at `now` for the machine's `current` state, or `None` when
    /// not descaling.
    #[must_use]
    pub fn progress(&self, current: StateInfo, now: Duration) -> Option<DescaleProgress> {
        self.started?;
        if current.state != MachineState::Descale {
            return None;
        }
        let Some(position) = step_position(current.substate) else {
            // Descale/Ready at the very start or end: no progress, rather
            // than a stale figure.
            return Some(DescaleProgress {
                step_index: 0,
                progress: 0.0,
                seconds_remaining: 0,
                cycle: self.cycle,
            });
        };
        let step_seconds = DESCALE_SCHEDULE[position].1;
        let step_elapsed = now
            .saturating_sub(self.step_started)
            .as_secs_f32()
            .clamp(0.0, f32::from(step_seconds));
        let done = f32::from(seconds_before(position)) + step_elapsed;
        let total = f32::from(DESCALE_TOTAL_SECONDS);
        #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
        let seconds_remaining = (total - done).round().max(0.0) as u32;
        #[allow(clippy::cast_possible_truncation)]
        let step_index = (position + 1) as u8;
        Some(DescaleProgress {
            step_index,
            progress: (done / total).clamp(0.0, PROGRESS_CAP),
            seconds_remaining,
            cycle: self.cycle,
        })
    }
}

/// Where a maintenance cycle the core is following stands — carried by
/// `Event::MaintenanceProgress`.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum MaintenancePhase {
    /// Old firmware, machine heating: the 1 °C profile is loaded and the
    /// request is held until the machine reports it has left preheat.
    WaitingForPreheat,
    /// The held request has been sent; waiting for the machine to enter the
    /// state.
    Requested,
    /// The machine is in the maintenance state.
    Running,
    /// The machine left the maintenance state.
    Finished,
    /// Cancelled by the user, by another state request, or by a DE1 link drop.
    Cancelled,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn info(state: MachineState, substate: SubState) -> StateInfo {
        StateInfo { state, substate }
    }

    fn secs(s: u64) -> Duration {
        Duration::from_secs(s)
    }

    #[test]
    fn firmware_gate_1355_old_1356_new_unknown_old() {
        assert!(firmware_drops_cold_requests(Some(1355)));
        assert!(!firmware_drops_cold_requests(Some(1356)));
        assert!(!firmware_drops_cold_requests(Some(1358)));
        assert!(firmware_drops_cold_requests(None));
    }

    #[test]
    fn workaround_needs_maintenance_ghc_and_heating() {
        let heating = Some(info(MachineState::Idle, SubState::Heating));
        assert!(needs_cold_workaround(
            MachineState::Descale,
            Some(1355),
            Some(3),
            heating
        ));
        assert!(needs_cold_workaround(
            MachineState::AirPurge,
            None,
            Some(1),
            heating
        ));
        // ≥ 1356 → plain.
        assert!(!needs_cold_workaround(
            MachineState::Clean,
            Some(1356),
            Some(3),
            heating
        ));
        // Not a maintenance state.
        assert!(!needs_cold_workaround(
            MachineState::Espresso,
            Some(1355),
            Some(3),
            heating
        ));
        // Headless / GHC unread.
        assert!(!needs_cold_workaround(
            MachineState::Descale,
            Some(1355),
            Some(0),
            heating
        ));
        assert!(!needs_cold_workaround(
            MachineState::Descale,
            Some(1355),
            None,
            heating
        ));
        // Warm machine.
        assert!(!needs_cold_workaround(
            MachineState::Descale,
            Some(1355),
            Some(3),
            Some(info(MachineState::Idle, SubState::Ready))
        ));
        // Busy counts as heating.
        assert!(needs_cold_workaround(
            MachineState::Descale,
            Some(1355),
            Some(3),
            Some(info(MachineState::Busy, SubState::Ready))
        ));
    }

    #[test]
    fn cold_profile_is_one_frame_at_one_degree_with_no_tank_target() {
        let profile = cold_maintenance_profile();
        assert_eq!(profile.steps.len(), 1);
        assert!((profile.steps[0].temperature_c - 1.0).abs() < f32::EPSILON);
        assert!((profile.steps[0].duration_seconds - 1.0).abs() < f32::EPSILON);
        assert!(profile.tank_temperature.abs() < f32::EPSILON);
        assert!(profile.assemble().is_ok());
    }

    #[test]
    fn schedule_totals_720_seconds() {
        assert_eq!(DESCALE_TOTAL_SECONDS, 720);
    }

    #[test]
    fn progress_follows_the_schedule_and_resyncs_at_boundaries() {
        let mut t = DescaleTracker::default();
        let idle = info(MachineState::Idle, SubState::Ready);
        let s1 = info(MachineState::Descale, SubState::DescaleInit);
        t.on_state(Some(idle), s1, secs(100));
        let p = t.progress(s1, secs(115)).unwrap();
        assert_eq!(p.step_index, 1);
        assert_eq!(p.seconds_remaining, 705);
        // Clamped to the step's 30 s even if the boundary is late.
        assert_eq!(t.progress(s1, secs(200)).unwrap().seconds_remaining, 690);
        // Boundary into DescaleSteam resyncs: 300 s done before it.
        let s5 = info(MachineState::Descale, SubState::DescaleSteam);
        t.on_state(Some(s1), s5, secs(400));
        let p = t.progress(s5, secs(400)).unwrap();
        assert_eq!(p.step_index, 5);
        assert_eq!(p.seconds_remaining, 420);
        // Capped below 1.0 at the end of the last step.
        let p = t.progress(s5, secs(10_000)).unwrap();
        assert!(p.progress < 1.0 && p.progress >= 0.999 - f32::EPSILON);
        assert_eq!(p.seconds_remaining, 0);
        assert_eq!(p.cycle, 1);
        // Steam back to FillGroup = a new cycle.
        let s2 = info(MachineState::Descale, SubState::DescaleFillGroup);
        t.on_state(Some(s5), s2, secs(10_001));
        assert_eq!(t.progress(s2, secs(10_001)).unwrap().cycle, 2);
        // Leaving Descale ends tracking.
        t.on_state(Some(s2), idle, secs(10_002));
        assert!(t.progress(idle, secs(10_002)).is_none());
    }
}
