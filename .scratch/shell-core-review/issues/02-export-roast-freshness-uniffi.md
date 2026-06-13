# 02 — Export roast band / days-off-roast / freshness via UniFFI; unify freshness label+color

- **Status:** ready-for-agent
- **Severity:** P1
- **Area:** Core (UniFFI · WASM) · Android (tablet + phone) · Web
- **Punchlist:** T1-02, T3-02, T4-12 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem

This is a **correctness bug**. Core `roast_freshness` is band-aware (`core/de1-domain/src/bean.rs:99-187`: Dark best 4–10d / bad 15+, Medium best 6–14d / bad 22+, Light best 10–24d / bad 36+). Android `freshnessColor(frozen, days)` is **band-agnostic** — one threshold for all roasts (peak 4–21d). A dark-roast bean at 16d reads **stale in core/web but "peak green" on Android**. All three core fns are WASM-only (`de1-wasm:849,862,883`).

Android sites: `beans/BeanFormat.kt:23 (roastBand)`, `:55 (daysOffRoast)`; `freshnessColor` triplicated: `ui/screens/BeansScreen.kt:528`, `BrewScreen.kt:1497`, `ui/phone/PhoneBeansScreen.kt:412`; phone `RoastPicker` hardcodes a 3rd band threshold `PhoneBeanEditScreen.kt:417-421`.

T3-02 cross-shell label/color drift: web "In window/Fading/Stale" (`BeanDrawer.svelte:67-75`), tablet "Nd off roast" + hex bands (`BeansScreen.kt:423,528-535`), phone "Nd · resting/past peak" with different thresholds + telemetry-palette colors (`PhoneBeansScreen.kt:412-417`). All three shells need a unified label vocabulary and one color per band driven by core's verdict.

T4-12 — `freshnessColor()` is byte-identical in `BeansScreen.kt:528` and `BrewScreen.kt:1498` (comment admits the copy); phone uses different thresholds/colors. Collapsing these into one helper is part of this fix.

## Fix

- Export `roast_band`, `days_off_roast`, and `roast_freshness` (+ a `roast_freshness`→color helper) via UniFFI in `de1-ffi`.
- Delete `BeanFormat.roastBand` and `daysOffRoast` reimplementations.
- Replace all three `freshnessColor` copies with one helper `freshnessBucket(frozen, days, band)` in `coffee.crema.beans`, driven by core's band-aware verdict (T4-12 collapse).
- Align web freshness labels to one vocabulary: "In window / Fading / Stale" (or pick canonical) and one color per band, all shells (T3-02).

## Acceptance / Verify

A dark-roast bean (level ≥7) at 16 days shows the same status/color on web, tablet, and phone.

## Touched files

- `core/de1-ffi/src/lib.rs` — export `roast_band`, `days_off_roast`, `roast_freshness` + color helper
- `core/de1-wasm/src/lib.rs:849,862,883` — reference implementations
- `core/de1-domain/src/bean.rs:99-187` — band thresholds source of truth
- `android/app/src/main/java/coffee/crema/beans/BeanFormat.kt:23,55` — delete `roastBand`/`daysOffRoast`
- `android/app/src/main/java/coffee/crema/ui/screens/BeansScreen.kt:528` — replace `freshnessColor`
- `android/app/src/main/java/coffee/crema/ui/screens/BrewScreen.kt:1497` — replace `freshnessColor`
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneBeansScreen.kt:412` — replace `freshnessColor`
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneBeanEditScreen.kt:417-421` — replace hardcoded band threshold
- `web/src/lib/components/beans/BeanDrawer.svelte:67-75` — align label vocabulary

## Comments
<!-- triage + progress notes append below -->
