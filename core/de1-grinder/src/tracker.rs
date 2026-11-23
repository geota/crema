//! Grind detection: turns a stream of readings into completed grinds and
//! purges. Works for any grinder that reports its state.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::reading::{GrinderReading, GrinderState, ObservedSetting, PresetSlot};

/// A run shorter than this, with no grind counted, is a state blip and dropped.
pub const MIN_RUN_MS: u64 = 500;
/// The most speed samples kept per run (5 minutes at 5 Hz); later ones are
/// not added to the median.
const MAX_SAMPLES: usize = 1_500;

/// What a run was.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum RunKind {
    /// A dose was ground.
    Grind,
    /// The grinder cleared its chamber. Never a shot's grind.
    Purge,
}

/// How a run ended.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum GrindEnd {
    /// The grinder reported another state.
    StateChange,
    /// The link dropped mid-run.
    LinkLost,
}

/// One completed grind or purge.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GrindSession {
    /// Grind or purge.
    pub kind: RunKind,
    /// The first reading in the run (ms, the core's clock).
    #[typeshare(serialized_as = "U64")]
    pub started_at: u64,
    /// The first reading after it, or the last one in it when the link
    /// dropped.
    #[typeshare(serialized_as = "U64")]
    pub ended_at: u64,
    /// `ended_at - started_at`, ±200 ms at 5 Hz.
    pub duration_ms: u32,
    /// The dial at the start.
    pub setting: Option<ObservedSetting>,
    /// The dial moved during the run.
    pub setting_changed: bool,
    /// Median grinding speed over the run, rpm.
    pub grind_rpm: Option<u32>,
    /// Slowest grinding speed seen, rpm.
    pub grind_rpm_min: Option<u32>,
    /// Fastest grinding speed seen, rpm.
    pub grind_rpm_max: Option<u32>,
    /// Median feeder speed over the run, rpm.
    pub feed_rpm: Option<u32>,
    /// The preset at the start.
    pub preset: Option<PresetSlot>,
    /// Humidity at the start, %.
    pub humidity_pct: Option<u8>,
    /// The lifetime count went up during the run. `None` when the grinder
    /// doesn't report one.
    pub counted: Option<bool>,
    /// How it ended.
    pub ended_by: GrindEnd,
}

#[derive(Debug, Clone)]
struct Run {
    kind: RunKind,
    started_at: u64,
    last_at: u64,
    setting: Option<ObservedSetting>,
    setting_changed: bool,
    rpms: Vec<u32>,
    feeds: Vec<u32>,
    preset: Option<PresetSlot>,
    humidity_pct: Option<u8>,
    total_before: Option<u64>,
    total_latest: Option<u64>,
}

fn run_kind(state: GrinderState) -> Option<RunKind> {
    match state {
        GrinderState::Grinding => Some(RunKind::Grind),
        GrinderState::Purging => Some(RunKind::Purge),
        _ => None,
    }
}

fn median(values: &[u32]) -> Option<u32> {
    let mut sorted = values.to_vec();
    sorted.sort_unstable();
    sorted.get(sorted.len() / 2).copied()
}

impl Run {
    fn start(kind: RunKind, reading: &GrinderReading, total_before: Option<u64>) -> Self {
        let mut run = Run {
            kind,
            started_at: reading.at_ms,
            last_at: reading.at_ms,
            setting: reading.setting.clone(),
            setting_changed: false,
            rpms: Vec::new(),
            feeds: Vec::new(),
            preset: reading.preset,
            humidity_pct: reading.humidity_pct,
            total_before,
            total_latest: reading.total_grinds,
        };
        run.absorb(reading);
        run
    }

    fn absorb(&mut self, reading: &GrinderReading) {
        self.last_at = self.last_at.max(reading.at_ms);
        if self.setting.is_none() {
            self.setting.clone_from(&reading.setting);
        } else if let (Some(start), Some(now)) = (&self.setting, &reading.setting)
            && start.value != now.value
        {
            self.setting_changed = true;
        }
        if self.rpms.len() < MAX_SAMPLES
            && let Some(rpm) = reading.grind_rpm
        {
            self.rpms.push(rpm);
        }
        if self.feeds.len() < MAX_SAMPLES
            && let Some(rpm) = reading.feed_rpm
        {
            self.feeds.push(rpm);
        }
        self.total_latest = reading.total_grinds.or(self.total_latest);
    }

