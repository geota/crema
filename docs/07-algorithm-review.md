# 07 — Algorithm Review of the Legacy DE1 App

## Purpose

Crema is a clean-room Rust reimplementation of the Decent Espresso DE1 tablet app.
One stated goal is to use modern best-practice algorithms rather than slavishly port
the legacy Tcl ones — but *only* where a genuinely better algorithm exists.

This document surveys the **algorithmic core** of the legacy app (`de1app/de1plus/`):
signal processing, estimation, numerical methods, control logic, timing, data
structures, scheduling, and integrity checks. UI / skin / rendering / charting code
is deliberately out of scope.

For each algorithm we record: (1) what it does and where, (2) the legacy approach,
(3) whether a modern alternative is worth it, and (4) a verdict —
**REPLACE** / **KEEP** / **CONSIDER**.

The bias here is conservative. Most of this code does not need a fancier algorithm.
Only genuine improvements are flagged.

---

## 1. Scale weight / flow estimation (LSLR, finite-difference, median)

**Where:** `device_scale.tcl:591–1135` (`::device::scale::history` namespace) —
`_lslr_core`, `flow_fd`, `weight_median`, `flow_median`, `median`.

**What it does:** Estimates instantaneous drink weight and mass-flow rate from a
shift register of raw scale samples. The legacy app offers three runtime-selectable
estimator families: raw passthrough, least-squares linear regression over an
11-sample window (LSLR; `_lslr_core` at line 847), centered finite-difference
(`flow_fd` at line 808), and difference-of-medians (`flow_median` at line 973).

**Status: ALREADY ADDRESSED — do not re-recommend.**

Per the review brief, this area has already been evaluated for Crema. Crema
implemented **both** the legacy offset-median estimator (`flow_median`) **and** a
**Theil–Sen robust-regression** estimator, runtime-switchable. That is the right
outcome: Theil–Sen gives the noise rejection of regression with the outlier
robustness of the median, and keeping the legacy median preserves behavioral
parity for users who calibrated against it.

**Verdict: KEEP (already done).** No further action. Listed here only for
completeness so the survey is exhaustive.

One small note for the Crema implementation, not a new algorithm recommendation:
the legacy `_lslr_core` (lines 871–872) builds `sum_n` and `sum_nn` with **integer**
arithmetic (`$n_est * ($n_est - 1) / 2`). For the fixed window of 11 this is exact,
but if the window size is ever made configurable to an even value the integer
division could silently truncate. Crema should compute these in floating point or
assert the window size — trivial, just hygiene.

---

## 2. Scale reporting-period estimator (EMA of inter-arrival times)

**Where:** `device_scale.tcl:493–581` (`::device::scale::period` namespace),
specifically `estimate_update` at line 564.

**What it does:** Estimates the *actual* scale sample period (the BLE notification
cadence) instead of assuming a fixed 10 Hz. The flow estimators divide by this
period, so a wrong period directly scales the flow-rate error.

**Legacy approach:** A single-pole exponential moving average (EMA) of the
inter-arrival delta:

```
moving_average = moving_average * (1 - k) + delta * k
```

with `k = new_value_weight = 0.0001` (a time constant τ ≈ 10,000 samples ≈ 17
minutes). Deltas above a 0.35 s threshold or ≤ 0 are rejected before the update.
The estimate is persisted per-scale in settings so subsequent connections start
warm.

**Modern alternative — worth considering?** The very long τ is a deliberate
response to the Skale 2's high inter-arrival variance (the code comments cite a
basecamp plot). An EMA with a 17-minute time constant is effectively a slow mean
estimator. The legitimate weaknesses:

- **Cold-start lag.** A brand-new scale with no persisted estimate uses the
  100 ms default and takes minutes of pouring to converge — but a shot only lasts
  ~30 s, so the *first several shots* on a new scale run on a not-yet-converged
  period. The persistence mitigates this across sessions but not within the first.
- **The mean is the wrong statistic.** Scales like the Skale 2 clock updates on a
  ~150 ms grid but frequently double-up or skip slots, producing a multi-modal
  delta distribution (≈150 ms and ≈300 ms). The mean of that distribution is not
  the true clock period; the **mode**, or a clustered/quantized estimate, is.
- An EMA reacts to *no* structure in the data — a scale that genuinely changes its
  cadence (some scales do, between idle and active) is tracked only at the 17-min
  time constant.

