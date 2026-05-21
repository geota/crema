# 16 — Profile Upload Implementation Plan

**Status:** planning only — no code changes in this branch
**Branch:** `plan-profile-upload` (off `main`; the implementation branch
`write-actions-impl` is untouched)
**Companion:** `docs/14-write-actions-audit.md` §2, `docs/02-ble-protocol.md`
§5, `docs/13-deferred-refactors.md`, `docs/08-ffi-and-web-scope.md`

This document is the implementation spec for row §2 of the write-actions
audit — uploading an espresso profile to the DE1 via `HeaderWrite` and N×
`FrameWrite`. The codec is already in place; the bridge plumbing, the
sans-IO ack state machine, the new `WriteTarget`/`Source` variants, the
`CremaCore::upload_profile` method and the FFI/wasm wrapper are not. The
sections below specify each in enough detail that an implementer can write
the Rust without re-reading the legacy Tcl.

---

## 1. Purpose & scope

### What "profile upload" means

Pushing one espresso profile (header + ordered frames + extensions + tail)
to the DE1 over BLE so that the *next* `RequestedState=Espresso` will run
that profile. The user-visible trigger is selecting a profile in the
profile picker; the legacy app also re-pushes the active profile on
connect, after firmware-update reboot, and reactively from
`save_settings_to_de1` whenever `::settings` changes (`de1_comms.tcl:1539`).

A profile upload writes:

1. one 5-byte `ShotHeader` to `HeaderWrite` (cuuid_0F, UUID `0000A00F-…`),
2. one 8-byte `ShotFrame` per step to `FrameWrite` (cuuid_10, UUID `0000A010-…`),
3. one 8-byte `ExtensionFrame` per step that has an advanced limiter, to
   the same `FrameWrite` characteristic,
4. one 8-byte `ShotTail` to `FrameWrite`, with `FrameToWrite = frame_count`.

The DE1 echoes each `FrameWrite` back as a notification on `FrameWrite`
(see §6) so a host can confirm "yes, that frame was applied".

### Out of scope

- **Profile JSON I/O / storage.** Shell-owned (web `localStorage`,
  Android Room). Crema is sans-IO. The upload API takes a parsed
  `Profile` value (or its JSON serialisation across the FFI seam).
- **The companion tank-temperature MMR write** (`set_tank_temperature_threshold`,
  `de1_comms.tcl:1478,1060`) that the legacy app issues alongside the
  upload for "advanced" profiles. This is a §4 MMR write — see the audit
  row 4.2; the profile-upload command set does **not** include the MMR
  write here, so the orchestrator stays narrow. The shell may chain a
  `core.write_mmr(TankTempThreshold, …)` *after* upload completes if the
  profile is advanced; that policy lives outside this doc.
- **Firmware-update timing knobs.** Different write class, different
  characteristic, different state machine — see audit §6 and the
  separate firmware-update plan when it lands.
- **Active-profile state on the DE1.** The DE1 has no "current profile
  name" register; the in-app concept of "active profile" is shell-owned.
  Upload commits a new profile to RAM; from that point, the next shot
  uses it. There is no rollback once a frame is acked.

---

## 2. Legacy reference walk-through

Read-only reference at `/Users/adrianmaceiras/code/de1app/de1plus/`. Every
file:line citation below is exact at the read-only snapshot.

### 2.1 Entry point: `de1_send_shot_frames`

**File:** `de1app/de1plus/de1_comms.tcl:1439-1486`.

```tcl
proc de1_send_shot_frames { {override {}} } {
    # this is to track which frames are ACKed as having been successfully sent
    unset -nocomplain ::de1(shot_frames_sent)

    set parts [de1_packed_shot_wrapper $override]
    set header [lindex $parts 0]
    # header is the packed 5-byte ShotHeader (see §2.4 below)

    # purge previously queued upload writes (idempotent if user repeats)
    remove_matching_ble_queue_entries {^Espresso header:}
    remove_matching_ble_queue_entries {^Espresso frame #}

    # enqueue the header write
    userdata_append "Espresso header: …" [list de1_comm write "HeaderWrite" $header] 1
    #                                                          ^^^^^^^^^^^                ^
    #                                                          cuuid_0F                   vital=1

    # enqueue one FrameWrite per packed frame: normal frames first, then
    # extension frames, then the tail — exactly the order the packer (§2.3)
    # produces.
    set cnt 0
    foreach packed_frame [lindex $parts 1] {
        incr cnt
        userdata_append "Espresso frame #$cnt: …" \
            [list de1_comm write "FrameWrite" $packed_frame] 1
    }

    # advanced-profile-only: write the tank temp threshold (NOT a wire frame)
    if {$::settings(settings_profile_type) == "settings_2c"} {
        set_tank_temperature_threshold $::settings(tank_desired_water_temperature)
    } else {
        set_tank_temperature_threshold 0
    }

    # one final sentinel: when the BLE queue reaches this, fire the ACK check.
    userdata_append "Confirm that all shot frames were correctly sent" \
        [list confirm_de1_send_shot_frames_worked [lindex $parts 1]] 1
}
```

**Reading:**

- One header write + N frame writes + 1 sentinel (a Tcl callback, not a
  BLE write) are enqueued **back-to-back, synchronously, into the BLE
  command stack** (`::de1(cmdstack)`).
- All writes are tagged `vital=1` — the queue's retry-on-failure path
  retries vital writes (`de1_comms.tcl:144-159`).
- The header/frame writes are *not* awaited by `de1_send_shot_frames`
  itself; it returns immediately after enqueuing. The queue runner
  (`run_next_userdata_cmd`, see §2.2) drives them serially.

### 2.2 The BLE queue: `userdata_append` / `run_next_userdata_cmd`

**File:** `de1app/de1plus/de1_comms.tcl:38-179`.

`userdata_append` appends `[list comment cmd vital]` to `::de1(cmdstack)`
and calls `run_next_userdata_cmd`. The runner:

- On **Android**, runs **one write at a time** — `set ::de1(wrote) 1`
  before issuing, then waits for the BLE stack to call back into the
  ack handler (which calls `set ::de1(wrote) 0` and `run_next_userdata_cmd`).
- On non-Android, eagerly pumps the queue — multiple writes can be in
  flight, but only one per characteristic at a time in practice because
  BLE stacks serialise per-characteristic writes-with-response.
- After each issued write, schedules an `after 1000 run_next_userdata_cmd`
  as a heartbeat — so even if a write callback drops silently, the queue
  eventually retries on the *next* BLE event.

Two pieces of nuance matter for Crema:

1. **Per-frame ACK is what unblocks the next write on Android.** The
   write to `FrameWrite` is *write-with-response*; the BLE stack notifies
   the handler in `bluetooth.tcl:2824-2838` (`access eq "w"`,
   `cuuid eq $::de1(cuuid_10)`) when the DE1 ACKs. That ack handler is
   where `::de1(shot_frames_sent)` grows.
2. **No explicit per-frame delay.** The legacy code relies on the BLE
   stack's natural back-pressure (the next write blocks until the
   previous is acked). It does *not* sleep between frames.

### 2.3 The packer: `de1_packed_shot_wrapper` and `de1_packed_shot`

**File:** `de1app/de1plus/binary.tcl:864-1032`.

`de1_packed_shot` walks `profile(advanced_shot)` (a list of Tcl dicts, one
per step). For each step it builds a frame array. If the step has a
`max_flow_or_pressure` it also builds an extension-frame array (the index
is shifted by 32 — `binary.tcl:972`). After the loop it builds a tail
array (`FrameToWrite = cnt`, `MaxTotalVolume = 0` — line 1000-1006).

`make_chunked_packed_shot_sample` (`binary.tcl:839-860`) then packs:

1. the header (5 bytes — `spec_shotdescheader`),
2. *all* normal frames in order,
3. *all* extension frames in order,
4. the tail.

It returns `[list $packed_header $packed_frames]` — header on the side,
everything else as a flat list of 8-byte packets.

`de1_packed_shot_wrapper` branches on `::settings(settings_profile_type)`
(`pressure_to_advanced_list` / `flow_to_advanced_list` /
`settings_to_advanced_list` / `pressure_to_advanced_list` for `cool`) but
all branches end at `de1_packed_shot`. The branch selection is *legacy
profile-type fan-out* — Crema's `Profile` domain model has already
collapsed this; `Profile::assemble()` is the single packer.

### 2.4 Wire format (annotated)

#### `ShotHeader` (5 bytes, → cuuid_0F)

Source: `binary.tcl:786-795` (`spec_shotdescheader`).

