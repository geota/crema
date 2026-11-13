//! One simulated session: the real core against the simulator.

use de1_app::{Command, CoreOutput, CremaCore, Event, Source, WriteTarget};
use de1_domain::session::ActiveMode;
use de1_domain::{BrewDefaults, BrewOverrides, ConnectSweepSettings, CremaProfile, StopReason};
use de1_protocol::{MachineState, ShotSettings, SubState};
use std::time::Duration;

use crate::invariants::{Invariant, Violation};
use crate::sim::{De1Params, ScaleMode, SimCup, SimDe1, SimLane, SimLink, SimScale, Write};

/// The simulator's step.
const STEP_MS: u64 = 100;
/// A DE1 sample every this long.
const SAMPLE_MS: u64 = 200;
/// The shells' tick.
const TICK_MS: u64 = 1_000;
/// I13's tolerance to start with (`DESIGN.md` §7).
const WEIGHT_TOLERANCE_G: f32 = 6.0;
/// The reply to the machine-model read, from the capture: a DE1 model 2.
const MODEL_READ: [u8; 20] = [
    0x04, 0x80, 0x00, 0x0C, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
];

/// Something that happens to the world.
#[derive(Debug, Clone, PartialEq)]
pub enum Op {
    /// Time passes.
    Advance {
        /// How long, ms.
        ms: u64,
    },
    /// A press at the group head.
    GroupHead(MachineState),
    /// The user starts a shot in the app.
    AppStart {
        /// The profile's yield, grams.
        yield_g: f32,
    },
    /// The user taps Stop.
    UserStop,
    /// The user taps a mode in the app: Steam, Hot water or Flush.
    AppRequest(MachineState),
    /// The profile's yield changes.
    SetProfileTarget(Option<f32>),
    /// The user dials a yield.
    Dial(Option<f32>),
    /// The volume limit changes.
    SetVolumeLimit(Option<f32>),
    /// The max shot time changes.
    SetMaxTime(Option<f32>),
    /// The steam heater target changes.
    SetSteamTarget(f32),
    /// The user turns auto-tare on or off.
    SetAutoTare(bool),
    /// A profile with a step weight goes active.
    SetStepWeight(Option<f32>),
    /// The scale starts misbehaving this way.
    Scale(ScaleMode),
    /// The scale goes away.
    ScaleDisconnect,
    /// A scale connects.
    ScaleConnect,
    /// Requested-state writes get lost.
    LoseWrites(u32),
    /// Requested-state writes land twice.
    DuplicateWrites(u32),
    /// State reports get lost.
    LoseStates(u32),
    /// Samples stop while the scale stays up.
    MuteSamples {
        /// How long, ms.
        ms: u64,
    },
    /// The next write hangs in the lane.
    HangWrite,
    /// The two oldest queued writes swap.
    ReorderWrites,
    /// The next write lands late.
    DelayWrite {
        /// How late, ms.
        ms: u64,
    },
    /// The DE1 link drops.
    LinkDown {
        /// How long, ms.
        ms: u64,
    },
    /// The app restarts: a new core attaches to whatever is running.
    Attach,
}

/// What a run reached, to prove the generator isn't vacuous.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Coverage {
    /// Espresso shots seen by the core.
    pub shots: u32,
    /// Hot-water or flush sessions.
    pub water: u32,
    /// Steam sessions.
    pub steam: u32,
    /// `StopTriggered`.
    pub stops: u32,
    /// `StopTriggered` on weight, estimated included.
    pub weight_stops: u32,
    /// `StopTriggered` on the estimate.
    pub estimated_stops: u32,
    /// Espresso sessions with a load that changed while no readings arrived,
    /// which I13 skips (§3.5's known limit).
    pub unseen_loads: u32,
    /// `StopTriggered` on volume.
    pub volume_stops: u32,
    /// `StopRetried`.
    pub retries: u32,
    /// `StopNotHonoured`.
    pub not_honoured: u32,
    /// `ReadState` commands.
    pub read_states: u32,
    /// `SkipToNext` writes.
    pub skips: u32,
    /// `Command::Stop`.
    pub stop_commands: u32,
    /// App starts tapped while another flow ran.
    pub starts_over_a_flow: u32,
    /// A user's held request released into a flow request.
    pub held_releases: u32,
    /// Sessions first seen flowing.
    pub unseen: u32,
    /// Espresso flows the core first heard on an unknown substate.
    pub unknown_flows: u32,
    /// New sessions in the same mode.
    pub replaced: u32,
    /// Tares.
    pub tares: u32,
    /// `ScaleStale`.
    pub stale: u32,
    /// `ScaleLinkDead`.
    pub dead: u32,
    /// Link drops.
    pub drops: u32,
    /// App starts accepted.
    pub starts: u32,
    /// App starts that reached Espresso.
    pub started: u32,
}

/// Who a command came from, for I3.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Who {
    User,
    Core,
}

