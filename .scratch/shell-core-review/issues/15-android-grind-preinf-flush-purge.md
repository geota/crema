# 15 — Resolve Android grind/preinf dead-ends + flush/purge prefs

- **Status:** ✅ done (2026-06-14) — grind persists+records, preinf transient override, pre-flush/steam-purge wired; tablet-verified (no-crash, grind survives restart, preinf resets); machine effect DE1-gated
- **Severity:** P2

> **Decision (2026-06-14):** Implement, don't stub. Wire the pre-shot-flush /
> post-steam-purge consumers into the shot/steam sequence, AND make the grind +
> pre-infuse steppers actually affect the shot/record (not local-only state).
> End-to-end behaviour is DE1-gated (no simulator) — verify the shell logic +
> build/run; the on-machine effect can't be confirmed here.
- **Area:** Android tablet — `ui/screens/QuickControlsSheet.kt`, `MainViewModel.kt`, `ui/screens/BrewScreen.kt`
- **Punchlist:** T2-04 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
Grind stepper (`QuickControlsSheet.kt:115`, local `:215`) and Pre-infuse
stepper (`:117`, local `:244`) write only local state. `setPreFlush`/`setSteamPurge`
**persist** to AppPrefs but no shot/steam sequence reads them
(`MainViewModel.kt:1618,1624`); Settings pills them, but the Brew/QuickControls copies
(`BrewScreen.kt:323-324`) don't, so they read as functional there.

## Decision needed
Implement the pre-shot-flush / post-steam-purge consumers, or just add the "Not implemented yet" affordance to the Brew/QuickControls copies for parity?

## Fix
Implement the pre-shot-flush / post-steam-purge consumers, OR add the "Not
implemented yet" affordance to the Brew/QuickControls copies for parity.

## Acceptance / Verify
- Either: the pre-shot flush and post-steam purge are consumed by the shot/steam sequence.
- Or: Brew and QuickControls copies show a "Not implemented yet" affordance matching what Settings shows, so no surface falsely implies the controls are functional.

## Touched files
- `android/app/src/main/java/coffee/crema/ui/screens/QuickControlsSheet.kt:115,117,215,244` — grind/preinf steppers
- `android/app/src/main/java/coffee/crema/MainViewModel.kt:1618,1624` — flush/purge pref consumers
- `android/app/src/main/java/coffee/crema/ui/screens/BrewScreen.kt:323-324` — missing pill affordances

## Comments
<!-- triage + progress notes append below -->

### 2026-06-14 — implemented (Android; web already had parity)
Confirmed first (per the preinf question): the DE1 has **no pre-infusion register** —
pre-infusion is purely the leading shot frame(s) (`docs/02-ble-protocol.md:328`
`NumberOfPreinfuseFrames` only *tags* leading frames; `core/de1-domain/crema_profile.rs`
`blank_profile` shows `preinfusion_s` = the first segment's `time`). So a separate
preinf value is duplicative of the segment — the QC stepper can only re-cap the
leading frame. Web already wires flush/purge (`groupFlushBeforeShot` /
`autoPurgeAfterSteam`) and treats grind (records `bean.grinderSetting`) + preinf
(fingerprint-only) as cosmetic, so this is an **Android-only** issue.

- **Pre-infuse (decided: transient override):** `BrewParams.preinf` (nullable),
  threaded through `quickAdjustBrew` + the QC Brew→Pre-infuse stepper; baked into the
  upload by `overrideBrewParamsJson` capping the leading segment's `time` (saved
  profile untouched), exactly like dose/yield/brewTemp. Unit-tested
  (`OverrideBrewParamsTest`, 4 cases incl. segmentless no-op). Seeds from the active
  profile's first segment, so it reads the real value (e.g. 80's Espresso → 2 s).
- **Grind (decided: persist + record):** persisted `AppPrefs.qcGrind` /
  `MainUiState.qcGrind` (null = never dialed) + `setQcGrind`; recorded onto
  `StoredShot.grindSetting` at capture. Distinct from the bean's catalog setting.
- **Pre-flush / steam-purge (wired consumers):** `proceedToEspresso()` mirrors web —
  guard window → (if pre-flush) FLUSH + await `WaterSessionCompleted(Flush)` (30 s
  ceiling) → Espresso; both the skip + post-upload paths route through it.
  `SteamSessionCompleted` → `scheduleAutoPurge()` (1.5 s deferred FLUSH, stands down
  if a shot/flush owns the group). Removed the now-false "Not implemented yet" pills
  from the four Settings rows (tablet + phone).
- **Verify:** compile + `:app:testDebugUnitTest` green. Tablet emulator: grind→4.4
  survives a force-stop+relaunch; pre-infuse→8 s engages the Reset affordance and
  resets to the profile's 2 s on restart (correct transient semantics); no crashes.
  The machine writes (FLUSH/ESPRESSO sequencing, the capped-segment upload) are
  DE1-gated — shell logic + build/run verified only.

**Follow-up (noted, not done):** the leading-segment cap is shell-side Kotlin in
`overrideBrewParamsJson` — hoisting an `apply_brew_overrides` into `core/de1-domain`
(both bindings) would remove that drift and let web stop uploading the bare profile.
Recording the QC grind into the Visualizer wire (`metadata.grinder.setting`) is also
deferred (the record itself is captured).
