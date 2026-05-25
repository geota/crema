# Flow Calibration Workflow Audit

**Date:** 2026-05-25
**Question:** How is "flow calibration" implemented end-to-end in TCL +
reaprime, and what's the minimum we need to wire it up in Crema?

## Summary

The flow-calibration **wire** is well-specified — TCL, reaprime, and
Crema all encode `int(1000 × multiplier)` LE into MMR `0x80383C` with
wire `Len=4`, and Crema + reaprime agree on the safety range
0.13..=2.0. The **workflow** is *not* well-specified across references:
TCL ships only a 0.13..2.0 manual slider in the default skin plus a
test profile that asks the user to eyeball blue/brown lines and a
**separate, externally-hosted plugin** (`Graphical_Flow_Calibrator`,
not in this checkout) that we cannot audit from local sources;
reaprime exposes only the setter via its HTTP API — no UI, no math,
no measurement helper. We can ship the slider equivalent confidently;
the "graphical" auto-derive-from-weight workflow has no canonical
reference we can verify against without pulling external code.

## What "flow calibration" does

The DE1 estimates dispensed mass from a flow-meter reading inside the
gear pump. Manufacturing tolerance on the pump and meter assembly
biases the estimate by typically ±5–15%. The flow-calibration
multiplier (MMR `0x80383C`, scaled `int(1000 × m)`, default `1.000`)
is the firmware's per-machine correction factor: every flow sample the
firmware reports is multiplied by this value before it reaches the
app, profile-frame stop logic, and the `ShotSample.estimated_weight`
field. Bad multipliers cause weight-based stops to misfire (under-
or over-dispense) and skew the "flow" line on the shot graph; they
do **not** affect heater control, pressure cutoff, or any safety
ceiling (docs/18-calibration-safety.md §1: "the mildest of the three"
calibration hazards). Calibration = (a) dispense a known target while
weighing the actual output, (b) derive `new_multiplier = current ×
(measured / reported)`, (c) write it via MMR. Iterate until the
estimate matches the scale.

## TCL implementation

### User-facing flow

There are **two** parallel paths in the legacy app, both surfacing the
same `::settings(calibration_flow_multiplier)` value and the same MMR
register:

1. **Manual slider** — Calibrate settings page, sensor row "Flow".
   File `de1plus/skins/default/de1_skin_settings.tcl:2402`. A horizontal
   Tk scale widget bound directly to `::settings(calibration_flow_multiplier)`,
   range `0.13..2.0`, resolution `0.01`. The user is expected to:
   1. Run the test profile (next bullet).
   2. Compare the requested-flow (blue) and weight-rate (brown) graph lines.
   3. Drag the slider until the lines overlap.
   4. Press Ok — `de1_skin_settings.tcl:50-52` detects that the multiplier
      changed (compared against `::settings_backup`) and fires
      `set_calibration_flow_multiplier`.

   No safety dialog gates this slider (the larger calibration-page
   dialog at `de1_skin_settings.tcl:1075` exists, but only for the
   cuuid_12 sensor-calibration screen, not the flow MMR slider on the
   same page).

2. **Built-in test profile** — `de1plus/profiles/flow_calibration.tcl`.
   `profile_title "Test/Flow calibration"`, `beverage_type calibrate`.
   It's a step-pressure profile (1 bar → 9 bar in 1-bar steps). The
   `profile_notes` instruct: *"Put a 0.3mm puck simulator basket into
   a portafilter. Connect a bluetooth scale. Run this profile. Change
   the Flow value on the Calibration page, and rerun this profile.
   Repeat until the blue and brown lines follow each other."*