| Offset | Bytes | Field | Type | Notes |
|--------|-------|-------|------|-------|
| 0 | 1 | `HeaderV` | U8 | Constant `1` |
| 1 | 1 | `NumberOfFrames` | U8 | Total step count (≤ 32) |
| 2 | 1 | `NumberOfPreinfuseFrames` | U8 | Leading frames counted as preinfusion |
| 3 | 1 | `MinimumPressure` | U8P4 | Min bar in flow-priority frames |
| 4 | 1 | `MaximumFlow` | U8P4 | Max mL/s in pressure-priority frames |

#### `ShotFrame` — normal (8 bytes, → cuuid_10)

Source: `binary.tcl:797-808` (`spec_shotframe`), `binary.tcl:572-588`
(flag bits), `binary.tcl:906-985` (assembly).

| Offset | Bytes | Field | Type | Notes |
|--------|-------|-------|------|-------|
| 0 | 1 | `FrameToWrite` | U8 | 0-based index, **must be < 32** |
| 1 | 1 | `Flag` | U8 | bitmask (see below) |
| 2 | 1 | `SetVal` | U8P4 | Target pressure (bar) or flow (mL/s) — per `CtrlF` |
| 3 | 1 | `Temp` | U8P1 | Target temperature, 0.5 °C steps |
| 4 | 1 | `FrameLen` | F8_1_7 | Frame duration, seconds (custom format) |
| 5 | 1 | `TriggerVal` | U8P4 | Early-exit compare threshold (per `DC_CompF`) |
| 6-7 | 2 | `MaxVol` | U10P0 (big-endian, bit 10 = enabled) | Per-frame volume cap, 0–1023 mL |

Flag bits (`enum T_E_FrameFlags`):

| Bit | Name | Meaning |
|-----|------|---------|
| 0x01 | `CtrlF` | Flow-priority (else pressure) |
| 0x02 | `DoCompare` | Enable early-exit compare |
| 0x04 | `DC_GT` | Compare direction: `>` (else `<`) |
| 0x08 | `DC_CompF` | Compare against flow (else pressure) |
| 0x10 | `TMixTemp` | Target mix temp (else basket) |
| 0x20 | `Interpolate` | Smooth-ramp (else jump) |
| 0x40 | `IgnoreLimit` | Ignore header's min-press / max-flow |

The legacy `de1_packed_shot` sets `IgnoreLimit` unconditionally
(`binary.tcl:914`) — Crema's domain `ProfileStep::to_shot_frame` already
mirrors that (`de1-domain::profile.rs:128`).

#### `ExtensionFrame` (8 bytes, → cuuid_10)

Source: `binary.tcl:810-822` (`spec_extshotframe`).

| Offset | Bytes | Field | Type | Notes |
|--------|-------|-------|------|-------|
| 0 | 1 | `FrameToWrite` | U8 | `index + 32` (the offset is how the receiver demuxes) |
| 1 | 1 | `MaxFlowOrPressure` | U8P4 | The advanced limit (mL/s or bar) |
| 2 | 1 | `MaxFoPRange` | U8P4 | Tolerance band |
| 3-7 | 5 | `Pad1..5` | U8 | Zero |

#### `ShotTail` (8 bytes, → cuuid_10)

Source: `binary.tcl:824-837` (`spec_shottail`).

| Offset | Bytes | Field | Type | Notes |
|--------|-------|-------|------|-------|
| 0 | 1 | `FrameToWrite` | U8 | **Equals frame_count** — the de-facto terminator marker |
| 1-2 | 2 | `MaxTotalVolume` | U10P0 BE | Whole-shot volume cap |
| 3-7 | 5 | `Pad1..5` | U8 | Zero |

#### Upload order

`make_chunked_packed_shot_sample` returns:

```
header
frame[0], frame[1], …, frame[N-1],         ← all normal frames
ext_frame[i0], ext_frame[i1], …,           ← only for steps with a limiter
tail                                       ← FrameToWrite = N
```

This is the order packets hit `cuuid_10`. Crema's
`AssembledProfile::frame_packets` (`de1-domain::profile.rs:255-261`)
already produces them in exactly this order.

### 2.5 Per-frame ACK: legacy receive path

**File:** `de1app/de1plus/bluetooth.tcl:2824-2838`.

```tcl
} elseif {$access eq "w"} {
    set ::de1(wrote) 0
    run_next_userdata_cmd
    …
    } elseif {$cuuid eq $::de1(cuuid_10)} {
        parse_binary_shotframe $value arr3
        ::bt::msg -INFO "ACK shot frame written to DE1: [array get arr3] ($data_for_log)"
        # keep track of what frames were acked as sent, so we can later
        # make sure all were indeed acked
        lappend ::de1(shot_frames_sent) [array get arr3]
    }
```

Key reading:

- The ACK arrives **on the same characteristic** that was written
  (cuuid_10 / `FrameWrite`), as an ATT *write response* — Tcl surfaces it
  to the `bluetooth.tcl` handler exactly like a notification.
- The legacy parses it with `parse_binary_shotframe` — **the same
  decoder used for the write** (`binary.tcl:673-701`). It picks out
  `FrameToWrite` to identify which frame the DE1 echoed.
- `parse_binary_shotframe` dispatches by `FrameToWrite >= 32` to
  `spec_extshotframe` — extension-frame ACKs are decoded with their own
  spec (`binary.tcl:687-693`).
- The header's ACK is NOT tracked (the legacy app doesn't track
  `::de1(shot_frames_sent)` for header writes). Same for the tail — its
  `FrameToWrite == frame_count` ACK is just lumped into
  `::de1(shot_frames_sent)`.

### 2.6 The watchdog: `confirm_de1_send_shot_frames_worked`

**File:** `de1app/de1plus/de1_comms.tcl:1488-1537`.

```tcl
proc confirm_de1_send_shot_frames_worked {parts} {
    # Walk ::de1(shot_frames_sent) checking each entry's FrameToWrite ==
    # expected sequential index 0, 1, 2, …
    set success 1
    set num 0
    foreach frame_sent [ifexists ::de1(shot_frames_sent)] {
        array set this_frame_array $frame_sent
        if {$num != [ifexists this_frame_array(FrameToWrite)]} {
            set success 0; break
        }
        incr num
    }
    if {$success == 1} {
        # Count must match the number of frames sent.
        if {[llength [ifexists ::de1(shot_frames_sent)]] != [llength $parts]} {
            set success 0
        }
    }
    if {$success != 1} {
        ::comms::msg -NOTICE "… shot frames were not successfully sent, trying again"
        #after 500 de1_send_shot_frames     ← retry is COMMENTED OUT
    } …
    unset -nocomplain ::de1(shot_frames_sent)
}
```

Reading:

- The check fires at the *end* of the BLE queue (the sentinel callback
  enqueued in §2.1) — so by the time it runs, every write that's going
  to ack has had a chance to ack.
- The success condition is: every ack's `FrameToWrite` matched the
  expected sequential index, *and* the total ack count equals
  `llength $parts` (i.e. `frame_count + extension_count + 1` — every
  frame written to cuuid_10, including extensions and tail).
- **The retry call is commented out** (`#after 500 de1_send_shot_frames`,
  line 1524). The legacy app logs failure and gives up. It does **not**
  in fact retry. The audit's note that the legacy "retries on a 500 ms
  timer" is wrong — it once did, but the retry line has been disabled at
  least since this branch was cut.

### 2.7 Re-read after upload?

`de1app/de1plus/de1_comms.tcl:1668,1673`:

```tcl
userdata_append "read shot header" [list de1_comm read "HeaderWrite"] 1
userdata_append "read shot frame"  [list de1_comm read "FrameWrite"] 1
```

These are **debug-only** procs (`read_de1_shot_header` and
`read_de1_shot_frame`) — they trigger when the user opens a debug screen,
not as part of upload. Crema does not need them.

### 2.8 Characteristic UUIDs

`de1app/de1plus/machine.tcl:69-70`:

```
cuuid_0F = 0000A00F-0000-1000-8000-00805F9B34FB    HeaderWrite
cuuid_10 = 0000A010-0000-1000-8000-00805F9B34FB    FrameWrite
```

Both are read/write/notify in the DE1's GATT table. The legacy app
**does not subscribe** to cuuid_0F at all — the header write is fire-and-forget.
It **does** receive cuuid_10 acks (as ATT write responses), which the
BLE-stack delivers to the same callback path as notifications.

### 2.9 What is **not** in the upload flow

- **No `RequestedState` write.** Upload does not start a shot; the user
  starts the shot via the brew page's normal Start button.
