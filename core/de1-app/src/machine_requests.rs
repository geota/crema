//! Machine state requests on their way to the DE1 (F16a): the firmware's
//! profile-download guard, the maintenance "Run now" gate, and the wake hint
//! the shells use to tick the core between their once-a-second ticks.
//!
//! **Profile-download guard** (BC 9788201734; reaprime
//! `ConnectionTimings.profileDownloadGuard`, 500 ms). The DE1 holds
//! `ProfileDownloadInProgress` while a profile downloads and for a short
//! window after the last frame write; a state request inside it is aborted to
//! HeaterDown after preinfuse. So every request — the user's and the core's
//! own — waits while a profile upload is in flight and for
//! [`PROFILE_DOWNLOAD_GUARD`] after it completes. It is **held**: the latest
//! held request replaces an earlier one, and it goes out on the first tick
//! past the window ([`Event::WakeAt`] asks for that tick). A held
//! maintenance release (the cold-maintenance workaround) waits the same way.
//!
//! **Idle is never held.** A Stop must reach the DE1 at once whatever is
//! pending — a group-head shot can start while a profile uploads — so an Idle
//! request bypasses the guard and drops any held request.
//!
//! **A held request is for the machine as it was.** The machine state is
//! recorded when a request is held; if the machine has left that state by
//! the release (a shot started at the group head, a steam session, sleep),
//! the request is dropped, not sent — sending it would bounce the machine
//! through Idle and stop what it is doing — and a maintenance request held
//! from Idle or Sleep can't go out from any other state, so the Run-now gate
//! holds at the release too. A request held longer than
//! [`MAX_HOLD`] (an upload waiting for the machine model) is dropped too.
//! The user's own requests report each of these as
//! [`Event::MachineRequestDropped`]; [`Event::MachineRequestHeld`] tells
//! them one is waiting.
//!
//! **Run-now gate.** The app may request a maintenance cycle (descale, clean,
//! air purge, steam rinse) only with the machine in Idle or Sleep
//! ([`de1_domain::maintenance_request_allowed`], the calibration guard's
//! rule); any other request is refused with
//! [`Event::MachineRequestRefused`] and nothing is written.
//!
//! **Wake hint.** Whenever the earliest deadline the core is waiting for
//! changes, it emits [`Event::WakeAt`] with that time (the shell's clock, the
//! one it passes to `on_tick`). The shell keeps one one-shot timer, replaced
//! by each hint, that calls `on_tick` then.

use std::time::Duration;

use de1_domain::{is_run_now_state, maintenance_request_allowed};
use de1_protocol::{MachineState, requested_state};
use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::{Command, CoreOutput, CremaCore, Event, WriteTarget};

/// How long after a profile upload completes the DE1 still aborts a state
/// request (BC 9788201734; reaprime `ConnectionTimings.profileDownloadGuard`).
pub const PROFILE_DOWNLOAD_GUARD: Duration = Duration::from_millis(500);

/// The longest a request waits out the guard before it is dropped (an upload
/// that waits for the machine model can take ~10 s).
pub const MAX_HOLD: Duration = Duration::from_secs(3);

/// Why a held machine state request was dropped instead of sent.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
#[non_exhaustive]
pub enum MachineRequestDrop {
    /// The machine left the state it was in when the request was made (a
    /// shot started at the group head, for one).
    StateChanged {
        /// The state it was in.
        from: Option<MachineState>,
        /// The state it is in now.
        to: MachineState,
    },
    /// The profile took longer than [`MAX_HOLD`] to load.
    TookTooLong,
}

/// Why a machine state request was refused before it reached the DE1.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
#[non_exhaustive]
pub enum MachineRequestRefusal {
    /// A maintenance cycle needs the machine in Idle or Sleep.
    MachineNotIdle {
        /// The state the machine last reported.
        state: MachineState,
    },
    /// No machine state has been reported yet (fail closed).
    MachineStateUnknown,
}

/// The guard's bookkeeping.
#[derive(Debug, Default)]
pub(crate) struct RequestGuard {
    /// When the last profile upload completed.
    upload_completed_at: Option<Duration>,
    /// The request waiting out the guard.
    held: Option<Held>,
    /// The deadline the last [`Event::WakeAt`] asked for.
    wake_sent: Option<Duration>,
}

