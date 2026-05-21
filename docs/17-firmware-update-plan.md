# 17 — Firmware-update plan

**Status:** planning only — no core changes in this branch.
**Branch:** `plan-firmware-update` (off `main`).
**Companion:** `docs/14-write-actions-audit.md` §6 (codec inventory), `docs/02-ble-protocol.md`
(wire format), `docs/16-profile-upload-plan.md` (the other multi-frame BLE
write — shares the orchestration shape).

## Verdict up front

**Do not ship firmware update in Crema v1.** Carry the codec (already done),
carry the plan (this doc), keep the FFI surface free of firmware names, and
revisit the day a *second* DE1 user lives behind Crema. The justification is in
§5; the rest of the doc describes what the v2 design looks like so v1's choice
is informed, not blind.

---

## 1. Background — what the legacy wizard does

### 1.1 The wizard UX

The legacy Tcl skin's firmware wizard is five pages
(`skins/default/de1_skin_settings.tcl:1157-1209`):

| Page | What the user sees | What the app waits for |
|---|---|---|
| 1/5 | "Turn your DE1 off" | `::de1(device_handle) == 0` — the BLE link dropping. |
| 2/5 | "Turn your DE1 on" — *"It can take one minute to start"* | `::de1(device_handle) != 0`. Then it calls `start_firmware_update`, `disable_de1_reconnect`, and advances to page 3. |
| 3/5 | "Updating … N.N%" + ETA | `currently_erasing_firmware == 0 && currently_updating_firmware == 0`, i.e. the upload-and-verify finishes. The ETA label is in `vars.tcl:3804-3830`; the percentage label is in `vars.tcl:3833-3853`. |
| 4/5 | "Turn your DE1 off — firmware is ready to be applied" | `device_handle == 0`. Schedules a `enable_de1_reconnect` after 120 s and an `app_exit` after 600 s. |
| 5/5 | "Turn your DE1 on" — *"Can take several minutes"* | `device_handle != 0`; the app exits so the user starts it fresh on the post-update DE1. |

Two things to notice: (a) the wizard's first two pages are an *out-of-band*
hard power-cycle — the user pulls the DE1 plug, and the app uses the BLE
disconnection as the page-1 → page-2 trigger. (b) The wizard explicitly closes
the app at the end; the legacy code does not try to reconnect across the
firmware-applying reboot. A v1 Crema PWA can't pull the same trick — the user
can't reasonably "turn the DE1 off and on" from the brew page mid-shot — so any
Crema version has to *replace* the legacy "ask the user to power-cycle" beat
with a state-driven equivalent. Concretely: the DE1 enters
`MachineState::InBootLoader = 19` on its own when the upload is mapped (the
state is already modelled in `core/de1-protocol/src/state.rs`); Crema can
observe that on `cuuid_0E` and move pages on it. **Whether the DE1 actually
emits this state-change notification on `cuuid_0E` before the link drops is the
single open question that gates the v2 implementation** — see §6.

The legacy app also calls `disable_de1_reconnect` (`bluetooth.tcl:3047-3056`)
right before advancing to page 3, so the *expected* mid-update disconnect (page
1 → page 2) is treated as a wizard event, not as a fault. Without that hook
Crema's normal "BLE dropped → try to reconnect" loop would fight the wizard;
the v2 design has to disarm reconnect-on-drop for the lifetime of the upload.

### 1.2 BLE characteristics and commands involved

Four things move on the wire:

1. **`FWMapRequest` (`cuuid_09` / `0xA009`)** — the control channel. The
   7-byte packet (`window_increment u16 | fw_to_erase u8 | fw_to_map u8 |
   first_error u24`, big-endian) is *both* a write *and* a notify: the app
   writes one to ask the DE1 to do something; the DE1 echoes one back on the
   same characteristic with the result. The four uses Crema needs are already
   modelled in `core/de1-protocol/src/firmware.rs`:
   - `FWMapRequest::erase(1)` — "erase slot 1 in preparation for upload".
   - The DE1's erase-finished reply is the same shape with `fw_to_erase == 0`
     (legacy detects this at `de1_comms.tcl:475-478`).
   - `FWMapRequest::request_first_error()` — "tell me the offset of the first
     byte that failed to verify, or `FIRST_ERROR_NONE` if it all verified".
     Legacy decides Updated vs Update-failed on the reply at
     `de1_comms.tcl:484-496`.
   - `FWMapRequest::map(1)` — "make slot 1 active". Legacy does not actually
     call this in the standard flow; the DE1 self-maps on reboot. Kept for
     completeness; v2 should not need to write it.
2. **`WriteToMMR` (`cuuid_06` / `0xA006`)** — the data channel. 20 bytes per
   frame: `0x10 | offset u24 | data[16]`, big-endian offset (the existing
   `firmware::firmware_write_frame` encodes one). Frames are sent strictly in
   offset order, advancing by 16 each time; the DE1 does not ack individual
   frames.
