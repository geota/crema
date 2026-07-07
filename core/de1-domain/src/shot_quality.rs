//! Shot-quality analysis — channeling, grind direction, pour truncation, and
//! the summary verdict. A faithful port of Decenza's `ShotAnalysis` pipeline
//! (`Decenza/src/ai/shotanalysis.{h,cpp}`) and its conductance helpers
//! (`Decenza/src/ai/conductance.{h,cpp}`).
//!
//! [`analyze_shot`] is sans-IO and pure: feed it the recorded shot series
//! (pressure / flow / weight plus the goal curves and phase markers) and it
//! returns a [`ShotQualityReport`] — prose [`QualityLine`]s (the same English
//! text Decenza renders, em-dashes and all), four projection [`QualityBadges`],
//! the structured [`DetectorResults`], and a stable `verdict_category`.
//!
//! Unlike Decenza, crema does not persist a conductance series, so
//! `analyze_shot` derives it internally from pressure + flow via
//! [`conductance_series`] / [`conductance_derivative`] (both exported for the
//! shells' chart overlays).
//!
//! Deliberate deviation from Decenza: the expert-band knowledge base
//! (`ExpertBand`, verdict category `expertBandDeviation`,
//! `shotanalysis.cpp:1089-1200,1290-1301`) is omitted — crema has no profile
//! KB — and `profile_kb_resolved` is taken directly as an input flag instead
//! of being resolved from a KB id.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::history::StoredShot;
use crate::profile::{BeverageType, Compare, ExitMetric};

// ---------------------------------------------------------------------------
// Thresholds (tune here, applies everywhere) — `shotanalysis.h:84-302`.
// ---------------------------------------------------------------------------

/// |dC/dt| above this counts as an elevated sample (`shotanalysis.h:88`,
/// `CHANNELING_DC_ELEVATED`). Strictly greater-than.
pub const CHANNELING_DC_ELEVATED: f64 = 3.0;

/// A single-sample |dC/dt| peak above this is flagged as a transient
/// (self-healed) channel (`shotanalysis.h:89`, `CHANNELING_DC_TRANSIENT_PEAK`).
/// Strictly greater-than.
pub const CHANNELING_DC_TRANSIENT_PEAK: f64 = 5.0;

/// More than this many elevated samples means sustained channeling
/// (`shotanalysis.h:90`, `CHANNELING_DC_SUSTAINED_COUNT`). Strictly
/// greater-than: 11 elevated samples fire, 10 do not.
pub const CHANNELING_DC_SUSTAINED_COUNT: i32 = 10;

/// Seconds skipped at the start of the pour before channeling analysis — the
/// pressure-ramp / flow-catch-up transition spike is not a puck signal
/// (`shotanalysis.h:91`, `CHANNELING_DC_POUR_SKIP_SEC`).
pub const CHANNELING_DC_POUR_SKIP_S: f64 = 2.0;

/// Seconds trimmed off the end of the pour — flat-pressure profiles naturally
/// see dC/dt climb at the tail from puck erosion (`shotanalysis.h:92`,
/// `CHANNELING_DC_POUR_SKIP_END_SEC`).
pub const CHANNELING_DC_POUR_SKIP_END_S: f64 = 1.5;

/// Mean pour-window flow (mL/s) above which the shot is treated as a
/// turbo/filter shot and channeling analysis is skipped (`shotanalysis.h:93`,
/// `CHANNELING_MAX_AVG_FLOW`). Strictly greater-than.
pub const CHANNELING_MAX_AVG_FLOW: f64 = 3.0;

/// Relative goal change (across ±[`WINDOW_HALF_S`]) above which the active
/// goal is not stationary and the sample is excluded from channeling windows
/// (`shotanalysis.h:99`, `WINDOW_STATIONARY_REL`). Strictly greater-than
/// fails.
pub const WINDOW_STATIONARY_REL: f64 = 0.15;

/// Relative |actual − goal| / goal above which the actual value has not
/// converged onto the goal (`shotanalysis.h:100`, `WINDOW_CONVERGED_REL`).
/// Strictly greater-than fails.
pub const WINDOW_CONVERGED_REL: f64 = 0.15;

/// Half-width, seconds, of the stationarity / rising-pressure look-around
/// (`shotanalysis.h:101`, `WINDOW_HALF_SEC`).
pub const WINDOW_HALF_S: f64 = 0.75;

/// Goals below this are "no active control" — sentinel / unset
/// (`shotanalysis.h:102`, `WINDOW_MIN_GOAL`). Strictly less-than fails.
pub const WINDOW_MIN_GOAL: f64 = 0.1;

/// Window fragments separated by a gap of at most this many seconds are
/// merged (`shotanalysis.h:103`, `WINDOW_GAP_MERGE_SEC`). Inclusive.
pub const WINDOW_GAP_MERGE_S: f64 = 0.3;

/// Minimum pressure goal (bar) for a pressure-mode sample to count toward
/// channeling windows — excludes low-pressure soak/preinfusion phases whose
/// puck-wetting dC/dt fluctuations mimic channeling (`shotanalysis.h:109`,
/// `WINDOW_MIN_EXTRACTION_BAR`). Flow-mode phases bypass this gate.
pub const WINDOW_MIN_EXTRACTION_BAR: f64 = 4.5;

/// Peak pour-window pressure below this means the puck never built
/// resistance — the pour never really happened (`shotanalysis.h:120`,
/// `PRESSURE_FLOOR_BAR`). Strictly less-than fires.
pub const PRESSURE_FLOOR_BAR: f64 = 2.5;

/// Flow-goal values below this (mL/s) are ignored by the grind averaging —
/// preinfusion sentinel / unset goal (`shotanalysis.h:206`,
/// `FLOW_GOAL_MIN_AVG`). Strictly less-than skips.
pub const FLOW_GOAL_MIN_AVG: f64 = 0.3;

/// Average flow-vs-goal deviation (mL/s) beyond which a grind direction is
/// flagged (`shotanalysis.h:207`, `FLOW_DEVIATION_THRESHOLD`). Strictly
/// greater-than |delta| flags.
pub const FLOW_DEVIATION_THRESHOLD: f64 = 0.4;

/// Pressure (bar) a sample must reach to count as "pressurized" in the
/// choked-puck arm (`shotanalysis.h:226`, `CHOKED_PRESSURE_MIN_BAR`).
/// Strictly less-than resets the run.
pub const CHOKED_PRESSURE_MIN_BAR: f64 = 4.0;

/// Mean pressurized flow (mL/s) below which the severe choke arm fires
/// (`shotanalysis.h:227`, `CHOKED_FLOW_MAX_MLPS`). Strictly less-than.
pub const CHOKED_FLOW_MAX_ML_S: f64 = 0.5;

/// Integrated pressurized duration (s) the severe choke arm's gate requires
/// (`shotanalysis.h:228`, `CHOKED_DURATION_MIN_SEC`). Greater-or-equal
/// passes.
pub const CHOKED_DURATION_MIN_S: f64 = 15.0;

/// Yield / target ratio below which the moderate (yield-shortfall) choke arm
/// fires (`shotanalysis.h:234`, `CHOKED_YIELD_RATIO_MAX` — tightened from
/// 0.85 by Decenza's 500-shot audit). Strictly less-than.
pub const CHOKED_YIELD_RATIO_MAX: f64 = 0.70;

/// Yield / target ratio above which the yield-overshoot ("gusher") arm fires
/// (`shotanalysis.h:253`, `YIELD_OVERSHOOT_RATIO_MIN`). Strictly
/// greater-than.
pub const YIELD_OVERSHOOT_RATIO_MIN: f64 = 1.20;

/// Leading trim (s) on the first flow-mode phase at pour start — pump
/// ramp-up from idle is mechanical lag, not a grind signal
/// (`shotanalysis.h:264`, `GRIND_PUMP_RAMP_SKIP_SEC`).
pub const GRIND_PUMP_RAMP_SKIP_S: f64 = 0.5;

/// Trailing trim (s) on a flow-mode phase that exits via the "pressure"
/// transition — once the firmware's pressure limiter engages, the trailing
/// flow undershoot is by design (`shotanalysis.h:265`,
/// `GRIND_LIMITER_TAIL_SKIP_SEC`).
pub const GRIND_LIMITER_TAIL_SKIP_S: f64 = 1.5;

/// Half-width (s) of the flow-goal stationarity gate in grind Arm 1 —
/// excludes dynamic-bloom decay frames whose "goal" is a firmware ramp-down
/// (`shotanalysis.h:301`, `FLOW_GOAL_STATIONARY_HALF_SEC`).
pub const FLOW_GOAL_STATIONARY_HALF_S: f64 = 0.75;

/// Relative flow-goal change above which grind Arm 1 skips the sample
/// (`shotanalysis.h:302`, `FLOW_GOAL_STATIONARY_REL`). Strictly
/// greater-than skips.
pub const FLOW_GOAL_STATIONARY_REL: f64 = 0.15;

/// Minimum pressure-series length for any analysis — below this
/// `analyze_shot` bails with "Not enough data" (`shotanalysis.cpp:753`) and
/// `detect_pour_truncated` stays silent (`shotanalysis.cpp:642`).
pub const MIN_PRESSURE_SAMPLES: usize = 10;

/// Flow-trend delta (mL/s) beyond which rising/falling flow is called out
/// (`shotanalysis.cpp:860,867`). Strictly greater-than / less-than.
pub const FLOW_TREND_DELTA: f64 = 0.5;

/// Pour-progress fraction below which samples land in the flow-trend
/// "start" bucket (`shotanalysis.cpp:853`). Strictly less-than.
pub const FLOW_TREND_START_FRAC: f64 = 0.3;

/// Pour-progress fraction above which samples land in the flow-trend "end"
/// bucket (`shotanalysis.cpp:854`). Strictly greater-than.
pub const FLOW_TREND_END_FRAC: f64 = 0.7;

/// Preinfusion drip weight (g) above which the observation fires
/// (`shotanalysis.cpp:889`). Strictly greater-than.
pub const PREINFUSION_DRIP_MIN_G: f64 = 0.5;

/// Preinfusion duration (s) above which the drip observation fires
/// (`shotanalysis.cpp:889`). Strictly greater-than.
pub const PREINFUSION_DRIP_MIN_S: f64 = 1.0;

/// Hard skip-first-frame window (s) — parity with the de1app Tcl plugin's
/// polling window (`shotanalysis.cpp:711,721`). Strictly less-than fires.
pub const SKIP_FIRST_FRAME_WINDOW_S: f64 = 2.0;

// ---------------------------------------------------------------------------
// Conductance constants — `conductance.{h,cpp}`.
// ---------------------------------------------------------------------------

/// Pressure/flow dead zone: either input at or below this yields zero
/// conductance (`conductance.h:20`).
pub const CONDUCTANCE_DEAD_ZONE: f64 = 0.05;

/// Conductance clamp, matching the Visualizer convention
/// (`conductance.h:22`).
pub const CONDUCTANCE_CLAMP: f64 = 19.0;

/// dC/dt scale factor — the centered difference is multiplied by 10 to match
/// Visualizer.coffee (`conductance.cpp:32`).
pub const DCDT_SCALE: f64 = 10.0;

/// Smoothed dC/dt clamp floor (`conductance.cpp:66`).
pub const DCDT_CLAMP_MIN: f64 = -5.0;

/// Smoothed dC/dt clamp ceiling (`conductance.cpp:67`).
pub const DCDT_CLAMP_MAX: f64 = 19.0;

/// 9-point Gaussian smoothing kernel applied to the raw derivative
/// (`conductance.cpp:46-49`, from Visualizer.coffee).
pub const GAUSSIAN_KERNEL: [f64; 9] = [
    0.048297, 0.08393, 0.124548, 0.157829, 0.170793, 0.157829, 0.124548, 0.08393, 0.048297,
];

/// Half-width of [`GAUSSIAN_KERNEL`] (`conductance.cpp:50`).
pub const KERNEL_HALF: usize = 4;

// ---------------------------------------------------------------------------
// Input types.
// ---------------------------------------------------------------------------

/// One sample of a time series: `t` seconds from extraction start, `v` the
/// value (bar, mL/s, g, or conductance depending on the series). Series are
/// ordered ascending by `t`; there is no fixed sample rate — every window
/// rule works off the actual timestamps.
#[typeshare]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SeriesPoint {
    /// Seconds from extraction start.
    pub t: f64,
    /// Sample value.
    pub v: f64,
}

/// A profile phase boundary observed during the shot — the port of Decenza's
/// `HistoryPhaseMarker`. Decenza inserts a synthetic first marker with
/// `label == "Start"` and `frame_number == 0` at extraction start
/// (`shotanalysis.cpp:667-672`).
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PhaseMarker {
    /// Seconds from extraction start at which this phase began.
    pub time_s: f64,
    /// Phase label ("Start", "Preinfusion", "Pour", "End", …).
    pub label: String,
    /// 0-based profile frame number; negative when unknown.
    pub frame_number: i32,
    /// Whether this frame is flow-controlled (pump=flow) rather than
    /// pressure-controlled.
    pub is_flow_mode: bool,
    /// Why the *preceding* frame exited: "weight" / "pressure" / "flow"
    /// (sensor-confirmed), "pressure_unconfirmed" / "flow_unconfirmed",
    /// "time", or "" when unknown (old data).
    pub transition_reason: String,
}

/// One profile frame's configured exit, used to infer
/// [`PhaseMarker::transition_reason`] for reconstructed markers (indexed by
/// frame number in [`ShotQualityInput::frame_exits`]).
///
/// Crema never stored live transition reasons — shells rebuild phase markers
/// from telemetry, so every reason arrived as `""` and the confirmed-exit
/// suppression in skip-first-frame detection was dead code: a fill frame
/// exiting early on its pressure target got a false "First step skipped"
/// badge on every shot (the exact user-visible symptom Decenza fixed in
/// PR #1421, `shotanalysis.cpp:677-699`). With the specs supplied, the
/// analysis infers the reason from the recorded series at each boundary.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct FrameExitSpec {
    /// "pressure" / "flow", or "" when the frame has no early exit.
    pub metric: String,
    /// True = the frame exits when the metric rises OVER the threshold;
    /// false = when it falls under.
    pub exit_over: bool,
    /// Exit threshold (bar or mL/s, per `metric`).
    pub threshold: f64,
    /// Configured max frame duration, seconds; `<= 0` when unknown.
    pub max_duration_s: f64,
}

