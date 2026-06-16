# 45 — Reconcile design tokens (Copper300, paper-300, sheet radius)

- **Status:** ✅ done
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

### 2026-06-15 (session 5) — done

All three sub-items were cases of **Android drifting from the web `tokens.css`
source**, so all three align **Android → web** (single coherent direction; web
untouched). Line numbers had shifted since filing — actual sites below.

- **T3-06 Copper300:** `Color.kt:20` `0xFFE8A876` → `0xFFE0A375` (web `--copper-300`).
  Grep for `E8A876` in Android source → 0. `inversePrimary = Copper300` picks it
  up automatically.
- **T3-07 foam:** `Color.kt:133` light `surfaceContainerHighest` `0xFFE5D9C3` →
  `0xFFE8D9BC` (web `--paper-300`). The line's own comment already said
  `// paper-300 (crema foam)` — the hex was just stale. The **dark** theme's
  `surfaceContainerHighest = 0xFF322820` (Color.kt:90) is a different surface and
  is left alone.
- **T3-08 radius:** chose web's **24** as canonical. `Shape.kt:26` `extraLarge`
  28dp → 24dp (+ doc-comment line 14). The Shape.kt header cited
  `tablet/m3-tokens.css` as its source, but that file no longer exists, so the
  live web token wins. **Note:** 28dp was the M3-standard extraLarge; if the
  softer sheet is preferred, flip web `--radius-xl` to 28px instead — one line
  either way. `extraLarge` is shared (sheets + large hero cards), so all
  extraLarge Android surfaces step to 24dp.

**Found but out of scope (deferred):** `surfaceContainerHigh` (paper-200) also
drifted — `Color.kt:132` `0xFFECE2D1` vs web `--paper-200` `#ECDFC8`. Not in #45's
three sub-items; worth a follow-up nit.

Change is literal-value-only (valid hex / dp constants, no structural or API
change) → compile-safe; no gradle build run.
