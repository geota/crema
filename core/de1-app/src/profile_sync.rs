//! Keeping the DE1's profile in step with the one the user picked — reaprime's
//! `WorkflowDeviceSync`, owned by the core so both shells run one model.
//!
//! The shell says what it wants on the machine with
//! [`CremaCore::set_desired_profile`]: the active library profile plus the
//! Quick Controls overrides, on every activation, edit, override change and
//! restore. The core remembers the fingerprint of the effective profile it
//! last pushed and uploads (through [`CremaCore::upload_crema_profile`]) when
//! the two differ:
//!
//! - **Connection generation.** The link session is shared with the packet
//!   sync ([`crate::packet_sync`]). A link drop ([`CremaCore::de1_link_lost`])
//!   or teardown ([`CremaCore::reset`]) starts a new generation, forgets what was
//!   pushed and cancels any retry. A completion or failure of an upload from
//!   an older generation is ignored.
//! - **Every connect.** The first on-ready connect sweep of a generation
//!   ([`CremaCore::connect_sweep`]) settles the connection: what was pushed is
//!   forgotten and the desired profile is uploaded, whatever an earlier
//!   connection sent — the DE1 may have been power-cycled or changed by
//!   another app. Within one connection an unchanged profile is never sent
//!   twice.
//! - **Coalescing.** A change while an upload runs waits for it; the latest
//!   desired profile goes out when it finishes.
//! - **Retry.** A failed upload is retried after 3 s, 10 s, then every 30 s
//!   ([`RETRY_DELAYS`]), driven by [`CremaCore::on_tick`]. The first failure
//!   of a streak raises one [`Event::ProfileSyncWarning`]; a success (or a
//!   disconnect) clears it. A profile the core rejects outright (too many
//!   steps) is not retried until the desired profile changes.
//! - **Busy.** Nothing is uploaded while the machine is doing something (a
//!   shot, steam, hot water, a flush, a maintenance cycle the core is
//!   following) or its state is not known yet — after a drop, until this
//!   connection's first `StateInfo`: the upload waits for Idle / Sleep
//!   (Decenza `uploadCurrentProfile` blocks active phases the same way).
//! - **A drop ends the upload.** An upload running when the link drops
//!   cannot finish, so [`CremaCore::de1_link_lost`] fails it at once
//!   (`Aborted`, ignored by the sync as an older generation's) and the
//!   reconnect uploads without waiting for its ack timeout.
//! - **Never persisted.** Nothing here survives the process: after a restart
//!   the next connect uploads.
//! - **Hub only.** A read-only mirror core (a remote) never uploads.
//!
//! [`Event::ProfileSyncHolds`] reports each sync upload that landed, with the
//! fingerprint the DE1 now holds; a shot start waits for its own.

use std::time::Duration;

use de1_domain::{BrewOverrides, CremaProfile};
use de1_protocol::{MachineState, StateInfo};
use typeshare::typeshare;

use crate::{CoreOutput, CremaCore, Event, ms_to_duration};

/// Waits before the retries of a failed sync upload: the first three
/// failures, then the last value for every later one (reaprime
/// `WorkflowDeviceSync.retryDelays`).
pub const RETRY_DELAYS: [Duration; 3] = [
    Duration::from_secs(3),
    Duration::from_secs(10),
    Duration::from_secs(30),
];

/// The profile sync's state, as a shell sees it.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileSyncStatus {
    /// Fingerprint of the effective profile the shell wants on the DE1, or
    /// `None` with no active profile.
    pub desired: Option<String>,
    /// Fingerprint of what this connection last pushed successfully, or
    /// `None` (nothing yet, or what the DE1 holds is unknown).
    pub last_pushed: Option<String>,
    /// A sync upload is running.
    pub uploading: bool,
    /// A failed upload is waiting for its retry.
    pub retrying: bool,
}

impl ProfileSyncStatus {
    /// The DE1 holds the desired profile.
    #[must_use]
    pub fn holds_desired(&self) -> bool {
        self.desired.is_some() && self.desired == self.last_pushed
    }
}

/// What [`CremaCore::set_desired_profile_json`] returns: the output (an upload
/// it started) and the sync's status after it.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct ProfileSyncOutput {
    /// Events and BLE writes.
    pub output: CoreOutput,
    /// The sync's status after the call.
    pub status: ProfileSyncStatus,
}

/// The profile the shell wants on the DE1.
#[derive(Debug, Clone)]
struct Desired {
    profile: CremaProfile,
    qc: BrewOverrides,
    fingerprint: String,
}

