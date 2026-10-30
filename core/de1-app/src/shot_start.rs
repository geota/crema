//! The app's shot start, the post-steam auto-purge and the group-head banner
//! (F16b). Both shells used to run the start sequence themselves
//! (`ShotStartSequence.kt`, `start-sequence.ts`); every input was already a
//! core event, so the sequence lives here, like
//! [`maintenance_run`](crate::maintenance_run).
//!
//! **Start** ([`CremaCore::start_shot`]). Refused at once — nothing is
//! written — when a start is pending, a shot is running, the profile has more
//! steps than the DE1 holds, require-scale is on with no scale for a shot that
//! stops on weight, a firmware flash holds the link, or no machine state is
//! known. Otherwise it is accepted and runs:
//!
//! 1. **Sync**: the profile with the Quick Controls overrides becomes the
//!    desired profile (F7); the start waits for [`Event::ProfileSyncHolds`] of
//!    its fingerprint, at most [`START_UPLOAD_TIMEOUT`].
//! 2. **Pre-flush** (the `pre_flush` setting): a `HotWaterRinse`, then our
//!    flush's end, at most [`PRESHOT_FLUSH_TIMEOUT`]. A flush that ended
//!    outside Idle (a group-head button took the machine elsewhere) does not
//!    start the shot.
//! 3. **Espresso**: requested, then [`ESPRESSO_ENTRY_TIMEOUT`] for the machine
//!    to report it (a GHC-only machine ignores app starts).
//!
//! Each request goes through the profile-download guard
//! ([`machine_requests`](crate::machine_requests)). A user's request
//! ([`CremaCore::request_machine_state`]) cancels the start, as does a link
//! drop or a reset. Espresso reported before our request means the shot was
//! started at the group head: the start stands down. Progress is reported as
//! [`Event::ShotStartProgress`] and the end as [`Event::ShotStartEnded`]; a
//! shell shows "starting" between acceptance and the end.
//!
//! **Scale gate.** The shot's weight target is the Quick Controls yield if
//! set, else the profile's (the stop-at-weight precedence). With
//! stop-on-weight on, a target and no scale, the start is refused when
//! require-scale is on, otherwise it goes on with [`Event::StartNotice`].
//!
//! **Auto-purge** (the `steam_purge` setting): [`AUTO_PURGE_DELAY`] after a
//! steam session ends, one `HotWaterRinse` — only from Idle, with no start
//! pending and no request held, on the core that holds the DE1 (never a
//! read-only mirror). A start or a user's request disarms it.
//!
//! **Group-head banner.** When a shot starts that this core did not start, the
//! steps only an app start runs were skipped: [`Event::GroupHeadStartUnchecked`]
//! lists those that applied — no scale for a weight stop, the pre-shot flush,
//! a profile upload still pending. It never stops the shot or writes
//! anything. A read-only mirror never emits it.

use std::time::Duration;

use de1_domain::{BrewOverrides, CremaProfile, profile_bounds::MAX_PROFILE_STEPS};
use de1_protocol::MachineState;
use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::{CoreOutput, CremaCore, Event, ms_to_duration};

/// How long a start waits for the DE1 to hold its profile.
pub const START_UPLOAD_TIMEOUT: Duration = Duration::from_secs(15);
/// The ceiling on the pre-shot flush before Espresso is requested anyway (the
/// backstop when its end is missed).
pub const PRESHOT_FLUSH_TIMEOUT: Duration = Duration::from_secs(30);
/// How long a start waits for the machine to report Espresso after the
/// request.
pub const ESPRESSO_ENTRY_TIMEOUT: Duration = Duration::from_secs(5);
/// The wait before a post-steam auto-purge, so a shot started straight away
/// wins the group.
pub const AUTO_PURGE_DELAY: Duration = Duration::from_millis(1_500);

/// Why [`CremaCore::start_shot`] refused a start.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
#[non_exhaustive]
pub enum StartRefusal {
    /// A start is already pending.
    AlreadyStarting,
    /// A shot is already running.
    ShotRunning,
    /// The profile has more steps than the DE1 holds.
    TooManySteps {
        /// The profile's name.
        name: String,
        /// Its step count.
        count: u32,
    },
    /// Require-scale is on, the shot stops on weight and no scale is
    /// connected.
    ScaleRequired,
    /// A firmware flash holds the link.
    FirmwareLocked,
    /// The machine has not reported its state (not connected yet).
    NotConnected,
}

impl StartRefusal {
    /// One sentence for the user.
    #[must_use]
    pub fn message(&self) -> String {
        match self {
            Self::AlreadyStarting => "A shot is already starting".to_owned(),
            Self::ShotRunning => "A shot is already running".to_owned(),
            Self::TooManySteps { name, count } => {
                let name = if name.is_empty() {
                    "Untitled profile"
                } else {
                    name
                };
                let max = u32::from(MAX_PROFILE_STEPS);
                format!(
                    "“{name}” has {count} steps; the DE1 runs at most {max}. Remove {} in the profile editor to brew it.",
                    count.saturating_sub(max)
                )
            }
            Self::ScaleRequired => {
                "Shot not started — no scale connected, and “Require scale to start” is on"
                    .to_owned()
            }
            Self::FirmwareLocked => "Shot not started — a firmware update is running".to_owned(),
            Self::NotConnected => "Shot not started — the machine isn’t connected".to_owned(),
        }
    }
}

