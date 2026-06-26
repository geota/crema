//! The espresso shot state machine — observing a shot and recording it.
//!
//! [`ShotMonitor`] is sans-IO: feed it the DE1's [`StateInfo`] and [`ShotSample`]
//! notifications (each with a monotonic timestamp from the shell) and it tracks
//! the shot's [`ShotPhase`], accumulates a [`ShotRecord`], and returns the
//! notable [`ShotEvent`]s. SAW / SAV (stop-at-weight / stop-at-volume) logic
//! will build on this.

use std::time::Duration;

use de1_protocol::{MachineState, ShotSample, StateInfo, SubState};
use typeshare::typeshare;

use crate::session::SessionTimer;

/// Custom serde adapter for `Duration` ↔ millisecond integer on the
/// wire. Keeps Rust's internal `Duration` semantics intact while
/// emitting a plain `u32` on the JSON side so the web shell + Crema
/// JSONL bundle consume a chart-ready number without unpacking
/// `{ secs, nanos }`.
mod duration_ms {
    use serde::{Deserialize, Deserializer, Serializer};
    use std::time::Duration;

    pub fn serialize<S: Serializer>(d: &Duration, s: S) -> Result<S::Ok, S::Error> {
        // `Duration::as_millis` returns `u128`; saturating to `u64` covers
        // the impossible-in-practice overflow (`u64` ms ≈ 584M years).
        s.serialize_u64(u64::try_from(d.as_millis()).unwrap_or(u64::MAX))
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Duration, D::Error> {
        let ms = u64::deserialize(d)?;
        Ok(Duration::from_millis(ms))
    }
}

/// Hard cap on the number of telemetry samples retained for one shot.
///
/// A shot ends when the DE1 leaves the `Espresso` state — an untrusted signal
/// from the peer — so without a cap a buggy or hostile DE1 could stream
/// samples indefinitely and grow the heap without bound. The cap is sized far
/// beyond any real session: ~1 hour of telemetry at 10 Hz. A real espresso
/// shot is at most a few minutes, so a genuine shot is never truncated; once
/// the cap is reached further samples are simply dropped (no panic, no
/// reallocation).
pub const MAX_SHOT_SAMPLES: usize = 36_000;

/// Where an espresso shot is in its lifecycle.
///
/// `#[non_exhaustive]` so additional phases (e.g. a future post-shot
/// "Draining" classification) can be added without breaking the FFI surface —
/// this enum is the payload of `Event::ShotPhaseChanged`.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, serde::Serialize, serde::Deserialize)]
#[non_exhaustive]
pub enum ShotPhase {
    /// No espresso shot in progress.
    #[default]
    Idle,
    /// Heating and stabilising, before flow begins.
    Heating,
    /// Preinfusion — wetting the puck at low pressure or flow.
    Preinfusion,
    /// The main pour.
    Pouring,
    /// Ending — flow has stopped and the group is draining.
    Ending,
}

impl ShotPhase {
    /// Classify the shot phase from a DE1 state / substate pair. Any machine
    /// state other than `Espresso` is [`ShotPhase::Idle`] for shot tracking.
    pub fn classify(state: MachineState, substate: SubState) -> ShotPhase {
        if state != MachineState::Espresso {
            return ShotPhase::Idle;
        }
        match substate {
            SubState::Heating | SubState::FinalHeating | SubState::Stabilising => {
                ShotPhase::Heating
            }
            SubState::Preinfusion => ShotPhase::Preinfusion,
            SubState::Pouring => ShotPhase::Pouring,
            SubState::Ending => ShotPhase::Ending,
            _ => ShotPhase::Idle,
        }
    }
}

/// A telemetry sample tagged with its time since the shot began.
///
/// The DE1's [`ShotSample`] is the raw protocol decode (pressure, flow,
/// temperatures, setpoints). The overlay fields below carry data that
/// rides *with* a DE1 sample but isn't on the wire:
///
/// - the scale's smoothed weight + flow at this instant (`scale_weight`,
///   `scale_flow_weight`) — only present when a scale was paired;
/// - the running pump-side dispensed volume (`dispensed_volume`) —
///   the DE1's integrated `group_flow × Δt`, computed core-side;
/// - the two puck-resistance signals (`resistance`,
///   `resistance_weight`) — pre-computed by [`crate::lib::puck_resistance`]
///   / `puck_resistance_weight` so every shell consumes the identical
///   value without re-deriving the formula or threshold.
///
/// All overlays are `Option<f32>`: legacy `.shot.json` imports
/// pre-dating this PR carry none of them, and a shot pulled without a
/// scale paired carries no `scale_*` values. `#[serde(default)]` so an
/// older record deserialises cleanly with the new fields absent.
#[typeshare]
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TimedSample {
    /// Time since the shot started. Serialises as a millisecond
    /// integer (`u32`) on the wire so the JSON shape is JS-friendly
    /// — Rust keeps the internal `Duration` for arithmetic.
    #[typeshare(serialized_as = "I64")]
    #[serde(with = "duration_ms")]
    pub elapsed: Duration,
    /// The telemetry sample.
    pub sample: ShotSample,
    /// Scale-derived cumulative weight at this instant, grams. `None`
    /// when no scale was paired. Maps to the legacy de1app `espresso_weight`
    /// channel + the Visualizer `totals.weight` upload field.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub scale_weight: Option<f32>,
    /// Scale-derived mass-flow rate at this instant, grams per second
    /// (`dW/dt`). `None` when no scale was paired. The truer espresso
    /// flow during the pour — measures what exits the puck. Maps to
    /// `espresso_flow_weight` + Visualizer `flow.by_weight`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub scale_flow_weight: Option<f32>,
    /// Running pump-side dispensed volume at this instant, millilitres.
    /// `None` for legacy imports that didn't record it; live captures
    /// always populate it from the DE1's integrator. Maps to
    /// `espresso_water_dispensed` + Visualizer `totals.water_dispensed`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dispensed_volume: Option<f32>,
    /// DE1-flow-derived puck resistance, `bar / (ml/s)²`. `None` near
    /// zero flow (the `puck_resistance` floor). NOT uploaded to
    /// Visualizer — the spec accepts no `espresso_resistance` field;
    /// Visualizer derives the same series server-side from pressure +
    /// flow. Carried on the sample only so the chart can render it
    /// without re-deriving per render pass.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resistance: Option<f32>,
    /// Scale-flow-derived puck resistance, `bar / (g/s)²`. `None` when
    /// no scale was paired or scale flow is sub-floor. The chart prefers
    /// this over `resistance` per-sample when present. Same
    /// non-upload story as `resistance`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resistance_weight: Option<f32>,
}

/// A completed espresso shot — its duration and full telemetry series.
///
/// The samples span the whole `Espresso` machine state, including the heating
/// phase before flow; consumers can use the [`ShotEvent::PhaseChanged`] stream
/// to locate flow-start.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShotRecord {
    /// Total shot duration. Serialises as a millisecond integer
    /// (`u32`) on the wire — the chart consumes a plain number.
    #[serde(with = "duration_ms")]
    pub duration: Duration,
    /// Every telemetry sample collected during the shot, in order.
    pub samples: Vec<TimedSample>,
}