/// One session.
#[derive(Debug)]
pub struct World {
    core: CremaCore,
    /// The machine.
    pub de1: SimDe1,
    /// The cup.
    pub cup: SimCup,
    headspace_ml: f32,
    scale: Option<SimScale>,
    tare_latency_ms: u64,
    link: SimLink,
    lane: SimLane,
    now: u64,
    read_only: bool,
    pre_flush: bool,
    was_down: bool,
    /// A state has reached the core on this link.
    state_heard_on_link: bool,
    /// An app start is pending.
    starting: bool,
    /// A user's request the core is holding for the profile-download guard:
    /// its release is still the user's.
    user_held: Option<u8>,
    /// The flow heard when Start was tapped, until the machine leaves it.
    start_from: Option<MachineState>,
    /// When the core last got a weight reading, ms.
    last_weight_ms: Option<u64>,
    /// Since when a flowing shot with a weight target has shown no live leg
    /// and no reason, ms.
    unguarded_since: Option<u64>,
    /// The open session has been heard past its pre-flow substates.
    flowed: bool,
    /// The user's auto-tare setting.
    auto_tare: bool,
    /// The hot-water dose, ml.
    hot_water_ml: f32,
    /// The frame of the last sample the core heard.
    last_sample_frame: Option<u8>,
    /// Back from an outage on a flowing shot: the frame before, when the state
    /// came back, and how long the link was down (`DESIGN.md` §3.3).
    return_check: Option<(Option<u8>, u64, u64)>,
    /// When the link last went down.
    down_at: Option<u64>,
    /// The tick the core asked for with `WakeAt`, ms, as a shell's one-shot
    /// timer.
    wake_at: Option<u64>,
    /// A state read the core asked for, due on the next step.
    read_requested: bool,
    /// The substate the core last heard.
    heard_sub: Option<SubState>,
    /// The stop the core owes, by its writes: when the last `Stop` went out.
    owed_last: Option<u64>,
    /// `Stop`s in this owed episode.
    stops_in_episode: u32,
    /// Samples the core heard at flow since the last `Stop`.
    flow_samples_since_stop: u32,
    /// `StopNotHonoured` since the episode or the link began.
    alerts: u32,
    /// I6's own reading of the session the core should have open.
    open_mode: Option<ActiveMode>,
    /// Whether that session began before the core saw it.
    unseen: bool,
    reported: Option<[u8; 2]>,
    heard: Option<MachineState>,
    /// Shot samples the core has received.
    samples_heard: u32,
    /// Scale readings the core has received.
    weights_heard: u32,
    steam_target_c: f32,
    profile_target_g: Option<f32>,
    dial_g: Option<f32>,
    volume_limit_ml: Option<f32>,
    max_time_s: Option<f32>,
    step_weight_g: Option<f32>,
    sample_clock: u16,
    skips_since_sample: u32,
    stale_open: bool,
    dead_open: bool,
    stopped_this_flow: bool,
    /// The oracle saw a new session open in the same mode on the input in
    /// hand: I20 is checked once the core has it.
    replacing: bool,
    /// The core heard this espresso session flow on an unknown substate.
    unknown_flow: bool,
    /// A load moved on this espresso's scale where the core can't see it
    /// (§3.5's known limits): while no readings were arriving in a flowing
    /// session, before the gate, where `Tared` has no returning check; after
    /// it, only a load the returning check or the step band accepts. A load
    /// that moves in preheat is judged, a gap included: the step detector
    /// watches from the tare (#389). It reads as coffee, so I13 is skipped
    /// for the rest of the session: not only while the load stays,
    /// because once the core's gate opens with the load in its reading, `last`
    /// carries it, and when the load lifts the estimate counts from there (the
    /// model shrank that to a true 29.8 g of 36). A load seen arriving on a
    /// live reading, or one the core refuses, is judged.
    unseen_load: bool,
    /// The oracle's gate: since when the true cup has held 5 g with the
    /// scale reporting, and whether that held 1 s.
    gate_since: Option<u64>,
    gate_seen: bool,
    /// The true cup at the last reading, grams.
    last_cup_g: f32,
    /// This espresso opened from a zeroed cup: a tare, or `|net| ≤ 2 g`.
    /// Auto-tare off on a loaded scale has no weight-stop guarantee (§3.5).
    opened_near_zero: bool,
    /// The load on the scale at the last reading, grams.
    last_load: f32,
    /// What the run reached.
    pub coverage: Coverage,
    /// What broke.
    pub violations: Vec<Violation>,
    /// Every requested-state write the core sent, in order, with a
    /// `Command::Stop` as its Idle byte and `true` for a stop.
    pub requests: Vec<(u8, bool)>,
}

fn weight_is_tare(data: &[u8]) -> bool {
    matches!(data, [0x03, 0x0A, 0x01, ..])
}

impl World {
    /// A connected, idle session.
    #[must_use]
    pub fn new(
        params: De1Params,
        headspace_ml: f32,
        lane_timeout_ms: u64,
        read_only: bool,
        pre_flush: bool,
    ) -> Self {
        let mut world = Self {
            core: CremaCore::new(),
            de1: SimDe1::new(params),
            cup: SimCup::default(),
            headspace_ml,
            scale: Some(SimScale::new(200)),
            tare_latency_ms: 200,
            link: SimLink::default(),
            lane: SimLane::new(lane_timeout_ms),
            now: 1_000,
            read_only,
            pre_flush,
            was_down: false,
            state_heard_on_link: false,
            flowed: false,
            auto_tare: true,
            hot_water_ml: 120.0,
            last_sample_frame: None,
            return_check: None,
            down_at: None,
            wake_at: None,
            read_requested: false,
            heard_sub: None,
            owed_last: None,
            stops_in_episode: 0,
            flow_samples_since_stop: 0,
            alerts: 0,
            last_weight_ms: None,
            unguarded_since: None,
            starting: false,
            start_from: None,
            user_held: None,
            open_mode: None,
            unseen: false,
            reported: None,
            heard: None,
            samples_heard: 0,
            weights_heard: 0,
            steam_target_c: 148.0,
            profile_target_g: Some(36.0),
            dial_g: None,
            volume_limit_ml: None,
            max_time_s: None,
            step_weight_g: None,
            sample_clock: 0,
            skips_since_sample: 0,
            stale_open: false,
            dead_open: false,
            stopped_this_flow: false,
            replacing: false,
            unknown_flow: false,
            unseen_load: false,
            gate_since: None,
            gate_seen: false,
            last_cup_g: 0.0,
            opened_near_zero: true,
            last_load: 0.0,
            coverage: Coverage::default(),
            violations: Vec::new(),
            requests: Vec::new(),
        };
        world.core.set_start_settings(pre_flush, false, false);
        world.attach();
        world
    }