/// Where an accepted start stands.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[non_exhaustive]
pub enum StartPhase {
    /// Waiting for the DE1 to hold the shot's profile.
    Syncing,
    /// The pre-shot flush is running.
    Flushing,
    /// Espresso was requested; waiting for the machine to report it.
    Requested,
}

/// How a start ended.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
#[non_exhaustive]
pub enum StartEnd {
    /// The machine reported Espresso after our request.
    Started,
    /// The profile upload failed (the core keeps retrying it).
    UploadFailed,
    /// The DE1 did not confirm the profile within [`START_UPLOAD_TIMEOUT`].
    UploadTimedOut,
    /// The pre-shot flush ended outside Idle.
    FlushLeftIdle {
        /// Where the machine went.
        state: MachineState,
    },
    /// The machine did not report Espresso within [`ESPRESSO_ENTRY_TIMEOUT`].
    NotEntered,
    /// A user's request, a link drop or a reset cancelled it.
    Cancelled,
    /// The shot was started at the group head first.
    StoodDown,
}

impl StartEnd {
    /// The sentence to tell the user, when there is one.
    #[must_use]
    pub fn message(&self) -> Option<String> {
        match self {
            Self::UploadFailed => Some(
                "The profile did not upload, so the shot was not started. Try again, or reload the profile."
                    .to_owned(),
            ),
            Self::UploadTimedOut => Some("Profile upload timed out — shot not started".to_owned()),
            Self::FlushLeftIdle { .. } => {
                Some("Shot not started — the machine left the flush".to_owned())
            }
            Self::NotEntered => {
                Some("The machine didn’t start the shot — start it on the machine".to_owned())
            }
            Self::Started | Self::Cancelled | Self::StoodDown => None,
        }
    }
}

/// A non-blocking notice about a start.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[non_exhaustive]
pub enum StartCheck {
    /// The shot stops on weight but no scale is connected: the weight stop is
    /// off.
    NoScale,
    /// The pre-shot flush did not run (a group-head start).
    PreFlushSkipped,
    /// The profile upload had not finished: the machine runs the profile it
    /// held before.
    ProfilePending,
}

impl StartCheck {
    /// One sentence for the user.
    #[must_use]
    pub fn message(self) -> &'static str {
        match self {
            Self::NoScale => "No scale connected, so the weight stop is off for this shot",
            Self::PreFlushSkipped => "Started on the machine, so the pre-shot flush didn’t run",
            Self::ProfilePending => {
                "The profile hadn’t finished loading, so the machine is running the previous one"
            }
        }
    }
}

/// One line of the group-head banner.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StartNoticeLine {
    /// Which check.
    pub check: StartCheck,
    /// Its sentence.
    pub message: String,
}

impl From<StartCheck> for StartNoticeLine {
    fn from(check: StartCheck) -> Self {
        Self {
            check,
            message: check.message().to_owned(),
        }
    }
}

/// What [`CremaCore::start_shot`] returns: whether the start was accepted, why
/// not, and the output (the first requests, events).
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct StartShotResult {
    /// The start runs.
    pub accepted: bool,
    /// Why it was refused.
    pub refusal: Option<StartRefusal>,
    /// The refusal's sentence.
    pub message: Option<String>,
    /// Events and BLE writes.
    pub output: CoreOutput,
}

/// The start's settings, latched from the shell's.
#[derive(Debug, Clone, Copy, Default)]
pub(crate) struct StartSettings {
    pub(crate) pre_flush: bool,
    pub(crate) require_scale: bool,
    pub(crate) steam_purge: bool,
}

/// An accepted start.
#[derive(Debug, Clone)]
struct Start {
    phase: Phase,
}

#[derive(Debug, Clone)]
enum Phase {
    /// Waiting for the DE1 to hold `fingerprint`.
    Syncing {
        fingerprint: String,
        until: Duration,
    },
    /// Our flush was requested; `started` once it began.
    Flushing { started: bool, until: Duration },
    /// Espresso requested.
    Requested { until: Duration },
}

/// What a start's deadline does.
enum Due {
    Espresso,
    End(StartEnd),
}

/// The start, the purge and the settings.
#[derive(Debug, Default)]
pub(crate) struct ShotStarter {
    start: Option<Start>,
    purge_at: Option<Duration>,
    pub(crate) settings: StartSettings,
}

impl CremaCore {
    /// Start an espresso shot with `profile` and the Quick Controls overrides
    /// `qc` — see the module docs.
    pub fn start_shot(
        &mut self,
        profile: &CremaProfile,
        qc: &BrewOverrides,
        now: Duration,
    ) -> StartShotResult {
        self.clock = self.clock.max(now);
        let mut out = CoreOutput::default();
        if let Some(refusal) = self.start_refusal(profile, qc) {
            return StartShotResult {
                accepted: false,
                message: Some(refusal.message()),
                refusal: Some(refusal),
                output: self.gate_read_only(out),
            };
        }
        if self.stops_on_weight(profile, qc) && self.scale.is_none() {
            out.events.push(Event::StartNotice {
                notice: StartCheck::NoScale.into(),
            });
        }
        self.starter.purge_at = None;
        // The newer intent wins: a request still waiting out the guard (Steam
        // tapped during an upload) would go out and then be stopped by the
        // shot's Espresso.
        self.drop_held_request();
        // A start is the user doing something (the idle-sleep timer).
        self.idle_sleep.note_activity();
        self.starter.start = Some(Start {
            phase: Phase::Syncing {
                fingerprint: String::new(),
                until: self.clock + START_UPLOAD_TIMEOUT,
            },
        });
        let desired = self.set_desired_profile(Some(profile), qc, self.clock);
        out.events.extend(desired.events);
        out.commands.extend(desired.commands);
        let status = self.profile_sync_status();
        if status.holds_desired() {
            self.start_after_sync(&mut out);
        } else if let Some(Start {
            phase: Phase::Syncing { fingerprint, .. },
        }) = self.starter.start.as_mut()
        {
            *fingerprint = status.desired.unwrap_or_default();
            out.events.push(Event::ShotStartProgress {
                phase: StartPhase::Syncing,
            });
        }
        self.emit_wake(&mut out);
        StartShotResult {
            accepted: true,
            refusal: None,
            message: None,
            output: self.gate_read_only(out),
        }
    }

