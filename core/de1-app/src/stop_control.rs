//! The stop supervisor's glue (`.scratch/machine-control/DESIGN.md` §3.3): a
//! session owns its stop. A leg firing or the user's Stop makes it owed; the
//! machine winding down honours it; until then it's retried on the clock, then
//! only on proof of flow. A link's first state with a session open is checked
//! against the outage before any retry goes out.
//!
//! The decisions are the session's ([`de1_domain::session`],
//! [`de1_domain::stop`]); this module applies them to the core's inputs.

use std::time::Duration;

use de1_domain::scale_trust::WeightAuthority;
use de1_domain::session::{
    ActiveMode, Baseline, Blocked, Observed, Session, is_pre_flow, is_wind_down,
};
use de1_domain::stop::{
    FLOW_PROOF_ML_S, FLOW_PROOF_SAMPLES, STOP_ALERT_AFTER, STOP_RETRY_CAP, StopCause, StopState,
    stop_retry_interval,
};
use de1_domain::{AutoStop, HOT_WATER_STOP_ON_SCALE_ML, StopConfig, StopReason, StopTargets};
use de1_protocol::{MachineState, StateInfo};

use crate::machine_requests::Initiator;
use crate::{Command, CoreOutput, CremaCore, DEFAULT_SCALE_LAG, Event, Scale};

/// How long after a link's first flowing espresso state the core waits for a
/// sample to compare frames: the frame number isn't in `StateInfo`.
pub(crate) const OUTAGE_SAMPLE_WAIT: Duration = Duration::from_secs(1);

/// The longest espresso outage the same shot can survive on frame evidence.
pub(crate) const ESPRESSO_OUTAGE_LIMIT: Duration = Duration::from_secs(60);

/// The bound for a flush outage when the core hasn't written the flush
/// timeout (the connect sweep does): 60 s.
pub(crate) const FLUSH_OUTAGE_LIMIT: Duration = Duration::from_secs(60);

/// What [`Event::StopNotHonoured`] tells the user.
pub(crate) const STOP_NOT_HONOURED: &str =
    "Crema can't stop the machine. Press Stop on the machine.";

/// No legs.
pub(crate) const NO_LEGS: StopTargets = StopTargets {
    weight: None,
    volume: None,
    max_time: None,
};

/// Which legs a session's [`AutoStop`] runs.
#[derive(Debug, Clone, Copy, PartialEq)]
pub(crate) enum StopLegs {
    /// The espresso legs, re-resolved on every input.
    Espresso,
    /// A hot-water pour's, latched when it began.
    HotWater {
        /// The weight target, grams, if the app saw the pour begin with a
        /// scale operational.
        weight_g: Option<f32>,
        /// The user's volume, ml.
        v_ml: Option<f32>,
    },
}

/// A session's auto-stop and the legs it runs. It exists for as long as the
/// session flows (`DESIGN.md` §3.3).
#[derive(Debug)]
pub(crate) struct FlowStop {
    pub(crate) legs: StopLegs,
    pub(crate) stop: AutoStop,
}

/// A read of the session's auto-stop.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct AutoStopView {
    /// The mode whose legs it runs.
    pub mode: ActiveMode,
    /// When its clock started.
    pub started: Duration,
    /// It has fired.
    pub triggered: bool,
    /// The legs as of the last input.
    pub targets: StopTargets,
}

/// A link's first espresso state found a session flowing: is it the same
/// shot? Decided on the first sample, within [`OUTAGE_SAMPLE_WAIT`].
#[derive(Debug, Clone, Copy)]
pub(crate) struct OutageCheck {
    /// The frame the last sample before the drop was on.
    frame_before: Option<u8>,
    /// How long the link was down, if known.
    outage: Option<Duration>,
    /// When the first state came back.
    returned_at: Duration,
}

