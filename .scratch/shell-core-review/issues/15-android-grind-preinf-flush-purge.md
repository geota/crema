# 15 — Resolve Android grind/preinf dead-ends + flush/purge prefs

- **Status:** ready-for-agent (decided 2026-06-14: implement everything)
- **Severity:** P2

> **Decision (2026-06-14):** Implement, don't stub. Wire the pre-shot-flush /
> post-steam-purge consumers into the shot/steam sequence, AND make the grind +
> pre-infuse steppers actually affect the shot/record (not local-only state).
> End-to-end behaviour is DE1-gated (no simulator) — verify the shell logic +
> build/run; the on-machine effect can't be confirmed here.
- **Area:** Android tablet — `ui/screens/QuickControlsSheet.kt`, `MainViewModel.kt`, `ui/screens/BrewScreen.kt`
- **Punchlist:** T2-04 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
Grind stepper (`QuickControlsSheet.kt:115`, local `:215`) and Pre-infuse
stepper (`:117`, local `:244`) write only local state. `setPreFlush`/`setSteamPurge`
**persist** to AppPrefs but no shot/steam sequence reads them
(`MainViewModel.kt:1618,1624`); Settings pills them, but the Brew/QuickControls copies
(`BrewScreen.kt:323-324`) don't, so they read as functional there.

## Decision needed
Implement the pre-shot-flush / post-steam-purge consumers, or just add the "Not implemented yet" affordance to the Brew/QuickControls copies for parity?

## Fix
Implement the pre-shot-flush / post-steam-purge consumers, OR add the "Not
implemented yet" affordance to the Brew/QuickControls copies for parity.

## Acceptance / Verify
- Either: the pre-shot flush and post-steam purge are consumed by the shot/steam sequence.
- Or: Brew and QuickControls copies show a "Not implemented yet" affordance matching what Settings shows, so no surface falsely implies the controls are functional.

## Touched files
- `android/app/src/main/java/coffee/crema/ui/screens/QuickControlsSheet.kt:115,117,215,244` — grind/preinf steppers
- `android/app/src/main/java/coffee/crema/MainViewModel.kt:1618,1624` — flush/purge pref consumers
- `android/app/src/main/java/coffee/crema/ui/screens/BrewScreen.kt:323-324` — missing pill affordances

## Comments
<!-- triage + progress notes append below -->