A better fit is a **two-stage estimator**: a short warm-up window (e.g. first
~30 valid deltas) using the **median** delta for a fast, robust initial period,
then either freeze it or hand off to a slow EMA for drift. Alternatively, snap the
estimate to the nearest plausible grid value (quantization to the scale's known
clock). This removes cold-start error on the first shot and is more accurate on
multi-modal scales. Cost: a few lines and one extra buffer; no real complexity.

**Verdict: CONSIDER.** The EMA is not *wrong* and the persistence largely hides
the cold-start problem. But a median-warmup-then-EMA is strictly better for the
first-shot-on-a-new-scale case and for multi-modal scales, at near-zero cost.
Low effort, modest accuracy win. Worth doing if/when Crema touches this path.

---

## 3. Line-frequency estimation from packet arrival timing

**Where:** `de1_de1.tcl:324–391` (`::de1::_line_frequency_estimator`).

**What it does:** The DE1 firmware reports a "SampleTime" measured in mains
half-cycles (a 100 Hz or 120 Hz counter). The app needs to know whether the mains
is 50 Hz or 60 Hz to convert SampleTime deltas into real seconds (used for volume
integration — see §4). When the user hasn't set the line frequency, the app infers
it from the data.

**Legacy approach:** A **two-point slope estimate**. It records the first
`(update_received, SampleTime)` pair, waits for a ≥10 s window to elapse, then takes
a single second pair and computes:

```
hz_est = (Δhalf_cycles / 2) / Δwall_clock_seconds
```

It classifies 45–55 → 50 Hz, 55–65 → 60 Hz, else error. It also defers if the BLE
transmit queue depth > 1 (a "too busy for good timing" guard) and correctly unwraps
the 16-bit counter rollover. It then stops — one estimate, done.

**Modern alternative — worth considering?** The legacy comment block (lines
338–342) does the error analysis honestly: it only needs to *distinguish* 50 from
60 Hz (a 20 % gap), and a single 10 s window with worst-case ±500 ms endpoint jitter
is a 5 % error — comfortably inside the 20 % margin. Given that the task is a
**binary classification with a wide margin**, a fancier estimator (linear
regression over many samples, a phase-locked counter, etc.) buys nothing — it would
reduce the estimate's *variance* but the decision boundary is so far from both
candidates that variance is irrelevant.

The one genuine fragility: a two-point estimate is sensitive to a single bad
endpoint sample (e.g. one delayed packet at exactly the start or end). A trivially
more robust version uses the **first and last of a handful of samples**, or a
least-squares slope over the whole 10 s window — same wall-clock cost, immune to a
single outlier endpoint. But because the margin is 20 %, even a corrupted endpoint
rarely flips the classification.

**Verdict: KEEP.** The algorithm is correctly matched to the problem (wide-margin
binary classification). A regression slope would be marginally more robust but the
existing busy-queue guard plus the wide decision margin already make
misclassification very unlikely. Not worth the change. (Crema should keep the
"prefer firmware-reported value if present" precedence — `line_frequency_nom` at
`de1_de1.tcl:247` — which is the real correctness lever.)

---

## 4. Volume integration from flow (rectangle rule)

**Where:** `de1_de1.tcl:558–595` (`from_shotvalue`).

**What it does:** Integrates the firmware-reported `GroupFlow` over time to produce
total dispensed water volume (`::de1(volume)`), and per-phase volumes
(preinfusion / pour). This volume drives the "stop-at-volume" (SAV) feature.

**Legacy approach:** Left-rectangle (left-Riemann) numerical integration:

```
intersample_time = Δhalf_cycle_counter / (2 * line_frequency)
volume += GroupFlow * intersample_time
```

The 16-bit SampleTime counter is correctly unwrapped (line 562), and there are
sanity clamps rejecting negative or >1000 ml single-step contributions.

**Modern alternative — worth considering?** The obvious "upgrade" is the
**trapezoidal rule**: `volume += (flow_prev + flow_now)/2 * dt`. Trapezoidal
integration has O(dt²) error vs O(dt) for rectangles. Quantitatively, at the DE1's
~100–120 Hz sample rate (dt ≈ 8–10 ms) and flow changing on the scale of seconds,
the rectangle-vs-trapezoid difference is roughly **half of one sample's flow
change** — on the order of **0.1–0.3 ml** over an entire 40 ml shot, i.e. well
under 1 %. The dominant volume error is the firmware's own flow-sensor accuracy and
the unmeasured retained water in the group head — both far larger than the
integration-scheme error.

