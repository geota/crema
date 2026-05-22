# 25 — Enum naming conventions across the Rust layers

Closes task #58. The question that surfaced mid-session was whether
the `TempSensor::Coffee/Water` rename (commit `6f1b125`) and the
`HotWaterRinse` ↔ `Flush` two-layer pattern were consistent.

Short answer: **they're the same rule applied at different layers.**
Coffee/Water belongs at the domain layer because it's a user-facing
concept from the community v2 JSON profile contract; HotWaterRinse
belongs at the protocol layer because that's the firmware's byte
name; `Flush` re-surfaces in the wasm/UI layer because that's what
the chip button says. No conflict, no inconsistency.

This doc pins the rule explicitly so future enums land on the right
side.

---

## 1. The three Rust layers

| Crate | Audience | Naming source |
|---|---|---|
| `de1-protocol` | Wire format · firmware-byte semantics | The firmware's own names |
| `de1-domain` | App logic · profile model · history records | Community v2 JSON contract |
| `de1-wasm` / `de1-ffi` | Shell-facing FFI · UI buttons + labels | What the user reads on a button |

Each layer is the right home for a different *kind* of name:

- **Protocol layer** is closest to the wire. Naming there should
  match the firmware's documented field names so a reader staring at
  a BLE notification's bytes can grep the same string in the source.
- **Domain layer** is the persistent shape of profiles and history
  that round-trip through community v2 JSON. Naming there should
  match the v2 contract so an exported profile is byte-identical to
  what reaprime/Visualizer/de1app produce.
- **Shell layer** is what the user sees. Names there can be tuned
  for clarity in UI copy without dragging the protocol or persistence
  shapes along.

A name *should* differ across layers only when the audiences
genuinely care about different framings. When they all care about
the same thing, the same name flows through every layer (most enums
fall here).

---

## 2. Classified enum catalog

### Protocol-layer (uses firmware names)

| Enum | Location | Variants | Wire shape |
|---|---|---|---|
| `MachineState` | `de1-protocol::state` | `Idle / Espresso / Steam / HotWater / HotWaterRinse / Descale / Clean / Sleep / SchedIdle / SteamRinse / AirPurge / ShortCal / SelfTest / …` | u8 byte; custom From impl |
| `SubState` | `de1-protocol::state` | All 30+ firmware substate labels | u8 byte |
| `FrameFlags` (struct field) | `de1-protocol::profile` | `target_mix_temp: bool`, `interpolate: bool`, … | Flag-bit byte in each shot frame |
| `Pump` (in `FrameFlags` derived via `flow_priority`) | Same | Mechanism-level | Bit on flags byte |

These names match the legacy de1app TCL + reaprime's parsing
identifiers so a contributor cross-referencing a wire capture can
pattern-match on the same identifier in any of the three codebases.

### Domain-layer (uses community v2 JSON contract)

| Enum | Location | Variants | JSON spelling |
|---|---|---|---|
| `Pump` | `de1-domain::profile` | `Pressure / Flow` | `"pressure" / "flow"` |
| `Transition` | Same | `Fast / Smooth` | `"fast" / "smooth"` |
| `TempSensor` | Same | `Coffee / Water` | `"coffee" / "water"` |
| `ExitMetric` | Same | `Pressure / Flow` | `"pressure" / "flow"` |
| `Compare` | Same | `Over / Under` | `"over" / "under"` |
| `BeverageType` | Same | `Espresso / Calibrate / Cleaning / Manual / Pourover` | `"espresso" / "calibrate" / …` |

These names come straight from the v2 profile JSON spec the DE1
community agreed on. `TempSensor::Coffee` is the **concept**
("regulate the coffee's temperature at the basket"); the protocol
layer flips that to the wire flag `target_mix_temp: false`. The
mapping happens once in `ProfileStep::to_shot_frame` and is the
boundary between the two layers.

### Shell-layer (user-readable button names)