    /// [`start_shot`](Self::start_shot) from JSON (a library `CremaProfile`
    /// and an optional `BrewOverrides`), for the bridges. A malformed payload
    /// is one [`Event::DecodeError`] and no start.
    pub fn start_shot_json(
        &mut self,
        profile_json: &str,
        qc_json: Option<&str>,
        now_ms: u64,
    ) -> StartShotResult {
        let parsed = serde_json::from_str::<CremaProfile>(profile_json)
            .map_err(|e| format!("profile JSON parse failed: {e}"))
            .and_then(|p| {
                de1_domain::parse_brew_overrides(qc_json)
                    .map(|qc| (p, qc))
                    .map_err(|e| format!("overrides JSON parse failed: {e}"))
            });
        match parsed {
            Ok((profile, qc)) => self.start_shot(&profile, &qc, ms_to_duration(now_ms)),
            Err(message) => {
                let mut output = CoreOutput::default();
                output.events.push(Event::DecodeError { message });
                StartShotResult {
                    output,
                    ..StartShotResult::default()
                }
            }
        }
    }

    /// A start is pending (from acceptance to its end).
    pub fn shot_starting(&self) -> bool {
        self.starter.start.is_some()
    }

    /// Latch the start settings: the pre-shot flush, require-scale and the
    /// post-steam auto-purge. Kept across [`reset`](Self::reset).
    pub fn set_start_settings(&mut self, pre_flush: bool, require_scale: bool, steam_purge: bool) {
        self.starter.settings = StartSettings {
            pre_flush,
            require_scale,
            steam_purge,
        };
        if !steam_purge {
            self.starter.purge_at = None;
        }
    }

    /// Why a start can't be accepted now, if so.
    fn start_refusal(&self, profile: &CremaProfile, qc: &BrewOverrides) -> Option<StartRefusal> {
        let current = self.last_state.map(|info| info.state);
        if self.firmware_locks_writes() {
            Some(StartRefusal::FirmwareLocked)
        } else if self.starter.start.is_some() {
            Some(StartRefusal::AlreadyStarting)
        } else if current == Some(MachineState::Espresso) {
            Some(StartRefusal::ShotRunning)
        } else if current.is_none() {
            Some(StartRefusal::NotConnected)
        } else if profile.segments.len() > usize::from(MAX_PROFILE_STEPS) {
            Some(StartRefusal::TooManySteps {
                name: profile.name.clone(),
                count: u32::try_from(profile.segments.len()).unwrap_or(u32::MAX),
            })
        } else if self.starter.settings.require_scale
            && self.stops_on_weight(profile, qc)
            && self.scale.is_none()
        {
            Some(StartRefusal::ScaleRequired)
        } else {
            None
        }
    }

    /// Whether this shot stops on weight: stop-on-weight on and a weight
    /// target — the Quick Controls yield if set, else the profile's (the
    /// stop-at-weight precedence, `effective_target_weight`).
    fn stops_on_weight(&self, profile: &CremaProfile, qc: &BrewOverrides) -> bool {
        let positive = |g: f32| g.is_finite() && g > 0.0;
        let target = qc
            .yield_g
            .filter(|g| positive(*g))
            .or_else(|| Some(profile.yield_out).filter(|g| positive(*g)));
        self.stop_on_weight && !self.weight_target_disabled && target.is_some()
    }

    /// The DE1 holds the start's profile: the pre-flush, or Espresso.
    fn start_after_sync(&mut self, out: &mut CoreOutput) {
        if self.starter.settings.pre_flush {
            self.set_start_phase(
                Phase::Flushing {
                    started: false,
                    until: self.clock + PRESHOT_FLUSH_TIMEOUT,
                },
                out,
            );
            self.issue_state_request(MachineState::HotWaterRinse, false, out);
        } else {
            self.request_start_espresso(out);
        }
    }

    fn request_start_espresso(&mut self, out: &mut CoreOutput) {
        self.set_start_phase(
            Phase::Requested {
                until: self.clock + ESPRESSO_ENTRY_TIMEOUT,
            },
            out,
        );
        self.issue_state_request(MachineState::Espresso, false, out);
    }

    fn set_start_phase(&mut self, phase: Phase, out: &mut CoreOutput) {
        let reported = match phase {
            Phase::Syncing { .. } => StartPhase::Syncing,
            Phase::Flushing { .. } => StartPhase::Flushing,
            Phase::Requested { .. } => StartPhase::Requested,
        };
        if let Some(start) = self.starter.start.as_mut() {
            start.phase = phase;
            out.events
                .push(Event::ShotStartProgress { phase: reported });
        }
    }

