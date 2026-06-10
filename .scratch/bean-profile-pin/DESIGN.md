# Bean → Profile preference ("pin a bean to a profile")

Status: SHIPPED v1 (`1bcb917` web/core, `f798bb7` android) · 2026-06-10
Implemented per the user's refinements: triggers = Beans page + Brew page
activation; the Brew bean dropdown shows a link glyph on linked beans.
v1 ships WITHOUT toast-undo (neither shell's toast/snackbar supports
actions yet — recovery is one manual profile pick) and without the
Brew-card mismatch affordance (open question #3, not requested).
Scope: both shells (web PWA + Android tablet).

## What it is

A bean can name a **preferred profile**. When the user activates that bean
(makes it the brew-page bean), the preferred profile auto-loads as the active
profile. One toast with Undo; no other enforcement.

## Naming

NOT "pin" — `pinned` is already taken in both shells (a profile's
Favorites-strip flag: web `overrides[id].pinned`, Android `togglePinProfile`).
Overloading it would confuse both the code and the UI copy.

- Field: `preferredProfileId: string | null` on Bean (both shells).
- UI label: **"Preferred profile"** (row in the bean editor), chip copy
  "Prefers <name>".

## Direction: one-way, bean → profile

Agreed with the instinct in the request, for reasons beyond churn:

1. **The bean is physical reality; the profile is a recipe.** Declaring
   "this bag is in the hopper" should pull up the right recipe. Picking a
   recipe must NOT silently re-declare what coffee you're using — software
   can't change what's in the hopper, so profile→bean auto-load would lie.
2. **Cardinality.** One profile serves many beans (every light-roast bag →
   the same Blooming profile). A bean has at most one go-to recipe. The
   one-to-many direction stores cleanly on the many side (the bean).
3. **Churn**, as noted: bags die in ~3 weeks; profiles live for months. Pins
   on the short-lived object keep graveyard pointers rare and harmless.

**Soft inverse for visibility (cheap, later):** the profile card/editor can
show a read-only "Preferred by N beans" line computed by scanning the bean
library. No stored back-pointer, no inverse behavior, no sync issues. Not in
v1 scope, listed as a follow-up.

## Trigger semantics — exactly when does auto-load fire?

Single rule: **fires only on an explicit user act of bean activation, and
only at that moment.** No continuous enforcement, no boot-time firing.

| Site | Fires? | Why |
| --- | --- | --- |
| Beans page "Use this bean" (web `beans/+page` → `setActiveBean`) | YES | explicit |
| Brew bean strip / bean picker select (BrewDashboard ×2) | YES | explicit |
| Bean editor "active" toggle / save-with-activate (BeanEditPage) | YES | explicit |
| Re-buy / duplicate-bag flow (BeanContextCard) | YES (new bag inherits the pin → fires) | same coffee, same recipe — the point of the feature |
| `quickAdd` (brew inline "+ Add bean") | n/a | fresh bean has no preference yet |
| App boot restoring `activeBeanId` | **NO** | boot already restores the last active *profile* independently; firing here would fight the user's last explicit profile choice |
| Bean-sync pull mutating beans | **NO** | not a user act |
| History "Load on Brew" | n/a | verified: it sets only the profile, never the bean — no conflict path |
| Clearing the bean (`setActiveBean(null)`) | NO | nothing to prefer |

Implementation shape: do NOT bury the hook inside `setActiveBean` (it gets
called by restore paths); add an explicit wrapper used by the user-act call
sites — web: `activateBean(id)` on the bean store that calls
`setActiveBean(id)` then resolves the preference; Android: the existing
`vm.setActiveBean(id)` user path gains the same step (its boot/restore path
sets state directly and is untouched).

### Auto-load behavior at fire time

1. Resolve `preferredProfileId` against the profile store. Missing → do
   nothing silently EXCEPT a one-line toast "Preferred profile no longer
   exists" the first time (and the bean editor shows "(missing)").
2. Already active → no-op (no toast spam).
3. `shotInProgress` → **skip with toast** "Profile switch skipped mid-shot".
   Rationale: web `setActive` eagerly pushes recipe targets to the core and
   uploads to the DE1; doing that mid-extraction is the one genuinely unsafe
   moment. Skipping (not deferring) keeps the model predictable.
4. Otherwise `profiles.setActive(preferredId)` (web) /
   `vm.setActiveProfile(preferredId)` (Android) — both are the existing,
   side-effect-complete entry points (core latch + eager DE1 upload on web;
   prefs persist + Quick-Controls reseed on Android).
5. Toast: `Loaded "Blooming Espresso" — preferred by Geometry` with an
   **Undo** action that restores the previously-active profile id. Undo is
   what makes silent automation acceptable.

### What auto-load deliberately does NOT do

- Never re-fires when the user manually switches profiles afterwards. The
  preference is an activation-moment default, not a lock.
- No "you brewed with a different profile — update the preference?" nag.
  (Rejected: turns a convenience into a scold. The bean editor is the one
  place the preference changes.)
- No profile→bean auto-load (see Direction).

## Data model + persistence

**Web** (`web/src/lib/bean/model.ts`):
```ts
/** The profile auto-loaded when this bean is activated; null = none.
 *  Dangling ids are tolerated everywhere (profile deletion, import). */
preferredProfileId: string | null;
```
- `blankBean()` → `null`. localStorage `crema.beans.v1` is additive-tolerant
  (older records lack the key → `?? null` at read, same as other fields).
- Verified safe vs Visualizer bean-sync: pull "update" actions patch named
  fields only (`library.updateBean(localId, {…})`), so a local-only field
  survives round-trips. Guard in review: keep it patch-style; never
  whole-object replace from wire.
- Export/import (bean library JSON): field rides along. On import to another
  device the id may not resolve (custom profiles are device-local) →
  dangling-tolerant per the rules above. Built-in profile ids are shared
  across shells, so preferences on built-ins DO transfer meaningfully.

**Android** (`beans/BeanLibrary.kt`):
```kotlin
/** Profile auto-loaded when this bean becomes active; null = none. */
val preferredProfileId: String? = null,
```
- `@Serializable` additive default → old `beanLibrary.json` loads cleanly.
- Seed converter (`seed/gen_seed.py` + `web_seed.py`) untouched (absent key
  = null both sides).

**Profile identity:** built-ins have stable ids; customs keep their id
through edits; duplicate-profile mints a new id (pins correctly stay on the
original). Renames don't break anything (we store the id, show the live
name).

