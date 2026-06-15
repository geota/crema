# 34 — Hoist the multi-channel spark chart

- **Status:** ✅ done (2026-06-15)
- **Severity:** P2
- **Area:** Android phone + tablet — `ui/phone/PhoneHistoryScreen.kt`, `ui/screens/HistoryScreen.kt`
- **Punchlist:** T4-09 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
`PhoneSpark` (`PhoneHistoryScreen.kt:419-448`) functionally identical to `SparkChart` (`HistoryScreen.kt:528-563`). Hoist with stroke/size params.

## Fix
Extract a shared `CremaSparkChart(...)` composable (e.g. in `ui/components/CremaComponents.kt` or a dedicated `ui/history/` package) with parameters for stroke width and size. Both `PhoneHistoryScreen` and `HistoryScreen` call the shared composable, passing their respective sizing values.

## Acceptance / Verify
- `PhoneSpark` and `SparkChart` are removed; `grep -rn "fun PhoneSpark\|fun SparkChart" android/` returns 0
- A single `CremaSparkChart` (or equivalent shared name) exists
- Spark charts render identically (modulo size params) on phone and tablet history screens

## Touched files
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneHistoryScreen.kt:419-448` — remove `PhoneSpark`, call shared composable
- `android/app/src/main/java/coffee/crema/ui/screens/HistoryScreen.kt:528-563` — remove `SparkChart`, call shared composable
- `android/app/src/main/java/coffee/crema/ui/components/CremaComponents.kt` (or new file) — new shared `CremaSparkChart` with stroke/size params

## Comments
<!-- triage + progress notes append below -->

### 2026-06-15 — done
Added `CremaSparkChart(samples, modifier, insetDp, tempStroke, weightStroke,
flowStroke, pressureStroke)` to `CremaComponents.kt` (next to `CremaStarRating` —
the shared compact list-row visuals). Byte-identical channel logic to the two
former privates; the only phone↔tablet deltas (inset 2dp/3dp + per-channel stroke
widths) are now params with tablet values as defaults.

Removed `SparkChart` (`HistoryScreen.kt`) and `PhoneSpark` (`PhoneHistoryScreen.kt`)
— `grep "fun PhoneSpark\|fun SparkChart"` → 0. Tablet calls with defaults; phone
passes `insetDp=3f, tempStroke=1.0f, weightStroke=1.2f, flowStroke=1.2f`
(pressure 1.8f unchanged). Also dropped the now-orphaned `Canvas/Path/Stroke/
StrokeCap/StrokeJoin/TelemetrySample` imports both screens had left behind
(`CanvasShotChart` lives in its own file, so neither screen draws Canvas anymore).
Value-identical hoist: `:app:compileDebugKotlin` + `:app:testDebugUnitTest` green;
spark renders on the history list of both emulators.
