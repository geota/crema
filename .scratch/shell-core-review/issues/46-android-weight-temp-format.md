# 46 — Standardize Android weight/temp formatting + move freshness hex to theme

- **Status:** ready-for-agent
- **Severity:** P3
- **Area:** Android phone (`ui/phone/PhoneProfilesScreen.kt`), Android tablet (`ui/screens/`), Android theme
- **Punchlist:** T3-09 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
Weight "18.0 g" (tablet) vs "18g"/"%.0fg" (phone cards,
`PhoneProfilesScreen.kt:280`); temp `%.0f` on cards vs `%.1f` in brew readout; degree glyph
"°C" vs bare "°". Move Android freshness/telemetry hex into the theme (a
`CremaFreshnessColors` local like `CremaTelemetryColors`).

## Fix
- Standardize weight format to "18.0 g" across tablet and phone.
- Standardize temp format (settle on `%.1f` vs `%.0f`) and ensure the degree glyph is always "°C" (not bare "°").
- Extract freshness and telemetry hex color literals into a `CremaFreshnessColors` theme object, modelled on the existing `CremaTelemetryColors` pattern.

## Acceptance / Verify
- All weight displays across phone and tablet use a consistent format string.
- All temp displays use a consistent format string and "°C" glyph.
- No bare freshness hex literals remain outside the theme object.

## Touched files
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneProfilesScreen.kt:280` — "%.0fg" phone card weight format
- `android/app/src/main/java/coffee/crema/ui/screens/` — tablet weight/temp format sites
- Android theme files — add `CremaFreshnessColors`

## Comments
<!-- triage + progress notes append below -->
