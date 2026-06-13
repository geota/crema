# 43 — One relative-time vocabulary per shell

- **Status:** ready-for-human
- **Severity:** P2
- **Area:** Web (`ShotRow.svelte`, `profiles/model.ts`), Android tablet (`HistoryScreen.kt`), Android phone (`PhoneHistoryScreen.kt`)
- **Punchlist:** T3-04 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
Five schemes — web ShotRow "min/hour/day/wk" (`ShotRow.svelte:61-71`), web
Profiles "m/h/d/w/mo/y" (`profiles/model.ts:230-250`), tablet `compactAgo` "Nm/Nh/Nd ago"
(`HistoryScreen.kt:507-515`), phone day-buckets "Today/Yesterday/This week/Earlier"
(`PhoneHistoryScreen.kt:288-293`). A 2h-old shot reads differently on each.

## Decision needed
Which single relative-time vocabulary should all shells use (e.g. 'Nm/Nh/Nd ago' vs day-buckets 'Today/Yesterday/This week')?

## Fix
One relative-time helper per shell; align the bucketing rule across both Android
form factors.

## Acceptance / Verify
- A 2h-old shot displays the same relative-time representation on web ShotRow, web Profiles, tablet History, and phone History.
- No more than one relative-time implementation exists per platform (web / Android).

## Touched files
- `web/src/lib/components/history/ShotRow.svelte:61-71` — web ShotRow relative-time impl
- `web/src/lib/profiles/model.ts:230-250` — web Profiles relative-time impl
- `android/app/src/main/java/coffee/crema/ui/screens/HistoryScreen.kt:507-515` — tablet `compactAgo` impl
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneHistoryScreen.kt:288-293` — phone day-bucket impl

## Comments
<!-- triage + progress notes append below -->