/// Which sync upload an upload is: carried on the core's in-flight upload so
/// its completion or failure is attributed to the generation that sent it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct SyncTag {
    generation: u32,
    fingerprint: String,
}

/// The sans-IO decisions; [`CremaCore`] supplies the clock, the machine's
/// readiness and the upload itself.
#[derive(Debug, Default)]
pub(crate) struct ProfileSync {
    desired: Option<Desired>,
    last_pushed: Option<String>,
    /// A `StateInfo` arrived on this generation's link: the core's last
    /// state is this connection's, not one from before the drop.
    state_seen: bool,
    /// The sync upload running, if any.
    in_flight: Option<SyncTag>,
    /// Failures since the last success.
    attempt: usize,
    /// When the next retry is due.
    retry_at: Option<Duration>,
    /// A [`Event::ProfileSyncWarning`] is raised.
    warned: bool,
    /// A desired fingerprint the core rejected outright — not retried.
    rejected: Option<String>,
}

impl ProfileSync {
    fn status(&self) -> ProfileSyncStatus {
        ProfileSyncStatus {
            desired: self.desired.as_ref().map(|d| d.fingerprint.clone()),
            last_pushed: self.last_pushed.clone(),
            uploading: self.in_flight.is_some(),
            retrying: self.retry_at.is_some(),
        }
    }

    /// The desired library profile and its overrides, when this connection
    /// pushed it and the DE1 still holds it — what a shot starting now runs.
    pub(crate) fn held_profile(&self) -> Option<(&CremaProfile, &BrewOverrides)> {
        let desired = self.desired.as_ref()?;
        (self.last_pushed.as_deref() == Some(desired.fingerprint.as_str()))
            .then_some((&desired.profile, &desired.qc))
    }

    /// The desired library profile and its overrides, if any.
    fn desired_profile(&self) -> Option<(&CremaProfile, &BrewOverrides)> {
        self.desired.as_ref().map(|d| (&d.profile, &d.qc))
    }

    /// A new desired profile. A changed fingerprint starts a fresh attempt
    /// count (reaprime `_onChange`).
    fn set_desired(&mut self, desired: Option<Desired>) {
        let changed = self.desired.as_ref().map(|d| &d.fingerprint)
            != desired.as_ref().map(|d| &d.fingerprint);
        self.desired = desired;
        if changed {
            self.attempt = 0;
            self.retry_at = None;
            self.rejected = None;
        }
    }

    /// The link is gone: a new generation. Returns whether a raised warning
    /// must be cleared.
    fn disconnected(&mut self) -> bool {
        self.state_seen = false;
        self.last_pushed = None;
        self.in_flight = None;
        self.attempt = 0;
        self.retry_at = None;
        self.rejected = None;
        std::mem::take(&mut self.warned)
    }

    /// This generation's link just settled (its first on-ready sweep); a
    /// second sweep on the same connection does not re-upload.
    fn settled(&mut self) {
        self.last_pushed = None;
        self.attempt = 0;
        self.retry_at = None;
    }

    /// A `StateInfo` arrived. Returns whether it is the first of this
    /// generation.
    fn state_seen(&mut self) -> bool {
        !std::mem::replace(&mut self.state_seen, true)
    }

    /// The desired profile to upload now, if one is due and the machine can
    /// take it (`ready`).
    fn due(&self, now: Duration, ready: bool, settled: bool) -> Option<Desired> {
        let desired = self.desired.as_ref()?;
        let due = settled
            && self.state_seen
            && ready
            && self.in_flight.is_none()
            && self.last_pushed.as_ref() != Some(&desired.fingerprint)
            && self.rejected.as_ref() != Some(&desired.fingerprint)
            && self.retry_at.is_none_or(|at| now >= at);
        due.then(|| desired.clone())
    }

    /// A sync upload of `fingerprint` started: its tag.
    fn started(&mut self, fingerprint: String, generation: u32) -> SyncTag {
        let tag = SyncTag {
            generation,
            fingerprint,
        };
        self.retry_at = None;
        self.in_flight = Some(tag.clone());
        tag
    }

    /// Any upload started: what the DE1 holds is about to change.
    fn upload_started(&mut self) {
        self.last_pushed = None;
    }

    /// An upload completed. `tag` is the sync's (or `None` for another
    /// upload: the cold-maintenance profile). Returns whether a raised
    /// warning must be cleared and, for a sync upload of this generation,
    /// the fingerprint the DE1 now holds.
    fn completed(&mut self, tag: Option<SyncTag>, generation: u32) -> (bool, Option<String>) {
        let Some(tag) = tag else {
            // Something else is on the machine now.
            self.last_pushed = None;
            return (false, None);
        };
        if tag.generation != generation {
            return (false, None);
        }
        self.in_flight = None;
        self.attempt = 0;
        self.retry_at = None;
        self.last_pushed = Some(tag.fingerprint.clone());
        (std::mem::take(&mut self.warned), Some(tag.fingerprint))
    }

