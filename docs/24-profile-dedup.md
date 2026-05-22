# 24 — Crema-native profile dedup via content-hash

Plan for storing profile content-hashes outside the `Profile` model
(IndexedDB on the web, on the Android shell once it lands) so Crema
can detect "this is the same profile" across import / save / share
without locking the profile schema into permanent canonicalization
rules.

Companion to **docs/22 §4.4** (the audit-flagged interop question).

## 0. Decision recap

**User direction**: defer reaprime hash interop, **but** if we do
adopt a hash format, only diverge if there's a good reason. Reasoning:
canonicalization is permanent regardless of which format we pick (our
own past IDs need to keep matching too), so adopting reaprime's gets
us interop for free with the same cost.

**This doc**: adopt reaprime's hash format as the basis, but store
hashes outside the Profile struct, indexed in an IndexedDB store
separate from the profile-store JSON. That decoupling buys two
properties:

1. The `Profile` Rust struct's serialization shape can still evolve
   (new fields are additive; hash inputs are a fixed subset of
   "execution" fields).
2. Adding hash support is non-breaking for existing stored profiles —
   they all get a fresh hash on next read.

## 1. Hash format

Match reaprime's spec exactly (no divergence; the future cross-app
interop scenario is free):

```
hash = 'profile:' + hex(sha256(canonical_json(execution_fields)))[..20]
```

Where `execution_fields` is a fixed, deterministically-ordered
sub-document of the profile JSON, including only the fields that
affect what the DE1 actually does:

- `version: "2"` — always the string `"2"`, never numeric (matches
  reaprime).
- `beverage_type` — defaults to `"espresso"` if absent on the source.
- `tank_temperature` — defaults to `0` if absent.
- `steps` — full normalised array; each step emits **all** schema
  fields including `weight: null` slots and `exit: null` slots for
  steps without those fields.

Excluded (do not affect execution): `title`, `notes`, `author`, any
Crema-specific UI fields (preferred display unit, etc.).

Canonical-JSON discipline (reaprime):

- Key-sorted recursively (every object emits keys in lexicographic
  order).
- Numbers as JSON numbers, not strings. No NaN / Infinity.
- Strings unmodified (no trim, no case fold).
- Booleans / null as standalone JSON tokens.
- Arrays in document order (not sorted — the step order is
  semantically meaningful).

The first reaprime test fixtures' canonical-JSON outputs become the
Crema golden tests — any drift caught at PR time.

## 2. Storage

### 2.1 Web

IndexedDB store `crema-profile-hashes`:

```ts
type ProfileHashEntry = {
  profileLocalId: string;   // Crema's local UUID (existing primary key)
  hash: string;             // 'profile:af3c2b91…' (24 chars)
  hashedAt: number;         // performance.now() at compute time
  schemaVersion: number;    // bump when canonical-JSON rules change
};
```

One entry per local profile. Updated whenever the Profile saves and
the structural fields change.

### 2.2 Android

Same shape via Room (or whatever the shell uses for the local DB)
once the Android shell catches up. The web is the immediate target.

## 3. Compute flow

```
on profile-save:
  1. Persist the Profile as today (Crema's existing path).
  2. Compute canonical JSON over the execution-fields subset.
  3. sha256 + truncate.
  4. Upsert `{profileLocalId, hash, hashedAt, schemaVersion}` in
     IndexedDB.

on profile-import (any source — v2 JSON file, paste, future
share-URL):
  1. Compute the hash of the incoming JSON before persisting.
  2. Query IndexedDB for any local profile with the same hash.
  3. If match found → "You already have this profile" toast → offer
     to switch to the existing one instead of duplicate-saving.
  4. If no match → persist + hash.

on profile-delete:
  - Remove the IndexedDB entry too.
```

## 4. Module shape (Rust)

```
core/de1-domain/src/profile_hash.rs
├─ canonical_json(profile: &Profile) -> String
│    Emits the execution-fields-only canonical JSON. Pure function;
│    no IO. Driven by an explicit field list, not by serde — so
│    adding a Profile field doesn't accidentally change hashes.
├─ profile_hash(profile: &Profile) -> String
│    'profile:' + hex(sha256(canonical_json(profile)))[..20]
└─ HASH_SCHEMA_VERSION: u32
     Bump if the canonical-JSON rules ever change (they won't — but
     the field makes the contract explicit).
```

Web shell calls into the wasm crate's `profileHash(profile)` to
compute on save. The IndexedDB wrapping lives in
`web/src/lib/profiles/hash-store.ts`.

## 5. Tests

- **Golden vectors from reaprime's fixtures** — port the 20+ test
  cases from reaprime's `profile_test.dart` ProfileHash group as
  `.json` fixtures in `core/de1-domain/tests/fixtures/profile_hash/`.
- **Field-order invariance** — same fields, different source-JSON
  key order → same hash.
- **Whitespace invariance** — same fields, varying source-JSON
  whitespace → same hash.
- **Unrelated-field invariance** — same execution fields, different
  `title` / `notes` / Crema UI fields → same hash.
- **Step-order sensitivity** — swap two steps → different hash.
- **`weight: null` round-trip** — explicit null on a `weight`-less
  step ≡ absent `weight` in input → both produce the same hash.

## 6. Migration

Existing stored profiles get hashed on next read (lazy backfill). No
schema migration required because the hash store is brand new.

The Profile struct's `serde::Serialize` shape is unchanged.

## 7. Non-goals

- **Cross-device sync.** This doc is local dedup only. A future
  share-URL or Visualizer integration can use the hash as the join
  key, but neither is in scope here.
- **Editing profiles re-saves with a new hash.** That's correct
  behaviour — an edit produces a semantically-distinct profile.
  Crema's local UUID stays stable across edits so user references
  (active profile, favorites) don't break.
- **No hash → version migration story.** If we ever need to change
  the canonical-JSON rules, that's a Crema-wide migration with
  user-visible rehash. Bump `HASH_SCHEMA_VERSION` and rehash on
  next read; the IndexedDB query becomes "hash equals OR
  schemaVersion < current AND content matches after rehash."

## 8. Effort

~ 1.5 dev-days:

- Rust `profile_hash` module + canonical-JSON + tests — 0.5 day
- Wasm export + TS wrapper — 0.5 hour
- IndexedDB hash store + integration with the profile-save path — 0.25 day
- Import-dedup UX (toast + "switch to existing" link) — 0.25 day
- Golden-vector port from reaprime — 0.25 day

## 9. Cross-references

- **docs/22 §4.4** — original audit finding.
- **docs/06** (profile model) — if the Profile struct ever gains a
  field, decide explicitly whether it goes into the canonical-JSON
  subset (most won't).