/// Summary metrics derived from a [`ShotRecord`]'s recorded telemetry.
///
/// These are computed from the sample series alone — no scale or barista
/// input — so they describe what the machine measured during the shot.
#[derive(Debug, Clone, Copy, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct ShotMetrics {
    /// Highest group pressure observed, bar.
    pub peak_pressure: f32,
    /// Highest group flow observed, ml/s.
    pub peak_flow: f32,
    /// Total water dispensed, ml — the group flow integrated over time.
    pub total_water: f32,
    /// Shot duration (from [`ShotRecord::duration`]).
    pub duration: Duration,
}

/// Pre-computed summary peaks over a [`ShotRecord`]'s telemetry —
/// what the History list and detail readouts surface above the chart.
/// Returned by [`ShotRecord::peaks`] so each shell consumes the
/// identical numbers without re-deriving the formula or threshold.
///
/// Distinct from [`ShotMetrics`]: this struct adds the scale-derived
/// `peak_weight` / `final_weight` (which `ShotMetrics` skips because
/// they depend on a paired scale) and surfaces `peak_temp`, which
/// `ShotMetrics` doesn't track.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Default, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShotPeaks {
    /// Peak scale weight reached during the shot, grams. `None` when
    /// no scale was paired (no sample carried a `scale_weight`).
    pub peak_weight: Option<f32>,
    /// Final scale weight at shot end, grams. `None` for the same
    /// reason. NOT the same as `peak_weight`: a long drip-off makes
    /// `final_weight` exceed `peak_weight` after the user pulls the
    /// cup away.
    pub final_weight: Option<f32>,
    /// Peak group pressure reached, bar.
    pub peak_pressure: f32,
    /// Peak group-head temperature reached, °C.
    pub peak_temp: f32,
}