/// The states the DE1 enters only from Idle, and in which water flows.
fn is_flow_state(state: MachineState) -> bool {
    matches!(
        state,
        MachineState::Espresso
            | MachineState::Steam
            | MachineState::HotWater
            | MachineState::HotWaterRinse
    )
}

/// A request waiting out the guard.
#[derive(Debug, Clone, Copy)]
struct Held {
    state: MachineState,
    /// The machine state when it was held.
    from: Option<MachineState>,
    /// When it was held.
    at: Duration,
    /// The user asked for it (not the core itself).
    user: bool,
}

/// Where the guard stands.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Guard {
    /// Requests go out now.
    Clear,
    /// A profile upload is in flight: requests wait for it.
    Uploading,
    /// Requests wait until this time.
    Until(Duration),
}

impl CremaCore {
    /// Where the profile-download guard stands at the core's clock.
    pub(crate) fn guard(&self) -> Guard {
        if self.profile_upload_in_progress() {
            return Guard::Uploading;
        }
        match self.request_guard.upload_completed_at {
            Some(at) if self.clock < at + PROFILE_DOWNLOAD_GUARD => {
                Guard::Until(at + PROFILE_DOWNLOAD_GUARD)
            }
            _ => Guard::Clear,
        }
    }

    /// A profile upload completed at `now`: the guard window opens.
    pub(crate) fn note_upload_completed(&mut self, now: Duration, out: &mut CoreOutput) {
        self.request_guard.upload_completed_at = Some(now);
        self.emit_wake(out);
    }

    /// The Run-now gate: `Some` (the refusal) when `state` is a maintenance
    /// cycle and the machine is not in Idle or Sleep.
    pub(crate) fn refuse_run_now(&self, state: MachineState) -> Option<CoreOutput> {
        if !is_run_now_state(state) {
            return None;
        }
        let current = self.last_state.map(|info| info.state);
        if maintenance_request_allowed(current) {
            return None;
        }
        let reason = current.map_or(MachineRequestRefusal::MachineStateUnknown, |state| {
            MachineRequestRefusal::MachineNotIdle { state }
        });
        let mut out = CoreOutput::default();
        out.events
            .push(Event::MachineRequestRefused { state, reason });
        Some(out)
    }

    /// Send `state` through the guard: Idle at once (dropping a held
    /// request), anything else at once when the guard is clear, else held.
    /// `user`: the user asked for it (a held or dropped request is reported).
    pub(crate) fn issue_state_request(
        &mut self,
        state: MachineState,
        user: bool,
        out: &mut CoreOutput,
    ) {
        if state == MachineState::Idle || self.guard() == Guard::Clear {
            self.request_guard.held = None;
            self.write_state_now(state, out);
        } else {
            self.request_guard.held = Some(Held {
                state,
                from: self.last_state.map(|info| info.state),
                at: self.clock,
                user,
            });
            if user {
                out.events.push(Event::MachineRequestHeld { state });
            }
        }
        self.emit_wake(out);
    }

    /// A held request goes out once the guard is clear — unless the machine
    /// has moved on or it waited longer than [`MAX_HOLD`]. Run on every tick
    /// and whenever an upload ends. (A maintenance request passed the Run-now
    /// gate when it was held; with the machine still in that state, the gate
    /// still passes, and any other state drops it here.)
    pub(crate) fn drive_held_request(&mut self, out: &mut CoreOutput) {
        if let Some(held) = self.request_guard.held {
            let current = self.last_state.map(|info| info.state);
            let clear = self.guard() == Guard::Clear;
            let drop = if current != held.from {
                current.map(|to| MachineRequestDrop::StateChanged {
                    from: held.from,
                    to,
                })
            } else if !clear && self.clock >= held.at + MAX_HOLD {
                Some(MachineRequestDrop::TookTooLong)
            } else {
                None
            };
            if let Some(reason) = drop {
                self.request_guard.held = None;
                if held.user {
                    out.events.push(Event::MachineRequestDropped {
                        state: held.state,
                        reason,
                    });
                }
            } else if clear {
                self.request_guard.held = None;
                self.write_state_now(held.state, out);
                // The profile sync waited for it — but not after a flow
                // state: the last state report still reads Idle, and an
                // upload must not chase a machine entering Steam (F7.5). The
                // next state report or tick decides.
                if !is_flow_state(held.state) {
                    self.drive_profile_sync(out);
                }
            }
        }
        self.emit_wake(out);
    }