    /// A fresh link's setup, as the shells run it.
    fn attach(&mut self) {
        self.core.set_read_only(self.read_only);
        self.apply_targets();
        let out = self
            .core
            .on_notification(Source::De1MmrRead, &MODEL_READ, self.now);
        self.ingest(out, Who::Core);
        self.report_state();
        let sweep = ConnectSweepSettings {
            steam_temp_c: self.steam_target_c,
            steam_eco: true,
            ..ConnectSweepSettings::default()
        };
        let out = self.core.connect_sweep(&sweep, self.now);
        self.ingest(out, Who::Core);
        if self.scale.is_some() {
            self.core.connect_scale("BOOKOO_SC", &[]);
        }
    }

    fn apply_targets(&mut self) {
        self.core.set_auto_tare(self.auto_tare);
        self.core.set_max_shot_duration(self.max_time_s);
        // The yield, the dial and the volume limit are the desired profile's
        // (`DESIGN.md` §3.10), as a shell sets it on every change.
        let (profile, qc) = self.desired();
        let out =
            self.core
                .set_desired_profile(Some(&profile), &qc, Duration::from_millis(self.now));
        self.ingest(out, Who::User);
    }

    /// The profile on screen and its Quick Controls: one library profile,
    /// whose yield and volume limit the ops change, and the dialled yield.
    fn desired(&self) -> (CremaProfile, BrewOverrides) {
        #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
        let profile = CremaProfile {
            id: "model-profile".to_owned(),
            yield_out: self.profile_target_g.unwrap_or(0.0),
            max_total_volume_ml: self.volume_limit_ml.map_or(0, |ml| ml as u16),
            ..de1_domain::blank_profile(&BrewDefaults::default())
        };
        let qc = BrewOverrides {
            yield_g: self.dial_g,
            ..BrewOverrides::default()
        };
        (profile, qc)
    }

    /// Set the hot-water dose the next [`Op::SetSteamTarget`] sends, ml.
    pub fn set_hot_water_ml(&mut self, ml: f32) {
        self.hot_water_ml = ml;
    }

    /// What's really in the cup since the last tare, grams.
    #[must_use]
    pub fn true_cup_g(&self) -> f32 {
        self.scale
            .as_ref()
            .map_or(self.cup.grams, |s| s.true_net(self.cup.grams))
    }

    /// The world's clock, ms.
    #[must_use]
    pub fn now_ms(&self) -> u64 {
        self.now
    }

    /// The state the core last heard, which a lost report leaves behind the
    /// machine's.
    #[must_use]
    pub fn heard(&self) -> Option<MachineState> {
        self.heard
    }

    /// How many samples and weight readings the core has received.
    #[must_use]
    pub fn heard_counts(&self) -> (u32, u32) {
        (self.samples_heard, self.weights_heard)
    }

    fn weight_target(&self) -> Option<f32> {
        self.dial_g.or(self.profile_target_g)
    }

    /// Run one op.
    pub fn apply(&mut self, op: &Op) {
        match *op {
            Op::Advance { ms } => {
                for _ in 0..ms / STEP_MS {
                    self.step();
                }
            }
            Op::GroupHead(state) => {
                // A press that starts nothing leaves the pour's cup alone.
                let idle = self.de1.state == MachineState::Idle;
                self.de1.enter(state);
                if idle && self.de1.active() {
                    self.cup.headspace_ml = self.headspace_ml;
                }
            }
            Op::AppStart { yield_g } => self.app_start(yield_g),
            Op::UserStop => {
                let out = self.core.request_machine_state(MachineState::Idle);
                self.ingest(out, Who::User);
            }
            Op::AppRequest(state) => {
                let out = self.core.request_machine_state(state);
                self.ingest(out, Who::User);
            }
            Op::SetProfileTarget(g) => {
                self.profile_target_g = g;
                self.apply_targets();
            }
            Op::Dial(g) => {
                self.dial_g = g;
                self.apply_targets();
            }
            Op::SetVolumeLimit(ml) => {
                self.volume_limit_ml = ml;
                self.apply_targets();
            }
            Op::SetMaxTime(s) => {
                self.max_time_s = s;
                self.apply_targets();
            }
            Op::SetSteamTarget(c) => self.set_steam_target(c),
            Op::SetAutoTare(on) => {
                self.auto_tare = on;
                self.core.set_auto_tare(on);
            }
            Op::SetStepWeight(g) => self.set_step_weight(g),
            Op::Scale(mode) => {
                if let Some(scale) = &mut self.scale {
                    scale.set_mode(mode);
                }
            }
            Op::ScaleDisconnect => {
                self.scale = None;
                self.core.disconnect_scale();
            }
            Op::ScaleConnect => {
                if self.scale.is_none() {
                    self.scale = Some(SimScale::new(self.tare_latency_ms));
                    self.core.connect_scale("BOOKOO_SC", &[]);
                }
            }
            Op::LoseWrites(n) => self.link.lose_writes += n,
            Op::DuplicateWrites(n) => self.link.duplicate_writes += n,
            Op::LoseStates(n) => self.link.lose_states += n,
            Op::MuteSamples { ms } => self.link.samples_muted_until = self.now + ms,
            Op::HangWrite => self.lane.hang_next = true,
            Op::ReorderWrites => self.lane.reorder_next = true,
            Op::DelayWrite { ms } => self.lane.delay_next_ms = ms,
            Op::LinkDown { ms } => {
                if !self.link.down(self.now) {
                    self.coverage.drops += 1;
                    self.link.down_until = Some(self.now + ms);
                    self.lane.clear();
                    // An outage runs from the first drop, as the core's does:
                    // a link that drops again before any state arrived is the
                    // same outage, since nothing was heard in between.
                    if self.state_heard_on_link || self.down_at.is_none() {
                        self.down_at = Some(self.now);
                    }
                    self.state_heard_on_link = false;
                    // The outage may raise the alert again.
                    self.alerts = 0;
                    let out = self.core.de1_link_lost();
                    self.ingest(out, Who::Core);
                    self.was_down = true;
                }
            }
            Op::Attach => {
                self.core = CremaCore::new();
                self.core.set_read_only(self.read_only);
                self.core.set_start_settings(self.pre_flush, false, false);
                self.apply_targets();
                self.reported = None;
                self.heard = None;
                self.state_heard_on_link = false;
                self.last_sample_frame = None;
                self.starting = false;
                self.start_from = None;
                self.open_mode = None;
                self.unseen = false;
                self.lane.clear();
                self.stale_open = false;
                self.dead_open = false;
                self.stopped_this_flow = false;
                if !self.link.down(self.now) {
                    self.attach();
                }
            }
        }
    }