3. **`Version` read (`cuuid_01` / `0xA001`)** — read once before and again
   after; Crema already decodes via `firmware::Version`. The post-update
   `firmware_string()` is the user-visible proof the update stuck.
4. **`StateInfo` notify (`cuuid_0E`)** — Crema already subscribes. Two
   transitions matter: **the pre-flight gate** — refuse to start unless the
   DE1 is in `Sleep` or `Idle` (the legacy comment at `de1_comms.tcl:230`
   *"Tell DE1 to start to go to SLEEP (so it's asleep during firmware
   upgrade)"* makes the same call); and **the "in bootloader" transition**
   — after a successful map the DE1 reboots through
   `MachineState::InBootLoader (19)` then `Init (12)` and back to `Sleep
   (0)`. The first transition is observable today; the second is the open
   question in §6.

### 1.3 The legacy upload loop (the bits that matter)

From `de1_comms.tcl:842-998` (paraphrased; line numbers cited inline):

```
start_firmware_update():                                # de1_comms.tcl:842
  guard:  not already erasing       (line 865)
  guard:  not already updating      (line 870)
  size:   ::de1(firmware_update_size) = [file size [fwfile]]   (line 879)
  packet: arr(FWToErase) = 1, arr(FWToMap) = 1, FirstError* = 0 (lines 888-893)
  state:  currently_erasing_firmware = 1                       (line 900)
  write   FWMapRequest::erase(1) on cuuid_09                    (line 912)
  after 10 s: write_firmware_now                                (line 913)
              # belt-and-braces — runs even if the erase reply was lost

write_firmware_now():                                   # de1_comms.tcl:928
  state:  currently_erasing_firmware = 0
          currently_updating_firmware = 1                       (lines 930-931)
  start_time = clock milliseconds                                (line 932)
  binary = read_binary_file(fwfile())                            (line 935)   # legacy reads disk once
  bytes_uploaded = 0                                             (line 936)
  firmware_upload_next()                                         (line 938)

firmware_upload_next():                                 # de1_comms.tcl:942
  if bytes_uploaded >= size:                                     (line 952)
    save CRC32 of the image to settings(firmware_crc)            (line 953)
    write  FWMapRequest::request_first_error() on cuuid_09       (line 980)
    # the reply lands on FWMapRequest notify and decides Updated / Update failed
  else:                                                          (line 982)
    write  firmware_write_frame(bytes_uploaded, chunk[16]) on cuuid_06  (lines 985-988)
    bytes_uploaded += 16                                         (line 990)
    after 1 firmware_upload_next                                  (line 993)
```

A few details the codec already swallows:

- **The 1-ms inter-frame `after`** is the legacy app's flow-control on Android
  (`de1_comms.tcl:993`); on non-Android it advances synchronously (`line 991`).
  The legacy code does *not* drain frame acks because there are none; it
  relies on the link's BLE backpressure to throttle. On Android this works;
  on the web, Web Bluetooth's GATT-write queue is shallower and 1 ms is too
  aggressive in practice (we already see queue-full errors at burst rates
  above ~100 writes/s in the existing scale-write path).
- **No retry on individual frames**, only on the erase-doesn't-reply 10-s
  watchdog (`de1_comms.tcl:913`). The legacy code's only verify path is "ask
  for the first error after the whole upload"; if a frame *was* lost it shows
  up there as a non-`FIRST_ERROR_NONE` offset.
- **`disable_de1_reconnect`** during the wizard (`bluetooth.tcl:3053-3056`) —
  the app's normal "ble link dropped → try to reconnect" loop is paused, so
  the expected mid-update disconnect (page 1 → page 2) is treated as a wizard
  event, not as a fault. This is the design that lets the wizard expect a
  hard power-cycle.

---

## 2. Proposed state machine

```
                 start_firmware_upload(image, env)
                          │
                          ▼
              ┌────────────────────────┐
              │        Preflight       │  AC-power + machine-state +
              │                        │  link-quality + image checks
              └────────────┬───────────┘
            preflight ok   │   preflight failed
                          ▼               └───────────►  Failed { reason }
              ┌────────────────────────┐
              │           Erase        │  Command emitted:
              │  (FWMapRequest::erase) │  WriteCharacteristic De1FWMapRequest
              └────────────┬───────────┘
                          │  Source::De1FWMapRequest replies with fw_to_erase=0
                          ▼
              ┌────────────────────────┐
              │         Erasing        │  Reply received, erase confirmed
              │   (await ~5–10 s)      │
              └────────────┬───────────┘
                          │  erase-done reply OR 10 s wall clock
                          ▼
              ┌──────────────────────────────┐
              │  Uploading { sent, total }   │ ◄────┐  on_tick() advances 1
              │   (one frame per emit)       │      │  frame at a time, paced
              └────────────┬─────────────────┘ ─────┘  by inter_frame_min
                          │  sent == total
                          ▼
              ┌────────────────────────┐
              │        Verifying       │  Command:
              │ (request_first_error)  │  WriteCharacteristic De1FWMapRequest
              └────────────┬───────────┘  request_first_error()
                          │  Source::De1FWMapRequest reply
                ┌─────────┴─────────┐
                ▼                   ▼
        first_error ==        first_error !=
        FIRST_ERROR_NONE      FIRST_ERROR_NONE
                │                   │
                ▼                   ▼
            Done { … }         Failed { BadVerify { offset } }

   At any point:
   - cancel_firmware_upload() → Cancelled (no further commands emitted)
   - on_link_lost()           → Failed { reason: LinkLost }  (see §3.6)
   - 30 s no progress reply   → Failed { reason: Timeout }
```

