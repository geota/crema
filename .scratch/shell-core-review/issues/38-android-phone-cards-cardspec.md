# 38 — Route phone cards through `CremaCardSpec`

- **Status:** ✅ done (2026-06-15)
- **Severity:** P2
- **Area:** Android phone — `ui/phone/PhoneProfilesScreen.kt`, `ui/phone/PhoneBeansScreen.kt`, `ui/components/CremaComponents.kt`
- **Punchlist:** T4-14 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
Phone uses raw `Surface` with its own literals — `PhoneProfileCard` radius 18dp (`PhoneProfilesScreen.kt:200`), phone beans card radius 18dp (`PhoneBeansScreen.kt:343`) — vs tablet `CremaCardSpec` 16dp (`CremaComponents.kt:456`). Silent corner-radius drift.

## Fix
Consume `CremaCardSpec` for phone cards (with a single phone override if 18dp is intentional — e.g. a `phoneShape` variant on `CremaCardSpec`). Remove raw `Surface` + literal radius usage from phone cards.

## Acceptance / Verify
- `grep -rn "radius.*18.dp\|18.dp.*radius" android/.../phone` returns 0 raw literals (or only the single `CremaCardSpec` definition)
- Phone profile cards and bean cards visually use `CremaCardSpec` corner shape
- If 18dp is intentionally different from 16dp tablet, a named variant exists in `CremaCardSpec` (not a raw literal)

## Touched files
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneProfilesScreen.kt:200` — replace raw `Surface` + 18dp with `CremaCardSpec`
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneBeansScreen.kt:343` — replace raw `Surface` + 18dp with `CremaCardSpec`
- `android/app/src/main/java/coffee/crema/ui/components/CremaComponents.kt:456` — optionally add a phone-shape variant if 18dp is intentional

## Comments
<!-- triage + progress notes append below -->

### 2026-06-15 — done
Added `CremaCardSpec.phoneRadius = 18.dp` (named, documented as the handset
large-surface corner — intentionally softer than the 16dp tablet tile) and
routed every raw `RoundedCornerShape(18.dp)` on phone through it. The review
named the profile card + beans tile, but the acceptance grep surfaced 3 more of
the same 18dp handset corner, so all are routed (no silent drift left):
- `PhoneProfileCard` (PhoneProfilesScreen)
- phone beans tile (PhoneBeansScreen)
- machine-hero card ×2 (PhoneSettingsScreen — the section push + the hero panel)
- profile/bean picker dropdown sheet (PhoneBrewScreen)

`grep -rn "RoundedCornerShape(18.dp)" .../phone` → 0; the 18dp now lives only in
`CremaCardSpec.phoneRadius`. Both phone files already wildcard-import
`coffee.crema.ui.components.*`, so no import churn. Value-identical (18→18 via
token); `:app:compileDebugKotlin` green. Phone cards verified on the emulator.