There is also a subtle reason the rectangle rule is *defensible* here: it is
causal and uses only the current sample, so it can never "look back" and revise.
Trapezoidal needs the previous flow sample, which the code would have to retain;
a minor cost but real.

**Verdict: KEEP.** The integration-scheme error is negligible (<1 %) compared to
the sensor and physical-system error budget. Trapezoidal is "more correct" in a
textbook sense but produces no observable improvement in the stop-at-volume
feature. Not worth the added state. If Crema implements trapezoidal anyway it is
harmless — but it should not be presented as a meaningful accuracy gain.

---

## 5. Tare-detection state machine

**Where:** `device_scale.tcl:192–385` — `process_weight_update`, `tare`,
`on_tare_seen`.

**What it does:** When a tare is requested, the app must detect when the scale has
actually zeroed (BLE tare is fire-and-forget; some scales take 300–500 ms). On
detecting "close to zero" it clears the estimator shift registers.

**Legacy approach:** A timeout-gated threshold detector. After `tare` is called it
sets `_tare_awaiting_zero = True` and timestamps the request. Each weight update,
if `|weight| < tare_threshold` (0.04 g) **and** within `_tare_awaiting_zero_ms`
(1000 ms) of the request, it declares the tare seen. The 0.04 g threshold is
explicitly chosen to tolerate sub-0.005 g scales that won't read exactly 0.000
under light vibration.

**Modern alternative — worth considering?** This is event/state logic, not signal
processing. A "fancier" version (e.g. requiring N consecutive sub-threshold
samples, or confirming the *derivative* has settled) would slightly reduce the
chance of a false "tare seen" if a real weight transiently passes through zero —
but a tare request immediately followed by a genuine ~0 g reading passing through
on its way somewhere else is not a realistic scenario during the pre-flow window
where this runs. The threshold + timeout window is appropriately simple.

**Verdict: KEEP.** Correctly proportioned to the problem. No algorithmic upgrade
warranted.

---

## 6. SAW (stop-at-weight) predictive trigger

**Where:** `device_scale.tcl:1144–1398` (`::device::scale::saw`), `check_for_saw`
at line 1190.

**What it does:** Stops the shot early so the *final* drink weight (after drips
and system lag settle) hits the target. It must predict the future weight.

**Legacy approach:** A **first-order lead/lookahead**. It computes a lead amount:

```
stop_early_by = early_by_grams + flow_now * early_by_flow
```

where `early_by_flow` is a sum of fixed lag constants — scale `sensor_lag`
(a per-scale lookup table at `device_scale.tcl:157–182`, sourced from a James
Hoffmann video measurement), an estimator-algorithm lag, and a DE1 lag (0.1 s).
It triggers when `current_weight > target - stop_early_by`. Effectively:
predicted final weight = current weight + flow × total_lag, a linear extrapolation
by one dead-time interval.

**Modern alternative — worth considering?** This is genuinely a control problem
(dead-time compensation), and there are textbook better tools:

- A **Smith predictor** or explicit first-order-plus-dead-time model of the
  cup-fill dynamics would predict the settling tail more accurately than a single
  linear extrapolation, especially the post-cutoff drip.
- A short-horizon model-based extrapolation could account for flow *decay* during
  the cutoff (flow is not constant through the stop transient).

However — and this matters — the legacy lag constants are *empirically measured per
scale*. A linear "flow × dead-time" predictor with a well-tuned dead-time is, in
practice, very close to what a first-order model predicts when the dead time
dominates the lag. The realistic error sources are puck behavior, channeling, and
drip variance, none of which a better predictor model can know in advance. The
RDT-style answer here is: the marginal gram of accuracy from a Smith predictor is
swamped by shot-to-shot physical variance.

**Verdict: CONSIDER (low priority).** The linear lead-compensation is reasonable
and the per-scale empirical lag table is the right idea. A model-based predictor
is *theoretically* better and could be a nice power-user option, but it requires
identifying a fill-dynamics model and is unlikely to beat the empirical constants
by more than a fraction of a gram in real conditions. Only pursue if Crema wants a
"high-accuracy SAW" differentiator; not MVP.

---

## 7. Median helper

**Where:** `device_scale.tcl:934–946` (`::device::scale::history::median`).

**Legacy approach:** Full sort (`lsort -real`) then pick the middle element (or
average the two middle for even length). O(n log n).

