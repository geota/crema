# 05 — Sync custom profiles to mirrors (id-only sync shows the wrong profile)

- **Status:** ready-for-agent
- **Severity:** P2
- **Area:** Android (proxy config · MainViewModel · settings)
- **Depends on:** none (builds on T2 `ConfigSnapshot`)

## Problem

The T2 config snapshot sends the active profile **id**, and `applyRemoteConfig` applies
it only if the secondary already has it:

```kotlin
activeProfileId = cfg.activeProfileId?.takeIf { id -> profileJsonById.containsKey(id) } ?: it.activeProfileId,
```

(That guard is correct — it stops a mirror blanking its Brew screen on an unknown id.)
But it means: if the primary runs a **custom** profile the secondary doesn't have, the
secondary silently keeps its *own* profile. The two devices then show **different
profiles** while claiming to mirror — the curve, phases, dose/temp targets, even the
header name all diverge. Same hazard for `activeBeanId` (mirror shows the id with no
bean detail).

## Design

The secondary only needs the profile **for display** — the shot is driven by the
primary, which already has it. So send the **definition** as a *transient overlay*, not
a library import (don't pollute the mirror's saved library).

- Extend `ConfigSnapshot` with `activeProfileJson: String?` — the active profile's wire
  JSON (the `CremaProfile`/`profileJsonById[id]` value), populated by the primary when
  it builds the snapshot. (Optionally gzip/omit if the id is a known built-in to keep
  the frame lean.)
- `applyRemoteConfig` on a secondary: set a new `_ui.mirroredProfileJson` (transient,
  not persisted). The Brew screen prefers `mirroredProfileJson` over the library lookup
  when it's set (i.e. when mirroring). On `switchToNormal`/stop, clear it → the device's
  own library selection returns.
- Beans (smaller): add `activeBeanSummary` (name/roaster/roast/days-off) to the snapshot
  so the bean chip renders even when the bean isn't local. Full bean library sync is out
  of scope.

### Handoff interaction

On take-over (#01) the taker becomes primary and **needs the profile in its real
library** to actually drive shots with it. Two options: (a) on grant, persist the
mirrored profile into the taker's library (so it can run it); or (b) gate "start shot"
on the taker having the profile and otherwise prompt to import. Recommend (a) — promote
the transient overlay to a saved (de-duped) profile at the moment of handoff only.

## Fix (code, sketch)

- `settings/AppPrefs.kt` `ConfigSnapshot`: `+ activeProfileJson: String? = null`, `+ activeBeanSummary: String? = null`.
- `MainViewModel.configSnapshotJson`: fill `activeProfileJson` from `profileJsonById[activeProfileId]`.
- `MainViewModel`: add `_ui.mirroredProfileJson`; `applyRemoteConfig` sets/clears it.
- `PhoneBrewScreen` (+ wherever the active profile is read for Brew): prefer
  `mirroredProfileJson` when mirroring.
- Handoff path: promote the overlay to the library on `switchToPrimary` (option a).

## Acceptance / Verify

2-emulator: primary on a **custom** profile the secondary lacks → the secondary's Brew
header, curve, and targets **match the primary**; stopping the mirror restores the
secondary's own selection (no leaked library entry). After a take-over, the new primary
can start a shot on that profile.

## Touched files

- `android/app/src/main/java/coffee/crema/settings/AppPrefs.kt` — `ConfigSnapshot` fields
- `android/app/src/main/java/coffee/crema/ui/MainViewModel.kt` — snapshot fill + overlay apply/clear + handoff promote
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneBrewScreen.kt` — prefer overlay when mirroring

## Comments
<!-- triage + progress notes append below -->
