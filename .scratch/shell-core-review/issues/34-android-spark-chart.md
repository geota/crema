# 34 — Hoist the multi-channel spark chart

- **Status:** ready-for-agent
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
