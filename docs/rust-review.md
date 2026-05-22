# Rust review — Crema core spot-check

**Date:** 2026-05-22
**Scope:** `core/` workspace (6 crates, ~20 k LoC, 348 tests, all green;
`cargo clippy --workspace --all-targets` clean as of HEAD `7201c91`)
**Stance:** read-only spot review. The code has passed two scalar-fidelity
audits vs the legacy Tcl app and a three-lens rust / clean-code / security
review with **no Critical or High findings**. The bar here is correspondingly
high — this report mostly confirms how little is left to find.

---

## Summary

- **Critical / High:** none.
- **Major:** 1 (one real convention drift on an FFI event; the rest is honest).
- **Minor:** 4.
- **Nit:** 3.

The bridge surfaces are at exact parity (`de1-ffi` and `de1-wasm` expose
59 public methods each, identical fn-name set). Error types use `thiserror`,
named-field variants, and `#[non_exhaustive]` consistently. Tests are
deterministic — no real-time, no randomness, no global mutable state (one
`OnceLock` for the compile-time-embedded built-in profile JSON, idempotent).
`unsafe_code = "forbid"` is set workspace-wide and only opted out in the two
bridge crates (where UniFFI / wasm-bindgen require it for generated glue).

The single substantive convention drift is below as **Major #1**.

---

## Major

### M1 — `Event::ShotSettingsRead` carries `_c` / `_s` / `_ml` suffixes that `Event::Telemetry` deliberately dropped

- **Location:** `de1-app/src/event.rs:330-345`
- **Issue:** `Event::ShotSettingsRead` has `steam_temp_c`, `steam_timeout_s`,
  `hot_water_temp_c`, `hot_water_volume_ml`, `hot_water_timeout_s`,
  `espresso_volume_ml`, `group_temp_c`. The sibling variant
  `Event::Telemetry` (lines 102-130) in the same enum names equivalent
  scalars without suffixes (`head_temp`, `mix_temp`, `steam_temp`,
  `dispensed_volume_ml` — wait, this one *does* keep `_ml` — see N1
  below). The `_c` / `_s` suffixes on `ShotSettingsRead` were inherited
  from the wire-decode struct `de1_protocol::ShotSettings`
  (`de1-protocol/src/command.rs:69-81`), which per convention §3 keeps
  firmware byte names. The FFI value-type rename (convention §2) was
  applied to `Telemetry` and `WaterSessionCompleted` but missed here.
  HANDOFF.md §3 ¶2 is explicit: *"`Event::Telemetry` has `elapsed: u32`
  not `elapsed_ms`; units are documented, not named. The only place units
  survive in Rust field names is the wire-decode layer."*
- **Fix:** rename `steam_temp_c → steam_temp`, `steam_timeout_s →
  steam_timeout`, `hot_water_temp_c → hot_water_temp`,
  `hot_water_volume_ml → hot_water_volume`, `hot_water_timeout_s →
  hot_water_timeout`, `espresso_volume_ml → espresso_volume`,
  `group_temp_c → group_temp`. Update the two-line mapping in
  `de1-app/src/lib.rs:1619-1627` (`handle_shot_settings_read`) and the
  six call sites in the test fixture (`hotwater_settings` at
  `de1-app/src/lib.rs:2738-2748`). Regenerate typeshare bindings; the
  web/Android shells need the matching field rename. Doc-comments already
  carry the units explicitly. Format-version bumps for persisted types
  are not needed — this is event traffic, not storage.

---

## Minor

### m1 — Five FFI-surface enums inside `Event` variants lack `#[non_exhaustive]`

- **Locations:**
  - `de1-domain/src/stop.rs:105-114` (`StopReason`)
  - `de1-domain/src/shot.rs:29-42` (`ShotPhase`)
  - `de1-domain/src/water.rs:23-30` (`WaterSessionKind`)
  - `de1-domain/src/steam.rs:91-…` (`SteamClogReason`)
  - `de1-protocol/src/mmr.rs:98-170` (`MmrRegister`)
