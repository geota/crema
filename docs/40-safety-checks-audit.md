# 40 — Safety-checks audit (clamps, confirms, handshake guards)

A three-category comparison of Crema vs. legacy `de1plus` TCL vs. reaprime
on the protections each codebase puts between the user and a damaging /
out-of-range value on the DE1 BLE wire. Crema gaps with HIGH or CRITICAL
severity are closed in the accompanying PR; MEDIUM and LOW gaps are
documented for follow-up.

The driving principle, per the user's earlier guidance:
**"defer to reaprime when legacy + reaprime disagree on a missing-
capability mitigation."** Where TCL and reaprime disagree on a clamp,
the wider of the two is used and the divergence is called out below.

## A — Numeric clamps on writes

The table covers every user-driveable numeric setting that reaches an
MMR register or a `ShotSettings` packet. "Shell" refers to the web
front-end (Svelte steppers / modal); "core" refers to
`core/de1-app/src/lib.rs` setters; "Gap" is YES when a malicious or
buggy caller could push a value past the firmware-safe range
**without** the Crema core stopping it (defense-in-depth — replays,
the JS API surface, and the future Android bridge all hit the core,
not the shell).

| # | Concern | Crema (core) | Crema (shell) | TCL | reaprime | Gap | Severity | Action |
|---|---|---|---|---|---|---|---|---|
| 1 | Heater voltage must be 120 or 230 V | enum-only (`{120, 230}` rejects everything else with `InvalidArg`) | `MainsConfirmModal` type-to-confirm | direct button writes raw, no confirm | enum `De1HeaterVoltage`, but `unset` maps `firstWhere` orElse | none | CRITICAL | already closed (kept) |
| 2 | Fan threshold ≤ 50 °C | **clamped 0..=50** (this PR) | n/a (no UI) | none | `MMRItem.fanThreshold min=0 max=50` | closed | HIGH | clamped — `set_fan_threshold` |
| 3 | Steam high-flow start ≤ 4.0 s | **clamped 0..=4.0 s** (this PR) | n/a (no UI) | none | description: "Valid 0.0–4.0. 0 may result in an overheated heater" | closed | HIGH | clamped — `set_steam_highflow_start` |
| 4 | Hot-water idle temp ≤ 100 °C (avoid boil → pressure event) | **clamped 0..=100 °C** (this PR) | n/a (no UI) | none | none | closed | HIGH | clamped — `set_hot_water_idle_temp` |
| 5 | Flow-calibration multiplier 0.13..=2.0 | **clamped** (this PR) | n/a (no UI) | none | `MMRItem.calFlowEst min=130 max=2000` (raw) | closed | HIGH | clamped — `set_calibration_flow_multiplier` |
| 6 | Cup warmer ≤ 80 °C | **clamped 0..=80 °C** (this PR) | `StStepper min=0 max=80` | none | none | closed | MEDIUM (shell already caps, but Bengle-only) | clamped — `set_cup_warmer_temperature` |
| 7 | `ShotSettings.group_temp_c` ≤ 105 °C | **clamped 0..=105** (this PR) | n/a — set via `set_steam_hotwater_settings` | `vars.tcl:range_check_shot_variables` 0..105 | none | closed | HIGH | clamped — `clamp_shot_settings` |
| 8 | `ShotSettings.hot_water_temp_c` ≤ 100 °C | **clamped 0..=100** (this PR) | n/a | none | none | closed | HIGH | clamped — `clamp_shot_settings` |
| 9 | `ShotSettings.steam_temp_c` ∈ {0} ∪ [130, 170] °C | **clamped** (this PR) — values in (0, 130) snap to 0, values > 170 clamp to 170 | n/a | `binary.tcl` forces `<135` → 0 (heater off) | none; default skin slider 134..170 | closed | HIGH | clamped — `clamp_shot_settings` |
| 10 | `ShotSettings.steam_timeout_s` finite | wire-cap 0..=255 via `u8p0_encode` | n/a | TCL maps 0 → 255 ("don't turn off"); we don't | reaprime sends raw | YES (silent) | LOW | doc only |
| 11 | Steam flow ≤ ~7 ml/s | wire-cap (`* 100` then clamp to u16 → 655.35 ml/s) | n/a (no UI) | none | none | YES | LOW (firmware refuses; no damage) | doc only |
| 12 | Hot-water / flush flow ≤ ~15 ml/s | wire-cap (`* 10` → u16, 6553 ml/s) | n/a | none | none | YES | LOW | doc only |
| 13 | Flush temp 60..=100 °C | wire-cap only (u16 / 10 → 6553 °C) | **stepper-enforced 60..100** (`PreinfFlushStepper` `min={60} max={100}` — `QStepper.inc()` + click-to-type `commit()` both clamp) | none | none | YES | MEDIUM | closed at shell layer; core wire-cap stays as last-line guard |
| 14 | Phase 1 / Phase 2 flow ≤ ~10 ml/s | wire-cap (`* 10` → u16) | n/a | none | none | YES | LOW | doc only |
| 15 | Espresso warmup timeout ≤ 60 s | wire-cap (`*10` → u16) | n/a | none | none | YES | LOW | doc only |
| 16 | Tank refill threshold 3..=70 mm | wire-cap via U16P8 (max ~256 mm) | n/a | none (legacy slider 3..70 — UI only) | none | YES | LOW | doc only |
| 17 | Fan threshold reset baseline = 50 (not 55) | yes — `RESET_FAN_THRESHOLD_C = 50` matches reaprime's post-clamp behaviour | n/a | n/a | source says 55 but `_writeMMRInt` clamps to 50 silently | none | LOW | already closed (kept) |
| 18 | Tank temperature threshold sensible (legacy preheat dance) | sets the immediate value only; the 60 °C / 4 s dance is a shell concern (`docs/14` §4.2) | n/a (no UI) | yes (preheat with 60 °C if target ≥ 10) | none | none (not wired) | LOW | doc only |
| 19 | Profile-frame per-frame caps (temp, pressure, flow per frame) | de1-domain `Profile::assemble` rejects `NoSteps` / `TooManySteps`; per-frame wire encoding clamps via the fixed-point encoders | n/a | `range_check_shot_variables` + per-frame range checks | none | YES (silent — encoder clamps but doesn't reject) | out-of-scope (profile-frame validation deferred per task brief) | doc only |
| 20 | Steam eco temperature in steam range | core uses `steam_eco_temp` default 130 °C; not user-driveable today | n/a | yes (eco_steam_temperature; defaults safe) | none | none | LOW | n/a |

### Crema is stricter than both references

- **Heater voltage**: Crema rejects any value not in `{120, 230}` with
  a typed error; TCL silently writes whatever was passed,
  reaprime silently degrades to `unset`.
- **Reset baseline fan**: Crema writes 50 directly; reaprime writes 55
  and relies on the wire-side `_writeMMRInt` clamp.

Don't relax these.

### Where reaprime and TCL disagree

- **Fan threshold**: TCL has no clamp; reaprime caps at 50. We follow
  reaprime (closed gap #2).
- **Cal flow multiplier**: TCL has no clamp; reaprime caps at 0.13..2.0.
  We follow reaprime (closed gap #5).
- **Group temp**: TCL caps at 105 °C in `range_check_shot_variables`;
  reaprime doesn't clamp. We follow TCL (closed gap #7).

## B — Confirmation prompts on irreversible / dangerous actions

| Concern | Crema | TCL | reaprime | Gap | Severity | Proposed fix |
|---|---|---|---|---|---|---|
| Heater voltage change (mains mismatch can permanently damage heater) | `MainsConfirmModal` (type-to-confirm) | direct button — no confirm | no confirm | none | CRITICAL | already closed (kept) |
| Line frequency override (50/60 Hz) | `MainsConfirmModal` reused | n/a (auto-detect only) | n/a | none | LOW | kept |
| Reset 8 MMR machine settings to defaults | `window.confirm` plain prompt | n/a (no equivalent) | no confirm — `DELETE /api/v1/machine/settings/reset` is silent | none | MEDIUM | kept; a typed modal is overkill for the blast radius ("user retunes settings") |
| Reset user preferences (brew defaults, display, sound) | `window.confirm` | n/a | n/a | none | LOW | kept |
| Skin removal | n/a (no skin-removal UI in shell) | n/a | `AlertDialog` with Cancel/Remove | none | LOW | shell follow-up if/when skins land |
| Firmware update start | **type-to-confirm gate** in place — `FirmwareUpdateModal` mounts behind the Settings → Machine "Update firmware…" button; confirm fires a `// TODO(#55)` stub that surfaces "not yet implemented" inline | n/a | no confirm (POST `/api/v1/machine/firmware` starts immediately) | n/a | gate landed; flow deferred to #55 | wire `onConfirm` to the real upload flow when v2 ships |
| Factory reset (clear all Crema localStorage) | row marked `notImplemented`; click is a no-op | n/a | n/a | YES (cosmetic) | LOW | implement only when there's a real handler; row already pilled |
| Profile delete | implementation deferred (no UI) | TCL has popup confirm in profile editor | none | n/a | deferred (no Crema profile-delete UI yet) | when adding, follow legacy and `window.confirm` |
| Bean delete | exists in bean library (see `docs/32`) | TCL no equivalent | n/a | check separately | out of scope | — |
| Tank-empty disabling water-heat | not surfaced (firmware-level) | TCL warns | reaprime no confirm | n/a | LOW | the firmware itself disables; UI surfacing is cosmetic |

**Proposed fix sketches (deferred — UI work needs design review):**

1. *Firmware update start (CRITICAL when v2 lands)*: **gate landed** —
   `web/src/lib/components/settings/FirmwareUpdateModal.svelte` mirrors
   `MainsConfirmModal`'s type-to-confirm scheme. The user types
   `UPDATE` (a fixed literal, not the build hash — chosen because the
   from/to builds are already shown directly above the input, so
   echoing the build there would be redundant and would also leak the
   "should I update?" question into the confirmation token). When #55
   wires the v2 upload flow, the seam is `MachineSection.svelte`'s
   `onUpdateConfirm` handler (marked `// TODO(#55)`); today it surfaces
   "Firmware update is not yet implemented (#55)." inline.

## C — Connection-state / handshake guards

| Guard | Crema | TCL | reaprime | Gap | Severity | Action |
|---|---|---|---|---|---|---|
| Refuse writes during a firmware upload | `refuse_if_firmware_locked` stub on every setter (v2 lights it up; v1 always returns `false`) | yes — `de1_comms.tcl` BLE queue refuses writes when `firmware_uploading` | yes (per-call) | none | CRITICAL | kept (groundwork) |
| Refuse profile upload during a shot | `upload_profile` does NOT check `MachineState != Idle` — it accepts any state | TCL refuses (state-gated) | reaprime accepts in any state but firmware aborts in flight | YES | HIGH | proposed fix below |
| Refuse profile upload while another upload is in flight | `upload_profile` aborts the prior with `ProfileUploadFailed::Aborted` and starts the new one (race-safe but does not refuse) | TCL queues serially | reaprime serializes via futures | none (different semantics, equivalent safety) | LOW | kept |
| Refuse tare when no scale paired | `tare_scale` is a no-op without a scale | TCL no-op | reaprime no-op | none | LOW | kept |
| Refuse scale commands during a shot | scale push commands not gated | TCL gates a few | reaprime gates | partial | MEDIUM (out of scope — scale work is reaprime's per past guidance) | doc only |
| Refuse writes before `ShotSettings` first read | not gated (sends default if `steam_hotwater_settings` is `None`) | TCL waits for the read in some paths | reaprime reads on connect | YES | MEDIUM | the wire packet is benign defaults; firmware will accept |
| Refuse `set_steam_hotwater_settings` outside `Idle` / `Sleep` | not gated | TCL gates | reaprime not gated | YES | LOW | the firmware overrides mid-shot anyway |

**Proposed fix for HIGH gap "profile upload during a shot"
(deferred — needs UX decision):**

Add an early `if matches!(self.last_state_info.as_ref().map(|s| s.state),
Some(MachineState::Espresso | MachineState::Steam | MachineState::HotWater
| MachineState::HotWaterRinse))` check at the top of
`CremaCore::upload_profile`. On hit, return
`Err(AppError::InvalidArg { field: "machine_state", … })` with a
human-readable reason ("profile upload refused while a shot is in
progress"). The shell already debounces the upload trigger behind the
profile-picker tap, but a replay or external JS caller could push an
upload mid-shot; the firmware response is undefined and a recent
similar bug (de1app v1352, firmware NoAC light wipe) shows the class
is live. The shell would surface the error as a toast and re-arm the
upload once `state` returns to `Idle`.

## Severity rationale

- **CRITICAL** — could damage the machine or cause a firmware-level
  error. Closed: #1 (heater voltage). Deferred: firmware-update
  start (no UI yet).
- **HIGH** — could leave the user in an unrecoverable state, or
  sends a wildly invalid value to the firmware. **Closed in this PR**
  (#2–9). Deferred: profile-upload-during-shot guard.
- **MEDIUM** — silent no-op or odd behavior, but no harm.
  Doc-only.
- **LOW** — cosmetic or "would just look weird." Doc-only.

## Test plan

The closed Category A gaps are covered by 9 new unit tests in
`core/de1-app/src/lib.rs`:

- `set_fan_threshold_clamps_above_50c`
- `set_steam_highflow_start_clamps_above_4_seconds`
- `set_hot_water_idle_temp_clamps_above_100c`
- `set_cup_warmer_temperature_clamps_above_80c`
- `set_calibration_flow_multiplier_clamps_to_reaprime_range`
- `set_steam_hotwater_settings_clamps_group_temp_above_105c`
- `set_steam_hotwater_settings_clamps_hot_water_temp_above_100c`
- `set_steam_hotwater_settings_clamps_steam_temp_above_170c`
- `set_steam_hotwater_settings_snaps_sub_steam_temp_to_zero`

Each test exercises both an in-range value (passes through unchanged)
and an over-range / under-range value (clamps to the documented
boundary). Existing behavior tests (`reset_machine_defaults_emits_8_writes_with_known_baselines`,
`set_heater_voltage_*`) continue to pass — the new clamps don't
disturb the baseline reset values, which all sit inside the new
clamp windows.

## Out of scope

- Profile-frame-level field validation (per-frame temp / pressure /
  flow ranges). The fixed-point encoders silently clamp, and
  `Profile::assemble` rejects structural errors; per-field semantic
  validation is a separate body of work.
- BLE-level guards (MTU, gap, reconnect) — already in core.
- Scale-side commands — reaprime's responsibility per past guidance
  (`docs/30`).