/// Everything [`analyze_shot`] needs — the port of `ShotAnalysis::analyzeShot`'s
/// parameter list (`shotanalysis.cpp:731-747`), minus the conductance
/// derivative (computed internally from pressure + flow) and the expert band
/// (omitted in this port).
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ShotQualityInput {
    /// Group pressure (bar) over the shot.
    pub pressure: Vec<SeriesPoint>,
    /// Group flow (mL/s) over the shot.
    pub flow: Vec<SeriesPoint>,
    /// Cup weight (g) over the shot; may be empty (no scale).
    pub weight: Vec<SeriesPoint>,
    /// Commanded pressure goal (bar); may be empty.
    pub pressure_goal: Vec<SeriesPoint>,
    /// Commanded flow goal (mL/s); may be empty.
    pub flow_goal: Vec<SeriesPoint>,
    /// Phase boundary markers in time order.
    pub phases: Vec<PhaseMarker>,
    /// Beverage type; `filter` / `pourover` / `tea` / `steam` / `cleaning`
    /// (case-insensitive) skip the puck-integrity detectors.
    pub beverage_type: String,
    /// Total shot duration, seconds — the default pour end when no "End"
    /// marker is present.
    pub duration_s: f64,
    /// Configured duration of profile frame 0, seconds; `-1` when unknown.
    /// Drives the short-first-step cutoff in skip-first-frame detection.
    pub first_frame_configured_s: f64,
    /// Effective stop-at-weight target (g); `0` disables the yield arms.
    pub target_weight_g: f64,
    /// Final in-cup weight (g); `0` disables the yield arms.
    pub final_weight_g: f64,
    /// Number of frames in the profile; `-1` when unknown. Values `< 2`
    /// suppress skip-first-frame detection.
    pub expected_frame_count: i32,
    /// Per-profile analysis flags: `grind_check_skip`, `channeling_expected`,
    /// `flow_trend_ok`.
    pub analysis_flags: Vec<String>,
    /// Whether the profile's shape is known well enough to trust the flow
    /// goal as a target (Decenza: KB resolution succeeded). When false,
    /// grind Arm 1 (flow-vs-goal averaging) is skipped entirely; Arm 2's
    /// physics-level arms still run (`shotanalysis.cpp:369-384`).
    pub profile_kb_resolved: bool,
    /// Per-frame exit specs from the profile snapshot, indexed by frame
    /// number; empty when no profile was stored. Lets the analysis infer
    /// the `transition_reason` shells can't supply (see [`FrameExitSpec`]).
    /// `serde(default)` — additive, absent on inputs built before it.
    #[serde(default)]
    pub frame_exits: Vec<FrameExitSpec>,
}

// ---------------------------------------------------------------------------
// Output types.
// ---------------------------------------------------------------------------

/// One prose line of the shot summary. `line_type` is one of `good` /
/// `caution` / `warning` / `observation` / `verdict`; `kind` is a stable
/// machine-readable id (e.g. `channeling_sustained`). For the verdict line,
/// `kind` carries the verdict category (Decenza's verdict map has no kind).
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct QualityLine {
    /// User-facing English text (Decenza's exact wording).
    pub text: String,
    /// Stable machine-readable line id.
    pub kind: String,
    /// good / caution / warning / observation / verdict.
    pub line_type: String,
}

/// The four badge booleans the shells surface as chips. Projection rules:
/// `channeling` fires only on *sustained* severity (transients stay a prose
/// caution); `grind_issue` mirrors `detectGrindIssue`
/// (`shotanalysis.cpp:610-625`): not skipped, has data, and choked /
/// overshoot / |delta| beyond [`FLOW_DEVIATION_THRESHOLD`];
/// `pour_truncated` and `skip_first_frame` are 1:1 with their detectors.
#[typeshare]
#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct QualityBadges {
    /// Sustained channeling detected.
    pub channeling: bool,
    /// Grind flagged: choked, gusher, or |flow-vs-goal delta| > threshold.
    pub grind_issue: bool,
    /// The pour never pressurized (peak < [`PRESSURE_FLOOR_BAR`]).
    pub pour_truncated: bool,
    /// Profile frame 0 appears to have been skipped.
    pub skip_first_frame: bool,
}

/// Structured detector outputs — the typed values behind the prose lines,
/// mirroring Decenza's `DetectorResults` (`shotanalysis.h:479-600`).
/// `*_checked == false` means the detector was suppressed (pour-truncated
/// cascade, beverage-type skip, profile flag, or insufficient input); the
/// rest of that detector's fields stay at their defaults. Distinguishing
/// "not checked" from "checked, no signal" matters — silence on a skipped
/// detector is not the same as silence on a clean shot.
#[typeshare]
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DetectorResults {
    /// Pour never pressurized — runs first and dominates the cascade.
    pub pour_truncated: bool,
    /// Peak pour-window pressure; populated only when `pour_truncated` fired.
    pub peak_pressure_bar: f64,
    /// Pour window start the cascade used (0 when no pour/preinfusion
    /// markers were present).
    pub pour_start_s: f64,
    /// Pour window end (shot duration when no "End" marker; 0 only on the
    /// insufficient-data early return).
    pub pour_end_s: f64,
    /// Whether the channeling detector ran.
    pub channeling_checked: bool,
    /// "" if unchecked; else "none" / "transient" / "sustained".
    pub channeling_severity: String,
    /// Timestamp of the largest |dC/dt| spike inside the analysis windows.
    pub channeling_spike_time_s: f64,
    /// Whether the flow-trend detector ran.
    pub flow_trend_checked: bool,
    /// "" if unchecked; else "stable" / "rising" / "falling".
    pub flow_trend: String,
    /// (avg flow in last 30% of pour) − (avg in first 30%).
    pub flow_trend_delta_ml_s: f64,
    /// Preinfusion drip observation fired (weight and duration thresholds).
    pub preinfusion_observed: bool,
    /// Cup weight at the end of preinfusion.
    pub preinfusion_drip_weight_g: f64,
    /// Preinfusion duration (first marker → preinfusion end).
    pub preinfusion_drip_duration_s: f64,
    /// Whether the grind detector ran (not suppressed by cascade or config).
    pub grind_checked: bool,
    /// At least one grind arm produced a result.
    pub grind_has_data: bool,
    /// The pressure-mode choke check fired (severe flow or moderate yield
    /// arm).
    pub grind_choked_puck: bool,
    /// Yield ran past target by more than [`YIELD_OVERSHOOT_RATIO_MIN`].
    pub grind_yield_overshoot: bool,
    /// The puck behaved through a sustained pressurized pour — positive
    /// verification, not just absence of signal.
    pub grind_verified_clean: bool,
    /// (avg actual flow) − (avg goal flow); positive = coarse. Meaningful
    /// only when neither `grind_choked_puck` nor `grind_yield_overshoot`
    /// fired.
    pub grind_flow_delta_ml_s: f64,
    /// Qualifying samples averaged (Arm 1) or pressurized samples observed
    /// (choked path).
    pub grind_sample_count: i32,
    /// "" if no data; else "yieldOvershoot" / "chokedPuck" / "tooFine" /
    /// "tooCoarse" / "onTarget".
    pub grind_direction: String,
    /// "verified" / "notAnalyzable" / "skipped" / "" (pour-truncated cascade
    /// or degenerate pour window) — see `shotanalysis.h:556-573`.
    pub grind_coverage: String,
    /// Profile frame 0 appears to have been skipped.
    pub skip_first_frame: bool,
}

/// Combined output of [`analyze_shot`]: the prose lines (ending in one
/// verdict line, except on the insufficient-data early return), the badge
/// projection, the stable verdict category, and the structured detector
/// results the lines were formatted from.
#[typeshare]
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ShotQualityReport {
    /// Prose observation list, verdict last.
    pub lines: Vec<QualityLine>,
    /// Badge projection for chips.
    pub badges: QualityBadges,
    /// Stable enum-like category (see `shotanalysis.h:578-599`):
    /// `clean`, `cleanGrindNotAnalyzable`, `insufficientData`,
    /// `puckTruncated`, `skipFirstFrame`, `yieldOvershoot`, `chokedPuck`,
    /// `puckIntegrity[GrindFine|GrindCoarse]`,
    /// `minorIssues[GrindFine|GrindCoarse]`.
    pub verdict_category: String,
    /// The typed detector values behind the lines.
    pub detectors: DetectorResults,
}

// ---------------------------------------------------------------------------
// Conductance helpers — `conductance.{h,cpp}`.
// ---------------------------------------------------------------------------

/// Darcy conductance `C = F² / P`, clamped to [`CONDUCTANCE_CLAMP`] to match
/// the Visualizer convention; zero when either input is essentially zero
/// (`conductance.h:18-23`).
fn conductance_sample(pressure_bar: f64, flow_ml_s: f64) -> f64 {
    if flow_ml_s <= CONDUCTANCE_DEAD_ZONE || pressure_bar <= CONDUCTANCE_DEAD_ZONE {
        return 0.0;
    }
    let c = flow_ml_s * flow_ml_s / pressure_bar;
    c.min(CONDUCTANCE_CLAMP)
}

/// Build a time-aligned conductance series from pressure and flow. Samples
/// are paired **by index** — callers must ensure the two series share a time
/// axis; output length is the shorter of the two and each output point takes
/// the pressure sample's timestamp (`conductance.cpp:7-18`).
pub fn conductance_series(pressure: &[SeriesPoint], flow: &[SeriesPoint]) -> Vec<SeriesPoint> {
    pressure
        .iter()
        .zip(flow.iter())
        .map(|(p, f)| SeriesPoint {
            t: p.t,
            v: conductance_sample(p.v, f.v),
        })
        .collect()
}

/// Compute dC/dt on a conductance series (`conductance.cpp:20-72`): a
/// centered-difference derivative scaled ×[`DCDT_SCALE`] (forward/backward
/// difference at the edges, zero when the timestamps are degenerate), then
/// 9-point Gaussian smoothing with edge renormalization
/// ([`GAUSSIAN_KERNEL`]), then a clamp to
/// [[`DCDT_CLAMP_MIN`], [`DCDT_CLAMP_MAX`]]. Returns empty for fewer than 3
/// input points.
///
/// This is the single most diagnostic puck-integrity signal — it exposes
/// transient channeling events invisible in pressure / flow alone.
pub fn conductance_derivative(conductance: &[SeriesPoint]) -> Vec<SeriesPoint> {
    let n = conductance.len();
    if n < 3 {
        return Vec::new();
    }

    // Step 1: centered difference, scaled ×10 (`conductance.cpp:26-43`).
    let mut raw = vec![0.0; n];
    for i in 1..n - 1 {
        let dt = conductance[i + 1].t - conductance[i - 1].t;
        if dt > 0.001 {
            raw[i] = (conductance[i + 1].v - conductance[i - 1].v) / dt * DCDT_SCALE;
        }
    }
    let dt = conductance[1].t - conductance[0].t;
    if dt > 0.001 {
        raw[0] = (conductance[1].v - conductance[0].v) / dt * DCDT_SCALE;
    }
    let dt = conductance[n - 1].t - conductance[n - 2].t;
    if dt > 0.001 {
        raw[n - 1] = (conductance[n - 1].v - conductance[n - 2].v) / dt * DCDT_SCALE;
    }

    // Step 2: Gaussian smoothing with renormalization at the edges, then
    // step 3: clamp (`conductance.cpp:52-68`).
    let mut out = Vec::with_capacity(n);
    for i in 0..n {
        let mut smoothed = 0.0;
        let mut weight_sum = 0.0;
        let lo = i.saturating_sub(KERNEL_HALF);
        let hi = (i + KERNEL_HALF).min(n - 1);
        for (idx, raw_v) in raw.iter().enumerate().take(hi + 1).skip(lo) {
            let w = GAUSSIAN_KERNEL[idx + KERNEL_HALF - i];
            smoothed += raw_v * w;
            weight_sum += w;
        }
        if weight_sum > 0.0 {
            smoothed /= weight_sum;
        }
        out.push(SeriesPoint {
            t: conductance[i].t,
            v: smoothed.clamp(DCDT_CLAMP_MIN, DCDT_CLAMP_MAX),
        });
    }
    out
}

// ---------------------------------------------------------------------------
// Series / phase lookup helpers.
// ---------------------------------------------------------------------------

/// Linear interpolation at time `t`; NaN when the series is empty or `t`
/// falls outside `[first.t, last.t]` — callers treat NaN as "no goal data
/// for this moment" (`shotanalysis.cpp:28-45`, `lookupOrNaN`).
fn lookup_or_nan(data: &[SeriesPoint], t: f64) -> f64 {
    let Some(first) = data.first() else {
        return f64::NAN;
    };
    let last = data[data.len() - 1];
    if t < first.t || t > last.t {
        return f64::NAN;
    }
    for i in 1..data.len() {
        if data[i].t >= t {
            let (x0, x1) = (data[i - 1].t, data[i].t);
            let (y0, y1) = (data[i - 1].v, data[i].v);
            if x1 <= x0 {
                return y1;
            }
            let alpha = (t - x0) / (x1 - x0);
            return y0 + alpha * (y1 - y0);
        }
    }
    last.v
}

/// Step lookup: the value of the first sample with `t >= time`, else the
/// last sample's value; 0 for an empty series (`shotanalysis.cpp:305-312`,
/// `findValueAtTime`). NOT interpolated — the grind detector deliberately
/// differs from the channeling detector's [`lookup_or_nan`]
/// (`shotanalysis.h:292-300`).
fn find_value_at_time(data: &[SeriesPoint], time: f64) -> f64 {
    if data.is_empty() {
        return 0.0;
    }
    for pt in data {
        if pt.t >= time {
            return pt.v;
        }
    }
    data[data.len() - 1].v
}

/// The `is_flow_mode` flag of the phase active at time `t` — the last marker
/// with `time_s <= t`. Per the extracted spec, `None` when no marker has
/// started yet ("none before first → fail"); Decenza's `phaseAtTime`
/// (`shotanalysis.cpp:13-23`) instead falls back to the first marker, but
/// the two only differ for `t` before the first marker, which cannot occur
/// in practice — the synthetic "Start" marker sits at extraction start.
fn phase_at_time(phases: &[PhaseMarker], t: f64) -> Option<bool> {
    let mut active = None;
    for phase in phases {
        if phase.time_s <= t {
            active = Some(phase.is_flow_mode);
        } else {
            break;
        }
    }
    active
}

/// The filter/pourover/tea/steam/cleaning beverage skip-set, case-insensitive
/// (`shotanalysis.cpp:277-287,328-333,634-640`): none of these have a puck
/// whose integrity can be scored.
fn beverage_skips_analysis(beverage_type: &str) -> bool {
    matches!(
        beverage_type.to_lowercase().as_str(),
        "filter" | "pourover" | "tea" | "steam" | "cleaning"
    )
}

// ---------------------------------------------------------------------------
// Channeling detection.
// ---------------------------------------------------------------------------

/// Contiguous time range to include in dC/dt channeling analysis
/// (`shotanalysis.h:132-135`, `DetectionWindow`).
#[derive(Debug, Clone, Copy, PartialEq)]
struct DetectionWindow {
    start: f64,
    end: f64,
}

/// Severity levels of `detect_channeling_from_derivative`
/// (`shotanalysis.h:125-129`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ChannelingSeverity {
    None,
    Transient,
    Sustained,
}

/// Commit `current` into `windows` (merging into the previous window when
/// the gap is at most [`WINDOW_GAP_MERGE_S`]) and reset it
/// (`shotanalysis.cpp:145-156`, `flushCurrent`).
fn flush_window(windows: &mut Vec<DetectionWindow>, current: &mut DetectionWindow) {
    if current.start >= 0.0 && current.end > current.start {
        match windows.last_mut() {
            Some(last) if current.start - last.end <= WINDOW_GAP_MERGE_S => last.end = current.end,
            _ => windows.push(*current),
        }
    }
    *current = DetectionWindow {
        start: -1.0,
        end: -1.0,
    };
}

