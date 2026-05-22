# 21 — Write-on-configure MMR registers: implementation plan

Companion to `docs/20-read-paths-audit.md` §9. The audit's per-register
table identified ~9 MMRs the legacy app only ever writes to, sourced
from the user's tablet settings (`::settings(*)`). This doc lays out
the plan to wire each in Crema — what changes, in what order, gated
by which UI surface.

## 1. Pattern: write-on-configure, write-on-connect

The legacy pattern (`bluetooth.tcl:2054-2114`,
`later_new_de1_connection_setup`) is:

1. **At connect**: the app reads `::settings(name)` and fires the
   corresponding `set_<name>` proc, which packs the value into an
   `mmr_write` packet and dispatches it on `cuuid_06 / WriteToMMR`.
2. **On settings change** (the user adjusts a slider): the app calls
   the same `set_<name>` proc with the new value, immediately
   re-writing.

There is no **read** in the loop — the user-supplied value is canonical;
the DE1 mirrors it. This matches our `createCremaApp` line-frequency
pattern (push once at startup, push again on user change) and our
existing `de1.ts` connect-time MMR sweep (read identity registers, not
configuration values).

### Crema parallel

- **Setting** lives in `lib/settings/store.svelte.ts`.
- **Connect-time write** lives in the same `onState('ready')` hook
  that already handles `autoUploadActiveProfileOnReady` and
  `setSuppressDe1Sleep` (`app.svelte.ts:159-176`).
- **On-change write** is wired directly from the UI control's
  `onChange` handler, mirroring the line-frequency selector
  (`AdvancedSection.svelte:onLineFrequencyChange`).
- **Core method**: most registers already have a typed setter on
  `CremaCore`; the bridge is wired; the web facade exposes
  `app.setX(value)`. The remaining work is the **settings field +
  UI control + connect-time push**, not the BLE layer.

## 2. Inventory — what's where

| # | Register | Addr | Core method | Bridge method | Web facade method |
|---|---|---|---|---|---|
| 1 | `HotWaterFlowRate` | `0x80384C` | `set_hot_water_flow_rate(f32)` ✅ | bridged ✅ | not on app yet ⏳ |
| 2 | `HotWaterIdleTemp` | `0x803818` | not yet ⏳ | — | — |
| 3 | `Phase1FlowRate` | `0x803810` | not yet ⏳ | — | — |
| 4 | `Phase2FlowRate` | `0x803814` | not yet ⏳ | — | — |
| 5 | `FlushFlowRate` | `0x803840` | `set_flush_flow_rate(f32)` ✅ | bridged ✅ | not on app yet ⏳ |
| 6 | `FlushTimeout` | `0x803848` | `set_flush_timeout(Duration)` ✅ | bridged ✅ | not on app yet ⏳ |
| 7 | `SteamFlow` | `0x803828` | `set_steam_flow(f32)` ✅ | bridged ✅ | not on app yet ⏳ |
| 8 | `SteamHighFlowStart` | `0x80382C` | `set_steam_highflow_start(Duration)` ✅ | bridged ✅ | not on app yet ⏳ |
| 9 | `SteamTwoTapStop` | `0x803850` | not yet ⏳ | — | — |
| 10 | `CupWarmerTemp` | `0x803874` | `set_cup_warmer_temperature(u8)` ✅ | bridged ✅ | not on app yet ⏳ |
| 11 | `TankTempThreshold` | `0x80380C` | `set_tank_threshold(u8)` ✅ | bridged ✅ | not on app yet ⏳ |
| 12 | `EspressoWarmupTimeout` | `0x803838` | not yet ⏳ | — | — |
| 13 | `UsbChargerOn` | `0x803854` | `set_usb_charger_on(bool)` ✅ | bridged ✅ | not on app yet ⏳ |

**8 of 13 already have the core method + bridge**, so they only need
settings fields + UI surfaces + connect-time push wired. **5 need
new core methods** (HotWaterIdleTemp, Phase1FlowRate, Phase2FlowRate,
SteamTwoTapStop, EspressoWarmupTimeout) — small additions; each follows
the existing pattern (`mmr_write_command(MmrRegister::X, value, byte_len)`).

## 3. Grouped by feature surface

The natural UI grouping (not connect-order):

### 3.1 Hot Water Settings — Settings → "Hot Water"

A new section under `/settings`. Five registers:

- `HotWaterIdleTemp` — "Idle temperature" (°C, 50-95, default 85)
- `HotWaterFlowRate` — "Dispense flow rate" (mL/s, 1-10, default 4)
- `Phase1FlowRate` — "Initial flow rate" (mL/s, used for the first
  ~25% of the dispense). The legacy splits hot water into two phases
  for splash control.
- `Phase2FlowRate` — "Steady flow rate"
- Plus a hot-water target volume (mL) — already exists in core as
  `ShotSettings::target_hot_water_vol`, written via
  `set_steam_hotwater_settings` (the typed setter). Doesn't need a
  per-MMR write.

### 3.2 Steam Settings — Settings → "Steam"

A new section. Four registers:

- `SteamFlow` — "Steam flow rate" (mL/s, 1-3, default 1.6)
- `SteamHighFlowStart` — "Initial-burst duration" (seconds, 0-3,
  default 0) — high flow at the start to clear condensate, then
  steps down to the steady rate.
- `SteamTwoTapStop` — "Double-tap stop window" (ms, 100-1000,
  default 500) — second tap within this window stops steam.
- Plus the steam target temperature + duration via the
  `ShotSettings` write path (already wired).

### 3.3 Group Flush Settings — Settings → "Group flush"

Or could live under Maintenance. Two registers:

- `FlushFlowRate` — "Flush flow rate" (mL/s, 4-10, default 6)
- `FlushTimeout` — "Max flush duration" (s, 1-15, default 5)

