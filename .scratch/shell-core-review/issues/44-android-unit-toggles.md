# 44 — Implement or hide Android °C/°F & g/oz unit toggles

- **Status:** ready-for-agent (decided 2026-06-14: implement conversion)
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