**Modern alternative:** `Quickselect` / `nth_element` is O(n) average. For the
window sizes here (n = 5 and n = 11), the constant factors make sort and select
indistinguishable — and Rust's `slice::select_nth_unstable` is available for free
if wanted. The sort is also being called from `flow_median` twice per update.

**Verdict: KEEP.** O(n log n) on n ≤ 11 is irrelevant. If Crema's median path uses
`select_nth_unstable` that's fine (idiomatic Rust), but it is not an improvement
worth a line of justification — it is a wash.

---

## 8. Event dispatch / scheduling

**Where:** `event.tcl` (whole file, 106 lines); `de1_de1.tcl:22–213`
(`::de1::event` listener/apply machinery).

**What it does:** A generic publish/subscribe system. Listeners register on named
callback lists with a timing flag (`-sync`, `-noidle`, `-idle`); `apply` fires
them. `-sync` runs inline with error trapping, `-noidle` schedules `after 0`,
`-idle` schedules `after idle [after 0 ...]`.

**Legacy approach:** Three flat lists per event type, iterated in registration
order. Dispatch is O(listeners). Tcl's `after` is the scheduling primitive.

**Modern alternative — worth considering?** This is a small, correct event bus.
In Rust the natural expression is channels (`tokio::mpsc` / `broadcast`) or a
typed callback registry; the legacy "register a string proc name into a list"
pattern is a Tcl idiom that simply does not translate — Crema will use typed
events regardless. There is no *algorithmic* deficiency: the list sizes are tiny
(single-digit listener counts), ordering is deterministic, and the three-tier
sync/idle timing model is a deliberate and sensible design (synchronous safety-
critical callbacks like flow-stop run inline; cosmetic ones are deferred).

