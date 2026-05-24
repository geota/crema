# 39 — Firmware-version gates: survey + plan

Status: audit (2026-05-24, branch `firmware-audit`). Updated alongside
docs/02 §6, docs/22, docs/27.

This doc surveys every feature in the two upstream sources (legacy de1app TCL
and reaprime) that conditionally changes wire behavior based on the **DE1
firmware build number** (`MmrRegister::FirmwareVersion`, MMR `0x800010`).
It then cross-checks Crema and lists what Crema is missing, with proposed
fixes and severity.

The build number on the wire (e.g. **1352** = "DE1 firmware v1352" in
`74cacdcd`) is the same integer that
`firmware_info::LATEST_KNOWN_FIRMWARE_BUILD` tracks; Crema surfaces it as
`UiSnapshot.de1FirmwareVersion` and persists it in ReplayMeta, but does **not
gate any feature** on it today.

---

## Survey

### Legacy TCL (`de1app/de1plus`)

| Source | Threshold | Behavior on out-of-version firmware |
|---|---|---|
| `bluetooth.tcl:2988-3001 firmware_has_fan_sleep_bug` | build `≤ 1174` *or* SHA `7C24F200` | On idle/sleep the app must rewrite `fan_threshold` to `0`, then restore on wake. Older firmware turns the fan on during sleep when threshold > 0 (Decent bug). |
| `bluetooth.tcl:2554, de1_comms.tcl:373` heater-voltage probe | build `≥ 1142` | Above this build the app actively prompts the user to set `heater_voltage` when the MMR-read returns 0. Below this build, the prompt is suppressed (heater voltage may legitimately read 0 on older fw). |
| `machine.tcl:1090 start_schedIdle` | build `≥ 1293` | Uses `MachineState::SchedIdle` (0x15) for scheduled-wake. Below this, falls back to the regular `Idle` (0x02) command. |
| Firmware-update flow (`vars.tcl:3781-3837`) | n/a — local CRC + version comparison | Compares the bundled `.dat` firmware build against the installed build to decide whether an update is available. Cosmetic only. |

### reaprime (`lib/src/models/device/impl/de1`)

reaprime **does not gate any feature on firmware version**. It reads
`MMRItem.cpuFirmwareBuild` (`unified_de1.dart:189`) and surfaces it in the
generic `MachineInfo`, but no code path in `lib/` branches on the value.
The single arithmetic the build number drives is the firmware-update
status comparison; the rest of reaprime's API is built against a recent
firmware floor (>= ~1293 implicit, since e.g. flush-temp at MMR `0x803844`
is post-flush-feature firmware).

### Crema (today)

`firmware_info.rs` tracks `LATEST_KNOWN_FIRMWARE_BUILD = 1352` and
synthesizes a `FirmwareUpdateStatus`. The build is surfaced to the shell
and recorded in replay metadata. **No setter or state command gates on
the build today.**

---

## Gap table

| TCL gate | reaprime gate | Crema gate | Severity |
|---|---|---|---|
| `firmware_has_fan_sleep_bug` — fan threshold rewrite on idle/sleep below build 1174 | none | none | **MEDIUM** (silent — fans-during-sleep on legacy firmware; only affects fw <= 1174) |
| Heater-voltage prompt only above build 1142 | none | none | **LOW** (cosmetic — Crema would just show the prompt unconditionally; firmware accepts the write) |
| `SchedIdle` (0x15) only above build 1293; fall back to `Idle` (0x02) below | none | none | **HIGH** (sending SchedIdle to a fw < 1293 may fail to set state at all — the firmware accepts only state ids it knows; scheduled-wake would silently no-op) |

Note: "TCL" and "reaprime" sometimes disagree on whether to gate at all.
Following the guidance in this audit's brief ("defer to reaprime when
legacy + reaprime disagree on a missing-capability mitigation"), the
fan-sleep + heater-voltage rows are intentional non-gates in reaprime;
they're work-arounds Decent has since rolled into the firmware itself.
The `SchedIdle` row is the only one where Crema is at risk *today* — but
Crema does not yet send `SchedIdle` from any code path, so the gap is
latent.

---

## Proposed fixes

### Gate 1 — `SchedIdle` (HIGH, when wired)

Wrap `request_machine_state(SchedIdle)` with a check that
`firmware_version >= 1293`; fall back to `Idle` otherwise. The
`CremaCore` already tracks the installed version through the MMR read
path. Shape:

```rust
// In `request_machine_state` (lib.rs:~2540):
let effective = if state == MachineState::SchedIdle
    && self.observed_firmware_version().is_some_and(|v| v < 1293)
{
    MachineState::Idle
} else {
    state
};
```

Test: `set_firmware_version_override` test-only hook + an assertion that
`request_machine_state(SchedIdle)` against fw 1200 writes the `Idle` byte.
Not implemented in this audit — Crema does not yet call `SchedIdle`. Add
this guard at the same time the shell exposes scheduled wake.

### Gate 2 — fan-sleep workaround (MEDIUM)

Implement at the shell layer (the wake/sleep transition is shell-driven).
When the shell sends `Sleep`, if `firmware_version <= 1174`, follow with
`set_fan_threshold(0)`; on `Idle` re-emit `set_fan_threshold(user_value)`.
The core already exposes `set_fan_threshold(u8)`. Tracking under
docs/27 alongside other settings-restore behavior.

### Gate 3 — heater-voltage prompt floor (LOW)

Pure UI: surface the "Heater voltage is unknown, please set it" warning
banner only when `de1FirmwareVersion >= 1142` *and* the last observed
`HeaterVoltage` MMR-read was `0`. Below 1142 the read may legitimately be
0 (firmware never wrote it). UI-only — no core change.

---

## Out of scope

- BLE API-version gating (`mmr_available` checks BLE API `≥ 4` in TCL).
  Crema treats MMR as always-present; that's correct against all
  firmware Crema would meet.
- `firmware_sha` matching (TCL `vars.tcl:3879`). Crema has no concept of
  a per-build SHA — it uses the integer build number alone, matching
  reaprime.
- Firmware-update upload flow (separate from version-gated wire-behavior
  changes). See `docs/17-firmware-update-plan.md`.

---

## References

- `de1app/de1plus/de1_comms.tcl:373, 1274` — fw-version read + prompt gate
- `de1app/de1plus/bluetooth.tcl:2988-3001` — `firmware_has_fan_sleep_bug`
- `de1app/de1plus/machine.tcl:1086-1095` — `SchedIdle` floor
- `reaprime/lib/src/models/device/impl/de1/unified_de1/unified_de1.dart:189` — fw-build read; no gates
- `core/de1-app/src/firmware_info.rs` — Crema's installed-build tracking
- `core/de1-protocol/src/state.rs:37` — `MachineState::SchedIdle = 21`