### 2.1 What advances each state

| From → To | Trigger | What the core does |
|---|---|---|
| `Idle → Preflight` | `start_firmware_upload(image, env)` call | Run the §3 gates synchronously; if any fail, transition `Idle → Failed{…}` instead and never touch BLE. |
| `Preflight → Erase` | All gates passed | Emit one `WriteCharacteristic{target: De1FWMapRequest, data: FWMapRequest::erase(1).encode()}` and one `Event::FirmwareUpdateStarted{image_bytes, frame_count}`. |
| `Erase → Erasing` | `Source::De1FWMapRequest` notification arrives with `fw_to_erase == 1` (matches legacy `de1_comms.tcl:480-482`) | Pure logging transition — the DE1 acknowledged the erase command. Emit `Event::FirmwarePhaseChanged{phase: Erasing}`. |
| `Erasing → Uploading` | `Source::De1FWMapRequest` reply with `fw_to_erase == 0` (legacy `de1_comms.tcl:475-478`) *or* 10 s wall-clock elapsed without a reply (matches legacy belt-and-braces in `de1_comms.tcl:913`) | Move to `Uploading{frame_index: 0, frame_count}`. Do **not** emit a frame yet — the next `on_tick()` does it. |
| `Uploading → Uploading` | `on_tick(now_ms)` and at least `inter_frame_min` since the last frame | Emit *one* `WriteCharacteristic{target: De1FrameUpload, …}` carrying `firmware_write_frame(sent, &image[sent..sent+16])`. Advance `sent += 16`. Emit `Event::FirmwareProgress{…}` every ~1% to keep the bridge JSON small. |
| `Uploading → Verifying` | `on_tick` with `sent >= total` | Emit `WriteCharacteristic{target: De1FWMapRequest, data: FWMapRequest::request_first_error().encode()}` (mirrors legacy `de1_comms.tcl:980`). |
| `Verifying → Done` | `Source::De1FWMapRequest` reply with `first_error == FIRST_ERROR_NONE` (legacy `de1_comms.tcl:487-490`) | Emit `Event::FirmwareUpdateCompleted{verified: true, first_error: None}`. |
| `Verifying → Failed{BadVerify{offset}}` | `Source::De1FWMapRequest` reply with `first_error != FIRST_ERROR_NONE` and `first_error != FIRST_ERROR_REQUEST` (legacy `de1_comms.tcl:492-494`) | Emit `Event::FirmwareUpdateFailed{reason: BadVerify{offset}}`. |
| `* → Cancelled` | `cancel_firmware_upload()` call | Stop emitting commands; remain `Cancelled` until `reset_firmware_upload()`. |
| `* → Failed{LinkLost}` | Shell calls `on_link_lost()` | See §3.6. |
| `* → Failed{Timeout}` | `on_tick` 30 s after the most recent `FWMapRequest` write with no reply | Matches the legacy app's implicit watchdog (it sat on the same characteristic indefinitely; Crema being sans-IO needs an explicit ceiling). |

### 2.2 Failure paths summarised

| `FirmwareFailureReason` | When | What the user sees |
|---|---|---|
| `PreflightAcPower` | Tablet on battery at start | "Plug the tablet in before updating." |
| `PreflightMachineBusy` | DE1 not in `Sleep` or `Idle` | "Take the machine to Sleep or Idle first." |
| `PreflightLinkDown` | `env.link_connected == false` | "Connect to the DE1 first." |
| `PreflightImageInvalid` | Image empty, too short, or larger than 24-bit offset space | "Firmware file is corrupted." |
| `EraseTimedOut` | No erase reply after the 10 s watchdog *and* no follow-up retry succeeds | "DE1 did not acknowledge the erase. Power-cycle and retry." |
| `BadVerify { offset: u32 }` | DE1's first-error reply pointed at a byte offset | "Verification failed at byte N. Power-cycle and retry." |
| `LinkLost` | BLE link dropped before `Done` | "Connection lost mid-update. Power-cycle and retry from scratch." (see §3.6) |
| `Timeout` | 30 s with no progress | "DE1 stopped responding. Power-cycle and retry." |

All failure variants are terminal: there is no in-place recovery — the shell
calls `reset_firmware_upload()` to get back to `Idle` and then
`start_firmware_upload(image, env)` from the top.