    /// The request itself: a flow state from anything but Idle goes via Idle
    /// first (the DE1 only enters a flow state from Idle), then the state.
    pub(crate) fn write_state_now(&mut self, state: MachineState, out: &mut CoreOutput) {
        let current = self.last_state.map(|info| info.state);
        if is_flow_state(state) && current != Some(MachineState::Idle) {
            out.commands.push(Command::WriteCharacteristic {
                target: WriteTarget::De1RequestedState,
                data: vec![requested_state(MachineState::Idle)],
            });
        }
        out.commands.push(Command::WriteCharacteristic {
            target: WriteTarget::De1RequestedState,
            data: vec![requested_state(state)],
        });
    }

    /// A request is waiting out the guard.
    pub(crate) fn request_held(&self) -> bool {
        self.request_guard.held.is_some()
    }

    /// The link is gone: a held request is for a machine that is no longer
    /// there.
    pub(crate) fn drop_held_request(&mut self) {
        self.request_guard.held = None;
        self.request_guard.wake_sent = None;
    }

    /// The earliest time the core needs a tick for.
    fn next_deadline(&self) -> Option<Duration> {
        let waiting = self.request_guard.held.is_some() || self.maintenance_waits_for_guard();
        let guard = match (waiting, self.guard()) {
            (true, Guard::Until(at)) => Some(at),
            // Clear already: as soon as possible.
            (true, Guard::Clear) => Some(self.clock),
            _ => None,
        };
        // A held request's hold limit.
        let limit = self.request_guard.held.map(|held| held.at + MAX_HOLD);
        match (guard, limit) {
            (Some(a), Some(b)) => Some(a.min(b)),
            (a, b) => a.or(b),
        }
    }

    /// A tick arrived: ask again for the next deadline, even an unchanged
    /// one — the shell's timer may have fired early against the core's clock,
    /// and a request must not wait for the next regular tick.
    pub(crate) fn rearm_wake(&mut self, out: &mut CoreOutput) {
        self.request_guard.wake_sent = None;
        self.emit_wake(out);
    }

