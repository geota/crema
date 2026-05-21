# 18 — Calibration writes: safety, bands, API, v1 verdict

**Status:** planning only — no core changes in this branch
**Branch:** `plan-calibration-safety` (off `main`)
**Companions:** `docs/14-write-actions-audit.md` §5 (the surveyed action set),
`docs/16-profile-upload-plan.md` (the parallel "build a write before shipping
it" planning doc), `core/de1-protocol/src/calibration.rs` (the wire-decode
layer this plan binds against).

---

## 1. Background

### What a calibration write actually does

The DE1 carries three closed-loop sensors that drive every safety and
metering decision the firmware makes: flow, pressure, and group temperature.
Each ships with a factory calibration burned into the machine. The
`Calibration` characteristic (`cuuid_12`) lets the app **overwrite the
in-use calibration** for any one of those three sensors. The packet shape is
already modelled — see `core/de1-protocol/src/calibration.rs`:

| Byte | Meaning |
|---:|---|
| 0..4 | `WriteKey` = `0xCAFEF00D` for `Write`/`ResetToFactory`, `1` for the reads |
| 4 | `CalCommand`: `0`=ReadCurrent, `1`=Write, `2`=ResetToFactory, `3`=ReadFactory |
| 5 | `CalTarget`: `0`=Flow, `1`=Pressure, `2`=Temperature |
| 6..10 | `de1_reported` value, S32P16 (signed 16.16 fixed point) |
| 10..14 | `measured` value, S32P16 |

A `Write` packet tells the DE1: *"you reported `de1_reported` while the
externally-measured true value was `measured` — adjust your sensor curve
accordingly."* The firmware computes a correction factor and applies it to
all subsequent sensor readings.

A `ResetToFactory` packet tells the DE1 to discard the user override and
restore the factory burn-in calibration. Both `de1_reported` and `measured`
are sent as `0.0` (`Calibration::reset_to_factory` already encodes this; see
`calibration.rs:127`).

### Why these writes are dangerous

A bad calibration value does not crash the machine. It quietly mis-reads
every shot from then on. Three classes of harm:

1. **Safety.** The pressure sensor is the firmware's input to the
   over-pressure cutoff. A calibration that says "the sensor under-reads by
   30 %" makes the firmware believe 12 bar is actually 16.4 bar — and the
   firmware's safety ceilings are calibrated against the *firmware's*
   pressure reading, not the true pressure. Bad pressure calibration can
   defeat over-pressure protection. Bad temperature calibration biases the
   group heater. (Bad flow calibration is the mildest of the three:
   miscounted shots, off-target weight stops, no hardware damage.)
2. **Recovery.** There is no firmware-side "undo" of the most recent write.
   The user has to either issue a `ResetToFactory` (which is offered) or
   compensate with another write. The first failure mode of the calibration
   UI is therefore "I made it worse and I cannot tell by how much."
3. **Silent drift.** Unlike a profile upload — which the user sees as a
   bad-tasting shot ten seconds later — calibration changes propagate to
   every reading on every screen. The bug is global.

Mid-shot the harm compounds: the firmware re-reads its calibration on every
sample. A write that arrives mid-pour can corrupt the temperature feedback
loop or trip the pressure cutoff while a hot puck is under load. **Mid-shot
calibration writes can brick a shot routine and, plausibly, scald the user**
(the firmware authors have stopped short of saying "yes" here in public, but
the legacy app's UI flow gates *all* of the calibration screen behind the
machine being asleep, and we should treat that as the binding constraint).

### What the legacy de1app UI gates on

The legacy Tcl calibration screen lives in `gui.tcl:2394-2453`
(`calibration_gui_init`) and `skins/default/de1_skin_settings.tcl:2183-2417`
(the three-page UI). The legacy app does **not** check machine state inside
`de1_send_calibration` itself (see `de1_comms.tcl:1586-1617`) — the
calibration BLE write fires unconditionally if the DE1 is connected. The
state gate is **structural**: the calibration pages are registered with
`page_to_show_when_off calibrate` (`de1_skin_settings.tcl:2190,2196,2201`),
where `off` is the legacy term for the Sleep/Idle machine-state page bucket
(see `machine.tcl:903-913`, the `ghc_message` proc and its sibling
`nextpage(machine:off)` lookups). The page is *only loadable* when the
machine is off — the user cannot navigate to it while the DE1 is running.
The behavioural invariant is therefore "calibration writes only happen
while the machine is in Sleep or Idle," even though the wire write itself
is unrestricted.

