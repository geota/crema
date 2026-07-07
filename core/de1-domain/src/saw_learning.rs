//! SAW (stop-at-weight) drip-learning model — a pure-Rust port of Decenza's
//! adaptive expected-drip predictor.
//!
//! When a shot is stopped at a target weight, coffee keeps flowing for a
//! moment (BLE round-trip + machine stop lag + final drip). Decenza learns
//! how many grams that "drip" adds, per (profile, scale) pair, and stops the
//! shot early by the predicted amount. This module ports the *model* only:
//! the Gaussian flow-similarity kernel, the tiered store, sample gates,
//! batch-median commits, the global bootstrap, convergence detection, the
//! read paths, and JSON persistence. The live-shot integration (settling
//! capture, stop threshold) stays with the caller.
//!
//! Ported from Decenza (local checkout `crema-design/Decenza`):
//! - kernel + constants: `src/machine/sawprediction.h:20-87`
//! - store, gates, batches, bootstrap, convergence, read paths:
//!   `src/core/settings_calibration.cpp:217-944`
//! - live snapshot capture: `src/main.cpp:778-802`
//! - live prediction math: `src/machine/weightprocessor.cpp:587-615`
//!
//! Tier layout (`settings_calibration.cpp` QSettings keys → fields here):
//! 1. per-pair committed history (`saw/perProfileHistory`) — batch medians,
//!    trimmed to [`MAX_PAIR_HISTORY`];
//! 2. per-pair pending batch (`saw/perProfileBatch`) — raw samples, commits
//!    at [`BATCH_SIZE`];
//! 3. global pool (`saw/learningHistory`) — committed medians mirrored flat,
//!    trimmed to [`MAX_GLOBAL_POOL`]; drives convergence detection;
//! 4. global bootstrap lag (`saw/globalBootstrapLag/<scale>`) — a cold-start
//!    lag prior per scale, the cross-profile median of per-pair lags.
//!
//! Keys are `"{profile}::{scale}"` (`settings_calibration.cpp:593-599`). The
//! caller passes already-normalized scale labels — the port carries no
//! normalization table.
//!
//! Deviations from Decenza (both deliberate):
//! - [`weighted_drip_prediction`] returns `Option<f64>` where Decenza returns
//!   `qQNaN()` — `None` replaces NaN as the "fall back" signal.
//! - The converged outlier gate in [`SawLearningModel::add_learning_point`]
//!   has no sensor-lag parameter, so its expected-drip chain stops at the
//!   bootstrap: per-pair kernel → bootstrap → *skip the gate*. Decenza's
//!   `getExpectedDripFor` (`settings_calibration.cpp:728-766`) would end in
//!   the sensor-lag default; skipping instead of rejecting is strictly more
//!   permissive and only differs on models with no learned data at all.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

// ---------------------------------------------------------------------------
// Constants (each cites its Decenza source)
// ---------------------------------------------------------------------------

/// Gaussian flow-similarity σ (g/s) used when weighting past entries by how
/// close their training flow was to the current flow. PR #870 narrowed it
/// from 1.5 → 0.25 (`sawprediction.h:24`).
const FLOW_SIMILARITY_SIGMA: f64 = 0.25;

/// Pre-computed 2σ² for the `exp(-x²/(2σ²))` kernel — 0.125
/// (`sawprediction.h:27-28`).
const FLOW_SIMILARITY_SIGMA_SQ2: f64 = 2.0 * FLOW_SIMILARITY_SIGMA * FLOW_SIMILARITY_SIGMA;

/// Floor below which the kernel's total weight is untrustworthy (every entry
/// far from the current flow) and the caller must fall back
/// (`sawprediction.h:32`).
const MIN_TOTAL_WEIGHT: f64 = 0.01;

/// Prediction clamp band, grams — values outside are not credible and pin to
/// the nearest edge (`sawprediction.h:36-37`).
const MIN_DRIP_PREDICTION: f64 = 0.5;
/// Upper edge of the prediction clamp band (`sawprediction.h:37`).
const MAX_DRIP_PREDICTION: f64 = 20.0;

/// Raw samples per pending batch before a median is committed
/// (`settings_calibration.cpp:23`, `kBatchSize`).
const BATCH_SIZE: usize = 3;

/// Committed medians kept per (profile, scale) pair — oldest removed first
/// (`settings_calibration.cpp:24`, `kMaxPairHistory`).
const MAX_PAIR_HISTORY: usize = 10;

/// Batch dispersion gate, seconds: if any raw sample's implied lag deviates
/// more than this from the batch median lag, the whole batch is dropped
/// (`settings_calibration.cpp:25`, `kBatchMaxDeviation`; applied at
/// `:819-837`).
const BATCH_MAX_DEVIATION_S: f64 = 1.5;

/// Global-pool cap — trims oldest first (`settings_calibration.cpp:539-541`
/// on the legacy path, `:880` on the batch-mirror path).
const MAX_GLOBAL_POOL: usize = 50;

/// Committed medians before a pair graduates from the global fallbacks to
/// its own per-pair model (`settings_calibration.cpp:21`,
/// `kSawMinMediansForGraduation`). One median = three gated shots.
const MIN_MEDIANS_FOR_GRADUATION: usize = 1;

/// Overshoot (grams, signed) below which a sample is an auto-reset candidate
/// — the shot stopped 6 g+ early, so the model may be systematically wrong
/// (`settings_calibration.cpp:461`). Two *consecutive* committed medians
/// below this wipe the pair's history (`settings_calibration.cpp:839-854`).
const AUTO_RESET_OVERSHOOT_G: f64 = -6.0;

/// Converged outlier gate floor, grams: a sample is rejected when its drip
/// deviates from the expected drip by more than `max(3.0, expected)`
/// (`settings_calibration.cpp:464`).
const OUTLIER_GATE_FLOOR_G: f64 = 3.0;

/// Implied lag (drip/flow, seconds) above which a sample is physically
/// implausible — beyond any real BLE scale's worst case
/// (`settings_calibration.cpp:452-455`).
const MAX_IMPLIED_LAG_S: f64 = 4.0;

/// Flow validity threshold, g/s — below this, drip/flow ratios are
/// meaningless (division by near-zero) and are not formed
/// (`settings_calibration.cpp:452`, `:789`, `:805`, `:817`, `:920`).
const FLOW_VALIDITY_G_PER_S: f64 = 0.5;

/// The DE1 machine-side stop-command lag added to the scale sensor lag in
/// the first-shot default (`settings_calibration.cpp:341-343`,
/// `weightprocessor.cpp:592-596`): `min(flow × (sensor_lag + 0.1), 8.0)`.
const DE1_STOP_LAG_S: f64 = 0.1;

/// Cap (grams) on every default/bootstrap drip estimate
/// (`settings_calibration.cpp:343`, `:763`, `:765`).
const MAX_DEFAULT_DRIP_G: f64 = 8.0;

/// Convergence window: the newest N global-pool entries for the scale
/// (`settings_calibration.cpp:282-284`).
const CONVERGENCE_WINDOW: usize = 5;

/// Minimum overshoot samples in the window for convergence to be assessable
/// (`settings_calibration.cpp:291-296`).
const CONVERGENCE_MIN_SAMPLES: usize = 3;

/// Converged when the window's mean |overshoot| is under this, grams
/// (`settings_calibration.cpp:291-300`).
const CONVERGENCE_MEAN_ABS_G: f64 = 1.5;

/// Divergence override: if the newest N signed overshoots all exceed
/// ±[`DIVERGENCE_OVERSHOOT_G`] in the same direction, the prediction is
/// systematically off (bean/grind change) and convergence is forced false
/// (`settings_calibration.cpp:301-325`).
const DIVERGENCE_RUN: usize = 3;

/// Same-direction overshoot magnitude (grams) that counts toward divergence
/// (`settings_calibration.cpp:314-317`).
const DIVERGENCE_OVERSHOOT_G: f64 = 1.0;

/// Recency weight for the newest kernel entry — shared by every read path
/// (`settings_calibration.cpp:349`, `:754`; `weightprocessor.cpp:599`).
const RECENCY_MAX: f64 = 10.0;

/// Recency weight for the oldest entry once converged — a shallower ramp
/// keeps more history in play (`settings_calibration.cpp:350`,
/// `weightprocessor.cpp:600`). Also the fixed `recencyMin` of the per-pair
/// read path (`settings_calibration.cpp:752-754`).
const RECENCY_MIN_CONVERGED: f64 = 3.0;