impl CremaCore {
    /// Build the espresso session's [`AutoStop`] at first flow, whatever its
    /// legs (`DESIGN.md` §3.3): a leg that resolves later, such as weight
    /// once the tare lands, goes live on the next input. Its clock is
    /// `flow_started`, so the arming delay counts from flow, not heating. A
    /// hot-water pour's stop still held here is replaced.
    pub(crate) fn arm_espresso_stop(&mut self, flow_started: Duration) {
        if self
            .auto_stop
            .as_ref()
            .is_some_and(|f| f.legs == StopLegs::Espresso)
        {
            return;
        }
        let targets = self.leg_targets(StopLegs::Espresso);
        let mut stop = AutoStop::new(targets, self.stop_config(), flow_started);
        self.attach_drip_model(&mut stop, targets.weight.is_some());
        self.auto_stop = Some(FlowStop {
            legs: StopLegs::Espresso,
            stop,
        });
    }

    /// Build a hot-water pour's [`AutoStop`] when its session opens, at
    /// `opened`: for an Unseen pour that's the attach. The weight target is
    /// the pour's only if the app saw it begin; the user's volume `V` stands
    /// in when the scale can't stop it.
    pub(crate) fn arm_hot_water_stop(&mut self, opened: Duration) {
        let target = self.hot_water_target();
        let legs = StopLegs::HotWater {
            weight_g: target
                .and_then(|t| t.stop_weight_g)
                .filter(|_| !self.began_unseen()),
            v_ml: target.and_then(|t| t.volume_ml),
        };
        let lag = self
            .scale
            .as_ref()
            .map_or(DEFAULT_SCALE_LAG, Scale::sensor_lag);
        let stop = AutoStop::new(self.leg_targets(legs), StopConfig::hot_water(lag), opened);
        self.auto_stop = Some(FlowStop { legs, stop });
    }

    /// Drop the session's auto-stop if it's espresso's (`espresso`) or hot
    /// water's (`!espresso`), returning it. A mode ending never drops the
    /// next mode's stop.
    pub(crate) fn take_auto_stop(&mut self, espresso: bool) -> Option<AutoStop> {
        if self
            .auto_stop
            .as_ref()
            .is_some_and(|f| (f.legs == StopLegs::Espresso) == espresso)
        {
            return self.auto_stop.take().map(|f| f.stop);
        }
        None
    }

    /// The legs live now.
    ///
    /// - Espresso: [`effective_stop_targets`](Self::effective_stop_targets).
    /// - Hot water (`DESIGN.md` §3.5): weight under a trusted scale; the
    ///   volume leg only when the packet on the machine is the 250 ml backstop
    ///   and no trusted weight stop is armed. If the packet holds `V`, the DE1
    ///   owns the stop and an app leg would race it.
    fn leg_targets(&self, legs: StopLegs) -> StopTargets {
        match legs {
            StopLegs::Espresso => self.effective_stop_targets().unwrap_or(NO_LEGS),
            StopLegs::HotWater { weight_g, v_ml } => {
                let weight =
                    weight_g.filter(|_| self.hot_water_authority() == WeightAuthority::Trusted);
                let packet_is_backstop = self
                    .steam_hotwater_settings
                    .as_ref()
                    .is_some_and(|p| p.hot_water_volume_ml >= HOT_WATER_STOP_ON_SCALE_ML - 0.5);
                StopTargets {
                    weight,
                    volume: v_ml.filter(|_| packet_is_backstop && weight.is_none()),
                    max_time: None,
                }
            }
        }
    }

    /// Attach the learned-drip snapshot to an [`AutoStop`] whose weight leg
    /// is armed — the SAW-learning threshold (see `de1_domain::saw_learning`).
    /// Keyed by (active profile title, scale label); a shot with no weight
    /// target keeps the legacy config untouched.
    fn attach_drip_model(&self, stop: &mut AutoStop, weight_armed: bool) {
        if !weight_armed {
            return;
        }
        let profile = self.last_active_profile_title.as_deref().unwrap_or("");
        let scale_label = self.scale.as_ref().map_or("", |s| s.label());
        let snapshot = self.saw_model.snapshot(profile, scale_label);
        let lag = self
            .scale
            .as_ref()
            .map_or(DEFAULT_SCALE_LAG, Scale::sensor_lag)
            .as_secs_f64();
        stop.set_drip_model(snapshot, lag);
    }

