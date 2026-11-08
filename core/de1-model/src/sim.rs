//! The simulated machine, cup, scale, link and write lane.

use std::collections::VecDeque;

use de1_protocol::{MachineState, ShotSettings, SubState};

/// How a flow ends on this simulated firmware.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Ending {
    /// No `Ending` substate: straight to Idle.
    None,
    /// `Ending` for a second, then Idle.
    Prompt,
    /// The pour runs on for 1.5 s before `Ending`.
    Late,
    /// `Ending`, back to `Pouring`, then `Ending` again.
    BackToPouring,
}

/// What varies between firmwares, or isn't known yet.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct De1Params {
    /// The DE1 reports Preinfusion. The capture on disk doesn't.
    pub preinfusion: bool,
    /// How a flow winds down.
    pub ending: Ending,
    /// Idle requests that are accepted but ignored, from the start.
    pub idle_ignored: u32,
    /// Two-tap steam: the first Idle goes to Puffing, a second ends it.
    pub two_tap_steam: bool,
}

impl Default for De1Params {
    fn default() -> Self {
        Self {
            preinfusion: true,
            ending: Ending::Prompt,
            idle_ignored: 0,
            two_tap_steam: false,
        }
    }
}

/// The espresso profile's frame length and count.
const FRAME_MS: u64 = 20_000;
const FRAMES: u8 = 3;
/// The firmware's flush timeout.
const FLUSH_MS: u64 = 10_000;
/// `group_flow` climbs at about this rate in `Ending`, ml/s per second, up to
/// [`ENDING_FLOW`]. Both captures on disk show it: the pump is off, but the
/// reported flow reaches 4 ml/s, so reported flow alone can't prove water.
const ENDING_RAMP: f32 = 1.15;
const ENDING_FLOW: f32 = 4.0;
/// After `Ending`, samples keep coming while `group_flow` decays with this time
/// constant, ms. The 2026-05-17 capture crosses 0.3 ml/s about 2.3 s into Idle.
const TAIL_TAU_MS: f32 = 800.0;
/// Under this, the tail is over and samples stop.
const TAIL_END: f32 = 0.005;

/// The machine. Time moves only through [`step`](Self::step).
#[derive(Debug, Clone)]
pub struct SimDe1 {
    params: De1Params,
    /// Reported state.
    pub state: MachineState,
    /// Reported substate.
    pub sub: SubState,
    /// The frame the machine is on.
    pub frame: u8,
    /// Substates still to run in this mode, with their remaining time. The
    /// last one ending returns the machine to Idle.
    script: VecDeque<(SubState, u64)>,
    in_mode_ms: u64,
    poured_ml: f32,
    settings: Option<ShotSettings>,
    ignored_left: u32,
    winding_down: bool,
    group_flow: f32,
}

impl SimDe1 {
    /// An idle machine.
    #[must_use]
    pub fn new(params: De1Params) -> Self {
        Self {
            params,
            state: MachineState::Idle,
            sub: SubState::Ready,
            frame: 0,
            script: VecDeque::new(),
            in_mode_ms: 0,
            poured_ml: 0.0,
            settings: None,
            ignored_left: params.idle_ignored,
            winding_down: false,
            group_flow: 0.0,
        }
    }

    /// Whether the machine is in a flow state, in any substate.
    #[must_use]
    pub fn active(&self) -> bool {
        self.state.is_flow_state()
    }

    /// The water really leaving the group now, ml/s.
    #[must_use]
    pub fn flow_ml_s(&self) -> f32 {
        match self.sub {
            SubState::Preinfusion | SubState::Pouring => 2.0,
            SubState::Ending => 0.5,
            _ => 0.0,
        }
    }

    /// The flow it reports in its samples, ml/s. Not the same thing: see
    /// [`ENDING_RAMP`].
    #[must_use]
    pub fn group_flow(&self) -> f32 {
        self.group_flow
    }

    /// Whether it's sending shot samples: in a flow state, and through the
    /// tail after one.
    #[must_use]
    pub fn sends_samples(&self) -> bool {
        self.active() || self.group_flow > 0.0
    }

    /// The wire form of the state report.
    #[must_use]
    pub fn state_bytes(&self) -> [u8; 2] {
        [self.state as u8, self.sub as u8]
    }

    /// Whether water reaches the cup in this mode.
    #[must_use]
    pub fn pours_into_cup(&self) -> bool {
        matches!(self.state, MachineState::Espresso | MachineState::HotWater)
    }

