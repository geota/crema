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
    /// `StopTriggered` on weight.
    pub weight_stops: u32,
    /// `StopTriggered` on volume.
    pub volume_stops: u32,
    /// `StopRetried`.
    pub retries: u32,
    /// `SkipToNext` writes.
    pub skips: u32,
    /// `Command::Stop`.
    pub stop_commands: u32,
    /// App starts tapped while another flow ran.
    pub starts_over_a_flow: u32,
    /// Sessions first seen flowing.
    pub unseen: u32,
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
    /// The profiles an app start has used, one per yield.
    profiles: Vec<CremaProfile>,
    /// The flow heard when Start was tapped, until the machine leaves it.
    start_from: Option<MachineState>,
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
            starting: false,
            start_from: None,
            profiles: Vec::new(),
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
        self.core.set_profile_target_weight(self.profile_target_g);
        self.core.set_shot_target_weight(self.dial_g);
        self.core.set_profile_volume_limit(self.volume_limit_ml);
        self.core.set_max_shot_duration(self.max_time_s);
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
                self.cup = SimCup {
                    grams: self.cup.grams,
                    headspace_ml: self.headspace_ml,
                };
                self.de1.enter(state);
            }
            Op::AppStart { yield_g } => self.app_start(yield_g),
            Op::UserStop => {
                let out = self.core.request_machine_state(MachineState::Idle);
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
                    self.state_heard_on_link = false;
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
        // The same library profile each time for a yield, as a user taps
        // Start on the profile they brew: a second start needs no upload.
        let profile = match self.profiles.iter().find(|p| p.yield_out == yield_g) {
            Some(p) => p.clone(),
            None => {
                let p = CremaProfile {
                    yield_out: yield_g,
                    ..de1_domain::blank_profile(&BrewDefaults::default())
                };
                self.profiles.push(p.clone());
                p
            }
        };
        let result = self.core.start_shot(
            &profile,
            &BrewOverrides::default(),
            Duration::from_millis(self.now),
        );
        if result.accepted {
            self.coverage.starts += 1;
            self.starting = true;
            self.start_from = self.heard.filter(|s| s.is_flow_state());
            if self.start_from.is_some() {
                self.coverage.starts_over_a_flow += 1;
            }
            self.cup = SimCup {
                grams: self.cup.grams,
                headspace_ml: self.headspace_ml,
            };
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
                volume_ml: 120.0,
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
        if self.start_from != self.heard {
            self.start_from = None;
        }
        self.track_session(bytes[1]);
        let out = self
            .core
            .on_notification(Source::De1State, &bytes, self.now);
        self.ingest(out, Who::Core);
    }

    /// I6's oracle, apart from the core's own session: a mode opens unseen
    /// only on the first state of a link, past the pre-flow substates.
    fn track_session(&mut self, substate: u8) {
        let first = !std::mem::replace(&mut self.state_heard_on_link, true);
        let mode = ActiveMode::of(self.de1.state);
        if mode == self.open_mode {
            return;
        }
        self.open_mode = mode;
        let pre_flow = [
            SubState::Ready,
            SubState::Heating,
            SubState::FinalHeating,
            SubState::Stabilising,
        ]
        .iter()
        .any(|s| *s as u8 == substate);
        self.unseen = !self.read_only && mode.is_some() && first && !pre_flow;
        let weighs = matches!(mode, Some(ActiveMode::Espresso | ActiveMode::HotWater))
            && self.scale.is_some()
            && self.weight_target().is_some();
        if self.unseen && weighs {
            self.coverage.unseen += 1;
        }
    }

    /// I6 both ways: the core's reading of the session matches the oracle's.
    /// "No scale" outranks the reason, and a core attached during an outage
    /// has no scale until the shells' next connect.
    fn check_session(&mut self) {
        let blocked = self.core.weight_stop_blocked_by();
        if self.scale.is_none() || self.weight_target().is_none() || blocked == Some("no-scale") {
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
            self.report_state();
            if self.now.is_multiple_of(SAMPLE_MS)
                && self.de1.sends_samples()
                && self.now >= self.link.samples_muted_until
            {
                self.sample_clock = self.sample_clock.wrapping_add(24);
                self.skips_since_sample = 0;
                self.samples_heard += 1;
                let sample = self.de1.sample(self.sample_clock);
                let out = self
                    .core
                    .on_notification(Source::De1ShotSample, &sample, self.now);
                self.ingest(out, Who::Core);
            }
        }
        let reading = self
            .scale
            .as_mut()
            .and_then(|s| s.reading(self.now, self.cup.grams));
        if let Some(grams) = reading {
            self.stale_open = false;
            self.dead_open = false;
            self.weights_heard += 1;
            let frame = SimScale::frame(grams);
            let out = self
                .core
                .on_notification(Source::ScaleWeight, &frame, self.now);
            self.ingest(out, Who::Core);
        }
        if self.now.is_multiple_of(TICK_MS) {
            let out = self.core.on_tick(self.now);
            self.ingest(out, Who::Core);
        }
        self.check_session();
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
            Event::StopRetried { .. } => self.coverage.retries += 1,
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
                match reason {
                    StopReason::Weight => {
                        self.coverage.weight_stops += 1;
                        if self.unseen {
                            self.violate(Invariant::I6, "weight stop while unseen".into());
                        }
                        if !self.read_only
                            && let Some(target) = self.weight_target()
                            && self.cup.grams < target - WEIGHT_TOLERANCE_G
                        {
                            let grams = self.cup.grams;
                            self.violate(
                                Invariant::I13,
                                format!("weight stop at {grams} g of {target} g"),
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
                if who == Who::Core
                    && flow_request
                    && !start_bounce
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