    /// Re-derive the auto-stop's legs before it judges an input, and after a
    /// change to anything they depend on (scale, toggles, target setters).
    /// No-op with no session flowing. Hot water has no drip model.
    pub(crate) fn refresh_auto_stop_targets(&mut self) {
        let Some(legs) = self.auto_stop.as_ref().map(|f| f.legs) else {
            return;
        };
        let targets = self.leg_targets(legs);
        if self
            .auto_stop
            .as_ref()
            .is_some_and(|f| f.stop.targets() == targets)
        {
            return;
        }
        if let Some(mut flow_stop) = self.auto_stop.take() {
            flow_stop.stop.set_targets(targets);
            // A weight leg arriving mid-shot (scale registered late) also
            // needs the learned-drip snapshot it missed at arm time.
            if legs == StopLegs::Espresso {
                self.attach_drip_model(&mut flow_stop.stop, targets.weight.is_some());
            }
            self.auto_stop = Some(flow_stop);
        }
    }

    /// The hot-water pour's weight target, if the pour has one.
    pub(crate) fn hot_water_stop_target_g(&self) -> Option<f32> {
        match self.auto_stop.as_ref()?.legs {
            StopLegs::HotWater { weight_g, .. } => weight_g,
            StopLegs::Espresso => None,
        }
    }

    /// The session's auto-stop, for the model crate and diagnostics.
    #[must_use]
    pub fn auto_stop_view(&self) -> Option<AutoStopView> {
        self.auto_stop.as_ref().map(|f| AutoStopView {
            mode: match f.legs {
                StopLegs::Espresso => ActiveMode::Espresso,
                StopLegs::HotWater { .. } => ActiveMode::HotWater,
            },
            started: f.stop.started(),
            triggered: f.stop.has_triggered(),
            targets: f.stop.targets(),
        })
    }

    /// The running pour volume, ml: what the volume legs compare. Zero at
    /// every session's start.
    #[must_use]
    pub fn pour_ml(&self) -> f32 {
        self.volume_integrator.pour_ml()
    }

    /// Emit [`Event::StopTargetsArmed`] whenever the armed picture changed —
    /// called from the scale-reading and telemetry paths (cheap `Copy`
    /// compare), so arming, a late-registering scale, and every stop toggle
    /// all surface in the shells' event logs within a beat (issue #15:
    /// arming failures used to be silent).
    pub(crate) fn report_stop_targets(&mut self, out: &mut CoreOutput) {
        let (armed, t) = self.stop_targets_projection();
        // The blocked REASON is part of the reported picture: a scale dropping
        // mid-shot changes only that, and the shell needs to hear about it.
        let current = (
            armed,
            t,
            self.effective_target_weight(),
            self.weight_stop_blocked_by(),
        );
        if Some(current) == self.reported_stop_targets {
            return;
        }
        self.reported_stop_targets = Some(current);
        // Emitted even when every leg is `None` — "nothing will stop this
        // shot" is the single most important thing this event can say, and
        // suppressing it is what let geota/crema#56 run silently.
        out.events.push(Event::StopTargetsArmed {
            weight: t.weight,
            volume: t.volume,
            max_time: t.max_time,
            armed,
            weight_configured: self.effective_target_weight(),
            weight_blocked: self.weight_stop_blocked_by().map(str::to_owned),
            weight_blocked_note: self.weight_blocked().map(|b| b.note().to_owned()),
        });
    }

    /// The session's stop, if a session is open.
    pub(crate) fn stop_state(&self) -> Option<StopState> {
        self.session.map(|s| s.stop)
    }

    /// A stop is owed or being honoured: the legs are not live.
    pub(crate) fn stop_in_hand(&self) -> bool {
        self.stop_state().is_some_and(|s| s != StopState::Armed)
    }

