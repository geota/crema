# 26 — Collapse the three Android settings-row systems

- **Status:** ✅ done 2026-06-15 (settings-screen rows unified; `SettingsRow` deferred to 33 — see note)
- **Severity:** P2
- **Area:** Android phone + tablet — `ui/phone/components/CremaPhoneComponents.kt`, `ui/phone/PhoneSettingsScreen.kt`, `ui/screens/SettingsScreen.kt`
- **Punchlist:** T4-01 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
Shared `SettingsRow` (`ui/phone/components/CremaPhoneComponents.kt:408`) is **dead** (referenced only in a comment, `:53`); phone defines private `PRow/PPill/PSelect/PStepper/PMono/PStatusDot` (`PhoneSettingsScreen.kt:924-1019`) near-duplicating tablet's private `SetRow/SetPill/SetSelect/SetStepper/MonoReadout/StatusDot` (`SettingsScreen.kt:945-1041`). Differences are only padding + a FlowRow pill wrap.

## Fix
One parameterized row set (pill/connection semantics shared); delete the dead `SettingsRow` and both private copies.

## Acceptance / Verify
- `grep -rn "PRow\|PPill\|PSelect\|PStepper\|PMono\|PStatusDot" android/` returns 0 results
- `grep -rn "SetRow\|SetPill\|SetSelect\|SetStepper\|MonoReadout\|StatusDot" android/` returns 0 results (or only the new shared definition)
- Dead `SettingsRow` at `CremaPhoneComponents.kt:408` is removed
- Phone and tablet settings screens render identically in layout (padding delta acceptable via param)

## Touched files
- `android/app/src/main/java/coffee/crema/ui/phone/components/CremaPhoneComponents.kt:53,408` — remove dead `SettingsRow`
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneSettingsScreen.kt:924-1019` — delete private `PRow/PPill/PSelect/PStepper/PMono/PStatusDot`
- `android/app/src/main/java/coffee/crema/ui/screens/SettingsScreen.kt:945-1041` — delete private `SetRow/SetPill/SetSelect/SetStepper/MonoReadout/StatusDot`
- new shared file (e.g. `ui/components/SettingsRowComponents.kt`) — parameterized row set

## Comments
<!-- triage + progress notes append below -->

### 2026-06-15 — scope shrunk: steppers + status dot already extracted
Two of the six row widgets are already unified, so this issue no longer owns them:
- **`SetStepper`/`PStepper`** → done in **issue 35** (now `CremaStepper`/`CremaStepperStyle.Bare`/`BareCompact`).
- **`StatusDot`/`PStatusDot`** → extracted to shared **`CremaStatusDot`** (`CremaComponents.kt`),
  byte-identical to both originals; all 8 call sites routed. (Prompted during 35.)

**Remaining for 26:** the row chrome + the other shared widgets —
`PRow/PPill/PSelect/PMono` ↔ `SetRow/SetPill/SetSelect/MonoReadout`, plus the dead
`SettingsRow` at `CremaPhoneComponents.kt:408`. Update the acceptance greps: drop
`PStepper`/`SetStepper`/`PStatusDot`/`StatusDot` (already 0), keep the rest.

### 2026-06-15 — DONE (settings-screen rows unified; `SettingsRow` is NOT dead → deferred to 33)
**Stale premise corrected:** the shared `SettingsRow` (`CremaPhoneComponents.kt`) is
**not dead** — `PhoneScaleScreen` uses it 6× for the capability rows, and (unlike
`PRow`/`SetRow`) it renders **no dividers**. Folding it into the unified row would
visibly change the (scale-gated, unverifiable) scale screen, so it's **left for issue
33**, which already owns the scale capability body. The acceptance bullet "remove dead
`SettingsRow`" is dropped.

**What shipped — one shared row set** (`ui/components/SettingsRowComponents.kt`):
- `CremaSettingsRow` / `CremaSettingsPill` / `CremaSettingsSelect` / `CremaMonoReadout`,
  with a `LocalSettingsRowDense` composition-local carrying the only phone↔tablet
  difference (paddings 16/13 vs 20/16, gap 12 vs 16, select 38 vs 40). The phone
  settings root provides `true`; the tablet uses the default `false`. So the ~150 call
  sites are a **pure rename**, not a per-site flag.
- Deleted phone `PRow/PPill/PSelect/PMono` (PhoneSettingsScreen) + tablet
  `SetRow/SetPill/SetSelect/MonoReadout` (SettingsScreen); removed the now-unused
  imports they left behind.
- **Pill text unified to "Soon"** (operator's call) — the tablet's "Not implemented yet"
  → "Soon"; the phone already said "Soon". A deliberate, visible tablet change.
- FlowRow is used in both modes (title+pill); on the roomy tablet the pill stays inline
  (matching the old `SetRow` Row), on the phone it wraps whole (the reason `PRow` used it).

**Acceptance:** `grep PRow|PPill|PSelect|PMono` (phone) → 0; `grep SetRow|SetPill|SetSelect|MonoReadout`
(tablet) → 0. Build green. **Validated live both emulators:** tablet Machine shows the
"Telemetry rate · SOON" pill (changed), the "50 Hz" select, the copper "CONNECT DE1"
pill, and "Model —" mono — all roomy; phone Machine shows the same dense, with the GHC
"CONNECT DE1" pill wrapping below the long title.

**For 33:** route the `PhoneScaleScreen` `SettingsRow` usages onto the canonical row
(add a `last`/no-divider story) and retire `SettingsRow` then.
