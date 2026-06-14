# 48 — Pick a canonical History stats set (tablet vs phone)

- **Status:** done (2026-06-14)
- **Severity:** P3

> **Done (2026-06-14):** Shared pure `historyStats(shots)` in
> `history/ShotHistory.kt` (+ unit test) drives all three. Stats scoped to the
> filtered list. Tablet + web (PWA) show six tiles (Shots · Weight · Avg weight ·
> Avg ratio · Avg time · Avg rating); phone shows the three averages. Verified on
> both emulators + `npm run check`. Commit `e637438`.

> **Decision (2026-06-14):** Stats are scoped to the **current filter / time-range**
> (not all-time). Form factors need not be identical, but **tablet should match
> the web (PWA)**.
> - **Tablet + PWA (6 tiles):** Shots (count) · Weight (total g) · Avg weight ·
>   Avg ratio · Avg time · Avg rating.
> - **Phone (3 tiles):** Avg ratio · Avg time · Avg rating.
>
> Note this expands scope to the **web** History strip (align it to the tablet 6).
> "Weight" = total dispensed; "Avg weight" = per-shot average (both shown).
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