    /// A leg fired. Legs act only while the session is armed. A mirror shows
    /// the stop and writes nothing: it never owes one.
    pub(crate) fn push_stop(
        &mut self,
        reason: Option<StopReason>,
        now: Duration,
        out: &mut CoreOutput,
    ) {
        let Some(reason) = reason else {
            return;
        };
        if self.stop_in_hand() {
            return;
        }
        self.stop_triggered_this_shot = true;
        self.shot_stop_reason.get_or_insert(reason);
        out.events.push(Event::StopTriggered { reason });
        if self.read_only {
            return;
        }
        self.owe_stop(StopCause::Auto(reason), now, out);
    }

    /// The user's Stop with a session open (from
    /// [`request_machine_state`](Self::request_machine_state)): owed from
    /// armed, sent at once while owed, and sent once while the machine winds
    /// down (the second tap of two-tap steam).
    pub(crate) fn user_stop(&mut self, now: Duration, out: &mut CoreOutput) {
        self.owe_stop(StopCause::User, now, out);
    }

    fn owe_stop(&mut self, cause: StopCause, now: Duration, out: &mut CoreOutput) {
        let initiator = match cause {
            StopCause::User => Initiator::User,
            StopCause::Auto(_) => Initiator::Core,
        };
        let Some(session) = self.session.as_mut() else {
            self.request_state(MachineState::Idle, initiator, out);
            return;
        };
        match session.stop {
            StopState::Armed => {
                session.stop = StopState::Owed {
                    cause,
                    last_write: now,
                    attempts: 0,
                };
                // A leg judged the old session while the core can't yet tell
                // it from a new one: its write waits for the outage check,
                // at most a second. A user Stop goes at once.
                if matches!(cause, StopCause::Auto(_)) && self.outage_check.is_some() {
                    self.flow_proof = 0;
                    return;
                }
            }
            StopState::Owed {
                ref mut last_write, ..
            } => *last_write = now,
            StopState::Honoured { .. } => {}
        }
        self.flow_proof = 0;
        self.request_state(MachineState::Idle, initiator, out);
        self.emit_wake(out);
    }

    /// A state in the session's own mode (`DESIGN.md` §3.3's transitions):
    /// winding down honours an owed stop and cancels any still queued; flowing
    /// again after an honoured stop owes it again, except in steam, where a
    /// person at the machine resumes a paused steam.
    pub(crate) fn stop_on_state(&mut self, info: StateInfo, now: Duration, out: &mut CoreOutput) {
        let Some(session) = self.session.as_mut() else {
            return;
        };
        let wind_down = is_wind_down(info.substate);
        let flowing = !wind_down && !is_pre_flow(info.substate);
        let steam = session.mode == ActiveMode::Steam;
        match session.stop {
            StopState::Armed if wind_down => {
                session.stop = StopState::Honoured { cause: None };
            }
            StopState::Owed { cause, .. } if wind_down => {
                session.stop = StopState::Honoured { cause: Some(cause) };
                out.commands.push(Command::CancelStop);
            }
            StopState::Honoured { cause: Some(cause) } if flowing && !steam => {
                session.stop = StopState::Owed {
                    cause,
                    last_write: now,
                    attempts: 0,
                };
                self.flow_proof = 0;
                self.request_state(MachineState::Idle, Initiator::Core, out);
            }
            StopState::Honoured { .. } if flowing => session.stop = StopState::Armed,
            _ => {}
        }
    }

    /// A link's first state with the same session open, flowing: is the
    /// machine still running the flow the core knew (`DESIGN.md` §3.3's
    /// outage table)? Espresso waits for a sample's frame; the other modes go
    /// on the outage against the machine's own timeout.
    pub(crate) fn stop_on_return(&mut self, info: StateInfo, now: Duration, out: &mut CoreOutput) {
        let Some(session) = self.session else {
            return;
        };
        if is_wind_down(info.substate) || is_pre_flow(info.substate) {
            return;
        }
        let outage = self.link.lost_at.map(|at| now.saturating_sub(at));
        if session.mode == ActiveMode::Espresso {
            self.outage_check = Some(OutageCheck {
                frame_before: self.last_frame,
                outage,
                returned_at: now,
            });
            self.emit_wake(out);
            return;
        }
        let limit = self.machine_timeout(session.mode);
        if outage.is_some_and(|o| o <= limit) {
            self.resume_owed_stop(now, out);
        } else {
            self.outage_unsure(now, out);
        }
    }

