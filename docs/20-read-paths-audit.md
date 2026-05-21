# 20 — Read-paths audit: gaps, bugs, plan

**Status:** audit only — no code changes in this branch
**Branch:** `read-paths-audit` (off `main`)
**Companions:** `docs/10-wiring-existing-read-paths.md` (implementation spec
for the read paths that landed),
`docs/11-new-read-paths-and-ui.md` (R1–R6 read-side landed, UI deferred),
`docs/14-write-actions-audit.md` (the write-side counterpart in spirit),
`docs/16-profile-upload-plan.md` §6.1 / §6.2 (snoop-verified profile-upload
findings, 2026-05-21), `docs/17-firmware-update-plan.md` §7 (snoop-verified
firmware-update findings, same session),
`docs/19-write-path-verification.md` (the write-side post-implementation audit).

---

## 1. Methodology

This document is the **read-side counterpart** to `docs/14`. It enumerates
every place the DE1 (and the connected Bookoo scale) exposes data Crema can
observe, cross-references each against:

1. **`core/de1-protocol/src/*.rs`** — wire decoders. Every decoded field is
   accounted for, whether it reaches an `Event` or not.
2. **`core/de1-app/src/event.rs`** — the FFI `Event` enum. Every variant is
   listed and checked against `applyEvent`.
3. **`core/de1-app/src/lib.rs`** — the orchestrator. Every `last_*` cache,
   every monitor, every Source decoder is walked to confirm what triggers
   each event.
4. **`web/src/lib/state/ui-state.svelte.ts`** — every `case '…':` arm of
   `applyEvent` (lines 581–969).
5. **`web/src/lib/core/index.ts`** — every method on the `CremaCore` async
   facade (538 LOC), cross-checked against the bridge.
6. **The UI** — every Svelte component under
   `web/src/lib/components/brew/`,
   `web/src/lib/components/settings/sections/`,
   `web/src/lib/components/profiles/`,
   `web/src/lib/components/history/`, and the route files
   `web/src/routes/{scale,history,profiles,settings}/+page.svelte`. Every
   `ui.<field>` / `snap?.<field>` / `snapshot.<field>` reference is
   inventoried.

Every finding cites the file path with line number. Where the legacy
de1app does something Crema does not (or vice-versa), that is called out
even if it is outside the "displayed widget" framing — the 2026-05-21 HCI
snoop in docs/16 §6 + docs/17 §7 is the gold-standard reference for what
the legacy actually does on the wire.

---

## 2. The data the DE1 (and scale) expose

### 2.1 DE1 notify characteristics — what arrives unprompted

Subscribed in `web/src/lib/ble/de1.ts:250-275`. Six notify subscriptions
in total; one (Version) is read once at connect time as a one-shot Read,
two (MMR_READ / FRAME_WRITE) are request/reply.

| UUID | Notify | Crema `Source` | Decoder | Event(s) emitted | Status |
|---|---|---|---|---|---|
| `A001` Version | one-shot Read at connect | `De1Version` | `firmware.rs::Version::decode` (18 B) | `Event::Firmware` | Wired (`de1.ts:288-307`) |
| `A005` ReadFromMMR | reply to a Write to same UUID | `De1MmrRead` | `mmr.rs::MmrReadReply::decode` (20 B) | `Event::MmrValue` (one per register) | Wired (`de1.ts:267`; only `FirmwareVersion` read triggered, `de1.ts:319`) |
| `A00D` ShotSample | ~4–10 Hz during a shot | `De1ShotSample` | `shot_sample.rs::ShotSample::parse` (19 B, 11 fields) | `Event::Telemetry` (subset: 6 of 11 fields), plus `ShotPhaseChanged`/`ShotFrameChanged` via `ShotMonitor` | **Subset only** — see §5.5 |
| `A00E` StateInfo | on state/substate change | `De1State` | `state.rs::StateInfo::parse` (2 B) | `Event::MachineStateChanged`, plus `ShotStarted`/`ShotCompleted`/`WaterSessionStarted`/`WaterSessionCompleted`/`SteamSessionStarted`/`SteamSessionCompleted`/`SteamEcoModeChanged` derivations | Wired |
| `A00F` HeaderWrite | (none — see §5.5) | `De1ProfileHeader` (in core, unused by BLE shell) | `profile.rs::ShotHeader::decode` | `Event::ProfileHeaderRead` | **Dormant** — connect-time Read removed in `49f0803` after HCI snoop showed (a) legacy never Reads it, (b) DE1 returns zero bytes (docs/16 §6.1) |
| `A010` FrameWrite | (none — see §5.5) | `De1FrameAck` | `profile.rs::ack_frame_byte` (byte 0 of 8) | (no event emitted; advances `ProfileUploadProgress`) | **Synthesised** — DE1 emits zero HVN on this UUID per snoop; shell synthesises acks from successful Writes (`app.svelte.ts:339-359`, docs/16 §6.2) |
| `A011` WaterLevels | on tank-level change | `De1WaterLevels` | `command.rs::WaterLevels::decode` (4 B) | `Event::WaterLevel` | Wired |
| `A012` Calibration | reply to a Write to same UUID | `De1Calibration` | `calibration.rs::Calibration::decode` (14 B) | `Event::Calibration` (only `ReadCurrent` / `ReadFactory` replies surfaced — `lib.rs:1521-1531`) | **Never invoked** — no UI nor connect-time path calls `readCalibration` |
| `A002` RequestedState | (write-only — no notify subscribed) | — | — | — | Write-only |
| `A006` WriteToMMR | (write-only) | — | — | — | Write-only |
| `A00B` ShotSettings | (write-only here; legacy reads it via `de1_read_hotwater` at connect — Crema does not) | — | — | — | **Gap** — see §5.5 |

Two notify-eligible characteristics the DE1 exposes that Crema does NOT
subscribe to:

- `A00B` ShotSettings (`cuuid_0B`) — the legacy reads it once at connect
  (`de1_comms.tcl:1659-1663`, `de1_read_hotwater`) to pull the
  steam/hot-water defaults back from the machine. Crema only ever Writes
  this characteristic. No UI surface reads back from the wire so this is
  cosmetic; it would only matter if the user changes the settings from
  another app and wants Crema to reflect them. **Low priority.**
- The legacy also reads `Version` (we do — wired), `StateInfo` (we
  subscribe — wired) on connect-time bootstrap.

### 2.2 DE1 read characteristics — request/reply

Three of the above use the request/reply pattern (write a request,
notification with the reply lands on the *same* characteristic):

- **MMR read** — `read_mmr(register)` in `lib.rs:348-355` builds a
  20-byte `ReadFromMMR` packet written to `WriteTarget::De1MmrRequest`
  (`A005`); reply decoded by `handle_mmr_read` (`lib.rs:1478-1502`) into
  `Event::MmrValue`. **Only one register is read in practice today**:
  `FirmwareVersion` (`0x800010`), triggered at connect time
  (`de1.ts:319`). All other 22 `MmrRegister` variants are decodable but
  unread. (Doc 11 R1 spec proposes reading the diagnostic set — see §5.5.)
- **Calibration read** — `read_calibration(target)` /
  `read_factory_calibration(target)` (`lib.rs:365-386`) build a
  `Calibration::read_request` packet to `WriteTarget::De1Calibration`
  (`A012`); reply decoded by `handle_calibration` (`lib.rs:1511-1532`)
  into `Event::Calibration`. **Never invoked anywhere** — no UI button,
  no connect-time hook. (Doc 11 R3 spec — read-side ready, UI deferred.)
- **Version read** — one-shot, no follow-up subscription needed; wired.

### 2.3 DE1 MMR registers (all `MmrRegister` variants)

