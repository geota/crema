# 42 — Drop the phone roast→color map

- **Status:** ready-for-agent
- **Severity:** P1
- **Area:** Android phone — `ui/phone/PhoneProfilesScreen.kt`
- **Punchlist:** T3-03 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
Phone `RoastPill` hardcodes per-band browns (`PhoneProfilesScreen.kt:338-340`:
light `#E8C088` / medium `#D89A63` / dark `#BA8A66`); web + tablet intentionally use
copper-wash only (`ProfilesScreen.kt:386-400`). Same Light bean = copper pill (web/tablet)
vs tan pill (phone).

## Fix
Use the copper-wash convention on phone; also reconcile pill radius (phone 7dp
vs tablet 999dp).

## Acceptance / Verify
- `RoastPill` in `PhoneProfilesScreen.kt` no longer contains per-band hex color literals.
- A Light bean shows the same copper-wash color on phone as on tablet and web.
- Pill corner radius matches tablet (999dp / fully rounded).

## Touched files
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneProfilesScreen.kt:338-340` — remove per-band color map, adopt copper-wash; fix pill radius 7dp → 999dp
- `android/app/src/main/java/coffee/crema/ui/screens/ProfilesScreen.kt:386-400` — reference for correct copper-wash implementation

## Comments
<!-- triage + progress notes append below -->