---

## 3. Safeguards

### 3.1 AC-power gate

The tablet must be on mains power. A brick caused by tablet battery-death
during upload is the failure mode this most directly prevents. The shell, not
the core, owns this signal — Web Bluetooth runs on a Chromebook / iPad /
desktop where `navigator.getBattery()` (Web) or `BatteryManager` (Android)
reports `charging: true`. The core takes the boolean as part of
`start_firmware_upload`'s preflight context:

```rust
pub fn start_firmware_upload(
    &mut self,
    image: Vec<u8>,
    env: FirmwareUploadEnv,    // pre-flight context the shell supplies
) -> CoreOutput;
```

where `FirmwareUploadEnv { on_ac_power: bool, link_connected: bool }` is a
small input struct. The core refuses to leave `Preflight` if `!on_ac_power`.
Refusing here, in plain Rust, makes the gate testable without any tablet
present.

### 3.2 Machine-state gate

Allowed start states: `MachineState::Sleep` and `MachineState::Idle`. Refuse
in every other state — `Espresso`, `Steam`, `HotWater`, `HotWaterRinse`,
`SteamRinse`, `Clean`, `Descale`, `ShortCal`, `LongCal`, `SelfTest`, `Refill`,
`AirPurge`, `Busy`, `GoingToSleep`, `Init`, `InBootLoader`, `FatalError`,
`SchedIdle`. The legacy app's implicit policy is the same — the wizard
suggests the user power-cycles the machine, which leaves it in `Sleep` on
wake; the comment at `de1_comms.tcl:230` is explicit about wanting the DE1
asleep during firmware upgrade. The core already tracks `last_state`, so the
gate is a single-condition check at the top of `start_firmware_upload`.

### 3.3 Confirm dialog

The shell, not the core, owns the dialog (the core is sans-IO and has no UI).
The shape we recommend, mirroring the audit's §4.13 heater-voltage
recommendation:

- Modal with title *"Update DE1 firmware?"*
- Body: the firmware string we are about to install (parsed from the file
  header — see §6), the firmware string currently installed
  (`Event::Firmware.firmware_string` cached from the last `Version` read), a
  one-line warning ("Do not interrupt power or BLE during the update"), and
  the word `UPDATE` in monospace next to a text field. The button stays
  disabled until the user types `UPDATE`. Same pattern de1app uses for its
  destructive-action dialogs.
- Confirm button issues `CremaCore::start_firmware_upload(image, env)`.
- Cancel button does nothing.

### 3.4 Lockout — other writes refused during upload

While `phase != Idle && phase != Done && !matches!(phase, Failed{..} |
Cancelled)`, **every other write-emitting `CremaCore::*` method returns
`CoreOutput::default()` and emits one `Event::FirmwareLockoutHit { method:
String }`** so the shell can show a transient toast. The list of affected
methods is the current public-write surface:

- `request_machine_state`
- `set_steam_hotwater_settings`
- `enable_steam_eco_mode`
- (future) `set_refill_threshold`, every `set_*` MMR helper from audit §4,
  `upload_profile`, `write_calibration`, `reset_calibration`,
  `bookoo_start_timer` / `_stop_timer` / `_reset_timer`.

Read-only methods (`read_mmr`, `read_calibration`, `read_factory_calibration`,
`scale_capabilities`, `builtin_profiles_json`) pass through — they cannot
disturb the upload.

The reactive auto-policies (steam eco-mode tick rewrites, audit §7.2 Bookoo
auto-timer) also gate on the lockout flag.

**This is the only safeguard that has to live inside the core.** Every other
gate is a preflight check that the v2 implementation can add at upload time.
The lockout, by contrast, has to be threaded through *every other write
method* — which means it should be installed **as a stub in v1** even though
the rest of the firmware update is v2:

```rust
impl CremaCore {
    /// `true` while a firmware upload is in progress. v1 always returns
    /// `false`; v2 returns `true` for the `Erase..Verifying` phases. Every
    /// public write method calls this and bails early when it returns `true`.
    pub(crate) fn firmware_locks_writes(&self) -> bool {
        false
    }
}
```

Each audited v1 write — §3.2 `set_refill_threshold`, every §4 MMR setter, §2
`upload_profile`, §7.2 Bookoo timer commands — gets one line:

```rust
if self.firmware_locks_writes() {
    let mut out = CoreOutput::default();
    out.events.push(Event::FirmwareLockoutHit {
        method: "set_refill_threshold".into(),
    });
    return out;
}
```

That one-line guard is cheap to add when each write lands; retrofitting nine
of them at v2 time is not. Carrying the stub now is the v1 investment that
keeps v2 cheap.

### 3.5 No-screen-saver

Shell-owned. The web shell calls `navigator.wakeLock.request('screen')` for
the lifetime of `phase != Idle`; Android holds a partial wake lock. The core
emits the lifecycle events the shell needs:

