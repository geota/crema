# Handoff — T1 core-export issues

**Updated:** 2026-06-13 (T1 01–11 all addressed) · **Branch:** `effect/phase-0-spike`
· **Worktree:** clean (only pre-existing untracked `.scratch/android-compose-polish/`,
`.scratch/phone-impl/`, `Task`).

## TL;DR — T1 is complete

All 11 T1 issues are done or consciously deferred. Issues 01–10 landed; 11 is
deferred (brew-critical, can't be tested without an emulator). Two issues landed
their safe core half and deferred a risky Android/refactor half with rationale in
the issue file (06 Android wire-replacement, 05 bean/wire-shot type dedup). See
the per-issue tables below. No Android build was possible (no NDK) — all Kotlin
was reviewed by eye; all Rust + web changes were verified (`cargo test`,
uniffi-bindgen, `npm run check`).

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
| `43022b0` | **issue 03** — `profile_bounds_json` via UniFFI; both Android editors source firmware caps from core (hoisted the JSON builder into `de1-domain`). ✅ |
| `034d3d9` | **issue 04** — web `canonicalToDisplay` routes through the wasm unit helpers (no more open-coded constants). ✅ |
| `938b48b` | **issue 09** — `roast_band5` added to core + both bindings; web & Android delegate. ✅ |
| `7cc6ae0` | **issue 08** — `default_brew_defaults_json()` in core; web `DEFAULT_SETTINGS` (lazy getters) + Android `BrewDefaults.INSTANCE` read it. ✅ |
| `8504ee3` | **issue 10** — bean/roaster sync surface (6 fns) exported via UniFFI (latent, no Android caller yet). ✅ |
| `3e2d6fd` | **issue 07** — machine error/model text (4 fns) via UniFFI; Android model-name/cup-warmer routed through core + readable substate-error row. ✅ (`is_recoverable` exported, unwired) |
| `fe87d0e` | **issue 05** — web `crema-core.ts` regenerated from `core/bindings/` + `generate-bindings.sh` sync + CI freshness gate; `MaintenanceState/Readout` deduped. ✅ (bean/wire-shot dedup deferred — idiom divergence) |
| `1485f40` | **issue 06** — bean/roaster wire converters (6 fns) via UniFFI. ✅ (Android hand-wire replacement deferred — blocked on shot→Bean data model) |

All T1 issues are now done or deferred — see **Deferred work** below and the
per-issue `## Comments` for full rationale.

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
- **CI core gates (`.github/workflows/ci.yml` `rust` job): also run `cargo fmt --all -- --check`
  and `cargo clippy --workspace --all-targets -- -D warnings`** before calling a core change
  done — they bite. The workspace `[lints]` (root `Cargo.toml`) **denies `cast_precision_loss`**
  (so a `len() as f32` average needs `#[allow(clippy::cast_precision_loss)]` — see
  `maintenance.rs`/`volume.rs`); keep UniFFI signatures ≤100 cols or rustfmt wraps them.
- **rustfmt version skew (pre-existing):** local `rustfmt 1.9.0` reformats ~8 *untouched*
  committed files (chain collapse/expand heuristics) — the repo was formatted with an older
  stable. **Do NOT `cargo fmt --all`** (it'd rewrite untouched code repo-wide). Only hand-fix
  fmt in your own lines (≤100-col wraps are version-stable). If CI fmt fails on files you
  didn't touch, that's this skew, not your change.
- Kotlin import ordering isn't enforced (ktlint is skipped in the gradle bindgen step), so
  insertion point doesn't break the build.

## Deferred work (all needs an Android emulator + on-device verification)

Three pieces were consciously left for when Android can be built and run (no NDK
here). Each is documented in full in its issue's `## Comments`:

- **11 — fingerprint upload-skip** (`⏸️ deferred`, P3). Not a one-liner: skipping
  the `startShot()` upload means rerouting the Espresso-request trigger (today it
  fires only from `ProfileUploadCompleted`), AND invalidating the cached
  fingerprint on DE1 disconnect (the web clears it — `app.svelte.ts:758`) so a
  skip never starts a shot against a stale/absent profile. P3 optimisation vs.
  brew-safety risk, unverifiable here. FFI (`profileFingerprint`) is ready.
- **06 — Android wire-replacement** (export done; replacement deferred). The
  hand-assembly (`WireShot.kt`, `VisualizerSync.kt`) builds the bean wire from a
  flat `"Roaster · Name"` label, not a full `Bean` — so it can't call the new
  `beanToWire` (needs a full Bean) until Android threads the shot→Bean record
  through to the wire site (i.e. when bean-sync ships). Changing it now would alter
  a live Visualizer upload, untestable here.
- **05 — bean/wire-shot type dedup** (staleness+CI done; dedup deferred). The
  remaining hand-declared types (`bean/model.ts` string-union enums, `WireShot`'s
  `| null`) are deliberate web idioms, not stale copies — unifying them is an
  enum/optional migration across many consumers, a separate refactor. The
  staleness risk is already gone (generated types + CI gate).

Also still open from earlier: **T3-02** web freshness label-vocab (deferred in
issue 02). Other tiers (T2–T5, issues 12–49) are untouched by this pass.

## CI note (can't run GH Actions locally)
Issue 05 added a `typeshare bindings freshness` step to the `rust` job in
`.github/workflows/ci.yml` (install pinned `typeshare-cli 1.13.4`, re-run
`core/generate-bindings.sh`, `git diff --exit-code`). It mirrors the existing
`gen-builtin-ids` idempotency gate but was authored blind — **watch its first CI
run**: if the installed typeshare emits any formatting/version drift vs. the
committed bindings, the diff will fail and the version pin or the committed files
need a refresh.

## Resume prompt
"T1 (01–11) is complete in `.scratch/shell-core-review/HANDOFF.md`. If continuing:
pick up the three emulator-gated deferrals (11, 06 Android-replace, 05 dedup) once
Android can be built, or start a new tier (T2–T5, issues 12–49) from INDEX.md."
