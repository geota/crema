# 31 — Extract `applyBeanEdits(bean, draft)` + share bag presets

- **Status:** ready-for-agent
- **Severity:** P2
- **Area:** Android phone + tablet — `ui/phone/PhoneBeanEditScreen.kt`, `ui/screens/BeanEditScreen.kt`
- **Punchlist:** T4-06 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
Bean editor `save{}` lambda field-for-field identical `PhoneBeanEditScreen.kt:92-140` ≡ `BeanEditScreen.kt:140-194`; `BAG_PRESETS` (`:39`) duplicates `BE_BAG_PRESETS`.

## Fix
Extract a shared `applyBeanEdits(bean, draft): Bean` pure function (or extension) in the `beans` package that applies the draft fields onto the bean. Consolidate `BAG_PRESETS` and `BE_BAG_PRESETS` into a single constant. Both phone and tablet bean editors call the shared function and reference the single constant.

## Acceptance / Verify
- Only one `applyBeanEdits` implementation exists in the codebase
- Only one bag-presets constant exists; `grep -rn "BAG_PRESETS\|BE_BAG_PRESETS" android/` shows a single definition
- Phone and tablet bean save behavior is identical

## Touched files
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneBeanEditScreen.kt:92-140` — replace save lambda with `applyBeanEdits` call
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneBeanEditScreen.kt:39` — remove duplicate `BAG_PRESETS`
- `android/app/src/main/java/coffee/crema/ui/screens/BeanEditScreen.kt:140-194` — replace save lambda with `applyBeanEdits` call
- beans package — new `applyBeanEdits` function + consolidated `BAG_PRESETS`

## Comments
<!-- triage + progress notes append below -->