    /// End the pending start with `end`.
    fn end_start(&mut self, end: StartEnd, out: &mut CoreOutput) {
        if self.starter.start.take().is_some() {
            let message = end.message();
            out.events.push(Event::ShotStartEnded { end, message });
        }
    }

    /// A user's request (or a link drop, a reset) cancels a pending start
    /// and disarms the purge.
    pub(crate) fn cancel_start(&mut self, out: &mut CoreOutput) {
        self.starter.purge_at = None;
        self.end_start(StartEnd::Cancelled, out);
    }

    /// A start is past its sync stage: the profile sync must not upload.
    pub(crate) fn start_past_sync(&self) -> bool {
        self.starter
            .start
            .as_ref()
            .is_some_and(|s| !matches!(s.phase, Phase::Syncing { .. }))
    }

    /// The profile sync reports the DE1 holds `fingerprint`.
    pub(crate) fn start_on_sync_holds(&mut self, fingerprint: &str, out: &mut CoreOutput) {
        let waiting = self.starter.start.as_ref().is_some_and(
            |s| matches!(&s.phase, Phase::Syncing { fingerprint: f, .. } if f == fingerprint),
        );
        if waiting {
            self.start_after_sync(out);
        }
    }

    /// The sync upload of `fingerprint` failed or was refused: a start waiting
    /// for that profile is over. An older upload's failure is not its own.
    pub(crate) fn start_on_sync_failed(&mut self, fingerprint: &str, out: &mut CoreOutput) {
        let waiting = self.starter.start.as_ref().is_some_and(
            |s| matches!(&s.phase, Phase::Syncing { fingerprint: f, .. } if f == fingerprint),
        );
        if waiting {
            self.end_start(StartEnd::UploadFailed, out);
        }
    }

    /// `WaterSessionStarted(Flush)`: our flush began.
    pub(crate) fn start_on_flush_started(&mut self) {
        if let Some(Start {
            phase: Phase::Flushing { started, .. },
        }) = self.starter.start.as_mut()
        {
            *started = true;
        }
    }

    /// `WaterSessionCompleted(Flush)`: our flush ended — Espresso if the
    /// machine is back in Idle, else the start is over.
    pub(crate) fn start_on_flush_completed(&mut self, out: &mut CoreOutput) {
        let ours = self
            .starter
            .start
            .as_ref()
            .is_some_and(|s| matches!(s.phase, Phase::Flushing { started: true, .. }));
        if !ours {
            return;
        }
        match self.last_state.map(|info| info.state) {
            Some(MachineState::Idle) => self.request_start_espresso(out),
            Some(state) => self.end_start(StartEnd::FlushLeftIdle { state }, out),
            None => self.end_start(StartEnd::Cancelled, out),
        }
    }

    /// The machine entered `state` (from `prev`): Espresso ends a requested
    /// start, makes an earlier one stand down, and — when this core did not
    /// start the shot — raises the group-head banner.
    pub(crate) fn start_on_state_entry(
        &mut self,
        prev: Option<MachineState>,
        state: MachineState,
        out: &mut CoreOutput,
    ) {
        if state != MachineState::Espresso || prev == Some(MachineState::Espresso) {
            return;
        }
        let requested = self
            .starter
            .start
            .as_ref()
            .is_some_and(|s| matches!(s.phase, Phase::Requested { .. }));
        if requested {
            self.end_start(StartEnd::Started, out);
            return;
        }
        self.end_start(StartEnd::StoodDown, out);
        self.starter.purge_at = None;
        if !self.read_only {
            self.group_head_banner(out);
        }
    }

    /// The checks a group-head start skipped that applied.
    fn group_head_banner(&self, out: &mut CoreOutput) {
        let mut notices = Vec::new();
        let weight_target = self.shot_target_weight.or(self.profile_target_weight);
        if self.stop_on_weight
            && !self.weight_target_disabled
            && weight_target.is_some()
            && self.scale.is_none()
        {
            notices.push(StartCheck::NoScale.into());
        }
        if self.starter.settings.pre_flush {
            notices.push(StartCheck::PreFlushSkipped.into());
        }
        let sync = self.profile_sync_status();
        if sync.desired.is_some() && !sync.holds_desired() {
            notices.push(StartCheck::ProfilePending.into());
        }
        if !notices.is_empty() {
            out.events.push(Event::GroupHeadStartUnchecked { notices });
        }
    }

    /// A steam session ended: arm the post-steam purge.
    pub(crate) fn arm_auto_purge(&mut self, out: &mut CoreOutput) {
        if self.starter.settings.steam_purge
            && !self.read_only
            && self.starter.start.is_none()
            && self.starter.purge_at.is_none()
        {
            self.starter.purge_at = Some(self.clock + AUTO_PURGE_DELAY);
            self.emit_wake(out);
        }
    }