/// Recency weight for the oldest entry while still adapting — a steeper ramp
/// chases recent shots faster (`settings_calibration.cpp:350`,
/// `weightprocessor.cpp:600`).
const RECENCY_MIN_ADAPTING: f64 = 1.0;

/// Live-snapshot entry caps: converged keeps more history
/// (`settings_calibration.cpp:348`, `main.cpp:788`,
/// `weightprocessor.cpp:598`).
const SNAPSHOT_ENTRIES_CONVERGED: usize = 12;
/// Live-snapshot cap while adapting (`settings_calibration.cpp:348`).
const SNAPSHOT_ENTRIES_ADAPTING: usize = 8;

/// Newest per-pair medians read by the per-pair prediction path
/// (`settings_calibration.cpp:737-740`).
const PER_PAIR_READ_ENTRIES: usize = 3;

/// Pairs (with a committed median on the scale) required before a bootstrap
/// median is computed (`settings_calibration.cpp:922-925`).
const MIN_BOOTSTRAP_PAIRS: usize = 2;

/// Lag count at which the bootstrap applies its IQR fence
/// (`settings_calibration.cpp:928`).
const IQR_FENCE_MIN_LAGS: usize = 4;

/// Tukey fence multiplier: keep lags within `[q1 − 1.5·IQR, q3 + 1.5·IQR]`
/// (`settings_calibration.cpp:927-937`).
const IQR_FENCE_MULTIPLIER: f64 = 1.5;

// ---------------------------------------------------------------------------
// Kernel
// ---------------------------------------------------------------------------

/// Predict the expected drip from past `(drip, flow)` pairs using a Gaussian
/// flow-similarity kernel and a linear recency weight — the shared math of
/// `SawPrediction::weightedDripPrediction` (`sawprediction.h:53-85`), which
/// Decenza centralised so the live threshold and the learning feedback can
/// never drift in σ.
///
/// `drips` and `flows` are parallel, **newest-first** (index 0 = newest).
/// For entry `i` (`sawprediction.h:64-77`):
///
/// - `recency_i = recency_max − i·(recency_max − recency_min)/max(1, n−1)`
/// - `floww_i  = exp(−(flows[i] − current_flow)² / 0.125)` (2σ², σ = 0.25)
/// - `w_i      = recency_i · floww_i`
///
/// Returns the weighted-average drip clamped to
/// [[`MIN_DRIP_PREDICTION`], [`MAX_DRIP_PREDICTION`]] grams
/// (`sawprediction.h:82-84`), or `None` when the inputs are empty/mismatched
/// or the total weight falls under [`MIN_TOTAL_WEIGHT`]
/// (`sawprediction.h:59-61`, `:79-81`) — `None` replaces Decenza's `qQNaN()`;
/// callers fall back to their own default (fallback chains differ per site).
#[must_use]
pub fn weighted_drip_prediction(
    drips: &[f64],
    flows: &[f64],
    current_flow: f64,
    recency_max: f64,
    recency_min: f64,
) -> Option<f64> {
    let count = drips.len();
    if count == 0 || flows.len() != count {
        return None;
    }

    // `qMax(qsizetype{1}, count - 1)` (`sawprediction.h:64`). Entry counts
    // are tiny (≤ 50 by the pool cap); the checked u32→f64 route is lossless.
    let denom = f64::from(u32::try_from(count - 1).unwrap_or(u32::MAX).max(1));
    let recency_step = (recency_max - recency_min) / denom;

    let mut weighted_drip_sum = 0.0;
    let mut total_weight = 0.0;
    // `index` counts 0, 1, 2, … exactly in f64 — `index * recency_step`
    // matches the C++ `i * step` without a lossy usize→f64 cast.
    let mut index = 0.0_f64;
    for (&drip, &flow) in drips.iter().zip(flows) {
        let recency_weight = recency_max - index * recency_step;
        let flow_diff = flow - current_flow;
        let flow_weight = (-(flow_diff * flow_diff) / FLOW_SIMILARITY_SIGMA_SQ2).exp();
        let w = recency_weight * flow_weight;
        weighted_drip_sum += drip * w;
        total_weight += w;
        index += 1.0;
    }

    if total_weight < MIN_TOTAL_WEIGHT {
        return None;
    }
    Some((weighted_drip_sum / total_weight).clamp(MIN_DRIP_PREDICTION, MAX_DRIP_PREDICTION))
}

// ---------------------------------------------------------------------------
// Store types
// ---------------------------------------------------------------------------

/// One learning sample — raw (in a pending batch) or a committed batch
/// median. Field set mirrors Decenza's JSON entries
/// (`settings_calibration.cpp:777-784` raw, `:857-864` median), so the
/// serialized names (`drip`/`flow`/`overshoot`/`scale`/`profile`/`ts`/
/// `batchSize`) match its on-disk format.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct SawEntry {
    /// Grams that landed after the stop trigger: final settled weight minus
    /// weight at trigger (caller clamps negative→0 pre-call).
    drip: f64,
    /// Short-window flow (g/s) at the moment the stop was triggered.
    flow: f64,
    /// Signed grams over (+) / under (−) the target — final minus target.
    overshoot: f64,
    /// Scale label (already normalized by the caller).
    scale: String,
    /// Profile identifier.
    profile: String,
    /// Sample timestamp, seconds (caller-supplied; Decenza stamps
    /// `currentSecsSinceEpoch`, `settings_calibration.cpp:783`).
    ts: u64,
    /// Raw-sample count behind a committed median
    /// (`settings_calibration.cpp:864`, `batchSize`); `None` on raw entries.
    #[serde(skip_serializing_if = "Option::is_none")]
    batch_size: Option<u32>,
}

/// The learning data a live shot captures at start — Decenza builds the same
/// snapshot on `espressoCycleStarted` (`main.cpp:784-802`) and hands it to
/// the weight worker, so mid-shot prediction never touches the store.
///
/// `drips`/`flows` are parallel and newest-first, already resolved through
/// the per-pair → global-pool fallback and truncated to the converged/
/// adapting cap. Feed it to [`SawLearningModel::expected_drip`].
#[derive(Debug, Clone, Default, PartialEq)]
pub struct SawSnapshot {
    /// Drip values, grams, newest-first.
    pub drips: Vec<f64>,
    /// Training flows, g/s, parallel to `drips`.
    pub flows: Vec<f64>,
    /// Whether the scale's model had converged at capture time — selects the
    /// kernel's entry cap and recency floor.
    pub converged: bool,
}

/// The tiered SAW drip-learning store. Persist it as an opaque JSON blob
/// (serde round-trips it losslessly); all mutation goes through
/// [`add_learning_point`](Self::add_learning_point).
///
/// The four tiers mirror Decenza's QSettings keys — see the module docs.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SawLearningModel {
    /// Tier 1 — committed batch medians per `"profile::scale"` key,
    /// oldest→newest, trimmed to [`MAX_PAIR_HISTORY`]
    /// (`saw/perProfileHistory`).
    pair_history: BTreeMap<String, Vec<SawEntry>>,
    /// Tier 2 — raw samples awaiting a batch commit per pair key
    /// (`saw/perProfileBatch`).
    pending_batch: BTreeMap<String, Vec<SawEntry>>,
    /// Tier 3 — committed medians mirrored flat across all pairs,
    /// oldest→newest, trimmed to [`MAX_GLOBAL_POOL`] (`saw/learningHistory`).
    global_pool: Vec<SawEntry>,
    /// Tier 4 — cold-start lag prior (seconds) per scale
    /// (`saw/globalBootstrapLag/<scale>`).
    bootstrap_lag: BTreeMap<String, f64>,
}

/// The pair key: `"{profile}::{scale}"` (`settings_calibration.cpp:593-599`,
/// `sawPairKey`). The caller passes already-normalized scale labels.
fn pair_key(profile: &str, scale: &str) -> String {
    format!("{profile}::{scale}")
}

/// Sort-then-middle median, averaging the two central values on an even
/// count — Decenza's `medianOf` (`settings_calibration.cpp:808-813`), reused
/// verbatim by the bootstrap median (`:939-940`). Empty input → 0.0.
fn median(values: &[f64]) -> f64 {
    if values.is_empty() {
        return 0.0;
    }
    let mut sorted = values.to_vec();
    sorted.sort_by(f64::total_cmp);
    let n = sorted.len();
    if n.is_multiple_of(2) {
        f64::midpoint(sorted[n / 2 - 1], sorted[n / 2])
    } else {
        sorted[n / 2]
    }
}