## Deletion cascade

When a profile is deleted and ≥1 bean prefers it:
- Delete-confirm body gains one line: "2 beans prefer this profile; their
  preference will be cleared."
- On confirm, clear those beans' `preferredProfileId` (single library write).
- Belt-and-braces: every read path still tolerates a dangling id (covers
  imports and any historical state).
Hiding a BUILT-IN profile (web `hiddenBuiltins`) does NOT clear preferences —
the profile still exists and `setActive` works; auto-load proceeds.

## UX surfaces

1. **Bean editor** — "Preferred profile" row (select listing profiles +
   "None"; shows "(missing)" + Clear when dangling). Both shells, in the
   Brew-ish section of the editor.
2. **Bean card** (library grid + brew picker) — a small chip/line
   "Prefers <profile name>" when set. Subtle; not a new column.
3. **Brew bean context card** — if the active bean has a preference and the
   ACTIVE profile differs (user manually switched), show a quiet inline
   affordance: "Bean prefers <name> — Load". Zero-cost discoverability of
   the mismatch without nagging. (v1-optional; cheap on both shells.)
4. **Toast with Undo** on every auto-load (see above).
5. **Quick pin action**: bean card overflow gains "Prefer current profile"
   (sets the preference to the currently-active profile) — the fastest way
   to create the link from real usage. Both shells.

## Edge cases checklist

- Pinned profile deleted → cascade-clear + tolerant reads. ✓ above
- Dangling id from import / cross-device → tolerate + editor "(missing)". ✓
- Mid-shot activation → skip + toast (DE1 upload safety). ✓
- Bean archived/frozen/defrosted → preference is inert data; untouched.
- Duplicate-bag / re-buy → copy the preference to the new bag. ✓
- Bean-sync pull → field survives (patch-style updates); Visualizer has no
  such field so it never round-trips remotely — device-local by design.
- Manual profile change after auto-load → respected; no re-fire. ✓
- Preference set to the already-active profile → activation no-ops quietly.
- Undo after auto-load → restores prior profile id (and its DE1 upload via
  the same setActive path).
- Erase-all / factory reset → beans go with the library; nothing special.

## Implementation sketch (when approved)

**Web** (~5 files): `bean/model.ts` (+field, blankBean), `bean/store.svelte.ts`
(`activateBean()` wrapper + duplicate-copy + cascade helper), call-site swap
(`beans/+page`, `BrewDashboard`, `BeanEditPage`, `BeanContextCard` →
`activateBean`), `profiles/store.svelte.ts` or the delete flow (cascade
hook), `BeanEditPage.svelte` (editor row), bean card chip, toast+undo.
Tests: store unit tests for activate/dangle/cascade.

**Android** (~4 files): `BeanLibrary.kt` (+field), `MainViewModel.kt`
(`setActiveBean` user-path hook + cascade in profile delete + undo state),
bean editor sheet (+select row), bean card chip + overflow action,
snackbar w/ Undo action (SnackbarHost already supports actions? — verify;
else a two-line snackbar with a "Load previous" follow-up).

Estimated: a day across both shells including verification.

## Open questions for the user

1. Name OK? ("Preferred profile" — avoiding the taken "pin".)
2. Mid-shot rule: skip-with-toast (proposed) vs defer-until-idle.
3. Is the Brew-card mismatch affordance (UX #3) in or out of v1?
