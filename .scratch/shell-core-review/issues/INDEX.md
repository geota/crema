# Issues index — Holistic review: three shells + core

Generated from `../PUNCHLIST.md` (2026-06-13). Each issue is an independently-grabbable
unit of work. Status vocabulary per `docs/agents/triage-labels.md`:
`ready-for-agent` / `ready-for-human` / `needs-info`.

`ready-for-human` = a product/design decision is needed before coding (see the issue's
**Decision needed** section).

Sequencing lives at the bottom of `../PUNCHLIST.md`. Recommended first wave:
**12, 04, 45, 17, 42, 47** (quick wins), then the core-export pass **01, 02, 03, 07, 18**.

## Theme 1 — Refactor into core (avoid drift)

| # | Title | Sev | Status |
|---|-------|-----|--------|
| 01 | Export `brew_ratio` via UniFFI + one 1-decimal ratio formatter everywhere | P1 | ✅ done |
| 02 | Export roast band/days-off-roast/freshness via UniFFI; unify freshness label+color | P1 | ✅ done (web vocab deferred) |
| 03 | Export `profile_bounds_json` via UniFFI; replace hardcoded editor limits | P1 | ✅ done |
| 04 | Web `canonicalToDisplay` — route through WASM unit helpers | P1 | ✅ done |
| 05 | Generate web `crema-core.ts` from `core/bindings/` + CI freshness check | P1 | ✅ done (bean/wire dedup: wontfix — idiom) |
| 06 | Export bean/roaster wire converters via UniFFI; delete Android hand-built wire | P2 | ✅ done (incl. Android wire migration) |
| 07 | Export `sub_state_error_message` + machine model/feature fns via UniFFI | P2 | ✅ done |
| 08 | Add core brew-defaults source; both shells read it | P2 | ✅ done |
| 09 | Add `roast_band5` to core + both bindings | P2 | ✅ done |
| 10 | Proactively export bean/roaster sync surface via UniFFI | P2 | ✅ done |
| 11 | Wire Android profile-fingerprint upload-skip | P3 | ✅ done (blind; decision unit-tested, E2E DE1-gated) |

## Theme 2 — Wiring completeness

| # | Title | Sev | Status |
|---|-------|-----|--------|
| 12 | Remove false "Not implemented yet" pills from CalibrationSection | P1 | ✅ done |
| 13 | Add `visualizerAutoUpload` UI toggle + fix stale comment | P1 | ✅ done |
| 14 | Wire Android QuickControls steam/water/flush steppers | P1 | ✅ done |
| 15 | Resolve Android grind/preinf dead-ends + flush/purge prefs | P2 | ✅ done |
| 16 | Fix Android phone wiring regressions vs tablet | P1 | ✅ done |
| 17 | Refresh stale web doc-comment headers | P3 | ready-for-agent |

## Theme 5 — Core-internal (Rust)

| # | Title | Sev | Status |
|---|-------|-----|--------|
| 18 | Close non-domain FFI parity gaps (calibration-write, reset-defaults) | P2 | ready-for-agent |
| 19 | Use the `f64_to_ms` helper everywhere in de1-wasm | P2 | ready-for-agent |
| 20 | Align scale-command receivers `&mut self` → `&self` in WASM | P2 | ready-for-agent |
| 21 | Fix `set_line_frequency_override` truncating cast | P3 | ready-for-agent |
| 22 | Replace `u64::MAX as f32` warmup clamp ceiling | P3 | ready-for-agent |
| 23 | Add `RoastBand::from_wire_str` instead of inline match | P3 | ready-for-agent |
| 24 | Document or remove stranded `brand::mark_svg()` | P3 | ready-for-agent |
| 25 | Avoid per-call alloc in `core_version` | P3 | ready-for-agent |

## Theme 4 — Intra-shell consolidation

| # | Title | Sev | Status |
|---|-------|-----|--------|
| 26 | Collapse the three Android settings-row systems | P2 | ready-for-agent |
| 27 | Extract `SettingsConfirmDialogs(...)` (copied verbatim phone↔tablet) | P2 | ready-for-agent |
| 28 | Hoist per-domain filter/sort + brew-fallback helpers (Android) | P2 | ready-for-agent |
| 29 | Reconcile low-tank threshold (phone 20f vs tablet 5f) | P3 | ready-for-agent |
| 30 | Hoist maintenance burn-down math (Android) | P2 | ready-for-agent |
| 31 | Extract `applyBeanEdits(bean, draft)` + share bag presets | P2 | ready-for-agent |
| 32 | Add `SegmentEdit` unit helpers + promote `toEdit` | P2 | ready-for-agent |
| 33 | Share scale metadata + capability body (Android) | P2 | ready-for-agent |
| 34 | Hoist the multi-channel spark chart (Android) | P2 | ready-for-agent |
| 35 | Route all Android steppers through `CremaStepper` | P2 | ready-for-agent |
| 36 | Extract `CremaStarRating(...)` | P2 | ✅ done |
| 37 | Extract `CremaEmptyState(...)` (+ Scale empty-state type-scale nit) | P3 | ready-for-agent |
| 38 | Route phone cards through `CremaCardSpec` | P2 | ready-for-agent |
| 39 | Extract `useVisualizerConnection()` rune helper (web) | P2 | ready-for-agent |
| 40 | Share `bestEffortRemoteDelete(ids)` (web) | P2 | ready-for-agent |
| 41 | Factor a shared `useStepper` numeric core (web) | P3 | ready-for-agent |

## Theme 3 — Design consistency across shells

| # | Title | Sev | Status |
|---|-------|-----|--------|
| 42 | Drop the phone roast→color map | P1 | ✅ done |
| 43 | One relative-time vocabulary per shell | P2 | 🟢 ready-for-agent (decided: compact relative) |
| 44 | Implement or hide Android °C/°F & g/oz unit toggles | P2 | ✅ done (readouts + toggles; editable steppers deferred) |
| 45 | Reconcile design tokens (Copper300, paper-300, sheet radius) | P2 | ready-for-agent |
| 46 | Standardize Android weight/temp formatting + move freshness hex to theme | P3 | ✅ done (formatting via #44; freshness hex → CremaFreshnessColors) |
| 47 | Title-case the web ProfileCard roast chip | P3 | ready-for-agent |
| 48 | Pick a canonical History stats set (tablet vs phone) | P3 | ✅ done |
| 49 | Casing nit: "Frozen" vs "frozen" | P3 | ready-for-agent |
| 50 | Phone segment-options: match QuickControls selectors + inline the `>`/`<` | P3 | ready-for-agent |

**Folded into other issues:** T3-01→01, T3-02→02, T4-12→02, T3-12 (empty-state half)→37.

**Added post-review:** 50 (2026-06-14, phone profile segment-option UI consistency).