/// Arithmetic mean without a lossy length cast (counts in f64); empty → 0.0.
fn mean(values: impl Iterator<Item = f64>) -> f64 {
    let mut sum = 0.0;
    let mut n = 0.0;
    for v in values {
        sum += v;
        n += 1.0;
    }
    if n > 0.0 { sum / n } else { 0.0 }
}

impl SawLearningModel {
    /// Feed one post-shot training sample for `(profile, scale)` — the port
    /// of `addSawLearningPoint` (`settings_calibration.cpp:436-483`) routed
    /// through the per-pair batch path (`addSawPerPairEntry`, `:770-899`);
    /// the legacy profile-less global path is not ported (a profile is
    /// always known here).
    ///
    /// `drip` = final settled weight − weight at stop trigger (grams; the
    /// caller clamps negative→0 first, but negatives are hard-rejected here
    /// too). `flow` = short-window flow at the stop (g/s). `overshoot` =
    /// final − target (signed grams). `ts_s` = sample timestamp, seconds.
    ///
    /// Gates, in order:
    /// 1. `drip < 0 || flow < 0` → reject (scale glitches,
    ///    `settings_calibration.cpp:443-446`);
    /// 2. `flow > 0.5 && drip/flow > 4.0` → reject (implied lag beyond any
    ///    real BLE scale; the flow guard keeps the ratio meaningful,
    ///    `:452-455`);
    /// 3. converged outlier gate (`:461-472`): skipped when
    ///    `overshoot < −6` (auto-reset candidate — the model may be
    ///    systematically wrong and must accept the new baseline) or when the
    ///    scale has not converged; otherwise the expected drip comes from the
    ///    per-pair kernel → bootstrap chain (see the module docs on the
    ///    missing sensor-lag leg) and the sample is rejected when
    ///    `|drip − expected| > max(3.0, expected)`;
    /// 4. accept → append to the pair's pending batch (`:774-794`); on the
    ///    [`BATCH_SIZE`]th sample the batch commits (median + dispersion +
    ///    auto-reset + pool mirror + bootstrap recompute, `:796-899`).
    pub fn add_learning_point(
        &mut self,
        profile: &str,
        scale: &str,
        drip: f64,
        flow: f64,
        overshoot: f64,
        ts_s: u64,
    ) {
        // Gate 1 — physical validity (`settings_calibration.cpp:443-446`).
        if drip < 0.0 || flow < 0.0 {
            return;
        }

        // Gate 2 — implausible implied lag (`settings_calibration.cpp:452-455`).
        if flow > FLOW_VALIDITY_G_PER_S && drip / flow > MAX_IMPLIED_LAG_S {
            return;
        }

        // Gate 3 — converged outlier rejection (`settings_calibration.cpp:461-472`).
        let auto_reset_candidate = overshoot < AUTO_RESET_OVERSHOOT_G;
        if !auto_reset_candidate
            && self.is_converged(scale)
            && let Some(expected) = self.gate_expected_drip(profile, scale, flow)
        {
            let threshold = expected.max(OUTLIER_GATE_FLOOR_G);
            if (drip - expected).abs() > threshold {
                return;
            }
        }

        // Gate 4 — accept into the pending batch (`settings_calibration.cpp:774-794`).
        let key = pair_key(profile, scale);
        let batch = self.pending_batch.entry(key.clone()).or_default();
        batch.push(SawEntry {
            drip,
            flow,
            overshoot,
            scale: scale.to_owned(),
            profile: profile.to_owned(),
            ts: ts_s,
            batch_size: None,
        });
        if batch.len() >= BATCH_SIZE {
            self.commit_batch(&key, profile, scale, ts_s);
        }
    }

    /// Commit a full pending batch — the tail of `addSawPerPairEntry`
    /// (`settings_calibration.cpp:796-899`). Steps:
    ///
    /// 1. element-wise medians of drip/flow/overshoot over the raws
    ///    (`:796-816`); `median_lag = median_drip/median_flow` when
    ///    `median_flow > 0.5`, else 0 (`:817`);
    /// 2. dispersion gate: per-raw `lag_i = drip_i/flow_i` (flows > 0.5
    ///    only); any `|lag_i − median_lag| > 1.5 s` drops the whole batch —
    ///    pending cleared, nothing committed (`:819-837`; IQR gating is not
    ///    used because 3 values are too few);
    /// 3. per-pair auto-reset: median overshoot < −6 g AND the pair's last
    ///    committed median overshoot < −6 g → wipe the pair's history first,
    ///    so the new median becomes the sole baseline (`:839-854` — two
    ///    consecutive *medians* ≈ six consecutive bad shots, intentional
    ///    debouncing);
    /// 4. append the median to the pair history, trim to
    ///    [`MAX_PAIR_HISTORY`] oldest-first (`:856-868`);
    /// 5. mirror the median into the global pool, trim to
    ///    [`MAX_GLOBAL_POOL`] (`:870-881`) — this keeps convergence and the
    ///    global read path fed;
    /// 6. clear the pending batch (`:885-887`) and recompute the scale's
    ///    bootstrap (`:894-896`).
    fn commit_batch(&mut self, key: &str, profile: &str, scale: &str, ts_s: u64) {
        // Removing the key doubles as "clear pending" for both the drop and
        // the commit paths (`settings_calibration.cpp:834-835`, `:885-887`).
        let Some(batch) = self.pending_batch.remove(key) else {
            return;
        };

        let drips: Vec<f64> = batch.iter().map(|e| e.drip).collect();
        let flows: Vec<f64> = batch.iter().map(|e| e.flow).collect();
        let overshoots: Vec<f64> = batch.iter().map(|e| e.overshoot).collect();
        let lags: Vec<f64> = batch
            .iter()
            .filter(|e| e.flow > FLOW_VALIDITY_G_PER_S)
            .map(|e| e.drip / e.flow)
            .collect();

        let median_drip = median(&drips);
        let median_flow = median(&flows);
        let median_overshoot = median(&overshoots);
        let median_lag = if median_flow > FLOW_VALIDITY_G_PER_S {
            median_drip / median_flow
        } else {
            0.0
        };

        // Dispersion gate (`settings_calibration.cpp:819-837`).
        if lags
            .iter()
            .any(|lag| (lag - median_lag).abs() > BATCH_MAX_DEVIATION_S)
        {
            return;
        }

        let median_entry = SawEntry {
            drip: median_drip,
            flow: median_flow,
            overshoot: median_overshoot,
            scale: scale.to_owned(),
            profile: profile.to_owned(),
            ts: ts_s,
            batch_size: u32::try_from(batch.len()).ok(),
        };

        {
            let history = self.pair_history.entry(key.to_owned()).or_default();
            // Per-pair auto-reset (`settings_calibration.cpp:839-854`).
            if median_overshoot < AUTO_RESET_OVERSHOOT_G
                && history
                    .last()
                    .is_some_and(|last| last.overshoot < AUTO_RESET_OVERSHOOT_G)
            {
                history.clear();
            }
            history.push(median_entry.clone());
            while history.len() > MAX_PAIR_HISTORY {
                history.remove(0);
            }
        }

        self.global_pool.push(median_entry);
        while self.global_pool.len() > MAX_GLOBAL_POOL {
            self.global_pool.remove(0);
        }

        self.recompute_bootstrap(scale);
    }