- `Event::FirmwareUpdateStarted` — shell takes the wake lock.
- `Event::FirmwareUpdateCompleted{..}` *or* a `Failed`/`Cancelled` transition
  via `FirmwarePhaseChanged` — shell releases it.

The legacy app omits this on the wizard — the user is staring at the screen
anyway — but the legacy `delay_screen_saver` (`gui.tcl:851`, called on every
shot-related tick) shows the same need; the wizard inherits screen-on
behaviour through the active dialog. Crema's web shell doesn't get that for
free, so the wake lock is explicit.

### 3.6 BLE-drop strategy — abort, not resume

**Recommendation: abort. On any BLE disconnect during `phase != Idle &&
phase != Done`, transition to `Failed{LinkLost}` and require the user to
restart from `Erase`.** Do not implement resume.

The reasoning is three layers:

1. **The legacy app does not resume.** Its disconnect handler runs
   `bluetooth.tcl`'s reconnect attempt; if it succeeds mid-upload, the next
   `firmware_upload_next` call still writes the next sequential offset, but
   `currently_updating_firmware` is now reset and the binary buffer has been
   `unset` on completion (`de1_comms.tcl:967`). There is no documented
   recovery path. We would be inventing a behaviour the upstream firmware
   has not been tested against.
2. **The DE1's erased flash is half-written.** Even if we *could* fast-forward
   to the right offset, partial-image firmware can boot into an unusable
   state. The verify phase catches it if we reach it; if we don't reach it
   because we crashed mid-upload, we don't even know whether the partial
   image will boot.
3. **The complexity cost is large for one user.** Resume requires: tracking
   `sent` across the disconnect, surviving a reconnect event, distinguishing
   between "deliberate end-of-wizard disconnect" and "tablet went out of
   range", and a "resume offset N?" prompt. Each of these is its own bug
   surface, and there is exactly one Crema user. The expected number of
   mid-update disconnects per year is roughly zero if the AC-power gate and
   the same-room policy hold; building resume to handle "roughly zero"
   events is the opposite of focus.

Concretely: the shell's `disconnect` handler calls
`CremaCore::on_link_lost()`; the core transitions to `Failed{LinkLost}` and
the shell shows "Connection lost mid-update — power-cycle the DE1 and start
over". The user re-runs the wizard end-to-end.

The one observable cost of "abort" is that the DE1 sits with a half-written
firmware slot after a drop. The legacy comments at `de1_comms.tcl:475-482`
make clear the DE1's erase-in-progress state is recoverable: a fresh
`FWMapRequest::erase(1)` re-erases cleanly. Our restart path does exactly
that.

---

## 4. Crema-side API shape (sketch)

```rust
//! core/de1-app/src/firmware_upload.rs (new module)

use std::time::Duration;
use typeshare::typeshare;
use serde::{Deserialize, Serialize};

/// Pre-flight context the shell supplies once, at the start of an upload.
/// The shell owns these signals; the core decides whether they're acceptable.
#[derive(Debug, Clone, Copy)]
pub struct FirmwareUploadEnv {
    /// True iff the tablet is currently on mains power (not running on its
    /// own battery).
    pub on_ac_power: bool,
    /// True iff the BLE link is currently up. Lets the core fail fast if the
    /// shell calls `start_firmware_upload` while disconnected.
    pub link_connected: bool,
}

/// Where the firmware upload is in its lifecycle. Mirrors §2's state machine.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "phase", content = "content")]
#[non_exhaustive]
pub enum FirmwarePhase {
    Idle,
    Preflight,
    Erase,
    Erasing,
    /// Uploading frame `frame_index` of `frame_count` (zero-based).
    Uploading { frame_index: u32, frame_count: u32 },
    Verifying,
    Done,
    Cancelled,
    Failed { reason: FirmwareFailureReason },
}

#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[non_exhaustive]
pub enum FirmwareFailureReason {
    PreflightAcPower,
    PreflightMachineBusy,
    PreflightLinkDown,
    PreflightImageInvalid,
    EraseTimedOut,
    /// Byte offset the DE1 said failed to verify.
    BadVerify { offset: u32 },
    LinkLost,
    Timeout,
}

/// Internal driver state for the upload, owned by `CremaCore` while a
/// firmware update is in progress. Reset to `None` between updates.
#[derive(Debug)]
pub(crate) struct FirmwareUpload {
    image: Vec<u8>,
    phase: FirmwarePhase,
    /// Bytes successfully *emitted* to the shell (not necessarily acked).
    bytes_uploaded: u32,
    /// Wall clock of the most recent frame emit; gates pacing against
    /// `inter_frame_min`.
    last_frame_at: Option<Duration>,
    /// Wall clock of the most recent `FWMapRequest` write; gates the 30-s
    /// no-reply timeout.
    last_map_request_at: Option<Duration>,
    /// Wall clock the upload started; powers the ETA in `FirmwareProgress`.
    started_at: Option<Duration>,
    /// Minimum gap between frame writes. Set by the shell at start; defaults
    /// to 4 ms on web, 1 ms on Android — see §6.
    inter_frame_min: Duration,
}
```

