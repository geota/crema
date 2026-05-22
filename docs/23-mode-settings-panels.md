# 23 — Settings panels for Steam / Hot Water / Flush modes

Companion to **docs/21 — write-on-configure MMR plan** and the
mode-chip work in commits `033df00` / `560f71e` / `a6d63ad` / `74839af`
/ `0571637`. This doc specifies the UI for surfacing + editing the
DE1's mode-specific settings.

## 0. Why this exists

The brew-page mode chips (Steam / Hot water / Flush) currently use
hardcoded target subs:

- Steam → `148 °C · 8 s`
- Hot water → `92 °C · 250 ml`
- Flush → `4 s`

The real targets live in the DE1's `cuuid_0B` (ShotSettings — steam +
hot-water + group-temp) and in MMR registers `FlushTimeout` (and the
audit-discovered `FlushTemp` at `0x803844`). The chip subs should
reflect the live, user-editable settings from those panels.

The chips and the head-pill banner are already wired; the only missing
piece is the **settings UI** to view and change the underlying values.

## 1. Data plumbing — what's already in place

| Layer | Status |
|---|---|
| Wire decode `ShotSettings` 9-byte packet | ✅ `core/de1-protocol/src/command.rs` |
| `Event::ShotSettingsRead` carrying the seven fields | ✅ as of commit `e4fc88f` (§3.5) |
| `Source::De1ShotSettings` notify route | ✅ same commit |
| BLE shell subscribe + connect-time Read of A00B | ✅ same commit |
| `UiSnapshot.de1ShotSettings` field + `applyEvent` case | ✅ same commit |
| Write API `setSteamHotwaterSettings(ShotSettings)` | ✅ pre-existing in `CremaCore` |
| MMR `FlushTimeout` register read/write | ✅ pre-existing |
| MMR `FlushTemp` register | ✅ as of commit `300a202` (§3.2) — only the enum, no read/write helpers yet |

What's NOT plumbed yet:
- `setFlushTemp` / `setFlushTimeout` typed setters on `CremaCore` (we can
  write via `writeMmr` but no convenience wrapper).
- A connect-time read of `FlushTimeout` / `FlushTemp` so the snapshot
  has flush settings on hand (only ShotSettings auto-reads on connect).
- Settings store mirror — local UI state for the panels (debounced
  edits, dirty-flag handling, error toasts).
- The actual panels.

## 2. UX

Three new sections in **Settings → Machine**, slotted after the
existing **Brew defaults** / **Water + maintenance** / **Calibration**
sections (or as one combined "Modes" section with three sub-groups;
see §2.4).

Each section follows the existing `StSectionHead` + `StGroup` + `StRow`
+ `StStepper` rhythm used by other settings panels.

### 2.1 Steam

| Row | Control | Source | Range / step |
|---|---|---|---|
| Steam target temperature | StStepper (°C) | `ShotSettings.steam_temp_c` | 100–170, step 1 |
| Steam max time | StStepper (s) | `ShotSettings.steam_timeout_s` | 5–120, step 1 |

Reaprime exposes both as numeric inputs; legacy de1app surfaces them
in the Steam tab.

### 2.2 Hot water

| Row | Control | Source | Range / step |
|---|---|---|---|
| Hot water temperature | StStepper (°C) | `ShotSettings.hot_water_temp_c` | 60–100, step 1 |
| Hot water target volume | StStepper (ml — D1-unit-aware) | `ShotSettings.hot_water_volume_ml` | 50–500, step 10 |
| Hot water max time | StStepper (s) | `ShotSettings.hot_water_timeout_s` | 10–180, step 1 |

Legacy + reaprime both treat the volume as a *time budget* fallback if
no scale is paired — the timeout fires if the volume isn't reached.

### 2.3 Flush

| Row | Control | Source | Range / step |
|---|---|---|---|
| Flush temperature | StStepper (°C) | MMR `FlushTemp` (0x803844) | 80–100, step 1 |
| Flush max time | StStepper (s) | MMR `FlushTimeout` | 1–30, step 1 |

