# 46 — Standardize Android weight/temp formatting + move freshness hex to theme

- **Status:** ✅ done (2026-06-15) — formatting unified via #44; freshness hex → theme
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

### 2026-06-15 — done
- **Weight/temp formatting** unified by the issue **44** sweep: every brew/scale/
  history/profile readout now routes through `Format.kt` (`format*`/`convert*`),
  so there's a single source for precision (weight `%.1f` g / `%.2f` oz, temp
  `%.1f`) and the degree glyph is always explicit (`°C`/`°F`, never bare `°`).
  *Deliberate exception:* the dense phone readouts (the 4-up machine-readiness
  strip, profile-card metrics, history-row/detail metrics) render the converted
  value+unit **compact, no space** (`18.0g`, `93.0°C`) to fit; the glyph is still
  explicit. Spacing those to match the tablet's `18.0 g` would risk clipping the
  4-up tiles — left as a layout follow-up if byte-identical spacing is wanted.
- **Freshness hex → theme:** the five verdict hues moved from the inlined literals
  in `Format.freshnessColor` into `FreshnessPalette` (`theme/Color.kt`) +
  `CremaFreshnessColors` (`theme/CremaTheme.kt`, a local mirroring
  `CremaTelemetryColors`), accessed via `CremaTheme.freshness`. `freshnessColor`
  is now `@Composable` and reads the theme; values are byte-identical, so the dots
  are visually unchanged. Telemetry hex was already themed (`CremaTelemetryColors`).
  The scattered `0xFFDBA764` amber elsewhere is a separate *warning/attention* hue
  (low-water, maintenance-due, heating), not a freshness color — out of scope.