New methods on `CremaCore` (additive; nothing existing changes):

```rust
impl CremaCore {
    /// Start an upload. Runs the §3 gates, emits the erase write if all pass.
    /// Returns the `CoreOutput` carrying that write — or a `Failed`-state
    /// `Event::FirmwareUpdateFailed` if a gate failed (no commands then).
    pub fn start_firmware_upload(
        &mut self,
        image: Vec<u8>,
        env: FirmwareUploadEnv,
    ) -> CoreOutput;

    /// Move to `Cancelled`. Any further `on_tick` returns an empty
    /// `CoreOutput`; `reset_firmware_upload` returns to `Idle`.
    ///
    /// Note: cancel emits **no BLE bytes** — it only flips the core's phase.
    /// That is why this is a dedicated method, not a variant of any
    /// hypothetical `MachineRequest` enum: such a variant would suggest
    /// symmetry with `request_machine_state` that the wire does not have.
    /// See §4.1.
    pub fn cancel_firmware_upload(&mut self) -> CoreOutput;

    /// The shell tells the core the BLE link dropped. In the v1/v2 abort
    /// design this transitions to `Failed{LinkLost}`; a future resume design
    /// would consume the same hook differently.
    pub fn on_link_lost(&mut self) -> CoreOutput;

    /// Clear `Failed`/`Cancelled`/`Done` back to `Idle` so the next
    /// `start_firmware_upload` can run.
    pub fn reset_firmware_upload(&mut self) -> CoreOutput;

    /// Where we are. `None` when no upload has been attempted this session.
    pub fn firmware_phase(&self) -> Option<FirmwarePhase>;

    /// Whether the lockout is currently active. v1 stub returns `false`;
    /// v2 returns `true` for the `Erase..Verifying` phases.
    pub(crate) fn firmware_locks_writes(&self) -> bool;
}
```

`on_tick(now_ms)` already exists for the other monitors and is reused as the
upload pump — one `firmware_write_frame` per tick, paced by
`inter_frame_min`. See §6 for the recommended values.

New event variants (extend `Event`, all `#[non_exhaustive]`-safe):

```rust
#[non_exhaustive]
pub enum Event {
    // … existing variants …

    /// Pre-flight gates passed; the erase write is in this CoreOutput.
    FirmwareUpdateStarted {
        /// Image size, bytes.
        image_bytes: u32,
        /// Number of 16-byte frames to send.
        frame_count: u32,
    },

    /// Phase transition the UI may want to redraw on. Fires on every
    /// state-machine edge in §2 except `Uploading → Uploading`, which is
    /// covered by `FirmwareProgress`.
    FirmwarePhaseChanged {
        phase: FirmwarePhase,
    },

    /// Per-frame progress while in `Uploading`. The core emits this every
    /// ~1% so the bridge JSON stays small (a 32 kB image at 16 B/frame is
    /// 2048 frames; one event per frame is too much chatter).
    FirmwareProgress {
        bytes_uploaded: u32,
        bytes_total: u32,
        /// Estimated time remaining, milliseconds. `0` early in the upload
        /// when there isn't enough history.
        eta_ms: u32,
    },

    /// Terminal success.
    FirmwareUpdateCompleted {
        /// Always `true` here (failure goes through `FirmwareUpdateFailed`);
        /// kept as a field for symmetry with the legacy `Updated`/`Update
        /// failed` label.
        verified: bool,
        /// `None` on a clean verify; `Some(offset)` if the DE1 reported a
        /// bad-byte offset (we treat this as failure, but the offset is
        /// surfaced for diagnostics).
        first_error: Option<u32>,
    },

    /// Terminal failure. Carries the reason for the UI.
    FirmwareUpdateFailed {
        reason: FirmwareFailureReason,
    },

    /// A non-firmware write was refused because an upload is in progress.
    /// Lets the shell toast "Other commands are locked during firmware
    /// update". `method` is a stable label like `"request_machine_state"`.
    FirmwareLockoutHit {
        method: String,
    },
}
```

New `WriteTarget` + `Source` variants (extend the existing enums; both are
already `#[non_exhaustive]`):

```rust
pub enum WriteTarget {
    // … existing …
    /// The DE1 `FWMapRequest` characteristic (`cuuid_09` / `A009`) — erase,
    /// map, and verify control. The DE1 also notifies on this; subscribe
    /// only during an upload.
    De1FWMapRequest,
    /// The DE1 `WriteToMMR` characteristic (`cuuid_06` / `A006`) — the
    /// firmware-frame data channel. Distinct from `De1MmrRequest`
    /// (`cuuid_05`) which is for register reads.
    ///
    /// Shares the underlying UUID with audit §4's planned `De1MmrWrite`
    /// — both write to `cuuid_06`. The shell maps them to the same UUID;
    /// the codec encodes what each is for. If audit §4 lands first (it
    /// will, in v1), this slot is `De1MmrWrite` and the v2 firmware path
    /// reuses it; if §6 lands first (it won't), the name reverses. The v2
    /// commit reuses whichever name v1 picked rather than introducing a
    /// duplicate.
    De1FrameUpload,
}

pub enum Source {
    // … existing …
    /// The DE1 `FWMapRequest` notify (`cuuid_09`) — replies to erase, map,
    /// and verify writes. Subscribed only during a firmware upload.
    De1FWMapRequest,
}
```

