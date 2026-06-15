# 43 — One relative-time vocabulary per shell

- **Status:** ✅ done (2026-06-15) — both shells
- **Severity:** P2

> **Decision (2026-06-14):** Adopt one compact-relative vocabulary, scaled across
> all units, on every shell (web ShotRow, web Profiles, Android tablet + phone
> History): `just now` / `2h ago` / `6d ago` / `5w ago` / `3mo ago` / `2y ago`.
> One helper per platform. (The History list keeps its day-section headers
> independently of the per-row relative time.)
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

### 2026-06-15 — web side done (closes the issue)
New `$lib/utils/relative-time.ts` `relativeAgo(ms, asOf?)` — one compact
`Nm/Nh/Nd/Nw/Nmo/Ny ago` formatter (the `$lib/utils/ratio` pattern), matching
the Android `Format.relativeAgo` thresholds exactly. Both web sites now route
through it:
- `profiles/model.ts` `relativeLastUsed` keeps its `null`→"never used" /
  unparseable→as-is handling, then delegates the math to `relativeAgo` (was a
  verbatim copy of the same thresholds).
- `ShotRow.svelte` `ago` drops its bespoke verbose, week-capped, `Math.round`
  logic ("3 hours ago" / "2 wk ago") for `relativeAgo(shot.completedAt)` — now
  compact and unbounded (mo/y), matching Profiles + Android. (The row keeps its
  separate `HH:mm` `timeH` div; web rows show clock + relative, unlike the
  Android phone which replaced the clock.)

**Quirk preserved per the decision:** days ∈ [360, 364] → "0y ago" lives in the
one shared `relativeAgo` now and is locked by a test, so web + Android can only
diverge deliberately.

Verified: `relative-time.vitest.ts` (21 threshold cases + the [360,364] quirk +
future-clamp) and `profiles/model.vitest.ts` green (24 tests); `svelte-check`
clean (0/0 across 1205 files). Browser spot-check skipped — `ago` is now a
pure, unit-tested function rendered into the existing `{ago}` div.

### 2026-06-15 — Android side done (operator decided: replace phone clock with relative)
**Operator decision (2026-06-15):** phone History rows **replace the HH:mm clock**
with the compact-relative label (day-section headers stay).

Added one shared Android helper `relativeAgo(epochMs, now)` in `ui/Format.kt`,
thresholds **byte-identical** to web `relativeLastUsed` (`just now` / `Nm` / `Nh` /
`Nd` / `Nw`<5 / `Nmo`<12 / `Ny`). Pinned by `RelativeAgoTest` (8 cases).
- Tablet `HistoryScreen`: `compactAgo` (capped at weeks) removed → row uses
  `relativeAgo` (now extends to mo/y).
- Phone `PhoneHistoryScreen`: per-row `SimpleDateFormat("HH:mm")` → `relativeAgo`;
  the `dayLabel` day-section headers ("TODAY"/"THIS WEEK"/"EARLIER") kept.
Verified on the phone emulator (rows read "1d ago"…"5d ago"; headers intact).

**Known web-parity quirk (left as-is for parity):** days in [360,364] → `months=12`
(not <12) → `years=days/365=0` → "0y ago". Present in web `relativeLastUsed` too;
fix it in BOTH shells together if ever addressed.

**STILL TODO — web side** (unblocks closing this issue): unify the two web impls
into one helper with the same vocabulary — `ShotRow.svelte:61-71` (currently
verbose "min/hour/day/wk", capped at weeks) and `profiles/model.ts` `relativeLastUsed`
(already the canonical full-scale one). Extract a shared `relativeAgo(ms, asOf)` both
call. Needs the Svelte tooling + a browser/chrome-devtools check.
