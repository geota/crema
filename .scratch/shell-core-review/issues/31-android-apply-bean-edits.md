# 31 — Extract `applyBeanEdits(bean, draft)` + share bag presets

- **Status:** ✅ done (2026-06-15)
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

### 2026-06-15 — done
New `beans/BeanEdits.kt` (package `coffee.crema.beans`, alongside `BeanFormat`
and the `Bean.isFrozen` extension it relies on):
- `applyBeanEdits(b: Bean, draft: BeanDraft): Bean` — the field-for-field save
  mapping (trim/ifBlank defaults, freeze-window history, UByte/Float coercions)
  both editors had inlined verbatim. `grep "fun applyBeanEdits"` → 1.
- `BeanDraft` — the ~26 editable form fields as plain values; each editor builds
  one from its local state and calls `vm.updateBean(id, roaster) { applyBeanEdits(it, draft) }`.
- `BAG_PRESETS = listOf(113, 227, 250, 340, 454, 1000)` — replaces the tablet's
  `BE_BAG_PRESETS` and the phone's `BAG_PRESETS`. `grep "BE_BAG_PRESETS"` → 0;
  single `BAG_PRESETS` definition.

The active-bean toggle + `onBack()` stay at each call site (unchanged). Dropped
the now-orphaned `core.BeanMix`/`core.BeanRoastType` imports both editors left
behind (the mix/roast-type selector options are hardcoded `SegOption`s, not enum
`entries`). Value-identical; `:app:compileDebugKotlin` + `:app:testDebugUnitTest`
green. Bean-save flow to be re-confirmed in the batched emulator pass.