    fn app_start(&mut self, yield_g: f32) {
        // The user sets the profile's yield, which a shell makes the desired
        // profile at once, and taps Start on it with the dial as it is: a
        // second start of the same one needs no upload.
        self.profile_target_g = Some(yield_g);
        self.apply_targets();
        let (profile, qc) = self.desired();
        let result = self
            .core
            .start_shot(&profile, &qc, Duration::from_millis(self.now));
        if result.accepted {
            self.coverage.starts += 1;
            self.starting = true;
            self.start_from = self.heard.filter(|s| s.is_flow_state());
            if self.start_from.is_some() {
                self.coverage.starts_over_a_flow += 1;
            }
            // The headspace refills when the machine enters the flow (`land`).
        }
        self.ingest(result.output, Who::User);
    }

    fn set_steam_target(&mut self, celsius: f32) {
        self.steam_target_c = celsius;
        let targets = de1_app::SteamHotWaterTargets {
            steam_temp_c: celsius,
            steam_timeout_s: 60.0,
            hot_water_temp_c: 85.0,
            hot_water: de1_domain::HotWaterDose {
                mode: de1_domain::HotWaterMode::Volume,
                volume_ml: self.hot_water_ml,
                ..de1_domain::HotWaterDose::default()
            },
        };
        let out = self.core.set_steam_hotwater_targets(&targets);
        self.ingest(out, Who::User);
    }

    fn set_step_weight(&mut self, grams: Option<f32>) {
        self.step_weight_g = grams;
        let mut profile = de1_domain::cold_maintenance_profile();
        let step = profile.steps[0].clone();
        profile.steps = vec![step.clone(), step.clone(), step];
        for step in &mut profile.steps {
            step.weight = None;
            step.exit = None;
        }
        profile.steps[0].weight = grams;
        self.core.set_active_profile(Some(&profile));
    }

    fn report_state(&mut self) {
        let bytes = self.de1.state_bytes();
        if self.reported == Some(bytes) {
            return;
        }
        self.reported = Some(bytes);
        if !self.de1.active() {
            self.stopped_this_flow = false;
        }
        if self.link.lose_states > 0 {
            self.link.lose_states -= 1;
            return;
        }
        // A lost report can leave the core in another flow than the machine's,
        // so a new state heard is a new episode too.
        if self.heard != Some(self.de1.state) {
            self.stopped_this_flow = false;
        }
        self.heard = Some(self.de1.state);
        self.track_stop(bytes[1]);
        if self.start_from != self.heard {
            self.start_from = None;
        }
        self.track_session(bytes[1]);
        let out = self
            .core
            .on_notification(Source::De1State, &bytes, self.now);
        self.ingest(out, Who::Core);
        self.check_replaced();
    }

    /// I6's oracle, apart from the core's own session: a mode opens unseen
    /// only on the first state of a link, past the pre-flow substates.
    fn track_session(&mut self, substate: u8) {
        let first = !std::mem::replace(&mut self.state_heard_on_link, true);
        let mode = ActiveMode::of(self.de1.state);
        let pre_flow = [
            SubState::Ready,
            SubState::Heating,
            SubState::FinalHeating,
            SubState::Stabilising,
        ]
        .iter()
        .any(|s| *s as u8 == substate);
        if mode == self.open_mode {
            // A pre-flow state on return is a new pour, seen from its start,
            // and so is one after an espresso flowed, on any report.
            let replaced = (first && mode.is_some() && pre_flow)
                || (mode == Some(ActiveMode::Espresso) && self.flowed && pre_flow);
            self.flowed = (self.flowed || !pre_flow) && !replaced;
            if replaced {
                self.scale_zero_at_open();
                self.replacing = true;
                self.unknown_flow = false;
                self.unseen = false;
                self.owed_last = None;
                self.stops_in_episode = 0;
                self.stopped_this_flow = false;
                self.alerts = 0;
            }
            return;
        }
        self.open_mode = mode;
        if mode == Some(ActiveMode::Espresso) {
            self.scale_zero_at_open();
        }
        self.flowed = !pre_flow;
        // The core's frame memory ends with its shot.
        self.last_sample_frame = None;
        self.unseen = !self.read_only && mode.is_some() && first && !pre_flow;
        let weighs = matches!(mode, Some(ActiveMode::Espresso | ActiveMode::HotWater))
            && self.scale.is_some()
            && self.weight_target().is_some();
        if self.unseen && weighs {
            self.coverage.unseen += 1;
        }
    }