/// Build mode-aware inclusion windows for channeling detection
/// (`shotanalysis.cpp:116-271`, `buildChannelingWindows`). Walks the
/// pressure series as the time grid (flow when pressure is empty) and admits
/// a sample only when the active goal (flow goal in flow-mode phases,
/// pressure goal otherwise) is stationary, the actual value has converged
/// onto it, and pressure is not rising fast. No phase data → a single
/// whole-pour window (legacy fallback); phases present but nothing
/// qualifying → empty, which the detector treats as a deliberate silent
/// pass, NOT a whole-pour fallback.
fn build_channeling_windows(
    input: &ShotQualityInput,
    pour_start: f64,
    pour_end: f64,
) -> Vec<DetectionWindow> {
    let mut windows = Vec::new();
    if pour_end <= pour_start {
        return windows;
    }
    if input.phases.is_empty() {
        windows.push(DetectionWindow {
            start: pour_start,
            end: pour_end,
        });
        return windows;
    }
    let grid = if input.pressure.is_empty() {
        &input.flow
    } else {
        &input.pressure
    };
    if grid.is_empty() {
        return windows;
    }

    let mut current = DetectionWindow {
        start: -1.0,
        end: -1.0,
    };

    // Trim both pour fringes so the windows match what the detector will
    // actually analyze (`shotanalysis.cpp:158-163`).
    let analysis_start = pour_start + CHANNELING_DC_POUR_SKIP_S;
    let analysis_end = pour_end - CHANNELING_DC_POUR_SKIP_END_S;

    for pt in grid {
        let t = pt.t;
        if t < analysis_start {
            continue;
        }
        if t > analysis_end {
            break;
        }

        let Some(is_flow_mode) = phase_at_time(&input.phases, t) else {
            flush_window(&mut windows, &mut current);
            continue;
        };

        let (goal_series, actual_series) = if is_flow_mode {
            (&input.flow_goal, &input.flow)
        } else {
            (&input.pressure_goal, &input.pressure)
        };

        let goal_now = lookup_or_nan(goal_series, t);
        let goal_past = lookup_or_nan(goal_series, t - WINDOW_HALF_S);
        let goal_fut = lookup_or_nan(goal_series, t + WINDOW_HALF_S);
        let actual = lookup_or_nan(actual_series, t);

        // No goal data at this moment → skip (`shotanalysis.cpp:184-189`).
        if goal_now.is_nan() || goal_past.is_nan() || goal_fut.is_nan() || actual.is_nan() {
            flush_window(&mut windows, &mut current);
            continue;
        }
        // Goal is zero/sentinel — no active control (`shotanalysis.cpp:190-194`).
        if goal_now < WINDOW_MIN_GOAL {
            flush_window(&mut windows, &mut current);
            continue;
        }
        // Pressure-mode only: exclude low-pressure soaks whose puck-wetting
        // dC/dt mimics channeling (`shotanalysis.cpp:196-204`).
        if !is_flow_mode && goal_now < WINDOW_MIN_EXTRACTION_BAR {
            flush_window(&mut windows, &mut current);
            continue;
        }
        // Stationarity (`shotanalysis.cpp:206-212`).
        let rel_past = (goal_past - goal_now).abs() / goal_now;
        let rel_fut = (goal_fut - goal_now).abs() / goal_now;
        if rel_past > WINDOW_STATIONARY_REL || rel_fut > WINDOW_STATIONARY_REL {
            flush_window(&mut windows, &mut current);
            continue;
        }
        // Convergence (`shotanalysis.cpp:214-219`).
        let convergence_err = (actual - goal_now).abs() / goal_now;
        if convergence_err > WINDOW_CONVERGED_REL {
            flush_window(&mut windows, &mut current);
            continue;
        }
        // Rising-pressure fence, both modes: intentional pressure ramps
        // (lever rise, rise-and-hold final leg) produce the same
        // conductance-drop signature as channeling; falling pressure is a
        // legitimate signal and stays in (`shotanalysis.cpp:221-260`).
        let pressure_now = lookup_or_nan(&input.pressure, t);
        let pressure_fut = lookup_or_nan(&input.pressure, t + WINDOW_HALF_S);
        if !pressure_now.is_nan()
            && !pressure_fut.is_nan()
            && pressure_now > 0.5
            && pressure_fut > pressure_now * (1.0 + WINDOW_STATIONARY_REL)
        {
            flush_window(&mut windows, &mut current);
            continue;
        }

        // Sample qualifies — extend or start the current window.
        if current.start < 0.0 {
            current.start = t;
        }
        current.end = t;
    }
    flush_window(&mut windows, &mut current);
    windows
}

/// Classify puck integrity from the smoothed dC/dt series
/// (`shotanalysis.cpp:49-114`, `detectChannelingFromDerivative`). Counts
/// BOTH signs of dC/dt: the textbook channel is a positive surge, but the
/// post-channel flow collapse is an equally diagnostic negative. Empty
/// `windows` → `None` immediately — a deliberate silent pass, never an
/// unrestricted fallback. Returns the severity and the timestamp of the
/// largest spike.
fn detect_channeling_from_derivative(
    conductance_derivative: &[SeriesPoint],
    pour_start: f64,
    pour_end: f64,
    windows: &[DetectionWindow],
) -> (ChannelingSeverity, f64) {
    if conductance_derivative.is_empty() || windows.is_empty() {
        return (ChannelingSeverity::None, 0.0);
    }

    let analysis_start = pour_start + CHANNELING_DC_POUR_SKIP_S;
    let analysis_end = pour_end - CHANNELING_DC_POUR_SKIP_END_S;
    let mut max_spike = 0.0;
    let mut max_spike_time = 0.0;
    let mut sustained_count: i32 = 0;

    for pt in conductance_derivative {
        if pt.t < analysis_start {
            continue;
        }
        if pt.t > analysis_end {
            break;
        }
        if !windows.iter().any(|w| pt.t >= w.start && pt.t <= w.end) {
            continue;
        }
        let v = pt.v.abs();
        if v > max_spike {
            max_spike = v;
            max_spike_time = pt.t;
        }
        if v > CHANNELING_DC_ELEVATED {
            sustained_count += 1;
        }
    }

    let severity = if sustained_count > CHANNELING_DC_SUSTAINED_COUNT {
        ChannelingSeverity::Sustained
    } else if max_spike > CHANNELING_DC_TRANSIENT_PEAK {
        ChannelingSeverity::Transient
    } else {
        ChannelingSeverity::None
    };
    (severity, max_spike_time)
}

/// Whether channeling analysis should be skipped: non-espresso beverages,
/// or turbo shots whose mean pour-window flow (samples above the dead zone)
/// exceeds [`CHANNELING_MAX_AVG_FLOW`] (`shotanalysis.cpp:273-303`,
/// `shouldSkipChannelingCheck`).
fn should_skip_channeling_check(
    beverage_type: &str,
    flow: &[SeriesPoint],
    pour_start: f64,
    pour_end: f64,
) -> bool {
    if beverage_skips_analysis(beverage_type) {
        return true;
    }
    if pour_start < pour_end && !flow.is_empty() {
        let mut sum = 0.0;
        let mut count: i32 = 0;
        for fp in flow {
            if fp.t < pour_start {
                continue;
            }
            if fp.t > pour_end {
                break;
            }
            if fp.v > CONDUCTANCE_DEAD_ZONE {
                sum += fp.v;
                count += 1;
            }
        }
        if count > 0 && sum / f64::from(count) > CHANNELING_MAX_AVG_FLOW {
            return true;
        }
    }
    false
}

// ---------------------------------------------------------------------------
// Pour-truncated detection.
// ---------------------------------------------------------------------------

/// True when the pour never pressurized — peak pour-window pressure stayed
/// below [`PRESSURE_FLOOR_BAR`] (`shotanalysis.cpp:627-655`,
/// `detectPourTruncated`). Catches the puck failures every other detector is
/// blind to: with near-zero resistance, conductance saturates (flat dC/dt)
/// and flow tracks the preinfusion goal perfectly. Skipped for non-espresso
/// beverages where low pressure is expected.
fn detect_pour_truncated(
    pressure: &[SeriesPoint],
    pour_start: f64,
    pour_end: f64,
    beverage_type: &str,
) -> bool {
    if beverage_skips_analysis(beverage_type) {
        return false;
    }
    if pressure.len() < MIN_PRESSURE_SAMPLES || pour_end <= pour_start {
        return false;
    }
    peak_pressure_in_window(pressure, pour_start, pour_end) < PRESSURE_FLOOR_BAR
}

/// Peak pressure over `pour_start..=pour_end` (`shotanalysis.cpp:648-653`;
/// re-used by `analyze_shot` for the warning text, `shotanalysis.cpp:789-797`).
fn peak_pressure_in_window(pressure: &[SeriesPoint], pour_start: f64, pour_end: f64) -> f64 {
    let mut peak_bar = 0.0;
    for pt in pressure {
        if pt.t < pour_start {
            continue;
        }
        if pt.t > pour_end {
            break;
        }
        if pt.v > peak_bar {
            peak_bar = pt.v;
        }
    }
    peak_bar
}

// ---------------------------------------------------------------------------
// Grind direction (flow-vs-goal + choked-puck + yield arms).
// ---------------------------------------------------------------------------

/// Result of the grind direction check (`shotanalysis.h:305-350`,
/// `GrindCheck`; the gate-diagnostic fields are not ported).
#[derive(Debug, Default)]
struct GrindCheck {
    /// (avg actual flow) − (avg goal flow); positive = coarse. Meaningful
    /// only when neither `choked_puck` nor `yield_overshoot` fired.
    delta: f64,
    /// Qualifying samples averaged (Arm 1) or pressurized samples observed
    /// (choked path).
    sample_count: i32,
    /// At least one arm produced a result.
    has_data: bool,
    /// Suppressed by flag or beverage type.
    skipped: bool,
    /// The pressure-mode choke check fired (severe flow or moderate yield
    /// arm).
    choked_puck: bool,
    /// Yield / target above [`YIELD_OVERSHOOT_RATIO_MIN`] — gusher.
    yield_overshoot: bool,
    /// Sustained pressurized pour with no arm firing and delta within
    /// tolerance — positive verification.
    verified_clean: bool,
}

/// An inclusive time range.
#[derive(Debug, Clone, Copy)]
struct TimeRange {
    start: f64,
    end: f64,
}

/// The grind direction check (`shotanalysis.cpp:314-608`,
/// `analyzeFlowVsGoal`). Three arms feed one result:
///
/// * **Arm 0** (pre-gate): yield overshoot — yield/target above
///   [`YIELD_OVERSHOOT_RATIO_MIN`]. No pressurized-window gate: a gusher by
///   definition can't sustain pressure (`shotanalysis.cpp:339-350`).
/// * **Arm 1**: flow-vs-goal averaging across flow-mode phases, with a
///   pump-ramp leading trim, a limiter-tail trailing trim, and a flow-goal
///   stationarity gate (`shotanalysis.cpp:352-482`). Gated on
///   `profile_kb_resolved` and phase data being present.
/// * **Arm 2**: choked-puck check restricted to pressure-mode portions of
///   the pour — a severe arm (mean pressurized flow below
///   [`CHOKED_FLOW_MAX_ML_S`] behind the 15 s × 4 bar gate) and a moderate
///   yield-shortfall arm (only needs 5 pressurized samples); a healthy
///   gate-passing walk verifies the pour clean
///   (`shotanalysis.cpp:484-607`).
fn analyze_flow_vs_goal(input: &ShotQualityInput, pour_start: f64, pour_end: f64) -> GrindCheck {
    let mut result = GrindCheck::default();

    if input.analysis_flags.iter().any(|f| f == "grind_check_skip")
        || beverage_skips_analysis(&input.beverage_type)
    {
        result.skipped = true;
        return result;
    }

    // Arm 0: yield overshoot (`shotanalysis.cpp:346-350`).
    if input.target_weight_g > 0.0
        && input.final_weight_g > 0.0
        && input.final_weight_g / input.target_weight_g > YIELD_OVERSHOOT_RATIO_MIN
    {
        result.has_data = true;
        result.yield_overshoot = true;
    }

    if pour_start >= pour_end || input.flow.is_empty() {
        return result;
    }

    // Arm 1: build flow-mode ranges (`shotanalysis.cpp:367-429`). Both
    // trims are gated on the range staying at least 1 s long post-trim so
    // extreme puck-failure shots keep their (short) analyzable window.
    const MIN_POST_TRIM_RANGE_S: f64 = 1.0;
    let mut flow_mode_ranges: Vec<TimeRange> = Vec::new();
    if input.profile_kb_resolved && !input.phases.is_empty() {
        let mut first_flow_mode_at_pour_start_seen = false;
        for (i, phase) in input.phases.iter().enumerate() {
            if !phase.is_flow_mode {
                continue;
            }
            let mut start = phase.time_s;
            let mut end = input.phases.get(i + 1).map_or(pour_end, |n| n.time_s);
            // Pump-ramp trim on the first flow-mode phase that coincides
            // with pour start (0.1 s margin absorbs BLE timestamp jitter).
            if !first_flow_mode_at_pour_start_seen && phase.time_s + 0.1 >= pour_start {
                if (end - start) - GRIND_PUMP_RAMP_SKIP_S >= MIN_POST_TRIM_RANGE_S {
                    start += GRIND_PUMP_RAMP_SKIP_S;
                }
                first_flow_mode_at_pour_start_seen = true;
            }
            // Limiter-tail trim on both confirmed and unconfirmed pressure
            // exits (`shotanalysis.cpp:413-426`).
            if let Some(next) = input.phases.get(i + 1) {
                let reason = next.transition_reason.to_lowercase();
                if (reason == "pressure" || reason == "pressure_unconfirmed")
                    && (end - start) - GRIND_LIMITER_TAIL_SKIP_S >= MIN_POST_TRIM_RANGE_S
                {
                    end -= GRIND_LIMITER_TAIL_SKIP_S;
                }
            }
            if end > start {
                flow_mode_ranges.push(TimeRange { start, end });
            }
        }
    }

    // Arm 1: flow-vs-goal averaging (`shotanalysis.cpp:430-482`).
    if !flow_mode_ranges.is_empty() && !input.flow_goal.is_empty() {
        let in_flow_mode = |t: f64| flow_mode_ranges.iter().any(|r| t >= r.start && t <= r.end);
        let mut actual_sum = 0.0;
        let mut goal_sum = 0.0;
        let mut count: i32 = 0;
        for fp in &input.flow {
            let t = fp.t;
            if t < pour_start || t > pour_end {
                continue;
            }
            if !in_flow_mode(t) {
                continue;
            }
            let goal = find_value_at_time(&input.flow_goal, t);
            if goal < FLOW_GOAL_MIN_AVG {
                continue; // preinfusion sentinel / unset goal
            }
            // Flow-goal stationarity gate — excludes dynamic-bloom decay
            // frames; deliberately uses the clamping step lookup, not
            // `lookup_or_nan` (`shotanalysis.cpp:448-471`).
            let goal_past = find_value_at_time(&input.flow_goal, t - FLOW_GOAL_STATIONARY_HALF_S);
            let goal_fut = find_value_at_time(&input.flow_goal, t + FLOW_GOAL_STATIONARY_HALF_S);
            let denom = goal.max(FLOW_GOAL_MIN_AVG);
            if (goal_past - goal).abs() / denom > FLOW_GOAL_STATIONARY_REL
                || (goal_fut - goal).abs() / denom > FLOW_GOAL_STATIONARY_REL
            {
                continue;
            }
            actual_sum += fp.v;
            goal_sum += goal;
            count += 1;
        }
        result.sample_count = count;
        if count >= 5 {
            result.has_data = true;
            result.delta = actual_sum / f64::from(count) - goal_sum / f64::from(count);
        }
    }

    // Arm 2: choked-puck check on pressure-mode portions
    // (`shotanalysis.cpp:484-536`). Runs in addition to Arm 1, not as a
    // fallback: a healthy flow-mode preinfusion pins delta near zero while
    // the choke lives entirely in the pressure-mode tail.
    if input.pressure.is_empty() {
        return result;
    }
    let mut pressure_mode_ranges: Vec<TimeRange> = Vec::new();
    for (i, phase) in input.phases.iter().enumerate() {
        if phase.is_flow_mode {
            continue;
        }
        let start = phase.time_s;
        let end = input.phases.get(i + 1).map_or(pour_end, |n| n.time_s);
        if end > start {
            pressure_mode_ranges.push(TimeRange { start, end });
        }
    }
    // No pressure-mode markers (lever profiles labeled flow-mode
    // throughout) → the whole pour is a candidate; the per-sample pressure
    // gate still restricts to actually-pressurized portions.
    let in_pressure_mode = |t: f64| {
        pressure_mode_ranges.is_empty()
            || pressure_mode_ranges
                .iter()
                .any(|r| t >= r.start && t <= r.end)
    };

    let mut pressurized_duration = 0.0;
    let mut flow_sum = 0.0;
    let mut flow_samples: i32 = 0;
    let mut prev_x = 0.0;
    let mut prev_valid = false;
    for fp in &input.flow {
        if fp.t < pour_start || fp.t > pour_end || !in_pressure_mode(fp.t) {
            prev_valid = false;
            continue;
        }
        let press = find_value_at_time(&input.pressure, fp.t);
        if press < CHOKED_PRESSURE_MIN_BAR {
            prev_valid = false;
            continue;
        }
        if prev_valid {
            let dt = fp.t - prev_x;
            if dt > 0.0 && dt < 1.0 {
                // cap dt to ignore samples after a gap
                pressurized_duration += dt;
            }
        }
        flow_sum += fp.v;
        flow_samples += 1;
        prev_x = fp.t;
        prev_valid = true;
    }

    // Split-arm gates (`shotanalysis.cpp:544-605`): the flow arm needs
    // sustained pressurization; the yield arm only needs 5 pressurized
    // samples.
    let gate_passed = flow_samples >= 5 && pressurized_duration >= CHOKED_DURATION_MIN_S;
    if flow_samples >= 5 {
        let mean_flow = flow_sum / f64::from(flow_samples);
        let flow_choked = gate_passed && mean_flow < CHOKED_FLOW_MAX_ML_S;
        let yield_shortfall = input.target_weight_g > 0.0
            && input.final_weight_g > 0.0
            && input.final_weight_g / input.target_weight_g < CHOKED_YIELD_RATIO_MAX;
        if gate_passed || yield_shortfall {
            result.has_data = true;
        }
        if flow_choked || yield_shortfall {
            result.choked_puck = true;
            result.sample_count = flow_samples;
            // delta keeps its flow-vs-goal meaning; consumers short-circuit
            // on choked_puck before reading it.
        } else if gate_passed
            && !result.yield_overshoot
            && result.delta.abs() <= FLOW_DEVIATION_THRESHOLD
        {
            // The puck behaved through a sustained pressurized pour.
            // sample_count stays whatever Arm 1 saw (possibly 0).
            result.verified_clean = true;
        }
    }

    result
}

