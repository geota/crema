//! Maintenance-cycle orchestration: the cold-maintenance workaround for old
//! DE1 firmware and the progress the shells show for a running cycle.
//!
//! The pure rules live in [`de1_domain::cold_maintenance`]; this module is
//! the sans-IO state machine that applies them to the notification stream.
//!
//! **Cold workaround** (Decenza `b1ceab8c` `requestMaintenanceState`, de1app
//! `de1_send_pre_maintenance_profile`, decaid
//! `_prepareColdMaintenanceWorkaround`). On firmware below build 1356 — or an
//! unread build, which counts as old — a Descale / Clean / AirPurge request
//! on a GHC machine that is still heating is silently dropped. So instead of
//! the state write, [`CremaCore::request_machine_state`] uploads the one-frame
//! 1 °C profile, zeroes the tank threshold, and HOLDS the request
//! ([`MaintenancePhase::WaitingForPreheat`]). The first state notification
//! showing the machine no longer heating releases it
//! ([`MaintenancePhase::Requested`]). On firmware ≥ 1356 the request stays the
//! plain single write it always was.
//!
//! **Progress.** Whoever started it (the app or the GHC), a cycle is followed
//! from the machine entering the state ([`MaintenancePhase::Running`]) to
//! leaving it ([`MaintenancePhase::Finished`]); a descale reports its step,
//! fraction and seconds left from the fixed 720 s schedule
//! ([`de1_domain::DescaleTracker`]), recomputed on every notification and
//! tick, emitted only when a whole second (or step / cycle / phase) moves.
//!
//! **Cancel / disconnect.** [`CremaCore::cancel_maintenance`] drops a held
//! request, or asks a running cycle to stop (Idle). A DE1 link drop or reset
//! abandons whatever is being followed with [`MaintenancePhase::Cancelled`].

use std::time::Duration;

use de1_domain::{
    MaintenancePhase, cold_maintenance_profile, is_machine_heating, is_maintenance_state,
    needs_cold_workaround,
};
use de1_protocol::{MachineState, MmrRegister, StateInfo, requested_state};

use crate::{Command, CoreOutput, CremaCore, Event, WriteTarget, mmr_write_command};

/// How long a released (cold-workaround) request may wait for the machine to
/// enter the state before the core gives up on it and reports `Cancelled`,
/// so the shell's progress row cannot hang on a request the firmware refused.
pub(crate) const MAINTENANCE_ENTRY_TIMEOUT: Duration = Duration::from_secs(15);

/// The cycle the core is following.
#[derive(Debug, Clone)]
pub(crate) struct MaintenanceRun {
    /// The maintenance state.
    state: MachineState,
    /// Where it stands.
    phase: MaintenancePhase,
    /// The 1 °C profile was loaded for it.
    cold: bool,
    /// The user cancelled while it was running — its exit reports
    /// `Cancelled`, not `Finished`.
    cancelled: bool,
    /// When the held request was released (`Requested`).
    requested_at: Option<Duration>,
    /// The last values reported, for edge-triggering.
    reported: Option<(MaintenancePhase, u8, u32, u8)>,
}

impl MaintenanceRun {
    fn new(state: MachineState, phase: MaintenancePhase, cold: bool) -> Self {
        Self {
            state,
            phase,
            cold,
            cancelled: false,
            requested_at: None,
            reported: None,
        }
    }
}

impl CremaCore {
    /// Start the cold workaround for `state` when the gate says so — see the
    /// module docs. Returns `true` when the request was HELD (the caller must
    /// not write the state); `false` for a plain request.
    pub(crate) fn begin_cold_maintenance(
        &mut self,
        state: MachineState,
        out: &mut CoreOutput,
    ) -> bool {
        if !needs_cold_workaround(
            state,
            self.de1_firmware_build,
            self.de1_ghc_info,
            self.last_state,
        ) {
            return false;
        }
        let Ok(upload) = self.upload_profile(&cold_maintenance_profile(), self.clock) else {
            // The fixed profile always assembles; if it ever did not, a plain
            // request is better than none.
            return false;
        };
        out.commands.extend(upload.commands);
        out.events.extend(upload.events);
        // The 0 °C tank target the reference profiles carry (decaid
        // `tankTemperature: 0`, Decenza `setTankDesiredWaterTemperature(0)`).
        out.commands
            .extend(mmr_write_command(MmrRegister::TankTempThreshold, 0, 4).commands);
        self.maintenance = Some(MaintenanceRun::new(
            state,
            MaintenancePhase::WaitingForPreheat,
            true,
        ));
        self.report_maintenance(self.clock, out);
        true
    }