| Enum | Location | Variants | Differs from protocol? |
|---|---|---|---|
| `MachineRequest` | `de1-wasm`, `de1-ffi` | `Sleep / Idle / Espresso / Steam / HotWater / Flush / Descale / Clean / SkipToNext / SteamRinse / AirPurge / SchedIdle / ShortCal / SelfTest` | Yes — `Flush` is the user word for the firmware's `HotWaterRinse` |
| `NotificationSource` | Same | One variant per BLE characteristic Crema reads | No — mirrors protocol `Source` 1:1 |
| `MmrReg` | Same | One variant per MMR register | No — mirrors protocol `MmrRegister` 1:1 |

The single deliberate mismatch is `Flush` ↔ `HotWaterRinse`. The
firmware names byte `0x06` for the *mechanism* (running hot water
through the group); the brew page calls the button *Flush* because
that's the user's mental model. The bridge's `From<MachineRequest>`
impl is the one-line boundary between the two names.

---

## 3. The rule — when to introduce a layer-specific name

> **Use a layer-specific name only when the audience at that layer
> genuinely cares about a different framing of the same concept.**

In practice that's a high bar. The only place we currently meet it
is `Flush` vs `HotWaterRinse` — a single user-facing button label
that doesn't read well as "Hot Water Rinse" in the chip row.

For every other enum: pick the right naming source for the layer
the enum lives in, and let it flow through unchanged. The bridge
doesn't try to UI-ify enum names that the user never reads (BLE
sources, MMR register ids, etc.).

### Decision checklist for new enums

When you add an enum that crosses layers, ask in order:

1. **Where does this enum primarily live?** Protocol byte → put it
   in `de1-protocol`. Profile JSON / persistence → `de1-domain`.
   UI-only request that the user types or clicks → `de1-wasm`.
2. **Does the user ever see one of these variant names?** No → keep
   one set of names across every layer.
3. **Does the user see the variant under a *different* name than the
   wire form?** Yes → introduce a shell-layer rename at the bridge
   boundary; keep the protocol/domain names firmware-/contract-true.
4. **Does the audience differ between protocol-byte-readers and
   community-JSON-readers?** Yes → keep the protocol enum
   firmware-named and put a community-named twin in `de1-domain`,
   with a mapping at the persistence boundary. (Today only
   `MachineState`/`MachineRequest` and the implicit
   `target_mix_temp`/`TempSensor` use this pattern.)

---

## 4. Existing inconsistencies to NOT fix

After running the catalog above, these *look* inconsistent but are
correct under the rule:

- **`TempSensor::Coffee/Water` (domain) vs `target_mix_temp` flag
  bit (protocol)** — both correct. The flag bit lives on the
  protocol struct `FrameFlags` and names what the wire bit toggles;
  the enum lives on the domain `ProfileStep` and names what the user
  chose. Mapping is one line in `to_shot_frame`.

- **`MachineState::HotWaterRinse` (protocol) vs
  `MachineRequest::Flush` (wasm)** — same pattern at a different
  layer. Protocol enum gets the firmware name (a contributor reading
  a `StateInfo` byte capture can grep `HotWaterRinse` here and in
  reaprime); wasm enum gets the user-facing name. Mapping is one
  line in `From<MachineRequest>`.

- **`Pump::Pressure/Flow` in both `de1-protocol` and `de1-domain`**
  — different enums that happen to share names. The protocol one
  isn't actually an enum yet (it's the `FrameFlags.flow_priority`
  bool); the domain one is the v2-JSON profile field. If we ever
  promote the protocol bool into a typed enum, naming will align
  organically (Pressure/Flow is both the firmware concept *and* the
  v2 contract).

---

## 5. Things to clean up if they ever surface

- The `Pump` situation (item above) is sleeping: the protocol layer
  represents pump-priority as a bool, the domain layer as an enum.
  A future contributor might want both at the same place. When that
  happens, the protocol bool becomes a Pump enum (mirror the domain
  enum) and the two stay in sync forever.

- If we ever ship a "use the firmware's name for everything" CLI
  inspector (a doctor tool that decodes a wire capture), it should
  consume the `de1-protocol` types directly — no domain or shell
  rename in its output.

---

## 6. Bottom line

The current Crema codebase is consistent. **Closing task #58
without code changes.** This doc is the record of the rule — future
PRs that introduce enums should pass the §3 checklist.
