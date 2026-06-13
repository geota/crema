# 45 — Reconcile design tokens (Copper300, paper-300, sheet radius)

- **Status:** ready-for-agent
- **Severity:** P2
- **Area:** Web (`web/src/styles/tokens.css`), Android theme (`ui/theme/Color.kt`, `ui/theme/Shape.kt`)
- **Punchlist:** T3-06 + T3-07 + T3-08 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem

**T3-06:** `Copper300` token drifted — `web/src/styles/tokens.css:54` has `#E0A375` vs
`ui/theme/Color.kt:20` has `#E8A876`; the only copper stop that drifted (400/500/600/700
match). Used as accent tint.

**T3-07:** "crema foam" surface drifted — `tokens.css:41` has `#E8D9BC` vs
`ui/theme/Color.kt:120` (`surfaceContainerHighest`, comment claims parity) has `#E5D9C3`.

**T3-08:** Sheet radius drifted — `tokens.css:220` has `--radius-xl: 24px` vs
`ui/theme/Shape.kt:26` has `extraLarge 28dp`. Visible bottom-sheet corner delta.

## Fix

- **T3-06:** Pick one value for `Copper300`; align Android `Color.kt:20` to `#E0A375`
  since 500-700 already match web.
- **T3-07:** Align `surfaceContainerHighest` / "crema foam" to a single value across
  `tokens.css:41` and `Color.kt:120`.
- **T3-08:** Align sheet radius — pick 24px or 28dp as canonical and update the other.

## Acceptance / Verify
- `grep` on `#E8A876` in Android source returns 0 hits (Copper300 unified).
- `surfaceContainerHighest` / foam surface hex matches between web and Android.
- Bottom sheet corner radius visually matches between web and Android.

## Touched files
- `web/src/styles/tokens.css:54` — `Copper300` web value
- `web/src/styles/tokens.css:41` — foam surface web value
- `web/src/styles/tokens.css:220` — `--radius-xl` sheet radius
- `android/app/src/main/java/coffee/crema/ui/theme/Color.kt:20` — `Copper300` Android value
- `android/app/src/main/java/coffee/crema/ui/theme/Color.kt:120` — `surfaceContainerHighest` Android value
- `android/app/src/main/java/coffee/crema/ui/theme/Shape.kt:26` — `extraLarge` sheet radius

## Comments
<!-- triage + progress notes append below -->