    fn finish(self, ended_at: u64, ended_by: GrindEnd) -> Option<GrindSession> {
        let duration = ended_at.saturating_sub(self.started_at);
        let counted = match (self.total_before, self.total_latest) {
            (Some(before), Some(after)) => Some(after > before),
            _ => None,
        };
        if duration < MIN_RUN_MS && counted != Some(true) {
            return None;
        }
        Some(GrindSession {
            kind: self.kind,
            started_at: self.started_at,
            ended_at,
            duration_ms: u32::try_from(duration).unwrap_or(u32::MAX),
            setting: self.setting,
            setting_changed: self.setting_changed,
            grind_rpm: median(&self.rpms),
            grind_rpm_min: self.rpms.iter().min().copied(),
            grind_rpm_max: self.rpms.iter().max().copied(),
            feed_rpm: median(&self.feeds),
            preset: self.preset,
            humidity_pct: self.humidity_pct,
            counted,
            ended_by,
        })
    }
}

/// Detects grinds and purges in one connection's readings.
///
/// A run starts on the first reading in `Grinding` (or `Purging`) and ends on
/// the first reading in any other state, or when the link drops. A run under
/// [`MIN_RUN_MS`] that counted no grind is dropped.
#[derive(Debug, Clone, Default)]
pub struct GrindTracker {
    run: Option<Run>,
    last_total: Option<u64>,
}

impl GrindTracker {
    /// A tracker with nothing seen.
    #[must_use]
    pub fn new() -> Self {
        GrindTracker::default()
    }

    /// Whether a grind (not a purge) is running.
    #[must_use]
    pub fn grinding(&self) -> bool {
        self.run.as_ref().is_some_and(|r| r.kind == RunKind::Grind)
    }

    /// Feed one reading; returns the run it completed, if any.
    pub fn on_reading(&mut self, reading: &GrinderReading) -> Option<GrindSession> {
        let kind = run_kind(reading.state);
        let mut completed = None;
        let mut ended_here = false;
        if let Some(run) = &mut self.run {
            if Some(run.kind) == kind {
                run.absorb(reading);
                self.last_total = reading.total_grinds.or(self.last_total);
                return None;
            }
            if let Some(mut run) = self.run.take() {
                // The count can tick on the reading that ends the run.
                run.total_latest = reading.total_grinds.or(run.total_latest);
                completed = run.finish(reading.at_ms, GrindEnd::StateChange);
                ended_here = true;
            }
        }
        if let Some(kind) = kind {
            // A count on this reading belongs to the run it ended, if any;
            // otherwise to the run it starts.
            let before = if ended_here {
                reading.total_grinds.or(self.last_total)
            } else {
                self.last_total.or(reading.total_grinds)
            };
            self.run = Some(Run::start(kind, reading, before));
        }
        self.last_total = reading.total_grinds.or(self.last_total);
        completed
    }