    /// A load the core can't see: one that changed while no readings were
    /// arriving. Called on each reading, before it counts as heard.
    fn watch_unseen_load(&mut self) {
        let gap_ms = self
            .last_weight_ms
            .map_or(u64::MAX, |at| self.now.saturating_sub(at));
        let load = self.scale.as_ref().map_or(0.0, |s| s.load_now(self.now));
        let load_step = load - std::mem::replace(&mut self.last_load, load);
        let cup = self.true_cup_g();
        let gain = cup - std::mem::replace(&mut self.last_cup_g, cup);
        // Before flow the step detector judges every reading, the first after
        // a gap included (#389), so preheat only moves the comparison on.
        let pouring = self.open_mode == Some(ActiveMode::Espresso) && self.flowed;
        if !pouring {
            return;
        }
        // A reading missed (the sim's scale reports every step), and the load
        // changed meanwhile.
        if gap_ms > STEP_MS && load_step != 0.0 && load != 0.0 {
            #[allow(clippy::cast_precision_loss)]
            let gap_s = gap_ms.min(60_000) as f32 / 1000.0;
            let step = gain + load_step;
            let accepted = if gap_ms >= 1_000 {
                // A return: `Tared` has no check; after the gate, the core's
                // band, `last − 3 g` to `last + 1.5 × poured + 5 g`.
                !self.gate_seen || (-3.0..=1.5 * gain.max(0.0) + 5.0).contains(&step)
            } else {
                // Not a return: the step band, `3 g + 12 g/s × dt`.
                step.abs() <= 3.0 + 12.0 * gap_s
            };
            if accepted && !std::mem::replace(&mut self.unseen_load, true) {
                self.coverage.unseen_loads += 1;
            }
        }
        if cup >= 5.0 {
            let since = *self.gate_since.get_or_insert(self.now);
            self.gate_seen |= self.now.saturating_sub(since) >= 1_000;
        } else {
            self.gate_since = None;
        }
    }

    /// I13's zero for a new espresso session: what the scale reads now.
    fn scale_zero_at_open(&mut self) {
        self.unseen_load = false;
        self.gate_since = None;
        self.gate_seen = false;
        self.last_cup_g = self.true_cup_g();
        self.last_load = self.scale.as_ref().map_or(0.0, |s| s.load_now(self.now));
        let (now, cup) = (self.now, self.cup.grams);
        let reads = self
            .scale
            .as_mut()
            .map(|scale| scale.session_opened(now, cup));
        // The gate and rule (b) assume a session starts from a zeroed cup: a
        // tare at the start, or a scale already near zero (the pre-shot zero
        // correction's 2 g). With auto-tare off on a loaded scale, the zero is
        // the user's and the design makes no weight-stop promise.
        self.opened_near_zero = self.auto_tare || reads.is_none_or(|g| g.abs() <= 2.0);
    }

    /// I6 both ways: the core's reading of the session matches the oracle's.
    /// "No scale" outranks the reason, and a core attached during an outage
    /// has no scale until the shells' next connect.
    fn check_session(&mut self) {
        let blocked = self.core.weight_stop_blocked_by();
        // The weight row is espresso's; a hot-water pour's own effects are
        // checked at the tare, the skip and the stop.
        if self.open_mode != Some(ActiveMode::Espresso)
            || self.scale.is_none()
            || self.weight_target().is_none()
            || blocked == Some("no-scale")
        {
            return;
        }
        let core_unseen = blocked == Some("began-unseen");
        if core_unseen != self.unseen {
            let unseen = self.unseen;
            self.violate(
                Invariant::I6,
                format!("core says unseen {core_unseen}, oracle {unseen}"),
            );
        }
    }

    fn step(&mut self) {
        self.now += STEP_MS;
        let poured = self.de1.step(STEP_MS);
        if self.de1.pours_into_cup() {
            self.cup.pour(poured);
        }
        for write in self.lane.step(self.now) {
            self.land(write);
        }
        let down = self.link.down(self.now);
        if !down {
            if self.was_down {
                self.was_down = false;
                self.reported = None;
            }
            if std::mem::take(&mut self.read_requested) {
                // A read's reply comes whatever was reported before.
                self.reported = None;
            }
            self.report_state();
            if self.now.is_multiple_of(SAMPLE_MS)
                && self.de1.sends_samples()
                && self.now >= self.link.samples_muted_until
            {
                // The DE1's own counter: mains half-cycles, 120 a second at
                // 60 Hz, running whether or not samples are sent.
                #[allow(clippy::cast_possible_truncation)]
                {
                    self.sample_clock = (self.now * 120 / 1_000) as u16;
                }
                self.skips_since_sample = 0;
                self.samples_heard += 1;
                let sample = self.de1.sample(self.sample_clock);
                if self.de1.group_flow() >= 0.3 {
                    self.flow_samples_since_stop += 1;
                }
                if let Some((before, at, outage)) = self.return_check.take() {
                    if self.now.saturating_sub(at) >= 1_000 {
                        // Too late to settle it: the core's rule too.
                        self.outage_unsure();
                    } else if before.is_some_and(|b| self.de1.frame < b) {
                        // A new shot, begun during the outage: it owes nothing.
                        // A mirror follows the hub, which saw it begin.
                        self.replacing = true;
                        self.unseen = !self.read_only;
                        self.owed_last = None;
                        self.stops_in_episode = 0;
                        self.stopped_this_flow = false;
                        self.alerts = 0;
                    } else if outage > 60_000 {
                        self.outage_unsure();
                    }
                }
                if self.de1.state == MachineState::Espresso {
                    self.last_sample_frame = Some(self.de1.frame);
                }
                let out = self
                    .core
                    .on_notification(Source::De1ShotSample, &sample, self.now);
                self.ingest(out, Who::Core);
                self.check_replaced();
            }
        }
        let reading = self
            .scale
            .as_mut()
            .and_then(|s| s.reading(self.now, self.cup.grams));
        // A re-zero mid-pour is a disturbance; between pours it's the zero.
        let pouring = self.open_mode == Some(ActiveMode::Espresso) && self.flowed;
        let cup = self.cup.grams;
        let rezero = self.scale.as_mut().and_then(SimScale::take_rezero);
        if let Some(scale) = self.scale.as_mut()
            && rezero.is_some()
            && !pouring
        {
            scale.zero_is_users(cup);
        }
        // §3.5's known limit: a re-zero during a gap before the gate. `Tared`
        // has no returning check, so the core can't see it, and a virtual zero
        // it latched for a load that moved since the old zero (a hand tared in
        // and lifted, a spoon) is then off by that load (#389).
        let gap_ms = self
            .last_weight_ms
            .map_or(u64::MAX, |at| self.now.saturating_sub(at));
        if pouring
            && !self.gate_seen
            && gap_ms >= 1_000
            && rezero.is_some_and(|moved| moved > 0.0)
            && !std::mem::replace(&mut self.unseen_load, true)
        {
            self.coverage.unseen_loads += 1;
        }
        if let Some(grams) = reading {
            self.watch_unseen_load();
            self.stale_open = false;
            self.dead_open = false;
            self.weights_heard += 1;
            self.last_weight_ms = Some(self.now);
            let frame = SimScale::frame(grams);
            let out = self
                .core
                .on_notification(Source::ScaleWeight, &frame, self.now);
            self.ingest(out, Who::Core);
        }
        let woken = self.wake_at.is_some_and(|at| at <= self.now);
        if self.now.is_multiple_of(TICK_MS) || woken {
            if woken {
                self.wake_at = None;
            }
            let out = self.core.on_tick(self.now);
            self.ingest(out, Who::Core);
        }
        if self
            .return_check
            .is_some_and(|(_, at, _)| self.now.saturating_sub(at) >= 1_000)
        {
            self.return_check = None;
            self.outage_unsure();
        }
        self.check_session();
        self.check_legs();
        self.check_auto_stop();
        self.check_retries(down);
    }