impl ShotRecord {
    /// Compute summary [`ShotMetrics`] from the recorded telemetry.
    ///
    /// Peak pressure and flow are the maxima over all samples; total water is
    /// the trapezoidal integral of group flow (ml/s) over the elapsed time of
    /// the sample series. A record with no samples yields all-zero metrics
    /// except [`duration`](ShotMetrics::duration).
    pub fn metrics(&self) -> ShotMetrics {
        let mut peak_pressure = 0.0f32;
        let mut peak_flow = 0.0f32;
        let mut total_water = 0.0f32;
        let mut prev: Option<&TimedSample> = None;

        for timed in &self.samples {
            peak_pressure = peak_pressure.max(timed.sample.group_pressure);
            peak_flow = peak_flow.max(timed.sample.group_flow);
            if let Some(prev) = prev
                && timed.elapsed > prev.elapsed
            {
                // Trapezoidal rule: average flow over the interval × duration.
                let dt_s = (timed.elapsed - prev.elapsed).as_secs_f32();
                let avg_flow = (prev.sample.group_flow + timed.sample.group_flow) / 2.0;
                total_water += avg_flow * dt_s;
            }
            prev = Some(timed);
        }

        ShotMetrics {
            peak_pressure,
            peak_flow,
            total_water,
            duration: self.duration,
        }
    }

    /// Derive [`ShotPeaks`] from the sample series.
    ///
    /// `peak_weight` / `final_weight` are `None` when no sample carries
    /// a `scale_weight` (no scale paired); otherwise `peak_weight` is
    /// the max scale weight observed and `final_weight` is the last
    /// sample's reading. Pressure / temp peaks are always present —
    /// they come from the DE1 itself, not an external sensor.
    #[must_use]
    pub fn peaks(&self) -> ShotPeaks {
        let mut peak_weight: Option<f32> = None;
        let mut final_weight: Option<f32> = None;
        let mut peak_pressure = 0.0f32;
        let mut peak_temp = 0.0f32;
        for timed in &self.samples {
            peak_pressure = peak_pressure.max(timed.sample.group_pressure);
            peak_temp = peak_temp.max(timed.sample.head_temp);
            if let Some(w) = timed.scale_weight {
                peak_weight = Some(peak_weight.map_or(w, |p| p.max(w)));
                final_weight = Some(w);
            }
        }
        ShotPeaks {
            peak_weight,
            final_weight,
            peak_pressure,
            peak_temp,
        }
    }
}

/// A notable change observed by a [`ShotMonitor`].
#[derive(Debug, Clone, PartialEq)]
pub enum ShotEvent {
    /// An espresso shot has begun (the machine entered the `Espresso` state).
    Started,
    /// The shot has moved to a new phase.
    PhaseChanged(ShotPhase),
    /// The DE1 has advanced to a new profile frame.
    FrameChanged(u8),
    /// The shot has finished; carries the completed record.
    Completed(ShotRecord),
}

/// In-progress shot accumulation. The session timing lives in a shared
/// [`SessionTimer`]; this holds only what is shot-specific.
#[derive(Debug, Default)]
struct ShotInProgress {
    timer: SessionTimer,
    last_frame: Option<u8>,
    samples: Vec<TimedSample>,
}

/// Observes the DE1's notification stream and tracks one espresso shot at a time.
///
/// Sans-IO: it holds no clock and performs no I/O. The shell supplies a
/// monotonic `now_ms` with each notification.
#[derive(Debug, Default)]
pub struct ShotMonitor {
    phase: ShotPhase,
    shot: Option<ShotInProgress>,
}

impl ShotMonitor {
    /// Create a monitor in the idle state.
    pub fn new() -> ShotMonitor {
        ShotMonitor::default()
    }

    /// The current shot phase.
    pub fn phase(&self) -> ShotPhase {
        self.phase
    }

    /// Whether an espresso shot is currently being recorded.
    pub fn is_shot_in_progress(&self) -> bool {
        self.shot.is_some()
    }