    /// Recompute the scale's bootstrap lag — `recomputeGlobalSawBootstrap`
    /// (`settings_calibration.cpp:901-944`). The bootstrap is a cold-start
    /// prior for *new* pairs: one committed median (3 real shots) already
    /// beats a static sensor-lag constant.
    ///
    /// Every pair whose **last** committed median is on this scale and has
    /// `flow > 0.5` contributes `lag = drip/flow` (`:909-921`). Fewer than
    /// [`MIN_BOOTSTRAP_PAIRS`] → abort, keeping any existing value
    /// (`:922-925`). At ≥ [`IQR_FENCE_MIN_LAGS`] lags a Tukey fence
    /// (`q1 = lags[n/4]`, `q3 = lags[3n/4]`, keep within `±1.5·IQR`) drops
    /// under-trained outliers, but only if ≥ 2 survive (`:926-938`). The
    /// stored value is the median of the kept lags (`:939-941`).
    fn recompute_bootstrap(&mut self, scale: &str) {
        let mut lags: Vec<f64> = Vec::new();
        for history in self.pair_history.values() {
            let Some(last) = history.last() else { continue };
            if last.scale == scale && last.flow > FLOW_VALIDITY_G_PER_S {
                lags.push(last.drip / last.flow);
            }
        }
        if lags.len() < MIN_BOOTSTRAP_PAIRS {
            return;
        }
        lags.sort_by(f64::total_cmp);
        if lags.len() >= IQR_FENCE_MIN_LAGS {
            let n = lags.len();
            let q1 = lags[n / 4];
            let q3 = lags[3 * n / 4];
            let iqr = q3 - q1;
            let lower = q1 - IQR_FENCE_MULTIPLIER * iqr;
            let upper = q3 + IQR_FENCE_MULTIPLIER * iqr;
            let kept: Vec<f64> = lags
                .iter()
                .copied()
                .filter(|lag| *lag >= lower && *lag <= upper)
                .collect();
            if kept.len() >= MIN_BOOTSTRAP_PAIRS {
                lags = kept;
            }
        }
        self.bootstrap_lag.insert(scale.to_owned(), median(&lags));
    }

    /// Whether the scale's predictions have converged — `isSawConverged`
    /// (`settings_calibration.cpp:266-330`), computed over the global pool
    /// filtered by scale.
    ///
    /// Takes the newest [`CONVERGENCE_WINDOW`] entries for the scale; fewer
    /// than [`CONVERGENCE_MIN_SAMPLES`] → not converged (`:282-296`).
    /// Converged when the mean |overshoot| is under
    /// [`CONVERGENCE_MEAN_ABS_G`] (`:298-300`). Divergence override: when the
    /// newest [`DIVERGENCE_RUN`] signed overshoots all exceed ±1 g in the
    /// same direction, the prediction is systematically off (bean/grind
    /// change) and adaptation mode is forced without a manual reset
    /// (`:301-325`).
    #[must_use]
    pub fn is_converged(&self, scale: &str) -> bool {
        let recent: Vec<f64> = self
            .global_pool
            .iter()
            .rev()
            .filter(|entry| entry.scale == scale)
            .map(|entry| entry.overshoot)
            .take(CONVERGENCE_WINDOW)
            .collect();
        if recent.len() < CONVERGENCE_MIN_SAMPLES {
            return false;
        }
        let mut converged = mean(recent.iter().map(|v| v.abs())) < CONVERGENCE_MEAN_ABS_G;
        if converged {
            // `recent` is newest-first, so the first DIVERGENCE_RUN values
            // are the last 3 shots (`settings_calibration.cpp:305-324`).
            let newest = &recent[..DIVERGENCE_RUN];
            let all_over = newest.iter().all(|v| *v > DIVERGENCE_OVERSHOOT_G);
            let all_under = newest.iter().all(|v| *v < -DIVERGENCE_OVERSHOOT_G);
            if all_over || all_under {
                converged = false;
            }
        }
        converged
    }

    /// Capture the learning data a live shot needs at start — what Decenza
    /// assembles on `espressoCycleStarted` (`main.cpp:784-802`) via
    /// `sawLearningEntriesFor` (`settings_calibration.cpp:686-703`).
    ///
    /// Entries are newest-first: the pair's committed medians once it has
    /// ≥ [`MIN_MEDIANS_FOR_GRADUATION`] of them, else the global pool
    /// filtered by scale. Truncated to [`SNAPSHOT_ENTRIES_CONVERGED`] /
    /// [`SNAPSHOT_ENTRIES_ADAPTING`] per [`is_converged`](Self::is_converged)
    /// (`main.cpp:786-788`).
    #[must_use]
    pub fn snapshot(&self, profile: &str, scale: &str) -> SawSnapshot {
        let converged = self.is_converged(scale);
        let max_entries = if converged {
            SNAPSHOT_ENTRIES_CONVERGED
        } else {
            SNAPSHOT_ENTRIES_ADAPTING
        };

        let mut drips = Vec::new();
        let mut flows = Vec::new();
        let graduated = self
            .pair_history
            .get(&pair_key(profile, scale))
            .filter(|history| history.len() >= MIN_MEDIANS_FOR_GRADUATION);
        if let Some(history) = graduated {
            for entry in history.iter().rev().take(max_entries) {
                drips.push(entry.drip);
                flows.push(entry.flow);
            }
        } else {
            for entry in self
                .global_pool
                .iter()
                .rev()
                .filter(|entry| entry.scale == scale)
                .take(max_entries)
            {
                drips.push(entry.drip);
                flows.push(entry.flow);
            }
        }
        SawSnapshot {
            drips,
            flows,
            converged,
        }
    }

    /// The live-path prediction over a start-of-shot snapshot —
    /// `WeightProcessor::getExpectedDrip` (`weightprocessor.cpp:587-615`).
    /// An associated fn (no `&self`): mid-shot prediction must not touch the
    /// store, exactly like Decenza's worker-thread snapshot.
    ///
    /// Empty snapshot → the first-shot default
    /// `min(flow × (sensor_lag_s + 0.1), 8.0)` (`weightprocessor.cpp:592-596`,
    /// matching de1app's pre-learning behaviour). Otherwise the kernel runs
    /// over the first `min(len, converged ? 12 : 8)` entries with recency
    /// 10.0 → (converged ? 3.0 : 1.0) (`weightprocessor.cpp:598-607`); a
    /// `None` kernel result (every entry's flow far from `current_flow`)
    /// falls back to the same default (`:609-613`).
    #[must_use]
    pub fn expected_drip(snapshot: &SawSnapshot, current_flow: f64, sensor_lag_s: f64) -> f64 {
        let default = (current_flow * (sensor_lag_s + DE1_STOP_LAG_S)).min(MAX_DEFAULT_DRIP_G);
        if snapshot.drips.is_empty() {
            return default;
        }
        let max_entries = if snapshot.converged {
            SNAPSHOT_ENTRIES_CONVERGED
        } else {
            SNAPSHOT_ENTRIES_ADAPTING
        };
        let recency_min = if snapshot.converged {
            RECENCY_MIN_CONVERGED
        } else {
            RECENCY_MIN_ADAPTING
        };
        // Truncate each slice independently (QVector::mid caps at size); a
        // hand-built length mismatch then yields None → the default.
        let drips = &snapshot.drips[..snapshot.drips.len().min(max_entries)];
        let flows = &snapshot.flows[..snapshot.flows.len().min(max_entries)];
        weighted_drip_prediction(drips, flows, current_flow, RECENCY_MAX, recency_min)
            .unwrap_or(default)
    }

    /// Expected drip for a pair via the store — `getExpectedDripFor`
    /// (`settings_calibration.cpp:728-766`), used post-shot for learning
    /// feedback (and by the converged outlier gate, minus the last leg).
    ///
    /// Chain: per-pair kernel over the ≤ [`PER_PAIR_READ_ENTRIES`] newest
    /// medians with recency 10.0 → 3.0 (`:737-758`) → bootstrap
    /// `min(flow × lag, 8.0)` when the scale has one (`:761-764`) → the
    /// sensor-lag default `min(flow × (sensor_lag_s + 0.1), 8.0)` (`:765`).
    #[must_use]
    pub fn expected_drip_for(
        &self,
        profile: &str,
        scale: &str,
        flow: f64,
        sensor_lag_s: f64,
    ) -> f64 {
        if let Some(prediction) = self.per_pair_prediction(profile, scale, flow) {
            return prediction;
        }
        if let Some(bootstrap) = self.bootstrap(scale) {
            return (flow * bootstrap).min(MAX_DEFAULT_DRIP_G);
        }
        (flow * (sensor_lag_s + DE1_STOP_LAG_S)).min(MAX_DEFAULT_DRIP_G)
    }

    /// The per-pair kernel leg shared by
    /// [`expected_drip_for`](Self::expected_drip_for) and the converged
    /// outlier gate: the ≤ [`PER_PAIR_READ_ENTRIES`] newest committed
    /// medians, recency 10.0 → 3.0 (`settings_calibration.cpp:731-758` —
    /// `recencyMin` is fixed at 3.0 because per-pair history only exists
    /// after graduation).
    fn per_pair_prediction(&self, profile: &str, scale: &str, flow: f64) -> Option<f64> {
        let history = self.pair_history.get(&pair_key(profile, scale))?;
        if history.len() < MIN_MEDIANS_FOR_GRADUATION {
            return None;
        }
        let mut drips = Vec::with_capacity(PER_PAIR_READ_ENTRIES);
        let mut flows = Vec::with_capacity(PER_PAIR_READ_ENTRIES);
        for entry in history.iter().rev().take(PER_PAIR_READ_ENTRIES) {
            drips.push(entry.drip);
            flows.push(entry.flow);
        }
        weighted_drip_prediction(&drips, &flows, flow, RECENCY_MAX, RECENCY_MIN_CONVERGED)
    }