    /// I20, once the core has the input that opened a new session in the
    /// same mode: its volume is 0, and its auto-stop, if it has one yet, began
    /// now. The integrator restarts too, so even the sample that settled an
    /// outage adds nothing.
    fn check_replaced(&mut self) {
        if !std::mem::take(&mut self.replacing) {
            return;
        }
        self.coverage.replaced += 1;
        let pour = self.core.pour_ml();
        if pour > 0.001 {
            self.violate(Invariant::I20, format!("new session has {pour} ml poured"));
        }
        if let Some(view) = self.core.auto_stop_view()
            && view.started != Duration::from_millis(self.now)
        {
            let started = view.started;
            self.violate(
                Invariant::I20,
                format!("new session's auto-stop began at {started:?}"),
            );
        }
    }

    /// I21: a session the core heard flowing has its auto-stop, for its own
    /// mode: espresso from first flow, an unknown substate included, and hot
    /// water from its start.
    fn check_auto_stop(&mut self) {
        let mode = self.heard.and_then(ActiveMode::of);
        let flowing = self.heard_sub.is_some_and(|s| {
            !de1_domain::session::is_pre_flow(s) && !de1_domain::session::is_wind_down(s)
        });
        let needs = match mode {
            Some(ActiveMode::Espresso) => flowing,
            Some(ActiveMode::HotWater) => true,
            _ => false,
        };
        if mode == Some(ActiveMode::Espresso)
            && flowing
            && self.heard_sub == Some(SubState::Unknown)
            && !std::mem::replace(&mut self.unknown_flow, true)
        {
            self.coverage.unknown_flows += 1;
        }
        if !needs || self.open_mode != mode {
            return;
        }
        let has = self.core.auto_stop_view().map(|v| v.mode);
        if has != mode {
            self.violate(
                Invariant::I21,
                format!("{mode:?} session flowing with auto-stop {has:?}"),
            );
        }
    }

    /// No sample settled a return, or the outage was too long to tell: with
    /// nothing owed, the core refuses the scale for the rest of the shot.
    fn outage_unsure(&mut self) {
        if self.owed_last.is_none() && !self.read_only {
            self.unseen = true;
        }
    }

    /// The owed episode by the core's writes, from each state it hears.
    fn track_stop(&mut self, substate: u8) {
        // An unnamed byte is `Unknown`, as the core decodes it.
        let sub = Some(SubState::try_from(substate).unwrap_or(SubState::Unknown));
        let first = !self.state_heard_on_link;
        let flowing = sub.is_some_and(|s| {
            !de1_domain::session::is_pre_flow(s) && !de1_domain::session::is_wind_down(s)
        });
        if first {
            self.return_check = None;
        }
        if first
            && self.de1.state == MachineState::Espresso
            && self.open_mode == Some(ActiveMode::Espresso)
            && flowing
        {
            let outage = self.down_at.map_or(u64::MAX, |at| self.now - at);
            self.return_check = Some((self.last_sample_frame, self.now, outage));
        }
        if ActiveMode::of(self.de1.state) != Some(ActiveMode::Espresso) {
            self.return_check = None;
        }
        let ended = ActiveMode::of(self.de1.state) != self.open_mode
            || sub.is_some_and(de1_domain::session::is_wind_down);
        if ended {
            self.owed_last = None;
            self.stops_in_episode = 0;
            self.alerts = 0;
        } else if first && self.owed_last.is_some() {
            // Back from an outage: the retry clock restarts here.
            self.owed_last = Some(self.now);
        }
        self.heard_sub = sub;
    }

    /// I2 on a `Stop`, and the start or the next beat of an owed episode.
    fn watch_stop(&mut self, who: Who) {
        let mode = self.heard.and_then(ActiveMode::of);
        let winding = self
            .heard_sub
            .is_some_and(de1_domain::session::is_wind_down);
        if mode.is_none() {
            self.violate(Invariant::I2, "Stop with no session".into());
        }
        if who == Who::Core && winding {
            self.violate(
                Invariant::I2,
                "Stop from the core while winding down".into(),
            );
        }
        if !winding && mode.is_some() {
            self.owed_last = Some(self.now);
            self.stops_in_episode += 1;
        }
        self.flow_samples_since_stop = 0;
    }

    /// I1: an owed stop is retried within its interval while the machine, as
    /// the core heard it, still flows.
    fn check_retries(&mut self, down: bool) {
        let Some(last) = self.owed_last else {
            return;
        };
        let flowing = self.heard_sub.is_some_and(|s| {
            !de1_domain::session::is_pre_flow(s) && !de1_domain::session::is_wind_down(s)
        });
        let steam = self.heard == Some(MachineState::Steam);
        // "Link up" is the core's: a state heard on this link (DESIGN.md §3.3).
        if self.read_only
            || down
            || !self.state_heard_on_link
            || !flowing
            || self.stops_in_episode > 30
        {
            return;
        }
        let interval = if steam { 2_000 } else { 1_000 };
        // The core's clock moves on an input: a tick at worst, and an outage
        // check waits a second for its sample.
        if self.now.saturating_sub(last) > interval + TICK_MS + 1_100 {
            let gap = self.now - last;
            self.violate(Invariant::I1, format!("no Stop for {gap} ms with one owed"));
            self.owed_last = Some(self.now);
        }
    }

