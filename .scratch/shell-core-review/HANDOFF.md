# Handoff — T1 core-export issues

**Updated:** 2026-06-13 · **Branch:** `effect/phase-0-spike` · **Worktree:** clean (only
pre-existing untracked `.scratch/android-compose-polish/`, `.scratch/phone-impl/`, `Task`).

## What this is

Implementing the T1 issues from `.scratch/shell-core-review/issues/` (the holistic
review backlog), one commit per issue. Goal: move drift-prone domain logic into the
Rust core and route every shell through it.

## Done (committed)

| Commit | What |
|--------|------|
| `6c41640` | **wip checkpoint** — pre-existing android-consolidation WIP (sort keys, filter chips, phone history). NOT mine; do not re-touch / re-attribute. |
| `3829149` | docs: punchlist + 49 issue files + INDEX. |
| `68e4975` | **issue 01** (T1-01+T3-01) — `brew_ratio` via UniFFI; one 1-decimal `formatRatio`. ✅ |
| `e43cfa2` | **issue 02** (T1-02+T4-12) — band-aware freshness via UniFFI `roast_band`/`days_off_roast`/`roast_freshness`; shared `freshnessColor`. ✅ (web label-vocab T3-02 deferred) |

Remaining T1: **03–11** (see INDEX.md). 03 is the last P1.

## The recipe (followed for 01 & 02 — reuse it)

1. **Core export.** Add `#[uniffi::export] pub fn …` to `core/de1-ffi/src/lib.rs`
   (free fns live ~L347+, after `brew_ratio`/`roast_freshness`). Mirror the existing
   wasm export in `core/de1-wasm/src/lib.rs` — same `de1_domain::…` delegate. UniFFI
   takes native `i64`/`Option<T>` (no wasm f64 workaround needed).
2. **Verify the binding** (NO Android build needed):
   ```
   cd core && cargo build -p de1-ffi
   cargo run -q -p de1-ffi --bin uniffi-bindgen -- generate --no-format \
     --library target/debug/libde1_ffi.dylib --language kotlin --out-dir /tmp/uniffi-check
   grep -n "fun \`yourFn\`" /tmp/uniffi-check/coffee/crema/core/de1_ffi.kt
   ```
   Confirms the fn + its exact Kotlin signature. snake_case → camelCase; package is
   `coffee.crema.core`; bindings are **build-time generated** (never hand-edit them).
3. **Route the shells.** Web calls the wasm export (often already does). Android calls
   `coffee.crema.core.<camelFn>`. Shared Kotlin helpers:
   - `coffee.crema.ui.Format.kt` — UI-facing formatters (`formatRatio`, `freshnessColor`).
   - `coffee.crema.beans.BeanFormat.kt` — domain-y, Compose-free (`roastBand`,
     `daysOffRoast`, `freshnessVerdict`). Keep Compose out of non-ui packages.