- **Issue:** HANDOFF.md §3 ¶5 enumerates `Event`, `Source`, `Command`,
  `WriteTarget`, `MachineRequest` as the FFI enums that need
  `#[non_exhaustive]`. The convention is correctly applied to those five.
  But every one of the above is *also* reachable via FFI through an
  `Event` variant payload (`Event::StopTriggered { reason: StopReason }`,
  `Event::ShotPhaseChanged { phase: ShotPhase }`, etc.) and ships in the
  TS / Kotlin bindings (`core/bindings/crema-core.ts:776`, `:790`,
  `:798`, `:872`). Adding a variant — and `MmrRegister` adds them
  routinely; `FlushTemp` and `SchedIdle` were added in the last two
  weeks — is a breaking change for any out-of-workspace consumer's
  exhaustive `match`. The shipping web shell is in-workspace so it
  doesn't bite; an Android app or third-party consumer would.
- **Fix:** add `#[non_exhaustive]` to each. Internal exhaustive matches
  within the workspace keep compiling (the attribute only affects
  out-of-crate matches). For TS the generated string-enum stays
  identical; for Kotlin the `sealed class` consumer would need
  `else -> …` arms. `MachineState` and `SubState`
  (`de1-protocol/src/state.rs:12, 98`) are a judgment call — they're
  pinned 1:1 to firmware u8s and the firmware does add new values
  (`SchedIdle = 21`, `SteamRinse = 16`); recommend marking them too for
  symmetry with `MachineRequest`, which already has it.

### m2 — `HeaterTweaks` public struct fields keep `_c` / `_per_s`-shape suffixes

- **Location:** `de1-app/src/lib.rs:1762-1780`
- **Issue:** the composite-arg record exposes `hot_water_idle_temp_c: u8`,
  `phase_1_flow_rate: f32` (mL/s implied by doc), `flush_flow_rate: f32`,
  etc. By the same rule that justifies M1 above, the `_c` should drop
  (`hot_water_idle_temp`). The `_flow_rate` names without an explicit
  `_per_s` are already in line, and the `Duration` fields
  (`espresso_warmup_timeout`, `flush_timeout`) correctly carry no suffix.
  Note the **bridge layer is a legitimate exception** here — `de1-ffi/
  src/lib.rs:282-299` (`HeaterTweaksRecord`) deliberately keeps
  `_seconds` / `_ms` on the `Duration`-flattened scalars and documents
  why ("`Duration` does not cross UniFFI cleanly, so timeouts are flat
  scalars (seconds / ms) documented in their field names"). That's
  correct under convention §3 ¶1. Only the core's `HeaterTweaks` (which
  *does* use `Duration` internally) has the inconsistency.
- **Fix:** rename `hot_water_idle_temp_c → hot_water_idle_temp` in the
  struct and the test fixture (`de1-app/src/lib.rs:3119`); update the
  one call site at lib.rs:698 (the field projection inside
  `set_heater_tweaks`).

### m3 — Profile-upload error mapping silently coerces unexpected `AppError`s to `Empty`

- **Locations:**
  - `de1-wasm/src/lib.rs:695-711`
  - `de1-ffi/src/lib.rs:717-727`
