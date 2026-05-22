# 22 — Reaprime audits: consolidated action list

Companion doc to the seven detailed audits at `~/reaprime-audits/01..07-*.md`
(the audits stay outside this repo — they're research artifacts, this
file is the actionable consolidation that lives in-tree).

Every item below traces to one or more audit findings. Severity is in
two dimensions:

- **Correctness** — does ignoring this produce wrong values, lost
  shots, or a corrupted persistence layer?
- **Effort** — `XS` (one-line / mechanical), `S` (≤ 2 h), `M` (≤ 1 day),
  `L` (multi-day), `XL` (multi-week).

The recommended sequence at the bottom (§7) bundles items into
shippable commits with rough order.

---

## 1. CRITICAL — landed bugs to fix

### 1.1 `ShotSample` parser has HeadTemp / MixTemp byte offsets swapped

- **Source**: `01-protocol-diff.md`
- **Severity**: correctness-CRITICAL · effort `S`
- **Where**: `core/de1-protocol/src/shot_sample.rs:79-80`,
  `docs/02-ble-protocol.md` (§3.2 table), unit-test fixture.
- **What's wrong**: Crema reads HeadTemp at bytes 6–8 (U24P16) and
  MixTemp at 9–10 (U16P8). The legacy TCL spec
  (`de1plus/de1_de1.tcl:712-726`, canonical reference) and reaprime
  both have MixTemp at bytes 6–7 (Short, U16P8) and HeadTemp at 8–10
  (U24P16). Our parser's fields overlap with the wire's actual
  layout, so what Crema calls `head_temp` is reading bytes 6-7-8 — a
  corrupted mix of wire MixTemp + the high byte of wire HeadTemp.
- **Why it didn't surface louder**: Crema's `docs/02` repeats the
  wrong order, and the test fixture was built consistent with the
  buggy parser. Three-way disagreement: Crema (parser + docs + test)
  vs legacy TCL + reaprime + actual DE1.
- **Why we noticed something off earlier**: the "group temp looks
  widely wrong" report from the user that produced commits `d129eef`
  and `63fc71f`. We treated it as a label problem; it was actually a
  decoder problem.
- **Verification needed**: a real-DE1 BLE capture. The prior is
  extremely strong that we're wrong (legacy TCL is canonical;
  reaprime ships in production), but a wire capture seals it.
- **Fix**: swap the offsets in `shot_sample.rs`, update `docs/02`,
  regenerate the test fixture's bytes against the corrected
  semantics, add a wire-capture-derived test vector.

### 1.2 Profile-upload → `requestState(Espresso)` race aborts shots

- **Source**: `05-write-side-cross-ref.md`
- **Severity**: correctness-CRITICAL · effort `XS`
- **Where**: orchestrator side, in the post-upload path. No code
  currently sits here.
- **What's wrong**: after a profile upload completes, the DE1
  firmware briefly holds a `ProfileDownloadInProgress` flag. A
  `requestState(Espresso)` written into that window **aborts the
  shot mid-preinfuse** (reaprime cites BC bug 9788201734). Crema
  has no guard delay; an aggressive Load-on-Brew → Start sequence
  reproduces the bug.
- **Fix**: add a ~100 ms minimum-elapsed guard between
  `Event::ProfileUploadCompleted` and any state-transition write.
  Cleanest: a `last_profile_upload_at` timestamp on `CremaCore` +
  a check in `request_machine_state`. Or shell-side delay if we
  don't want core-level temporal awareness.
- **Note**: also affects auto-upload-on-connect (commit `3fdfcb4`)
  if the user power-cycles into a stale "queued Start" — unlikely
  but possible.

---

## 2. HIGH — wire-format correctness

These are byte-level disagreements between Crema and reaprime where
reaprime ships in production. Each needs a real-DE1 BLE capture to
fully settle but is worth pursuing because reaprime's wire encoding
has more field-time validation than ours.

### 2.1 `SteamFlow` MMR scaling

- **Source**: `05-write-side-cross-ref.md`, `01-protocol-diff.md`
- **Severity**: correctness-HIGH · effort `XS`
- Crema docs say `×10`. Reaprime ships `×100`. Codec divergence —
  the wrong scale produces a wrong setting on the wire.

### 2.2 `SteamHighFlowStart` MMR byte count + scaling

- **Source**: `05-write-side-cross-ref.md`
- **Severity**: correctness-HIGH · effort `XS`
- Crema docs say 1-byte. Reaprime writes 4-byte plain int despite
  enum-description claiming `×100`. Needs DE1 capture to settle.

### 2.3 Firmware erase/verify FWMapRequest sequence diverges

- **Source**: `05-write-side-cross-ref.md`,
  `docs/17-firmware-update-plan.md §7`