    /// A new state request supersedes a maintenance request still held for
    /// preheat — otherwise it would fire later, unasked.
    pub(crate) fn supersede_held_maintenance(&mut self, out: &mut CoreOutput) {
        if self
            .maintenance
            .as_ref()
            .is_some_and(|run| run.phase == MaintenancePhase::WaitingForPreheat)
        {
            self.finish_maintenance(MaintenancePhase::Cancelled, self.clock, out);
        }
    }

    /// Cancel the maintenance cycle the core is following: a held or
    /// just-released request is dropped (`Cancelled`); a running cycle is
    /// asked to stop with an Idle request and reports `Cancelled` when the
    /// machine leaves the state. Empty when nothing is being followed.
    ///
    /// Refused while a firmware upload is in progress.
    pub fn cancel_maintenance(&mut self) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("cancel_maintenance") {
            return out;
        }
        let mut out = CoreOutput::default();
        let Some(phase) = self.maintenance.as_ref().map(|run| run.phase) else {
            return out;
        };
        match phase {
            MaintenancePhase::WaitingForPreheat | MaintenancePhase::Requested => {
                self.finish_maintenance(MaintenancePhase::Cancelled, self.clock, &mut out);
                // A released request may already be on its way in: stop it.
                if phase == MaintenancePhase::Requested {
                    push_state_write(MachineState::Idle, &mut out);
                }
            }
            MaintenancePhase::Running => {
                if let Some(run) = self.maintenance.as_mut() {
                    run.cancelled = true;
                }
                push_state_write(MachineState::Idle, &mut out);
            }
            MaintenancePhase::Finished | MaintenancePhase::Cancelled => {}
        }
        self.gate_read_only(out)
    }

    /// Fold a state transition into the followed cycle. Called from
    /// `handle_state` after `last_state` is committed.
    pub(crate) fn advance_maintenance(
        &mut self,
        info: StateInfo,
        now: Duration,
        out: &mut CoreOutput,
    ) {
        // Release a held request on the first packet showing the machine is
        // no longer heating (Decenza `flushPendingMaintenanceState`).
        if let Some(run) = self.maintenance.as_mut()
            && run.phase == MaintenancePhase::WaitingForPreheat
            && !is_machine_heating(info)
        {
            run.phase = MaintenancePhase::Requested;
            run.requested_at = Some(now);
            push_state_write(run.state, out);
        }
        if is_maintenance_state(info.state) {
            let carried_cold = self
                .maintenance
                .as_ref()
                .is_some_and(|run| run.state == info.state && run.cold);
            let already_running = self.maintenance.as_ref().is_some_and(|run| {
                run.state == info.state && run.phase == MaintenancePhase::Running
            });
            if !already_running {
                self.maintenance = Some(MaintenanceRun::new(
                    info.state,
                    MaintenancePhase::Running,
                    carried_cold,
                ));
            }
        } else if self
            .maintenance
            .as_ref()
            .is_some_and(|run| run.phase == MaintenancePhase::Running)
        {
            let cancelled = self.maintenance.as_ref().is_some_and(|run| run.cancelled);
            let phase = if cancelled {
                MaintenancePhase::Cancelled
            } else {
                MaintenancePhase::Finished
            };
            self.finish_maintenance(phase, now, out);
            return;
        }
        self.report_maintenance(now, out);
    }

    /// Per-notification / per-tick upkeep: the released-request timeout and
    /// the descale countdown.
    pub(crate) fn update_maintenance(&mut self, now: Duration, out: &mut CoreOutput) {
        let Some(run) = self.maintenance.as_ref() else {
            return;
        };
        if run.phase == MaintenancePhase::Requested
            && run
                .requested_at
                .is_some_and(|at| now.saturating_sub(at) >= MAINTENANCE_ENTRY_TIMEOUT)
        {
            self.finish_maintenance(MaintenancePhase::Cancelled, now, out);
            return;
        }
        self.report_maintenance(now, out);
    }

    /// The DE1 link dropped or the core is resetting: whatever cycle is being
    /// followed is over as far as the core can know.
    pub(crate) fn abandon_maintenance(&mut self, out: &mut CoreOutput) {
        if self.maintenance.is_some() {
            self.finish_maintenance(MaintenancePhase::Cancelled, self.clock, out);
        }
        self.descale = de1_domain::DescaleTracker::default();
    }

    /// End the followed cycle with a terminal `phase`, re-asserting the tank
    /// threshold the 1 °C profile zeroed.
    fn finish_maintenance(&mut self, phase: MaintenancePhase, now: Duration, out: &mut CoreOutput) {
        let Some(run) = self.maintenance.as_mut() else {
            return;
        };
        run.phase = phase;
        let cold = run.cold;
        self.report_maintenance(now, out);
        self.maintenance = None;
        if cold && let Some(tank) = self.tank_temp_threshold_c {
            out.commands.extend(
                mmr_write_command(MmrRegister::TankTempThreshold, u32::from(tank), 4).commands,
            );
        }
    }

    /// Emit [`Event::MaintenanceProgress`] when the reading moved.
    fn report_maintenance(&mut self, now: Duration, out: &mut CoreOutput) {
        let descale = self
            .last_state
            .and_then(|info| self.descale.progress(info, now));
        let Some(run) = self.maintenance.as_mut() else {
            return;
        };
        let is_descale = run.state == MachineState::Descale;
        let (step_index, progress, seconds_remaining, cycle) = match (run.phase, descale) {
            (MaintenancePhase::Finished, _) if is_descale => (0, 1.0, 0, 0),
            (MaintenancePhase::Running, Some(p)) if is_descale => {
                (p.step_index, p.progress, p.seconds_remaining, p.cycle)
            }
            _ => (0, 0.0, 0, 0),
        };
        let key = (run.phase, step_index, seconds_remaining, cycle);
        if run.reported == Some(key) {
            return;
        }
        run.reported = Some(key);
        #[allow(clippy::cast_possible_truncation)]
        let step_count = if is_descale {
            de1_domain::DESCALE_SCHEDULE.len() as u8
        } else {
            0
        };
        out.events.push(Event::MaintenanceProgress {
            state: run.state,
            phase: run.phase,
            step_index,
            step_count,
            progress,
            seconds_remaining,
            cycle,
            cold_workaround: run.cold,
        });
    }
}