- **Issue:** both bridge crates have an identical `match AppError` block:
  `NoSteps → Empty`, `TooManySteps {…} → TooManySteps {…}`, **any other
  variant (including `Serialization`) → `Empty`**. The comment is honest
  ("`Serialization` is for shot history; upload_profile cannot produce
  it. Fall through to Empty as the safest default") — and today it
  can't fire — but the moment a fourth `AppError` variant gets added
  upstream of `upload_profile` it will be silently misclassified as an
  empty-profile failure with no message. The error message also gets
  lost. This is the classic "wildcard arm hides future bugs" smell.
- **Fix:** two options, in increasing order of work:
  1. *Minimum:* add a `ProfileUploadFailure::Internal { message: String
     }` variant in `de1-app/src/event.rs` (it's already
     `#[non_exhaustive]`) and route the wildcard there with the original
     error's `to_string()`. Both bridge crates' wildcards become
     `_ => ProfileUploadFailure::Internal { message: err.to_string() }`.
  2. *Better:* move the entire match to one helper on `de1-app` itself
     — `pub fn profile_upload_failure_from_app_error(e: AppError) ->
     ProfileUploadFailure` — and have both bridges call it. The
     `Internal` variant lives next to the helper, the duplication
     disappears, and the bridges shrink by ~15 lines each.

### m4 — `set_steam_hotwater_settings` and `_for_eco` clone the cached settings on every read path

- **Location:** `de1-app/src/lib.rs:772-781` (`steam_settings_for_eco`)
- **Issue:** `let mut settings = self.steam_hotwater_settings.clone().
  unwrap_or_default()`. `ShotSettings` is `Clone` but holds plain
  scalars (it's a 7-field `f32`/`u8`-only struct in
  `de1-protocol/src/command.rs:60-83`); the clone is essentially a
  memcpy. **The clone is correct** — the function then mutates fields
  on the local copy. The improvement is cosmetic: `ShotSettings` is
  small enough to be `Copy`. Adding `Copy` to it would:
  - elide the `.clone()` here and at lib.rs:1618 (`self.steam_hotwater_settings = Some(settings.clone())`),
  - flag any caller that accidentally hands out the cached object
    expecting in-place mutation,
  - cost zero — it's already `Clone + PartialEq` and has no heap.
- **Fix:** add `Copy` to `ShotSettings`'s derive list at
  `de1-protocol/src/command.rs:58`; remove the two `.clone()` calls.

---

## Nit

### n1 — `dispensed_volume_ml` on `Event::Telemetry` keeps the `_ml` suffix the rest of the event variant drops

- **Location:** `de1-app/src/event.rs:119`
- **Issue:** `dispensed_volume_ml: f32` lives next to `head_temp: f32`,
  `mix_temp: f32`, `group_pressure: f32` — all stripped. Doc says
  "Running shot volume dispensed so far, mL". The persisted-history
  rename (commit `be38ae6`) dropped this for `ShotMetrics`
  (`peak_pressure_bar` → `peak_pressure`, `total_water_ml` →
  `total_water`); the FFI event missed the parallel rename.
- **Fix:** rename `dispensed_volume_ml → dispensed_volume` here and
  at the one source-side mapping (`de1-app/src/lib.rs:1201`) and the
  one test reference. Regenerate bindings. Same kind of fix as M1; this
  one is a single field and could go in the same patch.

### n2 — `firmware_write_frame` writes `u8::try_from(FIRMWARE_FRAME_DATA_LEN).unwrap_or(u8::MAX)` for a compile-time `16`

- **Location:** `de1-protocol/src/firmware.rs:168`
- **Issue:** runtime fallibility for what is statically known to be
  `0x10`. The comment notes the intent ("`FIRMWARE_FRAME_DATA_LEN` is
  the compile-time constant 16 — fits a u8") — the construct is just
  the cheapest way to satisfy `cast_possible_truncation = "deny"`.
- **Fix:** `const _: () = assert!(FIRMWARE_FRAME_DATA_LEN <= u8::MAX as
  usize);` once at the top of the module, then `packet[0] =
  FIRMWARE_FRAME_DATA_LEN as u8;` here with a `#[allow]` on the cast.
  Or `#[allow(clippy::cast_possible_truncation)]` on the line. Tiny.

### n3 — `WaterMonitor::on_state_info` does `self.session.take().expect("checked Some above")`

- **Location:** `de1-domain/src/water.rs:109-117`
- **Issue:** the `.expect("checked Some above")` reads correctly today,
  but Rust 2024's `let_chains` could rewrite the guard so the take is
  inside the chain — `if self.session.as_ref().is_some_and(|s| new_kind
  != Some(s.kind)) && let Some(mut session) = self.session.take() { …
  }` — and the expect goes away. Marginal.
- **Fix:** see above. The current code is correct; this is a "remove a
  Rust-2024-era papercut" tweak.

---

## Top 5 worth doing

1. **M1** — rename `Event::ShotSettingsRead` fields to drop `_c` /
   `_s` / `_ml`. ~10-line patch in `event.rs` + the handler in `lib.rs`
   + regenerate bindings. This is the only one that genuinely matters,
   and small enough to land in one commit.
2. **N1** — drop `_ml` from `Event::Telemetry.dispensed_volume_ml` in
   the same patch as M1. Same diff shape, same binding regen.
3. **m1** — add `#[non_exhaustive]` to the five nested FFI enums (and
   optionally `MachineState`/`SubState`). Five-line patch across five
   files; no behavioural change.
4. **m3** — introduce `ProfileUploadFailure::Internal { message }` and
   pipe the bridge wildcards through it. Inoculates against silent
   misclassification when `AppError` grows.
5. **m2** — drop `_c` from `HeaterTweaks.hot_water_idle_temp_c`. One
   field rename + one test fixture line.

Everything below that is genuinely just nits. The code is in good shape
— diminishing returns past this list. Honest recommendation: bundle
M1 + N1 (one commit, one binding regen) as the main win, and treat the
rest as opportunistic cleanup whenever the surrounding code is touched.
