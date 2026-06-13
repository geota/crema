# 02 — Export roast band / days-off-roast / freshness via UniFFI; unify freshness label+color

- **Status:** done (web label-vocab T3-02 deferred — see comment)
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

**2026-06-13 — done (core bug fixed).** Exported `roast_band`, `days_off_roast`,
`roast_freshness` via UniFFI (mirror the wasm exports → `de1_domain`). Backed
`BeanFormat.roastBand`/`daysOffRoast` with core (capitalised band label / Int days
kept). Added a Compose-free, **band-aware** `freshnessVerdict(frozen, level, days)`
in the `beans` pkg (core `roast_freshness`) and one shared
`coffee.crema.ui.freshnessColor(frozen, level, days)` mapping verdict→hue —
replacing the two byte-identical tablet copies (`BeansScreen`, `BrewScreen`, T4-12)
and the phone's telemetry-palette block. Threaded `bean.roastLevel` into all three
sites. **The bug is fixed:** a dark roast at 16d is now core-verdict `bad` (red)
everywhere, not "peak green".

Phone freshness label unified to the tablet's "Nd off roast" + band-aware dot
(dropped the band-agnostic resting/past-peak wording and telemetry colours).

Verified: `cargo build -p de1-ffi`; `uniffi-bindgen` emits `roastBand(Int?):String?`,
`daysOffRoast(String?,Long):Long?`, `roastFreshness(String?,Long?):String?`; no
leftover `beanFreshnessColor`/2-arg `freshnessColor` callers. Android NDK build not
run in this env.

**Deferred (T3-02 web half):** the web BeanDrawer still shows verdict *words*
("In window / Fading / Stale") rather than the Android "Nd off roast" + dot. Both
are core-backed and correct; choosing one cross-shell vocabulary is a presentation
decision, tracked as a follow-up rather than guessed here. The phone
`RoastPicker` pip-colour thresholds (`PhoneBeanEditScreen`) are roast-band colouring,
not freshness — they belong with issue 42 (T3-03).