The one thing worth carrying over explicitly: the **`-sync` tier exists so that
flow-stopping callbacks are not subject to scheduler latency** (see comment in
`event.tcl:46–48` and the `_generic_add` "failure may result in not stopping hot
water" note). Crema must preserve a guaranteed-synchronous path for stop logic;
do not route stop-at-weight/volume decisions through an async queue that could be
delayed behind UI work.

**Verdict: KEEP (design, not code).** No algorithmic change. Reimplement in
idiomatic Rust but preserve the synchronous-critical-path guarantee.

---

## 9. `after_flow_complete` deferred-callback logic

**Where:** `de1_de1.tcl:118–196` (`_maybe_after_flow_complete_callbacks`).

**What it does:** Fires "shot is fully done" callbacks `after_flow_complete_delay`
seconds after flow ends — but defers if the machine re-entered a flow phase
(e.g. "ending" substate), holding for idle.

**Legacy approach:** A single pending `after` ID plus a `holding_for_idle` boolean.
A small hand-rolled state machine.

**Modern alternative:** This is exactly the kind of timer+state logic that an
explicit state enum or a cancellable future expresses more clearly. In Rust a
`tokio` timer with explicit cancellation, or a typed state machine, removes the
"is the after ID still valid?" class of bug. But this is a *code-structure*
improvement, not an algorithmic one — there is no better *algorithm*, just a
cleaner representation.

**Verdict: KEEP (reimplement cleanly).** No algorithm to replace; just model it as
an explicit state machine / cancellable timer in Rust rather than an
ID-plus-boolean.

---

## 10. BLE fixed-point codecs (`binary.tcl`)

**Where:** `binary.tcl:490–569` (`convert_*` functions), `de1_de1.tcl:680–764`
(`shotsample_parse`).

**What it does:** Encode/decode the DE1's BLE wire formats: UxPy fixed-point
(U8P4, U8P1, U16P8, S32P16, U10P0), a 24-bit head-temperature value assembled from
3 bytes, and the unusual `F8_1_7` format.

**Legacy approach:** Mechanical bit math. `U16P8` = `round(x * 256)`, etc. The
24-bit temp is three `char` fields recombined. `F8_1_7` is a split-range encoding:
high bit clear → value is `byte / 10` (fine resolution, small range); high bit set
→ value is `byte & 127` (integer, large range).

**Modern alternative — worth considering?** These are codecs dictated by the wire
protocol; there is no "better algorithm" — the encoding is fixed by the firmware.
Crema must match them bit-for-bit.

> **CORRECTION (verified 2026-05-16).** This section originally flagged
> `convert_F8_1_7_to_float` as not being the inverse of the encoder near the
> 12.75 boundary. **That finding was wrong** — it rested on a bit error.
>
> The claim was that `12.7 s` encodes to byte `127` "which has the high bit
> set." It does not: `127` is `0b0111_1111` — bit 7 is **clear**. The encoder's
> low branch (`round(x*10)`, used for `x < 12.75`) produces only bytes `0..=127`,
> all high-bit-clear; the high branch (`round(x)|128`) produces `141..=255`. The
> decoder's `& 0x80` test therefore separates the two regimes correctly.
> `encode(12.7) = 127` and `decode(127) = 12.7` — a correct round-trip,
> confirmed by running the legacy Tcl procs directly as an oracle.

The `F8_1_7` codec is **correct and mutually consistent**. It is lossy in the
whole-second regime by design (`12.75 s` decodes back as `13 s`) — that is the
format, not a defect. Crema's `de1-protocol::fixed_point` already implements it
faithfully, with the boundary cases unit-tested against the Tcl oracle.

**Verdict: KEEP.** The BLE fixed-point formats are protocol-dictated and the
legacy codec — `F8_1_7` included — is correct.

---

## 11. Update integrity — SHA-256 hashing

**Where:** `updater.tcl:42–85` (`calc_sha`), manifest diff at `updater.tcl:650–714`.

**What it does:** Verifies downloaded update files against a server manifest by
content hash; only files whose hash differs are fetched; downloaded files are
stored under their hash as filename and re-verified.

**Legacy approach:** **SHA-256** (`shasum -a 256`, or the pure-Tcl `sha2::sha256`
fallback). The manifest diff is a straightforward map keyed by filename comparing
`(size, mtime, sha)`. Content-addressed temp storage (`$tmpdir/$filesha`)
deduplicates identical files.

**Modern alternative — worth considering?** SHA-256 is already a current,
cryptographically sound choice — there is nothing to "upgrade" for integrity. (The
old `sha1` references at lines 338–380 are commented-out cert-pinning experiments,
not in use.) A faster hash like **BLAKE3** would cut hash time on large update
sets and is the modern default for *non-adversarial throughput*, but update
verification here is I/O-bound (downloading over the network) — hashing is not the
bottleneck — and SHA-256 has hardware acceleration on the ARM tablets in question.

The actual security consideration is upstream of the hash: whether the *manifest
itself* is authenticated (signed) so an attacker can't supply both a malicious file
and a matching hash. That is a protocol/signing question for Crema's updater
design, not an algorithm-selection question, and is out of scope for this review —
but worth a one-line flag.

**Verdict: KEEP.** SHA-256 is correct and modern. BLAKE3 is faster but pointless
here (I/O-bound). The content-addressed dedup scheme is good — keep it. Separately:
ensure the manifest is signed (design item, not algorithm).

---

## 12. History / shot storage

**Where:** `history_viewer.tcl` (parsing, listing), `history_export.tcl`,
`device_scale.tcl:1113–1133` (`format_for_history`), `utils.tcl` shot-file globbing
(`utils.tcl:1744`, `1892`, `1933`).

**What it does:** Shots are written as individual `.shot` files (Tcl-dict text
format). Listing/browsing globs the history directory and sorts filenames
(timestamp-prefixed) with `lsort -dictionary`. Parsing is incremental
(`parse_next_shotfile` schedules itself `after idle` to avoid blocking).

**Legacy approach:** One file per shot; directory scan + dictionary sort for
ordering; incremental cooperative parsing to keep the UI responsive.

**Modern alternative — worth considering?** For the data *scale* involved — a user
accumulates maybe hundreds to low-thousands of shots over years — a directory of
files with a glob-and-sort is entirely adequate; the sort is O(n log n) on a few
thousand short strings, sub-millisecond. The incremental `after idle` parsing is a
sensible cooperative-scheduling pattern (Crema would use async tasks instead, same
effect).

A database (SQLite) would give indexed queries, range scans, and aggregate stats
"for free" and is the obvious modern choice **if Crema wants rich history features**
(filtering by bean, statistics over time, fast search). That is a
**product/feature** decision, not an algorithmic deficiency: the legacy file-per-
shot scheme is not *slow* or *wrong*, it is just feature-limited. The plain-text
`.shot` files also have real virtues — human-readable, trivially exportable,
robust to partial corruption (one bad file ≠ lost history), and already an
ecosystem format other tools read.

**Verdict: KEEP (with a CONSIDER for SQLite as a feature decision).** No
algorithmic problem. If Crema's roadmap includes analytics/search over shot
history, back it with SQLite (or an index sidecar) — but keep `.shot` file
export for interop. This is a storage-architecture choice to make deliberately in
the data-model design doc, not an algorithm to "replace."

---

## 13. Profiler

**Where:** `profiler.tcl` (whole file).

This is a thin wrapper over Tcl's stdlib `::profiler` package used for ad-hoc
developer profiling. No algorithmic content; not shipped behavior.

**Verdict: KEEP / N/A.** Crema uses Rust tooling (`cargo flamegraph`, `tracing`,
`criterion`); nothing to port.

---

## Summary table

| # | Algorithm / area | Legacy approach | Verdict | Recommended alternative |
|---|---|---|---|---|
| 1 | Scale weight/flow estimation | LSLR / finite-diff / offset-median | KEEP (already done) | Theil–Sen + median, already implemented in Crema |
| 2 | Scale period estimator | Single-pole EMA, τ≈17 min | **CONSIDER** | Median warm-up window, then slow EMA (or grid-snap) |
| 3 | Line-frequency estimation | Two-point slope, 10 s window | KEEP | (Regression slope marginally more robust; not worth it) |
| 4 | Volume integration | Left-rectangle Riemann sum | KEEP | (Trapezoidal: <1 % gain, negligible) |
| 5 | Tare detection | Threshold + timeout window | KEEP | — |
| 6 | SAW predictive stop | Linear lead = flow × empirical dead-time | **CONSIDER** (low pri) | Model-based / Smith predictor for fill dynamics |
| 7 | Median helper | Full sort, O(n log n) | KEEP | (`select_nth` O(n): a wash at n≤11) |
| 8 | Event dispatch | Flat lists, 3 timing tiers | KEEP (redesign in Rust) | Typed events / channels; preserve sync-critical path |
| 9 | `after_flow_complete` defer | after-ID + boolean | KEEP (redesign in Rust) | Explicit state machine / cancellable timer |
| 10 | BLE fixed-point codecs | Mechanical bit math | KEEP | (a flagged `F8_1_7` decoder bug was verified and found incorrect — see §10) |
| 11 | Update integrity hash | SHA-256 | KEEP | (BLAKE3 faster but I/O-bound; sign the manifest) |
| 12 | History storage | File-per-shot + glob/sort | KEEP (CONSIDER SQLite) | SQLite if analytics wanted; keep `.shot` export |
| 13 | Profiler | Tcl stdlib wrapper | N/A | Rust tooling |

---

## Prioritized recommendations

Out of 13 areas surveyed, **none warrants a REPLACE.** The original review flagged
the `F8_1_7` decoder as a correctness bug and ranked it the highest-value finding;
on verification that finding was incorrect (a bit error in the analysis — see §10).
Two areas are worthwhile **CONSIDER** items; the rest should be ported as-is (in
idiomatic Rust) — they are correctly proportioned to their problems.

1. **`F8_1_7` codec — no action; finding withdrawn.** Byte `127` has the high bit
   clear, so `encode(12.7) → 127 → decode → 12.7` round-trips correctly. The codec
   is already implemented faithfully and oracle-tested in `de1-protocol`.

2. **Scale period estimator — CONSIDER (low effort, modest win).**
   Replace the cold-start path of the 17-minute EMA with a median over the first
   ~30 valid inter-arrival deltas (then hand off to a slow EMA for drift, or
   grid-snap). Fixes first-shot-on-a-new-scale flow-rate error and handles
   multi-modal scales (Skale 2). A few lines; do it when Crema implements the
   period estimator anyway.

3. **SAW model-based predictor — CONSIDER (higher effort, situational).**
   A Smith-predictor / first-order-plus-dead-time model of cup-fill dynamics is
   theoretically better than the legacy linear lead-compensation, but real-world
   shot-to-shot physical variance limits the achievable gain to a fraction of a
   gram. Only pursue as a deliberate "high-accuracy SAW" feature, not for MVP.

Everything else — line-frequency classification, volume integration, tare
detection, the median helper, event dispatch, deferred callbacks, SHA-256, and the
file-per-shot history store — is **already at or near optimal for its problem
size and error budget**. Porting it faithfully (in clean Rust) is the right call;
do not gold-plate it.

Two non-algorithmic items to carry into other design docs: (a) preserve a
**synchronous, scheduler-latency-free path** for flow-stop decisions (§8); and
(b) decide deliberately whether shot history needs a **SQLite-backed index** for
analytics (§12) — and if so, keep `.shot`-file export for interoperability.