    /// Enter a flow state, as a group-head press or a request does. Only Idle
    /// (or a wake from Sleep) can enter one.
    pub fn enter(&mut self, state: MachineState) {
        if self.state != MachineState::Idle {
            return;
        }
        self.state = state;
        self.frame = 0;
        self.in_mode_ms = 0;
        self.poured_ml = 0.0;
        self.winding_down = false;
        self.script = match state {
            MachineState::Espresso => {
                let mut s =
                    VecDeque::from([(SubState::Heating, 1_000), (SubState::Stabilising, 500)]);
                let pour = FRAME_MS * u64::from(FRAMES);
                if self.params.preinfusion {
                    s.push_back((SubState::Preinfusion, 5_000));
                }
                s.push_back((SubState::Pouring, pour));
                s
            }
            MachineState::HotWater | MachineState::HotWaterRinse => {
                VecDeque::from([(SubState::Pouring, u64::MAX)])
            }
            MachineState::Steam => VecDeque::from([(SubState::Steaming, u64::MAX)]),
            _ => VecDeque::new(),
        };
        self.sync_sub();
    }

    /// Whatever is running winds down, on this firmware's terms.
    fn end(&mut self) {
        self.winding_down = true;
        self.script = match self.params.ending {
            Ending::None => VecDeque::new(),
            Ending::Prompt => VecDeque::from([(SubState::Ending, 1_000)]),
            Ending::Late => VecDeque::from([(self.sub, 1_500), (SubState::Ending, 1_000)]),
            Ending::BackToPouring => VecDeque::from([
                (SubState::Ending, 300),
                (SubState::Pouring, 500),
                (SubState::Ending, 1_000),
            ]),
        };
        self.sync_sub();
    }

    fn sync_sub(&mut self) {
        match self.script.front() {
            Some((sub, _)) => self.sub = *sub,
            None => {
                self.state = MachineState::Idle;
                self.sub = SubState::Ready;
            }
        }
    }

    /// A write to the requested-state characteristic landed.
    pub fn on_request(&mut self, byte: u8) {
        if byte == MachineState::Idle as u8 {
            if self.ignored_left > 0 {
                self.ignored_left -= 1;
                return;
            }
            match (self.state, self.sub) {
                (MachineState::Idle, _) | (_, SubState::Ending) => {}
                (MachineState::Sleep, _) => self.state = MachineState::Idle,
                (MachineState::Steam, SubState::Puffing) => {
                    self.script.clear();
                    self.sync_sub();
                }
                (MachineState::Steam, _) if self.params.two_tap_steam => {
                    self.script = VecDeque::from([(SubState::Puffing, 5_000)]);
                    self.sync_sub();
                }
                _ => self.end(),
            }
        } else if byte == MachineState::SkipToNext as u8 {
            if self.state == MachineState::Espresso && self.flowing() {
                self.frame += 1;
                if self.frame >= FRAMES {
                    self.end();
                }
            }
        } else if let Some(state) = [
            MachineState::Espresso,
            MachineState::Steam,
            MachineState::HotWater,
            MachineState::HotWaterRinse,
        ]
        .into_iter()
        .find(|s| *s as u8 == byte)
        {
            self.enter(state);
        }
    }

    /// A write to the shot-settings characteristic landed.
    pub fn on_settings(&mut self, data: &[u8]) {
        if let Ok(settings) = ShotSettings::decode(data) {
            self.settings = Some(settings);
        }
    }

    #[must_use]
    pub fn flowing(&self) -> bool {
        matches!(
            self.sub,
            SubState::Preinfusion | SubState::Pouring | SubState::Steaming
        )
    }

    /// Advance `dt_ms`. Returns the water delivered, ml.
    pub fn step(&mut self, dt_ms: u64) -> f32 {
        let delivered = self.step_mode(dt_ms);
        self.step_group_flow(dt_ms);
        delivered
    }

    fn step_group_flow(&mut self, dt_ms: u64) {
        #[allow(clippy::cast_precision_loss)]
        let dt_ms = dt_ms as f32;
        self.group_flow = match self.sub {
            SubState::Preinfusion | SubState::Pouring => self.flow_ml_s(),
            SubState::Ending => (self.group_flow + ENDING_RAMP * dt_ms / 1000.0).min(ENDING_FLOW),
            _ => {
                let decayed = self.group_flow * (-dt_ms / TAIL_TAU_MS).exp();
                if decayed < TAIL_END { 0.0 } else { decayed }
            }
        };
    }

