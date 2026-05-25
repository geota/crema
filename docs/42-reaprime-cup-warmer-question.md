# Bengle cup-warmer (MatSetPoint) wire encoding — Crema/reaprime divergence

Open question for reaprime maintainers: which encoding does the Bengle
firmware expect on MMR `0x803874` (`MatSetPoint`) — **raw °C** (legacy
TCL) or **°C × 10** (reaprime's `BengleMmr.matSetPoint`)?

## Declarations

**reaprime** —
[`lib/src/models/device/impl/bengle/bengle_mmr.dart:12-21`](https://github.com/reaprime/reaprime/blob/main/lib/src/models/device/impl/bengle/bengle_mmr.dart#L12-L21):

```dart
matSetPoint(0x00803874, 4, MmrValueKind.scaledFloat, 'MatSetPoint',
            min: 0, max: 800, readScale: 0.1, writeScale: 10.0),
```

Wire = `°C × 10` as 4-byte LE int, range 0..800.

**legacy de1plus TCL** —
[`de1plus/de1_comms.tcl:1184`](https://github.com/decentespresso/de1app/blob/main/de1plus/de1_comms.tcl#L1184):

```tcl
mmr_write "set_cupwarmer_temperature" "803874" "04" \
    [zero_pad [long_to_little_endian_hex $temp] 2]
```

Wire = raw °C as LE int, no scaling.

**Crema** — `core/de1-app/src/lib.rs::set_cup_warmer_temperature` follows
the TCL convention (raw °C u8).

## Wire bytes for a 60 °C target

| Path     | Bytes (LE)               | FW reads as |
| -------- | ------------------------ | ----------- |
| Crema    | `[60]`                   | 60          |
| TCL      | `[60, 0]`                | 60          |
| reaprime | `[88, 02, 0, 0]` (= 600) | 600         |

## Why it matters

If reaprime is right, Crema/TCL silently target **10× too cold** — 60 °C
lands as 6.0 °C and the plate never warms. If legacy is right, reaprime
targets **10× too hot** — 60 °C → 600 °C, which the firmware almost
certainly refuses or clamps.

No published Decent spec excerpt for `MatSetPoint` is on hand.
`bengle_mmr.dart`'s comment says "raw IEEE-754 float32" while `kind` is
`scaledFloat writeScale:10.0` — those aren't internally consistent,
which is part of what raised the question.

## Resolution

Anyone with a Bengle can run both apps, set the target to e.g. 50 °C, and
observe whether the plate stabilizes around **5 °C**, **50 °C**, or a
clamped value. That pins the correct encoding; we'll align Crema once
known.