// ---------------------------------------------------------------------------
// Skip-first-frame detection.
// ---------------------------------------------------------------------------

/// True when the shot appears to have skipped profile frame 0
/// (`shotanalysis.cpp:657-729`, `detectSkipFirstFrame`). Two branches:
///
/// * **FW-bug**: frame 0 was never observed before a non-zero frame — the
///   hard 2 s window applies (parity with the de1app polling plugin);
///   `first_frame_configured_s` is ignored because frame 0 never ran.
/// * **Short-first-step**: frame 0 was observed but ended early — cutoff is
///   `min(2.0, 0.5 × configured)` when the configured duration is known,
///   else the hard 2 s window.
///
/// A confirmed sensor exit ("pressure"/"flow"/"weight", case-insensitive) on
/// the first non-zero frame suppresses detection — the frame exited as
/// designed. Unconfirmed variants, "time", and "" fall through to the
/// duration checks. `expected_frame_count >= 0 && < 2` suppresses (no second
/// frame to skip to); out-of-range frame numbers are treated as malformed.
fn detect_skip_first_frame(
    phases: &[PhaseMarker],
    expected_frame_count: i32,
    first_frame_configured_s: f64,
) -> bool {
    if phases.is_empty() {
        return false;
    }
    if (0..2).contains(&expected_frame_count) {
        return false;
    }

    // Ignore the synthetic "Start" marker (`shotanalysis.cpp:667-672`).
    let first_real_marker = usize::from(phases[0].label == "Start" && phases[0].frame_number == 0);

    let mut saw_frame_zero = false;
    for phase in &phases[first_real_marker..] {
        if phase.frame_number < 0 {
            continue;
        }
        if expected_frame_count >= 0 && phase.frame_number >= expected_frame_count {
            continue;
        }
        if phase.frame_number == 0 {
            saw_frame_zero = true;
            continue;
        }

        // Confirmed sensor exits suppress (`shotanalysis.cpp:701-704`).
        let reason = phase.transition_reason.to_lowercase();
        if reason == "pressure" || reason == "flow" || reason == "weight" {
            return false;
        }

        // FW-bug branch (`shotanalysis.cpp:706-711`).
        if !saw_frame_zero {
            return phase.time_s < SKIP_FIRST_FRAME_WINDOW_S;
        }

        // Short-first-step branch (`shotanalysis.cpp:713-725`).
        let mut cutoff = SKIP_FIRST_FRAME_WINDOW_S;
        if first_frame_configured_s > 0.0 {
            cutoff = SKIP_FIRST_FRAME_WINDOW_S.min(0.5 * first_frame_configured_s);
        }
        return phase.time_s < cutoff;
    }
    false
}

// ---------------------------------------------------------------------------
// The full pipeline.
// ---------------------------------------------------------------------------

fn push_line(lines: &mut Vec<QualityLine>, text: impl Into<String>, line_type: &str, kind: &str) {
    lines.push(QualityLine {
        text: text.into(),
        kind: kind.to_string(),
        line_type: line_type.to_string(),
    });
}

/// Fewest stored samples worth analyzing — below this the report is the
/// insufficient-data early return anyway, so callers can skip the bridge
/// round-trip and hide the quality card (both shells used 10).
const MIN_STORED_SAMPLES: usize = 10;
/// Median commanded-goal thresholds for the per-frame flow-vs-pressure
/// mode call: a frame is flow-driven when the machine commanded meaningful
/// flow and essentially no pressure.
const FLOW_MODE_FLOW_FLOOR: f64 = 0.2;
const FLOW_MODE_PRESSURE_CEIL: f64 = 0.2;

fn median(values: &mut [f64]) -> f64 {
    values.sort_by(f64::total_cmp);
    let mid = values.len() / 2;
    if values.len() % 2 == 1 {
        values[mid]
    } else {
        (values[mid - 1] + values[mid]) / 2.0
    }
}

fn beverage_str(bt: BeverageType) -> &'static str {
    match bt {
        BeverageType::Espresso => "espresso",
        BeverageType::Calibrate => "calibrate",
        BeverageType::Cleaning => "cleaning",
        BeverageType::Manual => "manual",
        BeverageType::Pourover => "pourover",
    }
}

/// Build the analysis input for a persisted [`StoredShot`] — the single
/// core-owned replacement for the two shell mappers that used to
/// re-implement this projection in TypeScript and Kotlin (and had already
/// diverged; review #39). Phase markers come from the REAL per-sample
/// `frame_number` (synthetic `Start` at t=0, one marker per transition, a
/// closing `End`), per-frame flow-mode from the median commanded goals,
/// labels from the recipe's preinfuse count (`Preinfusion` below it, the
/// first frame at/past it `Pour`, later `Frame N`; without a recipe the
/// first transition to a frame ≥ 1 anchors `Pour`).
///
/// Returns `None` when there is nothing worth analyzing
/// ([`MIN_STORED_SAMPLES`]) — callers hide the quality card.
#[must_use]
pub fn input_from_stored_shot(shot: &StoredShot) -> Option<ShotQualityInput> {
    let samples = &shot.record.samples;
    if samples.len() < MIN_STORED_SAMPLES {
        return None;
    }
    let recipe = shot.profile.as_ref().filter(|p| !p.steps.is_empty());
    let preinfuse_count: Option<i32> = recipe.map(|p| i32::from(p.preinfuse_step_count));

    let mut pressure = Vec::with_capacity(samples.len());
    let mut flow = Vec::with_capacity(samples.len());
    let mut weight = Vec::new();
    let mut pressure_goal = Vec::with_capacity(samples.len());
    let mut flow_goal = Vec::with_capacity(samples.len());
    // Per-frame commanded-goal accumulators + the observed transitions.
    let mut frame_flow_goals: std::collections::BTreeMap<u8, Vec<f64>> = Default::default();
    let mut frame_pressure_goals: std::collections::BTreeMap<u8, Vec<f64>> = Default::default();
    let mut transitions: Vec<(f64, u8)> = Vec::new();
    let mut current_frame: Option<u8> = None;

    for ts in samples {
        let t = ts.elapsed.as_secs_f64();
        pressure.push(SeriesPoint {
            t,
            v: f64::from(ts.sample.group_pressure),
        });
        flow.push(SeriesPoint {
            t,
            v: f64::from(ts.sample.group_flow),
        });
        if let Some(w) = ts.scale_weight {
            weight.push(SeriesPoint { t, v: f64::from(w) });
        }
        pressure_goal.push(SeriesPoint {
            t,
            v: f64::from(ts.sample.set_group_pressure),
        });
        flow_goal.push(SeriesPoint {
            t,
            v: f64::from(ts.sample.set_group_flow),
        });
        let frame = ts.sample.frame_number;
        frame_flow_goals
            .entry(frame)
            .or_default()
            .push(f64::from(ts.sample.set_group_flow));
        frame_pressure_goals
            .entry(frame)
            .or_default()
            .push(f64::from(ts.sample.set_group_pressure));
        match current_frame {
            None => current_frame = Some(frame),
            Some(f) if f != frame => {
                transitions.push((t, frame));
                current_frame = Some(frame);
            }
            _ => {}
        }
    }

    let is_flow_mode = |frame: u8,
                        flows: &std::collections::BTreeMap<u8, Vec<f64>>,
                        pressures: &std::collections::BTreeMap<u8, Vec<f64>>|
     -> bool {
        let (Some(f), Some(p)) = (flows.get(&frame), pressures.get(&frame)) else {
            return false;
        };
        if f.is_empty() || p.is_empty() {
            return false;
        }
        median(&mut f.clone()) > FLOW_MODE_FLOW_FLOOR
            && median(&mut p.clone()) < FLOW_MODE_PRESSURE_CEIL
    };

    // Labels: recipe-aware, with the no-recipe "first frame ≥ 1 is the
    // pour" heuristic. A zero preinfuse count means the pour begins at
    // extraction start — the Start marker anchors it, no Pour label.
    let mut pour_seen = preinfuse_count.is_some_and(|c| c <= 0);
    let mut label_for = |frame: u8| -> String {
        if let Some(count) = preinfuse_count {
            if i32::from(frame) < count {
                return "Preinfusion".to_string();
            }
            if !pour_seen {
                pour_seen = true;
                return "Pour".to_string();
            }
            return format!("Frame {frame}");
        }
        if !pour_seen && frame >= 1 {
            pour_seen = true;
            return "Pour".to_string();
        }
        format!("Frame {frame}")
    };

    let duration_s = shot.record.duration.as_secs_f64();
    let last_frame = samples.last().map_or(0, |ts| ts.sample.frame_number);
    let first_frame = samples.first().map_or(0, |ts| ts.sample.frame_number);
    let mut phases = Vec::with_capacity(transitions.len() + 2);
    phases.push(PhaseMarker {
        time_s: 0.0,
        label: "Start".to_string(),
        frame_number: 0,
        is_flow_mode: is_flow_mode(
            if frame_flow_goals.contains_key(&0) {
                0
            } else {
                first_frame
            },
            &frame_flow_goals,
            &frame_pressure_goals,
        ),
        transition_reason: String::new(),
    });
    for (t, frame) in &transitions {
        phases.push(PhaseMarker {
            time_s: *t,
            label: label_for(*frame),
            frame_number: i32::from(*frame),
            is_flow_mode: is_flow_mode(*frame, &frame_flow_goals, &frame_pressure_goals),
            transition_reason: String::new(),
        });
    }
    phases.push(PhaseMarker {
        time_s: duration_s,
        label: "End".to_string(),
        frame_number: i32::from(last_frame),
        is_flow_mode: is_flow_mode(last_frame, &frame_flow_goals, &frame_pressure_goals),
        transition_reason: String::new(),
    });

    let final_weight_g = weight
        .last()
        .map(|p| p.v)
        .or_else(|| shot.metadata.yield_out.map(f64::from))
        .unwrap_or(0.0);
    let target_weight_g = shot
        .yield_target
        .map(f64::from)
        .or_else(|| recipe.map(|p| f64::from(p.target_weight)))
        .unwrap_or(0.0);

    Some(ShotQualityInput {
        pressure,
        flow,
        weight,
        pressure_goal,
        flow_goal,
        phases,
        beverage_type: recipe
            .map_or("espresso", |p| beverage_str(p.beverage_type))
            .to_string(),
        duration_s,
        first_frame_configured_s: recipe
            .and_then(|p| p.steps.first())
            .map_or(-1.0, |st| f64::from(st.duration_seconds)),
        target_weight_g,
        final_weight_g,
        expected_frame_count: recipe
            .map_or(-1, |p| i32::try_from(p.steps.len()).unwrap_or(i32::MAX)),
        analysis_flags: Vec::new(),
        profile_kb_resolved: recipe.is_some(),
        frame_exits: recipe.map_or_else(Vec::new, |p| {
            p.steps
                .iter()
                .map(|st| FrameExitSpec {
                    metric: st.exit.map_or(String::new(), |e| {
                        match e.metric {
                            ExitMetric::Pressure => "pressure",
                            ExitMetric::Flow => "flow",
                        }
                        .to_string()
                    }),
                    exit_over: st.exit.is_some_and(|e| e.compare == Compare::Over),
                    threshold: st.exit.map_or(0.0, |e| f64::from(e.threshold)),
                    max_duration_s: f64::from(st.duration_seconds),
                })
                .collect()
        }),
    })
}