    /// A sync upload of `tag` failed at `now`: schedule the retry. Returns
    /// whether a warning must be raised (the first failure of a streak).
    /// Another generation's failure is ignored.
    fn failed(&mut self, tag: &SyncTag, now: Duration, generation: u32) -> bool {
        if tag.generation != generation {
            return false;
        }
        self.in_flight = None;
        self.last_pushed = None;
        let delay = RETRY_DELAYS[self.attempt.min(RETRY_DELAYS.len() - 1)];
        self.attempt = self.attempt.saturating_add(1);
        self.retry_at = Some(now + delay);
        !std::mem::replace(&mut self.warned, true)
    }

    /// The core refused `fingerprint` outright (validation): no retries until
    /// the desired profile changes. Returns whether a warning must be raised.
    fn rejected(&mut self, fingerprint: String) -> bool {
        self.rejected = Some(fingerprint);
        !std::mem::replace(&mut self.warned, true)
    }

    /// A foreign upload replaced the running sync upload before it finished.
    fn superseded(&mut self) {
        self.in_flight = None;
        self.last_pushed = None;
    }
}

/// The machine states a profile may be written in: nothing is running.
fn idle_enough(state: MachineState) -> bool {
    matches!(
        state,
        MachineState::Idle | MachineState::Sleep | MachineState::SchedIdle
    )
}

impl CremaCore {
    /// Tell the core which profile the DE1 should hold: the library
    /// `profile` (`None` = no active profile) with the Quick Controls
    /// overrides `qc`, as [`upload_crema_profile`](Self::upload_crema_profile)
    /// applies them. Called on every activation, edit, override change and
    /// restore; uploads at once when the connection is settled, the machine
    /// is idle and the DE1 does not hold it yet (see the module docs).
    pub fn set_desired_profile(
        &mut self,
        profile: Option<&CremaProfile>,
        qc: &BrewOverrides,
        now: Duration,
    ) -> CoreOutput {
        self.clock = self.clock.max(now);
        let desired = profile.map(|p| {
            let qc = de1_domain::normalize_brew_overrides(p, qc);
            Desired {
                fingerprint: de1_domain::effective_profile_fingerprint(p, &qc).unwrap_or_default(),
                profile: p.clone(),
                qc,
            }
        });
        self.profile_sync.set_desired(desired);
        let mut out = CoreOutput::default();
        // The packet's group target follows the profile (F5.3); it goes
        // before any upload, as in the connect sweep.
        self.drive_shot_settings(&mut out, false);
        self.drive_profile_sync(&mut out);
        self.gate_read_only(out)
    }

    /// The JSON face of [`set_desired_profile`](Self::set_desired_profile)
    /// both bridges export: a library `CremaProfile` JSON (`None` = no active
    /// profile) and an optional `BrewOverrides` JSON. A malformed payload
    /// comes back as one [`Event::DecodeError`] and changes nothing.
    pub fn set_desired_profile_json(
        &mut self,
        profile_json: Option<&str>,
        qc_json: Option<&str>,
        now_ms: u64,
    ) -> ProfileSyncOutput {
        let parsed = profile_json
            .map(serde_json::from_str::<CremaProfile>)
            .transpose()
            .map_err(|e| format!("profile JSON parse failed: {e}"))
            .and_then(|p| {
                de1_domain::parse_brew_overrides(qc_json)
                    .map(|qc| (p, qc))
                    .map_err(|e| format!("overrides JSON parse failed: {e}"))
            });
        let output = match parsed {
            Ok((profile, qc)) => {
                self.set_desired_profile(profile.as_ref(), &qc, ms_to_duration(now_ms))
            }
            Err(message) => {
                let mut out = CoreOutput::default();
                out.events.push(Event::DecodeError { message });
                out
            }
        };
        ProfileSyncOutput {
            output,
            status: self.profile_sync_status(),
        }
    }

    /// The profile sync's state — whether the DE1 holds the desired profile,
    /// an upload is running or a retry is waiting.
    pub fn profile_sync_status(&self) -> ProfileSyncStatus {
        self.profile_sync.status()
    }