- **No state precondition check.** The legacy app pushes the profile
  whether the machine is Idle or in Espresso (it removes
  `^Espresso header:` / `^Espresso frame #` queue entries first, so a
  repeated select replaces in-flight writes — `de1_comms.tcl:1458-1459`).
  Mid-shot upload is technically permitted by the legacy code; the DE1
  firmware may or may not honour it before the *next* shot. We treat
  mid-shot upload as a soft-error case (see §8).
- **No active-profile name write.** The DE1 does not store a profile
  name; the legacy app's "current profile" string is shell-only.
- **No ACK collation for the header.** Only frame ACKs are tracked.

---

## 3. Current Crema scaffolding

### 3.1 Already in place

| Symbol | Path | Notes |
|--------|------|-------|
| `ShotHeader`, `ShotFrame`, `ExtensionFrame`, `ShotTail` | `core/de1-protocol/src/profile.rs` | Full `encode` + `decode` round-trip, tested. |
| `FrameFlags` | same | Round-trips through `to_byte` / `from_byte`. |
| `SHOT_HEADER_LEN = 5`, `SHOT_FRAME_LEN = 8` | same | Wire-length constants. |
| `is_extension_frame(&[u8]) -> bool` | same | `FrameToWrite >= 32`. |
| Fixed-point codecs (`u8p4`, `u8p1`, `u10p0`, `f8_1_7`, …) | `core/de1-protocol/src/fixed_point.rs` | Saturate vs. legacy wrap — see the module's doc-comment for the audited deviation. |
| `Profile`, `ProfileStep`, `Limiter`, `ExitCondition`, `Transition`, `Pump`, `TempSensor`, `ExitMetric`, `Compare` | `core/de1-domain/src/profile.rs` | The domain model. |
| `Profile::assemble() -> Result<AssembledProfile, DomainError>` | same, line 190 | The legacy `de1_packed_shot` → typed-Rust translation. |
| `AssembledProfile::header_packet()` / `frame_packets()` | same, line 247-262 | Produces packets in upload order. |
| `DomainError::NoSteps` / `DomainError::TooManySteps` | `core/de1-domain/src/error.rs` | Validation errors `assemble` surfaces. |
| `CremaCore::builtin_profiles_json` | `core/de1-app/src/lib.rs:202` | Returns the built-in corpus as a JSON array — the shell's source of seed profiles. |

### 3.2 Missing — the work this plan specifies