- **Severity**: correctness-HIGH · effort `S` (just spec; v2-deferred)
- Reaprime sets `firmwareToMap=1` on erase and skips the legacy
  four-step "map" sub-flow, collapsing to a two-write protocol.
  Crema's docs/17 §7 plan is more conservative. When v2 firmware-update
  implementation kicks off, reconcile against reaprime's two-write
  flow as the modern reference.

### 2.4 `appFeatureFlags = 1` is never written at connect time

- **Source**: `05-write-side-cross-ref.md`
- **Severity**: correctness-HIGH (silent feature non-functional)
  · effort `XS`
- The `UserPresent` heartbeat we shipped in `6c8be97` only does
  anything if `FeatureFlags.UserNotPresent` is set. Crema writes
  the bit when the user toggles "Keep DE1 awake" but **never on
  initial connect**, so on a fresh session the DE1's firmware
  ignores our heartbeats until the user touches the toggle.
- **Fix**: write `setFeatureFlags(1)` (UserNotPresent bit set) in
  the `onState('ready')` block, unconditionally if the setting is
  on. Already half-implemented by `setSuppressDe1Sleep` — just
  fire it at connect time.

### 2.5 Ten MMR registers carry implicit scale factors

- **Source**: `01-protocol-diff.md`
- **Severity**: correctness-MEDIUM (no codec bug, but easy to mis-use)
  · effort `S`
- `Phase1FlowRate`, `Phase2FlowRate`, `HotWaterIdleTemp`,
  `HotWaterFlowRate`, `FlushFlowRate`, `EspressoWarmupTimeout`,
  `FlushTimeout`, `SteamFlow`, `SteamHighFlowStart`,
  `CalibrationFlowMultiplier` — all want `×0.1` / `×0.01` / `×0.001`
  conversions reaprime carries in a `readScale` field on the
  address type. Crema only hints in docstrings.
- **Fix**: either add a `scale()` method on `MmrRegister` returning
  `f32`, or expand docstrings and the high-level write helpers to
  enforce the divisor.

---

## 3. HIGH — dormant / wrong code to remove or finish

### 3.1 Drop dormant `FRAME_WRITE (A010)` subscription

- **Source**: `04-read-side-cross-ref.md`, `docs/16 §6.2`
- **Severity**: correctness-LOW (it never fires) · effort `XS`
- `web/src/lib/ble/de1.ts` subscribes to FRAME_WRITE notify but
  snoop-confirmed the DE1 never emits on it (commit `49f0803`
  context). One line to remove.

### 3.2 Add `FlushTemp` register at `0x803844`

- **Source**: `01-protocol-diff.md`, `04-read-side-cross-ref.md`,
  `05-write-side-cross-ref.md`
- **Severity**: feature-MEDIUM · effort `XS`
- Reaprime models it; Crema's `MmrRegister` enum doesn't have it.
  Add the variant + address + the test arm.

### 3.3 Add the 5 missing `MachineRequest` variants

- **Source**: `05-write-side-cross-ref.md`
- **Severity**: feature-LOW · effort `XS`
- `SchedIdle`, `SteamRinse`, `AirPurge`, `ShortCal`, `SelfTest`
  exist in the legacy DE1 firmware but not in our wasm
  `MachineRequest` enum or the facade's `requestMachineState` map.
  Add the five mappings.

### 3.4 Surface 5 dropped `ShotSample` fields on `Event::Telemetry`

- **Source**: `04-read-side-cross-ref.md`, `01-protocol-diff.md`
- **Severity**: feature-MEDIUM (unlocks goal-overlay UI) · effort `S`
- `set_mix_temp`, `set_head_temp`, `set_group_pressure`,
  `set_group_flow`, `frame_number` — Crema decodes them all but
  `Event::Telemetry` drops them. Surface them so the brew page's
  goal-overlay (commit `c761222`) can use them.
- **Dependency**: §1.1 has to land first — these fields' offsets
  may also be affected by the parser fix.

### 3.5 Subscribe `ShotSettings (A00B)` notify + connect-time Read

- **Source**: `04-read-side-cross-ref.md`,
  `docs/20-read-paths-audit.md §5.3`
- **Severity**: feature-MEDIUM · effort `S`
- Reaprime + legacy both Read at connect (the legacy
  `de1_read_hotwater` proc). Crema only Writes. Unblocks the
  bidirectional Steam / Hot-Water settings UI currently stubbed
  in QuickSheet.

### 3.6 Rename `SteamTwoTapStop` to match reaprime's `steamPurgeMode`?

- **Source**: `01-protocol-diff.md`, `04-read-side-cross-ref.md`
- **Severity**: nomenclature-LOW · effort `XS` (or skip)
- Same MMR address (`0x803850`), different name. Need DE1 firmware
  docs to confirm which is canonical. Until then, leave as-is.

---

