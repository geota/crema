# 29 — Reconcile low-tank threshold (phone 20f vs tablet 5f)

- **Status:** ✅ done 2026-06-15 — defer to the DE1's reported refill threshold (web parity)
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

### 2026-06-15 — DONE (simpler than the rescope: defer to the machine value)
**The rescope's premise was wrong.** It assumed the firmware refill threshold was
unreadable, so a configurable setting seeded from the de1app default was the
workaround. But the **DE1 reports its own refill threshold live** — it rides in the
*same* `Event.WaterLevel` packet as the level (`EventWaterLevelInner.refill_threshold`,
already on the generated Kotlin type). The **web shell already defers to it**
(`waterRefillThreshold` + `waterRefillSoon(level, threshold) = level ≤ threshold + 5mm`,
`web/src/lib/state/ui-state.svelte.ts`). Android was the only shell ignoring it and
hardcoding a constant. **Operator confirmed in-session: just default to the machine
value** — no setting, no machine-write, no de1app constant. This fixes the
phone/tablet split *and* gives Android↔web parity.

**What shipped:**
- `MainUiState.waterRefillThresholdMm` captures `event.content.refill_threshold` in
  the `Event.WaterLevel` handler (alongside the existing level).
- New shared `MainUiState.refillSoon()` + `const REFILL_SOON_MARGIN_MM = 5f` in
  `ui/MainViewModel.kt`, mirroring web (`level ≤ threshold + 5mm`; false when either
  reading is null → naturally suppressed before the first packet / while disconnected).
- 3 hardcoded tank checks routed through it: tablet `SettingsScreen` (was `LOW_TANK_MM=5f`),
  phone `PhoneSettingsScreen` (was `LOW_TANK_MM_PHONE=20f`), **and** phone `PhoneBrewScreen`
  tank tile (was a third hardcoded `> 5f`) — so there is now genuinely one definition.
- Both constants deleted. **No FFI change** (`refill_threshold` already delivered;
  `setRefillThreshold` also already exists on both bridges if a write is ever wanted).

**Acceptance:** `grep -rn "LOW_TANK" android/` → 0; `grep -rn "fun refillSoon" android/` → 1.
Build green (`:app:assembleDebug`). Phone + tablet render the Tank section identically
when disconnected ("Connect the DE1 to read the tank level." / "—"); the live cue is
DE1-gated (no DE1 sim, same as web).

**For the record (not used):** the parked research found de1app's
`$::settings(water_refill_point)` defaults to ≈ **5 mm** (low–med confidence; the
agent's session had WebFetch blocked, couldn't read the raw `vars.tcl`/`machine.tcl`),
min 3 mm, full tank ≈ 70–90 mm. That 5 mm matched the old tablet constant; the phone's
20 mm was the outlier. Decenza's default wasn't web-discoverable. All moot — Android
now tracks whatever the machine reports.
