# 16 — Fix Android phone wiring regressions vs tablet

- **Status:** ready-for-agent
- **Severity:** P1
- **Area:** Android phone — `ui/phone/PhoneSettingsScreen.kt`, `ui/phone/PhoneBeansScreen.kt`, `ui/phone/PhoneBeanEditScreen.kt`, `ui/phone/PhoneBrewScreen.kt`
- **Punchlist:** T2-05 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
4 distinct regressions in the phone shell vs tablet:

1. Terms / Privacy rows have empty `{}` control lambda (`ui/phone/PhoneSettingsScreen.kt:915-916`)
   where tablet renders a readout (`SettingsScreen.kt:889-890`).
2. "All" beans filter returns `else -> true` → shows archived
   (`PhoneBeansScreen.kt:88`) vs tablet `b.archivedAt == null` (`BeansScreen.kt:141`).
3. Restock stepper steps 1.0g / max 5000 (`PhoneBeanEditScreen.kt:262,304`) vs tablet
   5.0g / max 2000 (`BeanEditScreen.kt:311`).
4. Steam & Tank brew tiles pass `ok = false` unconditionally
   (`PhoneBrewScreen.kt:711`) so they never show ready tint.

## Fix
Align each phone behavior to the tablet's (or to the more-correct of the two);
derive ok-state for the Steam/Tank tiles or drop the param.

## Acceptance / Verify
- Terms/Privacy rows on phone render a readout matching tablet behavior.
- "All" beans filter on phone excludes archived beans (matches `b.archivedAt == null`).
- Restock stepper on phone steps 5.0g with max 2000 (matching tablet).
- Steam & Tank brew tiles on phone show the ready tint when the machine is ready.

## Touched files
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneSettingsScreen.kt:915-916` — fix empty control lambdas
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneBeansScreen.kt:88` — fix "All" filter predicate
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneBeanEditScreen.kt:262,304` — fix restock stepper step/max
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneBrewScreen.kt:711` — fix unconditional `ok = false`

## Comments
<!-- triage + progress notes append below -->