    fn step_mode(&mut self, dt_ms: u64) -> f32 {
        if !self.active() {
            return 0.0;
        }
        self.in_mode_ms += dt_ms;
        #[allow(clippy::cast_precision_loss)]
        let delivered = self.flow_ml_s() * dt_ms as f32 / 1000.0;
        self.poured_ml += delivered;
        if let Some((_, left)) = self.script.front_mut() {
            *left = left.saturating_sub(dt_ms);
            if *left == 0 {
                self.script.pop_front();
                if self.script.is_empty() && !self.winding_down {
                    self.end();
                } else {
                    self.sync_sub();
                }
            }
        }
        if self.state == MachineState::Espresso && self.sub == SubState::Pouring {
            let elapsed = u8::try_from(self.in_mode_ms / FRAME_MS).unwrap_or(FRAMES);
            self.frame = self.frame.max(elapsed.min(FRAMES - 1));
        }
        if self.flowing() && !self.winding_down && self.limit_reached() {
            self.end();
        }
        delivered
    }

    /// The firmware's own bound on this mode.
    fn limit_reached(&self) -> bool {
        #[allow(clippy::cast_precision_loss)]
        let elapsed_s = self.in_mode_ms as f32 / 1000.0;
        let packet = self.settings.as_ref();
        match self.state {
            MachineState::HotWater => {
                let volume = packet.map_or(250.0, |p| p.hot_water_volume_ml);
                let timeout = packet.map_or(60.0, |p| p.hot_water_timeout_s);
                self.poured_ml >= volume || elapsed_s >= timeout
            }
            MachineState::HotWaterRinse => self.in_mode_ms >= FLUSH_MS,
            MachineState::Steam => elapsed_s >= packet.map_or(60.0, |p| p.steam_timeout_s),
            _ => false,
        }
    }

    /// The machine's shot sample: time, flow, frame.
    #[must_use]
    pub fn sample(&self, sample_time: u16) -> [u8; 19] {
        let mut p = [0u8; 19];
        p[0..2].copy_from_slice(&sample_time.to_be_bytes());
        #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
        let flow = (self.group_flow * 4096.0) as u16;
        p[4..6].copy_from_slice(&flow.to_be_bytes());
        p[17] = self.frame;
        p
    }
}

/// What's physically in the cup. Water reaches it late: the capture shows
/// 55 ml poured with 0 g in the cup at 8 s.
#[derive(Debug, Clone, Default)]
pub struct SimCup {
    /// True weight, grams.
    pub grams: f32,
    /// Water still to pass before the cup sees any, ml.
    pub headspace_ml: f32,
}

impl SimCup {
    /// `ml` left the group.
    pub fn pour(&mut self, ml: f32) {
        let held = ml.min(self.headspace_ml);
        self.headspace_ml -= held;
        self.grams += ml - held;
    }
}

/// How the scale misbehaves.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum ScaleMode {
    /// Reports the cup.
    Healthy,
    /// Stops sending, for good.
    Stall,
    /// Connected, and has never sent anything.
    Silent,
    /// Off for the first half of each `period_ms`.
    Flap {
        /// Period, ms.
        period_ms: u64,
    },
    /// Zeroes itself once.
    Rezero,
    /// A hand on the cup: `grams` on top for `ms`, then back to healthy.
    Bump {
        /// Added weight.
        grams: f32,
        /// How long the hand stays, ms.
        ms: u64,
    },
    /// A step up that stays while this mode lasts.
    StepUp {
        /// Added weight.
        grams: f32,
    },
    /// Reads 0 while this mode lasts.
    TransientZero,
}

/// The scale.
#[derive(Debug, Clone)]
pub struct SimScale {
    mode: ScaleMode,
    bump_ends_at: Option<u64>,
    zero_g: f32,
    tare_at: Option<u64>,
    /// Write-to-effect tare latency, ms.
    pub tare_latency_ms: u64,
}

impl SimScale {
    /// A healthy scale.
    #[must_use]
    pub fn new(tare_latency_ms: u64) -> Self {
        Self {
            mode: ScaleMode::Healthy,
            bump_ends_at: None,
            zero_g: 0.0,
            tare_at: None,
            tare_latency_ms,
        }
    }