3. **Graphical Flow Calibrator (GFC) plugin** — registered as a
   submodule `de1plus/plugins/Graphical_Flow_Calibrator` (`.gitmodules`
   → https://github.com/Damian-AU/Graphical_Flow_Calibrator). The
   directory is **empty** in this checkout; the source lives in an
   external repo we did not pull. The `Streamline` skin
   (`de1plus/skins/Streamline/skin.tcl:736-741`) adds a "Calib" button
   that runs `page_show GFC` when this plugin is loaded. We cannot
   audit the GFC's math/UI from local sources; the plugin presumably
   automates the dispense-weigh-compute loop described in the test
   profile's notes, but verifying that requires fetching
   `github.com/Damian-AU/Graphical_Flow_Calibrator`.

### Math

**There is no automated math in the default-skin TCL.** The user reads
the graph and moves the slider manually. The slider's `-variable` is
the live setting; on dialog-Ok the setting is written through.

The Graphical Flow Calibrator plugin (external, unaudited) is the
de-facto source of the dispense-weigh-derive workflow. The canonical
formula assumed by the test-profile notes is `new_multiplier =
current_multiplier × (measured_weight_g / dispensed_volume_ml)` (1 g
water ≈ 1 ml at room temperature), but we cannot cite a TCL line
implementing this — it is presumably in the GFC plugin.

### Safety / clamps

- **No clamp on the wire-write path itself.** `de1plus/de1_comms.tcl:1334-1337`:
  ```tcl
  proc set_calibration_flow_multiplier {m} {
      ::comms::msg -NOTICE set_calibration_flow_multiplier "'$m'"
      mmr_write "set_calibration_flow_multiplier" "80383C" "04" \
          [zero_pad [long_to_little_endian_hex [expr {int(1000 * $m)}] ] 2]
  }
  ```
  Whatever multiplier the caller passes is encoded as `int(1000×m)` LE
  and shipped. Negative or absurd values would be silently truncated to
  whatever fits in the low 32 bits.
- **UI-level clamp via the slider widget.** The default skin slider
  (`de1_skin_settings.tcl:2402`) bounds 0.13..2.0 with 0.01 resolution.
  Only enforced if the user goes through the UI.
- **Startup integrity check.** `de1plus/utils.tcl:1312-1315`:
  ```tcl
  if {[string is double -strict $::settings(calibration_flow_multiplier)] != 1 \
      || $::settings(calibration_flow_multiplier) == "0.13"} {
      msg --WARNING "Flow calibration had invalid value: '$::settings(calibration_flow_multiplier)'"
      set ::settings(calibration_flow_multiplier) 1
  }
  ```
  Resets to `1.0` if non-numeric, or specifically the string `"0.13"`
  (the slider minimum, treated as a sentinel for "user dragged to floor
  by accident").
- **Startup default-resolution trick.** `de1plus/bluetooth.tcl:2099-2106`:
  the tablet stores `1.000` (3-decimal precision) as the "default,
  never user-touched" marker. On every BLE reconnect, if the local
  value is `"1.000"`, the app *reads* the factory-set multiplier from
  the DE1 instead of overwriting it. Any user-touched value becomes
  2-decimal (e.g. `1.00`) and is treated as authoritative — pushed back
  to the DE1 on connect. This is the legacy "Decent factory pre-
  calibrates the DE1, then the tablet defers to the machine on
  first contact" handshake.

### Reset to factory

**No dedicated reset path for flow calibration in the default skin's
calibration page.** The "Reset to factory" buttons for temperature and
pressure (`de1_skin_settings.tcl:2358-2360`, commented out — the
original buttons were removed) used `de1_send_calibration <sensor> 0 0 3`
on cuuid_12 with `calibcmd=3` (factory). Flow has no analogous button
because flow calibration lives on a different register (MMR `0x80383C`,
not cuuid_12) and the firmware does not expose a factory reset for
MMR registers — the closest is "set the multiplier to 1.0".

A user wanting to reset to factory therefore (a) drags the slider to
1.00 and Oks, or (b) deletes the local settings file (which on next
launch triggers the `1.000` sentinel handshake described above,
inheriting the factory pre-cal from the DE1 itself).

There is a separate per-profile flow-calibration purge — translation
key `"You are about to delete saved custom flow calibration settings
for all profiles, all profiles will revert back to using the apps flow
calibration setting"` (`translation.tcl:1479`) — which clears
profile-level overrides; it is **not** a wire-side reset.

### Sources

- `de1plus/skins/default/de1_skin_settings.tcl:2402` — manual slider widget (0.13..2.0)
- `de1plus/skins/default/de1_skin_settings.tcl:50-52` — Ok-button save trigger
- `de1plus/skins/default/de1_skin_settings.tcl:2368` — "x N.NN" multiplier display
- `de1plus/de1_comms.tcl:1334-1337` — `set_calibration_flow_multiplier` (the wire write)
- `de1plus/de1_comms.tcl:1324-1327` — `get_calibration_flow_multiplier` (MMR read)
- `de1plus/bluetooth.tcl:2092-2107` — startup handshake (1.000 sentinel)
- `de1plus/bluetooth.tcl:2532-2534` — MMR read decode (raw / 1000.0)
- `de1plus/utils.tcl:1312-1315` — settings-load integrity check
- `de1plus/machine.tcl:271` — default `calibration_flow_multiplier "1.000"`
- `de1plus/profiles/flow_calibration.tcl` — built-in test profile (the workflow trigger)
- `de1plus/vars.tcl:876-882` — `return_flow_calibration_measurement` (`mL/s` display formatter; unrelated to the multiplier — used for phase-1/2 flow display)
- `de1plus/binary.tcl:207-215` — comment "a very incorrect flow calibration might cause issues, so turning SAV off if SAW is on" — the only documented downstream behavioural concern
- `.gitmodules` + `de1plus/skins/Streamline/skin.tcl:736-741` — Graphical Flow Calibrator submodule (empty in this checkout)

## reaprime implementation

### User-facing flow

**None.** reaprime is a Dart library exposing the DE1 over a stable
interface plus an HTTP webserver. There is no Flutter widget, no
controller method beyond the getter/setter, and no measurement helper.
The setter exists for the consumer app (Crema, Bonsai, or whoever
embeds reaprime) to call — reaprime itself never invokes it except
during `applySettingsDefaults` to reset to `1.0`.

### Math

**Absent.** The setter accepts any `double` and writes
`int(1000 × multiplier)` after the wire-level clamp (next section).

### Safety / clamps

- **MMR-level clamp on raw value.**
  `lib/src/models/device/impl/de1/de1.models.dart:315-324`:
  ```dart
  calFlowEst(
    0x0080383C,
    4,
    MmrValueKind.scaledFloat,
    "Flow Estimation Calibration",
    readScale: 0.001,
    writeScale: 1000.0,
    min: 130,
    max: 2000,
  ),
  ```
  The `_writeMMRInt` helper (`unified_de1.mmr.dart:116-121`) calls
  `value.clamp(item.min!, item.max!)` if both bounds are set, so
  `setFlowEstimation(0.0)` writes raw `130` (multiplier `0.13`) and
  `setFlowEstimation(5.0)` writes raw `2000` (multiplier `2.0`).
- **No high-level dialog or warning** in any reaprime API surface.
- **No machine-state gate** — the write fires unconditionally if the
  DE1 is connected.

### Reset to factory

`lib/src/controllers/de1_controller.defaults.dart:49`:
```dart
await _de1?.setFlowEstimation(1.0);
```
called from `applySettingsDefaults`, exposed as
`DELETE /api/v1/machine/settings/reset` (8 MMR writes including this
one). There is no per-register reset endpoint and no analogue of TCL's
`1.000` sentinel handshake — reaprime always writes whatever the
consumer asked for, never defers to the firmware's pre-cal.

### Sources

- `lib/src/models/device/impl/de1/de1.models.dart:315-324` — `MMRItem.calFlowEst` (address, scales, clamps)
- `lib/src/models/device/impl/de1/unified_de1/unified_de1.mmr.dart:116-126` — `_writeMMRInt` clamp + `_writeMMRScaled`
- `lib/src/models/device/impl/de1/unified_de1/unified_de1.dart:156-159` — `getFlowEstimation()` (MMR read)
- `lib/src/models/device/impl/de1/unified_de1/unified_de1.dart:329-332` — `setFlowEstimation(multiplier)` (MMR write)
- `lib/src/models/device/de1_interface.dart:45-46` — abstract interface
- `lib/src/services/webserver/de1handler.dart:245-261` — HTTP `GET`/`POST /api/v1/machine/calibration` (the only consumer surface)
- `lib/src/controllers/de1_controller.defaults.dart:49` — reset-to-`1.0` baseline
- `lib/src/models/device/impl/mock_de1/mock_de1.dart:462,475-482` — mock that just stores the value (no clamp)

## Crema current state

### User-facing flow

**No UI surface today.** The setter is wired all the way out to the
Svelte bridge, but no settings panel exposes it. The Calibration
settings page (cuuid_12 sensor calibration) renders the Flow row in a
`notImplemented` state with the sub-text *"Flow calibration uses a
separate multiplier register (MMR 0x80383C); read it on the Brew
settings screen when that surface lands."*
(`web/src/lib/components/settings/sections/CalibrationSection.svelte:301-318`).

The Brew settings screen does not yet host a flow-calibration row.

### Math

Absent — same as the references. The setter accepts an `f32` multiplier.

### Safety / clamps

`core/de1-app/src/lib.rs:1224-1249` — `set_calibration_flow_multiplier`:

```rust
pub fn set_calibration_flow_multiplier(&self, multiplier: f32) -> CoreOutput {
    if let Some(out) = self.refuse_if_firmware_locked("set_calibration_flow_multiplier") {
        return out;
    }
    // Range 0.13..=2.0 multiplier — matches reaprime calFlowEst.
    let clamped = multiplier.clamp(0.13, 2.0);
    let raw = (clamped * 1000.0).round().clamp(0.0, 65_535.0) as u32;
    mmr_write_command(MmrRegister::CalibrationFlowMultiplier, raw, 4)
}
```

- **Clamp** at the Crema layer: 0.13..=2.0 — explicitly cites reaprime
  as canon (docs/40 §A row 5, docs/41 row 13).
- **Firmware-lock gate**: `refuse_if_firmware_locked` rejects writes
  while a firmware upload is in progress.
- **No machine-state gate** — Crema does not require Sleep/Idle for
  flow calibration writes (docs/18 §1 documents the safety analysis:
  flow cal is the mildest of the three calibration hazards, never
  affects pressure or temp cutoffs).
- **Tests cover the wire**: `set_calibration_flow_multiplier_clamps_to_reaprime_range`
  and `set_calibration_flow_multiplier_emits_len_byte_4_and_4_byte_payload`
  (`core/de1-app/src/lib.rs:5193-5230`).

### Reset to factory

`reset_machine_defaults` (`core/de1-app/src/lib.rs:1124-1199`) re-writes
the multiplier to `1.0` (raw `1000`, 4-byte LE) as part of the 8-write
settings-reset baseline — mirrors reaprime
`DELETE /api/v1/machine/settings/reset`. No per-register reset call.

### Sources

- `core/de1-protocol/src/mmr.rs:151,195,235` — register catalog entry, address `0x80_383C`, scale `0.001`
- `core/de1-app/src/lib.rs:1224-1249` — `set_calibration_flow_multiplier(f32)`
- `core/de1-app/src/lib.rs:1170-1175` — multiplier in `reset_machine_defaults` baseline
- `core/de1-app/src/lib.rs:5193-5230` — clamp + wire-byte-count tests
- `core/de1-ffi/src/lib.rs:773-779` — FFI export
- `core/de1-wasm/src/lib.rs:1097-1101` — WASM export
- `web/src/lib/core/index.ts:580-590, 952-957` — TS bridge contract + impl
- `web/src/lib/components/settings/sections/CalibrationSection.svelte:301-318` — placeholder row (notImplemented)

## D. Decent BLE protocol spec citations

The Decent firmware MMR map is **not openly published** as a versioned
document. Every reference in this audit cites either the legacy TCL
register description (`de1plus/de1_comms.tcl` comments) or reaprime's
`MMRItem` enum description strings, both of which were reverse-engineered
from Decent's released code and firmware notes posted to the
diy.decentespresso.com forum over time.

For flow calibration specifically:

- The wire byte format (4-byte LE, scaled `int(1000 × multiplier)`) is
  documented inside Crema in `core/de1-app/src/lib.rs:1224-1239` and
  matches the two reference implementations exactly (docs/41 row 13).
- The 0.13..=2.0 clamp is **reaprime-only convention**; it does not
  appear in any firmware-published document we could find. Crema follows
  reaprime per the docs/40 verdict.
- **The workflow itself** — what physical measurement the user takes,
  how the multiplier is derived from it, what error tolerance is
  acceptable — is **not** documented in the protocol. The closest thing
  to a spec is the test-profile `profile_notes` field
  (`flow_calibration.tcl:37`) and (presumably) the source of the
  external Graphical Flow Calibrator plugin.

## Diff analysis

| Concern | TCL | reaprime | Crema | Agree? |
|---|---|---|---|---|
| Wire address | MMR `0x80383C` (`de1_comms.tcl:1336`) | MMR `0x0080383C` (`de1.models.dart:316`) | MMR `0x80_383C` (`mmr.rs:195`) | yes |
| Wire encoding | `int(1000 × m)` LE, `Len=4` (`de1_comms.tcl:1336`) | `int(1000 × m)` LE, `Len=4` (`de1.models.dart:321`, `mmr.dart:124`) | `int(1000 × m)` LE, `byte_len=4` (`lib.rs:1247-1248`) | yes |
| Default multiplier | `1.000` (`machine.tcl:271`) | `1.0` (set via `applySettingsDefaults`, `de1_controller.defaults.dart:49`) | `1.0` (reset baseline `RESET_CAL_FLOW_EST_RAW = 1000`, `lib.rs:1145`) | yes |
| Safety clamp on wire write | none (`de1_comms.tcl:1334-1337`) | raw 130..2000 (= 0.13..=2.0), `de1.models.dart:322-323` + `mmr.dart:117-120` | 0.13..=2.0, `lib.rs:1246` | TCL diverges (no clamp); Crema follows reaprime (docs/40 §"Where reaprime and TCL disagree") |
| UI-level clamp | slider 0.13..=2.0 step 0.01 (`de1_skin_settings.tcl:2402`) | n/a (no UI) | n/a (no UI yet — placeholder in `CalibrationSection.svelte`) | UI-equivalent: TCL has slider, reaprime/Crema rely on caller |
| Settings-file integrity check | yes — reset to 1 if non-numeric or `"0.13"` sentinel (`utils.tcl:1312-1315`) | n/a (no persistent settings file) | n/a (preferences live in the web store; the multiplier is read from the DE1 each connect) | TCL-only; not applicable to the others |
| Startup handshake (1.000 sentinel) | yes — defers to DE1 factory value if local is `"1.000"` (`bluetooth.tcl:2099-2106`) | no — always writes `1.0` on reset, never on connect | no — never writes on connect; reads on demand | TCL diverges; the reaprime/Crema model is "the DE1 is the source of truth, read it; only write on explicit user action" |
| Reset-to-factory mechanism | (a) drag slider to 1.00; (b) delete settings file → 1.000 sentinel pulls factory value from DE1 | write `1.0` via `applySettingsDefaults` (HTTP `DELETE /api/v1/machine/settings/reset`) | write `1.0` via `reset_machine_defaults` | functional parity at "write 1.0"; TCL has the extra factory-pull path |
| Per-register reset wire packet | no (MMR has no reset opcode) | no | no | yes (firmware doesn't expose one) |
| User-facing math | none in default skin; (presumed) `new = current × measured/dispensed` in external GFC plugin | none | none | TCL has it indirectly via the external GFC plugin; reaprime/Crema have no math at all |
| Dispensed-water target | implied by test profile `flow_calibration.tcl` — step-pressure 1→9 bar through a 0.3 mm puck simulator basket | n/a | n/a | TCL only |
| Measurement input | bluetooth scale reading + visual graph comparison (per `flow_calibration.tcl` profile notes) | n/a | n/a | TCL only |
| Machine-state precondition | calibration screen registered with `page_to_show_when_off` (Sleep/Idle only) — but the flow slider rides the same page so inherits this gate (`de1_skin_settings.tcl:2190` + `gui.tcl:2394-2453`) | none | none for flow (docs/18 §1 explicitly judged flow as the mild case; pressure/temperature gates still TBD) | TCL gates by UI navigation; reaprime/Crema do not gate |
| Mid-shot write rejection | no explicit check; UI page-gate is the only barrier | no | `refuse_if_firmware_locked` only (firmware upload only) | none of them refuse mid-shot at the write layer |
| Confirmation dialog | no (the existing calibration-page dialog at `de1_skin_settings.tcl:1075` covers the cuuid_12 sensor calibration only; the flow slider drag does not retrigger it) | n/a (no UI) | n/a (no UI yet) | no consensus to enforce |
| BLE characteristic | MMR (R1) — same as flush, hot-water, phase-1/2 flow rates | MMR (R1) | MMR (R1) | yes |
| Read scale | `raw / 1000.0` (`bluetooth.tcl:2533-2534`) | `readScale: 0.001` (`de1.models.dart:320`) | `0.001` (`mmr.rs:235`) | yes |

## Verdict

**Straightforward to add?** **Partially.** The 1-knob slider equivalent
(let the user type/drag a multiplier in 0.13..=2.0, write it, read it
back) is straightforward: every layer is already wired and verified.
The *automated* dispense-weigh-derive workflow is **not** straightforward
to add from local sources alone — the canonical implementation lives in
an external submodule we don't have, and reaprime offers nothing here.

**Verified approach we can use?** **reaprime, plus a Crema-original
UI.** Per the project's "defer to reaprime when legacy + reaprime
disagree" guidance:

- Wire format, default value, clamp range, reset path: **reaprime is
  canonical** (and TCL agrees on the wire format; only diverges on the
  clamp, which we already follow reaprime on).
- UI/UX: **Crema-original.** reaprime has none; TCL's default-skin slider
  is the only verified UI pattern, and it's a simple bounded numeric
  input. The external GFC plugin's auto-derive flow is unverified — we
  should not clone it from notes alone.

**Risks**:

- **Silent drift.** Bad flow cal is the mildest of the three calibration
  hazards (docs/18 §1) but still causes weight-stop misfires that the
  user attributes to grind/dose/profile. A read-back-after-write UI
  step is worth ~5 min of build to head off this support burden.
- **The 1.000 sentinel divergence.** Crema deliberately *does not*
  implement TCL's "defer to firmware on first connect if local value is
  `1.000`" handshake. This is a small behavioural change for users
  migrating from Decent Espresso/de1app: a freshly-installed Crema
  paired with a factory-calibrated DE1 will read the factory value out
  of MMR on demand, but won't proactively push `1.0` over it. This is
  probably correct (the firmware is the source of truth) but worth
  flagging in user-facing migration notes.
- **No mid-shot guard.** None of the three implementations refuse a
  flow-cal write mid-shot. docs/18 §1 judged this acceptable for flow
  specifically (mid-shot flow-cal does not affect pressure or temp
  cutoffs, only the in-flight estimate). If we ever extend the same
  safety analysis to pressure/temp cal, we should not extend the
  same leniency.

**Recommended next steps** (for whoever picks up the implementation):

1. **Add a Brew-settings row** ("Flow calibration multiplier") with a
   stepper or slider bounded 0.13..=2.00 step 0.01, default 1.00. Read
   the current value from `de1MachineInfo[CalibrationFlowMultiplier]`
   (already populated by the MMR read path) and write through
   `app.core.setCalibrationFlowMultiplier(value)`. The
   `CalibrationSection.svelte:304` sub-text already promises this
   surface.
2. **Wire the read on Brew-settings mount** (mirror
   `CalibrationSection.refresh` for the cuuid_12 reads) so the user
   sees the factory-set or last-written value when they open the
   screen, not a stale snapshot.
3. **Add a "Reset to 1.0" affordance** next to the row — matches
   `reset_machine_defaults`' behaviour for this register. Do **not**
   add a "Reset to factory" wire packet; the firmware doesn't expose
   one.
4. **Display the read-back value after a write succeeds**, so the user
   gets visible confirmation the new value landed (helps with the
   "silent drift" risk above). The read already lands in
   `de1MachineInfo` automatically via the existing event stream.
5. **Defer the auto-derive (dispense-weigh-compute) workflow.** Treat
   the manual slider as v1; the auto-flow as a v2 contingent on
   either (a) fetching and auditing the external GFC plugin source,
   or (b) asking the reaprime maintainers whether they intend to ship
   a `MachineService`-level calibration helper.

## Open questions for upstream

1. **For reaprime maintainers:** is there a plan to expose a calibration
   helper at the controller/service layer (analogous to
   `applySettingsDefaults`) — i.e. "dispense N ml of hot water,
   compare to scale reading, write the derived multiplier"? If yes,
   we want to align Crema's UI to whatever shape that helper takes
   rather than building a one-off.
2. **For Decent (firmware):** is the 0.13..2.0 multiplier range a
   firmware-side accepted range, or just reaprime's defensive clamp?
   We follow reaprime today (docs/40 §A row 5) but a firmware-level
   spec would let us narrow or widen the clamp confidently.
3. **For Decent:** what is the firmware's behaviour if a `0` or
   negative multiplier is written? Our clamp prevents this, but the
   answer informs whether the clamp should be a `Result::Err` ("write
   refused") rather than a silent floor.
4. **Should we pull `Graphical_Flow_Calibrator` source and audit it
   for the canonical auto-derive math?** Submodule URL
   `https://github.com/Damian-AU/Graphical_Flow_Calibrator`. If we want
   the auto-flow in Crema, this is the only verified reference.
