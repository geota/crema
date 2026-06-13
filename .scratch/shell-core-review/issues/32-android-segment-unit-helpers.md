# 32 — Add `SegmentEdit` unit helpers + promote `toEdit`

- **Status:** ready-for-agent
- **Severity:** P2
- **Area:** Android phone + tablet — `ui/phone/PhoneProfileEditScreen.kt`, `ui/screens/ProfileEditScreen.kt`
- **Punchlist:** T4-07 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem
`isPressure → "bar"/"ml/s"` mapping reimplemented 5+ sites: `PhoneProfileEditScreen.kt:288-296,354-355,431,439`, `ProfileEditScreen.kt:428,436,454,466`. Also, `ProfileSegment.toEdit()` (`ProfileEditScreen.kt:514`, currently `private`) is inlined by phone (`PhoneProfileEditScreen.kt:85-104`).

## Fix
- Add `SegmentEdit.targetUnit()`, `SegmentEdit.limiterUnit()`, `SegmentEdit.exitUnit()` methods/properties to the `SegmentEdit` type, consolidating all `isPressure` → unit-string mappings
- Promote `ProfileSegment.toEdit()` from `private` to package-visible (or `internal`) in `ProfileEditScreen.kt` (or move it to the `profiles` package) so phone can call it directly instead of inlining

## Acceptance / Verify
- `grep -rn "isPressure.*bar\|isPressure.*ml" android/` returns 0 inline mappings (all replaced by the helpers)
- `toEdit()` is not inlined in `PhoneProfileEditScreen.kt:85-104`
- Phone and tablet profile editors show identical unit labels

## Touched files
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneProfileEditScreen.kt:288-296,354-355,431,439` — replace with `SegmentEdit.targetUnit()` etc.
- `android/app/src/main/java/coffee/crema/ui/phone/PhoneProfileEditScreen.kt:85-104` — replace with promoted `toEdit()` call
- `android/app/src/main/java/coffee/crema/ui/screens/ProfileEditScreen.kt:428,436,454,466` — replace with `SegmentEdit.targetUnit()` etc.
- `android/app/src/main/java/coffee/crema/ui/screens/ProfileEditScreen.kt:514` — promote `toEdit()` visibility
- `SegmentEdit` model class — add `targetUnit()`, `limiterUnit()`, `exitUnit()`

## Comments
<!-- triage + progress notes append below -->
