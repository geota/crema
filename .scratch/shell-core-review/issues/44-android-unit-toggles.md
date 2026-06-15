# 44 — Implement or hide Android °C/°F & g/oz unit toggles

- **Status:** ✅ done (2026-06-15) — readouts + toggles wired; editable steppers deferred
- **Severity:** P2

> **Decision (2026-06-14):** Implement, don't hide. Export the unit-conversion
> fns via UniFFI (the Theme-1 pattern), add unit prefs to an Android settings
> store, and route every brew/scale/history readout through the core conversions
> so the °C/°F and g/oz toggles work end-to-end. Standardize formatting to
> "18.0 g" / "93.0 °C" across tablet + phone. Fully emulator-verifiable (pure
> display; flip toggle → seeded readouts change), no DE1 needed. Mirrors web.
- **Area:** Android tablet (`SettingsScreen.kt`), Android phone (`PhoneSettingsScreen.kt`)
- **Punchlist:** T3-05 — `../PUNCHLIST.md`
- **Depends on:** 01-style core export — specifically a UniFFI unit-conversion export (mirrors T1-08's pattern of exporting domain helpers via UniFFI so Android can route display through core unit conversions)

## Problem
Android is Celsius/grams hardcoded; the Settings unit toggles are
`notImplemented` on both tablet (`SettingsScreen.kt:547-552`) and phone
(`PhoneSettingsScreen.kt:615-620`) — flipping does nothing. Weight also spaces
differently ("18.0 g" tablet vs "18g" phone), temp decimals/glyph vary.

## Decision needed
Implement Android unit conversion (requires exporting the 8 unit-conversion fns via UniFFI — see issue 07's pattern / Theme 1), or hide the toggles until then?

## Fix
Back a SettingsStore with units, route display through the core unit conversions
(export via UniFFI); or hide the toggle until then. Standardize "18.0 g" / "93.0 °C".

## Acceptance / Verify
- Unit toggles either work end-to-end (flip to °F/oz → all brew readouts update) OR are removed/hidden with no silent no-op affordance.
- Weight formatted consistently as "18.0 g" across tablet and phone.
- Temp formatted consistently as "93.0 °C" (with degree glyph) across tablet and phone.

## Touched files
- `android/app/src/main/java/coffee/crema/ui/screens/SettingsScreen.kt:547-552` — tablet `notImplemented` unit toggles
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneSettingsScreen.kt:615-620` — phone `notImplemented` unit toggles

## Comments
<!-- triage + progress notes append below -->

### 2026-06-15 — implemented (readouts + toggles)
Completes the WIP scaffolding (`126f19c`: UniFFI conversions + `Format.kt` +
`AppPrefs` unit fields). This commit:

- **MainViewModel:** `weightUnit`/`tempUnit`/`pressureUnit`/`volumeUnit` added to
  `MainUiState` + `currentPrefs`/`loadPrefs`/`resetPreferences` round-trip + four
  `set*Unit` setters (pure display prefs — no machine write).
- **Readout sweep** — every brew/scale/history/profile **display** readout routed
  through `Format.kt`'s `convert*`/`format*`, so flipping a unit re-renders them:
  tablet BrewScreen (channel cards, foot steam, last-shot, profile summary,
  **LimitsCard** yield/volume), HistoryScreen (stats, rows, detail), ScaleScreen
  (hero, tare, dose helper), ProfilesScreen (card metrics); phone PhoneBrewScreen
  (dose→yield, ready params, **DualChip channel legend**, machine-readiness strip,
  last-shot), PhoneHistoryScreen (row + detail), PhoneProfilesScreen (card),
  PhoneSettingsScreen (machine-info temp).
- **Settings toggles** wired on both shells (tablet `SettingsScreen` Units group;
  phone `DisplaySection` Units group) — real segmented buttons bound to the prefs
  + setters; `notImplemented`/`SOON` pills dropped. Option keys are the canonical
  vocab (`g`/`oz`, `C`/`F`, `bar`/`psi`, `ml`/`floz`).

**Verified** on emulator (tablet 5556 + phone 5554, no DE1 needed — pure display):
flip °C→°F / g→oz / bar→psi / ml→fl oz → all readouts update. e.g. profile
"36.0 g · 79.0 °C" → "1.27 oz · 174.2 °F"; History detail "132 psi / 199.4 °F";
phone "0.63 oz → 1.27 oz · 194.0 °F · 109 psi". `:app:testDebugUnitTest` green.

**Deferred (follow-ups, NOT in this issue's acceptance):**
- **Editable steppers** (Quick Controls steam/water/flush temp+vol, dose/yield,
  profile-editor segment temp/pressure/volume, bean bag/remaining, Settings brew
  defaults). The web makes these unit-aware via a `dimension`-prop stepper with
  display-grid stepping + inverse conversion; porting that across the ~6 bespoke
  Android stepper helpers is a larger change. They still commit canonical, so this
  is cosmetic, not a correctness gap. **Worth a dedicated issue.**
- **Hardcoded mode-chip labels** ("148 °C · 90 s" etc. on the Steam/Water/Flush
  chips) don't reflect live QC settings yet, so they don't track the toggle —
  pre-existing (issue 14/50 territory); fix when they're bound to real state.
- The Settings **calibration** offset steppers (temp/pressure re-zero) are still
  `notImplemented`, so left alone.