/// One `RequestedState` write.
fn push_state_write(state: MachineState, out: &mut CoreOutput) {
    out.commands.push(Command::WriteCharacteristic {
        target: WriteTarget::De1RequestedState,
        data: vec![requested_state(state)],
    });
}

#[cfg(test)]
mod tests {
    use de1_domain::MaintenancePhase;

    /// The 24-bit big-endian address bytes an MMR packet carries.
    fn addr_bytes(register: MmrRegister) -> [u8; 3] {
        let [_, a, b, c] = register.address().to_be_bytes();
        [a, b, c]
    }
    use de1_protocol::{MachineState, MmrRegister, requested_state};

    use crate::{Command, CoreOutput, CremaCore, Event, Source, WriteTarget};

    fn mmr_packet(register: MmrRegister, value: u32) -> [u8; de1_protocol::MMR_PACKET_LEN] {
        let mut packet = [0u8; de1_protocol::MMR_PACKET_LEN];
        packet[1..4].copy_from_slice(&addr_bytes(register));
        packet[4..8].copy_from_slice(&value.to_le_bytes());
        packet
    }

    /// A core connected to a GHC machine at `build` (None = never read),
    /// idle and heating.
    fn heating_core(build: Option<u32>) -> CremaCore {
        let mut core = CremaCore::new();
        if let Some(build) = build {
            let _ = core.on_notification(
                Source::De1MmrRead,
                &mmr_packet(MmrRegister::FirmwareVersion, build),
                0,
            );
        }
        let _ = core.on_notification(Source::De1MmrRead, &mmr_packet(MmrRegister::GhcInfo, 3), 0);
        let _ = core.on_notification(Source::De1State, &[2, 1], 1_000); // Idle / Heating
        core
    }