    /// The link is gone (a drop or a teardown): a new link generation for
    /// both syncs (see the module docs and [`crate::packet_sync`]).
    pub(crate) fn link_lost_session(&mut self, out: &mut CoreOutput) {
        self.link.generation = self.link.generation.wrapping_add(1);
        self.link.settled = false;
        self.packet_sync_new_generation();
        if self.profile_sync.disconnected() {
            out.events.push(Event::ProfileSyncWarning { active: false });
        }
    }

    /// A `StateInfo` notification arrived, before
    /// [`handle_state`](Self::handle_state) records it. The first of a
    /// generation makes the machine's state known again after a drop; when
    /// it repeats the state from before the drop (no change for
    /// `handle_state` to act on) a deferred upload is driven here.
    pub(crate) fn profile_sync_state_seen(&mut self, info: StateInfo, out: &mut CoreOutput) {
        if self.profile_sync.state_seen() && self.last_state == Some(info) {
            self.drive_profile_sync(out);
        }
    }

    /// Settle this generation's link: the on-ready connect sweep calls it
    /// before its writes, so the packet sync lets the sweep's packet
    /// through. Returns whether the link just settled (its first sweep).
    pub(crate) fn settle_link(&mut self) -> bool {
        !std::mem::replace(&mut self.link.settled, true)
    }

    /// The on-ready sweep's last step: a link that just settled forgets what
    /// was pushed, then the desired profile goes out after every setting.
    pub(crate) fn profile_sync_settled(&mut self, newly_settled: bool, out: &mut CoreOutput) {
        if newly_settled {
            self.profile_sync.settled();
        }
        self.drive_profile_sync(out);
    }

    /// Any upload is starting: what the DE1 holds is changing. A running sync
    /// upload it replaces (the cold-maintenance profile) is not a failure:
    /// the sync re-uploads once that run is over.
    pub(crate) fn profile_sync_upload_starting(&mut self) {
        if self.profile_sync.in_flight.is_some() {
            if let Some(upload) = self.profile_upload.as_mut() {
                upload.sync = None;
            }
            if let Some(parked) = self.parked_upload.as_mut() {
                parked.sync = None;
            }
            self.profile_sync.superseded();
        }
        self.profile_sync.upload_started();
    }

    /// An upload completed; `tag` says whether it was a sync upload.
    pub(crate) fn profile_sync_upload_completed(
        &mut self,
        tag: Option<SyncTag>,
        out: &mut CoreOutput,
    ) {
        let (clear_warning, holds) = self.profile_sync.completed(tag, self.link.generation);
        if clear_warning {
            out.events.push(Event::ProfileSyncWarning { active: false });
        }
        if let Some(fingerprint) = holds {
            out.events.push(Event::ProfileSyncHolds { fingerprint });
        }
        // A change that arrived meanwhile goes out now.
        self.drive_profile_sync(out);
    }

    /// The group target the steam / hot-water packet carries: the effective
    /// desired profile's brew temperature (its first step after the Quick
    /// Controls shift). de1app sends `espresso_temperature`; Decenza sends the
    /// profile's temperature, or the override. `None` with no desired profile.
    pub(crate) fn profile_group_temp_c(&self) -> Option<f32> {
        self.profile_sync.desired_profile().map(|(profile, qc)| {
            de1_domain::brew_temp_anchor(&de1_domain::effective_crema_profile(profile, qc))
        })
    }

    /// An upload failed; `tag` says whether it was a sync upload.
    pub(crate) fn profile_sync_upload_failed(
        &mut self,
        tag: Option<SyncTag>,
        out: &mut CoreOutput,
    ) {
        match tag {
            Some(tag) => {
                if self
                    .profile_sync
                    .failed(&tag, self.clock, self.link.generation)
                {
                    out.events.push(Event::ProfileSyncWarning { active: true });
                }
            }
            // Another upload (the cold profile) failed: the DE1 holds an
            // unknown mix, so the desired profile goes back once it can.
            None => self.profile_sync.upload_started(),
        }
    }