    /// Whether the core's weight could be trusted now, by the world's own
    /// facts. `slack_ms` allows for the core's clock, which only moves on an
    /// input: with samples muted that's the next tick.
    fn weight_trustable(&self, slack_ms: u64) -> bool {
        self.scale.is_some()
            && !self.unseen
            && self
                .last_weight_ms
                .is_some_and(|at| self.now.saturating_sub(at) < 1_000 + slack_ms)
    }

    /// I4 and I5 over the core's projection.
    fn check_legs(&mut self) {
        if self.read_only {
            return;
        }
        let (armed, targets) = self.core.stop_targets_projection();
        if armed && targets.weight.is_some() && !self.weight_trustable(TICK_MS + 100) {
            let detail = format!("weight leg live with scale {:?}", self.last_weight_ms);
            self.violate(Invariant::I5, detail);
        }
        let flowing = self.heard == Some(MachineState::Espresso)
            && self.de1.state == MachineState::Espresso
            && self.de1.flowing();
        // `armed` needs a leg now, so the core's flowing is its auto-stop.
        let core_flowing = self
            .core
            .auto_stop_view()
            .is_some_and(|v| v.mode == ActiveMode::Espresso);
        let unguarded = flowing
            && core_flowing
            && self.weight_target().is_some()
            && targets.weight.is_none()
            && targets.volume.is_none()
            && targets.max_time.is_none()
            && self.core.weight_stop_blocked_by().is_none();
        if !unguarded {
            self.unguarded_since = None;
            return;
        }
        let since = *self.unguarded_since.get_or_insert(self.now);
        if self.now.saturating_sub(since) > 6_500 {
            self.violate(
                Invariant::I4,
                format!("no leg and no reason since {since} ms"),
            );
            self.unguarded_since = None;
        }
    }

    fn land(&mut self, write: Write) {
        if self.link.down(self.now) {
            return;
        }
        if let Some(data) = write.settings {
            self.de1.on_settings(&data);
        }
        if let Some((header, data)) = write.profile {
            let source = if header {
                Source::De1HeaderAck
            } else {
                Source::De1FrameAck
            };
            let out = self.core.on_notification(source, &data, self.now);
            self.ingest(out, Who::Core);
        }
        if let Some(byte) = write.requested {
            if self.link.lose_writes > 0 {
                self.link.lose_writes -= 1;
                return;
            }
            let was_cup = self.de1.state;
            self.de1.on_request(byte);
            if self.link.duplicate_writes > 0 {
                self.link.duplicate_writes -= 1;
                self.de1.on_request(byte);
            }
            if was_cup == MachineState::Idle && self.de1.active() {
                self.cup.headspace_ml = self.headspace_ml;
            }
        }
    }

    fn violate(&mut self, invariant: Invariant, detail: String) {
        self.violations.push(Violation {
            invariant,
            at_ms: self.now,
            detail,
        });
    }

    /// Take a core output: watch it, then queue its writes.
    fn ingest(&mut self, out: CoreOutput, who: Who) {
        // A mirror's core sends nothing of its own, and nothing that drives
        // the machine even for its user's tap: no carve-out.
        let drives_machine = out.commands.iter().any(|c| {
            matches!(
                c,
                Command::Stop
                    | Command::WriteCharacteristic {
                        target: WriteTarget::De1RequestedState
                            | WriteTarget::De1ProfileHeader
                            | WriteTarget::De1ProfileFrame,
                        ..
                    }
            )
        });
        if self.read_only && (drives_machine || (who == Who::Core && !out.commands.is_empty())) {
            self.violate(Invariant::I10, format!("{:?}", out.commands));
        }
        for event in &out.events {
            self.watch_event(event);
        }
        for command in out.commands {
            self.watch_command(&command, who);
            match command {
                Command::WriteCharacteristic { target, data } => {
                    let write = match target {
                        WriteTarget::De1RequestedState => Some(Write {
                            requested: data.first().copied(),
                            settings: None,
                            profile: None,
                            stop: false,
                        }),
                        WriteTarget::De1ShotSettings => Some(Write {
                            requested: None,
                            settings: Some(data),
                            profile: None,
                            stop: false,
                        }),
                        WriteTarget::De1ProfileHeader => Some(Write {
                            requested: None,
                            settings: None,
                            profile: Some((true, data)),
                            stop: false,
                        }),
                        WriteTarget::De1ProfileFrame => Some(Write {
                            requested: None,
                            settings: None,
                            profile: Some((false, data)),
                            stop: false,
                        }),
                        _ => None,
                    };
                    if let Some(write) = write
                        && !self.read_only
                    {
                        self.lane.push(write);
                    }
                }
                Command::Stop => {
                    self.coverage.stop_commands += 1;
                    self.watch_stop(who);
                    self.requests.push((MachineState::Idle as u8, true));
                    if !self.read_only {
                        self.lane.push(Write {
                            requested: Some(MachineState::Idle as u8),
                            settings: None,
                            profile: None,
                            stop: true,
                        });
                    }
                }
                Command::CancelStop => self.lane.cancel_stop(),
                Command::ReadState => {
                    self.coverage.read_states += 1;
                    self.read_requested = true;
                }
                Command::WriteScale { data } => {
                    if weight_is_tare(&data) && self.unseen {
                        self.violate(Invariant::I6, "tare while unseen".into());
                    }
                    if weight_is_tare(&data)
                        && let Some(scale) = &mut self.scale
                    {
                        self.coverage.tares += 1;
                        scale.tare(self.now);
                    }
                }
                _ => {}
            }
        }
    }