    /// Expected drip for gate purposes — `getExpectedDripFor` without the
    /// sensor-lag leg (this API carries no sensor lag): per-pair kernel →
    /// bootstrap → `None`, and `None` means *skip the gate* rather than
    /// reject (see the module docs).
    fn gate_expected_drip(&self, profile: &str, scale: &str, flow: f64) -> Option<f64> {
        if let Some(prediction) = self.per_pair_prediction(profile, scale, flow) {
            return Some(prediction);
        }
        self.bootstrap(scale)
            .map(|lag| (flow * lag).min(MAX_DEFAULT_DRIP_G))
    }

    /// The scale's bootstrap lag, if set and positive — Decenza reads the
    /// QSettings key with a 0.0 default and treats `> 0.0` as "present"
    /// (`settings_calibration.cpp:660-663`, `:678`, `:723-724`, `:761-762`).
    fn bootstrap(&self, scale: &str) -> Option<f64> {
        self.bootstrap_lag
            .get(scale)
            .copied()
            .filter(|lag| *lag > 0.0)
    }

    /// Which tier would drive predictions for this pair — `sawModelSource`
    /// (`settings_calibration.cpp:672-684`). In precedence order:
    /// `"perProfile"` (≥ [`MIN_MEDIANS_FOR_GRADUATION`] committed medians),
    /// `"globalBootstrap"` (scale bootstrap > 0), `"globalPool"` (any pool
    /// entry for the scale), else `"scaleDefault"`. Decenza logs this at
    /// shot start for accuracy analysis (`main.cpp:789-795`).
    #[must_use]
    pub fn model_source(&self, profile: &str, scale: &str) -> &'static str {
        if self
            .pair_history
            .get(&pair_key(profile, scale))
            .is_some_and(|history| history.len() >= MIN_MEDIANS_FOR_GRADUATION)
        {
            return "perProfile";
        }
        if self.bootstrap(scale).is_some() {
            return "globalBootstrap";
        }
        if self.global_pool.iter().any(|entry| entry.scale == scale) {
            return "globalPool";
        }
        "scaleDefault"
    }

    /// Wipe every tier — `resetSawLearning`
    /// (`settings_calibration.cpp:549-559` removes all four QSettings keys).
    pub fn reset(&mut self) {
        *self = SawLearningModel::default();
    }

    /// Wipe one pair's committed history and pending batch —
    /// `resetSawLearningForProfile` (`settings_calibration.cpp:566-589`).
    /// The global pool and bootstrap are untouched, exactly as in Decenza.
    pub fn reset_pair(&mut self, profile: &str, scale: &str) {
        let key = pair_key(profile, scale);
        self.pair_history.remove(&key);
        self.pending_batch.remove(&key);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Tolerance for float comparisons — the model's sums are a handful of
    /// terms, so 1e-9 absolute is generous.
    fn assert_close(actual: f64, expected: f64) {
        assert!(
            (actual - expected).abs() < 1e-9,
            "expected {expected}, got {actual}"
        );
    }

    /// Push one raw learning point with benign defaults.
    fn add(model: &mut SawLearningModel, profile: &str, drip: f64, flow: f64, overshoot: f64) {
        model.add_learning_point(profile, "s", drip, flow, overshoot, 1);
    }

    /// Commit one batch of three identical raws for (profile, "s") — zero
    /// dispersion, so it always survives the batch gates.
    fn commit_identical_batch(
        model: &mut SawLearningModel,
        profile: &str,
        drip: f64,
        flow: f64,
        overshoot: f64,
    ) {
        for _ in 0..BATCH_SIZE {
            add(model, profile, drip, flow, overshoot);
        }
    }

    /// A hand-built committed-median entry for direct store injection.
    fn median_entry(profile: &str, scale: &str, drip: f64, flow: f64, overshoot: f64) -> SawEntry {
        SawEntry {
            drip,
            flow,
            overshoot,
            scale: scale.to_owned(),
            profile: profile.to_owned(),
            ts: 0,
            batch_size: Some(3),
        }
    }

    // ===== kernel =====

    #[test]
    fn kernel_single_matching_entry_returns_its_drip() {
        // count−1 = 0 → denom clamps to 1; flow diff 0 → flow weight 1;
        // the single weight cancels → the entry's drip, unclamped at 3.0.
        let p = weighted_drip_prediction(&[3.0], &[2.0], 2.0, 10.0, 1.0);
        assert_close(p.unwrap(), 3.0);
    }

    #[test]
    fn kernel_sigma_weighting_spot_value() {
        // Two entries, recency 10→1 (denom 1 → weights 10 and 1).
        // Entry 1 is 0.5 g/s off the query flow: exponent = 0.5²/0.125 = 2,
        // so w1 = 1·e⁻². Prediction = (2·10 + 4·e⁻²)/(10 + e⁻²).
        let e2 = (-2.0_f64).exp();
        let expected = (2.0 * 10.0 + 4.0 * e2) / (10.0 + e2);
        let p = weighted_drip_prediction(&[2.0, 4.0], &[2.0, 2.5], 2.0, 10.0, 1.0);
        assert_close(p.unwrap(), expected);
    }

    #[test]
    fn kernel_one_sigma_gap_weights_by_exp_half() {
        // A 1σ (0.25 g/s) flow gap must weight by exp(−0.5): with uniform
        // recency (5,5) and drips {0, 10}, prediction = 10·e^{−½}/(1+e^{−½}).
        let e_half = (-0.5_f64).exp();
        let expected = 10.0 * e_half / (1.0 + e_half);
        let p = weighted_drip_prediction(&[0.0, 10.0], &[2.0, 2.25], 2.0, 5.0, 5.0);
        assert_close(p.unwrap(), expected);
    }

    #[test]
    fn kernel_recency_is_linear_newest_to_oldest() {
        // Three same-flow entries, recency 10→1: step = 4.5 → weights
        // 10, 5.5, 1. Drips {1,2,3} → (10 + 11 + 3)/16.5 = 24/16.5.
        let p = weighted_drip_prediction(&[1.0, 2.0, 3.0], &[2.0, 2.0, 2.0], 2.0, 10.0, 1.0);
        assert_close(p.unwrap(), 24.0 / 16.5);
    }

    #[test]
    fn kernel_uniform_recency_reduces_to_mean() {
        // recency_max == recency_min → recency drops out; same flows → the
        // unweighted mean.
        let p = weighted_drip_prediction(&[1.0, 2.0, 3.0], &[1.5, 1.5, 1.5], 1.5, 5.0, 5.0);
        assert_close(p.unwrap(), 2.0);
    }

    #[test]
    fn kernel_clamps_into_half_gram_to_twenty_band() {
        let high = weighted_drip_prediction(&[50.0], &[2.0], 2.0, 10.0, 1.0);
        assert_close(high.unwrap(), MAX_DRIP_PREDICTION);
        let low = weighted_drip_prediction(&[0.1], &[2.0], 2.0, 10.0, 1.0);
        assert_close(low.unwrap(), MIN_DRIP_PREDICTION);
    }

    #[test]
    fn kernel_none_when_total_weight_tiny() {
        // Decenza's σ-signature regression case (tst_sawprediction.cpp):
        // trained at 1.5 g/s, queried at 2.5 → flow weight e⁻⁸ ≈ 3.4e-4,
        // total ≈ 3.4e-3 < 0.01 → fall back.
        assert_eq!(
            weighted_drip_prediction(&[2.0], &[1.5], 2.5, 10.0, 3.0),
            None
        );
    }

    #[test]
    fn kernel_none_on_empty_or_mismatched_inputs() {
        assert_eq!(weighted_drip_prediction(&[], &[], 2.0, 10.0, 1.0), None);
        assert_eq!(weighted_drip_prediction(&[1.0], &[], 2.0, 10.0, 1.0), None);
        assert_eq!(
            weighted_drip_prediction(&[1.0], &[1.0, 2.0], 2.0, 10.0, 1.0),
            None
        );
    }

    // ===== add_learning_point gates =====

    #[test]
    fn gate_rejects_negative_drip_and_negative_flow() {
        let mut model = SawLearningModel::default();
        add(&mut model, "p", -0.1, 2.0, 0.0);
        add(&mut model, "p", 1.0, -0.1, 0.0);
        assert!(model.pending_batch.is_empty());
    }

    #[test]
    fn gate_rejects_implied_lag_above_four_seconds() {
        let mut model = SawLearningModel::default();
        add(&mut model, "p", 8.1, 2.0, 0.0); // 4.05 s > 4.0 → reject
        assert!(model.pending_batch.is_empty());
        add(&mut model, "p", 8.0, 2.0, 0.0); // exactly 4.0 s → accept
        assert_eq!(model.pending_batch["p::s"].len(), 1);
    }

    #[test]
    fn gate_skips_implied_lag_check_at_low_flow() {
        // flow ≤ 0.5 → the ratio is meaningless, so the gate is skipped.
        let mut model = SawLearningModel::default();
        add(&mut model, "p", 10.0, 0.5, 0.0);
        assert_eq!(model.pending_batch["p::s"].len(), 1);
    }

    /// Build a converged model: three committed batches for ("p", "s") at
    /// drip 2.0 g / flow 2.0 g/s / overshoot 0.5 g.
    fn converged_model() -> SawLearningModel {
        let mut model = SawLearningModel::default();
        for _ in 0..3 {
            commit_identical_batch(&mut model, "p", 2.0, 2.0, 0.5);
        }
        assert!(model.is_converged("s"));
        model
    }

    #[test]
    fn gate_converged_outlier_rejects_far_drip() {
        let mut model = converged_model();
        // Expected ≈ 2.0 (per-pair medians), threshold = max(3.0, 2.0) = 3.0.
        add(&mut model, "p", 6.0, 2.0, 0.0); // |6−2| = 4 > 3 → reject
        assert!(!model.pending_batch.contains_key("p::s"));
        add(&mut model, "p", 4.5, 2.0, 0.0); // |4.5−2| = 2.5 ≤ 3 → accept
        assert_eq!(model.pending_batch["p::s"].len(), 1);
    }

    #[test]
    fn gate_auto_reset_candidate_bypasses_outlier_check() {
        let mut model = converged_model();
        // Same outlier drip as above, but overshoot < −6 g → candidate →
        // the gate must not defend the stale converged model.
        add(&mut model, "p", 6.0, 2.0, -7.0);
        assert_eq!(model.pending_batch["p::s"].len(), 1);
    }

    #[test]
    fn gate_skipped_without_per_pair_history_or_bootstrap() {
        let mut model = converged_model();
        // One trained pair only → the bootstrap needs ≥ 2 pairs, so it is
        // unset; profile "q" has no history → the gate has no expected drip
        // and must skip (not reject).
        assert!(model.bootstrap("s").is_none());
        add(&mut model, "q", 7.9, 2.0, 0.0);
        assert_eq!(model.pending_batch["q::s"].len(), 1);
    }

    // ===== batch commit =====

    #[test]
    fn batch_below_three_stays_pending() {
        let mut model = SawLearningModel::default();
        add(&mut model, "p", 2.0, 2.0, 0.0);
        add(&mut model, "p", 2.2, 2.0, 0.0);
        assert_eq!(model.pending_batch["p::s"].len(), 2);
        assert!(model.pair_history.is_empty());
        assert!(model.global_pool.is_empty());
    }

    #[test]
    fn batch_commits_elementwise_medians_at_three() {
        let mut model = SawLearningModel::default();
        model.add_learning_point("p", "s", 2.0, 2.0, 0.5, 1);
        model.add_learning_point("p", "s", 2.4, 2.2, 0.3, 2);
        model.add_learning_point("p", "s", 2.2, 1.8, 0.7, 3);

        let history = &model.pair_history["p::s"];
        assert_eq!(history.len(), 1);
        let committed = &history[0];
        assert_close(committed.drip, 2.2);
        assert_close(committed.flow, 2.0);
        assert_close(committed.overshoot, 0.5);
        assert_eq!(committed.batch_size, Some(3));
        assert_eq!(committed.ts, 3);

        // Mirrored into the global pool; pending cleared.
        assert_eq!(model.global_pool.len(), 1);
        assert_eq!(model.global_pool[0], *committed);
        assert!(!model.pending_batch.contains_key("p::s"));
    }

    #[test]
    fn batch_dropped_when_lag_dispersion_exceeds_band() {
        let mut model = SawLearningModel::default();
        // Lags 1.0, 1.0, 4.0 s; median lag = 2.0/2.0 = 1.0; |4−1| = 3 > 1.5.
        add(&mut model, "p", 2.0, 2.0, 0.0);
        add(&mut model, "p", 2.0, 2.0, 0.0);
        add(&mut model, "p", 8.0, 2.0, 0.0); // implied lag exactly 4.0 s passes gate 2
        assert!(model.pair_history.is_empty());
        assert!(model.global_pool.is_empty());
        assert!(!model.pending_batch.contains_key("p::s")); // pending cleared
    }

    #[test]
    fn low_flow_batch_commits_with_zero_median_lag() {
        // Flows ≤ 0.5 g/s form no lags, and median_lag falls back to 0 —
        // nothing to disperse, so the batch commits.
        let mut model = SawLearningModel::default();
        commit_identical_batch(&mut model, "p", 1.0, 0.3, 0.0);
        assert_eq!(model.pair_history["p::s"].len(), 1);
        assert_close(model.pair_history["p::s"][0].drip, 1.0);
    }

    #[test]
    fn pair_history_trims_to_ten_medians() {
        let mut model = SawLearningModel::default();
        for k in 0..11_u32 {
            let drip = 1.0 + 0.1 * f64::from(k);
            commit_identical_batch(&mut model, "p", drip, 2.0, 0.0);
        }
        let history = &model.pair_history["p::s"];
        assert_eq!(history.len(), MAX_PAIR_HISTORY);
        // Oldest (k=0, drip 1.0) dropped; k=1..=10 remain.
        assert_close(history[0].drip, 1.1);
        assert_close(history[9].drip, 2.0);
    }

    #[test]
    fn global_pool_trims_to_fifty() {
        let mut model = SawLearningModel::default();
        for _ in 0..51 {
            commit_identical_batch(&mut model, "p", 2.0, 2.0, 0.0);
        }
        assert_eq!(model.global_pool.len(), MAX_GLOBAL_POOL);
    }

    #[test]
    fn auto_resets_pair_after_two_consecutive_early_stop_medians() {
        let mut model = SawLearningModel::default();
        commit_identical_batch(&mut model, "p", 2.0, 2.0, -7.0);
        assert_eq!(model.pair_history["p::s"].len(), 1);
        // Second consecutive median < −6 g → history wiped, new median is
        // the sole baseline.
        commit_identical_batch(&mut model, "p", 2.0, 2.0, -7.0);
        assert_eq!(model.pair_history["p::s"].len(), 1);
        // The global pool keeps both medians — only the pair is wiped.
        assert_eq!(model.global_pool.len(), 2);
    }

    #[test]
    fn no_auto_reset_when_early_stops_are_not_consecutive() {
        let mut model = SawLearningModel::default();
        commit_identical_batch(&mut model, "p", 2.0, 2.0, -7.0);
        commit_identical_batch(&mut model, "p", 2.0, 2.0, 0.0);
        commit_identical_batch(&mut model, "p", 2.0, 2.0, -7.0);
        assert_eq!(model.pair_history["p::s"].len(), 3);
    }

    // ===== bootstrap =====

    #[test]
    fn bootstrap_requires_two_pairs_and_filters_scale() {
        let mut model = SawLearningModel::default();
        commit_identical_batch(&mut model, "a", 2.0, 2.0, 0.0); // lag 1.0
        assert!(model.bootstrap("s").is_none()); // one pair — no bootstrap

        // A pair on another scale must not count toward "s".
        model.add_learning_point("c", "other", 3.0, 2.0, 0.0, 1);
        model.add_learning_point("c", "other", 3.0, 2.0, 0.0, 2);
        model.add_learning_point("c", "other", 3.0, 2.0, 0.0, 3);
        assert!(model.bootstrap("s").is_none());

        commit_identical_batch(&mut model, "b", 3.0, 2.0, 0.0); // lag 1.5
        // Two "s" pairs → median of {1.0, 1.5} = 1.25 (even count averages).
        assert_close(model.bootstrap("s").unwrap(), 1.25);
    }

    #[test]
    fn bootstrap_iqr_fence_drops_outlier_lag_at_four_pairs() {
        let mut model = SawLearningModel::default();
        // Last-median lags per pair: 0.1, 2.0, 2.05, 2.1 s (flow 2.0).
        // Sorted q1 = lags[1] = 2.0, q3 = lags[3] = 2.1, IQR = 0.1 →
        // fence [1.85, 2.25] drops 0.1; median of {2.0, 2.05, 2.1} = 2.05.
        commit_identical_batch(&mut model, "p0", 0.2, 2.0, 0.0);
        commit_identical_batch(&mut model, "p1", 4.0, 2.0, 0.0);
        commit_identical_batch(&mut model, "p2", 4.1, 2.0, 0.0);
        commit_identical_batch(&mut model, "p3", 4.2, 2.0, 0.0);
        assert_close(model.bootstrap("s").unwrap(), 2.05);
    }

    #[test]
    fn bootstrap_keeps_prior_value_when_pairs_drop_below_two() {
        let mut model = SawLearningModel::default();
        commit_identical_batch(&mut model, "a", 2.0, 2.0, 0.0);
        commit_identical_batch(&mut model, "b", 3.0, 2.0, 0.0);
        assert_close(model.bootstrap("s").unwrap(), 1.25);

        // Remove pair b, then trigger a recompute via a fresh commit for a:
        // only one contributing pair remains → abort, keep 1.25.
        model.reset_pair("b", "s");
        commit_identical_batch(&mut model, "a", 2.0, 2.0, 0.0);
        assert_close(model.bootstrap("s").unwrap(), 1.25);
    }

    // ===== convergence =====

    /// Inject a global-pool entry directly (bypasses batching).
    fn push_pool(model: &mut SawLearningModel, scale: &str, drip: f64, overshoot: f64) {
        model
            .global_pool
            .push(median_entry("p", scale, drip, 2.0, overshoot));
    }

    #[test]
    fn not_converged_below_three_samples() {
        let mut model = SawLearningModel::default();
        assert!(!model.is_converged("s"));
        push_pool(&mut model, "s", 2.0, 0.1);
        push_pool(&mut model, "s", 2.0, 0.1);
        assert!(!model.is_converged("s"));
    }

    #[test]
    fn converged_when_mean_abs_overshoot_under_band() {
        let mut model = SawLearningModel::default();
        for _ in 0..3 {
            push_pool(&mut model, "s", 2.0, 0.5);
        }
        assert!(model.is_converged("s"));
    }

    #[test]
    fn not_converged_when_mean_abs_overshoot_high() {
        let mut model = SawLearningModel::default();
        push_pool(&mut model, "s", 2.0, 2.0);
        push_pool(&mut model, "s", 2.0, -2.0);
        push_pool(&mut model, "s", 2.0, 2.0);
        assert!(!model.is_converged("s")); // mean |overshoot| = 2.0 ≥ 1.5
    }

    #[test]
    fn divergence_override_all_positive_forces_adaptation() {
        let mut model = SawLearningModel::default();
        // Mean |overshoot| = (0.1 + 3·1.2)/4 = 0.925 < 1.5 → converged, but
        // the newest 3 are all > +1 g → systematically off → forced false.
        push_pool(&mut model, "s", 2.0, 0.1);
        for _ in 0..3 {
            push_pool(&mut model, "s", 2.0, 1.2);
        }
        assert!(!model.is_converged("s"));
    }

    #[test]
    fn divergence_override_all_negative_forces_adaptation() {
        let mut model = SawLearningModel::default();
        push_pool(&mut model, "s", 2.0, 0.1);
        for _ in 0..3 {
            push_pool(&mut model, "s", 2.0, -1.2);
        }
        assert!(!model.is_converged("s"));
    }

    #[test]
    fn mixed_sign_overshoots_do_not_trigger_divergence() {
        let mut model = SawLearningModel::default();
        push_pool(&mut model, "s", 2.0, 1.2);
        push_pool(&mut model, "s", 2.0, -1.2);
        push_pool(&mut model, "s", 2.0, 1.2);
        assert!(model.is_converged("s")); // mean 1.2 < 1.5, signs mixed
    }

    #[test]
    fn convergence_window_is_last_five() {
        let mut model = SawLearningModel::default();
        // Two huge old overshoots pushed out of the 5-entry window by five
        // clean shots.
        push_pool(&mut model, "s", 2.0, 10.0);
        push_pool(&mut model, "s", 2.0, 10.0);
        for _ in 0..5 {
            push_pool(&mut model, "s", 2.0, 0.0);
        }
        assert!(model.is_converged("s"));
    }

    #[test]
    fn convergence_filters_by_scale() {
        let mut model = SawLearningModel::default();
        for _ in 0..3 {
            push_pool(&mut model, "other", 2.0, 0.0);
        }
        assert!(!model.is_converged("s"));
        assert!(model.is_converged("other"));
    }

    // ===== snapshot =====

    #[test]
    fn snapshot_prefers_per_pair_medians_newest_first() {
        let mut model = SawLearningModel::default();
        commit_identical_batch(&mut model, "p", 2.0, 2.0, 0.0);
        commit_identical_batch(&mut model, "p", 3.0, 2.5, 0.0);
        let snap = model.snapshot("p", "s");
        assert_eq!(snap.drips, vec![3.0, 2.0]); // newest first
        assert_eq!(snap.flows, vec![2.5, 2.0]);
        assert!(!snap.converged);
    }

    #[test]
    fn snapshot_falls_back_to_global_pool_filtered_by_scale() {
        let mut model = SawLearningModel::default();
        push_pool(&mut model, "other", 9.0, 0.0);
        push_pool(&mut model, "s", 2.0, 0.0);
        push_pool(&mut model, "s", 3.0, 0.0);
        // Profile "q" has no pair history → global pool, "s" entries only.
        let snap = model.snapshot("q", "s");
        assert_eq!(snap.drips, vec![3.0, 2.0]);
    }

    #[test]
    fn snapshot_empty_when_untrained() {
        let model = SawLearningModel::default();
        let snap = model.snapshot("p", "s");
        assert!(snap.drips.is_empty());
        assert!(snap.flows.is_empty());
        assert!(!snap.converged);
    }

    #[test]
    fn snapshot_caps_at_eight_while_adapting() {
        let mut model = SawLearningModel::default();
        // Alternating ±2 g overshoots keep the scale non-converged.
        for k in 0..10_u32 {
            let overshoot = if k % 2 == 0 { 2.0 } else { -2.0 };
            push_pool(&mut model, "s", f64::from(k), overshoot);
        }
        let snap = model.snapshot("p", "s");
        assert!(!snap.converged);
        assert_eq!(snap.drips.len(), SNAPSHOT_ENTRIES_ADAPTING);
        assert_close(snap.drips[0], 9.0); // newest
        assert_close(snap.drips[7], 2.0); // 8th-newest
    }

    #[test]
    fn snapshot_caps_at_twelve_when_converged() {
        let mut model = SawLearningModel::default();
        for k in 0..13_u32 {
            push_pool(&mut model, "s", f64::from(k), 0.0);
        }
        let snap = model.snapshot("p", "s");
        assert!(snap.converged);
        assert_eq!(snap.drips.len(), SNAPSHOT_ENTRIES_CONVERGED);
        assert_close(snap.drips[0], 12.0);
        assert_close(snap.drips[11], 1.0);
    }

    // ===== expected_drip (live path) =====

    #[test]
    fn expected_drip_empty_snapshot_uses_sensor_lag_default() {
        let snap = SawSnapshot::default();
        // 2.0 g/s × (0.4 + 0.1) s = 1.0 g.
        assert_close(SawLearningModel::expected_drip(&snap, 2.0, 0.4), 1.0);
    }

    #[test]
    fn expected_drip_default_caps_at_eight_grams() {
        let snap = SawSnapshot::default();
        // 20 g/s × 0.5 s = 10 g → capped at 8.
        assert_close(SawLearningModel::expected_drip(&snap, 20.0, 0.4), 8.0);
    }

    #[test]
    fn expected_drip_runs_kernel_over_snapshot() {
        let snap = SawSnapshot {
            drips: vec![2.0],
            flows: vec![2.0],
            converged: false,
        };
        assert_close(SawLearningModel::expected_drip(&snap, 2.0, 0.4), 2.0);
    }

    #[test]
    fn expected_drip_kernel_none_falls_back_to_default() {
        // Every entry's flow far from the query → kernel None → default
        // 1.0 × (0.38 + 0.1) = 0.48 g (defaults are NOT clamped to 0.5).
        let snap = SawSnapshot {
            drips: vec![5.0],
            flows: vec![10.0],
            converged: false,
        };
        assert_close(SawLearningModel::expected_drip(&snap, 1.0, 0.38), 0.48);
    }

    #[test]
    fn expected_drip_uses_eight_entries_adapting_twelve_converged() {
        // 12 entries: first 8 (newest) drip 2.0, last 4 drip 10.0, all at
        // the query flow. Adapting reads 8 → exactly 2.0. Converged reads
        // all 12 with recency 10→3 (step 7/11, Σw = 78, Σw last-4 = 174/11)
        // → (2·(78 − 174/11) + 10·174/11)/78 = 3108/858.
        let mut drips = vec![2.0; 8];
        drips.extend([10.0; 4]);
        let flows = vec![2.0; 12];

        let adapting = SawSnapshot {
            drips: drips.clone(),
            flows: flows.clone(),
            converged: false,
        };
        assert_close(SawLearningModel::expected_drip(&adapting, 2.0, 0.4), 2.0);

        let converged = SawSnapshot {
            drips,
            flows,
            converged: true,
        };
        assert_close(
            SawLearningModel::expected_drip(&converged, 2.0, 0.4),
            3108.0 / 858.0,
        );
    }

    // ===== expected_drip_for (store read path) =====

    #[test]
    fn expected_drip_for_uses_three_newest_medians() {
        let mut model = SawLearningModel::default();
        // Oldest median has a wildly different drip; only the newest 3 are
        // read, so it must not influence the prediction.
        let history = vec![
            median_entry("p", "s", 9.0, 2.0, 0.0),
            median_entry("p", "s", 2.0, 2.0, 0.0),
            median_entry("p", "s", 2.0, 2.0, 0.0),
            median_entry("p", "s", 2.0, 2.0, 0.0),
        ];
        model.pair_history.insert("p::s".to_owned(), history);
        assert_close(model.expected_drip_for("p", "s", 2.0, 0.4), 2.0);
    }

    #[test]
    fn expected_drip_for_falls_back_to_bootstrap() {
        let mut model = SawLearningModel::default();
        model.bootstrap_lag.insert("s".to_owned(), 1.25);
        // No pair history → bootstrap: 2.0 × 1.25 = 2.5 g.
        assert_close(model.expected_drip_for("p", "s", 2.0, 0.4), 2.5);
        // Bootstrap leg caps at 8 g too: 20 × 1.25 = 25 → 8.
        assert_close(model.expected_drip_for("p", "s", 20.0, 0.4), 8.0);
    }

    #[test]
    fn expected_drip_for_kernel_none_falls_through_to_bootstrap() {
        let mut model = SawLearningModel::default();
        model.pair_history.insert(
            "p::s".to_owned(),
            vec![median_entry("p", "s", 5.0, 10.0, 0.0)],
        );
        model.bootstrap_lag.insert("s".to_owned(), 1.0);
        // Query at 1.0 g/s: the pair's only median trained at 10 g/s →
        // kernel None → bootstrap min(1.0 × 1.0, 8.0) = 1.0 g.
        assert_close(model.expected_drip_for("p", "s", 1.0, 0.4), 1.0);
    }

    #[test]
    fn expected_drip_for_sensor_lag_default_when_untrained() {
        let model = SawLearningModel::default();
        // 2.0 × (0.38 + 0.1) = 0.96 g.
        assert_close(model.expected_drip_for("p", "s", 2.0, 0.38), 0.96);
        // The default caps at 8 g.
        assert_close(model.expected_drip_for("p", "s", 30.0, 0.38), 8.0);
    }

    // ===== model_source =====

    #[test]
    fn model_source_walks_the_fallback_chain() {
        let mut model = SawLearningModel::default();
        assert_eq!(model.model_source("p", "s"), "scaleDefault");

        push_pool(&mut model, "s", 2.0, 0.0);
        assert_eq!(model.model_source("p", "s"), "globalPool");
        assert_eq!(model.model_source("p", "other"), "scaleDefault");

        model.bootstrap_lag.insert("s".to_owned(), 1.0);
        assert_eq!(model.model_source("p", "s"), "globalBootstrap");

        model.pair_history.insert(
            "p::s".to_owned(),
            vec![median_entry("p", "s", 2.0, 2.0, 0.0)],
        );
        assert_eq!(model.model_source("p", "s"), "perProfile");
        // Another profile on the same scale still reads the bootstrap.
        assert_eq!(model.model_source("q", "s"), "globalBootstrap");
    }

    // ===== reset =====

    #[test]
    fn reset_clears_every_tier() {
        let mut model = SawLearningModel::default();
        commit_identical_batch(&mut model, "a", 2.0, 2.0, 0.0);
        commit_identical_batch(&mut model, "b", 3.0, 2.0, 0.0);
        add(&mut model, "c", 2.0, 2.0, 0.0); // leave a pending sample
        model.reset();
        assert_eq!(model, SawLearningModel::default());
    }

    #[test]
    fn reset_pair_clears_only_that_pair() {
        let mut model = SawLearningModel::default();
        commit_identical_batch(&mut model, "a", 2.0, 2.0, 0.0);
        commit_identical_batch(&mut model, "b", 3.0, 2.0, 0.0);
        add(&mut model, "a", 2.0, 2.0, 0.0); // pending for a
        let bootstrap_before = model.bootstrap("s").unwrap();

        model.reset_pair("a", "s");
        assert!(!model.pair_history.contains_key("a::s"));
        assert!(!model.pending_batch.contains_key("a::s"));
        // Pair b, the global pool, and the bootstrap survive.
        assert_eq!(model.pair_history["b::s"].len(), 1);
        assert_eq!(model.global_pool.len(), 2);
        assert_close(model.bootstrap("s").unwrap(), bootstrap_before);
    }

    // ===== JSON persistence =====

    #[test]
    fn json_round_trip_preserves_model() {
        let mut model = SawLearningModel::default();
        commit_identical_batch(&mut model, "a", 2.0, 2.0, 0.5);
        commit_identical_batch(&mut model, "b", 3.0, 2.5, -0.5);
        add(&mut model, "c", 1.8, 2.2, 0.1); // pending raw sample

        let json = serde_json::to_string(&model).unwrap();
        let restored: SawLearningModel = serde_json::from_str(&json).unwrap();
        assert_eq!(restored, model);
    }

    #[test]
    fn empty_json_deserializes_to_default() {
        let restored: SawLearningModel = serde_json::from_str("{}").unwrap();
        assert_eq!(restored, SawLearningModel::default());
    }

    #[test]
    fn json_uses_camel_case_keys() {
        let mut model = SawLearningModel::default();
        commit_identical_batch(&mut model, "a", 2.0, 2.0, 0.5);
        add(&mut model, "c", 1.8, 2.2, 0.1);
        let json = serde_json::to_string(&model).unwrap();
        for key in [
            "pairHistory",
            "pendingBatch",
            "globalPool",
            "bootstrapLag",
            "batchSize",
        ] {
            assert!(json.contains(key), "missing key {key} in {json}");
        }
        // Raw pending entries carry no batchSize (skip_serializing_if).
        let pending = serde_json::to_string(&model.pending_batch).unwrap();
        assert!(!pending.contains("batchSize"));
    }

    // ===== end-to-end =====

    #[test]
    fn learned_pair_drives_snapshot_and_live_prediction() {
        let mut model = SawLearningModel::default();
        for _ in 0..3 {
            commit_identical_batch(&mut model, "p", 2.2, 2.0, 0.3);
        }
        assert_eq!(model.model_source("p", "s"), "perProfile");
        assert!(model.is_converged("s"));

        let snap = model.snapshot("p", "s");
        assert!(snap.converged);
        assert_eq!(snap.drips.len(), 3);
        // All medians identical → the live prediction is the learned drip.
        assert_close(SawLearningModel::expected_drip(&snap, 2.0, 0.4), 2.2);
        // The post-shot read path agrees.
        assert_close(model.expected_drip_for("p", "s", 2.0, 0.4), 2.2);
    }
}