### 4.1 Why cancel is a method, not a `MachineRequest` variant

The v1 audit (`docs/14-write-actions-audit.md` §6) sketched a
`cancel_firmware_upload()` method, not a request-enum variant. We keep that
shape for one concrete reason: **cancel emits no BLE bytes**. It flips the
core's `FirmwarePhase` to `Cancelled` and stops emitting commands; it does
not write to `RequestedState`, does not touch `cuuid_09`, does not touch
`cuuid_06`. Burying cancel inside any `MachineRequest`-style enum would
suggest a write-symmetry the wire doesn't have, and the bridge layer would
have to special-case it ("this variant doesn't actually generate a
command"). A dedicated method has none of that asymmetry. The same logic
covers `reset_firmware_upload` and `on_link_lost`: all three are pure
state-machine pokes from the shell, none of them ever cross the BLE seam.

If a `MachineRequest` enum *does* land later for ergonomics on the wasm /
UniFFI bridges (it would be a quality-of-life win regardless), the cancel
still belongs on a dedicated method; the enum should carry only the
variants that map to actual `RequestedState` bytes.

### 4.2 Frame pacing

Audit §6.2 left the per-platform inter-frame delay as an open question; the
recommendation here is **4 ms on web, 1 ms on Android**, both expressed as a
`Duration` field on `FirmwareUpload.inter_frame_min` that the shell sets at
start:

```rust
// Web shell (TS sketch):
core.start_firmware_upload(image, {
  on_ac_power: true,
  link_connected: true,
  inter_frame_min_ms: 4,
});

// Android shell (Kotlin sketch):
core.startFirmwareUpload(image, FirmwareUploadEnv(
  onAcPower = true,
  linkConnected = true,
  interFrameMinMs = 1,
))
```

Reasoning:

- **Web Bluetooth's GATT-write queue is shallower than Android's GATT
  layer.** We already observe queue-full errors at ~100 writes/s on the
  scale-write path on Chromium; 1 ms (≈1000 writes/s) is well above that.
  4 ms (≈250 writes/s) leaves headroom even when the Web Bluetooth
  implementation backs off under contention.
- **Wall-clock impact is acceptable either way.** A 32 kB image at 16-byte
  frames is 2048 writes; at 4 ms that's 8 s, at 1 ms it's 2 s. The user is
  already watching a "do not interrupt" dialog; another 6 s is invisible.
- **Android matches legacy.** Legacy uses `after 1` (`de1_comms.tcl:993`)
  on Android. Mirroring that keeps the v2 implementation directly
  comparable to a known-working baseline. Diverging only on web, where
  legacy doesn't run, is the lower-risk choice.

The choice belongs at the shell so it can adapt per platform (and per
firmware-frame-size, if the DE1 ever raises the cap), but the field lives in
the core's upload struct so the pacing logic sits next to the state machine.

---

## 5. Recommendation — v1 or v2?

### 5.1 Arguments for v1

- The codec is ready. Audit §6 lays it out; the only missing wiring is the
  state machine in this doc plus four event variants and two write targets.
- The legacy wizard is one of the more user-visible features; "I can update
  my DE1 firmware without leaving Crema" is a respectable v1 boast.
- Firmware updates happen rarely (a handful per year per machine); even an
  imperfect flow is "good enough" if it is gated behind enough warnings.

### 5.2 Arguments against v1, in order of weight

1. **There is no documented recovery from a brick.** The DE1's only
   power-on user input is the front panel; if a partial-image write leaves
   the firmware in a state where the bootloader can't load it, the recovery
   path is "ship the machine to Decent". The user is the only consumer; a
   bricked DE1 takes Crema with it. The marginal value of a v1 firmware
   wizard does not justify even a 1%-per-update brick risk.
2. **The "expected mid-update disconnect" pattern of the legacy app
   doesn't translate.** Legacy uses BLE drop as the "user has power-cycled
   the DE1" signal (`de1_skin_settings.tcl:1164`); the wizard explicitly
   expects two disconnects. Web Bluetooth surfaces those disconnects to
   the page only when the user permits it and they look identical to "the
   tablet wandered out of range". v1 will get the UX wrong on the first
   real attempt with no second user to discover the bug before it bricks
   something. The `MachineState::InBootLoader` substitute (§1.1, §6) is
   *plausible* but unverified, and verifying it requires the same
   real-DE1 spike that we don't have on the v1 critical path.
3. **Web Bluetooth firmware-upload timing is untested.** Audit §6 open
   question: 1 ms inter-frame is fine on Android, untested on the Web
   stack. §4.2 above settles the recommendation but not the *empirical*
   answer. The cheapest way to validate is a Web Bluetooth spike against a
   real DE1, and that spike is not on the v1 critical path. Shipping
   without it means the first attempt is the test, on a DE1 that does not
   have an in-place rollback.
4. **The lockout interacts with every other write we'll add.** §3.4
   requires every public write method to check the firmware phase. That's
   easy to thread *now* if we don't ship the rest, by writing the gate
   into `CremaCore` as a stub method that the v2 implementation flips
   real. If we wait until v2 to add the gate, we have to revisit every
   audited write at that time. The stub now buys cheap insurance; the
   *implementation* in v2 stays small.
5. **Quantitatively**: the rest of the audit-queue (§3.2 water threshold,
   §4 MMR setters, §2 profile upload, §7.2 Bookoo timer) is roughly 500
   LOC + tests, ships immediately useful functionality on every shot, and
   has well-understood failure modes (a wrong MMR write at worst sends an
   ill-tempered packet the DE1 ignores or clamps). Firmware update is
   roughly 600–800 LOC + tests, ships rarely-useful functionality, and
   has a worst-case failure of a $3000 paperweight. **The per-LOC value
   of a v1 firmware wizard is one of the worst on the audit table**, even
   without weighting by the brick risk.

### 5.3 Verdict

**v2.** Ship the codec (done), keep this plan in `docs/`, carry the
`firmware_locks_writes()` stub in `CremaCore` from the v1 commit that lands
audit §4 (so every future write that respects the lockout has a one-line
guard pre-installed), and revisit when there is a second Crema user or a
known-good Web Bluetooth firmware-upload spike on a non-production DE1.

If something forces the decision the other way — say, Decent ships a
firmware change that's required for a feature you want — the path back is
clean: this doc is the design.

---

## 6. Open questions / things to verify before v2 implementation

### The single open question that gates v2

**Does the DE1 emit `MachineState::InBootLoader = 19` on `cuuid_0E` before
the link drops at the end of an upload?** Crema's "you can stop staring at
the machine, the update applied" UX hinges on this. Legacy doesn't watch
for it (it relies on the BLE drop instead, which Crema can't reliably
interpret on Web Bluetooth — see §5.2). The codec already recognises the
state (`core/de1-protocol/src/state.rs`), so the answer is one captured
notification away. **Verifying this on a real DE1 is the prerequisite for
starting v2 implementation.** If the answer is "no", the v2 UX has to
fall back to legacy-style "wait for the link to drop, then come back",
and the wizard's post-upload pages need a different design.

### Other things to verify

- **Web Bluetooth inter-frame delay**. §4.2 recommends 4 ms on web, 1 ms on
  Android. A 32 kB firmware image at 16-byte frames is 2048 writes; at 4 ms
  that's 8 s, at 1 ms it's 2 s. Either is acceptable wall-clock; the
  question is which spec the Web Bluetooth implementation tolerates without
  queue-full errors. The same spike that answers the InBootLoader question
  can answer this one.
- **Firmware-file header parsing**. The legacy `parse_firmware_file_header`
  (referenced from `de1_comms.tcl:814` via `fwfile`) reads metadata the
  wizard never displays. The Crema v2 confirm dialog wants the "you're
  about to install FW 1.x.y" string — that means we need to either parse
  the header in Rust or read it from the file metadata the user uploads.
  Recommend Rust-side parsing in `core/de1-protocol/src/firmware.rs`, shape
  mirroring the existing `Version::decode`.
- **`firmware_crc` skip-if-same**. Legacy writes the CRC32 of the file to
  `::settings(firmware_crc)` on successful upload (`de1_comms.tcl:953`)
  and uses it next time to decide "no update necessary"
  (`vars.tcl:3833-3843`). Crema's settings storage is shell-owned (web
  `localStorage`, Android `DataStore`); the same pattern works — store the
  CRC after a successful upload, read it before a new attempt to
  short-circuit "you're trying to install the firmware that's already
  there". Lives in the shell; the core surfaces the CRC as part of
  `FirmwareUpdateCompleted` if asked.
- **`disable_de1_reconnect` equivalent in Crema**. Legacy disarms the
  reconnect loop (`bluetooth.tcl:3053-3056`) for the wizard. Crema's shell
  needs the same hook: a `core.firmware_phase()` poll lets the shell
  decide not to attempt auto-reconnect while `phase` is non-`Idle`. No
  core change needed — the shell already controls reconnect attempts.
- **`Map` write necessity**. Legacy never explicitly writes
  `FWMapRequest::map(1)`; the DE1 self-maps on reboot. v2 should not add a
  map write unless the InBootLoader-observability spike turns up
  evidence it's needed. Keep `FWMapRequest::map` in the codec (it's a
  cheap method already there) and leave it unused in the state machine.