The settings-page entry button additionally throws up a one-shot dialog
("Bad calibration settings might make your espresso machine unuseable. Only
proceed if you have been told to or have read the relevant manual sections
and know what you are doing.") before opening the calibration screen
(`de1_skin_settings.tcl:1075`). This is the only confirmation in the entire
flow — once past the dialog, every `<Return>` keypress in an entry field
fires a write.

A small extra: `calibration_gui_init` forces the temperature display unit
to Celsius for the duration of the calibration page (`gui.tcl:2397-2402`,
`calibration_disabled_fahrenheit`), so users entering a temperature
measurement cannot confuse units between display and entry. Crema's
calibration page needs the same lock.

### What this doc decides

1. The per-sensor value bands Crema clamps to **before** building a
   `Calibration::write(...)` packet.
2. The machine-state preconditions Crema enforces **inside the core**, so a
   shell can't accidentally route around them.
3. The Rust API on `CremaCore` and the new error type.
4. Whether v1 ships per-sensor `Write` at all, or only ships
   `ResetToFactory`.

---

## 2. Per-sensor value bands

Three bands for each sensor:

- **Wire range** — what the S32P16 fixed-point encoding can represent and
  what the firmware will *accept* without faulting. This is broad and not
  useful as a safety bound, but it documents the absolute ceiling.
- **Legacy practical range** — what the Tcl UI lets the user enter.
  Inferred from the relevant `range_check_variable` clamps in
  `vars.tcl:4183-4201` and the wired-in sliders in `de1_skin_settings.tcl`.
  These are the settings the user types into when calibrating, so they
  bound the realistic `measured` and `de1_reported` values.
- **Crema-recommended range** — the conservative band the new API clamps
  to. Strictly tighter than the legacy range for the safety-critical
  sensors (pressure, temperature) and equal-or-tighter for flow.

S32P16 representable range is `−32 768.0 ..= 32 767.99998` (signed
`i32` / 65 536). That is the **wire range** for every sensor — well beyond
any physically plausible reading.

### Recommended Rust constants

These belong on `de1-app` (the application core), not the wire crate
(`de1-protocol/calibration.rs` stays a verbatim wire-decoder per
convention 3). One module, three pairs.

```rust
use std::ops::RangeInclusive;
use de1_protocol::CalTarget;

/// Allowed range for a `CalTarget::Flow` calibration value (`de1_reported`
/// and `measured`), in millilitres per second.
///
/// Legacy de1app accepts `0.0 ..= 8.0` (`vars.tcl:4192` clamps against
/// `::de1(max_flowrate_v11)`, defined at `machine.tcl:157` as `8`). Crema
/// narrows the floor off zero because a `0` reading at calibration time
/// is almost always a sensor read failure rather than a real measurement.
pub const FLOW_RANGE: RangeInclusive<f32> = 0.1..=8.0;

/// Allowed range for a `CalTarget::Pressure` calibration value
/// (`de1_reported` and `measured`), in bar.
///
/// Legacy de1app accepts `0.0 ..= 12.0` (`vars.tcl:4198` clamps against
/// `::de1(maxpressure)`, defined at `machine.tcl:169` as `10` and then
/// re-set at `machine.tcl:188` to `12`). Crema clamps to `2.0 ..= 10.0`:
/// pressure calibration below 2 bar lives in the noise floor of the
/// sensor; above 10 bar lives in the safety-ceiling region where the
/// firmware's own cutoffs trigger.
pub const PRESSURE_RANGE: RangeInclusive<f32> = 2.0..=10.0;

/// Allowed range for a `CalTarget::Temperature` calibration value
/// (`de1_reported` and `measured`), in degrees Celsius.
///
/// Legacy de1app accepts `0.0 ..= 105.0` on the entry field
/// (`vars.tcl:4183`). Crema clamps to `80.0 ..= 100.0`: the legacy
/// `min_temperature` / `max_temperature` defaults (`machine.tcl:159-160`)
/// already bound real espresso brewing to this window; below 80 °C is
/// too cool to extract, above 100 °C boils and the calibration is
/// meaningless.
pub const TEMPERATURE_RANGE: RangeInclusive<f32> = 80.0..=100.0;

/// Lookup helper: the recommended range for one calibration target.
pub fn recommended_range(target: CalTarget) -> RangeInclusive<f32> {
    match target {
        CalTarget::Flow => FLOW_RANGE,
        CalTarget::Pressure => PRESSURE_RANGE,
        CalTarget::Temperature => TEMPERATURE_RANGE,
    }
}
```

### Summary table

| Sensor | Wire range | Legacy practical range | **Crema recommended** | Notes |
|---|---|---|---|---|
| Flow | S32P16 (`±32 768`) mL/s | `0.0 ..= 8.0` mL/s (`vars.tcl:4192` → `machine.tcl:157`) | `0.1 ..= 8.0` mL/s | Floor lifted off zero; a `0.0` is almost certainly a read failure, not a measurement |
| Pressure | S32P16 (`±32 768`) bar | `0.0 ..= 12.0` bar (`vars.tcl:4198` → `machine.tcl:169,188`) | `2.0 ..= 10.0` bar | Safety-critical: tight band. Below 2 bar is sensor noise; above 10 bar is within the over-pressure cutoff window |
| Temperature | S32P16 (`±32 768`) °C | `0.0 ..= 105.0` °C (`vars.tcl:4183`) | `80.0 ..= 100.0` °C | Espresso-bracket only. Below 80 °C is too cool to extract; above 100 °C boils and meaningfully cannot be calibrated in atmospheric conditions |

Both `de1_reported` and `measured` are clamped against the same band: they
must both lie inside the recommended range for the write to be admitted. A
delta beyond a few percent is suspicious but **not rejected** — the band
only protects against off-by-an-order-of-magnitude typos, not against
honest-but-aggressive calibrations.

### What the band does **not** check

- **Sensible delta.** `de1_reported = 9.0`, `measured = 3.0` is in range
  but the user is asking the firmware to scale every pressure reading by
  `3.0 / 9.0 ≈ 0.33`. That is a 67 % correction; almost certainly wrong.
  A delta-sanity check (e.g. `|measured / de1_reported - 1.0| < 0.30`)
  would catch this. **Recommend: defer to v2.** The legacy app does not
  enforce this and the Crema UI can surface "are you sure?" at the shell
  layer if a delta beyond ~10 % is entered.
- **Unit confusion.** Nothing prevents the user from entering pressure in
  PSI (e.g. `145.0`). The pressure band's 10 bar ceiling catches the most
  egregious case (1 bar = ~14.5 PSI; a PSI-typed espresso pressure of
  `145` lands at 145 which is well above the ceiling and is rejected).
  Equivalent guard for Fahrenheit on the temperature sensor: 200 °F
  (≈ 93 °C) lands inside the band, so an F-vs-C confusion **is not
  caught**. The legacy app's defence here is the
  `calibration_disabled_fahrenheit` override (`gui.tcl:2397-2402`) which
  forces Celsius for the duration of the calibration screen — Crema should
  do the same in the shell. List this as a UX requirement; the core can't
  enforce it.

---

## 3. Machine-state gate: Sleep + Idle only

The core refuses calibration writes (and resets) when the most recent
known `MachineState` is anything other than `Idle` or `Sleep`. Returning
`Err(CalibrationError::MachineNotIdle { current_state })` lets the shell
surface "put the machine to sleep first" without needing protocol
knowledge.

### Why

1. **Mid-shot writes corrupt the live control loop.** The firmware
   re-reads its calibration on every sample (≈ 4 Hz pressure, ≈ 50 Hz
   temperature). A `Write` packet that lands during `Espresso`, `Steam`,
   `HotWater`, or any `*Rinse` state can flip the correction factor halfway
   through a closed-loop step, with poorly defined consequences. The
   firmware authors have not published the exact failure mode and we
   should not be the ones discovering it.
2. **Steam / clean / descale temperatures are above the calibration band.**
   A temperature-write at 140 °C steam-target is outside the recommended
   range anyway; rejecting on state is just a clearer error message than
   "out of range" when the user is calibrating during a steam idle.
3. **The legacy UI already enforces the gate, transitively.** The
   calibration page is gated behind `page_to_show_when_off calibrate`
   (`de1_skin_settings.tcl:2190,2196,2201`). "Off" is the legacy term for
   the "machine is asleep or idle" page bucket — `machine.tcl:903-913`
   shows the `nextpage(machine:off)` lookup, which is the page-stack key
   that resolves only while the DE1 is in its non-running states. Legacy
   users never had a path to a calibration write outside Sleep/Idle.
   Carrying the same invariant into Crema means we are not relaxing a
   constraint a real DE1 has been operating under for years.
4. **The core is the right enforcement point.** If only the shell gates,
   any future shell (Android, third-party clients, automation scripts via
   the FFI) re-litigates the gate. Putting it in `CremaCore` makes the
   constraint a property of the calibration write itself, mirrored by the
   shell's navigation guard rather than originating in it.

### Which states are admitted

| `MachineState` | Admit? | Why |
|---|---|---|
| `Sleep` | yes | Heaters off, sensors quiescent — the ideal calibration state |
| `Idle` | yes | Heaters tracking idle setpoint, sensors at steady state — what the legacy UI assumed |
| `Espresso`, `Steam`, `HotWater`, `HotWaterRinse`, `SteamRinse` | no | Live control loops referencing the calibration |
| `Clean`, `Descale`, `AirPurge`, `FillingTank`, `Refill` | no | Pump/heater routines that depend on the sensor curves; legacy never permitted calibration here |
| `Booting`, `NoRequest`, `Sentinel`, `SchedIdle` | no | Transitional / boot states; the machine has not stabilised |
| Unknown (no `MachineState` observed yet) | no | Fail-closed — better to surface "connect to the machine first" than to write blind |

The "no `MachineState` observed yet" case maps to
`CalibrationError::MachineStateUnknown` — distinct from `MachineNotIdle`
because the remediation differs ("wait for the state characteristic to
report" vs "put the machine to sleep").

---

## 4. Crema-side API design

Add to `de1-app/src/lib.rs`. The signatures follow the existing
`request_machine_state` / `read_calibration` patterns — the methods build
a `CoreOutput` whose `commands` carry the BLE write — but they are the
**first** writes in the codebase that can be **refused** before they reach
the wire, so they return `Result<CoreOutput, CalibrationError>` rather
than a bare `CoreOutput`.

### New module: `de1-app/src/calibration.rs`

```rust
//! Calibration-write safety: per-sensor clamps, machine-state gate, and
//! the `CalibrationError` returned by `CremaCore::write_calibration` and
//! `CremaCore::reset_calibration_to_factory`.
//!
//! See `docs/18-calibration-safety.md` for the rationale behind each band
//! and the Sleep/Idle gate.

use std::ops::RangeInclusive;
use de1_protocol::{CalTarget, MachineState};

pub const FLOW_RANGE: RangeInclusive<f32> = 0.1..=8.0;
pub const PRESSURE_RANGE: RangeInclusive<f32> = 2.0..=10.0;
pub const TEMPERATURE_RANGE: RangeInclusive<f32> = 80.0..=100.0;

/// The recommended write range for one calibration target. See the per-band
/// `const` declarations for the rationale.
pub fn recommended_range(target: CalTarget) -> RangeInclusive<f32> {
    match target {
        CalTarget::Flow => FLOW_RANGE,
        CalTarget::Pressure => PRESSURE_RANGE,
        CalTarget::Temperature => TEMPERATURE_RANGE,
    }
}

/// A calibration write was refused before it reached the wire.
///
/// `#[non_exhaustive]`: future safety checks (delta-sanity, unit-confusion
/// heuristics, …) get their own variants without breaking shell `match`es.
#[derive(Debug, Clone, PartialEq, thiserror::Error)]
#[non_exhaustive]
pub enum CalibrationError {
    /// `de1_reported` or `measured` fell outside `recommended_range(target)`.
    /// `value` is whichever of the two failed (the first failing one).
    #[error("calibration value {value} for {target:?} is outside the allowed range {allowed:?}")]
    OutOfRange {
        target: CalTarget,
        value: f32,
        allowed: RangeInclusive<f32>,
    },

    /// A calibration write or factory reset was attempted while the machine
    /// was in a state other than `Idle` or `Sleep`. The shell should display
    /// "Put the DE1 to sleep before calibrating."
    #[error("calibration requires the DE1 in Sleep or Idle, but it is in {current_state:?}")]
    MachineNotIdle { current_state: MachineState },

    /// A calibration write was attempted before any `MachineState` had been
    /// observed. The shell should display "Wait for the DE1 to connect and
    /// report state, then retry."
    #[error("calibration requires a known DE1 state, but none has been observed yet")]
    MachineStateUnknown,

    /// One of `de1_reported` or `measured` was non-finite (`NaN` / `inf`).
    /// Defensive: nothing legitimate produces this, but the wire encoder
    /// (`s32p16_encode`) does not validate, so we do.
    #[error("calibration value {value} for {target:?} is not finite")]
    NotFinite { target: CalTarget, value: f32 },
}
```

### Methods on `CremaCore`

```rust
use crate::calibration::{CalibrationError, recommended_range};

impl CremaCore {
    /// Build a `Write` calibration packet for `target`, after checking the
    /// machine is in `Sleep`/`Idle` and both values lie in
    /// `recommended_range(target)`.
    ///
    /// Returns `Ok(CoreOutput)` with one `WriteCharacteristic` against
    /// `WriteTarget::De1Calibration` carrying `Calibration::write(...)`'s
    /// encoded 14-byte packet. Returns `Err(CalibrationError)` without
    /// emitting any command if the preconditions fail — the wire stays
    /// quiet on a rejected write.
    pub fn write_calibration(
        &mut self,
        target: CalTarget,
        de1_reported: f32,
        measured: f32,
    ) -> Result<CoreOutput, CalibrationError> { /* … */ }

    /// Build a `ResetToFactory` calibration packet for `target`, after
    /// checking the machine is in `Sleep`/`Idle`. The factory-reset packet
    /// carries no user-supplied values, so the range check does not apply.
    ///
    /// Returns `Ok(CoreOutput)` with one `WriteCharacteristic` against
    /// `WriteTarget::De1Calibration` carrying
    /// `Calibration::reset_to_factory(target)`'s encoded packet.
    pub fn reset_calibration_to_factory(
        &mut self,
        target: CalTarget,
    ) -> Result<CoreOutput, CalibrationError> { /* … */ }
}
```

### Validation order

For `write_calibration`:

1. Check `self.last_state` is `Some`; otherwise `MachineStateUnknown`.
2. Check the state is `Idle` or `Sleep`; otherwise `MachineNotIdle`.
3. Check `de1_reported.is_finite()`; otherwise `NotFinite`.
4. Check `measured.is_finite()`; otherwise `NotFinite`.
5. Check `recommended_range(target).contains(&de1_reported)`; otherwise
   `OutOfRange` with `value: de1_reported`.
6. Check `recommended_range(target).contains(&measured)`; otherwise
   `OutOfRange` with `value: measured`.
7. Build the `Calibration::write(target, de1_reported, measured)` packet
   and emit one `Command::WriteCharacteristic`.

`reset_calibration_to_factory` runs steps 1, 2, then jumps to the build
step with `Calibration::reset_to_factory(target)`.

### Why `&mut self` even though no state changes

Both methods read `self.last_state` (the most recent `StateInfo` the core
observed; see `de1-app/src/lib.rs:82`). They take `&mut self` to match the
existing convention for "this method may emit commands" —
`set_steam_hotwater_settings`, `tare_scale`, `set_scale_volume` all take
`&mut self`. Consistency beats the marginal benefit of an `&self` on these
two. A future "this calibration write was issued at `t = …`" tracker (for
the read-back verifier in §7) will need `&mut self` anyway.

### Why `Result<_, CalibrationError>` and not folding into `AppError`

`AppError` (in `de1-app/src/error.rs`) currently has a single variant
(`Serialization`) and is the *return-path* error for the FFI bridges.
`CalibrationError` is **pre-flight validation** — it tells the caller
"this write is refused, fix the inputs and try again," whereas `AppError`
covers post-encode failure. They are different lifecycle stages and
different audiences (the shell shows the calibration error to the user;
the shell does not show `AppError::Serialization` to the user, it logs
it). Keep them separate. The bridge layer maps `CalibrationError` to a
shell-visible event (likely an `Event::CalibrationRejected { reason }`
variant added alongside this work).

### Note on `WriteTarget` and existing infrastructure

**No new `WriteTarget` variant is needed.** `WriteTarget::De1Calibration`
already exists (`event.rs:269`) and is the same characteristic the
`read_calibration` / `read_factory_calibration` requests use today
(the read sends a `WriteKey=1` packet on the same UUID and the DE1
answers on the notify side). The two new methods just emit a *different*
14-byte payload to the same target. This matches the §5 row in
`docs/14-write-actions-audit.md`: "no new variants — just bridge methods."

### Bridge wrappers

`de1-wasm` and `de1-ffi` mirror these in the obvious way — each takes the
same `target` + two `f32`s, returns a JSON string in the wasm bridge
(carrying either the `CoreOutput` or a `{"error": "..."}` shape) and a
typed `CalibrationResult` in the UniFFI bridge. The cost is small (≈ 40
LOC per bridge); the design is the standard one used elsewhere in the
write surface.

---

## 5. Recommendation: what ships in Crema v1

### The hypothesis to verify

> "Maybe **reset-to-factory only** in v1, with full per-sensor writes
> deferred to v2 once we trust the UI and the clamps."

### Verdict

**Confirmed. Ship `reset_calibration_to_factory` in v1. Defer
`write_calibration` to v2.**

The asymmetric implementation cost does not justify the asymmetric risk.

### The numbers

| Capability | Implementation cost | Recovery story if the user wrecks it | Failure rate the API has to tolerate |
|---|---|---|---|
| `reset_calibration_to_factory(target)` | ~15 LOC + tests in the core, ~10 LOC × 2 bridges, single-button UI | Trivial — call it again. Cannot harm the machine. Reverts to the burned-in calibration even if a prior `Write` was wrong. | 0 % — the factory calibration is the firmware's known-good baseline |
| `write_calibration(target, reported, measured)` | ~50 LOC + tests in the core (range check, state gate, error mapping), ~30 LOC × 2 bridges, a three-page UI with "are you sure?" gates, Celsius-lock plumbing, optional delta-sanity warning | Reset-to-factory **or** another write — but the user does not know how off they are. The DE1 has no "show me the magnitude of my current correction" read that we trust enough to surface. | Hard to put a number on without a UI in production. Plausible ≥ 5 % of users entering a value will enter a wrong-unit / wrong-decimal / wrong-sensor value at least once. |

### Why this is the right call

1. **Reset covers ~95 % of the legitimate v1 use case.** The real-world
   need for per-sensor calibration writes is rare: most users never touch
   the calibration page, and the ones who do mostly arrive because they
   have already wrecked their calibration (often via the legacy app) and
   want to get back to factory. Reset alone closes that loop.
2. **Calibration *reads* already work** (`read_calibration` /
   `read_factory_calibration` are in `de1-app/src/lib.rs:245-266`), so v1
   can still *display* the current vs. factory delta. The user can see
   they have drifted; they can reset; what they cannot yet do is write a
   new correction. That's a meaningful product.
3. **The UI for per-sensor writes is non-trivial.** Three pages (legacy),
   Celsius-lock, "type the value to confirm" pattern, delta-sanity
   warning, post-write read-back to verify the correction took. Each of
   those is a chance to ship a bug. None of them are needed for the
   reset path.
4. **A bad calibration is a multi-day support burden.** The user enters
   the wrong value, the next morning's shots taste wrong, they don't
   immediately connect the two events. We carry that diagnostic cost. The
   reset-only path's worst case is "I clicked reset and now my hand-tuned
   calibration is gone" — recoverable by writing the previous values back
   *when v2 lands*.
5. **The clamps are conservative on paper but unproven in production.**
   `2.0..=10.0` bar for pressure is a reasonable guess; we do not yet
   have a corpus of real DE1 calibration sessions to confirm it does not
   reject legitimate values. Shipping reset-only buys time to gather that
   data from the read-back path without exposing users to the failure
   mode.
6. **The public `CalibrationError` API lands in v1 anyway.** The full
   enum (`OutOfRange`, `MachineNotIdle`, `MachineStateUnknown`,
   `NotFinite`) is part of the v1 surface even though only the two
   state-gate variants are reachable through `reset_calibration_to_factory`.
   This means v2's `write_calibration` does **not** force a breaking
   change on shells when it ships — the variants are already there,
   dormant, and `#[non_exhaustive]` covers anything added later.

### v1 scope

- **Implement**: `CremaCore::reset_calibration_to_factory(target)` with
  the `MachineNotIdle` / `MachineStateUnknown` gates.
- **Implement**: the `CalibrationError` enum (full shape from §4, even
  though `OutOfRange` and `NotFinite` are unreachable in v1 — they're
  there for v2's `write_calibration` to use).
- **Implement**: bridge wrappers in `de1-wasm` and `de1-ffi` for the
  reset call.
- **Implement (v1 UI)**: a calibration page that
  - is reachable only when `MachineState ∈ {Idle, Sleep}` (the shell
    enforces this for navigation; the core enforces it on the write);
  - shows the current vs. factory calibration for each of the three
    sensors via `read_calibration` / `read_factory_calibration`;
  - offers one "Reset *X* to factory" button per sensor;
  - shows an explanation banner saying "per-sensor calibration writes
    are coming in a future release. For now you can only reset to
    factory."

### Deferred to v2

- `CremaCore::write_calibration(target, de1_reported, measured)` — the
  same signature shown in §4.
- Activation of the `OutOfRange` and `NotFinite` variants (which already
  exist in the v1 enum, dormant).
- The three-page write UI, Celsius lock, delta-sanity warning, "type to
  confirm" pattern.
- A v2 milestone gate: ≥ 10 real users have used reset-to-factory
  without incident, and we have one or more captured BLE traces of a
  reset write to confirm the round-trip behaviour matches what we
  expect.

### What changes if the user pushes back

If the user wants per-sensor writes in v1 anyway: the API shape in §4
ships unchanged, the v2-deferred work moves to v1, **and the doc-14 §5
row's risk grade is bumped from `M` to `H`**. The implementation
shouldn't be much more code than the spec sketch; the cost is UX testing
and the support overhead of a calibration-related bricked-shot complaint.

---

## 6. Implementation checklist (v1 only)

When the v1 implementation ticket fires, the following land together:

1. `core/de1-app/src/calibration.rs` — new module: `FLOW_RANGE`,
   `PRESSURE_RANGE`, `TEMPERATURE_RANGE`, `recommended_range`,
   `CalibrationError` (full enum).
2. `core/de1-app/src/lib.rs` — `pub use calibration::CalibrationError;`,
   `reset_calibration_to_factory(target)` method on `CremaCore`.
3. `core/de1-wasm/src/lib.rs` — bridge wrapper returning a JSON string.
4. `core/de1-ffi/src/lib.rs` — UniFFI wrapper returning a
   `CalibrationResult` typed enum.
5. `core/generate-bindings.sh` — re-vendor `crema-core.ts` and
   `CremaCoreTypes.kt` (preserving each file's custom header per
   convention 9).
6. Web shell — calibration page (read current/factory, reset buttons,
   "feature flag: per-sensor writes deferred" banner).
7. Android shell — calibration screen, same shape.
8. Tests — at minimum:
   - `reset_calibration_to_factory` while `MachineState = Espresso`
     returns `MachineNotIdle`.
   - `reset_calibration_to_factory` before any state observed returns
     `MachineStateUnknown`.
   - `reset_calibration_to_factory(CalTarget::Pressure)` while in
     `Idle` emits exactly one `WriteCharacteristic{De1Calibration, ...}`
     whose payload decodes back to a `CalCommand::ResetToFactory` packet
     for `CalTarget::Pressure`.

The whole batch is **S**, in docs-14 terms — call it ~150 LOC of Rust
plus the two bridge wrappers plus the typeshare regen, comfortably inside
one PR.

---

## 7. Open questions for the implementer

- **Does the DE1 ever ACK a calibration write?** The read path notifies
  on the same characteristic. The write path may or may not — the legacy
  app follows every write with an immediate `de1_read_calibration` to
  verify (`de1_skin_settings.tcl:2382,2389`). Recommend Crema do the same
  on the reset path: after `reset_calibration_to_factory` returns
  `Ok(CoreOutput)`, the shell should issue a `read_calibration(target)`
  ~250 ms later and confirm the read-back equals the factory value.
  Surface a `CalibrationResetFailed` event if it doesn't. (This belongs
  in the shell, not the core, because the timer lives in the shell.)
- **Is `MachineState::Idle` truly safe?** The firmware authors have not
  published the exact "calibration-safe" state predicate. The legacy app
  treats the whole "off" bucket as safe, which includes Sleep and Idle
  (it does not include the `*Rinse` substates of Idle's `SubState`
  family). Conservative answer: if the firmware version is known to be
  < some threshold, narrow to Sleep only. Defer until v2 unless someone
  surfaces a known-bad firmware version.
- **Does the band-clamp belong in `CremaCore` or one layer up?** Argument
  for the core: it's the single enforcement point that every bridge
  passes through. Argument for one layer up: a third-party client using
  the FFI may want a looser band for diagnostic work. Recommend the
  core, for v1 and v2; if a diagnostic mode is ever needed, add a
  `write_calibration_unchecked` with a doc-comment that the standard
  channel uses `write_calibration`.

---

## 8. Assumptions / things to verify

These are points where the doc commits to a position without a
firmware-side source-of-truth. Each is a candidate for confirmation
during implementation.

- **S32P16 wire range is firmware-accepted without internal clamping.**
  The wire encoding can represent `±32 768`; the firmware accepts the
  full range without faulting. The legacy proc never validates
  (`de1_comms.tcl:1586-1617`) and the firmware authors have not published
  bounds. Treated as "wire range is broad, recommended range is the real
  safety boundary" throughout. Worth a single BLE-capture test against a
  real DE1 in v2 to confirm.
- **Legacy machine-state gate is structural, not inline.**
  `de1_send_calibration` (`de1_comms.tcl:1586-1617`) performs no
  machine-state check — it writes whenever the DE1 is connected. The
  Sleep/Idle invariant is enforced by the calibration pages registering
  themselves with `page_to_show_when_off calibrate`
  (`de1_skin_settings.tcl:2190,2196,2201`), keyed against the "off" page
  bucket whose lookup table sits at `machine.tcl:903-913`
  (`ghc_message` + the `nextpage(machine:off)` indirection). The user
  cannot navigate to a calibration entry field while the DE1 is running.
  Crema lifts this from "structural / by-convention" to "enforced in the
  core."
- **`CalCommand = 3` in the legacy reset buttons is dead code.** The
  disabled buttons at `de1_skin_settings.tcl:2358-2360` pass `3` as the
  `calibcmd` argument; the proc comment at `de1_comms.tcl:1607` makes it
  unambiguous that **2** is the live reset value, matching
  `Calibration::reset_to_factory` in the wire crate (`CalCommand =
  ResetToFactory = 2`). The legacy `3` corresponds to `ReadFactory`,
  which makes the disabled buttons a copy-paste typo — they would have
  read the factory calibration, not reset to it. Crema uses `2` for
  reset (already encoded in `calibration.rs:127` via `reset_to_factory`).
- **Per-sensor calibration `Write` ACK semantics are unconfirmed.** The
  legacy app follows every write with an immediate read, which would
  hide whether the write itself notifies. The codec encodes the same
  characteristic in both directions, so an ACK *could* arrive on the
  same notify channel. Treating "read-back ~250 ms after the write" as
  the verification strategy, since that matches legacy behaviour and
  does not depend on un-documented ACK semantics. Confirm during v2
  implementation if a write-ACK turns out to be reliable; would save one
  round-trip per calibration.

---

## 9. Summary

- **3 sensors, 3 recommended write bands**: Flow `0.1..=8.0` mL/s,
  Pressure `2.0..=10.0` bar, Temperature `80.0..=100.0` °C. Tighter than
  the legacy app's bands on pressure and temperature; equal-or-tighter on
  flow.
- **Machine-state gate enforced in the core**: Sleep + Idle only. Other
  states return `CalibrationError::MachineNotIdle`. No observed state
  returns `CalibrationError::MachineStateUnknown`. Both gates close
  pre-flight; the wire stays quiet on a rejected write.
- **One new module, two new public methods, one new error type.** No new
  `WriteTarget` variant (re-uses `De1Calibration`). No changes to the
  wire crate (`core/de1-protocol/src/calibration.rs` is already
  complete).
- **v1 ships reset-to-factory only.** Per-sensor writes
  (`write_calibration`) ship in v2 once the UI is hardened, the clamps
  are validated against real sessions, and the delta-sanity warning is
  designed.
- **The full `CalibrationError` enum lands in v1** even though
  `OutOfRange` and `NotFinite` are unreachable until v2 — the variant
  set is part of the public API contract from day one, and
  `#[non_exhaustive]` covers any v3+ additions.
