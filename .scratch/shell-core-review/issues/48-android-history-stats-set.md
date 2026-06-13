# 48 — Pick a canonical History stats set (tablet vs phone)

- **Status:** ready-for-human
- **Severity:** P3
- **Area:** Android tablet (`ui/screens/HistoryScreen.kt`), Android phone (`ui/phone/PhoneHistoryScreen.kt`)
- **Punchlist:** T3-11 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
Tablet shows Avg yield / Avg time / Avg rating (`HistoryScreen.kt:391-393`);
phone shows Today / Avg ratio / Avg rating (`PhoneHistoryScreen.kt:310-312`).

## Decision needed
Which stat set is canonical for the History summary strip — tablet's (Avg yield / Avg time / Avg rating) or phone's (Today / Avg ratio / Avg rating)?

## Fix
Once a canonical set is decided, align both form factors to display the same stats in the History summary strip.

## Acceptance / Verify
- Tablet and phone History screens show the same stat set in the summary strip.
- The chosen stats are computed consistently from the same data source.

## Touched files
- `android/app/src/main/java/coffee/crema/ui/screens/HistoryScreen.kt:391-393` — tablet history stats strip
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneHistoryScreen.kt:310-312` — phone history stats strip

## Comments
<!-- triage + progress notes append below -->