/// [`input_from_stored_shot`] + [`analyze_shot`] in one call — the FFI/wasm
/// surface both shells use for the History quality card.
#[must_use]
pub fn analyze_stored_shot(shot: &StoredShot) -> Option<ShotQualityReport> {
    input_from_stored_shot(shot).map(|input| analyze_shot(&input))
}

/// A sensor exit counts as confirmed when the recorded boundary value is
/// within this margin of the threshold (sampling at ~5 Hz means the exact
/// crossing sample may sit just shy of it).
const EXIT_CONFIRM_EPS: f64 = 0.1;
/// A frame that ran to within this of its configured max exited on time.
const TIME_EXIT_TOLERANCE_S: f64 = 0.25;

/// The recorded value at the frame boundary: the last sample at or before
/// `t` (the reading the firmware acted on).
fn series_value_at(series: &[SeriesPoint], t: f64) -> Option<f64> {
    let mut last = None;
    for p in series {
        if p.t <= t {
            last = Some(p.v);
        } else {
            break;
        }
    }
    last
}

/// Fill empty `transition_reason`s on reconstructed phase markers from the
/// profile's per-frame exit specs + the recorded series (see
/// [`FrameExitSpec`]). Only `""` reasons are touched — real recorded
/// reasons, if a source ever supplies them, win. The final "End" marker is
/// skipped: the shot's end is a stop (user / SAW / volume), not a frame
/// exit, and inferring a sensor reason there would feed the grind
/// limiter-tail trimming garbage. Unconfirmed exits follow Decenza
/// `maincontroller.cpp:2700-2717`: record "time" when the frame ran its
/// configured duration, never guess a sensor reason.
fn infer_transition_reasons(input: &mut ShotQualityInput) {
    if input.frame_exits.is_empty() {
        return;
    }
    for i in 1..input.phases.len() {
        if !input.phases[i].transition_reason.is_empty() || input.phases[i].label == "End" {
            continue;
        }
        let prev_frame = input.phases[i - 1].frame_number;
        let prev_time = input.phases[i - 1].time_s;
        if prev_frame < 0 {
            continue;
        }
        #[allow(clippy::cast_sign_loss)]
        let Some(spec) = input.frame_exits.get(prev_frame as usize) else {
            continue;
        };
        let t = input.phases[i].time_s;
        let boundary = match spec.metric.as_str() {
            "pressure" => series_value_at(&input.pressure, t),
            "flow" => series_value_at(&input.flow, t),
            _ => None,
        };
        let confirmed = boundary.is_some_and(|v| {
            if spec.exit_over {
                v >= spec.threshold - EXIT_CONFIRM_EPS
            } else {
                v <= spec.threshold + EXIT_CONFIRM_EPS
            }
        });
        input.phases[i].transition_reason = if confirmed {
            spec.metric.clone()
        } else if spec.max_duration_s > 0.0
            && t - prev_time >= spec.max_duration_s - TIME_EXIT_TOLERANCE_S
        {
            "time".to_string()
        } else {
            String::new()
        };
    }
}