| Symbol | Where | One-line role |
|--------|-------|---------------|
| `Source::De1FrameAck` | `core/de1-app/src/event.rs` (enum `Source`) | cuuid_10 ACK arrives here. |
| `WriteTarget::De1ProfileHeader` | `core/de1-app/src/event.rs` (enum `WriteTarget`) | cuuid_0F write target. |
| `WriteTarget::De1ProfileFrame` | same | cuuid_10 write target (frames, extension frames, tail — all use the same characteristic). |
| `Event::ProfileUploadStarted` | `core/de1-app/src/event.rs` (enum `Event`) | Emitted at the start of an upload (frame count known). |
| `Event::ProfileUploadProgress { frame: u8, total: u8 }` | same | Emitted after each frame ACK. |
| `Event::ProfileUploadCompleted` | same | Emitted after the final (tail) ACK matches. |
| `Event::ProfileUploadFailed { reason: ProfileUploadFailure }` | same | Emitted on validation / unexpected-ack / disconnect failures. |
| `ProfileUploadFailure` | `core/de1-app/src/event.rs` (new enum, `#[typeshare]`, `#[non_exhaustive]`) | Tagged reason — `Empty`, `TooManySteps{count}`, `UnexpectedAck{expected, got}`, `WrongFrameCount{expected, got}`, `AckTimeout`, `Aborted`, `DisconnectedMidUpload`. |
| `AppError::ProfileUpload(DomainError)` | `core/de1-app/src/error.rs` (enum `AppError`) | Bubbles up assemble-time errors. |
| `ProfileUpload` (in-progress state) | `core/de1-app/src/lib.rs` (new private struct or `enum`) | Tracks which frame indices are queued, which are ACKed. |
| `CremaCore::upload_profile(&mut self, profile: &Profile, now: Duration) -> Result<CoreOutput, AppError>` | `core/de1-app/src/lib.rs` | The new public method. |
| `CremaCore::cancel_profile_upload(&mut self) -> CoreOutput` | same | Abort an in-progress upload; emits `ProfileUploadFailed { reason: Aborted }`. |
| `CremaCore::profile_upload_in_progress(&self) -> bool` | same | Status query for the shell (useful for the brew page's disable-while-uploading logic). |
| `CremaCore::handle_profile_frame_ack(data: &[u8], now: Duration, out: &mut CoreOutput)` | same | Private helper, dispatched from `on_notification` for `Source::De1FrameAck`. |
| `NotificationSource::De1FrameAck` | `core/de1-wasm/src/lib.rs`, `core/de1-ffi/src/lib.rs` | The mirror enums. |
| `CremaBridge::upload_profile(profile_json: String) -> String` | `core/de1-wasm/src/lib.rs`, `core/de1-ffi/src/lib.rs` | The bridge methods. |
| `CremaBridge::cancel_profile_upload() -> String` | same | Bridge for cancel. |
| `CremaBridge::profile_upload_in_progress() -> bool` | same | Bridge for status. |
| `web/src/lib/core/index.ts` `CremaCore` interface methods | web shell | `uploadProfile(p: Profile): Promise<CoreOutput>`, `cancelProfileUpload(): Promise<CoreOutput>`, `profileUploadInProgress(): Promise<boolean>`. |

### 3.3 What stays unchanged

- The `Profile::assemble` API and the codec are correct — no changes
  there. The domain test coverage is already strong (limiter →
  extension frame, exit condition → compare flags, mix-sensor flag, max
  steps).
- The fixed-point codec deviation from the legacy is already audited
  (see `fixed_point.rs` module doc) — no action.
- The existing `WriteTarget` / `Source` discriminants used today
  (RequestedState, ShotSettings, MmrRequest, Calibration, WaterLevels,
  State, ShotSample, etc.) are correct; this plan only **appends**.

---

## 4. Proposed Rust API

### 4.1 `de1-protocol` — no new symbols required

The codec is complete. The only consideration is whether to add a
`ProfileFrameAck` decode helper that picks `FrameToWrite` out of the
8-byte payload without committing to frame-vs-extension dispatch. The
analysis: `ShotFrame::decode(data)` already returns the `index`
(after-byte-0) for normal frames; for extension frames it would mis-read
the value, but we only need the **first byte** to identify the
acknowledged frame. Recommendation:

```rust
// core/de1-protocol/src/profile.rs (appended)

/// The acknowledged frame index from a `FrameWrite` echo notification.
///
/// The DE1 echoes each frame write back on the same characteristic;
/// the first byte of that 8-byte echo is `FrameToWrite`. Values `<
/// EXTENSION_FRAME_OFFSET` (32) identify normal-frame and tail acks
/// (the tail's `FrameToWrite == frame_count` is a normal-range value);
/// values `≥ 32` identify extension-frame acks (`index = byte - 32`).
///
/// Returns `None` for an empty slice. Trailing bytes are ignored.
pub fn ack_frame_byte(data: &[u8]) -> Option<u8> {
    data.first().copied()
}
```

This is the minimum surface — sufficient for the sans-IO ack matcher in
§5. Keeping `ShotFrame::decode` / `ExtensionFrame::decode` as the rich
decoders (used by debug tooling) and adding the tiny `ack_frame_byte`
helper for the hot path matches the existing module idiom.

### 4.2 `de1-app` — new public methods

```rust
// core/de1-app/src/lib.rs (appended to impl CremaCore)
use de1_domain::Profile;
use std::time::Duration;

impl CremaCore {
    /// Start uploading `profile` to the DE1.
    ///
    /// Assembles the profile into the protocol packets (errors on `NoSteps`
    /// / `TooManySteps`), arms the in-progress state machine, and returns a
    /// `CoreOutput` whose commands are the *full* sequence of writes —
    /// header, every normal frame, every extension frame, tail — in upload
    /// order. The shell submits them serially; the orchestrator advances
    /// its expected-ack cursor as `Source::De1FrameAck` notifications
    /// arrive (see §5).
    ///
    /// `now` stamps the upload start so the ack-timeout watchdog can fire
    /// from `on_tick` (§5.3). A second `upload_profile` while one is in
    /// progress emits a `ProfileUploadFailed { reason: Aborted }` for the
    /// prior upload, then starts the new one. To start fresh on
    /// connect/reset, call `reset()` first.
    ///
    /// # Errors
    ///
    /// [`AppError::ProfileUpload`] wrapping a [`DomainError`] if the
    /// profile fails validation.
    pub fn upload_profile(
        &mut self,
        profile: &Profile,
        now: Duration,
    ) -> Result<CoreOutput, AppError>;

    /// Cancel an in-progress profile upload. If no upload is in flight,
    /// returns an empty `CoreOutput`. Emits
    /// `ProfileUploadFailed { reason: Aborted }`.
    pub fn cancel_profile_upload(&mut self) -> CoreOutput;

    /// `true` from the moment `upload_profile` returns until the tail ACK
    /// is received (or the upload fails / is cancelled).
    pub fn profile_upload_in_progress(&self) -> bool;
}
```

#### Idiomatic convention adherence

- **`Duration` at the API boundary, not `u64 ms`.** The existing
  `on_notification` / `on_tick` take `now_ms: u64`; the bridge layer
  converts at the seam. The internal `upload_profile` takes
  `Duration`, consistent with the `arm_auto_stop` / `enable_steam_eco_mode`
  pattern (those take `now_ms: u64` only because they're called by the
  bridge today; new methods take `Duration` and the bridge converts).
  Cross-reference: `core/de1-app/src/lib.rs:53-57` (`ms_to_duration`).
- **`Profile` is the typed value, not raw JSON.** JSON crosses the FFI;
  the core takes the parsed value.
- **`Result<CoreOutput, AppError>` only when the input can be rejected.**
  Validation (`NoSteps`, `TooManySteps`) is the only rejection path;
  every other failure (ack mismatch, timeout, disconnect) becomes an
  `Event::ProfileUploadFailed`, not a `Result::Err`. This matches the
  rest of the core: `set_steam_hotwater_settings`, `request_machine_state`
  etc. return `CoreOutput` infallibly because they can't fail.
- **`#[non_exhaustive]` on `ProfileUploadFailure`.**
- **No `_ms` / `_s` suffixes on internal field names.** `Duration`
  carries the unit.

### 4.3 New `Event` / `WriteTarget` / `Source` variants

All in `core/de1-app/src/event.rs`. All `#[non_exhaustive]` already, so
appending is non-breaking.

```rust
// enum Source ─ append:
    /// The DE1 `FrameWrite` characteristic (`cuuid_10`) echo — the DE1
    /// echoes each profile-frame write back here as a write-response /
    /// notification, carrying the frame the firmware applied.
    De1FrameAck,

// enum WriteTarget ─ append:
    /// The DE1 `HeaderWrite` characteristic (`cuuid_0F`) — the 5-byte
    /// profile header is written here at the start of a profile upload.
    De1ProfileHeader,
    /// The DE1 `FrameWrite` characteristic (`cuuid_10`) — each 8-byte
    /// profile frame (normal, extension, tail) is written here in
    /// upload order during a profile upload.
    De1ProfileFrame,

// enum Event ─ append:
    /// A profile upload has begun. Carries the number of writes the
    /// shell will see — header + frames + extensions + tail.
    ProfileUploadStarted {
        /// Number of normal frames the profile has.
        frame_count: u8,
        /// Number of extension frames (one per step with a limiter).
        extension_frame_count: u8,
    },
    /// One profile frame was acknowledged by the DE1.
    ProfileUploadProgress {
        /// The frame index just acknowledged (for an extension-frame ack,
        /// this is the index of the *step* the extension extends — i.e.
        /// raw `FrameToWrite` byte minus 32). The tail ack carries
        /// `frame == frame_count`.
        frame: u8,
        /// Whether the just-acked frame was an extension frame.
        extension: bool,
        /// Total number of acks expected (frames + extensions + tail).
        total_acks: u16,
        /// Number of acks received so far (1-based, including this one).
        acks_received: u16,
    },
    /// The whole profile uploaded successfully — all frames acknowledged
    /// in order, plus the tail.
    ProfileUploadCompleted,
    /// The profile upload failed. The core has discarded its in-progress
    /// state; the shell may retry by calling `upload_profile` again.
    ProfileUploadFailed {
        /// Why the upload failed.
        reason: ProfileUploadFailure,
    },
```

```rust
// new enum (same file):
/// Why a profile upload failed.
///
/// `#[non_exhaustive]` so additional categories (e.g. firmware reports
/// "shot in progress") can be added without breaking the FFI surface.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
#[non_exhaustive]
pub enum ProfileUploadFailure {
    /// The profile had no steps (`DomainError::NoSteps`).
    Empty,
    /// The profile had more than 32 steps.
    TooManySteps {
        /// How many steps the rejected profile had.
        count: u32,
    },
    /// A frame ack arrived for a frame number the orchestrator did not
    /// expect.
    UnexpectedAck {
        /// The `FrameToWrite` byte the core expected next, raw (≥32 for
        /// extension acks, == frame_count for the tail).
        expected: u8,
        /// The `FrameToWrite` byte the ack actually carried.
        got: u8,
    },
    /// The upload ended with the wrong number of acks.
    WrongFrameCount {
        /// How many acks the core expected in total.
        expected: u16,
        /// How many acks it received.
        got: u16,
    },
    /// No ack arrived within the timeout (§8).
    AckTimeout {
        /// The `FrameToWrite` byte the core was waiting for.
        awaiting: u8,
    },
    /// The shell called `cancel_profile_upload` mid-upload.
    Aborted,
    /// The session was reset / disconnect mid-upload.
    DisconnectedMidUpload,
}
```

### 4.4 `Command` and `CoreOutput` — no new variants

`Command::WriteCharacteristic { target, data }` already covers both
header (with `target = De1ProfileHeader`) and frames (with
`target = De1ProfileFrame`). No new variants in `enum Command`.

### 4.5 Bridge methods

```rust
// core/de1-wasm/src/lib.rs ─ NotificationSource append:
    /// The DE1 `FrameWrite` characteristic — profile-frame acks.
    De1FrameAck,

// From impl: append the mapping arm.

// CremaBridge ─ append:

    /// Start uploading a profile. `profile_json` is the JSON-encoded
    /// `de1_domain::Profile` (the same shape `builtin_profiles_json`
    /// produces and the `Profile` TS interface in `$lib/core` mirrors).
    /// Returns a JSON-encoded `CoreOutput`; on validation failure the
    /// output's `events` contains a `ProfileUploadFailed` and `commands`
    /// is empty.
    ///
    /// The shell awaits the JSON, submits every `commands` entry in
    /// order, and routes `cuuid_10` notifications as `De1FrameAck` —
    /// the core does the rest.
    pub fn upload_profile(&mut self, profile_json: String, now_ms: f64) -> String;

    /// Cancel an in-progress profile upload.
    pub fn cancel_profile_upload(&mut self) -> String;

    /// `true` from `upload_profile` until the tail ack / a failure.
    pub fn profile_upload_in_progress(&self) -> bool;
```

The FFI bridge mirrors verbatim with `String` / `u64 now_ms` / `bool`,
following the `de1-ffi/src/lib.rs` idiom (see the `set_steam_hotwater_settings`
twin for the JSON-string-input parallel — that one takes a uniffi Record
since the shape is small and stable; the `Profile` shape is too rich for
that, so a JSON string is the right seam, matching `builtin_profiles_json`).

### 4.6 Web shell — `web/src/lib/core/index.ts`

Three new methods on the `CremaCore` interface, plus the
`'De1FrameAck'` literal in the `NotificationSource` union, plus a
`ProfileUploadFailure` TS union mirroring the Rust enum.

The web `Profile` interface is hand-vendored in `index.ts:94-121` (see
the `Profile is not a #[typeshare] type` doc-comment). It serialises
exactly the way the Rust `Profile` deserialises — so
`JSON.stringify(profile)` is the right argument to `uploadProfile`. No
typeshare regen is needed unless we decide to add `#[typeshare]` to
`Profile` (out of scope here; the existing convention is documented and
deliberate). The `ProfileUploadFailure` enum is added to the
`#[typeshare]` set and lands in the generated `crema-core.ts`
automatically.

---

## 5. State machine

### 5.1 The data the core holds during an upload

```rust
// core/de1-app/src/lib.rs (added field on CremaCore)

#[derive(Debug)]
struct ProfileUpload {
    /// Total normal frames.
    frame_count: u8,
    /// Indices (0-based) of normal frames that have extension frames,
    /// in the order they appear in the upload sequence (sorted by step
    /// index, like `AssembledProfile::extension_frames`).
    extension_indices: Vec<u8>,
    /// Total acks expected: `frame_count + extension_indices.len() + 1`
    /// (the +1 is the tail).
    total_acks: u16,
    /// Acks received so far.
    acks_received: u16,
    /// The `FrameToWrite` byte the next ack should carry. Encoded raw:
    /// 0..frame_count for normal frames, 32+index for extension frames,
    /// frame_count for the tail. Computed from `acks_received` and
    /// `extension_indices`.
    expected_ack: u8,
    /// `now` of the most recent ack — anchors the ack-timeout watchdog
    /// in `on_tick`. Set to the upload start for the first ack.
    last_progress: Duration,
}
```

The orchestrator does **not** queue / sequence the BLE writes — that's
the shell's job, exactly as today. The orchestrator only tracks acks:
which it expected, which arrived, when the latest one came in.

### 5.2 Diagram

```mermaid
stateDiagram-v2
    [*] --> Idle

    Idle --> Uploading: upload_profile(profile)\n→ emit ProfileUploadStarted\n→ emit commands [header, frame*, ext*, tail]
    Idle --> Idle: upload_profile validation failure\n→ Result::Err\n→ no state change

    Uploading --> Uploading: De1FrameAck (expected)\n→ emit ProfileUploadProgress\n→ advance expected_ack
    Uploading --> Idle: De1FrameAck final tail (expected)\n→ emit ProfileUploadCompleted
    Uploading --> Idle: De1FrameAck (unexpected FrameToWrite)\n→ emit ProfileUploadFailed{UnexpectedAck}
    Uploading --> Idle: cancel_profile_upload()\n→ emit ProfileUploadFailed{Aborted}
    Uploading --> Idle: on_tick after timeout\n→ emit ProfileUploadFailed{AckTimeout}
    Uploading --> Idle: reset()\n→ emit ProfileUploadFailed{DisconnectedMidUpload}
    Uploading --> Idle: upload_profile() again\n→ emit ProfileUploadFailed{Aborted}\n→ then re-enter Uploading
```

### 5.3 Ack-timeout watchdog

The legacy app has no per-frame timeout (it relies on the BLE queue's
`vital=1` retry, which itself relies on the BLE stack reporting a write
failure). Crema is sans-IO — the shell pushes ack notifications in;
the core has no way to know a write was silently dropped except via a
timeout.

**Recommendation:** **5-second timeout from the most recent ack** (or
from `upload_profile`'s `now` for the first ack). Rationale:

- A 24-frame profile with single-step extensions is 24 + 24 + 1 = 49
  frame writes. At typical BLE 7.5 ms connection intervals plus DE1
  firmware processing, a real upload takes around 1–2 seconds end-to-end.
  A captured upload would pin the number tightly; until then, 5 s gives
  a generous 100x margin.
- Web Bluetooth has been observed to stall under back-pressure for up
  to ~1 s (`docs/02-ble-protocol.md`); 5 s clears that with margin.
- The shell can always cancel earlier (e.g. a connection-lost listener).

`on_tick(now_ms)` checks:

```rust
if let Some(upload) = &self.profile_upload {
    if now.saturating_sub(upload.last_progress) >= PROFILE_UPLOAD_ACK_TIMEOUT {
        let awaiting = upload.expected_ack;
        self.profile_upload = None;
        out.events.push(Event::ProfileUploadFailed {
            reason: ProfileUploadFailure::AckTimeout { awaiting },
        });
    }
}
```

with `const PROFILE_UPLOAD_ACK_TIMEOUT: Duration = Duration::from_secs(5);`.

### 5.4 Ack dispatch logic (pseudocode)

```rust
fn handle_profile_frame_ack(&mut self, data: &[u8], now: Duration, out: &mut CoreOutput) {
    let Some(byte) = profile::ack_frame_byte(data) else { return; };
    let Some(upload) = self.profile_upload.as_mut() else {
        // No upload in flight; the cuuid_10 notify is just a left-over.
        return;
    };

    if byte != upload.expected_ack {
        let expected = upload.expected_ack;
        self.profile_upload = None;
        out.events.push(Event::ProfileUploadFailed {
            reason: ProfileUploadFailure::UnexpectedAck { expected, got: byte },
        });
        return;
    }

    upload.acks_received += 1;
    upload.last_progress = now;

    let extension = byte >= EXTENSION_FRAME_OFFSET;
    let frame_index = if extension { byte - EXTENSION_FRAME_OFFSET } else { byte };
    out.events.push(Event::ProfileUploadProgress {
        frame: frame_index,
        extension,
        total_acks: upload.total_acks,
        acks_received: upload.acks_received,
    });

    if upload.acks_received == upload.total_acks {
        // Final ack must be the tail (FrameToWrite == frame_count, < 32).
        // Already implicit in the expected_ack sequence — no extra check needed.
        self.profile_upload = None;
        out.events.push(Event::ProfileUploadCompleted);
    } else {
        upload.expected_ack = next_expected_ack(upload);
    }
}
```

`next_expected_ack` walks the upload sequence: `0, 1, …, frame_count - 1,
32 + ext0, 32 + ext1, …, frame_count` and returns the value at index
`acks_received`. Implemented as a small private helper that doesn't
require materialising the full sequence (it's `O(extension_indices.len())`
in the worst case, which is `≤ 32`).

### 5.5 Connect-time considerations

A `reset()` clears any in-progress upload (and emits
`DisconnectedMidUpload`). The shell calls `reset()` on a DE1 disconnect,
so reconnecting starts in `Idle` automatically. Source dispatch for
`Source::De1FrameAck` when `self.profile_upload` is `None` silently
drops the data — this guards against late acks from a prior session.

---

## 6. FrameWrite ack format — the audit open question

**Question (audit row 2.1):** what is the format of the `cuuid_10`
echo / notification that confirms a `FrameWrite` was applied?

**Best answer from the legacy parser:** the DE1 echoes the frame back
**verbatim** — same 8 bytes the host wrote, decoded by the same
`parse_binary_shotframe` spec (`binary.tcl:673-701`):

- For a normal-frame write, the echo is an 8-byte `ShotFrame` with
  `FrameToWrite < 32`.
- For an extension-frame write, the echo is an 8-byte payload starting
  with `FrameToWrite ≥ 32`; the dispatcher in `parse_binary_shotframe`
  switches to `spec_extshotframe` for these.
- For the tail write, the echo is an 8-byte payload with
  `FrameToWrite == frame_count` (which is `≤ 32`, so the dispatcher
  treats it as a normal frame — but the legacy collector doesn't care
  about the per-frame fields, only `FrameToWrite`).

For Crema's purposes, we only need byte 0 (`FrameToWrite`) — the
`ack_frame_byte` helper in §4.1. We do **not** validate the rest of the
ack payload against the bytes we wrote: that would catch a hardware bug,
not anything Crema can act on, and the legacy app doesn't bother either.

**One real-hardware capture is recommended before committing the
implementation.** Specifically:

1. The byte exact format of a tail ack (does the DE1 echo it as 8 bytes,
   or send a 0/short notify on success?).
2. Whether the DE1 emits an "error" notification on a malformed frame
   write, or simply doesn't ack.
3. Whether there are inter-frame timing constraints we can't see from
   the Tcl (the BLE stack hides them).

### How to capture this

Crema already has a recording infrastructure — the IndexedDB-backed
rolling capture buffer (`web/src/lib/capture/recorder.ts`).
**Existing path** records: `DE1_STATE`, `DE1_SHOT_SAMPLE`,
`DE1_WATER_LEVELS`, `SCALE_WEIGHT`, `SCALE_FF12`. **Missing:**
`DE1_FRAME_ACK` (cuuid_10), and as a bonus, `DE1_HEADER_ACK` (cuuid_0F)
to confirm whether the header echoes too.

Capture procedure (when the implementation lands):

1. Add `cuuid_10` (and optionally cuuid_0F) to the web BLE-layer
   `setupDe1Notifications` subscription list in
   `web/src/lib/ble/de1.ts`. Route each notify into the recorder under
   the new src `DE1_FRAME_ACK` (and `DE1_HEADER_ACK`).
2. Add `DE1_FRAME_ACK` to `sourceFromName` in
   `web/src/lib/replay/capture.ts` and `source_from_name` in
   `core/de1-app/examples/replay.rs`, mapping to `'De1FrameAck'` /
   `Source::De1FrameAck`.
3. Open the Advanced → Replay page (the existing UX for downloading
   the buffer). Switch profiles. Capture the next ~10 seconds.
   Download the JSONL.
4. Inspect the `DE1_FRAME_ACK` lines: every one should be 8 bytes,
   with byte 0 cycling 0, 1, 2, … extension-frame indices, then the
   tail's `frame_count` value.

This is **the gating action item before the implementation PR merges**.
The audit row §2 already flagged "Worth one BLE capture against a real
DE1 before implementing §2"; the capture/replay infrastructure is
already in place, so the lift is small (~30 LOC in three files plus a
hands-on profile-switch on real hardware).

---

## 7. Test plan

### 7.1 `de1-protocol` — already strong, gaps to fill

The existing tests (`profile.rs:268-438`) cover:

- `FrameFlags`: round-trip through every byte (0..=0x7F), default
  clear, individual-bit decoding.
- `ShotHeader`: encode-to-expected-bytes, decode/encode round-trip,
  reject short.
- `ShotFrame`: encode-to-expected-bytes (a non-trivial fixture
  exercising every field), decode/encode round-trip, reject short.
- `ExtensionFrame`: encode adds +32 offset, decode/encode round-trip.
- `ShotTail`: decode/encode round-trip.
- `is_extension_frame`: discriminates by byte 0 ≥ 32.

**Gaps to add (small, high-value):**

| Test | Why |
|------|-----|
| `ShotFrame::encode` for **every flag combination** (or at least every flag bit in isolation) → assert the right byte 1. | The flag-byte construction is the densest hand-written part of the codec; one test per bit is cheap insurance. |
| `ExtensionFrame::encode` for `index == 0` and `index == 31` (boundary). | Catches an off-by-one in the +32 offset. |
| `ShotTail::encode` with `frame_count == 32` (max). | Frame count is `u8`; 32 is the legal max; tail's `FrameToWrite == 32` must encode (not be flagged as an extension). |
| `ack_frame_byte` (new helper): `None` for `[]`; `Some(0)` for a 1-byte slice; `Some(byte0)` for an 8-byte slice. | The new helper. |
| Legacy-byte fixture: feed a captured-from-Tcl `de1_packed_shot` output bytes (computed once from the legacy `binary.tcl` packer for a representative builtin profile) and assert Crema's `assemble().frame_packets()` reproduces them byte-identical. | The cross-implementation invariant. The fixture is not regression of Crema — it's a regression test for **drift from the legacy DE1-app**. |

### 7.2 `de1-domain` — extend `profile.rs` tests

Already covered: header/tail count, flow-priority flag, exit-condition
flag mapping, mix-sensor flag, limiter → extension frame, ordering of
`frame_packets`, empty rejection, > 32 rejection.

**Gaps to add:**

| Test | Why |
|------|-----|
| Each `ExitMetric × Compare` combination → assert the four flag bits. | Today only one combination (`Flow / Over`) is asserted in `an_exit_condition_maps_to_compare_flags_and_trigger`. |
| `Pump::Pressure` step + a limiter → assert the extension's `max_flow_or_pressure` is **flow** (not pressure). | The legacy semantic: limiter is *the other quantity*. The test exists in feel but the comment in `de1-domain::profile.rs` (line 71) is the only documentation. |
| `assemble` of a 32-step profile → assert no panic / overflow, header's `frame_count == 32`, tail's `frame_count == 32`. | Boundary. |
| `frame_packets()` byte-level: a profile with two limiters → exactly `[frame0, frame1, ext0, ext1, tail]` byte ordering. | The legacy "normal frames first, then extensions, then tail" invariant. Already partially covered by `frame_packets_are_ordered_frames_then_extensions_then_tail` but only by length, not byte-order semantics. |
| Round-trip `Profile` through serde JSON, then `assemble`. | Closes the loop with the bridge layer which receives JSON. |

### 7.3 `de1-app` — new module-level tests

These are the **mock-handshake tests** the task asks for. Each follows
the same shape:

```rust
let mut core = CremaCore::new();
let profile = sample_profile();
let out = core.upload_profile(&profile, Duration::from_millis(0)).unwrap();

// 1. Assert the emitted command sequence.
assert_eq!(out.commands.len(), 1 + frames + extensions + 1);
match &out.commands[0] {
    Command::WriteCharacteristic { target, data } => {
        assert_eq!(*target, WriteTarget::De1ProfileHeader);
        assert_eq!(data.len(), SHOT_HEADER_LEN);
    }
    _ => panic!(),
}
// ... subsequent commands all De1ProfileFrame, lengths SHOT_FRAME_LEN.

// 2. Feed back the expected acks one-by-one and assert progress events.
for (i, cmd) in out.commands.iter().skip(1).enumerate() {
    let Command::WriteCharacteristic { data, .. } = cmd else { unreachable!(); };
    let ack = core.on_notification(Source::De1FrameAck, data, (i as u64) * 10);
    if i + 1 == frame_count + extension_count + 1 {
        assert!(ack.events.iter().any(|e| matches!(e, Event::ProfileUploadCompleted)));
    } else {
        assert!(ack.events.iter().any(|e| matches!(e, Event::ProfileUploadProgress { .. })));
    }
}
assert!(!core.profile_upload_in_progress());
```

**Mandatory cases:**

| Case | Asserts |
|------|---------|
| Minimal one-step profile. | The smallest upload — `header + 1 frame + tail` (3 writes, 2 acks). Sanity. |
| Three-step profile with no limiters. | Order: header, frame0, frame1, frame2, tail. 4 acks. |
| Three-step profile with one middle-step limiter. | Order: header, frame0, frame1, frame2, ext1, tail. 4 normal acks + 1 extension ack + 1 tail = 5 acks. Tests extension placement. |
| Every step has a limiter. | Header, frames 0..N, ext0..N, tail. Boundary for extension-frame ordering. |
| 32-step profile. | Boundary. |
| `upload_profile` with empty steps → `Err(AppError::ProfileUpload(DomainError::NoSteps))`. | Validation. |
| `upload_profile` with 33 steps → `Err(AppError::ProfileUpload(DomainError::TooManySteps { count: 33 }))`. | Validation. |
| Ack with the wrong `FrameToWrite` byte after a partial upload → `Event::ProfileUploadFailed { reason: UnexpectedAck { expected, got } }`, `profile_upload_in_progress() == false`. | The orchestrator's defensive path. |
| Ack arrives while no upload is in flight → empty `CoreOutput`. | Late ack from a prior session. |
| `cancel_profile_upload` mid-upload → `Event::ProfileUploadFailed { reason: Aborted }`. | Cancel path. |
| `cancel_profile_upload` with no upload → empty `CoreOutput`. | No-op cancel. |
| `reset()` mid-upload → `Event::ProfileUploadFailed { reason: DisconnectedMidUpload }`. | Disconnect path. |
| Calling `upload_profile` while one is already in flight → the prior upload emits `Aborted`, the new one starts cleanly. | Re-entry. |
| `on_tick(now + PROFILE_UPLOAD_ACK_TIMEOUT)` with no acks received → `Event::ProfileUploadFailed { reason: AckTimeout { awaiting: 0 } }`. | Timeout path. |
| `on_tick` half-way through the upload, *after* one ack, well before timeout from the *last ack* → no `AckTimeout` event. | The timeout resets on each ack. |

### 7.4 Bridge tests (wasm + ffi)

Add one symmetric pair per bridge, in the same style as the existing
ones (`tests` mod at the bottom of each `lib.rs`):

- `upload_profile_produces_writes_and_a_started_event` — parses the
  builtin-profile JSON, calls `upload_profile`, asserts the returned
  JSON contains `"De1ProfileHeader"`, `"De1ProfileFrame"`,
  `"ProfileUploadStarted"`.
- `upload_profile_rejects_invalid_json` — pass `"{}"`; assert a
  `DecodeError` or `ProfileUploadFailed { reason: Empty }` event (the
  exact shape depends on whether the bridge surfaces the JSON parse
  failure as a `DecodeError` event or a `Result::Err` → JSON; for
  consistency with `set_steam_hotwater_settings`, surface a structured
  failure event, not a panic).
- `cancel_profile_upload_after_upload_returns_aborted_event`.
- The "fuzz" / "every source / empty / garbage" panics tests should
  include `De1FrameAck`.

### 7.5 Legacy invariants worth asserting

From the legacy walkthrough (§2), these are the invariants that should
become Rust test assertions:

1. **Upload order:** header, then *every normal frame in step order*,
   then *every extension frame in step order*, then the tail. The legacy
   `make_chunked_packed_shot_sample` (`binary.tcl:839-860`) is the
   reference; `AssembledProfile::frame_packets` already matches. Assert
   it byte-level for a fixture.
2. **`IgnoreLimit` is always set** on every normal frame. Already
   covered implicitly by `ProfileStep::to_shot_frame` (line 128); add an
   explicit assertion to catch any future change.
3. **Tail's `FrameToWrite` equals `frame_count`.** Already covered by
   `assemble`'s test; assert it in the byte-fixture test too.
4. **Extension frame's `FrameToWrite` is `index + 32`.** Already
   covered. Re-assert in the byte-fixture test.
5. **The DE1 echoes each frame back, the host matches it by
   `FrameToWrite`.** This is the ack matcher — covered by the new
   `de1-app` tests in §7.3.
6. **Total ack count must equal `frame_count + extension_count + 1`
   (header is not acked).** Already implicit in the orchestrator;
   covered by the "every step has a limiter" test.

### 7.6 No formal legacy test suite to port

`de1app/de1plus/test/` does not exist. The `de1plus/profiles/test_*.tcl`
files are diagnostic profile fixtures (test_temperature.tcl,
test_pressure_release.tcl, etc.) — they are profiles users load to
diagnose hardware, not unit tests. There is nothing to port.

---

## 8. Error handling

### 8.1 BLE write error

Sans-IO: the core does not see BLE write failures directly. The shell
sees them. The expected protocol:

- **A write failure → no ack arrives → ack-timeout watchdog fires after
  `PROFILE_UPLOAD_ACK_TIMEOUT` (§5.3).** This is the catch-all.
- **The shell may also call `cancel_profile_upload`** on a BLE-layer
  error to short-circuit the 5-second wait; the core emits `Aborted`
  immediately.

No special retry logic in the core: the legacy app's retry was commented
out, and we follow that (§2.6). If the shell wants to retry, it can
call `upload_profile` again after the failure event — the orchestrator
handles re-entry cleanly.

### 8.2 No ack within timeout

See §5.3. **5-second timeout from the most recent ack** (or upload
start for the first ack). On fire, emits
`ProfileUploadFailed { reason: AckTimeout { awaiting } }` and clears the
in-progress state.

The shell must drive `on_tick` periodically (it already does — for the
scale-stale watchdog and the steam eco-mode tick). No new wiring there.

### 8.3 DE1 reports a frame error

**The legacy app does not parse a frame-error response** — it only
collects `FrameToWrite` echoes (`bluetooth.tcl:2834-2838`). The DE1
firmware's behaviour on a malformed frame is undocumented in the Tcl;
the conservative interpretation is "the DE1 doesn't ack a frame it
rejects".

Practical implication: a rejected frame manifests to Crema as an
ack-timeout, which surfaces as
`ProfileUploadFailed { reason: AckTimeout { awaiting } }`. The
`awaiting` byte tells the user (or the developer) which frame the DE1
choked on.

This is **a gap that one real-hardware capture (§6) should close**.
If the capture shows the DE1 emits some kind of error packet on
`cuuid_10`, we add a `ProfileUploadFailure::FirmwareRejected { frame, code }`
variant. Until then, "no ack" is the only failure mode we model.

### 8.4 Profile too large for `MAX_STEPS`

`Profile::assemble` already returns `DomainError::TooManySteps { count }`
for `steps.len() > 32` and `DomainError::NoSteps` for `steps.len() == 0`.
`CremaCore::upload_profile` wraps these in `AppError::ProfileUpload`,
returns `Result::Err` to the bridge, and the bridge surfaces a
`ProfileUploadFailed { reason: ProfileUploadFailure::{TooManySteps,Empty} }`
event in the JSON (or, equivalently, the bridge can return an empty
`CoreOutput` whose only event is `ProfileUploadFailed`; this latter is
the more uniform shape — every error becomes an event, the bridge never
needs to render a `Result::Err`).

**Decision:** the bridge **always** returns a `CoreOutput`-shaped JSON;
validation failures appear as `ProfileUploadFailed` events with no
accompanying commands. The internal `CremaCore::upload_profile` keeps
its `Result<CoreOutput, AppError>` signature (so unit tests can assert
on the typed error), but the bridge swallows the `Err` and emits the
failure event. This matches the `DecodeError`-event pattern used for
malformed notifications.

### 8.5 Disconnect mid-upload

The shell calls `CremaCore::reset()` on DE1 disconnect. The reset:

- Emits `Event::ProfileUploadFailed { reason: DisconnectedMidUpload }`
  if an upload was in flight, then `*self = CremaCore::new()` (or
  equivalent — clears in-progress state).
- The shell **does not** queue the upload to re-attempt on
  reconnect — that's a UX policy decision; recommendation is the
  reconnect handler can re-upload the *active* profile (the legacy app
  does this via `save_settings_to_de1` on connect). The shell drives it.

Implementation detail: emitting the event in `reset()` requires
`reset()` to return a `CoreOutput` (today it returns `()`). Recommend
keeping `reset()` returning `()` and instead emitting the
`DisconnectedMidUpload` event lazily — push it into the *next*
`on_notification` / `on_tick` `CoreOutput` after the reset. Alternative:
add a dedicated `disconnect()` method that returns `CoreOutput`, leaving
`reset()` for cold-start. **Recommended:** the latter — a new
`disconnect() -> CoreOutput` method that emits the event and then
clears state. `reset()` stays as the cold-start primitive.

### 8.6 Upload requested while a shot is in progress

The legacy app permits this — `de1_send_shot_frames` doesn't check
state (§2.9). The DE1 firmware probably keeps the in-progress shot's
profile in a separate buffer and applies the new one for the *next*
shot, but this isn't documented in the Tcl.

**Recommendation:** the core accepts the upload regardless of state.
The brew page UX should disable the profile picker while a shot is
running (a shell-side policy, modelled on the legacy GUI which also
hides the profile button during a shot — DSx2's brew screen logic).
The core does not enforce this; doing so would break the legacy
re-push-on-`save_settings` flow which can fire during a shot.

### 8.7 Concurrent uploads

If `upload_profile` is called while `profile_upload_in_progress()` is
true, the orchestrator:

1. Emits `ProfileUploadFailed { reason: Aborted }` for the prior upload.
2. Then proceeds with the new upload normally.

This is symmetric with `cancel_profile_upload + upload_profile` and
matches the legacy `remove_matching_ble_queue_entries` purge
(`de1_comms.tcl:1458-1459`) — the legacy code also lets a fresh upload
preempt a stale one.

---

## 9. FFI surface for the shell

### 9.1 What the shell sees

```ts
// web/src/lib/core/index.ts — CremaCore interface, appended

interface CremaCore {
    // … existing methods …

    /**
     * Upload `profile` to the DE1. Resolves to the initial CoreOutput
     * carrying ProfileUploadStarted + every BLE write command in upload
     * order. Subsequent progress arrives via ProfileUploadProgress
     * events on cuuid_10 notifications; success via
     * ProfileUploadCompleted; failure via ProfileUploadFailed.
     */
    uploadProfile(profile: Profile): Promise<CoreOutput>;

    /** Cancel an in-progress upload; emits ProfileUploadFailed{Aborted}. */
    cancelProfileUpload(): Promise<CoreOutput>;

    /** True from uploadProfile until tail-ack / failure / cancel. */
    profileUploadInProgress(): Promise<boolean>;
}
```

### 9.2 NotificationSource additions

```ts
// web/src/lib/core/index.ts — NotificationSource union
export type NotificationSource =
    | 'De1State'
    | 'De1ShotSample'
    | 'ScaleWeight'
    | 'ScaleCommand'
    | 'De1WaterLevels'
    | 'De1Version'
    | 'De1MmrRead'
    | 'De1Calibration'
    | 'De1FrameAck';   // ← new
```

The BLE layer (`web/src/lib/ble/de1.ts`) must subscribe to cuuid_10
and forward notifications with `source: 'De1FrameAck'`. Symmetric
addition in the capture/replay tooling (§6).

### 9.3 Event union additions

The typeshare-generated `crema-core.ts` will gain the four new event
variants (`ProfileUploadStarted`, `ProfileUploadProgress`,
`ProfileUploadCompleted`, `ProfileUploadFailed`) and the
`ProfileUploadFailure` enum automatically — they're added to the
existing `#[typeshare]` `enum Event` and a new `#[typeshare]` enum
respectively.

### 9.4 UX surface

A single profile-picker button calls `crema.uploadProfile(profile)`,
then renders progress from the event stream:

```
ProfileUploadStarted { frame_count: 5, extension_frame_count: 1 }
  ↓ "Uploading… (0/7)"
ProfileUploadProgress { frame: 0, extension: false, total_acks: 7, acks_received: 1 }
  ↓ "Uploading… (1/7)"
…
ProfileUploadProgress { frame: 5, extension: false, total_acks: 7, acks_received: 7 }  ← tail
ProfileUploadCompleted
  ↓ "Profile loaded"
```

A failure event replaces the progress text with the failure reason.

---

## 10. Open questions

1. **Exact `cuuid_10` echo format on real hardware.** The legacy parser
   treats it as the same 8-byte shape as the write; needs one
   real-hardware capture to verify, especially for the tail's ack and
   any error-path notification. See §6.
2. **Does `cuuid_0F` (HeaderWrite) emit any echo / notification on
   write?** The legacy app does not subscribe to it. If the DE1 does
   emit something, we ignore it; if it doesn't, we don't subscribe. No
   functional impact either way; worth confirming via the same capture.
3. **Inter-frame timing on Web Bluetooth.** Audit §6 noted that
   firmware-update writes need a 1 ms delay on non-Android platforms.
   Profile-upload writes are larger (8 bytes vs. firmware's 16 bytes
   payload but to a different characteristic with different ATT timing)
   and shorter in total — probably fine without a delay, but worth
   confirming on the same capture. If a delay is needed, it lives in
   the shell, per audit §6's recommendation.
4. **Behaviour when a tail ack arrives with `FrameToWrite != frame_count`.**
   Per §2.6, the legacy app's success check is "every ack's
   `FrameToWrite` matched the expected sequential index, *and* the
   total count matched." Crema's stricter check (every ack matches
   `expected_ack` exactly, including the tail) gives an
   `UnexpectedAck` failure here. Probably no behavioural difference
   against real hardware, but the test fixture once captured will tell
   us.
5. **The `tank_temperature_threshold` companion write.** Per audit
   §2.2 / row 4.2, this MMR write is part of the legacy upload path
   for advanced profiles, with a 4-second preheat dance. This plan
   excludes it (§1, out of scope). The shell composes the two when
   §4 MMR writes land — it's not the orchestrator's job here.
6. **`final_desired_shot_weight` / `espresso_volume_ml` piggyback.**
   The task framing mentions that the steam/hotwater settings include
   `espresso_volume_ml`, which "piggybacks on this." Confirmed from
   reading: `final_desired_shot_weight` is purely **app-side metadata**
   in both the legacy app (`profile.tcl:206-345`) and Crema's domain
   model (`de1-domain::profile.rs:158-167`). It does not appear on the
   profile upload wire. The connection to `ShotSettings.espresso_volume_ml`
   is via `de1_send_steam_hotwater_settings` (`de1_comms.tcl:1555`),
   which fires *alongside* `de1_send_shot_frames` from
   `save_settings_to_de1` — these are sibling writes, not a single
   compound write. Crema already plumbs `set_steam_hotwater_settings`
   separately; no profile-upload action.
7. **Should `upload_profile` accept a JSON `Profile` directly at the
   `CremaCore` level?** Today the core's public surface trades in typed
   values; the bridge does the JSON parse. Recommendation:
   `CremaCore::upload_profile(&Profile, Duration)` takes the typed
   value; the bridge layer parses JSON and surfaces a parse failure as
   `ProfileUploadFailed { reason: Empty }` (or a new `MalformedJson`
   variant). Alternative: a `CremaCore::upload_profile_json(&str,
   Duration)` convenience — would couple the core to serde_json, which
   already happens via `builtin_profiles_json`, but expanding it is
   speculative. Recommendation: stay typed-only in the core.

---

## 11. Risk assessment

| Risk | Severity | Mitigation |
|------|----------|------------|
| Ack format differs from the legacy walkthrough's assumption. | **Medium.** Would cause every upload to fail with `UnexpectedAck`. | Single real-hardware capture before merging. The `ack_frame_byte` helper is tiny; the failure mode is *visible* (an event with the actual `got` byte) so debugging from a capture is straightforward. |
| Ack-timeout too short for a slow connection (Bluetooth congestion, busy DE1). | **Low.** Surfaces as a spurious `AckTimeout` failure; user retries. | 5-second margin is ~100x typical. Capture confirms the real distribution. The constant lives in one place (`PROFILE_UPLOAD_ACK_TIMEOUT`) and can be tuned without an FFI change. |
| Ack-timeout too long → user perceives the app as hung. | **Low.** | The shell can show a progress UI with its own per-frame timeout (e.g. visual spinner that hits a "Still working…" state after 2 s) without changing the core. |
| Upload while the DE1 is in a `FatalError` or `Busy` state — the firmware may silently drop frames. | **Medium.** Same surface as a real ack-timeout. | Documented as a known failure mode; no special handling in the core. The shell may gate the profile picker on a recent `MachineStateChanged` showing a normal state. |
| Re-entrant `upload_profile` race (two calls from the UI in quick succession). | **Low.** | The orchestrator handles re-entry deterministically (aborts the prior, starts the new one). Add a focused test. |
| Concurrency in the shell: a frame ack and a `cancel_profile_upload` arriving "at the same time" — possible because both go through the JS event loop. | **Low.** | The core's `&mut self` API serialises them via the bridge mutex (FFI) / single-thread JS (wasm); there is no real concurrency at the core's seam. |
| Capturing the ack format requires a real DE1. | **Procedural risk, not a code risk.** | The implementation can land behind a feature gate (or as a `cfg(test)`-only check) until the capture confirms; the capture itself is half a day of work once we have hardware available. |
| **Expensive to undo:** the new `WriteTarget::De1ProfileHeader` / `De1ProfileFrame` variants and `Source::De1FrameAck` variant. | **Low.** | All three enums are already `#[non_exhaustive]`; the variants can be deprecated later if their shape needs to change (e.g. if we want a single `WriteTarget::De1Profile` and a discriminator on `data`). Recommend the per-characteristic split now because the shell needs to know which UUID to write to. |
| **Most-expensive-to-undo decision:** putting the ack state machine in `de1-app` (the orchestrator) rather than in a new submodule or in `de1-domain`. | **Low.** | Same place as the auto-stop / shot-monitor state machines; matches the existing seam between the codec (`de1-protocol` / `de1-domain`) and the state machine (`de1-app`). Moving it later is mechanical. |
| Web shell forgets to subscribe to `cuuid_10` notifications. | **Medium.** Would cause every upload to fail with `AckTimeout`. | One-time wiring in `web/src/lib/ble/de1.ts`; covered by adding the subscription alongside the capture-recording change in §6. Add a smoke test in the shell that asserts the subscription exists. |
| A real DE1 firmware version (older than 1293, or a beta) returns acks in a different shape. | **Low.** | The `ack_frame_byte` helper only reads byte 0; even a malformed-by-our-spec ack still yields a `FrameToWrite` byte. Worth scanning the firmware-release notes for any cuuid_10 changes during the capture exercise. |

### Validate against a real DE1

The **single most important pre-merge action** is one real-hardware
capture using the recommendation in §6. Everything else in this plan
is derivable from the legacy source and the existing codec; the
capture closes the only genuine unknown — the exact byte shape of
cuuid_10 notifications under real BLE conditions.

---

## Cross-references

- `docs/02-ble-protocol.md` §5 — the wire spec this plan codifies.
- `docs/04-mvp-scope.md` — profile upload is in scope for MVP.
- `docs/08-ffi-and-web-scope.md` — the FFI seam every new bridge method
  follows.
- `docs/13-deferred-refactors.md` — the scale-neutral seam noted there
  does not apply here (the DE1 is one device; the profile-upload codec
  is device-specific by nature).
- `docs/14-write-actions-audit.md` §2 — the audit row this plan
  expands; this plan supersedes the row's "Proposed Rust API" sketch
  with the full design.
- `core/de1-protocol/src/profile.rs` — the codec.
- `core/de1-protocol/src/fixed_point.rs` — the audited fixed-point
  encoder behaviour (saturate vs. wrap).
- `core/de1-domain/src/profile.rs` — `Profile::assemble`.
- `core/de1-app/src/event.rs` — `Source`, `WriteTarget`, `Event`,
  `Command`, `CoreOutput`.
- `core/de1-app/src/lib.rs` — `CremaCore` orchestrator.
- `core/de1-app/examples/replay.rs` — capture-file replay tool to
  extend with the new source.
- `core/de1-wasm/src/lib.rs`, `core/de1-ffi/src/lib.rs` — the bridges.
- `web/src/lib/core/index.ts` — the web-shell interface.
- `web/src/lib/capture/recorder.ts`, `web/src/lib/replay/capture.ts` —
  the capture/replay infrastructure for §6.
- `web/src/lib/ble/de1.ts` — where the cuuid_10 subscription lands.