`MmrRegister::ALL` carries 23 variants (`core/de1-protocol/src/mmr.rs:190-214`).
Every one is decodable; the table below tells what each one means and
whether Crema reads it / writes it / surfaces it.

| Variant | Addr | Bytes | Decoded? | Read in core? | Writable in core? | Surfaced in UI? | Notes |
|---|---|---|---|---|---|---|---|
| `FirmwareVersion` | `0x80_0010` | 1 word | yes | **YES at connect** (`de1.ts:319`) | n/a (read-only) | YES — `MachineSection.svelte:124` shows `v{value}` | The only MMR register actually read on the wire today. |
| `GhcInfo` | `0x80_381C` | bit 0 = present, bit 1 = active | yes | no | n/a (info only) | no | Legacy reads at connect (`de1_comms.tcl:1316`). |
| `TankTempThreshold` | `0x80_380C` | 1 byte (°C) | yes | no | yes (`set_tank_threshold`) | no | Legacy reads on `de1_de1.tcl:316` (subscribes via MMR notify). |
| `FanThreshold` | `0x80_3808` | 1 byte (°C) | yes | no | yes (`set_fan_threshold`) | no | Legacy reads (`de1_comms.tcl:1321`). |
| `SerialNumber` | `0x80_3830` | 1 word | yes | no | n/a (read-only) | no | Legacy reads (`de1_comms.tcl:1255`). The Machine card has a slot but shows BLE id instead — see §5.5. |
| `SteamFlow` | `0x80_3828` | `int(10 × mL/s)` | yes | no | yes (`set_steam_flow`) | no | Legacy reads (`de1_comms.tcl:1250`). |
| `RefillKit` | `0x80_385C` | 4-byte (0/1/2/auto) | yes | no | yes (`set_refill_kit_present`) | no | Legacy reads (`de1_comms.tcl:1244`). |
| `FlushFlowRate` | `0x80_3840` | 2-byte (`int(10 × mL/s)`) | yes | no | yes (`set_flush_flow_rate`) | no | |
| `HotWaterFlowRate` | `0x80_384C` | 2-byte (`int(10 × mL/s)`) | yes | no | yes (`set_hot_water_flow_rate`) | no | |
| `Phase1FlowRate` | `0x80_3810` | 3 words | yes | no | yes (`heater_tweaks`) | no | Legacy reads 3 words at this base (`de1_comms.tcl:388`) covering `Phase1FlowRate`/`Phase2FlowRate`/`HotWaterIdleTemp`. |
| `Phase2FlowRate` | `0x80_3814` | as above | yes | no | yes (`heater_tweaks`) | no | |
| `HotWaterIdleTemp` | `0x80_3818` | as above | yes | no | yes (`heater_tweaks`) | no | |
| `GhcMode` | `0x80_3820` | 1 byte | yes | no | yes (`set_ghc_mode`) | no | Legacy reads (`de1_comms.tcl:1311`). |
| `SteamHighFlowStart` | `0x80_382C` | 1 byte (s) | yes | no | yes (`set_steam_highflow_start`) | no | Legacy reads (`de1_comms.tcl:1300`). |
| `HeaterVoltage` | `0x80_3834` | 1 byte (V) | yes | no | yes (`set_heater_voltage`) | no | Legacy reads (`de1_comms.tcl:1117`). |
| `EspressoWarmupTimeout` | `0x80_3838` | 1 byte (s) | yes | no | yes (via `heater_tweaks`) | no | |
| `CalibrationFlowMultiplier` | `0x80_383C` | 2-byte (`int(1000 × m)`) | yes | no | yes | no | Legacy reads (`de1_comms.tcl:1326`). |
| `FlushTimeout` | `0x80_3848` | 2-byte (`int(10 × s)`) | yes | no | yes | no | |
| `UsbChargerOn` | `0x80_3854` | 1 byte (0/1) | yes | no | yes | no | |
| `FeatureFlags` | `0x80_3858` | 2 bytes | yes | no | yes (`set_feature_flags`) | no | Distinct from `UserPresent` (docs/14 §4 confirmed). |
| `UserPresent` | `0x80_3860` | 1 byte | yes | no | yes (`set_user_present`) | no | Distinct from `FeatureFlags` — see `mmr.rs:142-148`. |
| `SteamTwoTapStop` | `0x80_3850` | 1 byte | yes | no | yes (via `heater_tweaks`) | no | |
| `CupWarmerTemp` | `0x80_3874` | 2 bytes (°C) | yes | no | yes (Bengle only) | no | |

**Registers the legacy reads but Crema does not even model:**

- `0x80_0008` — **CpuBoardVersion + MachineModel + FirmwareVersion** (3
  words read in one request — `de1_comms.tcl:1260`). The legacy maps
  this onto `cpu_board_model` (`0x800008`), `machine_model` (`0x80000C`,
  the second word), `firmware_version` (`0x800010`, the third word).
  Crema models only `FirmwareVersion`. **The CPU-board version and the
  machine model — "DE1+ v1.4", "BENGLE rev 3" — are absent.** A
  diagnostics screen that wants to display "Model: DE1+ v1.4" cannot, on
  current code. Add `MmrRegister::CpuBoardVersion` (`0x800008`) and
  `MmrRegister::MachineModel` (`0x80000C`). The latter is what powers
  the legacy's `is_bengle_model` check used to gate the cup-warmer
  setting (docs/14 §4.14). **Cleanest gap to land** — see §5.5.

### 2.4 Scale event sources (Bookoo serial / settings / weight stream)

Three notify pathways, all decoded in `core/de1-scale/`:

| Event source | Crema `Source` | Decoder | `Event` emitted | Surfaced in UI? |
|---|---|---|---|---|
| Weight notification (Bookoo `ff11`) | `ScaleWeight` | `Scale::parse_reading` → `ScaleReading` (10 fields) | `Event::ScaleReading` (10 fields, all forwarded) | mostly — see below |
| Command channel `ff12` (settings/serial response) | `ScaleCommand` | `Scale::parse_command_response` → `bookoo::CommandResponse::{Serial,Settings}` | `Event::ScaleConfig` (5 optional fields) | mostly |
| (no other notify source — the Bookoo has no dedicated battery notify; it rides on the weight packet) | — | — | — | — |

`ScaleReading` field coverage (every one used / dormant):