    fn state_writes(out: &CoreOutput) -> Vec<u8> {
        out.commands
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

    fn uploads_profile(out: &CoreOutput) -> bool {
        out.commands.iter().any(|c| {
            matches!(
                c,
                Command::WriteCharacteristic {
                    target: WriteTarget::De1ProfileHeader,
                    ..
                }
            )
        })
    }

    fn tank_writes(out: &CoreOutput) -> Vec<u8> {
        let addr = addr_bytes(MmrRegister::TankTempThreshold);
        out.commands
            .iter()
            .filter_map(|c| match c {
                Command::WriteCharacteristic {
                    target: WriteTarget::De1MmrWrite,
                    data,
                } if data[1..4] == addr => Some(data[4]),
                _ => None,
            })
            .collect()
    }

    /// (phase, step_index, seconds_remaining, cold) of every progress event.
    fn progress(out: &CoreOutput) -> Vec<(MaintenancePhase, u8, u32, bool)> {
        out.events
            .iter()
            .filter_map(|e| match e {
                Event::MaintenanceProgress {
                    phase,
                    step_index,
                    seconds_remaining,
                    cold_workaround,
                    ..
                } => Some((*phase, *step_index, *seconds_remaining, *cold_workaround)),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn firmware_1355_holds_the_request_behind_the_cold_profile() {
        let mut core = heating_core(Some(1355));
        let out = core.request_machine_state(MachineState::Descale);
        assert!(uploads_profile(&out), "the 1 °C profile is uploaded");
        assert_eq!(tank_writes(&out), vec![0], "tank target zeroed");
        assert!(state_writes(&out).is_empty(), "the request is held");
        assert_eq!(
            progress(&out),
            vec![(MaintenancePhase::WaitingForPreheat, 0, 0, true)]
        );
        // Still heating: nothing goes out.
        let out = core.on_notification(Source::De1State, &[2, 2], 2_000); // FinalHeating
        assert!(state_writes(&out).is_empty());
        // Preheat over: the held request is released.
        let out = core.on_notification(Source::De1State, &[2, 0], 3_000);
        assert_eq!(
            state_writes(&out),
            vec![requested_state(MachineState::Descale)]
        );
        assert_eq!(
            progress(&out),
            vec![(MaintenancePhase::Requested, 0, 0, true)]
        );
        // The machine enters Descale: Running, cold flag carried.
        let out = core.on_notification(Source::De1State, &[10, 8], 4_000);
        assert_eq!(
            progress(&out),
            vec![(MaintenancePhase::Running, 1, 720, true)]
        );
    }

    #[test]
    fn firmware_1356_sends_a_plain_request() {
        let mut core = heating_core(Some(1356));
        let out = core.request_machine_state(MachineState::Descale);
        assert!(!uploads_profile(&out));
        assert_eq!(
            state_writes(&out),
            vec![requested_state(MachineState::Descale)]
        );
        assert!(progress(&out).is_empty());
        // Clean and AirPurge too.
        for state in [MachineState::Clean, MachineState::AirPurge] {
            let out = core.request_machine_state(state);
            assert!(!uploads_profile(&out));
            assert_eq!(state_writes(&out), vec![requested_state(state)]);
        }
    }

    #[test]
    fn unknown_firmware_counts_as_old() {
        let mut core = heating_core(None);
        let out = core.request_machine_state(MachineState::Clean);
        assert!(uploads_profile(&out));
        assert!(state_writes(&out).is_empty());
    }

    #[test]
    fn a_warm_machine_on_old_firmware_gets_a_plain_request() {
        let mut core = heating_core(Some(1300));
        let _ = core.on_notification(Source::De1State, &[2, 0], 2_000); // Idle / Ready
        let out = core.request_machine_state(MachineState::AirPurge);
        assert!(!uploads_profile(&out));
        assert_eq!(
            state_writes(&out),
            vec![requested_state(MachineState::AirPurge)]
        );
    }

    #[test]
    fn descale_countdown_follows_the_schedule_and_is_edge_triggered() {
        let mut core = heating_core(Some(1358));
        let _ = core.on_notification(Source::De1State, &[10, 8], 10_000); // DescaleInit
        let out = core.on_tick(10_400);
        assert!(progress(&out).is_empty(), "no new whole second yet");
        let out = core.on_tick(25_000);
        assert_eq!(
            progress(&out),
            vec![(MaintenancePhase::Running, 1, 705, false)]
        );
        // Boundary resync: FillGroup at t=40 s → 690 s left.
        let out = core.on_notification(Source::De1State, &[10, 9], 40_000);
        assert_eq!(
            progress(&out),
            vec![(MaintenancePhase::Running, 2, 690, false)]
        );
        // Water-level notifications advance it between boundaries.
        let out = core.on_notification(Source::De1WaterLevels, &[10, 0, 5, 0], 50_000);
        assert_eq!(
            progress(&out),
            vec![(MaintenancePhase::Running, 2, 680, false)]
        );
        // Leaving Descale finishes it.
        let out = core.on_notification(Source::De1State, &[2, 0], 60_000);
        assert_eq!(
            progress(&out),
            vec![(MaintenancePhase::Finished, 0, 0, false)]
        );
        let out = core.on_tick(61_000);
        assert!(progress(&out).is_empty());
    }

    #[test]
    fn cancel_while_held_drops_the_request() {
        let mut core = heating_core(Some(1355));
        let _ = core.request_machine_state(MachineState::Descale);
        let out = core.cancel_maintenance();
        assert_eq!(
            progress(&out),
            vec![(MaintenancePhase::Cancelled, 0, 0, true)]
        );
        // Preheat ending later sends nothing.
        let out = core.on_notification(Source::De1State, &[2, 0], 3_000);
        assert!(state_writes(&out).is_empty());
    }

    #[test]
    fn cancel_while_running_asks_for_idle_and_reports_cancelled_on_exit() {
        let mut core = heating_core(Some(1358));
        let _ = core.on_notification(Source::De1State, &[10, 8], 10_000);
        let out = core.cancel_maintenance();
        assert_eq!(
            state_writes(&out),
            vec![requested_state(MachineState::Idle)]
        );
        let out = core.on_notification(Source::De1State, &[2, 0], 11_000);
        assert_eq!(
            progress(&out),
            vec![(MaintenancePhase::Cancelled, 0, 0, false)]
        );
    }

    #[test]
    fn another_request_supersedes_a_held_one() {
        let mut core = heating_core(Some(1355));
        let _ = core.request_machine_state(MachineState::Descale);
        let out = core.request_machine_state(MachineState::Sleep);
        assert_eq!(
            progress(&out),
            vec![(MaintenancePhase::Cancelled, 0, 0, true)]
        );
        assert_eq!(
            state_writes(&out),
            vec![requested_state(MachineState::Sleep)]
        );
    }

    #[test]
    fn disconnect_mid_schedule_cancels() {
        let mut core = heating_core(Some(1358));
        let _ = core.on_notification(Source::De1State, &[10, 12], 10_000); // DescaleSteam
        let out = core.de1_link_lost();
        assert_eq!(
            progress(&out),
            vec![(MaintenancePhase::Cancelled, 0, 0, false)]
        );
        // And a held cold request is dropped by a reset too.
        let mut core = heating_core(Some(1355));
        let _ = core.request_machine_state(MachineState::Clean);
        let out = core.reset();
        assert_eq!(
            progress(&out),
            vec![(MaintenancePhase::Cancelled, 0, 0, true)]
        );
    }

    #[test]
    fn a_released_request_the_machine_never_honours_times_out() {
        let mut core = heating_core(Some(1355));
        let _ = core.request_machine_state(MachineState::Descale);
        let _ = core.on_notification(Source::De1State, &[2, 0], 2_000);
        let out = core.on_tick(2_000 + 14_000);
        assert!(progress(&out).is_empty());
        let out = core.on_tick(2_000 + 15_000);
        assert_eq!(
            progress(&out),
            vec![(MaintenancePhase::Cancelled, 0, 0, true)]
        );
    }

    #[test]
    fn a_cold_cycle_restores_the_swept_tank_threshold() {
        let mut core = heating_core(Some(1355));
        core.tank_temp_threshold_c = Some(30);
        let _ = core.request_machine_state(MachineState::Descale);
        let _ = core.on_notification(Source::De1State, &[2, 0], 2_000);
        let _ = core.on_notification(Source::De1State, &[10, 8], 3_000);
        let out = core.on_notification(Source::De1State, &[2, 0], 9_000);
        assert_eq!(
            progress(&out),
            vec![(MaintenancePhase::Finished, 0, 0, true)]
        );
        assert_eq!(tank_writes(&out), vec![30]);
    }
}
