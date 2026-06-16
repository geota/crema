# 50 — Phone profile segment-options: match QuickControls selectors + inline the `>`/`<`

- **Status:** ✅ done 2026-06-16 (part 1 inline `>`/`<` + part 2 design call: keep `CremaSegmentedButton`, add a compact uniform-width variant)
- **Severity:** P3
- **Area:** Android phone — `ui/phone/PhoneProfileEditScreen.kt`
- **Punchlist:** (added 2026-06-14 from user review; not in original PUNCHLIST)
- **Depends on:** none

## Problem

In the **phone** profile editor's segment (phase) options, the inputs don't match
the rest of the app's selector idiom, and the over/under comparison sits *outside*
its value field:

- **`>` / `<` is a separate control.** The Exit-early block renders the over/under
  comparison as its own `CremaSegmentedButton(SegOption("over", ">"),
  SegOption("under", "<"))` on a row above the threshold stepper
  (`PhoneProfileEditScreen.kt:419-423`), with the threshold `EdStepper` on a
  separate row below (`:425-428`). On the **tablet** and **PWA** the `>` / `<`
  renders *inside* the value input as a clickable prefix:
  - tablet: `EditStepper(..., compareSymbol = …, onCompare = …)`
    (`ui/screens/ProfileEditScreen.kt:461-462`; the in-stepper rendering is
    `:802-819`).
  - PWA: `web/src/lib/components/profiles/SegmentRow.svelte:343` ("the over/under
    comparison renders as a `>` / `<`" in the value field).
- **Selector styling drift.** The phone segment selectors use
  `CremaSegmentedButton` + `SegOption` (metric Pressure/Flow `:344-346`, ramp
  `:351-353`, exit metric/compare `:419-423`), whereas the app's QuickControls use
  the `CremaSplitLabel` + `SplitOption` idiom (`ui/screens/QuickControlsSheet.kt:218,
  247, 260, 273, 286` — a label whose segmented options drive a single stepper).
  The phone segment inputs should read like those Quick-Control selectors.

## Fix

- Move the Exit-early `>` / `<` toggle **inside** the threshold input on the phone,
  mirroring the tablet's `EditStepper` `compareSymbol` / `onCompare` (add the same
  affordance to the phone `EdStepper`, or factor a shared stepper). Drop the
  separate over/under `CremaSegmentedButton`.
- Make the phone segment-option inputs more closely match the QuickControls
  selectors (the `CremaSplitLabel` / `SplitOption` look) so the editor feels
  consistent with the rest of the phone UI.

## Acceptance / Verify

- On the phone, the Exit-early comparison shows as a tappable `>` / `<` *inside*
  the threshold value field (no separate segmented control), matching tablet + PWA.
- The phone segment selectors visually match the QuickControls selector idiom.
- Tablet + PWA segment editors unchanged. Verify on the phone emulator
  (`Pixel_10_Pro`) via a seeded custom profile's segment editor.

## Touched files

- `android/app/src/main/java/coffee/crema/ui/phone/PhoneProfileEditScreen.kt:344-355,419-428` — segment metric/ramp + exit metric/compare selectors and threshold stepper
- `android/app/src/main/java/coffee/crema/ui/screens/ProfileEditScreen.kt:461-462,802-819` — reference: tablet `compareSymbol`/`onCompare` in-stepper pattern
- `android/app/src/main/java/coffee/crema/ui/screens/QuickControlsSheet.kt:218-286` — reference: `CremaSplitLabel`/`SplitOption` selector idiom to match
- `web/src/lib/components/profiles/SegmentRow.svelte:343` — reference: PWA in-field `>`/`<`

## Comments
<!-- triage + progress notes append below -->

### 2026-06-15 — Part 1 done (inline `>`/`<`); Part 2 needs a design call
**Part 1 (the primary acceptance) — done + verified on the phone.** Now that issue 35
unified `CremaStepper` with `compareSymbol`/`onCompare`, the phone's Exit-early
threshold stepper (`BareCompact`) just takes those params: the `>`/`<` renders as a
tappable copper prefix INSIDE the value field, and the separate over/under
`CremaSegmentedButton` is gone. The metric selector + threshold-with-compare now share
one row, matching the tablet (`BoxedDense`) + PWA. Live-confirmed: `Pressure | > 4.5`.

**Part 2 (selectors match QuickControls) — deferred, needs the user's call.** The phone
segment selectors (Type Pressure/Flow, Transition Smooth/Fast, Exit metric) use
`CremaSegmentedButton` — the app's *discrete-selector* idiom. QuickControls' `CremaSplitLabel`
is specifically a *mode-swap* label (its options swap which value one stepper edits).
These are semantically different: a segment's Type genuinely IS pressure-or-flow (a
discrete choice), not a stepper mode. `CremaSplitLabel` is generic enough to render them
(prefix + options), so a conversion is *possible* and would visually match QC — but it's
a notable look change to the dense editor and a subjective preference, so leaving it for
a product decision rather than unilaterally reshaping the editor.

### 2026-06-16 — Part 2 DONE (design call: keep segmented, make it compact + uniform)
Prototyped **both** on the phone emulator and showed the operator side-by-side
(variant A = `CremaSegmentedButton` pills; variant B = `CremaSplitLabel`). **Operator
chose to keep the segmented idiom** (a segment's Type genuinely *is* a discrete
pressure-or-flow choice — the right affordance) **but shrink the pills** for density,
then asked for **uniform width** so they line up.

Shipped:
- New `compact` flag on `CremaSegmentedButton` (`CremaComponents.kt`): height 40→32 dp,
  tighter content padding, `labelMedium` text, **no check icon** (selection reads from
  the fill), and **equal-weight segments** so a fixed-width pill splits 50/50.
- The four phase-editor selectors (Type, Transition, Temperature, Exit metric) pass
  `compact = true` + a shared `SegmentPillWidth = 176.dp`, so all four are identical
  size and the right-aligned Type/Transition pair lines up (no more ragged left edges).
- `compact` defaults `false` → every other segmented button (roast/beverage, scale
  auto-stop, …) is untouched. Verified live on the phone emulator. Throwaway prototype
  screenshots discarded.