    /// Per-tick: the start's deadlines and the purge.
    pub(crate) fn drive_shot_start(&mut self, now: Duration, out: &mut CoreOutput) {
        let due = self.starter.start.as_ref().and_then(|s| match s.phase {
            Phase::Syncing { until, .. } if now >= until => {
                Some(Due::End(StartEnd::UploadTimedOut))
            }
            // The flush ceiling: Espresso anyway (the flush's end was missed).
            Phase::Flushing { until, .. } if now >= until => Some(Due::Espresso),
            Phase::Requested { until } if now >= until => Some(Due::End(StartEnd::NotEntered)),
            _ => None,
        });
        match due {
            Some(Due::Espresso) => self.request_start_espresso(out),
            Some(Due::End(end)) => self.end_start(end, out),
            None => {}
        }
        if self.starter.purge_at.is_some_and(|at| now >= at) {
            self.starter.purge_at = None;
            let idle = self.last_state.map(|info| info.state) == Some(MachineState::Idle);
            if idle && self.starter.start.is_none() && !self.request_held() {
                self.issue_state_request(MachineState::HotWaterRinse, false, out);
            }
        }
    }

    /// The start's and the purge's next deadline, for the wake hint.
    pub(crate) fn shot_start_deadline(&self) -> Option<Duration> {
        let start = self.starter.start.as_ref().map(|s| match s.phase {
            Phase::Syncing { until, .. }
            | Phase::Flushing { until, .. }
            | Phase::Requested { until } => until,
        });
        match (start, self.starter.purge_at) {
            (Some(a), Some(b)) => Some(a.min(b)),
            (a, b) => a.or(b),
        }
    }

    /// The link is gone or the core is resetting: the start is over, the purge
    /// is off.
    pub(crate) fn abandon_shot_start(&mut self, out: &mut CoreOutput) {
        self.cancel_start(out);
    }
}

#[cfg(test)]
mod tests {
    use de1_domain::{BrewDefaults, BrewOverrides, ConnectSweepSettings, blank_profile};
    use de1_protocol::requested_state;

    use super::*;
    use crate::{Command, Source, WriteTarget};

    const IDLE: [u8; 2] = [2, 0];
    const ESPRESSO: [u8; 2] = [4, 4];
    const FLUSH: [u8; 2] = [15, 5];
    const STEAM: [u8; 2] = [5, 7];

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