    /// Emit [`Event::WakeAt`] when the earliest deadline changed.
    pub(crate) fn emit_wake(&mut self, out: &mut CoreOutput) {
        let next = self.next_deadline();
        if next == self.request_guard.wake_sent {
            return;
        }
        self.request_guard.wake_sent = next;
        if let Some(at) = next {
            out.events.push(Event::WakeAt {
                at_ms: at.as_secs_f64() * 1000.0,
            });
        }
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use de1_domain::{BrewDefaults, blank_profile, to_wire};
    use de1_protocol::{MachineState, requested_state};

    use super::*;
    use crate::Source;

    const IDLE: [u8; 2] = [2, 0];
    const ESPRESSO: [u8; 2] = [4, 4];

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

    fn wakes(out: &CoreOutput) -> Vec<f64> {
        out.events
            .iter()
            .filter_map(|e| match e {
                Event::WakeAt { at_ms } => Some(*at_ms),
                _ => None,
            })
            .collect()
    }

    fn held(out: &CoreOutput) -> Vec<MachineState> {
        out.events
            .iter()
            .filter_map(|e| match e {
                Event::MachineRequestHeld { state } => Some(*state),
                _ => None,
            })
            .collect()
    }

    /// An idle, model-known core with a profile upload in flight (started at
    /// `at_ms`); returns the upload's writes.
    fn uploading(at_ms: u64) -> (CremaCore, CoreOutput) {
        let mut core = CremaCore::new_with_model(1);
        let _ = core.on_notification(Source::De1State, &IDLE, at_ms);
        let profile = to_wire(&blank_profile(&BrewDefaults::default()));
        let upload = core
            .upload_profile(&profile, Duration::from_millis(at_ms))
            .unwrap();
        assert!(core.profile_upload_in_progress());
        (core, upload)
    }

    /// Ack every write of `upload` at `at_ms`: the upload completes then.
    fn complete(core: &mut CremaCore, upload: &CoreOutput, at_ms: u64) -> CoreOutput {
        let mut last = CoreOutput::default();
        for c in &upload.commands {
            if let Command::WriteCharacteristic { target, data } = c {
                let source = match target {
                    WriteTarget::De1ProfileHeader => Source::De1HeaderAck,
                    WriteTarget::De1ProfileFrame => Source::De1FrameAck,
                    _ => continue,
                };
                last = core.on_notification(source, data, at_ms);
            }
        }
        assert!(!core.profile_upload_in_progress());
        last
    }

    /// AC-F16.2: a request during an upload waits for it and the 500 ms
    /// after; one inside the window waits for the rest of it. The core asks
    /// for the tick that releases it (AC-F16.1).
    #[test]
    fn f16_2_requests_wait_out_the_profile_download_guard() {
        let (mut core, upload) = uploading(1_000);
        let out = core.request_machine_state(MachineState::Steam);
        assert_eq!(
            state_writes(&out),
            Vec::<u8>::new(),
            "held during the upload"
        );
        assert_eq!(held(&out), vec![MachineState::Steam]);
        assert_eq!(
            wakes(&out),
            vec![4_000.0],
            "its hold limit, while it uploads"
        );
        let done = complete(&mut core, &upload, 1_200);
        assert_eq!(wakes(&done), vec![1_700.0], "the guard's end");
        assert!(state_writes(&core.on_tick(1_699)).is_empty());
        assert_eq!(
            state_writes(&core.on_tick(1_700)),
            vec![requested_state(MachineState::Steam)]
        );
        // Inside the window of a later upload's completion.
        let (mut core, upload) = uploading(5_000);
        let _ = complete(&mut core, &upload, 5_100);
        let _ = core.on_tick(5_300);
        let out = core.request_machine_state(MachineState::HotWater);
        assert!(state_writes(&out).is_empty());
        assert_eq!(
            state_writes(&core.on_tick(5_600)),
            vec![requested_state(MachineState::HotWater)]
        );
        // Past the window: at once.
        let out = core.request_machine_state(MachineState::Steam);
        assert_eq!(
            state_writes(&out),
            vec![requested_state(MachineState::Steam)]
        );
        assert!(held(&out).is_empty());
    }

    /// AC-F16.2: the latest held request wins, and the wake hint is not
    /// repeated for the same deadline.
    #[test]
    fn f16_2_a_newer_request_replaces_a_held_one() {
        let (mut core, upload) = uploading(1_000);
        let _ = complete(&mut core, &upload, 1_100);
        let first = core.request_machine_state(MachineState::Steam);
        let second = core.request_machine_state(MachineState::HotWater);
        assert!(state_writes(&first).is_empty() && state_writes(&second).is_empty());
        assert!(wakes(&second).is_empty(), "same deadline, no second hint");
        assert_eq!(
            state_writes(&core.on_tick(1_600)),
            vec![requested_state(MachineState::HotWater)]
        );
        assert!(state_writes(&core.on_tick(2_600)).is_empty(), "sent once");
    }

    /// AC-F16.2 (lead's guarantee): Idle is never held — not behind an
    /// upload, not behind the window — and drops a held request, also when a
    /// group-head shot started during the upload.
    #[test]
    fn f16_2_idle_is_never_held() {
        let (mut core, upload) = uploading(1_000);
        let _ = core.request_machine_state(MachineState::Steam);
        // A shot started at the group head while the profile uploads.
        let _ = core.on_notification(Source::De1State, &ESPRESSO, 1_050);
        let out = core.request_machine_state(MachineState::Idle);
        assert_eq!(
            state_writes(&out),
            vec![requested_state(MachineState::Idle)],
            "Stop goes out at once"
        );
        assert!(held(&out).is_empty());
        let _ = complete(&mut core, &upload, 1_100);
        assert!(
            state_writes(&core.on_tick(1_700)).is_empty(),
            "the held Steam was dropped"
        );
        // Inside the window too.
        let (mut core, upload) = uploading(3_000);
        let _ = complete(&mut core, &upload, 3_100);
        let out = core.request_machine_state(MachineState::Idle);
        assert_eq!(
            state_writes(&out),
            vec![requested_state(MachineState::Idle)]
        );
    }

    /// A held request is for this link: a drop discards it.
    #[test]
    fn a_link_drop_discards_a_held_request() {
        let (mut core, upload) = uploading(1_000);
        let _ = complete(&mut core, &upload, 1_100);
        let _ = core.request_machine_state(MachineState::Steam);
        let _ = core.de1_link_lost();
        let _ = core.on_notification(Source::De1State, &IDLE, 1_200);
        assert!(state_writes(&core.on_tick(2_000)).is_empty());
    }

    /// An upload that fails leaves nothing to wait for: the held request goes
    /// out on the tick asked for at once.
    #[test]
    fn a_failed_upload_releases_a_held_request() {
        let (mut core, _upload) = uploading(1_000);
        let _ = core.request_machine_state(MachineState::Steam);
        let out = core.cancel_profile_upload();
        assert_eq!(wakes(&out), vec![1_000.0], "a tick now");
        assert_eq!(
            state_writes(&core.on_tick(1_001)),
            vec![requested_state(MachineState::Steam)]
        );
    }

    /// AC-F16.4: a maintenance cycle only from Idle or Sleep; otherwise a
    /// refusal and nothing written.
    #[test]
    fn f16_4_maintenance_requests_need_idle_or_sleep() {
        let mut core = CremaCore::new_with_model(1);
        for state in [
            MachineState::Descale,
            MachineState::Clean,
            MachineState::AirPurge,
            MachineState::SteamRinse,
        ] {
            let out = core.request_machine_state(state);
            assert!(out.commands.is_empty(), "{state:?}");
            assert!(out.events.contains(&Event::MachineRequestRefused {
                state,
                reason: MachineRequestRefusal::MachineStateUnknown
            }));
        }
        let _ = core.on_notification(Source::De1State, &ESPRESSO, 1_000);
        let out = core.request_machine_state(MachineState::SteamRinse);
        assert!(out.commands.is_empty());
        assert!(out.events.contains(&Event::MachineRequestRefused {
            state: MachineState::SteamRinse,
            reason: MachineRequestRefusal::MachineNotIdle {
                state: MachineState::Espresso
            }
        }));
        // Idle and Sleep are allowed (SteamRinse is a plain write).
        for packet in [IDLE, [0, 0]] {
            let _ = core.on_notification(Source::De1State, &packet, 2_000);
            let out = core.request_machine_state(MachineState::SteamRinse);
            assert!(
                state_writes(&out).contains(&requested_state(MachineState::SteamRinse)),
                "{packet:?}"
            );
        }
        // Other requests are not gated.
        let _ = core.on_notification(Source::De1State, &ESPRESSO, 3_000);
        assert!(
            !core
                .request_machine_state(MachineState::Idle)
                .commands
                .is_empty()
        );
    }

    /// The profile sync starts no upload while a request is held: the request
    /// goes first.
    #[test]
    fn f16_2_no_upload_starts_while_a_request_is_held() {
        let (mut core, upload) = uploading(1_000);
        let _ = complete(&mut core, &upload, 1_100);
        let _ = core.request_machine_state(MachineState::Sleep);
        let _ = core.connect_sweep(&de1_domain::ConnectSweepSettings::default(), 1_200);
        let profile = blank_profile(&BrewDefaults::default());
        let out = core.set_desired_profile(
            Some(&profile),
            &de1_domain::BrewOverrides::default(),
            Duration::from_millis(1_300),
        );
        assert!(!core.profile_upload_in_progress(), "{:?}", out.events);
        let out = core.on_tick(1_600);
        assert_eq!(state_writes(&out)[0], requested_state(MachineState::Sleep));
        assert!(core.profile_upload_in_progress(), "then the sync uploads");
    }

    /// Validation P1 (F16a): a request held from Idle is dropped, not sent,
    /// when the machine has left Idle by the release — a shot started at the
    /// group head must not be bounced through Idle. One case per state.
    #[test]
    fn f16_2_a_held_request_never_stops_a_group_head_shot() {
        for state in [
            MachineState::Steam,
            MachineState::HotWater,
            MachineState::HotWaterRinse,
            MachineState::Sleep,
        ] {
            let (mut core, upload) = uploading(1_000);
            let out = core.request_machine_state(state);
            assert!(state_writes(&out).is_empty(), "{state:?} held");
            // The user presses Espresso at the group head.
            let _ = core.on_notification(Source::De1State, &ESPRESSO, 1_100);
            let done = complete(&mut core, &upload, 1_200);
            let mut writes = state_writes(&done);
            let release = core.on_tick(1_700);
            writes.extend(state_writes(&release));
            writes.extend(state_writes(&core.on_tick(5_000)));
            assert!(
                writes.is_empty(),
                "{state:?}: nothing written, got {writes:?}"
            );
            assert!(
                release.events.contains(&Event::MachineRequestDropped {
                    state,
                    reason: MachineRequestDrop::StateChanged {
                        from: Some(MachineState::Idle),
                        to: MachineState::Espresso,
                    },
                }),
                "{state:?}: {:?}",
                release.events
            );
        }
    }

    /// Validation P2 (F16a): a request is held at most 3 s; then it is dropped
    /// and the user told, not sent by surprise later.
    #[test]
    fn f16_2_a_long_hold_is_dropped_after_3_s() {
        let (mut core, upload) = uploading(1_000);
        let _ = core.request_machine_state(MachineState::Steam);
        assert!(
            core.on_tick(3_999)
                .events
                .iter()
                .all(|e| !matches!(e, Event::MachineRequestDropped { .. }))
        );
        let out = core.on_tick(4_000);
        assert!(out.events.contains(&Event::MachineRequestDropped {
            state: MachineState::Steam,
            reason: MachineRequestDrop::TookTooLong,
        }));
        let _ = complete(&mut core, &upload, 9_000);
        assert!(
            state_writes(&core.on_tick(10_000)).is_empty(),
            "never sent later"
        );
    }

    /// Validation P3 (F16a): a tick before the deadline asks for it again, so a
    /// shell timer that fired early can't strand a held request.
    #[test]
    fn an_early_tick_asks_for_the_deadline_again() {
        let (mut core, upload) = uploading(1_000);
        let _ = complete(&mut core, &upload, 1_100);
        let first = core.request_machine_state(MachineState::Steam);
        assert_eq!(wakes(&first), vec![1_600.0]);
        assert_eq!(wakes(&core.on_tick(1_500)), vec![1_600.0], "asked again");
        assert_eq!(
            state_writes(&core.on_tick(1_600)),
            vec![requested_state(MachineState::Steam)]
        );
    }

    /// The core's own requests (a shot start's, the purge's) are not reported
    /// to the user as held or dropped.
    #[test]
    fn only_the_users_requests_are_reported() {
        let (mut core, _upload) = uploading(1_000);
        let mut out = CoreOutput::default();
        core.issue_state_request(MachineState::HotWaterRinse, false, &mut out);
        assert!(held(&out).is_empty());
        let _ = core.on_notification(Source::De1State, &ESPRESSO, 1_100);
        let out = core.on_tick(1_200);
        assert!(
            !out.events
                .iter()
                .any(|e| matches!(e, Event::MachineRequestDropped { .. }))
        );
        assert!(state_writes(&out).is_empty());
    }

    /// Validation P2 (F16): releasing a held flow request starts no profile
    /// upload in the same output — the last report still reads Idle, and the
    /// header must not chase a machine entering Steam (F7.5).
    #[test]
    fn releasing_a_flow_request_starts_no_upload_with_it() {
        use de1_domain::{BrewOverrides, ConnectSweepSettings};
        let mut core = CremaCore::new_with_model(1);
        let _ = core.on_notification(Source::De1State, &IDLE, 1_000);
        let _ = core.connect_sweep(&ConnectSweepSettings::default(), 1_000);
        let p = blank_profile(&BrewDefaults::default());
        let upload = core.set_desired_profile(
            Some(&p),
            &BrewOverrides::default(),
            Duration::from_millis(1_100),
        );
        assert!(core.profile_upload_in_progress());
        let _ = core.request_machine_state(MachineState::Steam); // held
        // The dial moves: a new desired profile, waiting for the upload.
        let dialled = BrewOverrides {
            brew_temp: Some(p.segments[0].temp + 1.0),
            ..BrewOverrides::default()
        };
        let _ = core.set_desired_profile(Some(&p), &dialled, Duration::from_millis(1_200));
        let done = complete(&mut core, &upload, 1_300);
        assert!(!uploads_header(&done), "the held request goes first");
        let release = core.on_tick(1_800);
        assert_eq!(
            state_writes(&release),
            vec![requested_state(MachineState::Steam)]
        );
        assert!(!uploads_header(&release), "no header chasing Steam");
    }

    fn uploads_header(out: &CoreOutput) -> bool {
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
}
