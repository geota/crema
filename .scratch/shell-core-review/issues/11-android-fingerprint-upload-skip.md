# 11 — Wire Android profile-fingerprint upload-skip

- **Status:** ready-for-agent
- **Severity:** P3
- **Area:** Android
- **Punchlist:** T1-11 — `../PUNCHLIST.md`
- **Depends on:** none

## Problem

`profile_fingerprint` is already on **both** bindings, but Android "v1 always uploads (no fingerprint-skip)" (`MainViewModel.kt:1255`). No drift — just a missing optimization that web has.

## Fix

Compute the fingerprint and skip re-upload of an unchanged profile.

## Acceptance / Verify

Uploading the same profile twice results in only one actual upload; the second call is skipped because the fingerprint matches.

## Touched files

- `android/app/src/main/java/coffee/crema/ui/MainViewModel.kt:1255` — add fingerprint check before upload

## Comments
<!-- triage + progress notes append below -->