    /// Start misbehaving this way.
    pub fn set_mode(&mut self, mode: ScaleMode) {
        self.mode = mode;
        self.bump_ends_at = None;
    }

    /// A tare command landed at `now`.
    pub fn tare(&mut self, now: u64) {
        self.tare_at = Some(now + self.tare_latency_ms);
    }

    /// What's on the scale besides the cup at `now`, grams.
    fn load(&mut self, now: u64) -> f32 {
        match self.mode {
            ScaleMode::Bump { grams, ms } => {
                if now >= *self.bump_ends_at.get_or_insert(now + ms) {
                    self.set_mode(ScaleMode::Healthy);
                    0.0
                } else {
                    grams
                }
            }
            ScaleMode::StepUp { grams } => grams,
            _ => 0.0,
        }
    }

    /// The reading at `now`, if it sends one.
    pub fn reading(&mut self, now: u64, cup_g: f32) -> Option<f32> {
        // A tare zeroes everything on the platter, a hand included.
        let gross = cup_g + self.load(now);
        if self.tare_at.is_some_and(|at| now >= at) {
            self.tare_at = None;
            self.zero_g = gross;
        }
        let net = gross - self.zero_g;
        match self.mode {
            ScaleMode::Healthy | ScaleMode::Bump { .. } | ScaleMode::StepUp { .. } => Some(net),
            ScaleMode::Stall | ScaleMode::Silent => None,
            ScaleMode::Flap { period_ms } => (now % period_ms >= period_ms / 2).then_some(net),
            ScaleMode::Rezero => {
                self.zero_g = gross;
                self.mode = ScaleMode::Healthy;
                Some(0.0)
            }
            ScaleMode::TransientZero => Some(0.0),
        }
    }

    /// A Bookoo weight frame, the scale the probes use.
    #[must_use]
    pub fn frame(grams: f32) -> [u8; 20] {
        #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
        let centigrams = (grams.abs() * 100.0).round() as u32;
        let [_, high, mid, low] = centigrams.to_be_bytes();
        let sign = if grams < 0.0 { b'-' } else { b'+' };
        let mut p = [0u8; 20];
        p[..10].copy_from_slice(&[3, 0x0B, 0, 0, 0, 0, sign, high, mid, low]);
        p[19] = p[..19].iter().fold(0, |acc, b| acc ^ b);
        p
    }
}

/// What the BLE link does to traffic.
#[derive(Debug, Clone, Default)]
pub struct SimLink {
    /// Requested-state writes to lose.
    pub lose_writes: u32,
    /// Requested-state writes to land twice.
    pub duplicate_writes: u32,
    /// State reports to lose.
    pub lose_states: u32,
    /// The DE1 link is down until this time.
    pub down_until: Option<u64>,
    /// No shot samples until this time, while everything else stays up.
    pub samples_muted_until: u64,
}

impl SimLink {
    /// The DE1 link is down at `now`.
    #[must_use]
    pub fn down(&self, now: u64) -> bool {
        self.down_until.is_some_and(|until| now < until)
    }
}

/// One write queued in a shell's lane.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Write {
    /// Goes to the requested-state characteristic: the first byte.
    pub requested: Option<u8>,
    /// Goes to the shot-settings characteristic.
    pub settings: Option<Vec<u8>>,
    /// Goes to the profile characteristics, and is acked.
    pub profile: Option<(bool, Vec<u8>)>,
    /// A `Command::Stop`: the urgent lane, at most one outstanding.
    pub stop: bool,
}

/// A shell's GATT lane: one write at a time. A stop goes to the front of the
/// queue, and a second one is dropped while one is outstanding. The rule is the
/// design's; the real Kotlin and TypeScript lanes keep their own tests.
#[derive(Debug, Clone)]
pub struct SimLane {
    queue: VecDeque<Write>,
    in_flight: Option<(Write, u64, bool)>,
    /// The next write hangs until the timeout, and fails.
    pub hang_next: bool,
    /// The lane's write timeout: 5 s on Android, 10 s on web.
    pub timeout_ms: u64,
    /// Swap the two oldest queued writes, once.
    pub reorder_next: bool,
    /// Extra time on the air for the next write, ms.
    pub delay_next_ms: u64,
}

/// A write's time on the air.
const WRITE_MS: u64 = 40;

fn is_stop(write: &Write) -> bool {
    write.stop
}