## 4. MEDIUM — profile v2 parity & adoption

(All from `06-profile-v2-plan.md`.)

### 4.1 Add 5 missing v2 profile fields

- **Severity**: import-fidelity-MEDIUM · effort `S`
- Top-level: `author`, `beverage_type`, `tank_temperature`,
  `version` (as a **string** `"2"`, not a number). Per-step:
  `weight`. Crema currently drops them on import.
- All `#[serde(default)]` adds — zero breakage to existing stored
  profiles.

### 4.2 Strict enum validation on `transition` and `sensor`

- **Severity**: validation-MEDIUM · effort `XS`
- Reaprime throws on unknown enum spellings; Crema silently falls
  back. Audit the community profile corpus first to see if any
  ship typos — if yes, log + warn instead of throwing.

### 4.3 Profile export — canonical key order + `version: "2"` string

- **Severity**: export-correctness-MEDIUM · effort `S`
- Required if we ever want hash interop with reaprime's
  content-addressed IDs.

### 4.4 ProfileHash content-addressed IDs

- **Severity**: feature-MEDIUM (interop) · effort `M`
- 20+ test cases in reaprime's `profile_test.dart`. Adds
  `core/de1-domain/src/profile_hash.rs`. Locks Crema into a
  permanent canonical-JSON contract once shipped, so weigh
  carefully.

### 4.5 Newline-in-notes Profile round-trip equality

- **Source**: `03-tests-to-port.md`
- **Severity**: correctness-MEDIUM (causes redundant uploads)
  · effort `XS`
- A test fixture from reaprime catches that Crema's round-trip
  changes line endings, breaking equality checks and triggering
  unnecessary re-uploads. One-line normalisation fix; add the
  reaprime test vector.

---

## 5. MEDIUM — test imports

(All from `03-tests-to-port.md`.)

### 5.1 Port legacy de1app history-import fixtures

- **Severity**: feature-MEDIUM (new feature) · effort `M`
- `test/import/` and `test/fixtures/de1app/` include two captured
  shots (legacy TCL `.shot` + modern v2 JSON) + parsers covering
  timestamp / ID / snapshot / metadata extraction with truncated-
  array tolerance. Crema has no legacy de1app import path.
- Lands as new module `core/de1-domain/src/history_import.rs` +
  `core/de1-domain/tests/fixtures/`.

### 5.2 Port Bengle MMR byte-exact tests

- **Severity**: correctness-MEDIUM · effort `S`
- Cup warmer, SAW, steam-stop contracts. Crema's current Bengle
  coverage is light.

### 5.3 Port Acaia + Atomheart scale codec tests

- **Severity**: correctness-MEDIUM · effort `S`
- Acaia GATT-service-based protocol auto-detection + tare 3×
  retry. Atomheart timer-field decode. Both have Crema codecs but
  not these specific edge cases.

### 5.4 Settings import — legacy `settings.tdb`

- **Severity**: feature-LOW (migration aid) · effort `M`
- New `core/de1-app/src/settings_import.rs`. Lets users coming
  from de1app bring their stored preferences over.

---

## 6. LOW — UX adoption candidates

(All from `02-ux-features.md`.)

### 6.1 Demo / simulated-devices mode

- **Severity**: dev-UX-MEDIUM · effort `L` (~2 days)
- Unblocks screenshots, design review, CI, and dev iteration
  without the $3k machine. Reaprime's pattern: a `MockDe1` that
  scripts a shot to play back through the same orchestration as
  a real device.

### 6.2 Workflow-context model (bean / grinder / dose attached to shots)

- **Severity**: data-model-MEDIUM · effort `L` (~2–3 days)
- Crema currently attaches dose-grind to *profiles* implicitly via
  the brew-params seed. Reaprime models them on shots, which is
  the right shape — same profile, different beans on different
  days.

### 6.3 Active-steam screen + Skip-step button

- **Severity**: feature-MEDIUM · effort `M` (~1–2 days)
- Closes the worst current control gap for mid-shot adjustments.

### 6.4 Onboarding troubleshooting wizard pattern

- **Severity**: feature-LOW · effort `S`
- Reaprime's `troubleshooting_wizard.dart` is only 177 LOC. The
  pattern (sequence of yes/no questions with `shouldShow`
  predicates + Open-Settings shortcuts) adapts cleanly for
  Web-Bluetooth-specific failure modes: non-Chromium browser,
  GATT-failed, OS-paired-device-not-here.

---

## 7. NEW capability — USB transport

(From `07-usb-connection-plan.md`.)

### 7.1 Phase 1 — `De1Transport` interface refactor, BLE-only

- **Severity**: refactor (no user-visible change) · effort `M` (~1 day)
- Factor the BLE-specific bits in `web/src/lib/ble/de1.ts` behind
  a `De1Transport` interface. No behaviour change yet. Future
  USB / desktop transports plug into the same shape.