/// Run the full shot-summary pipeline (`shotanalysis.cpp:731-1316`,
/// `analyzeShot`): pour-window derivation, the pour-truncated dominator,
/// channeling, flow trend, preinfusion drip, grind direction,
/// skip-first-frame, the badge projection, and the verdict cascade. The
/// conductance derivative is computed internally from `pressure` + `flow`
/// via [`conductance_series`] / [`conductance_derivative`].
///
/// Detector order and line order match Decenza exactly; the verdict is
/// always the last line (first match wins in the cascade), except on the
/// insufficient-data early return which emits a single observation line.
#[must_use]
pub fn analyze_shot(input: &ShotQualityInput) -> ShotQualityReport {
    let mut owned = input.clone();
    infer_transition_reasons(&mut owned);
    let input = &owned;
    let mut lines: Vec<QualityLine> = Vec::new();
    let mut d = DetectorResults::default();

    // Insufficient data — bail before running any detector
    // (`shotanalysis.cpp:753-765`).
    if input.pressure.len() < MIN_PRESSURE_SAMPLES {
        push_line(
            &mut lines,
            "Not enough data to analyze.",
            "observation",
            "insufficient_data",
        );
        return ShotQualityReport {
            lines,
            badges: QualityBadges::default(),
            verdict_category: "insufficientData".to_string(),
            detectors: d,
        };
    }

    // --- Find phase boundaries (`shotanalysis.cpp:767-777`) ---
    // Last-write-wins over the markers, case-insensitive.
    let mut preinf_end = 0.0;
    let mut pour_start = 0.0;
    let mut pour_end = input.duration_s;
    for phase in &input.phases {
        let label = phase.label.to_lowercase();
        if label.contains("infus") || label == "start" {
            preinf_end = phase.time_s;
        }
        if label.contains("pour") {
            pour_start = phase.time_s;
        }
        if label == "end" {
            pour_end = phase.time_s;
        }
    }
    if pour_start == 0.0 && preinf_end > 0.0 {
        pour_start = preinf_end;
    }
    d.pour_start_s = pour_start;
    d.pour_end_s = pour_end;

    // --- Pour-truncated detection — runs first, dominates the cascade
    // (`shotanalysis.cpp:779-797`) ---
    let pour_truncated =
        detect_pour_truncated(&input.pressure, pour_start, pour_end, &input.beverage_type);
    d.pour_truncated = pour_truncated;
    let mut peak_pressure_bar = 0.0;
    if pour_truncated {
        peak_pressure_bar = peak_pressure_in_window(&input.pressure, pour_start, pour_end);
        d.peak_pressure_bar = peak_pressure_bar;
    }

    // --- dC/dt analysis (channeling) (`shotanalysis.cpp:799-839`) ---
    let conductance = conductance_series(&input.pressure, &input.flow);
    let derivative = conductance_derivative(&conductance);
    let skip_channeling = pour_truncated
        || should_skip_channeling_check(&input.beverage_type, &input.flow, pour_start, pour_end)
        || input
            .analysis_flags
            .iter()
            .any(|f| f == "channeling_expected");

    if !skip_channeling && !derivative.is_empty() {
        let windows = build_channeling_windows(input, pour_start, pour_end);
        let (severity, spike_time) =
            detect_channeling_from_derivative(&derivative, pour_start, pour_end, &windows);
        d.channeling_checked = true;
        d.channeling_spike_time_s = spike_time;
        match severity {
            ChannelingSeverity::Sustained => {
                d.channeling_severity = "sustained".to_string();
                push_line(
                    &mut lines,
                    "Sustained channeling detected in dC/dt \u{2014} puck prep issue",
                    "warning",
                    "channeling_sustained",
                );
            }
            ChannelingSeverity::Transient => {
                d.channeling_severity = "transient".to_string();
                push_line(
                    &mut lines,
                    format!("Transient channel at {spike_time:.0}s (self-healed)"),
                    "caution",
                    "channeling_transient",
                );
            }
            ChannelingSeverity::None => {
                d.channeling_severity = "none".to_string();
                push_line(
                    &mut lines,
                    "Puck stable \u{2014} no channeling spikes in dC/dt",
                    "good",
                    "channeling_none",
                );
            }
        }
    }

    // --- Flow trend during extraction (`shotanalysis.cpp:841-878`) ---
    // Suppressed when pour-truncated fires or the profile declares the
    // trend intentional via the `flow_trend_ok` flag.
    let flow_trend_ok = input.analysis_flags.iter().any(|f| f == "flow_trend_ok");
    if !pour_truncated
        && !flow_trend_ok
        && pour_start > 0.0
        && pour_end > pour_start
        && input.flow.len() > 10
    {
        let mut start_sum = 0.0;
        let mut start_count: i32 = 0;
        let mut end_sum = 0.0;
        let mut end_count: i32 = 0;
        let pour_span = pour_end - pour_start;
        for fp in &input.flow {
            if fp.t < pour_start || fp.t > pour_end {
                continue;
            }
            let progress = (fp.t - pour_start) / pour_span;
            if progress < FLOW_TREND_START_FRAC {
                start_sum += fp.v;
                start_count += 1;
            }
            if progress > FLOW_TREND_END_FRAC {
                end_sum += fp.v;
                end_count += 1;
            }
        }
        if start_count > 0 && end_count > 0 {
            let delta = end_sum / f64::from(end_count) - start_sum / f64::from(start_count);
            d.flow_trend_checked = true;
            d.flow_trend_delta_ml_s = delta;
            if delta > FLOW_TREND_DELTA {
                d.flow_trend = "rising".to_string();
                push_line(
                    &mut lines,
                    format!("Flow rose {delta:.1} mL/s during extraction (puck erosion)"),
                    "caution",
                    "flow_rising",
                );
            } else if delta < -FLOW_TREND_DELTA {
                d.flow_trend = "falling".to_string();
                push_line(
                    &mut lines,
                    format!(
                        "Flow dropped {:.1} mL/s (fines migration or clogging)",
                        delta.abs()
                    ),
                    "caution",
                    "flow_falling",
                );
            } else {
                d.flow_trend = "stable".to_string();
            }
        }
    }

    // --- Preinfusion drip (`shotanalysis.cpp:880-900`) ---
    if preinf_end > 0.0 && !input.weight.is_empty() {
        let mut preinf_weight = 0.0;
        for wp in &input.weight {
            if wp.t <= preinf_end {
                preinf_weight = wp.v;
            } else {
                break;
            }
        }
        let first_phase_time = input.phases.first().map_or(0.0, |p| p.time_s);
        let preinf_duration = preinf_end - first_phase_time;
        if preinf_weight > PREINFUSION_DRIP_MIN_G && preinf_duration > PREINFUSION_DRIP_MIN_S {
            d.preinfusion_observed = true;
            d.preinfusion_drip_weight_g = preinf_weight;
            d.preinfusion_drip_duration_s = preinf_duration;
            push_line(
                &mut lines,
                format!("Preinfusion: {preinf_weight:.1}g in {preinf_duration:.1}s"),
                "observation",
                "preinfusion_drip",
            );
        }
    }

    // --- Flow vs goal (grind direction) (`shotanalysis.cpp:902-1048`) ---
    // Suppressed entirely when pour-truncated fires: flow tracked the
    // preinfusion goal on a puck that held nothing back (delta ~0) and the
    // pressure-mode arms can never satisfy their gate — the "no signal"
    // would false-positive a Clean verdict on a puck failure.
    let grind = if pour_truncated {
        GrindCheck::default()
    } else {
        analyze_flow_vs_goal(input, pour_start, pour_end)
    };
    d.grind_checked = !pour_truncated && !grind.skipped;
    d.grind_has_data = grind.has_data;
    d.grind_choked_puck = grind.choked_puck;
    d.grind_yield_overshoot = grind.yield_overshoot;
    d.grind_verified_clean = grind.verified_clean;
    d.grind_flow_delta_ml_s = grind.delta;
    d.grind_sample_count = grind.sample_count;
    // Coverage: data availability, not health outcome
    // (`shotanalysis.cpp:942-965`). Empty when the pour-truncated cascade
    // suppressed the block or the pour window is degenerate.
    if !pour_truncated && pour_end > pour_start {
        d.grind_coverage = if grind.skipped {
            "skipped"
        } else if grind.verified_clean {
            "verified"
        } else if !grind.has_data {
            "notAnalyzable"
        } else {
            // An issue fired — the detector ran and produced data.
            "verified"
        }
        .to_string();
    }
    if grind.has_data {
        if grind.yield_overshoot {
            // Gusher pre-empts chokedPuck and the directional cautions —
            // order matches the verdict cascade (`shotanalysis.cpp:967-984`).
            d.grind_direction = "yieldOvershoot".to_string();
            let over_g = input.final_weight_g - input.target_weight_g;
            push_line(
                &mut lines,
                format!(
                    "Yield ran {over_g:.1} g over target \u{2014} puck offered too little \
                     resistance, grind way too coarse"
                ),
                "warning",
                "grind_yield_overshoot",
            );
        } else if grind.choked_puck {
            d.grind_direction = "chokedPuck".to_string();
            push_line(
                &mut lines,
                "Pour produced near-zero flow while pressure held \u{2014} puck choked, \
                 grind way too fine",
                "warning",
                "grind_choked_puck",
            );
        } else if grind.delta < -FLOW_DEVIATION_THRESHOLD {
            d.grind_direction = "tooFine".to_string();
            push_line(
                &mut lines,
                format!(
                    "Flow averaged {:.1} ml/s below target \u{2014} grind may be too fine",
                    grind.delta.abs()
                ),
                "caution",
                "grind_too_fine",
            );
        } else if grind.delta > FLOW_DEVIATION_THRESHOLD {
            d.grind_direction = "tooCoarse".to_string();
            push_line(
                &mut lines,
                format!(
                    "Flow averaged {:.1} ml/s above target \u{2014} grind may be too coarse",
                    grind.delta
                ),
                "caution",
                "grind_too_coarse",
            );
        } else {
            d.grind_direction = "onTarget".to_string();
            if grind.verified_clean {
                // The text names which signal verified the pour: Arm 1's
                // averaging when it produced samples, else Arm 2's
                // sustained-pressure gate alone (`shotanalysis.cpp:1011-1032`).
                let text = if grind.sample_count > 0 {
                    "Grind tracked goal during pour"
                } else {
                    "Puck sustained healthy pressure during pour"
                };
                push_line(&mut lines, text, "good", "grind_clean");
            }
        }
    } else if d.grind_coverage == "notAnalyzable" {
        // Espresso shot, non-degenerate window, but neither arm produced
        // data — say so instead of implying the puck held well
        // (`shotanalysis.cpp:1035-1048`).
        push_line(
            &mut lines,
            "Could not analyze grind on this profile shape \u{2014} check flow trend, \
             channeling, and taste instead",
            "observation",
            "grind_not_analyzable",
        );
    }

    // --- Pour truncated warning line (`shotanalysis.cpp:1050-1068`) ---
    if pour_truncated {
        push_line(
            &mut lines,
            format!(
                "Pour never pressurized (peak {peak_pressure_bar:.1} bar) \u{2014} puck \
                 offered no resistance. Likely causes: grind way too coarse, distribution \
                 failure, no/loose puck, severe underdose, or profile without a pressure cap."
            ),
            "warning",
            "pour_truncated",
        );
    }

    // --- Skip-first-frame (`shotanalysis.cpp:1070-1087`) — runs even when
    // pour-truncated fires ---
    let skip_first_frame = detect_skip_first_frame(
        &input.phases,
        input.expected_frame_count,
        input.first_frame_configured_s,
    );
    d.skip_first_frame = skip_first_frame;
    if skip_first_frame {
        push_line(
            &mut lines,
            "First profile step skipped \u{2014} likely a DE1 firmware bug (power-cycle \
             machine to fix) or first step too short (check profile settings)",
            "warning",
            "skip_first_frame",
        );
    }

    // --- Badge projection ---
    let badges = QualityBadges {
        channeling: d.channeling_severity == "sustained",
        grind_issue: !grind.skipped
            && grind.has_data
            && (grind.choked_puck
                || grind.yield_overshoot
                || grind.delta.abs() > FLOW_DEVIATION_THRESHOLD),
        pour_truncated,
        skip_first_frame,
    };

    // --- Verdict cascade — first match wins (`shotanalysis.cpp:1202-1313`,
    // minus the omitted expert-band branch) ---
    let has_warning = lines.iter().any(|l| l.line_type == "warning");
    let has_caution = lines.iter().any(|l| l.line_type == "caution");
    let grind_fine = grind.has_data && grind.delta < -FLOW_DEVIATION_THRESHOLD;
    let grind_coarse = grind.has_data && grind.delta > FLOW_DEVIATION_THRESHOLD;

    let (category, text): (&str, &str) = if pour_truncated {
        (
            "puckTruncated",
            "Verdict: Don't tune off this shot \u{2014} peak pressure never built, so the \
             other quality signals (channeling, grind direction) are unreliable. Check prep \
             (dose, distribution, basket, grind) and pull another.",
        )
    } else if skip_first_frame {
        (
            "skipFirstFrame",
            "Verdict: First profile step was skipped \u{2014} power-cycle the machine to \
             fix a firmware bug, or review the profile's first step settings.",
        )
    } else if grind.yield_overshoot {
        (
            "yieldOvershoot",
            "Verdict: Pour gushed past target \u{2014} grind way too coarse. Grind much finer.",
        )
    } else if grind.choked_puck {
        (
            "chokedPuck",
            "Verdict: Puck choked \u{2014} grind way too fine. Coarsen significantly.",
        )
    } else if has_warning {
        if grind_fine {
            (
                "puckIntegrityGrindFine",
                "Verdict: Puck integrity issue \u{2014} improve distribution. Grind is \
                 running fine \u{2014} try coarser.",
            )
        } else if grind_coarse {
            (
                "puckIntegrityGrindCoarse",
                "Verdict: Puck integrity issue \u{2014} improve distribution. Grind is \
                 running coarse \u{2014} try finer.",
            )
        } else {
            (
                "puckIntegrity",
                "Verdict: Puck integrity issue \u{2014} improve distribution.",
            )
        }
    } else if has_caution {
        if grind_fine {
            (
                "minorIssuesGrindFine",
                "Verdict: Grind appears too fine \u{2014} try coarser.",
            )
        } else if grind_coarse {
            (
                "minorIssuesGrindCoarse",
                "Verdict: Grind appears too coarse \u{2014} try finer.",
            )
        } else {
            (
                "minorIssues",
                "Verdict: Decent shot with minor issues to watch.",
            )
        }
    } else if d.grind_coverage == "notAnalyzable" {
        (
            "cleanGrindNotAnalyzable",
            "Verdict: Clean shot, but grind could not be evaluated for this profile shape.",
        )
    } else {
        ("clean", "Verdict: Clean shot. Puck held well.")
    };
    push_line(&mut lines, text, "verdict", category);

    ShotQualityReport {
        lines,
        badges,
        verdict_category: category.to_string(),
        detectors: d,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    /// `count` samples starting at t=0 with exact `step` spacing (use
    /// power-of-two steps like 0.25 so accumulation stays exact).
    fn series(step: f64, count: usize, f: impl Fn(f64) -> f64) -> Vec<SeriesPoint> {
        let mut out = Vec::with_capacity(count);
        let mut t = 0.0;
        for _ in 0..count {
            out.push(SeriesPoint { t, v: f(t) });
            t += step;
        }
        out
    }

    /// Triangle wave: `lo` at phase 0, `hi` at half period, back to `lo`.
    fn triangle(t: f64, period: f64, lo: f64, hi: f64) -> f64 {
        let phase = t.rem_euclid(period);
        let half = period / 2.0;
        if phase < half {
            lo + (hi - lo) * phase / half
        } else {
            hi - (hi - lo) * (phase - half) / half
        }
    }

    fn marker(
        time_s: f64,
        label: &str,
        frame_number: i32,
        is_flow_mode: bool,
        transition_reason: &str,
    ) -> PhaseMarker {
        PhaseMarker {
            time_s,
            label: label.to_string(),
            frame_number,
            is_flow_mode,
            transition_reason: transition_reason.to_string(),
        }
    }

    fn base_input(duration_s: f64) -> ShotQualityInput {
        ShotQualityInput {
            pressure: Vec::new(),
            flow: Vec::new(),
            weight: Vec::new(),
            pressure_goal: Vec::new(),
            flow_goal: Vec::new(),
            phases: Vec::new(),
            beverage_type: "espresso".to_string(),
            duration_s,
            first_frame_configured_s: -1.0,
            target_weight_g: 0.0,
            final_weight_g: 0.0,
            expected_frame_count: -1,
            analysis_flags: Vec::new(),
            profile_kb_resolved: true,
            frame_exits: Vec::new(),
        }
    }

    fn approx(a: f64, b: f64, eps: f64) -> bool {
        (a - b).abs() <= eps
    }

    fn has_line(report: &ShotQualityReport, kind: &str, line_type: &str) -> bool {
        report
            .lines
            .iter()
            .any(|l| l.kind == kind && l.line_type == line_type)
    }

    fn line_text<'a>(report: &'a ShotQualityReport, kind: &str) -> &'a str {
        &report
            .lines
            .iter()
            .find(|l| l.kind == kind)
            .expect("line kind present")
            .text
    }

    // -----------------------------------------------------------------
    // Conductance & dC/dt math.
    // -----------------------------------------------------------------

    #[test]
    fn conductance_sample_dead_zone_and_clamp() {
        // F² / P (conductance.h:18-23).
        assert!(approx(conductance_sample(9.0, 3.0), 1.0, 1e-12));
        // Dead zone is inclusive: F or P at exactly 0.05 → 0.
        assert_eq!(conductance_sample(9.0, 0.05), 0.0);
        assert_eq!(conductance_sample(0.05, 3.0), 0.0);
        // Clamp at 19: F=20, P=1 → 400 → 19.
        assert!(approx(conductance_sample(1.0, 20.0), 19.0, 1e-12));
    }

    #[test]
    fn conductance_series_pairs_by_index() {
        // Output length = min(len(P), len(F)); timestamps come from pressure.
        let pressure = vec![
            SeriesPoint { t: 0.0, v: 9.0 },
            SeriesPoint { t: 1.0, v: 9.0 },
            SeriesPoint { t: 2.0, v: 9.0 },
        ];
        let flow = vec![
            SeriesPoint { t: 0.5, v: 3.0 },
            SeriesPoint { t: 1.5, v: 6.0 },
        ];
        let c = conductance_series(&pressure, &flow);
        assert_eq!(c.len(), 2);
        assert!(approx(c[0].t, 0.0, 1e-12));
        assert!(approx(c[0].v, 1.0, 1e-12));
        assert!(approx(c[1].t, 1.0, 1e-12));
        assert!(approx(c[1].v, 4.0, 1e-12));
    }

    #[test]
    fn conductance_derivative_needs_three_points() {
        let two = series(1.0, 2, |t| t);
        assert!(conductance_derivative(&two).is_empty());
        assert!(conductance_derivative(&[]).is_empty());
    }

    #[test]
    fn conductance_derivative_linear_slope() {
        // C = 0.1·t → dC/dt×10 = 1.0 everywhere; smoothing a constant
        // (with edge renormalization) preserves it exactly.
        let c = series(1.0, 20, |t| 0.1 * t);
        let d = conductance_derivative(&c);
        assert_eq!(d.len(), 20);
        for pt in &d {
            assert!(approx(pt.v, 1.0, 1e-9), "expected 1.0 got {}", pt.v);
        }
        // Timestamps carried through.
        assert!(approx(d[3].t, 3.0, 1e-12));
    }

    #[test]
    fn conductance_derivative_clamps() {
        // Slope 3/s → raw 30 → clamped to 19 (conductance.cpp:65-67).
        let steep = series(1.0, 12, |t| 3.0 * t);
        for pt in conductance_derivative(&steep) {
            assert!(approx(pt.v, DCDT_CLAMP_MAX, 1e-9));
        }
        // Slope −1/s → raw −10 → clamped to −5.
        let falling = series(1.0, 12, |t| 12.0 - t);
        for pt in conductance_derivative(&falling) {
            assert!(approx(pt.v, DCDT_CLAMP_MIN, 1e-9));
        }
    }

    #[test]
    fn conductance_derivative_gaussian_spot_values() {
        // Unit impulse at index 4 of 9 (1 s spacing): centered difference
        // gives raw = [0,0,0,5,0,−5,0,0,0]. Hand-computed smoothed values:
        //  - index 4 (full kernel): 5·G[3] − 5·G[5] = 0 by symmetry.
        //  - index 3 (edge-renormalized, k=−4 dropped):
        //      (5·G[4] − 5·G[6]) / (1 − G[0]) = 0.231225 / 0.951704…
        //  - index 5 mirrors index 3 with opposite sign.
        let mut c = series(1.0, 9, |_| 0.0);
        c[4].v = 1.0;
        let d = conductance_derivative(&c);
        assert_eq!(d.len(), 9);
        assert!(approx(d[4].v, 0.0, 1e-9), "center: {}", d[4].v);

        let kernel_sum: f64 = GAUSSIAN_KERNEL.iter().sum();
        let expected3 = (5.0 * GAUSSIAN_KERNEL[4] - 5.0 * GAUSSIAN_KERNEL[6])
            / (kernel_sum - GAUSSIAN_KERNEL[0]);
        assert!(approx(d[3].v, expected3, 1e-9), "index 3: {}", d[3].v);
        assert!(approx(expected3, 0.242958, 1e-5));
        assert!(approx(d[5].v, -expected3, 1e-9), "index 5: {}", d[5].v);
    }

    // -----------------------------------------------------------------
    // Skip-first-frame — both branches (shotanalysis.cpp:657-729).
    // -----------------------------------------------------------------

    #[test]
    fn skip_first_frame_fw_bug_branch() {
        // Frame 0 never observed before frame 1: hard 2 s window.
        let early = vec![
            marker(0.0, "Start", 0, false, ""),
            marker(1.5, "Frame 1", 1, false, "time"),
        ];
        assert!(detect_skip_first_frame(&early, -1, -1.0));
        // The configured duration is ignored on this branch.
        assert!(detect_skip_first_frame(&early, -1, 10.0));

        let late = vec![
            marker(0.0, "Start", 0, false, ""),
            marker(2.5, "Frame 1", 1, false, "time"),
        ];
        assert!(!detect_skip_first_frame(&late, -1, -1.0));
    }

    #[test]
    fn skip_first_frame_short_first_step_branch() {
        // Frame 0 observed, frame 1 lands at 1.2 s.
        let phases = vec![
            marker(0.0, "Start", 0, false, ""),
            marker(0.2, "Frame 0", 0, false, ""),
            marker(1.2, "Frame 1", 1, false, "time"),
        ];
        // Unknown configured duration → hard 2 s window fires.
        assert!(detect_skip_first_frame(&phases, -1, -1.0));
        // Configured 2 s → cutoff min(2.0, 1.0) = 1.0 → 1.2 does not fire.
        assert!(!detect_skip_first_frame(&phases, -1, 2.0));
        // Configured 10 s → cutoff min(2.0, 5.0) = 2.0 → fires.
        assert!(detect_skip_first_frame(&phases, -1, 10.0));
    }

    #[test]
    fn skip_first_frame_suppressions() {
        // A confirmed sensor exit on the first non-zero frame suppresses,
        // case-insensitively.
        for reason in ["pressure", "flow", "weight", "Weight"] {
            let phases = vec![
                marker(0.0, "Start", 0, false, ""),
                marker(0.5, "Frame 1", 1, false, reason),
            ];
            assert!(!detect_skip_first_frame(&phases, -1, -1.0), "{reason}");
        }
        // expected_frame_count < 2 suppresses; out-of-range frames skipped.
        let phases = vec![
            marker(0.0, "Start", 0, false, ""),
            marker(1.0, "Frame 1", 1, false, "time"),
        ];
        assert!(!detect_skip_first_frame(&phases, 1, -1.0));
        let with_bogus = vec![
            marker(0.0, "Start", 0, false, ""),
            marker(0.5, "Frame 5", 5, false, "time"),
            marker(1.5, "Frame 1", 1, false, "time"),
        ];
        assert!(detect_skip_first_frame(&with_bogus, 3, -1.0));
        // Empty phases → false.
        assert!(!detect_skip_first_frame(&[], -1, -1.0));
    }

    // -----------------------------------------------------------------
    // input_from_stored_shot (review #39 — ported from the web mapper's
    // vitest cases before the shell mappers were deleted).
    // -----------------------------------------------------------------

    fn stored_sample(
        elapsed_ms: u64,
        frame: u8,
        set_p: f32,
        set_f: f32,
        w: Option<f32>,
    ) -> crate::TimedSample {
        crate::TimedSample {
            elapsed: Duration::from_millis(elapsed_ms),
            sample: de1_protocol::ShotSample {
                sample_time: 0,
                group_pressure: 8.0,
                group_flow: 2.0,
                head_temp: 92.0,
                mix_temp: 90.0,
                set_mix_temp: 90.0,
                set_head_temp: 92.0,
                set_group_pressure: set_p,
                set_group_flow: set_f,
                frame_number: frame,
                steam_temp: 150.0,
            },
            scale_weight: w,
            scale_flow_weight: None,
            dispensed_volume: None,
            resistance: None,
            resistance_weight: None,
        }
    }

    fn stored_shot_with(samples: Vec<crate::TimedSample>) -> StoredShot {
        let record = crate::ShotRecord {
            duration: samples.last().map_or(Duration::ZERO, |s| s.elapsed),
            samples,
        };
        StoredShot::new(0, record)
    }

    #[test]
    fn stored_shot_input_needs_ten_samples() {
        let samples: Vec<_> = (0..9)
            .map(|i| stored_sample(i * 200, 0, 8.0, 0.0, None))
            .collect();
        assert!(input_from_stored_shot(&stored_shot_with(samples)).is_none());
    }

    #[test]
    fn stored_shot_input_reconstructs_start_pour_end_markers() {
        // Frames 0,0,0,1,1... → synthetic Start@0/frame0, one transition
        // marker (Pour under the no-recipe heuristic), End@duration.
        let mut samples = Vec::new();
        for i in 0..6u64 {
            #[allow(clippy::cast_precision_loss)]
            samples.push(stored_sample(i * 200, 0, 8.0, 0.0, Some(i as f32)));
        }
        for i in 6..14u64 {
            #[allow(clippy::cast_precision_loss)]
            samples.push(stored_sample(i * 200, 1, 8.0, 0.0, Some(i as f32)));
        }
        let input = input_from_stored_shot(&stored_shot_with(samples)).expect("input");
        assert_eq!(input.phases.len(), 3);
        assert_eq!(input.phases[0].label, "Start");
        assert_eq!(input.phases[0].frame_number, 0);
        assert_eq!(input.phases[1].label, "Pour");
        assert_eq!(input.phases[1].frame_number, 1);
        assert!(approx(input.phases[1].time_s, 1.2, 1e-9));
        assert_eq!(input.phases[2].label, "End");
        assert_eq!(input.phases[2].frame_number, 1);
        // Weight series carried through; final = last scale weight.
        assert!(approx(input.final_weight_g, 13.0, 1e-6));
        // Pressure-driven frames: no flow mode.
        assert!(!input.phases[1].is_flow_mode);
    }

    #[test]
    fn stored_shot_input_flow_mode_from_median_goals() {
        // Frame 1 commands flow (2.5) with ~zero pressure → flow mode.
        let mut samples = Vec::new();
        for i in 0..6u64 {
            samples.push(stored_sample(i * 200, 0, 8.0, 0.0, None));
        }
        for i in 6..14u64 {
            samples.push(stored_sample(i * 200, 1, 0.0, 2.5, None));
        }
        let input = input_from_stored_shot(&stored_shot_with(samples)).expect("input");
        assert!(input.phases[1].is_flow_mode);
        // No scale + no metadata yield → 0 disables the yield arms.
        assert!(approx(input.final_weight_g, 0.0, 1e-9));
    }

    // -----------------------------------------------------------------
    // Transition-reason inference (Decenza #1421).
    // -----------------------------------------------------------------

    fn exit_spec(metric: &str, over: bool, threshold: f64, max_s: f64) -> FrameExitSpec {
        FrameExitSpec {
            metric: metric.to_string(),
            exit_over: over,
            threshold,
            max_duration_s: max_s,
        }
    }

    #[test]
    fn infers_a_confirmed_sensor_exit_and_a_time_exit() {
        // Frame 0: pressure-over-3.0 exit, satisfied at the 1.2 s boundary.
        // Frame 1: flow-under exit NOT satisfied, but the frame ran its
        // configured 10 s → "time" (never guess a sensor reason —
        // Decenza maincontroller.cpp:2700-2717).
        let mut input = base_input(30.0);
        input.pressure = series(0.25, 120, |t| if t <= 1.2 { 3.1 } else { 8.0 });
        input.flow = series(0.25, 120, |_| 2.0);
        input.phases = vec![
            marker(0.0, "Start", 0, false, ""),
            marker(1.2, "Pour", 1, false, ""),
            marker(11.2, "Frame 2", 2, false, ""),
            marker(30.0, "End", 2, false, ""),
        ];
        input.frame_exits = vec![
            exit_spec("pressure", true, 3.0, 8.0),
            exit_spec("flow", false, 0.5, 10.0),
            exit_spec("", false, 0.0, 20.0),
        ];
        infer_transition_reasons(&mut input);
        assert_eq!(input.phases[1].transition_reason, "pressure");
        assert_eq!(input.phases[2].transition_reason, "time");
        // The End marker is a stop, not a frame exit — never inferred.
        assert_eq!(input.phases[3].transition_reason, "");
    }

    #[test]
    fn leaves_unconfirmed_short_exits_and_recorded_reasons_alone() {
        let mut input = base_input(30.0);
        // Pressure never reaches the 3.0 exit target.
        input.pressure = series(0.25, 120, |_| 1.0);
        input.phases = vec![
            marker(0.0, "Start", 0, false, ""),
            // A genuinely skipped first frame: 1.2 s of an 8 s fill with
            // its exit unsatisfied — the reason stays "" and the
            // skip-first-frame duration check keeps working.
            marker(1.2, "Pour", 1, false, ""),
            // A recorded reason is never overwritten.
            marker(20.0, "Frame 2", 2, false, "weight"),
        ];
        input.frame_exits = vec![
            exit_spec("pressure", true, 3.0, 8.0),
            exit_spec("pressure", true, 9.0, 25.0),
        ];
        infer_transition_reasons(&mut input);
        assert_eq!(input.phases[1].transition_reason, "");
        assert_eq!(input.phases[2].transition_reason, "weight");
    }

    #[test]
    fn confirmed_first_frame_exit_kills_the_false_skip_badge_end_to_end() {
        // The Decenza #1421 symptom: an 8 s fill frame designed to exit on
        // pressure-over-3.0 does so at 1.2 s. Without frame_exits the
        // duration heuristic flags "First step skipped"; with them the
        // inferred "pressure" reason suppresses it.
        fn shot(with_specs: bool) -> ShotQualityReport {
            let mut input = base_input(30.0);
            input.pressure = series(0.25, 120, |t| if t <= 1.2 { 3.1 } else { 8.5 });
            input.flow = series(0.25, 120, |_| 1.8);
            input.expected_frame_count = 2;
            input.first_frame_configured_s = 8.0;
            input.phases = vec![
                marker(0.0, "Start", 0, false, ""),
                marker(1.2, "Pour", 1, false, ""),
                marker(30.0, "End", 1, false, ""),
            ];
            if with_specs {
                input.frame_exits = vec![
                    exit_spec("pressure", true, 3.0, 8.0),
                    exit_spec("", false, 0.0, 22.0),
                ];
            }
            analyze_shot(&input)
        }
        assert!(
            shot(false).badges.skip_first_frame,
            "control: badge fires without specs"
        );
        assert!(
            !shot(true).badges.skip_first_frame,
            "specs must suppress the false badge"
        );
    }

    // -----------------------------------------------------------------
    // analyze_shot — end to end.
    // -----------------------------------------------------------------

    #[test]
    fn insufficient_data_early_return() {
        let mut input = base_input(30.0);
        input.pressure = series(0.25, 9, |_| 9.0); // one short of the minimum
        input.flow = series(0.25, 9, |_| 2.0);
        let report = analyze_shot(&input);
        assert_eq!(report.lines.len(), 1);
        assert_eq!(report.lines[0].text, "Not enough data to analyze.");
        assert_eq!(report.lines[0].kind, "insufficient_data");
        assert_eq!(report.lines[0].line_type, "observation");
        assert_eq!(report.verdict_category, "insufficientData");
        assert_eq!(report.badges, QualityBadges::default());
        assert!(!report.detectors.channeling_checked);
        assert!(approx(report.detectors.pour_end_s, 0.0, 1e-12));
    }

    #[test]
    fn pour_truncated_dominates_cascade() {
        // Peak 1.5 bar < 2.5: channeling / flow-trend / grind all
        // suppressed; skip-first-frame still runs but loses the verdict.
        let mut input = base_input(30.0);
        input.pressure = series(0.25, 121, |_| 1.5);
        input.flow = series(0.25, 121, |_| 4.0);
        input.phases = vec![
            marker(0.0, "Start", 0, false, ""),
            marker(1.0, "Frame 1", 1, false, "time"),
        ];
        let report = analyze_shot(&input);
        assert!(report.detectors.pour_truncated);
        assert!(approx(report.detectors.peak_pressure_bar, 1.5, 1e-9));
        assert!(!report.detectors.channeling_checked);
        assert!(!report.detectors.flow_trend_checked);
        assert!(!report.detectors.grind_checked);
        assert_eq!(report.detectors.grind_coverage, "");
        assert!(report.detectors.skip_first_frame);
        assert_eq!(
            report.badges,
            QualityBadges {
                channeling: false,
                grind_issue: false,
                pour_truncated: true,
                skip_first_frame: true,
            }
        );
        assert_eq!(
            line_text(&report, "pour_truncated"),
            "Pour never pressurized (peak 1.5 bar) \u{2014} puck offered no resistance. \
             Likely causes: grind way too coarse, distribution failure, no/loose puck, \
             severe underdose, or profile without a pressure cap."
        );
        assert!(has_line(&report, "skip_first_frame", "warning"));
        // Verdict precedence: pourTruncated beats skipFirstFrame.
        assert_eq!(report.verdict_category, "puckTruncated");
        let verdict = report.lines.last().unwrap();
        assert_eq!(verdict.line_type, "verdict");
        assert_eq!(
            verdict.text,
            "Verdict: Don't tune off this shot \u{2014} peak pressure never built, so the \
             other quality signals (channeling, grind direction) are unreliable. Check prep \
             (dose, distribution, basket, grind) and pull another."
        );
    }

    /// Conductance sweeps between 0.05 and 1.85 (4 s legs → |dC/dt|×10 ≈
    /// 4.5, elevated but sub-transient) at 9 bar; flow = √(C·9) keeps the
    /// pour mean under the turbo threshold.
    fn sustained_channeling_input() -> ShotQualityInput {
        let mut input = base_input(30.0);
        input.pressure = series(0.25, 121, |_| 9.0);
        input.flow = series(0.25, 121, |t| (triangle(t, 8.0, 0.05, 1.85) * 9.0).sqrt());
        input
    }

    #[test]
    fn channeling_sustained_via_whole_pour_fallback() {
        // No phases → buildChannelingWindows emits the whole-pour window.
        let report = analyze_shot(&sustained_channeling_input());
        assert!(report.detectors.channeling_checked);
        assert_eq!(report.detectors.channeling_severity, "sustained");
        assert!(report.badges.channeling);
        assert!(!report.badges.grind_issue);
        assert_eq!(
            line_text(&report, "channeling_sustained"),
            "Sustained channeling detected in dC/dt \u{2014} puck prep issue"
        );
        // Arm 2 verified the pressurized pour (delta 0) → hasWarning with
        // no grind direction → puckIntegrity.
        assert!(report.detectors.grind_verified_clean);
        assert!(has_line(&report, "grind_clean", "good"));
        assert_eq!(
            line_text(&report, "grind_clean"),
            "Puck sustained healthy pressure during pour"
        );
        assert_eq!(report.verdict_category, "puckIntegrity");
    }

    #[test]
    fn channeling_empty_windows_pass_silently() {
        // Same violent dC/dt series, but phases exist and the pressure-mode
        // goal series is absent → every grid sample fails the goal lookup →
        // zero qualifying windows → severity none, NOT a whole-pour
        // fallback.
        let mut input = sustained_channeling_input();
        input.phases = vec![marker(0.0, "Start", 0, false, "")];
        let report = analyze_shot(&input);
        assert!(report.detectors.channeling_checked);
        assert_eq!(report.detectors.channeling_severity, "none");
        assert!(!report.badges.channeling);
        assert_eq!(
            line_text(&report, "channeling_none"),
            "Puck stable \u{2014} no channeling spikes in dC/dt"
        );
        assert_eq!(report.verdict_category, "clean");
    }

    #[test]
    fn channeling_transient_self_healed() {
        // Flat conductance 0.3, one sharp step to 1.3 at t=15 followed by a
        // slow (sub-elevated) decay: ~7 elevated samples (≤ 10) with a
        // smoothed peak above 5 → transient.
        let mut input = base_input(30.0);
        input.pressure = series(0.25, 121, |_| 9.0);
        input.flow = series(0.25, 121, |t| {
            let c = if t < 15.0 {
                0.3
            } else if t < 25.0 {
                1.3 - 0.1 * (t - 15.0)
            } else {
                0.3
            };
            (c * 9.0).sqrt()
        });
        let report = analyze_shot(&input);
        assert_eq!(report.detectors.channeling_severity, "transient");
        assert!(!report.badges.channeling, "transient must not badge");
        assert!(approx(report.detectors.channeling_spike_time_s, 15.0, 0.6));
        assert_eq!(
            line_text(&report, "channeling_transient"),
            format!(
                "Transient channel at {:.0}s (self-healed)",
                report.detectors.channeling_spike_time_s
            )
        );
        // Caution only → minorIssues (grind delta is 0 via Arm 2 verify).
        assert_eq!(report.verdict_category, "minorIssues");
    }

    #[test]
    fn channeling_skipped_for_turbo_and_beverage_and_flag() {
        // Mean pour flow > 3.0 → turbo skip (no channeling line at all).
        let mut turbo = base_input(30.0);
        turbo.pressure = series(0.25, 121, |_| 9.0);
        turbo.flow = series(0.25, 121, |_| 4.0);
        let report = analyze_shot(&turbo);
        assert!(!report.detectors.channeling_checked);
        assert_eq!(report.detectors.channeling_severity, "");

        // Beverage skip-set is case-insensitive and also disables
        // pour-truncated + grind (coverage "skipped").
        let mut filter = base_input(30.0);
        filter.beverage_type = "Filter".to_string();
        filter.pressure = series(0.25, 121, |_| 1.0);
        filter.flow = series(0.25, 121, |_| 4.0);
        let report = analyze_shot(&filter);
        assert!(!report.detectors.pour_truncated);
        assert!(!report.detectors.channeling_checked);
        assert!(!report.detectors.grind_checked);
        assert_eq!(report.detectors.grind_coverage, "skipped");

        // channeling_expected flag.
        let mut flagged = sustained_channeling_input();
        flagged.analysis_flags = vec!["channeling_expected".to_string()];
        let report = analyze_shot(&flagged);
        assert!(!report.detectors.channeling_checked);
    }

    /// A flow-mode profile: Start(0) then Pour(5); flow goal pinned at 2.0.
    fn arm1_input(actual_flow: f64) -> ShotQualityInput {
        let mut input = base_input(40.0);
        input.pressure = series(0.25, 161, |_| 8.0);
        input.flow = series(0.25, 161, |_| actual_flow);
        input.flow_goal = series(0.25, 161, |_| 2.0);
        input.phases = vec![
            marker(0.0, "Start", 0, true, ""),
            marker(5.0, "Pour", 1, true, "time"),
        ];
        input
    }

    #[test]
    fn grind_arm1_delta_too_fine() {
        // Actual 1.4 vs goal 2.0 → delta −0.6 < −0.4. The convergence gate
        // (|1.4−2|/2 = 0.3 > 0.15) keeps channeling windows empty, so the
        // deviation reads as grind, not channeling.
        let report = analyze_shot(&arm1_input(1.4));
        assert!(report.detectors.grind_has_data);
        assert_eq!(report.detectors.grind_direction, "tooFine");
        assert!(approx(report.detectors.grind_flow_delta_ml_s, -0.6, 1e-9));
        assert!(report.detectors.grind_sample_count >= 5);
        assert_eq!(report.detectors.grind_coverage, "verified");
        assert!(!report.detectors.grind_verified_clean, "|delta| > 0.4");
        assert!(report.badges.grind_issue);
        assert_eq!(
            line_text(&report, "grind_too_fine"),
            "Flow averaged 0.6 ml/s below target \u{2014} grind may be too fine"
        );
        assert_eq!(report.detectors.channeling_severity, "none");
        // Caution only, direction fine → minorIssuesGrindFine.
        assert_eq!(report.verdict_category, "minorIssuesGrindFine");
    }

    #[test]
    fn grind_arm1_delta_too_coarse() {
        let report = analyze_shot(&arm1_input(2.6));
        assert_eq!(report.detectors.grind_direction, "tooCoarse");
        assert!(approx(report.detectors.grind_flow_delta_ml_s, 0.6, 1e-9));
        assert!(report.badges.grind_issue);
        assert_eq!(
            line_text(&report, "grind_too_coarse"),
            "Flow averaged 0.6 ml/s above target \u{2014} grind may be too coarse"
        );
        assert_eq!(report.verdict_category, "minorIssuesGrindCoarse");
    }

    #[test]
    fn grind_verified_clean_tracks_goal() {
        // Actual = goal exactly: Arm 1 delta 0 with ≥ 5 samples, Arm 2's
        // gate passes (8 bar all pour) → verifiedClean with sampleCount > 0
        // → the "tracked goal" flavor of the good line. Channeling windows
        // qualify (converged, stationary) and dC/dt is flat → none.
        let report = analyze_shot(&arm1_input(2.0));
        assert!(report.detectors.grind_verified_clean);
        assert_eq!(report.detectors.grind_direction, "onTarget");
        assert!(report.detectors.grind_sample_count >= 5);
        assert_eq!(
            line_text(&report, "grind_clean"),
            "Grind tracked goal during pour"
        );
        assert_eq!(report.detectors.channeling_severity, "none");
        assert!(!report.badges.grind_issue);
        assert_eq!(report.detectors.grind_coverage, "verified");
        assert_eq!(report.verdict_category, "clean");
        assert_eq!(
            report.lines.last().unwrap().text,
            "Verdict: Clean shot. Puck held well."
        );
    }

    /// A pressure-mode profile: Start(0) then Pour(4, confirmed exit).
    fn pressure_mode_phases() -> Vec<PhaseMarker> {
        vec![
            marker(0.0, "Start", 0, false, ""),
            marker(4.0, "Pour", 1, false, "pressure"),
        ]
    }

    #[test]
    fn grind_arm2_choked_severe_flow_arm() {
        // 9 bar held the whole pour, mean flow 0.3 < 0.5 with ≥ 15 s
        // pressurized → severe choke. Pressure goal present and converged →
        // channeling windows qualify but dC/dt is flat.
        let mut input = base_input(30.0);
        input.pressure = series(0.25, 121, |_| 9.0);
        input.pressure_goal = series(0.25, 121, |_| 9.0);
        input.flow = series(0.25, 121, |_| 0.3);
        input.phases = pressure_mode_phases();
        let report = analyze_shot(&input);
        assert!(report.detectors.grind_choked_puck);
        assert_eq!(report.detectors.grind_direction, "chokedPuck");
        assert!(report.detectors.grind_sample_count >= 5);
        assert!(report.badges.grind_issue);
        assert_eq!(
            line_text(&report, "grind_choked_puck"),
            "Pour produced near-zero flow while pressure held \u{2014} puck choked, \
             grind way too fine"
        );
        assert_eq!(report.detectors.channeling_severity, "none");
        assert_eq!(report.verdict_category, "chokedPuck");
        assert_eq!(
            report.lines.last().unwrap().text,
            "Verdict: Puck choked \u{2014} grind way too fine. Coarsen significantly."
        );
    }

    #[test]
    fn grind_arm2_choked_moderate_yield_arm() {
        // Pressurized only ~8 s (< 15 s: the flow arm's gate fails) but
        // yield 20/36 = 0.56 < 0.70 → the yield arm still fires the choke.
        let mut input = base_input(30.0);
        input.pressure = series(
            0.25,
            121,
            |t| if (4.0..12.0).contains(&t) { 9.0 } else { 2.0 },
        );
        input.flow = series(0.25, 121, |_| 1.0);
        input.phases = pressure_mode_phases();
        input.target_weight_g = 36.0;
        input.final_weight_g = 20.0;
        let report = analyze_shot(&input);
        assert!(report.detectors.grind_choked_puck);
        assert!(report.detectors.grind_has_data);
        assert!(!report.detectors.grind_verified_clean);
        assert_eq!(report.verdict_category, "chokedPuck");
    }

    #[test]
    fn yield_overshoot_gusher() {
        // 50 g against a 36 g target: ratio 1.39 > 1.20 → gusher, and the
        // warning pre-empts the (healthy) Arm 2 verify.
        let mut input = base_input(30.0);
        input.pressure = series(0.25, 121, |_| 9.0);
        input.flow = series(0.25, 121, |_| 2.0);
        input.phases = pressure_mode_phases();
        input.target_weight_g = 36.0;
        input.final_weight_g = 50.0;
        let report = analyze_shot(&input);
        assert!(report.detectors.grind_yield_overshoot);
        assert!(
            !report.detectors.grind_verified_clean,
            "overshoot blocks verify"
        );
        assert_eq!(report.detectors.grind_direction, "yieldOvershoot");
        assert!(report.badges.grind_issue);
        assert_eq!(
            line_text(&report, "grind_yield_overshoot"),
            "Yield ran 14.0 g over target \u{2014} puck offered too little resistance, \
             grind way too coarse"
        );
        assert_eq!(report.verdict_category, "yieldOvershoot");
        assert_eq!(
            report.lines.last().unwrap().text,
            "Verdict: Pour gushed past target \u{2014} grind way too coarse. Grind much finer."
        );
    }

    #[test]
    fn skip_first_frame_beats_yield_overshoot() {
        // Verdict precedence: branch 2 (skipFirstFrame) over branch 3
        // (yieldOvershoot); both badges still fire.
        let mut input = base_input(30.0);
        input.pressure = series(0.25, 121, |_| 9.0);
        input.flow = series(0.25, 121, |_| 2.0);
        input.phases = vec![
            marker(0.0, "Start", 0, false, ""),
            marker(1.0, "Frame 1", 1, false, "time"),
        ];
        input.target_weight_g = 36.0;
        input.final_weight_g = 50.0;
        let report = analyze_shot(&input);
        assert!(report.detectors.skip_first_frame);
        assert!(report.detectors.grind_yield_overshoot);
        assert!(report.badges.skip_first_frame);
        assert!(report.badges.grind_issue);
        assert_eq!(report.verdict_category, "skipFirstFrame");
        assert_eq!(
            report.lines.last().unwrap().text,
            "Verdict: First profile step was skipped \u{2014} power-cycle the machine to \
             fix a firmware bug, or review the profile's first step settings."
        );
        assert_eq!(
            line_text(&report, "skip_first_frame"),
            "First profile step skipped \u{2014} likely a DE1 firmware bug (power-cycle \
             machine to fix) or first step too short (check profile settings)"
        );
    }

    #[test]
    fn sustained_channeling_with_fine_grind_names_direction() {
        // Verdict branch 5 with a direction: region A (4–18 s) oscillates
        // flow within ±14.5% of the 2.0 goal at 2.6 bar — converged windows
        // with |dC/dt| swings above 3 → sustained channeling; region B
        // (18–40 s) runs 1.2 vs 2.0 — excluded from windows (not converged)
        // but averaged by Arm 1, dragging delta below −0.4.
        let mut input = base_input(40.0);
        input.pressure = series(0.25, 161, |_| 2.6);
        input.flow_goal = series(0.25, 161, |_| 2.0);
        input.flow = series(0.25, 161, |t| {
            if t < 4.0 {
                2.0
            } else if t < 18.0 {
                triangle(t - 4.0, 4.0, 1.71, 2.29)
            } else {
                1.2
            }
        });
        input.phases = vec![
            marker(0.0, "Start", 0, true, ""),
            marker(4.0, "Pour", 1, true, "time"),
            marker(18.0, "Extract", 2, true, "time"),
        ];
        let report = analyze_shot(&input);
        assert_eq!(report.detectors.channeling_severity, "sustained");
        assert!(report.badges.channeling);
        assert!(report.detectors.grind_has_data);
        assert!(
            report.detectors.grind_flow_delta_ml_s < -FLOW_DEVIATION_THRESHOLD,
            "delta {}",
            report.detectors.grind_flow_delta_ml_s
        );
        assert_eq!(report.detectors.grind_direction, "tooFine");
        assert!(report.badges.grind_issue);
        assert_eq!(report.verdict_category, "puckIntegrityGrindFine");
        assert_eq!(
            report.lines.last().unwrap().text,
            "Verdict: Puck integrity issue \u{2014} improve distribution. Grind is \
             running fine \u{2014} try coarser."
        );
    }

    #[test]
    fn grind_not_analyzable_when_kb_unresolved_and_gate_fails() {
        // profile_kb_resolved=false skips Arm 1; 3.0 bar never reaches the
        // 4-bar pressurized gate so Arm 2 has nothing → notAnalyzable, and
        // with no warnings/cautions the verdict is honest about it.
        let mut input = base_input(40.0);
        input.pressure = series(0.25, 161, |_| 3.0);
        input.flow = series(0.25, 161, |_| 2.0);
        input.flow_goal = series(0.25, 161, |_| 2.0);
        input.phases = vec![
            marker(0.0, "Start", 0, true, ""),
            marker(4.0, "Pour", 1, true, "flow"),
        ];
        input.profile_kb_resolved = false;
        let report = analyze_shot(&input);
        assert!(report.detectors.grind_checked);
        assert!(!report.detectors.grind_has_data);
        assert_eq!(report.detectors.grind_coverage, "notAnalyzable");
        assert_eq!(report.detectors.grind_direction, "");
        assert!(!report.badges.grind_issue);
        assert_eq!(
            line_text(&report, "grind_not_analyzable"),
            "Could not analyze grind on this profile shape \u{2014} check flow trend, \
             channeling, and taste instead"
        );
        assert_eq!(report.verdict_category, "cleanGrindNotAnalyzable");
        assert_eq!(
            report.lines.last().unwrap().text,
            "Verdict: Clean shot, but grind could not be evaluated for this profile shape."
        );

        // Contrast: with the KB resolved, Arm 1 runs and produces on-target
        // data (delta 0), so the same shot verdicts clean.
        let mut input2 = base_input(40.0);
        input2.pressure = series(0.25, 161, |_| 3.0);
        input2.flow = series(0.25, 161, |_| 2.0);
        input2.flow_goal = series(0.25, 161, |_| 2.0);
        input2.phases = vec![
            marker(0.0, "Start", 0, true, ""),
            marker(4.0, "Pour", 1, true, "flow"),
        ];
        let resolved = analyze_shot(&input2);
        assert!(resolved.detectors.grind_has_data);
        assert_eq!(resolved.detectors.grind_direction, "onTarget");
        assert!(
            !resolved.detectors.grind_verified_clean,
            "Arm 2 gate failed"
        );
        assert_eq!(resolved.detectors.grind_coverage, "verified");
        assert_eq!(resolved.verdict_category, "clean");
    }

    /// Pressure-mode shell for the flow-trend tests: Pour starts at 5 s,
    /// goals absent so channeling passes silently, Arm 2 verifies clean.
    fn flow_trend_input(f: impl Fn(f64) -> f64) -> ShotQualityInput {
        let mut input = base_input(40.0);
        input.pressure = series(0.25, 161, |_| 8.0);
        input.flow = series(0.25, 161, f);
        input.phases = vec![
            marker(0.0, "Start", 0, false, ""),
            marker(5.0, "Pour", 1, false, "weight"),
        ];
        input
    }

    #[test]
    fn flow_trend_rising() {
        // First 30% of the pour averages 1.0, last 30% averages 2.0.
        let report = analyze_shot(&flow_trend_input(|t| {
            if t < 15.5 {
                1.0
            } else if t <= 29.5 {
                1.5
            } else {
                2.0
            }
        }));
        assert!(report.detectors.flow_trend_checked);
        assert_eq!(report.detectors.flow_trend, "rising");
        assert!(approx(report.detectors.flow_trend_delta_ml_s, 1.0, 1e-9));
        assert_eq!(
            line_text(&report, "flow_rising"),
            "Flow rose 1.0 mL/s during extraction (puck erosion)"
        );
        assert_eq!(report.verdict_category, "minorIssues");
        assert_eq!(
            report.lines.last().unwrap().text,
            "Verdict: Decent shot with minor issues to watch."
        );
    }

    #[test]
    fn flow_trend_falling_and_stable_and_flag() {
        let report = analyze_shot(&flow_trend_input(|t| {
            if t < 15.5 {
                2.0
            } else if t <= 29.5 {
                1.5
            } else {
                1.0
            }
        }));
        assert_eq!(report.detectors.flow_trend, "falling");
        assert!(approx(report.detectors.flow_trend_delta_ml_s, -1.0, 1e-9));
        assert_eq!(
            line_text(&report, "flow_falling"),
            "Flow dropped 1.0 mL/s (fines migration or clogging)"
        );

        // Stable: flat flow emits no trend line.
        let report = analyze_shot(&flow_trend_input(|_| 1.5));
        assert_eq!(report.detectors.flow_trend, "stable");
        assert!(!has_line(&report, "flow_rising", "caution"));
        assert!(!has_line(&report, "flow_falling", "caution"));

        // flow_trend_ok flag suppresses the detector entirely.
        let mut flagged = flow_trend_input(|t| if t < 15.5 { 1.0 } else { 2.0 });
        flagged.analysis_flags = vec!["flow_trend_ok".to_string()];
        let report = analyze_shot(&flagged);
        assert!(!report.detectors.flow_trend_checked);
    }

    #[test]
    fn preinfusion_drip_observed() {
        // Preinfusion end at 3 s with 1.6 g in the cup over 3.0 s.
        let mut input = base_input(40.0);
        input.pressure = series(0.25, 161, |_| 8.0);
        input.flow = series(0.25, 161, |_| 2.0);
        input.weight = series(0.25, 161, |t| if t < 1.0 { 0.0 } else { 0.8 * (t - 1.0) });
        input.phases = vec![
            marker(0.0, "Start", 0, false, ""),
            marker(3.0, "Preinfusion", 0, false, ""),
            marker(10.0, "Pour", 1, false, "weight"),
        ];
        let report = analyze_shot(&input);
        assert!(report.detectors.preinfusion_observed);
        assert!(approx(
            report.detectors.preinfusion_drip_weight_g,
            1.6,
            1e-9
        ));
        assert!(approx(
            report.detectors.preinfusion_drip_duration_s,
            3.0,
            1e-9
        ));
        assert_eq!(
            line_text(&report, "preinfusion_drip"),
            "Preinfusion: 1.6g in 3.0s"
        );
        assert!(approx(report.detectors.pour_start_s, 10.0, 1e-12));

        // Below either threshold the observation stays silent: dry
        // preinfusion (0.3 g at the 3 s mark).
        let mut dry = input.clone();
        dry.weight = series(0.25, 161, |t| if t < 3.25 { 0.3 } else { 20.0 });
        let report = analyze_shot(&dry);
        assert!(!report.detectors.preinfusion_observed);
        assert!(!has_line(&report, "preinfusion_drip", "observation"));
    }

    #[test]
    fn grind_check_skip_flag_forces_skipped() {
        let mut input = arm1_input(1.4);
        input.analysis_flags = vec!["grind_check_skip".to_string()];
        let report = analyze_shot(&input);
        assert!(!report.detectors.grind_checked);
        assert_eq!(report.detectors.grind_coverage, "skipped");
        assert!(!report.badges.grind_issue);
        assert!(!report.detectors.grind_has_data);
    }
}