    fn watch_event(&mut self, event: &Event) {
        match event {
            Event::ShotStarted => self.coverage.shots += 1,
            Event::WaterSessionStarted { .. } => self.coverage.water += 1,
            Event::SteamSessionStarted => self.coverage.steam += 1,
            Event::WakeAt { at_ms } => {
                #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
                let at = at_ms.max(0.0).ceil() as u64;
                self.wake_at = Some(at);
            }
            Event::StopRetried { attempt, .. } => {
                self.coverage.retries += 1;
                if *attempt > 30 && self.flow_samples_since_stop < 2 {
                    let samples = self.flow_samples_since_stop;
                    self.violate(
                        Invariant::I17,
                        format!("retry {attempt} on {samples} samples at flow"),
                    );
                }
            }
            Event::StopNotHonoured { .. } => {
                self.coverage.not_honoured += 1;
                self.alerts += 1;
                if self.alerts > 1 {
                    self.violate(
                        Invariant::I11,
                        "StopNotHonoured twice in one episode".into(),
                    );
                }
            }
            Event::MachineRequestHeld { state } => self.user_held = Some(*state as u8),
            Event::MachineRequestDropped { .. } => self.user_held = None,
            Event::ShotStartEnded { end, .. } => {
                self.starting = false;
                self.start_from = None;
                if *end == de1_app::StartEnd::Started {
                    self.coverage.started += 1;
                }
            }
            Event::ScaleStale => {
                self.coverage.stale += 1;
                if std::mem::replace(&mut self.stale_open, true) {
                    self.violate(Invariant::I11, "ScaleStale twice in one episode".into());
                }
            }
            Event::ScaleLinkDead { .. } => {
                self.coverage.dead += 1;
                if std::mem::replace(&mut self.dead_open, true) {
                    self.violate(Invariant::I11, "ScaleLinkDead twice in one episode".into());
                }
            }
            Event::StopTriggered { reason } => {
                self.coverage.stops += 1;
                if std::mem::replace(&mut self.stopped_this_flow, true) {
                    self.violate(Invariant::I11, "StopTriggered twice in one flow".into());
                }
                // A leg firing makes the session owe its stop, even while the
                // outage check holds the first write (`DESIGN.md` §3.3): an
                // unsure return then keeps the stop rather than refusing weight.
                if !self.read_only && self.owed_last.is_none() {
                    self.owed_last = Some(self.now);
                }
                match reason {
                    StopReason::Weight | StopReason::EstimatedWeight => {
                        self.coverage.weight_stops += 1;
                        if *reason == StopReason::EstimatedWeight {
                            self.coverage.estimated_stops += 1;
                        }
                        if self.unseen {
                            self.violate(Invariant::I6, format!("{reason:?} stop while unseen"));
                        }
                        // Espresso's target; what's in the cup since the
                        // last tare a person meant.
                        let grams = self
                            .scale
                            .as_ref()
                            .map_or(self.cup.grams, |s| s.true_net(self.cup.grams));
                        if !self.read_only
                            && !self.unseen_load
                            && self.opened_near_zero
                            && self.open_mode == Some(ActiveMode::Espresso)
                            // Not a stop that lands on another mode the core
                            // didn't hear the machine enter.
                            && self.de1.state == MachineState::Espresso
                            && let Some(target) = self.weight_target()
                            && grams < target - WEIGHT_TOLERANCE_G
                        {
                            self.violate(
                                Invariant::I13,
                                format!("{reason:?} stop at a true {grams} g of {target} g"),
                            );
                        }
                    }
                    StopReason::Volume => self.coverage.volume_stops += 1,
                    _ => {}
                }
            }
            _ => {}
        }
    }

    fn watch_command(&mut self, command: &Command, who: Who) {
        let Command::WriteCharacteristic { target, data } = command else {
            return;
        };
        match target {
            WriteTarget::De1RequestedState => {
                let Some(&byte) = data.first() else { return };
                self.requests.push((byte, false));
                if byte == MachineState::SkipToNext as u8 {
                    self.coverage.skips += 1;
                    if !self.read_only && !self.weight_trustable(TICK_MS + 100) {
                        self.violate(Invariant::I5, "SkipToNext on an untrusted weight".into());
                    }
                    if self.unseen {
                        self.violate(Invariant::I6, "SkipToNext while unseen".into());
                    }
                    self.skips_since_sample += 1;
                    if self.skips_since_sample > 1 {
                        self.violate(Invariant::I7, "two skips with no sample between".into());
                    }
                }
                let flow_request = [
                    MachineState::Espresso,
                    MachineState::Steam,
                    MachineState::HotWater,
                    MachineState::HotWaterRinse,
                ]
                .iter()
                .any(|s| *s as u8 == byte);
                // A start may bounce the flow that ran when Start was tapped:
                // the user asked.
                let start_bounce =
                    self.starting && self.heard.is_some() && self.heard == self.start_from;
                // A user's request released from the guard is still the user's.
                let user_release = who == Who::Core && self.user_held == Some(byte);
                if user_release {
                    self.user_held = None;
                    self.coverage.held_releases += 1;
                }
                if who == Who::Core
                    && flow_request
                    && !start_bounce
                    && !user_release
                    && let Some(heard) = self.heard
                    && heard.is_flow_state()
                    && heard != MachineState::HotWaterRinse
                    && heard as u8 != byte
                {
                    self.violate(
                        Invariant::I3,
                        format!("request {byte} while the machine reported {heard:?}"),
                    );
                }
            }
            WriteTarget::De1ShotSettings => {
                if let Ok(packet) = ShotSettings::decode(data)
                    && packet.steam_temp_c > self.steam_target_c
                {
                    let (sent, target) = (packet.steam_temp_c, self.steam_target_c);
                    self.violate(Invariant::I9, format!("steam {sent} over target {target}"));
                }
            }
            _ => {}
        }
    }
}
