# 11 — Wire Android profile-fingerprint upload-skip

- **Status:** deferred (brew-critical, not verifiable without on-device test — see Comments)
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

### 2026-06-13 — deferred (consciously, with rationale)

Not done this pass. It looks like a one-liner but isn't, and it touches
brew-critical flow I can't validate without an emulator + a real/simulated DE1
(no NDK in this env — Android can't even be compiled here).

**Why it's more than "check the fingerprint":** the upload in `startShot()` is the
*gated start* — `pendingBrew = true`, and the Espresso request only fires from
`applyEvent(ProfileUploadCompleted)` (`MainViewModel.kt:~2930`). If you skip the
upload, nothing requests Espresso → the shot silently never starts. A correct
skip must take an alternate path straight to the (delayed) Espresso request.

**Why it's also a correctness risk, not just an optimization:** the fingerprint
cache asserts "the DE1 already holds profile X." That's only true while the DE1
stays connected. The web shell handles this carefully — it caches the fingerprint
(`LAST_FINGERPRINT_KEY`), re-checks at connect (`ensureLoadedMatches`, on `ready`),
**and clears it on disconnect** (`app.svelte.ts:758`). Without the disconnect
invalidation, a skip after a power-cycle/reconnect would start a shot against a
stale or absent profile — wrong recipe, a brew-safety regression. So a faithful
Android port needs: (1) a cached last-uploaded fingerprint, (2) skip→Espresso
path, (3) invalidation on DE1 disconnect, and ideally (4) a connect-time
re-upload mirror of `ensureLoadedMatches`.

P3 pure optimization vs. risk of breaking shot-start, unverifiable here →
deferred until it can be exercised on an emulator. The FFI (`profileFingerprint`)
is already present, so it's ready to wire when that's possible.