    fn phases(out: &CoreOutput) -> Vec<StartPhase> {
        out.events
            .iter()
            .filter_map(|e| match e {
                Event::ShotStartProgress { phase } => Some(*phase),
                _ => None,
            })
            .collect()
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

    fn banner(out: &CoreOutput) -> Option<Vec<StartCheck>> {
        out.events.iter().find_map(|e| match e {
            Event::GroupHeadStartUnchecked { notices } => {
                Some(notices.iter().map(|n| n.check).collect())
            }
            _ => None,
        })
    }

    fn uploads(out: &CoreOutput) -> bool {
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

    fn profile(yield_out: f32) -> CremaProfile {
        let mut p = blank_profile(&BrewDefaults::default());
        p.yield_out = yield_out;
        p
    }

    fn report(core: &mut CremaCore, packet: [u8; 2], at_ms: u64) -> CoreOutput {
        core.on_notification(Source::De1State, &packet, at_ms)
    }

    /// Ack every profile write in `writes` at `at_ms`.
    fn ack(core: &mut CremaCore, writes: &CoreOutput, at_ms: u64) -> CoreOutput {
        let mut all = CoreOutput::default();
        for c in &writes.commands {
            if let Command::WriteCharacteristic { target, data } = c {
                let source = match target {
                    WriteTarget::De1ProfileHeader => Source::De1HeaderAck,
                    WriteTarget::De1ProfileFrame => Source::De1FrameAck,
                    _ => continue,
                };
                let out = core.on_notification(source, data, at_ms);
                all.events.extend(out.events);
                all.commands.extend(out.commands);
            }
        }
        all
    }

    /// A connected, idle, settled core (model known, the connect sweep run),
    /// with stop-on-weight off unless a test turns it on.
    fn connected(pre_flush: bool) -> CremaCore {
        let mut core = CremaCore::new_with_model(1);
        core.set_stop_on_weight(false);
        core.set_start_settings(pre_flush, false, false);
        let _ = report(&mut core, IDLE, 1_000);
        let _ = core.connect_sweep(&ConnectSweepSettings::default(), 1_000);
        core
    }

    fn start(core: &mut CremaCore, p: &CremaProfile, at_ms: u64) -> StartShotResult {
        core.start_shot(p, &BrewOverrides::default(), Duration::from_millis(at_ms))
    }

    /// AC-F16.5: sync → guard → pre-flush → Espresso → started.
    #[test]
    fn f16_5_a_start_syncs_guards_flushes_and_requests_espresso() {
        let mut core = connected(true);
        let r = start(&mut core, &profile(36.0), 2_000);
        assert!(r.accepted);
        assert!(core.shot_starting());
        assert_eq!(phases(&r.output), vec![StartPhase::Syncing]);
        assert!(uploads(&r.output), "the shot's profile goes to the DE1");
        // The upload lands: the flush is next, held by the download guard.
        let done = ack(&mut core, &r.output, 2_200);
        assert_eq!(phases(&done), vec![StartPhase::Flushing]);
        assert!(state_writes(&done).is_empty(), "inside the guard");
        assert_eq!(
            state_writes(&core.on_tick(2_700)),
            vec![requested_state(MachineState::HotWaterRinse)]
        );
        // Our flush runs and ends in Idle: Espresso.
        let _ = report(&mut core, FLUSH, 3_000);
        let out = report(&mut core, IDLE, 8_000);
        assert_eq!(phases(&out), vec![StartPhase::Requested]);
        assert_eq!(
            state_writes(&out),
            vec![requested_state(MachineState::Espresso)]
        );
        let out = report(&mut core, ESPRESSO, 8_300);
        assert_eq!(ends(&out), vec![StartEnd::Started]);
        assert_eq!(banner(&out), None, "an app start has no banner");
        assert!(!core.shot_starting());
    }

    /// AC-F16.5: a profile the DE1 already holds skips the wait, not the
    /// guard.
    #[test]
    fn f16_5_a_held_profile_skips_the_wait_but_not_the_guard() {
        let mut core = connected(false);
        let p = profile(36.0);
        let first = core.set_desired_profile(
            Some(&p),
            &BrewOverrides::default(),
            Duration::from_millis(1_500),
        );
        let _ = ack(&mut core, &first, 1_600);
        let r = start(&mut core, &p, 1_700);
        assert_eq!(phases(&r.output), vec![StartPhase::Requested]);
        assert!(
            state_writes(&r.output).is_empty(),
            "the guard runs to 2 100"
        );
        assert_eq!(
            state_writes(&core.on_tick(2_100)),
            vec![requested_state(MachineState::Espresso)]
        );
        // Long after an upload: at once.
        let _ = report(&mut core, ESPRESSO, 2_200);
        let _ = report(&mut core, IDLE, 30_000);
        let r = start(&mut core, &p, 31_000);
        assert_eq!(
            state_writes(&r.output),
            vec![requested_state(MachineState::Espresso)]
        );
    }

    /// AC-F16.5: a failed upload, or one the DE1 never confirms, ends it.
    #[test]
    fn f16_5_upload_failure_and_timeout_end_the_start() {
        let mut core = connected(false);
        let _ = start(&mut core, &profile(36.0), 2_000);
        let out = core.cancel_profile_upload();
        assert_eq!(ends(&out), vec![StartEnd::UploadFailed]);
        // From Steam the sync waits (busy): 15 s later the start gives up.
        let mut core = connected(false);
        let _ = report(&mut core, STEAM, 1_500);
        let r = start(&mut core, &profile(36.0), 2_000);
        assert!(r.accepted && !uploads(&r.output));
        assert!(ends(&core.on_tick(16_999)).is_empty());
        assert_eq!(ends(&core.on_tick(17_000)), vec![StartEnd::UploadTimedOut]);
    }

    /// AC-F16.5: the flush must end in Idle; the ceiling still starts.
    #[test]
    fn f16_5_the_flush_must_end_in_idle() {
        let mut core = connected(true);
        let r = start(&mut core, &profile(36.0), 2_000);
        let _ = ack(&mut core, &r.output, 2_100);
        let _ = core.on_tick(2_600);
        let _ = report(&mut core, FLUSH, 3_000);
        let out = report(&mut core, STEAM, 6_000);
        assert_eq!(
            ends(&out),
            vec![StartEnd::FlushLeftIdle {
                state: MachineState::Steam
            }]
        );
        assert!(!state_writes(&out).contains(&requested_state(MachineState::Espresso)));
        // A missed flush end: Espresso at the 30 s ceiling.
        let mut core = connected(true);
        let r = start(&mut core, &profile(36.0), 2_000);
        let _ = ack(&mut core, &r.output, 2_100);
        let _ = core.on_tick(2_600); // the flush goes out
        // 30 s from the flush stage (entered at the upload's end, 2 100).
        assert!(state_writes(&core.on_tick(32_099)).is_empty());
        assert_eq!(
            state_writes(&core.on_tick(32_100)),
            vec![requested_state(MachineState::Espresso)]
        );
    }

    /// AC-F16.5: a user's request cancels the start; its upload still lands,
    /// but no Espresso follows.
    #[test]
    fn f16_5_a_user_request_cancels_the_start() {
        let mut core = connected(false);
        let r = start(&mut core, &profile(36.0), 2_000);
        let out = core.request_machine_state(MachineState::Idle);
        assert_eq!(ends(&out), vec![StartEnd::Cancelled]);
        assert_eq!(
            state_writes(&out),
            vec![requested_state(MachineState::Idle)]
        );
        let done = ack(&mut core, &r.output, 2_200);
        assert!(phases(&done).is_empty());
        assert!(state_writes(&core.on_tick(3_000)).is_empty(), "no Espresso");
        // A link drop too.
        let mut core = connected(false);
        let _ = start(&mut core, &profile(36.0), 2_000);
        assert_eq!(ends(&core.de1_link_lost()), vec![StartEnd::Cancelled]);
        let mut core = connected(false);
        let _ = start(&mut core, &profile(36.0), 2_000);
        assert_eq!(ends(&core.reset()), vec![StartEnd::Cancelled]);
    }

    /// AC-F16.5: one start at a time, and the other refusals.
    #[test]
    fn f16_5_one_start_at_a_time() {
        let mut core = connected(false);
        assert!(start(&mut core, &profile(36.0), 2_000).accepted);
        let second = start(&mut core, &profile(36.0), 2_100);
        assert_eq!(second.refusal, Some(StartRefusal::AlreadyStarting));
        assert!(second.output.commands.is_empty());
        let _ = core.request_machine_state(MachineState::Idle);
        let _ = report(&mut core, ESPRESSO, 3_000);
        assert_eq!(
            start(&mut core, &profile(36.0), 3_100).refusal,
            Some(StartRefusal::ShotRunning)
        );
        let mut fresh = CremaCore::new_with_model(1);
        assert_eq!(
            start(&mut fresh, &profile(36.0), 0).refusal,
            Some(StartRefusal::NotConnected)
        );
        let mut core = connected(false);
        let mut long = profile(36.0);
        let seg = long.segments[0].clone();
        long.segments = vec![seg; 21];
        let r = start(&mut core, &long, 2_000);
        assert_eq!(
            r.refusal,
            Some(StartRefusal::TooManySteps {
                name: long.name.clone(),
                count: 21
            })
        );
        assert!(r.message.unwrap().contains("Remove 1"));
    }

    /// AC-F16.5: Espresso before our request means a group-head start.
    #[test]
    fn f16_5_espresso_before_our_request_stands_down() {
        let mut core = connected(false);
        let _ = start(&mut core, &profile(36.0), 2_000);
        let out = report(&mut core, ESPRESSO, 2_100);
        assert_eq!(ends(&out), vec![StartEnd::StoodDown]);
        assert!(state_writes(&out).is_empty());
    }

    /// AC-F16.5: a machine that ignores the request (GHC-only) frees the
    /// start after 5 s.
    #[test]
    fn f16_5_espresso_not_entered_in_5_s_fails() {
        let mut core = connected(false);
        let p = profile(36.0);
        let first = core.set_desired_profile(
            Some(&p),
            &BrewOverrides::default(),
            Duration::from_millis(1_100),
        );
        let _ = ack(&mut core, &first, 1_200);
        let r = start(&mut core, &p, 5_000);
        assert_eq!(
            state_writes(&r.output),
            vec![requested_state(MachineState::Espresso)]
        );
        assert!(ends(&core.on_tick(9_999)).is_empty());
        let out = core.on_tick(10_000);
        assert_eq!(ends(&out), vec![StartEnd::NotEntered]);
        assert!(out.events.iter().any(|e| matches!(
            e,
            Event::ShotStartEnded { message: Some(m), .. } if m.contains("start it on the machine")
        )));
    }

    /// AC-F16.6: the scale gate uses the stop-at-weight target — a Quick
    /// Controls yield of 0 falls back to the profile's 36 g.
    #[test]
    fn f16_6_the_scale_gate_uses_saws_target() {
        let zero = BrewOverrides {
            yield_g: Some(0.0),
            ..BrewOverrides::default()
        };
        // Require-scale: refused, nothing written.
        let mut core = connected(false);
        core.set_stop_on_weight(true);
        core.set_start_settings(false, true, false);
        let r = core.start_shot(&profile(36.0), &zero, Duration::from_millis(2_000));
        assert_eq!(r.refusal, Some(StartRefusal::ScaleRequired));
        assert!(r.output.commands.is_empty());
        // Off: a notice, and the start runs.
        core.set_start_settings(false, false, false);
        let r = core.start_shot(&profile(36.0), &zero, Duration::from_millis(2_100));
        assert!(r.accepted);
        assert!(r.output.events.contains(&Event::StartNotice {
            notice: StartCheck::NoScale.into()
        }));
        // No target anywhere: no check.
        let mut core = connected(false);
        core.set_stop_on_weight(true);
        core.set_start_settings(false, true, false);
        let r = core.start_shot(&profile(0.0), &zero, Duration::from_millis(2_000));
        assert!(r.accepted);
        assert!(
            !r.output
                .events
                .iter()
                .any(|e| matches!(e, Event::StartNotice { .. }))
        );
    }

    fn steam_session(core: &mut CremaCore, end_ms: u64) -> CoreOutput {
        let _ = report(core, STEAM, end_ms - 5_000);
        report(core, IDLE, end_ms)
    }

    /// AC-F16.3: 1.5 s after steam, once, only from Idle.
    #[test]
    fn f16_3_auto_purge_fires_from_idle_once() {
        let mut core = connected(false);
        core.set_start_settings(false, false, true);
        let out = steam_session(&mut core, 10_000);
        assert!(
            out.events
                .iter()
                .any(|e| matches!(e, Event::WakeAt { at_ms } if (*at_ms - 11_500.0).abs() < 0.5))
        );
        // A second steam end inside the delay doesn't arm a second purge.
        let _ = steam_session(&mut core, 11_000 + 5_000);
        assert!(state_writes(&core.on_tick(11_499)).is_empty());
        assert_eq!(
            state_writes(&core.on_tick(11_500)),
            vec![requested_state(MachineState::HotWaterRinse)]
        );
        assert!(state_writes(&core.on_tick(30_000)).is_empty(), "once");
        // Off: nothing.
        let mut core = connected(false);
        let _ = steam_session(&mut core, 10_000);
        assert!(state_writes(&core.on_tick(12_000)).is_empty());
    }

    /// AC-F16.3: the purge stands down for a start, a request or another
    /// state.
    #[test]
    fn f16_3_auto_purge_stands_down_for_a_start_or_another_state() {
        let mut core = connected(false);
        core.set_start_settings(false, false, true);
        let _ = steam_session(&mut core, 10_000);
        let _ = start(&mut core, &profile(36.0), 10_500);
        assert!(
            !state_writes(&core.on_tick(11_500))
                .contains(&requested_state(MachineState::HotWaterRinse))
        );
        let mut core = connected(false);
        core.set_start_settings(false, false, true);
        let _ = steam_session(&mut core, 10_000);
        let _ = report(&mut core, STEAM, 10_800);
        assert!(
            state_writes(&core.on_tick(11_500)).is_empty(),
            "not from Steam"
        );
        let mut core = connected(false);
        core.set_start_settings(false, false, true);
        let _ = steam_session(&mut core, 10_000);
        let _ = core.request_machine_state(MachineState::Sleep);
        assert!(
            !state_writes(&core.on_tick(11_500))
                .contains(&requested_state(MachineState::HotWaterRinse))
        );
    }

    /// AC-F16.3: a read-only mirror never purges.
    #[test]
    fn f16_3_a_mirror_never_purges() {
        let mut core = connected(false);
        core.set_start_settings(false, false, true);
        core.set_read_only(true);
        let _ = steam_session(&mut core, 10_000);
        assert_eq!(core.starter.purge_at, None, "a mirror never arms it");
        assert!(state_writes(&core.on_tick(12_000)).is_empty());
    }

    /// Validation P3 (F16b): a start drops a request still held — Steam tapped
    /// during an upload, then Coffee: the newer intent wins.
    #[test]
    fn a_start_drops_a_held_request() {
        let mut core = connected(false);
        let p = profile(36.0);
        let first = core.set_desired_profile(
            Some(&p),
            &BrewOverrides::default(),
            Duration::from_millis(1_500),
        );
        let _ = core.request_machine_state(MachineState::Steam); // held behind the upload
        // Coffee with the dial moved: the start waits for its own profile,
        // after the one uploading now.
        let dialled = BrewOverrides {
            brew_temp: Some(p.segments[0].temp + 1.0),
            ..BrewOverrides::default()
        };
        let r = core.start_shot(&p, &dialled, Duration::from_millis(1_600));
        assert!(r.accepted);
        let done = ack(&mut core, &first, 1_700);
        let mut writes = state_writes(&done);
        writes.extend(state_writes(&core.on_tick(2_200)));
        assert!(
            !writes.contains(&requested_state(MachineState::Steam)),
            "Steam never goes out: {writes:?}"
        );
    }

    /// Validation P3 (F16b): a start ends only on its own upload's failure.
    #[test]
    fn an_older_uploads_failure_does_not_end_the_start() {
        let mut core = connected(false);
        let p = profile(36.0);
        // The dial's upload is in flight when Coffee is tapped with another
        // brew temperature: the start waits for its own profile.
        let _ = core.set_desired_profile(
            Some(&p),
            &BrewOverrides::default(),
            Duration::from_millis(1_500),
        );
        let dialled = BrewOverrides {
            brew_temp: Some(p.segments[0].temp + 1.0),
            ..BrewOverrides::default()
        };
        let r = core.start_shot(&p, &dialled, Duration::from_millis(1_600));
        assert!(r.accepted);
        let out = core.cancel_profile_upload(); // the dial's upload fails
        assert!(
            ends(&out).is_empty(),
            "not the start's upload: {:?}",
            ends(&out)
        );
        assert!(core.shot_starting());
    }

    /// AC-F16.7: a group-head start lists the checks it skipped that applied.
    #[test]
    fn f16_7_a_group_head_start_lists_the_checks_it_skipped() {
        // No scale for a weight stop, the pre-flush, a profile upload pending.
        let mut core = connected(true);
        core.set_stop_on_weight(true);
        core.set_profile_target_weight(Some(36.0));
        let p = profile(36.0);
        let _ = core.set_desired_profile(
            Some(&p),
            &BrewOverrides::default(),
            Duration::from_millis(1_500),
        );
        let out = report(&mut core, ESPRESSO, 2_000);
        assert_eq!(
            banner(&out),
            Some(vec![
                StartCheck::NoScale,
                StartCheck::PreFlushSkipped,
                StartCheck::ProfilePending
            ])
        );
        assert!(out.commands.is_empty(), "it never writes");
        // Nothing applies: no banner.
        let mut core = connected(false);
        let first = core.set_desired_profile(
            Some(&p),
            &BrewOverrides::default(),
            Duration::from_millis(1_500),
        );
        let _ = ack(&mut core, &first, 1_600);
        assert_eq!(banner(&report(&mut core, ESPRESSO, 2_000)), None);
    }

    /// AC-F16.7: never for an app start, never on a mirror.
    #[test]
    fn f16_7_an_app_start_or_a_mirror_shows_no_banner() {
        let mut core = connected(true);
        core.set_stop_on_weight(true);
        core.set_profile_target_weight(Some(36.0));
        let r = start(&mut core, &profile(36.0), 2_000);
        let _ = ack(&mut core, &r.output, 2_100);
        let _ = core.on_tick(2_600);
        let _ = report(&mut core, FLUSH, 3_000);
        let _ = report(&mut core, IDLE, 6_000);
        assert_eq!(banner(&report(&mut core, ESPRESSO, 6_200)), None);
        let mut core = connected(true);
        core.set_read_only(true);
        assert_eq!(banner(&report(&mut core, ESPRESSO, 2_000)), None);
    }

    /// The settings survive a reset, like the stop targets.
    #[test]
    fn start_settings_survive_a_reset() {
        let mut core = connected(true);
        core.set_start_settings(true, true, true);
        let _ = core.reset();
        let s = core.starter.settings;
        assert!(s.pre_flush && s.require_scale && s.steam_purge);
    }
}