    /// The machine's own bound on a flow in `mode`, from the packet the core
    /// knows: past it, the flow running now can't be the one from before.
    fn machine_timeout(&self, mode: ActiveMode) -> Duration {
        let packet = self.steam_hotwater_settings.as_ref();
        let secs = match mode {
            ActiveMode::HotWater => packet.map_or(60.0, |p| p.hot_water_timeout_s),
            ActiveMode::Steam | ActiveMode::SteamRinse => packet.map_or(255.0, |p| {
                if p.steam_timeout_s > 0.0 {
                    p.steam_timeout_s
                } else {
                    255.0
                }
            }),
            ActiveMode::Flush => return self.flush_timeout.unwrap_or(FLUSH_OUTAGE_LIMIT),
            ActiveMode::Espresso => return ESPRESSO_OUTAGE_LIMIT,
        };
        Duration::from_secs_f32(secs.max(0.0))
    }

    /// A sample settles an espresso outage check: a lower frame is a new shot
    /// (begun unseen during the outage); otherwise the same shot if the outage
    /// was short enough, else unsure. Returns whether a new shot began.
    pub(crate) fn settle_outage_on_sample(
        &mut self,
        frame: u8,
        now: Duration,
        out: &mut CoreOutput,
    ) -> bool {
        let Some(check) = self.outage_check.take() else {
            return false;
        };
        // A sample at or past the wait is too late to settle it: the same rule
        // as the clock's, so the outcome rests on the sample's time alone.
        if now.saturating_sub(check.returned_at) >= OUTAGE_SAMPLE_WAIT {
            self.outage_unsure(now, out);
            return false;
        }
        if check.frame_before.is_some_and(|before| frame < before) {
            self.session = Some(Session::open(ActiveMode::Espresso, Observed::Unseen));
            out.commands.push(Command::CancelStop);
            return true;
        }
        if check.outage.is_some_and(|o| o <= ESPRESSO_OUTAGE_LIMIT) {
            self.resume_owed_stop(now, out);
        } else {
            self.outage_unsure(now, out);
        }
        false
    }

    /// No sample came within [`OUTAGE_SAMPLE_WAIT`] of the return: unsure.
    fn settle_outage_on_time(&mut self, now: Duration, out: &mut CoreOutput) {
        if self
            .outage_check
            .is_some_and(|c| now.saturating_sub(c.returned_at) >= OUTAGE_SAMPLE_WAIT)
        {
            self.outage_check = None;
            self.outage_unsure(now, out);
        }
    }

    /// The same flow continues: an owed stop goes out at once, past
    /// [`STOP_RETRY_CAP`] only on proof of flow.
    fn resume_owed_stop(&mut self, now: Duration, out: &mut CoreOutput) {
        if self.may_retry() {
            self.retry_stop(now, out);
        }
    }

    /// An owed stop may be written again: under [`STOP_RETRY_CAP`], or past
    /// it with proof of flow, and never automatically in steam past the cap.
    fn may_retry(&self) -> bool {
        match self.session {
            Some(Session {
                mode,
                stop: StopState::Owed { attempts, .. },
                ..
            }) => {
                attempts < STOP_RETRY_CAP
                    || (mode != ActiveMode::Steam && self.flow_proof >= FLOW_PROOF_SAMPLES)
            }
            _ => false,
        }
    }