4. **Commit.** Stage explicit paths only (the untracked `.scratch/phone-impl`, `Task`,
   `.scratch/android-compose-polish` are the user's — never `git add -A`). Update the
   issue file (Status→`done`, append a dated `## Comments` note) + the INDEX row, in the
   same commit. Co-Author trailer: `Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

### Gotchas (cost me time)
- **Edit tool needs a prior `Read` (the tool, not Bash) per file** — `grep`/`sed` via Bash
  does NOT register the file; the Edit fails "File has not been read yet." Read a 2-line
  slice to register, then Edit.
- **Line numbers drift** after the `6c41640` checkpoint — grep the *expression*, don't
  trust issue-file line numbers.
- **No Android NDK in this env** → can't `./gradlew`. Verify Rust via `cargo` + uniffi-bindgen;
  verify web via `cd web && npm run check` (svelte-check, ~1200 files, was 0/0). Review
  Kotlin by eye (watch overload resolution: `formatRatio` has Float+Double overloads —
  don't pass mixed Float/Double).
- Kotlin import ordering isn't enforced (ktlint is skipped in the gradle bindgen step), so
  insertion point doesn't break the build.

## Next: issue 03 — `profile_bounds_json` via UniFFI (P1, the bug)

**Bug:** Android profile editors hardcode bounds that don't match core, so you can author
firmware-invalid profiles. Core (`core/de1-domain/src/profile_bounds.rs`) is the truth:
frame time **25.5s**, brew/seg temp **100°C** (steam 170), pressure/flow **15.9375**,
volume **1023ml**, steps **32**. Web already parses these (`web/src/lib/profiles/bounds.ts`).

**Plan:**
1. Export `profile_bounds_json() -> String` via UniFFI (mirror wasm `profile_bounds_json`,
   `core/de1-wasm/src/lib.rs:971`). JSON keys (snake_case): `max_profile_steps,
   max_total_volume_ml, min_total_volume_ml, min_pressure_bar, max_pressure_bar,
   min_flow_ml_per_s, max_flow_ml_per_s, min_temperature_c, max_temperature_c,
   max_steam_temperature_c, min_frame_seconds, max_frame_seconds, max_preinfuse_steps`.
2. Android: parse once into a `ProfileBounds` (kotlinx.serialization, like other JSON-string
   FFI returns) — e.g. a `profiles/ProfileBounds.kt` with a lazily-parsed singleton.
3. Replace the wrong literals (both editors):
   - **`ui/screens/ProfileEditScreen.kt`**: L280 brew temp `…, 105.0` → `max_temperature_c`
     (100); L437 seg temp `20.0, 105.0` → 100; L433 seg time `0.0, 120.0` → `max_frame_seconds`
     (25.5); L429/L456 target/exit pressure `12.0` → `max_pressure_bar` (15.9375), flow `10.0`
     → `max_flow_ml_per_s`; L286 max volume `1023.0` is already right (keep, but source it).
   - **`ui/phone/PhoneProfileEditScreen.kt`**: L186 brew temp `80.0, 100.0` (already correct
     ceiling — source it); L378 seg temp `…105.0` → 100; L361 seg time `0.0, 127.0` → 25.5;
     L356 target `12.0` → 15.9375.
   Note flow vs pressure unit: segs switch unit by mode (`isFlow`) — bound by the matching
   max (`max_flow_ml_per_s` vs `max_pressure_bar`).
4. Verify: cargo + uniffi-bindgen emits `profileBoundsJson(): String`; eyeball editors.

## Issues 04–11 (pointers — read each issue file)

- **04** web `canonicalToDisplay` hardcoded constants → route through the wasm unit helpers
  it already imports (`web/src/lib/settings/format.ts:219`). Web-only; `npm run check`.
- **05** generate web `crema-core.ts` from `core/bindings/crema-core.ts` + CI freshness check
  (web binding is a stale hand-maintained copy missing 13 types). Bigger; mostly web/tooling.
- **06** export `bean_to_wire`/`bean_from_wire`/`roaster_to_wire`/… via UniFFI; delete the
  hand-built wire in `android/.../visualizer/WireShot.kt` + `VisualizerSync.kt`.
- **07** export `sub_state_error_message` (+ `is_recoverable`, `machine_model_name`,
  `has_cup_warmer`) via UniFFI; wire Android machine-error text (`MainViewModel.kt:2690`).
- **08** add core `default_brew_defaults_json()` (NEW core fn — no existing value source);
  both shells read it instead of hardcoded 18/2.0/93/8.
- **09** add `roast_band5` to core (NEW) + both bindings; replace web `bean/model.ts:309`
  and android `BeanFormat.kt:roastBand5`.
- **10** proactively export `reconcile_beans`/`reconcile_roasters`/`signature_for_bean`/
  `signature_for_roaster`/`coerce_bean`/`coerce_roaster` via UniFFI (latent; no Android caller
  yet — just the exports + a smoke check).
- **11** wire Android profile-fingerprint upload-skip (`profile_fingerprint` already on FFI;
  `MainViewModel.kt:1255` "v1 always uploads"). Android-only logic.

04, 08, 09, 11 are the lowest-risk next picks; 05 and 06 are the largest.

## Resume prompt
"Continue T1 from issue 03 using `.scratch/shell-core-review/HANDOFF.md`. Commit after each
issue; handoff again when context gets heavy."