impl SimLane {
    /// An empty lane.
    #[must_use]
    pub fn new(timeout_ms: u64) -> Self {
        Self {
            queue: VecDeque::new(),
            in_flight: None,
            hang_next: false,
            timeout_ms,
            reorder_next: false,
            delay_next_ms: 0,
        }
    }

    /// Queue a write.
    pub fn push(&mut self, write: Write) {
        if is_stop(&write) {
            let outstanding = self.queue.iter().any(is_stop)
                || self.in_flight.as_ref().is_some_and(|(w, ..)| is_stop(w));
            if !outstanding {
                self.queue.push_front(write);
            }
        } else {
            self.queue.push_back(write);
        }
    }

    /// `Command::CancelStop`: a stop not yet on the air is dropped.
    pub fn cancel_stop(&mut self) {
        self.queue.retain(|w| !is_stop(w));
    }

    /// The link went: what's queued dies with it.
    pub fn clear(&mut self) {
        self.queue.clear();
        self.in_flight = None;
    }

    /// The writes that landed by `now`.
    pub fn step(&mut self, now: u64) -> Vec<Write> {
        let mut landed = Vec::new();
        loop {
            if self.in_flight.is_none() {
                if self.reorder_next && self.queue.len() >= 2 {
                    self.queue.swap(0, 1);
                    self.reorder_next = false;
                }
                let Some(write) = self.queue.pop_front() else {
                    return landed;
                };
                let hung = std::mem::take(&mut self.hang_next);
                let delay = std::mem::take(&mut self.delay_next_ms);
                let took = if hung {
                    self.timeout_ms
                } else {
                    WRITE_MS + delay
                };
                self.in_flight = Some((write, now + took, hung));
            }
            match self.in_flight.take() {
                Some((write, done, hung)) if done <= now => {
                    if !hung {
                        landed.push(write);
                    }
                }
                other => {
                    self.in_flight = other;
                    return landed;
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(de1: &mut SimDe1, ms: u64) -> f32 {
        (0..ms / 100).map(|_| de1.step(100)).sum()
    }

    #[test]
    fn espresso_reports_preinfusion_only_when_the_firmware_does() {
        let mut de1 = SimDe1::new(De1Params::default());
        de1.enter(MachineState::Espresso);
        run(&mut de1, 2_000);
        assert_eq!(de1.sub, SubState::Preinfusion);
        let mut de1 = SimDe1::new(De1Params {
            preinfusion: false,
            ..De1Params::default()
        });
        de1.enter(MachineState::Espresso);
        run(&mut de1, 2_000);
        assert_eq!(de1.sub, SubState::Pouring);
    }

    #[test]
    fn a_shot_runs_to_the_end_of_its_frames_then_winds_down() {
        let mut de1 = SimDe1::new(De1Params::default());
        de1.enter(MachineState::Espresso);
        run(&mut de1, 66_500);
        assert_eq!(de1.frame, FRAMES - 1);
        assert_eq!(de1.sub, SubState::Ending);
        run(&mut de1, 2_000);
        assert_eq!(de1.state, MachineState::Idle);
    }

    #[test]
    fn hot_water_ends_at_the_packet_volume() {
        let mut de1 = SimDe1::new(De1Params {
            ending: Ending::None,
            ..De1Params::default()
        });
        de1.enter(MachineState::HotWater);
        let poured = run(&mut de1, 200_000);
        assert_eq!(de1.state, MachineState::Idle);
        assert!(
            (119.0..=121.0).contains(&poured),
            "60 s at 2 ml/s: {poured}"
        );
    }

    #[test]
    fn two_tap_steam_needs_a_second_idle() {
        let mut de1 = SimDe1::new(De1Params {
            two_tap_steam: true,
            ..De1Params::default()
        });
        de1.enter(MachineState::Steam);
        let idle = MachineState::Idle as u8;
        de1.on_request(idle);
        assert_eq!(de1.sub, SubState::Puffing);
        de1.on_request(idle);
        assert_eq!(de1.state, MachineState::Idle);
    }

    #[test]
    fn an_ignoring_machine_honours_the_stop_after_the_ignored_ones() {
        let mut de1 = SimDe1::new(De1Params {
            idle_ignored: 1,
            ..De1Params::default()
        });
        de1.enter(MachineState::HotWater);
        let idle = MachineState::Idle as u8;
        de1.on_request(idle);
        assert_eq!(de1.sub, SubState::Pouring);
        de1.on_request(idle);
        assert_eq!(de1.sub, SubState::Ending);
    }

    #[test]
    fn a_late_ending_keeps_pouring_first() {
        let mut de1 = SimDe1::new(De1Params {
            ending: Ending::Late,
            ..De1Params::default()
        });
        de1.enter(MachineState::HotWater);
        de1.on_request(MachineState::Idle as u8);
        assert_eq!(de1.sub, SubState::Pouring);
        run(&mut de1, 1_500);
        assert_eq!(de1.sub, SubState::Ending);
    }

    #[test]
    fn flow_states_are_entered_only_from_idle() {
        let mut de1 = SimDe1::new(De1Params::default());
        de1.enter(MachineState::Steam);
        de1.on_request(MachineState::Espresso as u8);
        assert_eq!(de1.state, MachineState::Steam);
    }

    #[test]
    fn skip_moves_one_frame_and_past_the_last_ends_the_shot() {
        let mut de1 = SimDe1::new(De1Params::default());
        de1.enter(MachineState::Espresso);
        run(&mut de1, 2_000);
        let skip = MachineState::SkipToNext as u8;
        de1.on_request(skip);
        assert_eq!(de1.frame, 1);
        de1.on_request(skip);
        de1.on_request(skip);
        assert_eq!(de1.sub, SubState::Ending);
    }

    #[test]
    fn water_reaches_the_cup_after_the_headspace() {
        let mut cup = SimCup {
            grams: 0.0,
            headspace_ml: 50.0,
        };
        cup.pour(40.0);
        assert!(cup.grams.abs() < f32::EPSILON);
        cup.pour(20.0);
        assert!((cup.grams - 10.0).abs() < f32::EPSILON);
    }

    #[test]
    fn a_tare_takes_effect_after_its_latency() {
        let mut scale = SimScale::new(200);
        scale.tare(1_000);
        assert_eq!(scale.reading(1_100, 20.0), Some(20.0));
        assert_eq!(scale.reading(1_200, 20.0), Some(0.0));
        assert_eq!(scale.reading(1_300, 25.0), Some(5.0));
    }

    #[test]
    fn group_flow_climbs_in_ending_though_the_pump_is_off() {
        let mut de1 = SimDe1::new(De1Params::default());
        de1.enter(MachineState::HotWater);
        run(&mut de1, 1_000);
        assert!((de1.group_flow() - 2.0).abs() < 0.01);
        de1.on_request(MachineState::Idle as u8);
        run(&mut de1, 900);
        assert_eq!(de1.sub, SubState::Ending);
        assert!(de1.group_flow() > 2.9, "{}", de1.group_flow());
        assert!(de1.flow_ml_s() < 1.0);
    }

    #[test]
    fn samples_outlast_the_flow_state_until_the_tail_dies() {
        let mut de1 = SimDe1::new(De1Params::default());
        de1.enter(MachineState::HotWater);
        run(&mut de1, 1_000);
        de1.on_request(MachineState::Idle as u8);
        run(&mut de1, 1_200);
        assert_eq!(de1.state, MachineState::Idle);
        assert!(de1.sends_samples());
        assert!(de1.group_flow() > 0.3);
        assert!(
            run(&mut de1, 1_000).abs() < f32::EPSILON,
            "no water in the tail"
        );
        run(&mut de1, 6_000);
        assert!(!de1.sends_samples());
    }

    #[test]
    fn a_bump_lifts_after_its_duration() {
        let mut scale = SimScale::new(0);
        scale.set_mode(ScaleMode::Bump {
            grams: 6.0,
            ms: 500,
        });
        assert_eq!(scale.reading(0, 10.0), Some(16.0));
        assert_eq!(scale.reading(400, 10.0), Some(16.0));
        assert_eq!(scale.reading(500, 10.0), Some(10.0));
    }

    #[test]
    fn a_second_bump_gets_its_own_duration() {
        let mut scale = SimScale::new(0);
        let bump = ScaleMode::Bump {
            grams: 6.0,
            ms: 500,
        };
        scale.set_mode(bump);
        assert_eq!(scale.reading(0, 10.0), Some(16.0));
        scale.set_mode(ScaleMode::Healthy);
        scale.set_mode(bump);
        assert_eq!(scale.reading(1_000, 10.0), Some(16.0));
    }

    #[test]
    fn a_tare_zeroes_a_hand_on_the_platter() {
        let mut scale = SimScale::new(0);
        scale.set_mode(ScaleMode::Bump {
            grams: 6.0,
            ms: 500,
        });
        scale.tare(100);
        assert_eq!(scale.reading(100, 10.0), Some(0.0));
        assert_eq!(scale.reading(600, 10.0), Some(-6.0), "the hand lifted");
    }

    #[test]
    fn a_tare_zeroes_a_step_up() {
        let mut scale = SimScale::new(0);
        scale.set_mode(ScaleMode::StepUp { grams: 250.0 });
        assert_eq!(scale.reading(0, 10.0), Some(260.0));
        scale.tare(100);
        assert_eq!(scale.reading(100, 10.0), Some(0.0));
        assert_eq!(scale.reading(200, 15.0), Some(5.0));
    }

    #[test]
    fn a_transient_zero_keeps_the_real_zero() {
        let mut scale = SimScale::new(0);
        scale.set_mode(ScaleMode::TransientZero);
        assert_eq!(scale.reading(0, 10.0), Some(0.0));
        scale.set_mode(ScaleMode::Healthy);
        assert_eq!(scale.reading(100, 10.0), Some(10.0));
    }

    #[test]
    fn a_rezero_stays_zeroed() {
        let mut scale = SimScale::new(0);
        scale.set_mode(ScaleMode::Rezero);
        assert_eq!(scale.reading(0, 10.0), Some(0.0));
        assert_eq!(scale.reading(100, 12.0), Some(2.0));
    }

    #[test]
    fn a_delayed_write_lands_late() {
        let mut lane = SimLane::new(5_000);
        lane.delay_next_ms = 1_000;
        lane.push(request(14));
        assert!(lane.step(0).is_empty());
        assert!(lane.step(1_000).is_empty());
        assert_eq!(lane.step(1_040), vec![request(14)]);
    }

    #[test]
    fn a_stale_scale_flaps_on_its_period() {
        let mut scale = SimScale::new(0);
        scale.set_mode(ScaleMode::Flap { period_ms: 2_000 });
        assert_eq!(scale.reading(500, 5.0), None);
        assert_eq!(scale.reading(1_500, 5.0), Some(5.0));
    }

    fn request(byte: u8) -> Write {
        Write {
            requested: Some(byte),
            settings: None,
            profile: None,
            stop: false,
        }
    }

    fn stop() -> Write {
        Write {
            stop: true,
            ..request(MachineState::Idle as u8)
        }
    }

    #[test]
    fn a_stop_jumps_the_queue_and_a_second_is_dropped() {
        let mut lane = SimLane::new(5_000);
        lane.push(request(14));
        lane.push(request(4));
        lane.push(stop());
        lane.push(stop());
        let landed: Vec<Write> = (0..10).flat_map(|i| lane.step(i * 100)).collect();
        assert_eq!(landed, vec![stop(), request(14), request(4)]);
    }

    #[test]
    fn a_plain_idle_keeps_its_place() {
        let mut lane = SimLane::new(5_000);
        lane.push(request(2));
        lane.push(request(4));
        lane.push(request(2));
        let landed: Vec<Write> = (0..10).flat_map(|i| lane.step(i * 100)).collect();
        assert_eq!(landed, vec![request(2), request(4), request(2)]);
    }

    #[test]
    fn cancel_stop_drops_a_queued_stop_but_not_one_on_the_air() {
        let mut lane = SimLane::new(5_000);
        lane.push(stop());
        assert!(lane.step(0).is_empty(), "on the air");
        lane.cancel_stop();
        assert_eq!(lane.step(100), vec![stop()]);
        lane.push(request(14));
        lane.push(stop());
        lane.cancel_stop();
        let landed: Vec<Write> = (2..10).flat_map(|i| lane.step(i * 100)).collect();
        assert_eq!(landed, vec![request(14)]);
    }

    #[test]
    fn a_hung_write_holds_the_lane_until_the_timeout_and_is_lost() {
        let mut lane = SimLane::new(5_000);
        lane.hang_next = true;
        lane.push(stop());
        assert!(lane.step(0).is_empty());
        lane.push(stop());
        lane.push(request(14));
        assert!(lane.step(4_900).is_empty());
        assert!(
            lane.step(5_000).is_empty(),
            "the next write goes on the air"
        );
        assert_eq!(
            lane.step(5_100),
            vec![request(14)],
            "the second stop was dropped"
        );
    }
}