    /// The link dropped: end a running run at its last reading.
    pub fn link_lost(&mut self) -> Option<GrindSession> {
        self.last_total = None;
        let run = self.run.take()?;
        let ended_at = run.last_at;
        run.finish(ended_at, GrindEnd::LinkLost)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::reading::GrinderSettings;

    fn reading(at_ms: u64, state: GrinderState, gap: u32, rpm: u32, total: u64) -> GrinderReading {
        GrinderReading {
            at_ms,
            state,
            raw_state: format!("{state:?}"),
            setting: Some(ObservedSetting {
                value: gap.to_string(),
                number: u16::try_from(gap).ok().map(f32::from),
            }),
            grind_rpm: Some(rpm),
            feed_rpm: Some(40),
            humidity_pct: Some(30),
            total_grinds: Some(total),
            preset: Some(PresetSlot::Manual),
            settings: GrinderSettings::default(),
        }
    }

    use GrinderState::{Grinding, Idle, Purging, Unknown};

    #[test]
    fn a_grind_runs_from_the_first_grinding_reading_to_the_first_other_one() {
        let mut t = GrindTracker::new();
        assert_eq!(t.on_reading(&reading(0, Idle, 480, 700, 10)), None);
        assert_eq!(t.on_reading(&reading(200, Grinding, 480, 700, 10)), None);
        assert!(t.grinding());
        assert_eq!(t.on_reading(&reading(400, Grinding, 480, 900, 10)), None);
        assert_eq!(t.on_reading(&reading(600, Grinding, 480, 800, 10)), None);
        assert_eq!(t.on_reading(&reading(10_200, Grinding, 480, 800, 10)), None);
        let s = t
            .on_reading(&reading(10_400, Idle, 480, 700, 11))
            .expect("a grind");
        assert_eq!(s.kind, RunKind::Grind);
        assert_eq!(
            (s.started_at, s.ended_at, s.duration_ms),
            (200, 10_400, 10_200)
        );
        assert_eq!(s.setting.map(|v| v.value).as_deref(), Some("480"));
        assert!(!s.setting_changed);
        assert_eq!(s.grind_rpm, Some(800));
        assert_eq!((s.grind_rpm_min, s.grind_rpm_max), (Some(700), Some(900)));
        assert_eq!(s.feed_rpm, Some(40));
        assert_eq!(s.preset, Some(PresetSlot::Manual));
        assert_eq!(s.humidity_pct, Some(30));
        assert_eq!(s.counted, Some(true));
        assert_eq!(s.ended_by, GrindEnd::StateChange);
        assert!(!t.grinding());
    }

    #[test]
    fn a_count_on_the_first_grinding_reading_still_counts() {
        let mut t = GrindTracker::new();
        t.on_reading(&reading(0, Idle, 480, 700, 10));
        t.on_reading(&reading(200, Grinding, 480, 700, 11));
        let s = t
            .on_reading(&reading(5_000, Idle, 480, 700, 11))
            .expect("a grind");
        assert_eq!(s.counted, Some(true));
    }

    #[test]
    fn a_moved_dial_is_noted_and_the_start_value_kept() {
        let mut t = GrindTracker::new();
        t.on_reading(&reading(0, Grinding, 480, 700, 10));
        t.on_reading(&reading(200, Grinding, 485, 700, 10));
        let s = t
            .on_reading(&reading(3_000, Idle, 485, 700, 10))
            .expect("a grind");
        assert_eq!(s.setting.map(|v| v.value).as_deref(), Some("480"));
        assert!(s.setting_changed);
        assert_eq!(s.counted, Some(false));
    }

    #[test]
    fn a_purge_is_its_own_run_and_ends_a_grind() {
        let mut t = GrindTracker::new();
        t.on_reading(&reading(0, Grinding, 480, 700, 10));
        let grind = t
            .on_reading(&reading(8_000, Purging, 480, 700, 11))
            .expect("grind");
        assert_eq!((grind.kind, grind.ended_at), (RunKind::Grind, 8_000));
        assert!(!t.grinding());
        let purge = t
            .on_reading(&reading(10_000, Idle, 480, 700, 11))
            .expect("purge");
        assert_eq!(purge.kind, RunKind::Purge);
        assert_eq!(purge.duration_ms, 2_000);
        assert_eq!(purge.counted, Some(false));
    }

    #[test]
    fn a_blip_under_half_a_second_is_dropped_unless_counted() {
        let mut t = GrindTracker::new();
        t.on_reading(&reading(0, Idle, 480, 700, 10));
        t.on_reading(&reading(200, Grinding, 480, 700, 10));
        assert_eq!(t.on_reading(&reading(600, Idle, 480, 700, 10)), None);
        t.on_reading(&reading(800, Grinding, 480, 700, 10));
        let s = t
            .on_reading(&reading(1_000, Idle, 480, 700, 11))
            .expect("counted");
        assert_eq!(s.duration_ms, 200);
        assert_eq!(s.counted, Some(true));
        // Exactly 500 ms is kept.
        t.on_reading(&reading(2_000, Grinding, 480, 700, 11));
        assert!(t.on_reading(&reading(2_500, Idle, 480, 700, 11)).is_some());
    }

    #[test]
    fn an_unknown_state_ends_a_grind() {
        let mut t = GrindTracker::new();
        t.on_reading(&reading(0, Grinding, 480, 700, 10));
        let s = t
            .on_reading(&reading(4_000, Unknown, 480, 700, 10))
            .expect("grind");
        assert_eq!(s.ended_by, GrindEnd::StateChange);
    }

    #[test]
    fn a_dropped_link_ends_a_grind_at_its_last_reading() {
        let mut t = GrindTracker::new();
        t.on_reading(&reading(0, Grinding, 480, 700, 10));
        t.on_reading(&reading(6_000, Grinding, 480, 700, 10));
        let s = t.link_lost().expect("grind");
        assert_eq!((s.ended_at, s.duration_ms), (6_000, 6_000));
        assert_eq!(s.ended_by, GrindEnd::LinkLost);
        assert_eq!(t.link_lost(), None);
    }

    #[test]
    fn missing_fields_give_missing_summaries() {
        let mut t = GrindTracker::new();
        let mut r = reading(0, Grinding, 480, 700, 10);
        r.setting = None;
        r.grind_rpm = None;
        r.feed_rpm = None;
        r.total_grinds = None;
        t.on_reading(&r);
        r.at_ms = 1_000;
        r.setting = Some(ObservedSetting {
            value: "490".into(),
            number: Some(490.0),
        });
        t.on_reading(&r);
        r.at_ms = 2_000;
        r.state = Idle;
        let s = t.on_reading(&r).expect("grind");
        assert_eq!(s.setting.map(|v| v.value).as_deref(), Some("490"));
        assert!(!s.setting_changed);
        assert_eq!(
            (s.grind_rpm, s.grind_rpm_min, s.feed_rpm),
            (None, None, None)
        );
        assert_eq!(s.counted, None);
    }
}