### 7.2 Phase 2 — WebUSB feasibility spike

- **Severity**: speculative · effort `S` (~2 days, time-boxed)
- May conclude "WebUSB DE1 is blocked by Chromium's CDC-ACM
  blocklist until Decent ships a firmware descriptor change."
  Clean fallback: "web shell stays BLE-only forever."

### 7.3 Phase 3 — Android USB host mode

- **Severity**: feature-MEDIUM · effort `M` (~2–3 days)
- Real user value (Android phones with USB-C OTG can power +
  talk to the DE1 over USB). Same `usb_serial` library reaprime
  uses (`com.github.felHR85:UsbSerial`). Ports reaprime's ASCII
  framing parser + MMR codec to Kotlin, ~70 LOC.

### 7.4 Phase 4 — Desktop USB

- **Severity**: future · effort `M`
- Falls out for free once Phase 3 lands.

---

## 8. Anti-patterns — explicitly DON'T port

(All flagged in audits — listed here so future contributors don't
accidentally re-introduce them.)

- **Reaprime's `onConnect` overwriting stored `refillKitPresent`
  with `0x02 (auto)` every connect** (audit #5). Drops user
  preference; keep Crema's behaviour.
- **Reaprime's 10-second fixed sleep after firmware erase**
  (audit #5). Crema's docs/17 §7 event-driven approach is better.
- **Reaprime's hard-coded `minPressure=0, maxFlow=12.0` in profile
  header** (audit #5; they have a TODO for this). Crema should
  source from the Profile model.
- **Reaprime's WebUI hosting, skin runtime, plugin system,
  native-permissions framework** (audit #2). Don't apply to a
  SvelteKit web app.
- **Don't strip Crema features reaprime lacks**: maintenance
  counters via integrated group-flow, calibration readout, star
  ratings, history stats strip, raw-capture per-shot download,
  unit-aware everything (D1), live phase indicator on chart, the
  graphical `ProfileCurveEditor`, keyboard nav. All are Crema-
  better.

---

## 9. Recommended sequence

### 9.1 Land-immediately bundle (Critical bugs + dormant cleanup)

Single commit (or two small commits):

1. §1.1 — ShotSample parser fix + docs/02 + test fixture
   regenerated. (Wants a real-DE1 capture to confirm; if no
   capture available now, file the fix on a separate branch.)
2. §1.2 — 100 ms guard between ProfileUploadCompleted and
   state-request writes.
3. §2.4 — `setFeatureFlags(1)` at connect time so the
   user-presence heartbeat works on fresh sessions.
4. §3.1 — Drop dormant `FRAME_WRITE` subscription.
5. §3.2 — Add `FlushTemp` register.
6. §3.3 — Add 5 `MachineRequest` variants.

Effort: half a day. Closes both criticals + 4 cleanup items.

### 9.2 Wire-format-week bundle

After §9.1 lands, with a real-DE1 capture in hand:

1. §2.1 — `SteamFlow` scaling.
2. §2.2 — `SteamHighFlowStart` byte count + scaling.
3. §2.5 — Add `scale()` to `MmrRegister`.

Effort: half a day + capture review.

### 9.3 Test-and-features bundle

Each is its own commit; order to taste:

- §3.4 — Surface dropped ShotSample fields on Telemetry.
- §3.5 — ShotSettings subscribe + connect-time Read.
- §4.1 — Five missing v2 profile fields.
- §4.5 — Newline-in-notes round-trip test.
- §5.1 — Legacy de1app history import.

Effort: 2–3 days.

### 9.4 UX bundle

Each its own multi-day effort, sequence to taste:

- §6.1 Demo mode (~2 days)
- §6.3 Active-steam screen (~1–2 days)
- §6.2 Workflow context model (~2–3 days; bigger data-model
  change — design before coding)
- §6.4 Troubleshooting wizard pattern (~ half day)

### 9.5 USB transport

Whenever there's appetite for the spike. §7.1 is mergeable as a
pure refactor regardless of where USB ultimately lands.

---

## 10. Open questions for the user

1. **Wire capture for §1.1**: do we have a real-DE1 `ShotSample`
   capture to verify the byte-offset fix? If not, file the fix
   blocked-on-capture or proceed on the strength of legacy-TCL +
   reaprime agreement?
2. **§4.2 strictness**: audit the community profile corpus for
   `transition` / `sensor` typos before flipping Crema to strict?
3. **§4.4 ProfileHash**: ship hash interop now (locking us into
   canonical JSON contract permanently), or defer until there's a
   concrete cross-app share scenario?
4. **§6.2 workflow context**: ship now (multi-day) or wait until
   the brew-page mode-switch design lands so we get one
   data-model pass?
