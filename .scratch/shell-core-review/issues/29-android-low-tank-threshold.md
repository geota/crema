# 29 — Reconcile low-tank threshold (phone 20f vs tablet 5f)

- **Status:** ready-for-agent — **rescoped 2026-06-15** (configurable, not a constant)
- **Severity:** P3
- **Area:** Android phone + tablet — `ui/phone/PhoneSettingsScreen.kt`, `ui/screens/SettingsScreen.kt`
- **Punchlist:** T4-04 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
`LOW_TANK_MM_PHONE = 20f` (`PhoneSettingsScreen.kt:487`) vs `LOW_TANK_MM = 5f` (`SettingsScreen.kt:82`) — refill warning fires at different levels.

## Fix
Determine the correct threshold (likely 5f matching the more conservative tablet value, or the value specified in hardware documentation), define it once as a shared constant (e.g. in a `CremaConstants.kt` or the domain model), and reference it from both phone and tablet settings screens.

## Acceptance / Verify
- Only one definition of the low-tank threshold constant exists in the Android codebase
- Phone and tablet show the refill warning at the same water level

## Touched files
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneSettingsScreen.kt:487` — remove `LOW_TANK_MM_PHONE`, use shared constant
- `android/app/src/main/java/coffee/crema/ui/screens/SettingsScreen.kt:82` — move `LOW_TANK_MM` to shared location

## Comments
<!-- triage + progress notes append below -->

### 2026-06-15 — RESCOPED by operator (do NOT just reconcile the constant)
**Operator decision:** "This should be configurable and default to whatever the
de1app / Decent (Decenza) apps default it to." So the fix is **not** picking 5
vs 20 mm — it's:
1. Make the low-tank warning threshold a **user setting** (Settings → Water, mm).
2. **Default** it to the DE1 firmware / de1app default refill threshold. The DE1
   already exposes its own `refill_threshold` (core: `set_refill_threshold` /
   `Event`/`waterRefillThreshold`, mirrored in web `crema-core.ts`). When a DE1 is
   connected, the warning should track that live value (same as de1app does);
   the setting's seed = the firmware default.
3. Drop both hardcoded constants (`LOW_TANK_MM_PHONE = 20f`, `LOW_TANK_MM = 5f`).

**Open research before coding:** confirm the de1app/Decent **default** refill
threshold value (firmware default — search Decent docs / de1app source), and decide
whether "configurable" writes to the **machine** (`setRefillThreshold`, persists on
the DE1, matches de1app) or stays a **shell-only** override. Leaning machine-write
for true de1app parity. Bigger than a P3 nit now — needs a UniFFI surface check +
a Settings control + the default. Emulator can verify the setting UI + default;
the live-threshold tracking is DE1-gated.