    /// Feed a [`StateInfo`] notification. `now` is a monotonic timestamp
    /// supplied by the shell (a [`Duration`] from a shell-chosen epoch).
    ///
    /// Returns the events this update produced — empty if nothing notable
    /// changed.
    pub fn on_state_info(&mut self, info: StateInfo, now: Duration) -> Vec<ShotEvent> {
        let mut events = Vec::new();
        let in_espresso = info.state == MachineState::Espresso;

        // A shot starts when the machine enters the Espresso state.
        if in_espresso && self.shot.is_none() {
            let mut shot = ShotInProgress::default();
            shot.timer.start(now);
            self.shot = Some(shot);
            events.push(ShotEvent::Started);
        }

        let new_phase = ShotPhase::classify(info.state, info.substate);
        if new_phase != self.phase {
            self.phase = new_phase;
            events.push(ShotEvent::PhaseChanged(new_phase));
        }

        // A shot ends when the machine leaves the Espresso state.
        if !in_espresso && let Some(mut shot) = self.shot.take() {
            let duration = shot.timer.finish(now).unwrap_or(Duration::ZERO);
            events.push(ShotEvent::Completed(ShotRecord {
                duration,
                samples: shot.samples,
            }));
        }
        events
    }

    /// Feed a [`ShotSample`] telemetry notification. Samples are recorded only
    /// while a shot is in progress.
    ///
    /// Returns the events this sample produced — empty if nothing notable.
    pub fn on_sample(&mut self, sample: &ShotSample, now: Duration) -> Vec<ShotEvent> {
        let mut events = Vec::new();
        if let Some(shot) = &mut self.shot {
            if shot.last_frame != Some(sample.frame_number) {
                shot.last_frame = Some(sample.frame_number);
                events.push(ShotEvent::FrameChanged(sample.frame_number));
            }
            // Drop samples past the cap so an over-long stream cannot grow the
            // heap without bound; see [`MAX_SHOT_SAMPLES`].
            if shot.samples.len() < MAX_SHOT_SAMPLES {
                shot.samples.push(TimedSample {
                    elapsed: shot.timer.elapsed(now),
                    sample: sample.clone(),
                    // Overlay channels are populated at the export
                    // boundary (the shell's `toRustStoredShot` adapter)
                    // — the live `ShotMonitor` only carries the DE1
                    // protocol sample. Leave them `None` here.
                    scale_weight: None,
                    scale_flow_weight: None,
                    dispensed_volume: None,
                    resistance: None,
                    resistance_weight: None,
                });
            }
        }
        events
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Helper: a `Duration` of `ms` milliseconds — the unit every test below
    /// thinks in.
    fn ms(ms: u64) -> Duration {
        Duration::from_millis(ms)
    }

    fn state(state: MachineState, substate: SubState) -> StateInfo {
        StateInfo { state, substate }
    }

    /// A telemetry sample carrying the given frame number; other fields zeroed.
    fn sample(frame_number: u8) -> ShotSample {
        ShotSample {
            sample_time: 0,
            group_pressure: 0.0,
            group_flow: 0.0,
            head_temp: 0.0,
            mix_temp: 0.0,
            set_mix_temp: 0.0,
            set_head_temp: 0.0,
            set_group_pressure: 0.0,
            set_group_flow: 0.0,
            frame_number,
            steam_temp: 0.0,
        }
    }

    /// Extract the `ShotRecord` from a `Completed` event in `events`.
    fn completed_record(events: Vec<ShotEvent>) -> ShotRecord {
        events
            .into_iter()
            .find_map(|event| match event {
                ShotEvent::Completed(record) => Some(record),
                _ => None,
            })
            .expect("a Completed event")
    }

    #[test]
    fn classifies_the_phase_from_state_and_substate() {
        assert_eq!(
            ShotPhase::classify(MachineState::Espresso, SubState::Pouring),
            ShotPhase::Pouring
        );
        assert_eq!(
            ShotPhase::classify(MachineState::Espresso, SubState::FinalHeating),
            ShotPhase::Heating
        );
    }

    #[test]
    fn a_non_espresso_state_classifies_as_idle() {
        assert_eq!(
            ShotPhase::classify(MachineState::Steam, SubState::Steaming),
            ShotPhase::Idle
        );
    }

    #[test]
    fn entering_espresso_starts_a_shot() {
        let mut monitor = ShotMonitor::new();
        let events =
            monitor.on_state_info(state(MachineState::Espresso, SubState::Heating), ms(1000));
        assert!(events.contains(&ShotEvent::Started));
        assert!(monitor.is_shot_in_progress());
        assert_eq!(monitor.phase(), ShotPhase::Heating);
    }

    #[test]
    fn phase_transitions_are_reported() {
        let mut monitor = ShotMonitor::new();
        monitor.on_state_info(
            state(MachineState::Espresso, SubState::Preinfusion),
            ms(1000),
        );
        let events =
            monitor.on_state_info(state(MachineState::Espresso, SubState::Pouring), ms(2000));
        assert_eq!(events, vec![ShotEvent::PhaseChanged(ShotPhase::Pouring)]);
    }

    #[test]
    fn samples_are_not_recorded_before_a_shot() {
        let mut monitor = ShotMonitor::new();
        let events = monitor.on_sample(&sample(0), ms(500));
        assert!(events.is_empty());
        assert!(!monitor.is_shot_in_progress());
    }

    #[test]
    fn a_new_frame_number_reports_a_frame_change() {
        let mut monitor = ShotMonitor::new();
        monitor.on_state_info(state(MachineState::Espresso, SubState::Pouring), ms(1000));
        monitor.on_sample(&sample(0), ms(1100));
        let events = monitor.on_sample(&sample(1), ms(1200));
        assert_eq!(events, vec![ShotEvent::FrameChanged(1)]);
    }

    #[test]
    fn a_full_shot_yields_a_record_with_samples_and_duration() {
        let mut monitor = ShotMonitor::new();
        monitor.on_state_info(
            state(MachineState::Espresso, SubState::Preinfusion),
            ms(1000),
        );
        monitor.on_sample(&sample(0), ms(1200));
        monitor.on_sample(&sample(0), ms(3000));
        let done = monitor.on_state_info(state(MachineState::Idle, SubState::Ready), ms(5000));

        let record = completed_record(done);
        assert_eq!(record.duration, ms(4000)); // 5000 - 1000
        assert_eq!(record.samples.len(), 2);
        assert_eq!(record.samples[0].elapsed, ms(200)); // 1200 - 1000
        assert_eq!(record.samples[1].elapsed, ms(2000)); // 3000 - 1000
        assert!(!monitor.is_shot_in_progress());
    }

    /// A `TimedSample` at `elapsed_ms` ms carrying the given pressure and flow.
    fn timed(elapsed_ms: u64, group_pressure: f32, group_flow: f32) -> TimedSample {
        let mut sample = sample(0);
        sample.group_pressure = group_pressure;
        sample.group_flow = group_flow;
        TimedSample {
            elapsed: ms(elapsed_ms),
            sample,
            scale_weight: None,
            scale_flow_weight: None,
            dispensed_volume: None,
            resistance: None,
            resistance_weight: None,
        }
    }

    #[test]
    fn metrics_report_peaks_water_and_duration() {
        let record = ShotRecord {
            duration: ms(30_000),
            samples: vec![
                timed(0, 1.0, 0.0),
                timed(1_000, 9.0, 2.0),
                timed(2_000, 6.0, 2.0),
            ],
        };
        let metrics = record.metrics();
        assert_eq!(metrics.peak_pressure, 9.0);
        assert_eq!(metrics.peak_flow, 2.0);
        // Trapezoidal: 0..1 s avg 1.0 -> 1.0 ml; 1..2 s avg 2.0 -> 2.0 ml.
        assert_eq!(metrics.total_water, 3.0);
        assert_eq!(metrics.duration, ms(30_000));
    }

    #[test]
    fn the_sample_buffer_is_capped() {
        let mut monitor = ShotMonitor::new();
        monitor.on_state_info(state(MachineState::Espresso, SubState::Pouring), ms(0));
        // Feed well past the cap: the excess samples are dropped, not retained.
        for _ in 0..MAX_SHOT_SAMPLES + 100 {
            monitor.on_sample(&sample(0), ms(1_000));
        }
        let done = monitor.on_state_info(state(MachineState::Idle, SubState::Ready), ms(2_000));
        let record = completed_record(done);
        assert_eq!(record.samples.len(), MAX_SHOT_SAMPLES);
    }

    #[test]
    fn metrics_of_an_empty_record_are_zero_except_duration() {
        let record = ShotRecord {
            duration: ms(5_000),
            samples: vec![],
        };
        let metrics = record.metrics();
        assert_eq!(metrics.peak_pressure, 0.0);
        assert_eq!(metrics.peak_flow, 0.0);
        assert_eq!(metrics.total_water, 0.0);
        assert_eq!(metrics.duration, ms(5_000));
    }
}