| Field | Type | `applyEvent` writes | Consumed by component |
|---|---|---|---|
| `weight` | `f32` | `scaleWeight` | YES — `BrewDashboard.svelte:181`, scale page hero (`scale/+page.svelte:48`) |
| `flow` (core's estimate) | `f32` | **DROPPED** — `applyEvent` only stores `r.device_flow`, never the core's `flow` | **dormant** — see §5.3 |
| `device_flow` (native) | `Option<f32>` | `scaleFlow` | YES — scale page sub-line (`scale/+page.svelte:64,326`) |
| `device_timer` | `Option<u32>` | `scaleTimer` | YES — scale page sub-line (`scale/+page.svelte:62,322`) |
| `device_volume` | `Option<u8>` | `scaleVolume` | YES — scale page settings segment (`scale/+page.svelte:457`) |
| `device_standby` | `Option<u8>` | `scaleStandbyMinutes` | YES — scale page auto-sleep row (`scale/+page.svelte:149,431`) |
| `device_battery` | `Option<u8>` | `scaleBattery` | YES — scale page header (`scale/+page.svelte:50,287`) |
| `device_flow_smoothing` | `Option<bool>` | `scaleFlowSmoothing` | YES — scale page row (`scale/+page.svelte:145,403`) |
| `device_auto_stop` | `Option<u8>` | `scaleAutoStop` | **dormant** — no UI selector exists; see §5.3 |

`ScaleConfig` field coverage:

| Field | `applyEvent` writes | Consumed by component |
|---|---|---|
| `anti_mistouch` | `scaleAntiMistouch` | YES — scale page row (`scale/+page.svelte:147,419`) |
| `active_mode` | `scaleActiveMode` | YES — mode segment (`scale/+page.svelte:475`) |
| `enabled_modes` | (only logged — not stored in any `UiSnapshot` field) | **dormant** — never surfaced |
| `serial` | `scaleSerial` | YES — scale page header (`scale/+page.svelte:54,295`) |
| `firmware_version` | `scaleFirmware` (formatted) | YES — scale page header (`scale/+page.svelte:52,291`) |

---

## 3. The data Crema's UI displays — by screen

### 3.1 Brew dashboard

`web/src/lib/components/brew/BrewDashboard.svelte` — the route's
centrepiece. Walked field by field:

| Widget | Source | Status |
|---|---|---|
| Profile name (`BrewDashboard.svelte:127-132`) | `ui.activeProfileTitle ?? activeProfile?.name ?? ui.activeProfileName ?? 'No profile selected'` | Wired — `activeProfileTitle` from `Event::ProfileUploadCompleted` is the real source; `activeProfileName` is a local mirror set on Profiles → "Load on Brew" (`+page.svelte:115`). Both work. |
| Profile meta (`pre-inf · ratio · target · °C`) | derived from `BrewParamState` seed (active profile or settings defaults) | Wired — but **see §3.2 ratio / temp / pre-inf are profile data, not live machine data** |
| Pressure readout (`ChannelReadout` "PRESSURE") | `tel?.pressure` from `Event::Telemetry.group_pressure` | Wired |
| Flow readout (`ChannelReadout` "FLOW") | `tel?.flow` from `Event::Telemetry.group_flow` | Wired |
| Temp readout (`ChannelReadout` "TEMP") | `tel?.temp` from `Event::Telemetry.head_temp` | Wired — group-head thermocouple, the warmed-up signal |
| Weight readout (`ChannelReadout` "WEIGHT") | `ui.scaleWeight` from `Event::ScaleReading.weight` | Wired |
| Yield card (`shotWeightM` / `yieldTarget`) | live `weight` while in shot, frozen `lastShot.yieldG` after | Wired |
| Ratio card | derived `weight / dose` | Wired |
| PhaseIndicatorCard (`BrewDashboard.svelte:301`) | `ui.shotFrame` from `Event::ShotFrameChanged`, plus active profile's segments | Wired (was A4 stub, replaced) |
| LiveChart goal line | active profile segments via `goalSegments` (`LiveChart.svelte:99-115`) | Wired (was A3 stub, replaced) |
| LiveChart curve | `ui.shotTelemetry` (buffered `Event::Telemetry`) | Wired |
| BeanContextCard (`BrewDashboard.svelte:306`) | `$lib/bean` store; grinder shows `bean.grinder` | Wired (A5 — was hardcoded "Niche Zero", now an editable field on the bean) |
| LastShotCard (`BrewDashboard.svelte:310`) | `ui.completedShot` from the `Event::ShotCompleted` fold | Wired |
| Foot: scale name | `ui.scaleName` | Wired |
| Foot: "Group" | `tempM.value` from `tel.head_temp` (`BrewDashboard.svelte:381`) | **FIXED in `d129eef`** (was `mixTempM` showing `mix_temp` labelled "Group" — example of "wired but mislabeled", now corrected). |
| Foot: "Steam" | `steamTempM.value` from `tel.steamTemp` (`Event::Telemetry.steam_temp`) | Wired (A1 — was hardcoded `148 °C`, now real) |
| Foot: Water | `waterTankMl(ui.waterLevel)` (volume via lookup table) + "refill soon" (`waterRefillSoon`) | Wired (E2 cue) |
| Start/Stop button | local `manualRunning` state — `BrewDashboard.svelte:112,238` | **STUB** — `// TODO: wire to DE1 control` |
| Edit / Switch profile buttons | no handler | **STUB** (`BrewDashboard.svelte:263`) |

QuickSheet steppers (Dose, Yield, Brew Temp, Steam, Hot Water, Pre-Inf,
Flush) all bind to a local `BrewParamState` model — **none of them
reaches the DE1**. `brew-params.svelte.ts:1-12` is explicit: "the
brew-CONTROL surface is UI-only … every place a value would reach the
DE1 is marked `// TODO: wire to DE1 control`." The Steam / Hot Water /
Flush steppers in particular would map onto
`set_steam_hotwater_settings` (Steam, Hot Water) and `set_flush_timeout`
/ `set_flush_flow_rate` (Flush). Both setters exist in the core bridge.

### 3.2 Profile picker / profile cards / individual profile pages

- **`web/src/routes/profiles/+page.svelte`** — the library grid.
  - Profile list (`profiles = store.all`) — wired to `$lib/profiles`.
  - Filtering / sorting / pinning / duplicating / delete — wired.
  - `loadOnBrew(id)` (`+page.svelte:110-118`) — wired: calls
    `app.uploadProfile(toCoreProfile(profile))`. Real profile upload to
    the DE1 (after the §2 implementation in docs/16 landed in commit
    `d14cfe3`). Sets `ui.activeProfileName` (local mirror) and arms the
    core, which on success emits `ProfileUploadCompleted` →
    `ui.activeProfileTitle`. Both fields end up populated.
  - `importProfile()` (`+page.svelte:144`) — **STUB**.
- **`web/src/lib/components/profiles/ProfileCard.svelte`** — one card.
  - All metrics (`Ratio`, `Dose`, `Temp`, `Pre-inf`) read from
    `CremaProfile` fields (`fromCoreProfile` adapts the core `Profile`
    JSON into `CremaProfile` — `profiles/model.ts:379-409`).
  - `dose` defaults to 18 if `profile.dose <= 0` (`model.ts:398`). The
    core `Profile.dose` field was added (docs/10 C1); built-in profiles
    that carry a `dose > 0` show their real dose, ones that don't show
    18. The "every card shows a hardcoded 18 g" gap from docs/10 C1 is
    **mostly closed** — but the fallback when a custom profile has no
    dose is still 18 g. Acceptable.
  - `lastUsed` — formatted by `relativeLastUsed` (`model.ts:159-179`),
    seeded from `store.setActive` (docs/10 C2 — wired).
  - `ProfilePreview` — 3-curve mini-chart from `profile.segments`.

- **`web/src/routes/profiles/[id]/+page.svelte` (the editor)** — pure
  client-side model editing; no DE1 reads.

### 3.3 Machine settings card

`web/src/lib/components/settings/sections/MachineSection.svelte`.

- **Hero machine card** (`MachineSection.svelte:202-276`):
  - Firmware label (`MachineSection.svelte:124-127`): prefers
    `snapshot.de1MachineInfo.FirmwareVersion` (`v{value}` — the
    legacy-format "v1352" build number from MMR `0x800010`), falls back
    to `snapshot.de1Firmware` (the `Version` characteristic's
    BLE-block label `v1.4.142 (API 4)`). **Wired.**
  - BLE id (`MachineSection.svelte:129`): `diag.deviceId` (Web
    Bluetooth's opaque per-origin device id). Wired.
  - Group temperature: `formatTemp(snapshot.latestTelemetry?.mixTemp,
    …)` (`MachineSection.svelte:131-133`). **Inconsistency** — this
    reads `mix_temp` while the Brew dashboard's "Group" foot reads
    `head_temp`. See §5.2.
  - Connect / Disconnect — wired to `app.connectDe1()` /
    `app.disconnectDe1()`.
  - Rename, Re-pair, Forget — **STUBS** (`MachineSection.svelte:186-193`).
- **Firmware-update block** (`MachineSection.svelte:264-275`):
  - `firmware_update_status` — wired (`firmware_info::compare` against
    the cached MMR `FirmwareVersion`). Read-only; the actual update is
    deferred to v2 per docs/17.
- **Connection diagnostics group** (`MachineSection.svelte:310-359`):
  - Connection state — wired
  - Selected device name/id — wired
  - GATT verified — wired
  - Machine state (`snapshot.machineState`) — wired (the "State /
    Substate" line)
  - Notifications received tally — wired
- **Peripherals group** (`MachineSection.svelte:361-385`):
  - Scale — "See Scale page" placeholder, no live state.
  - Grinder, Weight-aware tamper — **STUBS** (no device registry).
- **Connection / Telemetry rate group** — wired to `lib/settings`.

### 3.4 Scale page

`web/src/routes/scale/+page.svelte`. Already in good shape — every
`ScaleReading` Bookoo field except `device_auto_stop` is consumed
somewhere, and the `Event::ScaleConfig` `enabled_modes` field is the
only `ScaleConfig` field never read.

- Hero readout — `ui.scaleWeight` (`scale/+page.svelte:48,309`). Wired.
- Sub-line — timer (`ui.scaleTimer` formatted M:SS), device flow
  (`ui.scaleFlow`). Wired (was F1 stub, replaced).
- Re-pair button → `app.connectScale()`. Wired.
- Tare button → `app.tareScale()`. Wired.
- Reset peak / Start timer (sc-actions row 2&3) — **STUBS**
  (`scale/+page.svelte:189-196`). `Start timer` is the dormant counterpart
  to the live `device_timer_ms` — the core *now* exposes `startTimer` /
  `stopTimer` / `resetTimer` (committed in `3c247f7`) plus a reactive
  auto-policy (`96a7137`), but the button text still says "Stop the
  timer" with no handler.
- Dose helper progress — `weightG` against `activeProfile?.dose ?? 18` —
  wired.
- Quick-settings rows:
  - Flow smoothing — `app.setScaleFlowSmoothing(!flowSmoothing)`. Wired.
  - Anti-mistouch — `app.setScaleAntiMistouch(!antiMistouch)`. Wired.
  - Auto-sleep — `app.setScaleStandbyMinutes(...)`. Wired.
  - Beeper volume — `app.setScaleVolume(step)`. Wired (was F2 stub).
  - Display mode — `app.setScaleMode(id)`. Wired.
  - "Auto-tare on shot start" / "Stop-on-weight" toggles — **STUB**
    (local UI state only — `scale/+page.svelte:156-157`).
- Recent activity log — `ui.eventLog`. Wired.
- **Missing:** no auto-stop-mode selector (Flow-Stop / Cup-Removal). The
  core has `set_scale_auto_stop`, the state has `scaleAutoStop`, the
  event stream sets it from `device_auto_stop`. **Dormant.**

### 3.5 History page

`web/src/routes/history/+page.svelte` and
`web/src/lib/components/history/{ShotRow,ShotDetail,StaticShotChart}.svelte`.

- Shot list — `getHistoryStore().all`. Wired.
- Search / filter / range — wired (`+page.svelte:48-61`). Range filter
  ("Last 30 days") is now wired (was B2 stub).
- Stats — all derived from the store (`+page.svelte:69-102`).
- `ratioLabel(record)` in `history/model.ts:98-105` divides by
  `record.dose ?? 18` — uses the **real recorded dose** when present;
  falls back to 18 g for pre-existing records. Wired (was B1 stub).
- `ShotDetail` — chart redraws the stored telemetry series. Star rating,
  notes, download capture all wired.
- `ShotDetail.loadOnBrew` (`ShotDetail.svelte:143`) — **STALE STUB**.
  Comment says "the core exposes no profile-upload path" — false. The
  profile-upload path landed in `d14cfe3`. **See §5.4.**
- `ShotDetail.saveAsProfile`, `share` (Visualizer) — **STUBS**.
- "Export" and "Compare" buttons in the header — **STUBS**
  (`+page.svelte:131-135`).

### 3.6 Settings → other sections

- **`AboutSection.svelte`** — version metadata, no DE1 reads.
- **`AdvancedSection.svelte`** — telemetry display toggles (real
  prefs), Home Assistant (stub), Replay-a-capture (wired). The replay
  picker reads `ui.replay` (`AdvancedSection.svelte:40`); wired.
- **`BrewDefaultsSection.svelte`** — defaults for the Quick Sheet seed.
  Wired (D2).
- **`DisplaySection.svelte`** — unit preferences (D1). Wired.
- **`SharingSection.svelte`** — Visualizer / forum integrations — all
  **STUBS**.
- **`SoundSection.svelte`** — sound preferences. Wired.
- **`WaterSection.svelte`**:
  - Filter / descale / backflush cards — wired to `$lib/maintenance`
    (the integrated-flow water counter; docs/10 E1).
  - Water-chemistry inputs (source, hardness, TDS) — **STUBS**
    (local-only state, `WaterSection.svelte:38-42`).
  - **Missing:** there is no refill-threshold control. The core's
    `set_refill_threshold(thresholdMm)` is wired into the bridge but no
    Svelte component calls it (`web/src/lib/core/index.ts:243-244`).
    `ui.waterRefillThreshold` is read-back-displayable but
    write-back-orphaned.

---

## 4. Cross-reference matrix

One row per machine-side data source. Columns: **D**ecoded? · **C**ached
on `CremaCore`? · **E**vent emitted? · **S**urfaced in `UiSnapshot`? ·
**U**I consumer? · Known issues.

### DE1 data sources

| Data source | Crema decode | Cached on core | Event | UiSnapshot field | UI consumer | Issues |
|---|---|---|---|---|---|---|
| `StateInfo.state` | `state.rs::MachineState` | `last_state` | `MachineStateChanged.state` | `machineState` (string) | MachineSection diag, foot, log | none |
| `StateInfo.substate` | `state.rs::SubState` | `last_state` (composite) | `MachineStateChanged.substate` | `machineState` (string), `machineError` (via `machineErrorText` helper) | machineState shown; **`machineError` set, but no banner UI** | R5 read-side landed, UI deferred (§5.6) |
| `ShotSample.sample_time` | yes | no | **NOT emitted** | — | — | Dropped at the `Event::Telemetry` seam (`lib.rs:1135-1142`). Low value — it's a free-running AC half-cycle counter — but legacy / Visualizer captures use it. |
| `ShotSample.group_pressure` | yes | no | `Telemetry.group_pressure` | `latestTelemetry.pressure`, `shotTelemetry[*].pressure` | LiveChart, ChannelReadout, LastShotCard (`peakPressure`) | wired |
| `ShotSample.group_flow` | yes | no | `Telemetry.group_flow` | `latestTelemetry.flow`, etc. | wired | wired; **water-accumulation** integrates this (`app.svelte.ts:228-235`) |
| `ShotSample.head_temp` | yes | no | `Telemetry.head_temp` | `latestTelemetry.temp` | ChannelReadout "TEMP", Brew foot "Group", LastShotCard "Peak temp" | fixed in `d129eef` (was mislabeled mix_temp) |
| `ShotSample.mix_temp` | yes | no | `Telemetry.mix_temp` | `latestTelemetry.mixTemp` | **Only** MachineSection "Group" line (`MachineSection.svelte:131`) | **Inconsistent** — Brew "Group" reads `head_temp`, Machine "Group" reads `mix_temp`. §5.2. |
| `ShotSample.set_mix_temp` | yes | no | **NOT emitted** | — | — | Setpoint, useful for "are we at target" overlay. Dropped at the event seam. Doc 10 / 11 don't surface targets either. §5.3. |
| `ShotSample.set_head_temp` | yes | no | **NOT emitted** | — | — | Setpoint — same as above. |
| `ShotSample.set_group_pressure` | yes | no | **NOT emitted** | — | — | Setpoint — same as above. Doc 11 R4 calls for goal-line, but we derive it from the *profile*, not from the wire setpoint. |
| `ShotSample.set_group_flow` | yes | no | **NOT emitted** | — | — | Setpoint — same as above. |
| `ShotSample.frame_number` | yes | no | `ShotFrameChanged.frame` (via `ShotMonitor`) | `shotFrame` | PhaseIndicatorCard | wired |
| `ShotSample.steam_temp` | yes | no | `Telemetry.steam_temp` | `latestTelemetry.steamTemp` | Brew foot "Steam" | wired (A1) |
| `WaterLevels.current_mm` | yes | no | `WaterLevel.level` (+ 5 mm correction) | `waterLevel` | Brew foot "Water" (via `waterTankMl`) | wired |
| `WaterLevels.refill_threshold_mm` | yes | no | `WaterLevel.refill_threshold` | `waterRefillThreshold` | "refill soon" cue via `waterRefillSoon` | wired |
| `Version.ble.{release,commits,api_version,sha}` | yes | `last_firmware` | `Firmware.{release,commits,api_version,firmware_string}` | `de1Firmware` (string) | MachineSection "Firmware" line | wired (D4) |
| `Version.fw.*` | yes (decoded into `Version.fw`) | `last_firmware` | dropped (`firmware_string` may include it; see `firmware.rs:251-263`) | — | — | Snoop showed the CPU/FW block is often all-zero / a mirror of the BLE block; legacy treats BLE as the headline. Acceptable. |
| `MmrRegister::FirmwareVersion` | yes | `last_firmware_build` | `MmrValue { register, value }` | `de1MachineInfo.FirmwareVersion` | MachineSection (preferred over `de1Firmware`) | wired |
| `MmrRegister::*` (the other 22) | yes | no | `MmrValue` (emitted if reply arrives — none arrive today) | `de1MachineInfo.{Each}` | none | **Dormant** — read never triggered. §5.5 R1. |
| `MmrRegister::CpuBoardVersion` / `MachineModel` | **NOT MODELLED** | — | — | — | — | Cleanest gap to land. §5.5. |
| `Calibration.{target, command, de1_reported, measured}` | yes | no | `Event::Calibration` (only for `ReadCurrent` / `ReadFactory`) | `de1Calibration[target].{current,factory}` | none | **Dormant** — `readCalibration` never invoked. §5.6. |
| `ShotHeader.*` (read of `HeaderWrite`) | yes | `last_profile_header` | `ProfileHeaderRead.{frame_count, preinfuse_frame_count, minimum_pressure, maximum_flow}` | `loadedProfileShape` | none | **Dormant on Read** — legacy never Reads `cuuid_0F` per snoop (docs/16 §6.1). Crema removed the connect-time Read in `49f0803`. The fields and Source variant stay (used in write-path) but nothing produces `Event::ProfileHeaderRead` any more. §5.3. |
| `FrameWrite` echo (ack matcher) | `profile.rs::ack_frame_byte` | `profile_upload.expected_acks` walked | (no event from the wire; orchestrator emits `ProfileUploadProgress`/`Completed`/`Failed`) | `profileUploadProgress` | none | **Dormant in UI** — see §5.3. |
| `ShotSettings` (`cuuid_0B`) read at connect (legacy does this) | yes (the codec exists for round-trip) | `steam_hotwater_settings` (writeback cache) | dropped from notifications (no `Source::De1ShotSettings`) | — | — | Legacy round-trips this on connect (`de1_read_hotwater` → `de1_comms.tcl:1659`); Crema never reads back. §5.5. |

### Scale data sources

| Data source | Crema decode | Cached on core | Event | UiSnapshot field | UI consumer | Issues |
|---|---|---|---|---|---|---|
| `ScaleReading.weight_g` | `bookoo.rs` etc. | `flow` estimator state | `ScaleReading.weight` (smoothed) | `scaleWeight` | Brew, Scale page, LastShotCard, History dose helper | wired |
| `ScaleReading.flow_g_per_s` (device flow) | yes | no | `ScaleReading.device_flow` | `scaleFlow` | Scale page sub-line | wired |
| Core-estimated flow (`FlowEstimator`) | n/a | yes | `ScaleReading.flow` | **DROPPED** at `applyEvent` (`ui-state.svelte.ts:675` only stores `device_flow`) | — | **Dormant** — see §5.3. |
| `ScaleReading.timer_ms` | yes | no | `ScaleReading.device_timer` | `scaleTimer` | Scale page sub-line | wired |
| `ScaleReading.volume` | yes | no | `ScaleReading.device_volume` | `scaleVolume` (two-way) | Scale page volume control | wired |
| `ScaleReading.standby_minutes` | yes | no | `ScaleReading.device_standby` | `scaleStandbyMinutes` (two-way) | Scale page auto-sleep | wired |
| `ScaleReading.battery_percent` | yes | no | `ScaleReading.device_battery` | `scaleBattery` | Scale page header | wired |
| `ScaleReading.flow_smoothing` | yes | no | `ScaleReading.device_flow_smoothing` | `scaleFlowSmoothing` (two-way) | Scale page row | wired |
| `ScaleReading.auto_stop` | yes | no | `ScaleReading.device_auto_stop` | `scaleAutoStop` | **none** | **Dormant** — no UI selector. §5.3. |
| `ScaleConfig.serial` | yes | no | `ScaleConfig.serial` | `scaleSerial` | Scale page header | wired |
| `ScaleConfig.firmware_version` | yes | no | `ScaleConfig.firmware_version` | `scaleFirmware` | Scale page header | wired |
| `ScaleConfig.anti_mistouch` | yes | no | `ScaleConfig.anti_mistouch` | `scaleAntiMistouch` | Scale page row | wired |
| `ScaleConfig.active_mode` | yes | no | `ScaleConfig.active_mode` | `scaleActiveMode` | Scale page mode segment | wired |
| `ScaleConfig.enabled_modes` | yes | no | `ScaleConfig.enabled_modes` | **NOT stored in any `UiSnapshot` field** (only logged) | — | **Dormant** — could grey out unavailable modes in the selector. Low priority. §5.3. |

---

## 5. Findings, categorized

### 5.1 Wired correctly (no work needed)

Every entry below has a real path from the wire to a pixel, with no
hardcoded constant in between, no `// TODO`, and no known label mismatch.

1. **Machine state + substate** — full `MachineStateChanged` →
   `machineState` → MachineSection diag + Brew foot scale-state + log.
2. **Shot lifecycle** — `ShotStarted` / `ShotPhaseChanged` /
   `ShotFrameChanged` / `ShotCompleted` events → `shotInProgress`,
   `shotElapsed`, `shotFrame`, `shotTelemetry`, `completedShot`,
   `lastShotCompletedAt`, `lastShotDuration`. Drives ExtractionTimer,
   PhaseIndicatorCard, LastShotCard.
3. **Live telemetry channels** — pressure / flow / head_temp / steam_temp /
   mix_temp from `Event::Telemetry` → `latestTelemetry`, `shotTelemetry`
   → ChannelReadout, LiveChart, LastShotCard peaks. (Brew foot "Group" =
   `head_temp` after `d129eef` fix.)
4. **Puck resistance** — derived in state layer
   (`puckResistance(pressure, flow)`, `ui-state.svelte.ts:554-558`).
   Read-side wired (R4 in doc 11); UI consumer is the `TelemetrySample`
   struct, picked up by no chart series yet but available.
5. **Water tank level + refill cue** — `Event::WaterLevel` →
   `waterLevel`/`waterRefillThreshold` → Brew foot via `waterTankMl()` +
   `waterRefillSoon()`.
6. **Water-accumulation maintenance** — integrated flow over time, via
   `app.svelte.ts:228-235` → `$lib/maintenance` → WaterSection's filter /
   descale / backflush cards. (Docs 10 E1.)
7. **DE1 firmware label** — `Event::Firmware` →
   `de1Firmware`; MMR `FirmwareVersion` → `de1MachineInfo.FirmwareVersion`.
   MachineSection prefers the MMR build number.
8. **Firmware-update status** — `firmwareUpdateStatus()` compares cached
   MMR build against `LATEST_KNOWN_FIRMWARE_BUILD`. MachineSection
   "Check for updates" button.
9. **DE1 connection diagnostics** — `de1Diagnostics` (device name, id,
   `gattVerified`, notification tally) → MachineSection.
10. **Active profile identity** — `activeProfileTitle` (from
    `ProfileUploadCompleted`) is the real source; Brew header shows it.
11. **Profile upload + auto re-upload on every reconnect** —
    `uploadProfile` → BLE writes → synthesised `De1FrameAck`s → progress
    events → orchestrator advances. The auto re-upload on `ready`
    (`app.svelte.ts:158-160,474-495`) mirrors the legacy's
    `save_settings_to_de1` chain that the snoop confirmed.
12. **Scale weight, flow, timer, battery, firmware, serial, name** —
    full chain through `Event::ScaleReading` / `Event::ScaleConfig`.
    Scale page hero, sub-line, header.
13. **Scale settings (volume, standby, flow-smoothing, anti-mistouch,
    active mode)** — two-way through the Bookoo's settings echo. Scale
    page rows.
14. **Settings unit preferences** — D1 wired; Brew, History, Scale all
    drive readouts through one shared formatter family
    (`convertWeight`, `convertTemp`, etc.).
15. **History store** — shots recorded on `ShotCompleted` with real
    dose, real bean snapshot, real series; History page renders entirely
    from `lib/history`. B1 (real dose), B2 (range filter) are wired.

### 5.2 Wired but suspicious / mislabeled (fix needed)

1. **Group temperature inconsistency between screens.**
   - `web/src/lib/components/brew/BrewDashboard.svelte:380-382` —
     Brew foot "Group" reads `tempM` (= `tel?.temp` = `head_temp`). Fixed
     in `d129eef` (was mistakenly reading `mix_temp`).
   - `web/src/lib/components/settings/sections/MachineSection.svelte:131`
     — Machine card "Group" reads
     `snapshot.latestTelemetry?.mixTemp` (= `mix_temp`). **Still uses
     `mix_temp`**, but is labelled "Group" verbatim — same mislabel
     that was fixed on the Brew page. The two screens disagree on what
     "Group" means.
   - Recommendation: align MachineSection on `head_temp` (same as Brew),
     or relabel one of them. The Brew page's reasoning (recorded inline
     at `BrewDashboard.svelte:371-378`) is correct: `head_temp` stays
     meaningful at idle, `mix_temp` reads room-temp when nothing flows.
     The Machine card sits at idle most of the time, so `head_temp` is
     the better choice.
2. **`ShotDetail.loadOnBrew` is stale STUB.**
   - `web/src/lib/components/history/ShotDetail.svelte:143-146` — alerts
     "Loading a shot on Brew is coming in a later step" and the comment
     above says "the core exposes no profile-upload path". The path
     landed in `d14cfe3` (commit "core+web(profile): wire profile upload
     + loaded-profile read end-to-end"). The button **can be wired** to
     call `app.uploadProfile(toCoreProfile(<profile derived from this
     shot's profileName>))`. Comment + alert misleading.
3. **`activeProfileName` snapshot field is a dormant mirror, not the
   primary source.**
   - `ui-state.svelte.ts:207,423` declares it; the only writers are
     `routes/profiles/+page.svelte:115` (`app.state.patch({
     activeProfileName: profile.name })`) and the History pipeline
     (`app.svelte.ts:259`, captured as `profileName` on a
     `StoredShot`).
   - The brew page falls back through
     `ui.activeProfileTitle ?? activeProfile?.name ?? ui.activeProfileName`
     (`BrewDashboard.svelte:127-132`). The intended primary source is
     `activeProfileTitle` (set on `ProfileUploadCompleted`) — and that
     works. `activeProfileName` is a UI-only mirror that exists for
     pre-upload identity (so the brew page shows the freshly-clicked
     profile before the upload completes).
   - Not strictly a bug, but the design is muddy. The clean shape would
     be one field that the upload pipeline owns. Acceptable as-is; flag
     for cleanup when the brew control surface lands.
4. **PhaseIndicatorCard "frame index" interpretation when no shot is in
   progress.** `PhaseIndicatorCard.svelte:61` clamps `frame` to
   `segments.length - 1`; when `shotInProgress === false` the card still
   highlights the last frame (or `frame === 0` after `ShotStarted`
   reset). Cosmetic — the Brew dashboard hides the card when the Quick
   Sheet opens, but does NOT hide it between shots. Not a bug, but a
   visual edge. Acceptable.

### 5.3 Decoded in core, dormant in UI (no consumer reads it)

Each of these has a working wire-to-state path, but no Svelte component
reads the resulting `UiSnapshot` field. Doc 11 explicitly defers the UI
side of R1–R6; doc 14 §4 (the MMR setters) leaves the diagnostics-screen
UI to a later slice.

1. **`UiSnapshot.de1MachineInfo` — every register except
   `FirmwareVersion`.** R1 plumbing complete (`Event::MmrValue` →
   `de1MachineInfo`), but no UI control issues `app.readMmr(...)` for any
   other register, and even if one arrived it would have no consumer. The
   "Machine diagnostics screen" was deferred (doc 11 §UI side).
2. **`UiSnapshot.de1Calibration` — all three sensors.** R3 plumbing
   complete (`Event::Calibration` → `de1Calibration[target]`), but no UI
   ever triggers `app.readCalibration(...)`.
3. **`UiSnapshot.machineError`** — R5 plumbing complete
   (`MachineStateChanged.substate` → `machineErrorText` →
   `machineError`), no banner UI surfaces it.
4. **`UiSnapshot.lastShotCompletedAt` / `lastShotDuration` /
   `idleSince`** — R6 plumbing complete, no idle/ready widget.
5. **`UiSnapshot.loadedProfileShape`** — populated by
   `Event::ProfileHeaderRead`, but **the BLE shell no longer triggers
   that Read** (removed `49f0803`; the legacy never did either per
   snoop). The state field stays "for forward-compat in case the
   firmware ever exposes it on Read." `Event::ProfileHeaderRead` /
   `Source::De1ProfileHeader` are still defined; the wire end is dead.
6. **`UiSnapshot.profileUploadProgress`** — populated correctly by
   `ProfileUploadStarted/Progress/Completed/Failed`. No component reads
   it. The Brew page would naturally show "Uploading X… (acks/total)"
   when set, but doesn't.
7. **Core-estimated scale flow (`Event::ScaleReading.flow`).** The
   `FlowEstimator` computes a robust flow from the weight series, but
   `applyEvent` (`ui-state.svelte.ts:670-682`) only stores
   `device_flow ?? null` into `scaleFlow` — the core's own estimate is
   dropped. For weight-only scales (no `device_flow`), the foot reads
   `null`. Distinction noted in `event.rs:101-110` (both fields
   intentionally exposed). The Scale page sub-line falls back to "–"
   when `device_flow == null` even though the core has a perfectly good
   estimate. **Recommend**: in `applyEvent`, store
   `r.device_flow ?? r.flow` (preserving "device first, core fallback").
8. **`scaleAutoStop`** — set in state, no UI selector. The DE1's
   Bookoo `auto_stop` capability gates a selector that does not exist on
   the Scale page.
9. **`ScaleConfig.enabled_modes`** — never written into any
   `UiSnapshot` field (only emitted to the log). Could grey out
   unavailable modes in the mode selector; doesn't.
10. **`ShotSample` setpoint fields** — `set_mix_temp` / `set_head_temp`
    / `set_group_pressure` / `set_group_flow` are decoded but not in
    `Event::Telemetry`. Useful for a "live target vs. measured"
    overlay; not yet on any roadmap.
11. **`ShotSample.sample_time`** — the 16-bit AC-half-cycle counter.
    Visualizer captures use it for tick alignment; Crema's capture-replay
    format does not need it (we have wall-clock from `performance.now`).
    Low value, but legacy carries it. Acceptable to drop.

### 5.4 UI shows but data source is stubbed / hardcoded

A short list — most of the "TODO" markers are on the **control** side
(brew steppers, scale timer button), not the read side. The read-side
stubs that remain:

1. **`ShotDetail.loadOnBrew` / `saveAsProfile` / `share` are STUBs**
   (`ShotDetail.svelte:142-154`). Specifically:
   - `loadOnBrew` is technically wireable (profile-upload exists) — see
     §5.2 finding 2.
   - `saveAsProfile`: would need a curve→ProfileStep[] inverter; no
     core function exists. Deferred.
   - `share` (Visualizer upload): network layer the shell lacks.
     Deferred.
2. **Settings → Sharing → Visualizer link / Save / Sign in** — all
   STUBs (`SharingSection.svelte:54-60+`). Need network layer.
3. **Settings → Advanced → Home Assistant** — STUB
   (`AdvancedSection.svelte:55-56`). Need network layer.
4. **Settings → Water → water-chemistry inputs** — STUBs (local-only
   state — `WaterSection.svelte:38-42`).
5. **Settings → Machine → Rename / Forget / Pair grinder / Pair tamper**
   — STUBs (`MachineSection.svelte:186-193,261-262,374,383`). Need a
   device registry.
6. **Brew dashboard → Edit / Switch profile / Save preset / Start /
   Stop** — control-side STUBs
   (`BrewDashboard.svelte:264-272,398-403`, `QuickSheet.svelte:67-77`).
7. **Brew dashboard → QuickSheet steppers** — every Dose / Yield /
   Brew-Temp / Steam / Hot Water / Pre-Inf / Flush stepper is local
   state. Steam, Hot Water and the two flush parameters all have
   matching core setters (`set_steam_hotwater_settings`,
   `set_flush_timeout`, `set_flush_flow_rate`); not wired up.
8. **Scale page → Reset peak / Start timer** — STUBs
   (`scale/+page.svelte:185-196`). `Start timer` should call
   `app.startTimer()` / `app.stopTimer()` (core methods exist).
9. **Scale page → Auto-tare on shot start / Stop-on-weight** — local
   UI state only (`scale/+page.svelte:156-157`). Note the core's
   auto-stop is *armed* by `app.armAutoStop({weight, …})` which is
   reactive — these toggles should be persisted preferences feeding
   that.
10. **History → Export / Compare buttons** — STUBs
    (`history/+page.svelte:131-135`).

### 5.5 DE1 exposes but core doesn't decode

The smallest, cleanest gaps. Each is "the protocol crate is missing one
or two enum variants; the rest of the path is already in place."

1. **`MmrRegister::CpuBoardVersion` (`0x800008`)** —
   `cpuboard_machinemodel_firmwareversion` in legacy
   (`de1_comms.tcl:1260`). The DE1 returns 3 words at this base:
   `[CpuBoardVersion, MachineModel, FirmwareVersion]`. Crema models
   only the third. **Add** `CpuBoardVersion` at `0x800008`. Decoder is
   trivial. The diagnostics screen wants this.
2. **`MmrRegister::MachineModel` (`0x80000C`)** —
   `get_machine_model` in legacy (`de1_comms.tcl:1271`). Powers the
   legacy's `is_bengle_model` gate that docs/14 §4.14 already mentions
   (the cup-warmer setter is Bengle-only). Adding this is required
   before the cup-warmer setter can be properly capability-gated in the
   shell. **Add.**
3. **`MmrRegister::EspressoWarmupTimeout` is modelled but never read**
   — strictly this is a §5.3 finding (dormant), not a missing decode.
4. **`ShotSettings` notify subscription** — the legacy reads
   `cuuid_0B` once at connect (`de1_read_hotwater` →
   `de1_comms.tcl:1659-1663`) to learn what the firmware has stored for
   steam target / hot-water target / espresso volume / etc. Crema only
   ever Writes this characteristic; the codec is symmetric
   (`ShotSettings::decode` exists in `command.rs:125-143`) and unused on
   the read side. Add `Source::De1ShotSettings` + a connect-time Read +
   an `Event::SteamHotWaterSettingsRead { ... }`, so the Steam /
   Hot-Water / Espresso-Volume steppers can seed from the machine when
   they finally land. Low value while those steppers are local-only,
   but free protocol-side parity work.
5. **Setpoint fields in `ShotSample`** — see §5.3 finding 10. Adding
   them to `Event::Telemetry` is the same shape change as
   `head_temp`/`mix_temp` got in doc 10 A1/A2.

### 5.6 Documented in docs/10 or 11 as "deferred" — UI work waiting

Already on the roadmap; flagging for completeness so this audit covers
everything the planning docs do.

- **Machine diagnostics screen** (doc 11, "UI side — DEFERRED"). The
  expansion of MachineSection that surfaces all the dormant
  `de1MachineInfo` registers (§5.3 finding 1), the calibration values
  (§5.3 finding 2), plus the new `CpuBoardVersion` / `MachineModel`
  (§5.5 findings 1+2).
- **Error banner** (doc 11). Surface `machineError` (§5.3 finding 3) as
  a banner across the top of every screen when set.
- **Idle / ready indicators** (doc 11). Surface
  `lastShotCompletedAt` / `idleSince` as "X seconds since last shot",
  "warming up", etc. (§5.3 finding 4.)
- **Shot comparison / overlay** (doc 11). DSx-style.
- **Resistance** (doc 11 R4). The metric is computed; no chart series
  uses it. Trivial to add.
- **Maintenance / diagnostics actions** (doc 11). Descale, clean,
  flush workflows; calibration screens.
- **3rd-party / cloud** (doc 11). Visualizer upload/download.
- **DSx conveniences** (doc 11). Derived bean-weight / milk-weight,
  extraction ratio surfaced live.

---

## 6. Remaining work — plan

Priority order. Each item lands as a tight PR.

### 6.1 Bug fixes (priority 1)

- **Bug 1 — Machine card "Group" temp is mix_temp.** Fix
  `MachineSection.svelte:131` to read `latestTelemetry?.temp` instead of
  `latestTelemetry?.mixTemp` (matches the Brew page's `d129eef` fix).
  One-line change.
- **Bug 2 — `ShotDetail.loadOnBrew` STUB + stale comment.** Replace the
  alert with a real call to `app.uploadProfile(...)` derived from the
  shot's `profileName` (look the profile up via `getProfileStore()`).
  Update the comment block. ~20 LOC.
- **Bug 3 — `applyEvent` drops the core-estimated scale flow.** In
  `ui-state.svelte.ts:675`, store `r.device_flow ?? r.flow ?? null`
  instead of `r.device_flow ?? null`, so weight-only scales (and the
  pre-`device_flow`-known interval before the first Bookoo settings
  echo arrives) show the core's flow estimate instead of "—".

### 6.2 Wire-up tasks (priority 2)

The Svelte side of the existing core surface. Pure UI work — no
protocol changes.

- **Wire 1 — Settings → Water → Refill threshold control.** Add an
  `StValueChip` row that reads `ui.waterRefillThreshold` (mm) and
  commits via `app.setRefillThreshold(mm)`. Closes the WaterLevels write
  loop in the UI. Doc 14 §3.2 setter exists.
- **Wire 2 — Scale page → auto-stop-mode selector.** Add a row with the
  Bookoo's two modes (Flow-Stop / Cup-Removal), gated on
  `caps.auto_stop`, two-way through `app.setScaleAutoStop(...)`. Reads
  back `ui.scaleAutoStop`. Trivial.
- **Wire 3 — Scale page → "Start timer" button.** Replace the local
  `timerRunning` toggle with `app.startTimer()` / `app.stopTimer()` (+
  optional `resetTimer` on long-press). The core's reactive auto-policy
  already drives the timer on shot start; this exposes manual control.
- **Wire 4 — Brew page → upload progress.** When
  `ui.profileUploadProgress` is non-null, render a small
  "Uploading X… (acks/total)" line in the dashboard header so the
  auto-upload-on-reconnect path has a visible status (`app.svelte.ts:474`
  fires per session). Cheap.
- **Wire 5 — Brew page → loaded-profile shape (dormant but cheap).** If
  `ui.loadedProfileShape` ever becomes non-null again (a future
  firmware that does expose it on Read), show "DE1 has loaded: N
  frames" in the diagnostics. Free given the field already exists.

### 6.3 New data sources to add (priority 3)

Protocol / core changes — small, additive.

- **Add 1 — `MmrRegister::CpuBoardVersion` (`0x800008`) and
  `MachineModel` (`0x80000C`).** Two enum variants + two `address` arm
  entries + two `ALL` entries + a test. ~20 LOC.
- **Add 2 — Connect-time MMR read sweep.** Once Add 1 lands, extend
  `de1.ts:319` from a single `readMmr(FirmwareVersion)` to a small
  read-set (SerialNumber, CpuBoardVersion, MachineModel, GhcInfo,
  HeaterVoltage, FanThreshold, TankTempThreshold,
  CalibrationFlowMultiplier — the registers the legacy reads at
  connect, `de1_comms.tcl:1117-1341`). Each is a separate
  `read_mmr` call (or one batched 4-word read at `0x800008`); replies
  fold into `de1MachineInfo`. Then the dormant `de1MachineInfo` fields
  actually get populated and the Machine diagnostics screen (priority
  4) has data to render.
- **Add 3 — Connect-time `Source::De1ShotSettings` notify + Read +
  `Event::SteamHotWaterSettingsRead`.** Symmetric to the existing
  Write path. Lands a single 9-byte Read at connect; reply decoded by
  the existing `ShotSettings::decode`. Powers the Quick Sheet Steam /
  Hot Water / Espresso-Volume steppers when they get wired.
- **Add 4 — `ShotSample` setpoint fields in `Event::Telemetry`.** Add
  `set_mix_temp` / `set_head_temp` / `set_group_pressure` /
  `set_group_flow` (and probably `frame_number` for parity with the
  setpoint context). Enables a "target vs. measured" overlay on the
  LiveChart.

### 6.4 UI surfaces to design (priority 4)

The deferred docs/10 / docs/11 UI side. Each is its own design + build.

- **Surf 1 — Machine diagnostics screen.** Settings → Machine →
  expanded view that renders the populated `de1MachineInfo` (after Add 2)
  and `de1Calibration` (after a "read calibration" trigger lands —
  three Bookoo-style buttons that issue `readCalibration(target,
  factory?)` per sensor).
- **Surf 2 — Machine-error banner.** A top-of-page banner when
  `ui.machineError` is set. Doc 11 R5.
- **Surf 3 — Idle / "X seconds since last shot" indicator.** Doc 11 R6.
- **Surf 4 — Brew dashboard control surface.** Wire the QuickSheet
  steppers to the matching core setters (Steam / Hot Water →
  `set_steam_hotwater_settings`; Flush →
  `set_flush_flow_rate` / `set_flush_timeout`). Wire the Start / Stop
  button to `request_machine_state(Espresso)` /
  `request_machine_state(Idle)`. Wire profile Switch / Edit to the
  Profiles route. This is the big "brew control" feature, but it
  unblocks docs/14 §1 + §3 + §4 use cases all at once.
- **Surf 5 — Resistance chart series.** Doc 11 R4. Pull `resistance`
  (already in `TelemetrySample`) into LiveChart as an optional series
  gated on the `showPuckResistance` preference (already in settings).

---

## 7. Acceptance — what "done" means

This audit is **done** when:

- §5 is exhaustive — every `Event` variant accounted for; every
  `UiSnapshot` field accounted for; every TODO in the surveyed UI
  components flagged.
- §6.1 bugs have linked fixes ready (or commits) so the next agent can
  land them in one short PR.
- The doc itself passes the `docs/14` style bar: every assertion cites
  a path + line; every "missing" claim is verified against the source.

The READ side of doc 10 is **complete**; the READ side of doc 11
(R1–R6) is **complete** (core + state + types); the UI side of doc 11
is the deferred work in §6.4 and a few §6.3 protocol additions remain.
The most actionable items are §6.1 (one-liner bug fixes), then §6.2
(wire-up tasks that need no protocol changes — they unlock visible value
from existing core surface), then §6.3 (additive protocol work that
populates dormant state fields). §6.4 is design-led and parallel.

---

## 8. Open questions

- **Decide: realign Machine card "Group" on `head_temp`, or relabel
  one of the two readouts?** §5.2 finding 1. The argument for
  `head_temp` (matches Brew, stays meaningful at idle) is strong;
  confirming before changing.
- **Decide: should `activeProfileName` be retired in favour of
  `activeProfileTitle`?** §5.2 finding 3. A small refactor; affects
  the History recording of `profileName`, the Scale page's
  `targetProfileName`, and the Brew header's three-step fallback.
- **Decide: connect-time MMR sweep — read all 8 legacy-equivalent
  registers, or wait for a Diagnostics screen request?** §6.3 Add 2.
  Doing it at connect makes the read side ready when the UI lands;
  doing it on screen-open conserves BLE bandwidth on the connect path.
  Recommend: do it at connect (each read is ~30 ms over BLE; the eight
  reads take well under a second; the connect is already a
  multi-second affair).
- **Decide: should `Source::De1ProfileHeader` / `Event::ProfileHeaderRead`
  / `WriteTarget::De1ProfileHeader` (the read half) be deprecated /
  removed?** docs/16 §6.1 settled the wire reality (the legacy never
  Reads it; the DE1 returns zeros). Today the variants are harmless dead
  code — non-exhaustive enums make removal a breaking ABI change for
  any third-party Rust consumer of `de1-app` (there are none in this
  workspace). Recommend: leave them in; they may yet have a use if a
  future firmware exposes the buffer; the cost is one match arm.
- **Decide: is the core's `ShotSample.sample_time` worth re-introducing
  into `Event::Telemetry`?** §5.3 finding 11. Visualizer-format
  compatibility might want it, but the capture-replay format Crema
  uses (`{t, dir, src, hex}`) carries wall-clock instead. Recommend:
  defer until a Visualizer integration demands it.