Flush temp is the audit-discovered register from §3.2.

### 2.4 Section vs sub-group decision

**Two options:**

- **A** — three top-level sections at `Machine → Steam`, `Machine → Hot
  Water`, `Machine → Flush`. Cleanest navigation; matches the chip
  trio on the brew page.
- **B** — one section `Machine → Modes` with three sub-groups. Less
  vertical scrolling. Matches reaprime's tab layout more closely.

**Recommend A.** Each mode has 2–3 settings — small enough that a full
section per mode reads well, and the matched 1:1 mapping with the
brew-page chips is a navigation aid (the user thinks "Steam button"
and lands on a "Steam" section).

## 3. State machine

```
on Settings → Modes mount:
  derive: `liveSettings = ui.de1ShotSettings` (or null if DE1
          disconnected / no read yet)
  derive: `localEdits = {steamTempC, steamTimeoutS, …}`
          — initialised from liveSettings, mutated by StStepper input
  derive: `isDirty = localEdits !== liveSettings`

on user clicks Save:
  call app.setSteamHotwaterSettings(localEdits)
  on success: localEdits stays in sync with liveSettings (notify
              re-reads will reconcile via applyEvent)
  on failure: surface a toast, leave localEdits dirty

on incoming Event::ShotSettingsRead (notify or remote write):
  if !isDirty: localEdits <- snapshot.de1ShotSettings (silent merge)
  if  isDirty: show "Settings changed on the machine — Reload" prompt
               above the section
```

A debounce-and-auto-save pattern would be simpler but risky — every
StStepper tick on temperature would trigger a wire write. Use an
explicit Save button.

Flush settings have their own state — same shape, written via
`setFlushTimeout` + a new `setFlushTemp` setter.

## 4. Brew-page consumer

Once the panels are in place, update `BrewDashboard.svelte`'s mode-chip
subs:

```ts
const steamSubLive = $derived(
  modeState === 'steaming'
    ? `${modeElapsedSec.toFixed(1)} / ${(ui.de1ShotSettings?.steamTimeoutS ?? 8).toFixed(1)} s`
    : ui.de1ShotSettings
      ? `${ui.de1ShotSettings.steamTempC.toFixed(0)} °C · ${ui.de1ShotSettings.steamTimeoutS.toFixed(0)} s`
      : '148 °C · 8 s'  // hardcoded fallback while disconnected
);
```

Same shape for water and flush. The hardcoded values become the
disconnected-mode fallback, not the everyday display.

## 5. Implementation steps

1. **Core helpers** (~ half day)
   - Add `set_flush_temp(temp_c: f32)` to `CremaCore` (MMR write via
     `FlushTemp` with the ×10 scale factor per reaprime).
   - Add typed `set_flush_timeout(seconds: f32)` if not present
     (currently we have `setFlushTimeout(ms)` — verify units).
   - Wire connect-time MMR reads of `FlushTimeout` + `FlushTemp` so
     `snapshot.de1MachineInfo` has them at first paint.

2. **Settings store mirror** (~ 2 hours)
   - Local-edit buffer per section, dirty-flag tracking, debounced
     remote-change reconciliation.

3. **Steam panel** (~ 1–2 hours)
   - `web/src/lib/components/settings/sections/SteamSection.svelte`
     with two StStepper rows + Save / Reload.

4. **Hot Water panel** (~ 1–2 hours)
   - `HotWaterSection.svelte`. Includes D1-unit conversion on the
     volume row.

5. **Flush panel** (~ 1–2 hours)
   - `FlushSection.svelte`. Two MMR-backed rows.

6. **Brew-page consumer** (~ 30 min)
   - Flip chip subs from hardcoded to `ui.de1ShotSettings`-derived.

7. **Tests** (~ 1 hour)
   - Settings store reducer unit tests; verifies dirty-flag transitions
     + remote-reconcile.

**Total: 1–2 dev-days.** All work is purely additive — no changes to
already-shipped surfaces.
