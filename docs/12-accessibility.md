# 12 — Accessibility Support (Follow-up)

**Status:** planned — deferred from the `wire-read-paths` whole-repo review
**Scope:** the `web/` SvelteKit app (the Android shell is tracked separately)

## Purpose

The whole-repo review found that Crema's web app has **no systematic
accessibility support**: the design tokens for it exist but are unused, no
control has a visible focus state, icon-only controls have no accessible name,
interactive widgets are not keyboard-operable, and destructive flows use native
browser dialogs. This was deferred so the rest of the review fixes could land
first; this doc is the spec for a dedicated accessibility pass.

The goal is **WCAG 2.1 AA** for a tablet-first touch + keyboard PWA.

## Current state — what the review found

- `--shadow-focus` is defined in `tokens.css:184` and **never referenced**;
  there is no global `:focus-visible` rule. No control shows a focus state.
- Icon-only controls expose only a `title` attribute, which is not a reliable
  accessible name — `ProfileCard.svelte` pin / duplicate / edit buttons
  (`:64,:126,:132`); the pin button also lacks `aria-pressed`.
- Telemetry charts render into a bare `<div>` with an `aria-label` but no
  `role` — `LiveChart.svelte:386`, `StaticShotChart.svelte:230`,
  `ProfilePreview`, `ProfileCurveEditor`. An `aria-label` on a roleless `div`
  is not announced.
- `ProfileCard`'s overflow menu (`:135-188`) has correct `role="menu"` markup
  but no arrow-key navigation, no Escape-to-close, and no focus move on open.
- Destructive and feedback flows use native `confirm()` / `alert()` —
  `profiles/+page.svelte:126,:136`, `ShotDetail.svelte:103/107/111`,
  `ProfileEditor` discard / back. Not stylable, not themeable, crude focus trap.
- `SegmentRow.svelte:173` is a `role="button"` `div` that nests a dozen real
  `<button>`/`<input>` controls — invalid ARIA, confusing SR tree.
- Page-level search `<input>`s have no label — `history/+page.svelte:127`,
  `profiles/+page.svelte:164` (rely on placeholder only).
- The keyboard 1–5 nav shortcuts (`CremaSidebar.svelte:62-79`) are
  undiscoverable; the `.kbd` badge is styled (`quick-controls-v2.css:521`) but
  never rendered.
- Status changes (water tank crossing the refill threshold, scale stale,
  machine errors) are not announced — no `aria-live`.
- Colour-contrast of the warm accent colours on the espresso-dark surfaces is
  unverified and borderline in places (e.g. the refill-soon `--warning` cue).

## Work items

### A. Focus visibility

- Add a global `:focus-visible` rule (in `app.css`) using `--shadow-focus` (or a
  `--copper-500` outline). Verify it on every surface — `--shadow-focus`
  references `--bg-page`, so it must resolve correctly per theme.
- Remove any `outline: none` that is not paired with a replacement focus style.
- Verify every custom-styled control (steppers, segmented controls, toggles,
  the favorites strip, nav rail, cards) shows the focus ring.

### B. Accessible names

- Sweep every icon-only `<button>`/`<a>` and add `aria-label`. Follow the
  pattern already correct in `QStepper` and `ShotDetail`'s rating stars.
- Toggle-style controls get `aria-pressed`; the pin button is the first case.
- Decorative `<i>` glyphs keep `aria-hidden="true"` (already done in places).

### C. Keyboard operability

- Replace `ProfileCard`'s hand-rolled overflow menu with the **Bits UI**
  `DropdownMenu` primitive (Bits UI is already a dependency) — it brings arrow
  navigation, Escape, focus management, and roving tabindex for free.
- Audit segmented controls / `QChipRow` / steppers for arrow-key support.
- `SegmentRow` — drop `role="button"` from the row (an interactive element must
  not nest interactive children); make selection a side effect of focusing /
  clicking the inner controls, or add one explicit "select" affordance.
- Render the 1–5 shortcut digits on the nav rail (the `.kbd` badge) so the
  shortcuts are discoverable, or document them in a help affordance.

### D. Semantic structure

- Verify landmark roles: one `<main>`, the rail as `<nav>`, headings in order
  with exactly one `<h1>` per screen.
- Add `aria-label` to the page-level search inputs (or a visually-hidden
  `<label>`).
- `aria-current` is already correct on the rail and Settings nav — keep.

### E. Charts & dynamic content

- Give the chart containers `role="img"` plus an `aria-label` that summarizes
  the current values (the numeric data already lives in state) — e.g.
  "Shot telemetry: pressure 9.1 bar, flow 2.1 ml/s at 24 s".
- Add a polite `aria-live` region for status transitions: water tank crossing
  the refill threshold, scale-stale, and machine-error substates (the
  `machineError` field landed by doc 11 R5 feeds this directly).

### F. Replace native dialogs

- Replace `confirm()` / `alert()` with an in-app accessible dialog — the Bits UI
  `AlertDialog` primitive — for the profile delete confirm, the editor
  discard/back confirm, and the "coming later" feedback stubs. Themed, focus-
  trapped, Escape-dismissable.

### G. Colour contrast

- Audit every text/icon colour against its background for WCAG AA (4.5:1 text,
  3:1 large text / UI). Focus on the warm accent colours on `--espresso-950`:
  the `--warning` refill cue, status dots, roast chips, disabled states.
- Ensure status is never conveyed by colour alone (the refill cue already has
  text — keep that pattern).

### H. Motion

- Honour `prefers-reduced-motion`: gate the card hover lift, chart draw
  animations, and any transition on it.

## Testing & acceptance

- Automated: integrate `axe-core` (e.g. via a Playwright pass) — zero critical
  violations on each of the six screens.
- Keyboard-only: every screen fully operable with Tab / Shift-Tab / arrows /
  Enter / Space / Escape; focus is always visible and never trapped.
- Screen-reader smoke test (VoiceOver) on each screen: controls announce a name
  and role; charts announce their summary; status changes are announced.
- Contrast: all text/UI pairs verified AA.
- `pnpm check` 0/0, `pnpm build` clean.

## Ordering

A (focus) and B (names) first — cheap, global, high-impact. Then F (dialogs)
and C (keyboard widgets), which depend on Bits UI primitives. Then D, E, G, H.
Pairs naturally with the design-system token consolidation, since both touch
every screen.