    /// Upload the desired profile if one is due — see the module docs. Run
    /// after everything that can make one due: a desired change, the connect
    /// sweep, a state change, a finished upload, and every tick (retries).
    pub(crate) fn drive_profile_sync(&mut self, out: &mut CoreOutput) {
        let ready = !self.read_only
            && !self.firmware_locks_writes()
            && self.profile_upload.is_none()
            && self.parked_upload.is_none()
            && self.maintenance.is_none()
            && self.last_state.is_some_and(|s| idle_enough(s.state));
        let Some(desired) = self.profile_sync.due(self.clock, ready, self.link.settled) else {
            return;
        };
        match self.upload_crema_profile(&desired.profile, &desired.qc, self.clock) {
            Ok((upload, fingerprint)) => {
                out.events.extend(upload.events);
                out.commands.extend(upload.commands);
                if self.profile_upload_in_progress() {
                    let tag = self.profile_sync.started(fingerprint, self.link.generation);
                    if let Some(running) = self.profile_upload.as_mut() {
                        running.sync = Some(tag.clone());
                    }
                    if let Some(parked) = self.parked_upload.as_mut() {
                        parked.sync = Some(tag);
                    }
                }
            }
            Err(err) => {
                out.events.push(Event::ProfileUploadFailed {
                    reason: crate::ProfileUploadFailure::from(err),
                });
                if self.profile_sync.rejected(desired.fingerprint) {
                    out.events.push(Event::ProfileSyncWarning { active: true });
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tag(generation: u32, fp: &str) -> SyncTag {
        SyncTag {
            generation,
            fingerprint: fp.to_owned(),
        }
    }

    fn desired(fp: &str) -> Desired {
        let profile = de1_domain::blank_profile(&de1_domain::BrewDefaults::default());
        Desired {
            profile,
            qc: BrewOverrides::default(),
            fingerprint: fp.to_owned(),
        }
    }

    /// AC-F7.3 (model half): a completion or failure from an older
    /// generation changes nothing.
    #[test]
    fn f7_3_stale_generation_results_are_ignored() {
        let mut sync = ProfileSync::default();
        sync.settled();
        sync.set_desired(Some(desired("a")));
        let old = sync.started("a".into(), 0);
        assert!(!sync.disconnected());
        sync.settled();
        assert_eq!(sync.completed(Some(old.clone()), 1), (false, None));
        assert_eq!(sync.last_pushed, None);
        assert!(!sync.failed(&old, Duration::ZERO, 1));
        assert_eq!(sync.retry_at, None);
        // The new generation's own upload counts.
        let new = sync.started("a".into(), 1);
        assert_eq!(sync.completed(Some(new), 1), (false, Some("a".into())));
        assert_eq!(sync.last_pushed.as_deref(), Some("a"));
        let _ = tag(0, "x");
    }

    /// AC-HW10b (R2): an upload result tagged before a `reset()` is
    /// ignored — the generation it carries is never reused.
    #[test]
    fn stale_result_after_reset_ignored() {
        let mut core = crate::CremaCore::new_with_model(1);
        let _ = core.on_notification(crate::Source::De1State, &[2, 0], 0);
        let profile = de1_domain::blank_profile(&de1_domain::BrewDefaults::default());
        let _ = core.set_desired_profile(Some(&profile), &BrewOverrides::default(), Duration::ZERO);
        let _ = core.connect_sweep(&de1_domain::ConnectSweepSettings::default(), 10);
        let tag = core
            .profile_upload
            .as_ref()
            .and_then(|u| u.sync.clone())
            .expect("a sync upload runs");
        let _ = core.reset();
        let mut out = CoreOutput::default();
        core.profile_sync_upload_completed(Some(tag), &mut out);
        assert!(
            !out.events
                .iter()
                .any(|e| matches!(e, Event::ProfileSyncHolds { .. }))
        );
        assert_eq!(core.profile_sync_status().last_pushed, None);
    }

    /// AC-F7.4 (model half): 3 s, 10 s, 30 s, then 30 s; one warning per streak.    /// AC-F7.4 (model half): 3 s, 10 s, 30 s, then 30 s; one warning per streak.
    #[test]
    fn f7_4_retry_backoff_schedule() {
        let mut sync = ProfileSync::default();
        sync.settled();
        assert!(sync.state_seen(), "the first state of the generation");
        assert!(!sync.state_seen(), "only the first");
        sync.set_desired(Some(desired("a")));
        let mut now = Duration::ZERO;
        let mut warnings = 0;
        for expected in [3, 10, 30, 30, 30] {
            let t = sync.started("a".into(), 0);
            if sync.failed(&t, now, 0) {
                warnings += 1;
            }
            let at = sync.retry_at.expect("a retry is scheduled");
            assert_eq!(at - now, Duration::from_secs(expected));
            assert!(
                sync.due(at - Duration::from_millis(1), true, true)
                    .is_none()
            );
            assert!(sync.due(at, true, true).is_some());
            now = at;
        }
        assert_eq!(warnings, 1, "one warning for the streak");
        let t = sync.started("a".into(), 0);
        assert_eq!(
            sync.completed(Some(t), 0),
            (true, Some("a".into())),
            "success clears it"
        );
    }
}