    /// The core can't tell whether this is the flow from before the outage.
    /// An owed stop is kept, sent and raised: a wrong "same" costs one Idle on
    /// a shot started during the outage, a wrong "new" an unbounded pour.
    /// With nothing owed, the scale can't speak for this cup: weight is
    /// refused, time and volume continue.
    fn outage_unsure(&mut self, now: Duration, out: &mut CoreOutput) {
        let read_only = self.read_only;
        let Some(session) = self.session.as_mut() else {
            return;
        };
        match session.stop {
            StopState::Owed {
                ref mut attempts, ..
            } => {
                // The send is attempt 4, so attempt 5, one interval later,
                // raises the alert only if the machine still flows.
                *attempts = (*attempts).max(STOP_ALERT_AFTER - 2);
                if self.may_retry() {
                    self.retry_stop(now, out);
                }
            }
            // A mirror follows the hub, which judged the outage itself.
            _ if read_only => {}
            _ => {
                session.baseline = Baseline::Refused {
                    why: Blocked::BeganUnseen,
                };
            }
        }
    }

    /// A sample arrived: count it as proof of flow against the last write.
    pub(crate) fn note_flow_proof(&mut self, group_flow: f32) {
        if matches!(self.stop_state(), Some(StopState::Owed { .. }))
            && group_flow >= FLOW_PROOF_ML_S
        {
            self.flow_proof = self.flow_proof.saturating_add(1);
        }
    }

    /// Retry an owed stop when due (`DESIGN.md` §3.3): on the clock while the
    /// link is up and the outage evidence settled, up to [`STOP_RETRY_CAP`];
    /// past that only on proof of flow, and never automatically in steam,
    /// whose firmware timeout bounds it. Run on every state, sample, weight
    /// and tick.
    pub(crate) fn enforce_owed_stop(&mut self, now: Duration, out: &mut CoreOutput) {
        self.settle_outage_on_time(now, out);
        if self.read_only || !self.link.state_seen || self.outage_check.is_some() {
            return;
        }
        let Some(session) = self.session else {
            return;
        };
        let StopState::Owed { last_write, .. } = session.stop else {
            return;
        };
        let steam = session.mode == ActiveMode::Steam;
        if now.saturating_sub(last_write) < stop_retry_interval(steam) || !self.may_retry() {
            return;
        }
        self.retry_stop(now, out);
    }

    /// Send an owed stop again: [`Event::StopRetried`], and from
    /// [`STOP_ALERT_AFTER`] a state read so a lost report heals, with
    /// [`Event::StopNotHonoured`] the first time.
    fn retry_stop(&mut self, now: Duration, out: &mut CoreOutput) {
        let Some(session) = self.session.as_mut() else {
            return;
        };
        let StopState::Owed {
            cause,
            ref mut last_write,
            ref mut attempts,
        } = session.stop
        else {
            return;
        };
        *attempts += 1;
        *last_write = now;
        let attempt = *attempts;
        self.flow_proof = 0;
        out.events.push(Event::StopRetried {
            reason: cause.reason(),
            attempt,
        });
        if attempt == STOP_ALERT_AFTER {
            out.events.push(Event::StopNotHonoured {
                message: STOP_NOT_HONOURED.to_owned(),
            });
        }
        self.request_state(MachineState::Idle, Initiator::Core, out);
        if attempt >= STOP_ALERT_AFTER {
            out.commands.push(Command::ReadState);
        }
        self.emit_wake(out);
    }

    /// When the stop supervisor next needs a tick: the next retry, or the end
    /// of an outage check's wait.
    pub(crate) fn stop_deadline(&self) -> Option<Duration> {
        let check = self
            .outage_check
            .map(|c| c.returned_at + OUTAGE_SAMPLE_WAIT);
        let retry = match self.session {
            Some(Session {
                mode,
                stop:
                    StopState::Owed {
                        last_write,
                        attempts,
                        ..
                    },
                ..
            }) if !self.read_only
                && self.link.state_seen
                && self.outage_check.is_none()
                && attempts < STOP_RETRY_CAP =>
            {
                Some(last_write + stop_retry_interval(mode == ActiveMode::Steam))
            }
            _ => None,
        };
        match (check, retry) {
            (Some(a), Some(b)) => Some(a.min(b)),
            (a, b) => a.or(b),
        }
    }
}