### 3.4 Machine Settings — Settings → "Machine" (existing section)

Two registers belong here next to the existing Connection / Telemetry
toggles:

- `TankTempThreshold` — "Tank-temp warning threshold" (°C, 50-95,
  default 65). Trigger for the tank-too-hot warning. Power-user
  setting.
- `EspressoWarmupTimeout` — "Post-wake warmup delay" (s, 0-180,
  default 60). DE1 won't accept a shot until this many seconds after
  wake. Lower = faster shots but risk pulling before fully warm.
- `UsbChargerOn` — "Charge tablet from DE1" (boolean, default true).

### 3.5 Cup Warmer Settings — Bengle / DE1XXL only

Gated by `MachineModel ∈ {DE1XL, DE1XXL, DE1XXXL, DE1CAFE, BENGLE}`
(the audit's MMR identity reads, now at connect, surface this).

- `CupWarmerTemp` — "Cup-warmer plate temperature" (°C, 30-90,
  default 50).

## 4. Implementation order (smallest → largest)

### Phase 1 — Machine section additions (smallest)

The fastest landing. Three registers (Tank threshold, warmup timeout,
USB charger), all already plumbed through to the web facade. ~30 min
of work each.

1. Add `tankTempThresholdC` to settings.
2. Add `espressoWarmupTimeoutS` to settings.
3. Add `chargeTabletFromDe1` to settings.
4. Three new rows in MachineSection's Connection group (or split
  into a new "Machine behaviour" group).
5. Three new methods on CremaApp (one-line wrappers).
6. Three new lines in the `onState('ready')` push block.
7. Core method needed for: `set_espresso_warmup_timeout(Duration)` —
  the only one not already there. Trivial addition.

### Phase 2 — Group Flush section (small)

Add a new Settings section ("Group flush") with two rows. Both core
methods + bridge already there. ~1 h.

### Phase 3 — Hot Water section (medium)

Add a new Settings section. Three new core methods needed
(HotWaterIdleTemp, Phase1FlowRate, Phase2FlowRate), one already
(HotWaterFlowRate). Five rows in the new section. ~2 h.

### Phase 4 — Steam section (medium)

Add a new Settings section. One new core method needed
(SteamTwoTapStop), three already. Three rows in the new section. ~1 h.

### Phase 5 — Cup Warmer section (capability-gated, smallest)

Add a single row in the Machine section, gated by
`snapshot.de1MachineInfo.MachineModel ∈ Bengle_set`. Core method
already exists. ~30 min.

## 5. Naming conventions to settle before Phase 1 starts

- **Settings field names**: `camelCase` per existing convention.
  Suffixes for clarity: `C` for °C (`tankTempThresholdC`), `S` for
  seconds (`espressoWarmupTimeoutS`), `MlPerS` for mL/s
  (`hotWaterFlowMlPerS`).
- **CremaApp method names**: match the core method's intent —
  `setHotWaterFlowRate(mlPerS: number)` (the web type is `number`;
  the bridge converts to `f32`).
- **Bridge / wasm**: already in place — no changes needed.
- **Defaults**: copy the legacy defaults from `de1_init.tcl` (where
  `::settings(*)` defaults live). Check each before merging.

## 6. Cross-cutting work

### 6.1 Settings storage versioning

Adding 9-13 new settings fields. The existing
`lib/settings/store.svelte.ts` reads from
`crema.settings.v1`. New defaults won't conflict with old
localStorage state — missing fields fall through to `DEFAULT_SETTINGS`
on read. **No migration needed.**

### 6.2 Validation

Settings range guards belong in the UI control (e.g.,
`<input type="number" min={50} max={95}>`). The core's typed setters
don't validate ranges today — the DE1 firmware clamps internally. A
reasonable add: range-clamp on the web facade so out-of-band values
never reach the wire.

### 6.3 Connect-time push consolidation

The `onState('ready')` block in `app.svelte.ts` is growing. After
Phase 1-4 it'll have 12+ MMR writes plus the existing
profile-upload + line-freq + suppress-sleep. Worth refactoring into
a single `pushSettingsToDe1()` method that walks the settings store
and emits each write in order. Mirrors the legacy's
`later_new_de1_connection_setup` proc structure.

### 6.4 Lockout coordination

Each of these methods goes through `refuse_if_firmware_locked` on
the core side. During a firmware upload, the connect-time push would
emit ~12 `FirmwareLockoutHit` events. **Recommended**: gate the
`pushSettingsToDe1()` block on `app.firmwareUpdateState !== 'in-progress'`
to skip the writes cleanly rather than collecting a dozen lockout
events in the log.

## 7. Acceptance — what "done" means

- All 13 registers have a settings field, a UI surface, and fire on
  user change + connect.
- The Machine card's identity row gates the Cupwarmer row by model.
- New Settings sections (Hot Water, Steam, Group Flush) are
  reachable from the nav.
- The connect-time push is consolidated and lockout-aware.
- Legacy defaults match — anyone migrating from the legacy app sees
  the same out-of-box behaviour.

## 8. Open questions

1. **Do users actually change Phase1FlowRate / Phase2FlowRate?** These
  exist in the legacy but very few hobbyists touch them. Cheapest
  resolution: surface them in an "Advanced hot water" sub-group; OK
  if 99% of users never see them.
2. **Should the Group Flush section live under Maintenance instead?**
  The legacy puts it under Hot Water → Group Cleaning. Crema's
  existing "Water & maintenance" section is the natural home.
3. **EspressoWarmupTimeout — surface to the user, or keep hidden?**
  Legacy exposes it under "Power user" toggles. Lower-risk default:
  hide behind Advanced; document the default but don't tempt users
  to make their first shots cold.
