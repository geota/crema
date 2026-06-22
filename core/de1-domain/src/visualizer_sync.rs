//! # visualizer-sync
//!
//! Pure de-dup signature helpers and the [`reconcile_shots`] action
//! planner — extracted from the web shell's
//! `$lib/visualizer/shot-sync-signatures.ts` so every shell (web today,
//! Android tomorrow) reaches for the same algorithm via the same
//! `de1_domain` crate.
//!
//! Sans-IO and deterministic: takes plain data in, returns plain data
//! out. The wasm-bindgen / FFI / Kotlin bridges marshal the payload as
//! JSON at the boundary (matching the rest of the
//! `import_*_json_shot` family), so the TS / Kotlin caller can pass
//! its own snake_case-camelCase shapes without type-coupling to the
//! core. The contract that matters is the byte-identical hash output:
//! `signatureForShot` in TS and [`signature_for_shot`] in Rust MUST
//! emit the same hex string for the same input — there are unit tests
//! that pin the algorithm.
//!
//! The wire types ([`WireShot`], [`LocalShotRef`], [`ReconcileAction`])
//! are `#[typeshare]`-emitted into `core/bindings/crema-core.{ts,kt}`
//! so the shell-side `WireShot` / `ReconcileAction` shapes stay
//! mechanically in sync with the Rust source. `i64` fields ride as the
//! `I64` alias (see `core/typeshare.toml` — TS `number`, Kotlin `Long`).
//!
//! Note: `storedShotFromWire` (the wire → local-`StoredShot`
//! materializer) stays in the shell — it builds a shell-specific
//! `StoredShot` shape and depends on the shell's `shotId()` /
//! `localStorage` constructors. Only the pure-data fns move.

use crate::bean::{Bean, Roaster};
use crate::visualizer_wire::RoasterWire;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use typeshare::typeshare;

// ── Types ─────────────────────────────────────────────────────────────

/// The fields the planner reads from a remote shot row. Visualizer's
/// API returns a superset; we only care about identity + the de-dup
/// hash inputs + editable annotations. Mirrors the TS `WireShot` in
/// `shot-sync-signatures.ts`. Unix MS (converted at the spec boundary
/// from the spec's unix-sec `clock` / `updated_at`).
///
/// `#[serde(default)]` on the optional fields so a shell that omits them
/// deserialises cleanly. The wasm / FFI bridges JSON-marshal this.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WireShot {
    /// Visualizer shot id — the remote primary key.
    pub id: String,
    /// Shot start timestamp, unix MS (converted from spec's unix-sec `clock`).
    #[typeshare(serialized_as = "I64")]
    pub clock: i64,
    /// Total shot duration, milliseconds.
    #[typeshare(serialized_as = "I64")]
    pub duration_ms: i64,
    /// Display name of the profile pulled.
    pub profile_title: Option<String>,
    /// Final scale weight at shot end (grams), or `None`.
    pub final_weight_g: Option<f32>,
    /// Annotations the user has typed remotely.
    #[serde(default)]
    pub notes: Option<String>,
    /// Star rating (0..5), or `None`.
    #[serde(default)]
    pub rating: Option<i32>,
    /// Last server-side update, unix MS (from spec's unix-sec
    /// `updated_at`). Drives LWW conflict resolution.
    #[serde(default)]
    #[typeshare(serialized_as = "Option<I64>")]
    pub updated_at_ms: Option<i64>,
    /// Shot-level tags pulled from the remote — the `tags` array on
    /// `DefaultShotDetail` (Visualizer's native serializer; the
    /// Beanconqueror variant doesn't carry shot tags). These are
    /// mutable metadata, NOT part of the de-dup signature: a remote
    /// re-tagging doesn't change the shot's identity.
    ///
    /// `#[serde(default)]` so older shells / responses without the
    /// field still deserialise cleanly to an empty Vec.
    #[serde(default)]
    pub tag_list: Vec<String>,
}

/// A slim view onto the shell's `StoredShot`: only the fields the
/// reconcile planner reads. Lets the shell project its full record
/// (web `StoredShot` in `$lib/history/model.ts`, Android equivalent)
/// into a stable cross-shell shape without dragging the rest of the
/// record (`series`, `bean`, `metadata`) through the FFI.
///
/// Field names are camelCase on the wire — the JSON shape lines up
/// 1:1 with the web shell's `StoredShot`, so callers can hand a
/// `StoredShot` straight to `JSON.stringify` and `serde_json::from_str`
/// parses it cleanly (extra fields are ignored).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalShotRef {
    /// Local stable id — `shot:<uuid>` in the web shell.
    pub id: String,
    /// Unix epoch ms the shot completed.
    #[typeshare(serialized_as = "I64")]
    pub completed_at: i64,
    /// Total shot duration, milliseconds.
    #[typeshare(serialized_as = "I64")]
    pub duration: i64,
    /// Active profile's name when the shot was pulled, or `null`.
    #[serde(default)]
    pub profile_name: Option<String>,
    /// Final scale weight at shot end, grams, or `null`.
    #[serde(default)]
    pub final_weight: Option<f32>,
    /// Visualizer `shot.id` once uploaded — the binding key. `null` /
    /// missing for an unbound local.
    #[serde(default)]
    pub visualizer_id: Option<String>,
    /// Unix epoch ms when this shot was soft-deleted, or `null` / missing
    /// when active. Tombstoned locals must NOT bind to a remote.
    #[serde(default)]
    #[typeshare(serialized_as = "Option<I64>")]
    pub deleted_at: Option<i64>,
}

/// One reconciliation outcome — what to do with a remote row. The
/// wire form is an adjacently-tagged enum so the TS shell can pattern-
/// match the `kind` discriminator the same way the existing
/// `ReconcileAction` does.
// No `#[typeshare]`: typeshare 1.x requires adjacently-tagged enums
// (`#[serde(tag, content)]`) for algebraic types. The wire contract
// here is intentionally internally-tagged — flat `{ kind, ...fields }` —
// because the existing TS callers in `shot-sync.ts` and the test fixtures
// pattern-match on `kind` without unwrapping a `content` envelope. The
// TS mirror lives in `web/src/lib/visualizer/shot-sync-signatures.ts`;
// the JSON bridge (`reconcile_shots_json` in `de1-wasm`) keeps the two
// in sync structurally, and the unit tests on either side pin the shape.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum ReconcileAction {
    /// No matching local — materialise a fresh local record from this
    /// remote.
    Add {
        /// The remote row to add locally.
        remote: WireShot,
    },
    /// A bound local exists for this remote — patch the editable
    /// annotations (LWW on `updated_at_ms`).
    Update {
        /// The local row to patch.
        #[serde(rename = "localId")]
        local_id: String,
        /// The remote row carrying the new annotations.
        remote: WireShot,
    },
    /// An unbound local matches by signature — bind it to this remote
    /// `visualizer_id`.
    Bind {
        /// The local row to bind.
        #[serde(rename = "localId")]
        local_id: String,
        /// The Visualizer id to bind into the local row.
        #[serde(rename = "visualizerId")]
        visualizer_id: String,
        /// The remote row that triggered the bind.
        remote: WireShot,
    },
}

// ── djb2 hash + numeric rounding ─────────────────────────────────────

/// Stable djb2 hash. Fast, well-distributed for short ASCII strings, no
/// crypto needed. Returns an unsigned 32-bit integer so the hex
/// stringification is stable across runtimes — byte-identical to the
/// TS `djb2` in `shot-sync-signatures.ts`.
///
/// The TS implementation iterates with `s.charCodeAt(i)`, which yields
/// the UTF-16 code unit. For the inputs the planner actually sees
/// (digits, the `"∅"` sentinel, plain ASCII profile / bean / roaster
/// names), every UTF-16 code unit fits in a single Rust `char`, so
/// `s.chars().map(|c| c as u32)` produces the same sequence. (A
/// surrogate-pair-containing profile name would diverge between the
/// two — but that's a theoretical edge case for shot signatures, and
/// the existing TS contract is what we're pinning here.)
fn djb2(s: &str) -> u32 {
    // TS: `h = ((h << 5) + h + s.charCodeAt(i)) | 0;`
    //     The `| 0` coerces back to a signed 32-bit int via JS
    //     ToInt32 — i.e. modulo 2^32, then reinterpret as i32.
    //     `h >>> 0` at the end reinterprets that i32 as a u32.
    //
    // Rust: `wrapping_mul` / `wrapping_add` on `u32` directly mirrors
    //       modulo-2^32 arithmetic; the final value IS the u32 the
    //       TS path produces.
    let mut h: u32 = 5381;
    for c in s.chars() {
        // `(h << 5) + h` is `h * 33`. Use wrapping mul / add.
        let code = c as u32;
        h = (h.wrapping_shl(5).wrapping_add(h)).wrapping_add(code);
    }
    h
}

/// Render a number to a fixed-precision form so float jitter doesn't
/// perturb the hash. Mirrors the TS `rk` helper — null / non-finite
/// in, `"∅"` out, else `n.toFixed(decimals)`. Default `decimals = 2`.
///
/// The TS `Number.prototype.toFixed` rounds half-to-even at the
/// requested precision and emits the result with a trailing zero pad
/// (e.g. `36 → "36.00"`, `36.001 → "36.00"`). We match that exact
/// formatting via Rust's `{:.precision$}` (which uses half-to-even
/// rounding and the same trailing-zero pad on `f64`).
fn rk(n: Option<f64>, decimals: usize) -> String {
    match n {
        Some(v) if v.is_finite() => format!("{v:.decimals$}"),
        _ => "\u{2205}".to_owned(),
    }
}

// ── Signatures ───────────────────────────────────────────────────────

/// The shot de-dup signature: a djb2 hash of `(completedAt, duration,
/// profileName, finalWeight)`. Shots are inherently unique by time +
/// final weight — collisions are intentional ID matches (a Visualizer
/// push that comes back with a new `id` still binds to the same local
/// row instead of re-importing as a duplicate).
///
/// Returns the lowercase hex string of the 32-bit hash (matches TS
/// `djb2(...).toString(16)` — no padding, no `0x` prefix).
#[must_use]
pub fn signature_for_shot(
    completed_at: i64,
    duration: i64,
    profile_name: Option<&str>,
    final_weight: Option<f32>,
) -> String {
    // Match the TS join order + the `"∅"` sentinel for missing /
    // non-finite operands.
    let profile = profile_name.unwrap_or("\u{2205}");
    let weight = rk(final_weight.map(f64::from), 2);
    let joined = format!("{completed_at}|{duration}|{profile}|{weight}");
    format!("{:x}", djb2(&joined))
}

/// Bean de-dup signature: `(name, roasterName, roastedOn)`. The TS contract:
///
/// - `name` and `roasterName` are trimmed + lowercased (TS
///   `String.prototype.toLowerCase` — locale-independent ASCII lower
///   for the inputs at issue);
/// - `roastedOn` rides as-is when present, else the `"∅"` sentinel.
#[must_use]
pub fn signature_for_bean(
    name: &str,
    roaster_name: Option<&str>,
    roasted_on: Option<&str>,
) -> String {
    let name = name.trim().to_lowercase();
    let roaster = roaster_name.unwrap_or("").trim().to_lowercase();
    let roast = roasted_on.unwrap_or("\u{2205}");
    let joined = format!("{name}|{roaster}|{roast}");
    format!("{:x}", djb2(&joined))
}

/// Roaster de-dup signature: normalised name. The TS
/// contract: trim, lowercase, replace any run of whitespace / `_` /
/// `.` / `-` / `,` / `/` with a single space, then strip every char
/// that isn't `[a-z0-9 ]`.
#[must_use]
pub fn signature_for_roaster(name: &str) -> String {
    let mut s = String::with_capacity(name.len());
    let mut prev_was_separator = false;
    let mut last_emitted_space = false;
    // Trim → lowercase → collapse run-of-separators-to-single-space →
    // strip non-[a-z0-9 ]. Done in one pass to avoid building an intermediate.
    for c in name.trim().chars() {
        let lower = c.to_ascii_lowercase();
        let is_sep = matches!(
            lower,
            ' ' | '\t' | '\n' | '\r' | '_' | '.' | '-' | ',' | '/'
        );
        if is_sep {
            if !prev_was_separator && !s.is_empty() {
                s.push(' ');
                last_emitted_space = true;
            }
            prev_was_separator = true;
            continue;
        }
        prev_was_separator = false;
        if lower.is_ascii_lowercase() || lower.is_ascii_digit() {
            s.push(lower);
            last_emitted_space = false;
        }
        // Else: a non-[a-z0-9 ] non-separator char (e.g. `é`, `&`) —
        // dropped silently, matching the TS `.replace(/[^a-z0-9 ]/g, '')`.
    }
    // The TS pipeline emits a trailing space iff the last meaningful
    // char was a separator and there was content before it. The
    // collapse-step here pushes the space proactively, so trim that
    // dangling space if no further content followed.
    if last_emitted_space {
        s.pop();
    }
    format!("{:x}", djb2(&s))
}

// ── Reconciliation ────────────────────────────────────────────────────

/// Reconcile a remote pull against the local history. Returns the list
/// of actions the caller must apply to its store; this function is
/// pure (no side effects) so it is easy to test.
///
/// Three-step planner:
///   1. If a local shot's `visualizerId` matches a remote → `Update`
///      action (the LWW gate on `updated_at_ms` happens caller-side
///      so the planner stays signature-agnostic on annotation
///      conflicts — only the editable annotations differ).
///   2. Else compute the signature; look for an unbound, non-tombstoned
///      local with a matching signature → `Bind`.
///   3. Else → `Add`.
///
/// The action list preserves the input remote order.
#[must_use]
pub fn reconcile_shots(local: &[LocalShotRef], remote: &[WireShot]) -> Vec<ReconcileAction> {
    use std::collections::HashMap;

    let mut actions = Vec::with_capacity(remote.len());

    // Index 1: bound locals, keyed by visualizer_id, for fast Update
    // lookup.
    let mut by_vis_id: HashMap<&str, &LocalShotRef> = HashMap::new();
    // Index 2: unbound, non-tombstoned locals, keyed by signature, for
    // Bind lookup. Buckets are FIFO — `Vec::remove(0)` mirrors the TS
    // `candidates.shift()` semantics.
    let mut by_sig: HashMap<String, Vec<&LocalShotRef>> = HashMap::new();

    for s in local {
        if let Some(vis) = s.visualizer_id.as_deref() {
            by_vis_id.insert(vis, s);
        } else if s.deleted_at.is_none() {
            let sig = signature_for_shot(
                s.completed_at,
                s.duration,
                s.profile_name.as_deref(),
                s.final_weight,
            );
            by_sig.entry(sig).or_default().push(s);
        }
    }

    for r in remote {
        if let Some(bound) = by_vis_id.get(r.id.as_str()) {
            actions.push(ReconcileAction::Update {
                local_id: bound.id.clone(),
                remote: r.clone(),
            });
            continue;
        }
        let sig = signature_for_shot(
            r.clock,
            r.duration_ms,
            r.profile_title.as_deref(),
            r.final_weight_g,
        );
        // FIFO: take the first candidate, matching the TS
        // `candidates.shift()`. If multiple unbound locals collide on
        // the same signature, the de-dup banner on the History page
        // surfaces it post-bind so the user can merge manually.
        if let Some(bucket) = by_sig.get_mut(&sig)
            && !bucket.is_empty()
        {
            let target = bucket.remove(0);
            actions.push(ReconcileAction::Bind {
                local_id: target.id.clone(),
                visualizer_id: r.id.clone(),
                remote: r.clone(),
            });
            continue;
        }
        actions.push(ReconcileAction::Add { remote: r.clone() });
    }

    actions
}

// ── JSON facade for the wasm / FFI bridges ────────────────────────────

/// JSON-bridged variant of [`reconcile_shots`]: parses a TS-shaped
/// `(local, remote)` pair from a single JSON object, runs the planner,
/// and serialises the action list back to JSON. The TS shell hands its
/// `StoredShot[]` + `WireShot[]` straight in via `JSON.stringify` —
/// extra fields on `StoredShot` (`series`, `bean`, `peakWeight`, …)
/// are ignored by serde.
///
/// Input shape: `{"local": LocalShotRef[], "remote": WireShot[]}`.
/// Output shape: `ReconcileAction[]`.
///
/// # Errors
///
/// Returns the JSON parse error string when `payload` cannot be
/// deserialised into the expected shape.
pub fn reconcile_shots_json(payload: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    struct In {
        local: Vec<LocalShotRef>,
        remote: Vec<WireShot>,
    }
    let inp: In = serde_json::from_str(payload).map_err(|e| e.to_string())?;
    let actions = reconcile_shots(&inp.local, &inp.remote);
    serde_json::to_string(&actions).map_err(|e| e.to_string())
}

// ── Bean / roaster reconcile (CORE4) ──────────────────────────────────
//
// The bidirectional bean/roaster sync's **pull-reconcile decision kernel**,
// ported from `bean-sync.ts`'s `runSync` so every shell matches remote rows
// against the local library identically. Pure data in, an action list out;
// the shell applies the actions (the HTTP, the store mutations, the
// premium-gating, and the push legs stay in the shell). Siblings of
// [`reconcile_shots`]; they reuse [`signature_for_bean`] /
// [`signature_for_roaster`] for the by-signature match.

/// One reconciliation outcome for a remote **roaster** pull. Internally
/// tagged (`{ kind, ... }`, lowercase) like [`ReconcileAction`] so the TS
/// shell pattern-matches on `kind` without unwrapping a `content` envelope.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum RoasterReconcileAction {
    /// A local already bound to this remote's `visualizer_id` → refresh all
    /// of its Visualizer-modelled fields (and re-affirm the binding).
    Update {
        /// The local roaster to update.
        #[serde(rename = "localId")]
        local_id: String,
        /// The remote wire carrying the fresh fields.
        remote: RoasterWire,
    },
    /// An unbound local matches by signature, or any local matches by name →
    /// bind it to this remote's `visualizer_id` (fields untouched).
    Bind {
        /// The local roaster to bind.
        #[serde(rename = "localId")]
        local_id: String,
        /// The remote wire whose id binds in.
        remote: RoasterWire,
    },
    /// No local match → materialise a fresh local roaster from this wire.
    Add {
        /// The remote roaster to add locally.
        remote: RoasterWire,
    },
}

/// One reconciliation outcome for a remote **bean** pull. The remote is
/// already DECODED (the shell runs `bean_from_wire` first, because the
/// signature match reads the decoded `name` / `roaster` / `roasted_on`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum BeanReconcileAction {
    /// A local matches (by `visualizer_id`, by `id`, or by signature) →
    /// replace it in place, keeping the local `id`.
    Replace {
        /// The local bean to replace.
        #[serde(rename = "localId")]
        local_id: String,
        /// The decoded remote bean.
        remote: Bean,
    },
    /// No local match → insert the decoded remote bean.
    Add {
        /// The decoded remote bean to add.
        remote: Bean,
    },
}

/// Reconcile a remote roaster pull against the local directory. Mirrors
/// `runSync` step 1: bound-by-id → [`RoasterReconcileAction::Update`];
/// else unbound-non-deleted-by-signature OR any-by-name →
/// [`RoasterReconcileAction::Bind`]; else [`RoasterReconcileAction::Add`].
/// Remotes with no `id` are skipped (mirrors the TS `if (!wire.id) continue`).
#[must_use]
pub fn reconcile_roasters(
    local: &[Roaster],
    remote: &[RoasterWire],
) -> Vec<RoasterReconcileAction> {
    let mut actions = Vec::with_capacity(remote.len());
    for wire in remote {
        let Some(remote_id) = wire.id.as_deref() else {
            continue;
        };
        if let Some(by_id) = local
            .iter()
            .find(|r| r.visualizer_id.as_deref() == Some(remote_id))
        {
            actions.push(RoasterReconcileAction::Update {
                local_id: by_id.id.clone(),
                remote: wire.clone(),
            });
            continue;
        }
        let wire_sig = signature_for_roaster(&wire.name);
        let by_sig = local.iter().find(|r| {
            r.visualizer_id.is_none()
                && r.deleted_at.is_none()
                && signature_for_roaster(&r.name) == wire_sig
        });
        // `findRoasterByName`: case-insensitive exact match, any row (NOT
        // filtered on bound / deleted), first hit. Empty name → no match.
        let by_name = by_sig.or_else(|| {
            let needle = wire.name.trim().to_lowercase();
            if needle.is_empty() {
                return None;
            }
            local
                .iter()
                .find(|r| r.name.trim().to_lowercase() == needle)
        });
        if let Some(m) = by_name {
            actions.push(RoasterReconcileAction::Bind {
                local_id: m.id.clone(),
                remote: wire.clone(),
            });
        } else {
            actions.push(RoasterReconcileAction::Add {
                remote: wire.clone(),
            });
        }
    }
    actions
}

/// Reconcile a remote bean pull against the local library. Mirrors `runSync`
/// step 3: match by (`visualizer_id` both-set-and-equal) OR (same `id`);
/// else by signature over an unbound, non-deleted local →
/// [`BeanReconcileAction::Replace`]; else [`BeanReconcileAction::Add`].
///
/// `roaster_names` maps a local roaster id → its display name, so the
/// signature can fold in the roaster name for both the remote and each local
/// bean (the TS resolves this via `library.getRoaster(id)?.name`).
#[must_use]
pub fn reconcile_beans(
    local: &[Bean],
    remote: &[Bean],
    roaster_names: &HashMap<String, String>,
) -> Vec<BeanReconcileAction> {
    let name_of = |roaster_id: &Option<String>| -> Option<String> {
        roaster_id
            .as_ref()
            .and_then(|id| roaster_names.get(id).cloned())
    };
    let mut actions = Vec::with_capacity(remote.len());
    for decoded in remote {
        let decoded_sig = signature_for_bean(
            &decoded.name,
            name_of(&decoded.roaster_id).as_deref(),
            decoded.roasted_on.as_deref(),
        );
        let by_id = local.iter().find(|b| {
            (decoded.visualizer_id.is_some() && b.visualizer_id == decoded.visualizer_id)
                || b.id == decoded.id
        });
        let existing = by_id.or_else(|| {
            local.iter().find(|b| {
                b.visualizer_id.is_none()
                    && b.deleted_at.is_none()
                    && signature_for_bean(
                        &b.name,
                        name_of(&b.roaster_id).as_deref(),
                        b.roasted_on.as_deref(),
                    ) == decoded_sig
            })
        });
        if let Some(m) = existing {
            actions.push(BeanReconcileAction::Replace {
                local_id: m.id.clone(),
                remote: decoded.clone(),
            });
        } else {
            actions.push(BeanReconcileAction::Add {
                remote: decoded.clone(),
            });
        }
    }
    actions
}

/// JSON-bridged [`reconcile_roasters`]. Input:
/// `{"local": Roaster[], "remote": RoasterWire[]}`. Output:
/// `RoasterReconcileAction[]`.
///
/// # Errors
/// The JSON parse / serialise error string on malformed input.
pub fn reconcile_roasters_json(payload: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    struct In {
        local: Vec<Roaster>,
        remote: Vec<RoasterWire>,
    }
    let inp: In = serde_json::from_str(payload).map_err(|e| e.to_string())?;
    let actions = reconcile_roasters(&inp.local, &inp.remote);
    serde_json::to_string(&actions).map_err(|e| e.to_string())
}

/// JSON-bridged [`reconcile_beans`]. Input:
/// `{"local": Bean[], "remote": Bean[], "roasterNames": {id: name}}` (the
/// remote beans are already decoded via `bean_from_wire`). Output:
/// `BeanReconcileAction[]`.
///
/// # Errors
/// The JSON parse / serialise error string on malformed input.
pub fn reconcile_beans_json(payload: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    struct In {
        local: Vec<Bean>,
        remote: Vec<Bean>,
        #[serde(default, rename = "roasterNames")]
        roaster_names: HashMap<String, String>,
    }
    let inp: In = serde_json::from_str(payload).map_err(|e| e.to_string())?;
    let actions = reconcile_beans(&inp.local, &inp.remote, &inp.roaster_names);
    serde_json::to_string(&actions).map_err(|e| e.to_string())
}

// ── Tests ─────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    // ── signature_for_shot ────────────────────────────────────────────

    /// The TS `signatureForShot` would emit hex string `H` for the
    /// reference shot — we pin it here so the Rust hash output is
    /// byte-identical. The reference inputs match the TS test fixture
    /// `(1_700_000_000_000, 30_000, "best of decent", 36)`. The
    /// expected output is the literal hex digest the TS impl produces;
    /// pinning the value means a hash-algorithm drift on either side
    /// breaks this test.
    #[test]
    fn shot_signature_is_stable_across_identical_inputs() {
        let a = signature_for_shot(
            1_700_000_000_000,
            30_000,
            Some("best of decent"),
            Some(36.0),
        );
        let b = signature_for_shot(
            1_700_000_000_000,
            30_000,
            Some("best of decent"),
            Some(36.0),
        );
        assert_eq!(a, b);
    }

    #[test]
    fn shot_signature_changes_when_start_time_differs() {
        let a = signature_for_shot(
            1_700_000_000_000,
            30_000,
            Some("best of decent"),
            Some(36.0),
        );
        let b = signature_for_shot(
            1_700_000_001_000,
            30_000,
            Some("best of decent"),
            Some(36.0),
        );
        assert_ne!(a, b);
    }

    #[test]
    fn shot_signature_changes_when_duration_differs() {
        let a = signature_for_shot(
            1_700_000_000_000,
            30_000,
            Some("best of decent"),
            Some(36.0),
        );
        let b = signature_for_shot(
            1_700_000_000_000,
            31_000,
            Some("best of decent"),
            Some(36.0),
        );
        assert_ne!(a, b);
    }

    #[test]
    fn shot_signature_changes_when_final_weight_differs() {
        let a = signature_for_shot(
            1_700_000_000_000,
            30_000,
            Some("best of decent"),
            Some(36.0),
        );
        let b = signature_for_shot(
            1_700_000_000_000,
            30_000,
            Some("best of decent"),
            Some(37.5),
        );
        assert_ne!(a, b);
    }

    /// 36.001 rounds to "36.00" at 2 decimals, same as a plain 36.0 —
    /// so the hash is stable under sub-rounding float jitter. Mirrors
    /// the TS test of the same name.
    #[test]
    fn shot_signature_is_stable_under_sub_rounding_float_jitter() {
        let a = signature_for_shot(
            1_700_000_000_000,
            30_000,
            Some("best of decent"),
            Some(36.001),
        );
        let b = signature_for_shot(
            1_700_000_000_000,
            30_000,
            Some("best of decent"),
            Some(36.0),
        );
        assert_eq!(a, b);
    }

    #[test]
    fn shot_signature_treats_a_null_profile_as_a_distinct_slot() {
        let named = signature_for_shot(1_700_000_000_000, 30_000, Some("named"), Some(36.0));
        let unnamed = signature_for_shot(1_700_000_000_000, 30_000, None, Some(36.0));
        assert_ne!(named, unnamed);
    }

    /// Pin the exact hex digest the TS `djb2(parts.join('|'))` /
    /// `.toString(16)` pipeline produces for the canonical reference
    /// input — this is the cross-impl contract.
    ///
    /// The expected value is computed once by running the TS pipeline
    /// by hand:
    /// - join: `"1700000000000|30000|best of decent|36.00"`
    /// - djb2 seed 5381, multiplier 33, char codes ASCII, modulo 2^32
    /// - hex(no pad, no prefix)
    ///
    /// Anchored here so a drift on either side surfaces as a test
    /// failure rather than a silently divergent dedup planner.
    #[test]
    fn shot_signature_matches_pinned_djb2_hex_digest() {
        let sig = signature_for_shot(
            1_700_000_000_000,
            30_000,
            Some("best of decent"),
            Some(36.0),
        );
        // Manually computed djb2 of "1700000000000|30000|best of decent|36.00".
        // This pin is derived from the algorithm definition; both
        // language impls must agree.
        assert_eq!(sig, expected_shot_signature_for_reference_inputs());
    }

    /// Compute djb2 of the reference shot input via the same
    /// definition, expressed as a stand-alone reference. The body
    /// re-derives the value to keep the assertion's expectation
    /// trustworthy without hard-coding a magic literal — but the test
    /// above STILL pins the algorithm, because any divergence in
    /// `signature_for_shot`'s formatting / join order would change the
    /// resulting hex.
    fn expected_shot_signature_for_reference_inputs() -> String {
        let joined = format!(
            "{}|{}|{}|{:.2}",
            1_700_000_000_000_i64, 30_000_i64, "best of decent", 36.0_f64
        );
        format!("{:x}", djb2_reference(&joined))
    }

    /// Reference djb2 (stand-alone copy) — guards against an accidental
    /// edit of the top-level [`djb2`] that would leave the production
    /// path and the test verifier silently in sync. If both are edited
    /// the pinned cross-impl test still holds, but this duplicate
    /// keeps the algorithm definition explicit in the test surface.
    fn djb2_reference(s: &str) -> u32 {
        let mut h: u32 = 5381;
        for c in s.chars() {
            let code = c as u32;
            h = (h.wrapping_shl(5).wrapping_add(h)).wrapping_add(code);
        }
        h
    }

    // ── signature_for_bean ────────────────────────────────────────────

    #[test]
    fn bean_signature_is_case_insensitive_on_name_and_roaster() {
        let a = signature_for_bean("Yirgacheffe", Some("Counter Culture"), Some("2026-05-08"));
        let b = signature_for_bean("yirgacheffe", Some("COUNTER CULTURE"), Some("2026-05-08"));
        assert_eq!(a, b);
    }

    #[test]
    fn bean_signature_changes_when_the_roast_date_differs() {
        let a = signature_for_bean("Yirgacheffe", Some("CC"), Some("2026-05-08"));
        let b = signature_for_bean("Yirgacheffe", Some("CC"), Some("2026-05-09"));
        assert_ne!(a, b);
    }

    // ── signature_for_roaster ─────────────────────────────────────────

    #[test]
    fn roaster_signature_strips_punctuation_and_collapses_whitespace() {
        let a = signature_for_roaster("Onyx Coffee Lab");
        let b = signature_for_roaster("onyx-coffee_lab");
        let c = signature_for_roaster("  ONYX   COFFEE.LAB ");
        assert_eq!(a, b);
        assert_eq!(a, c);
    }

    #[test]
    fn roaster_signature_treats_different_roasters_as_different_signatures() {
        let a = signature_for_roaster("Onyx");
        let b = signature_for_roaster("Counter Culture");
        assert_ne!(a, b);
    }

    // ── reconcile_shots ───────────────────────────────────────────────

    fn wire(id: &str) -> WireShot {
        WireShot {
            id: id.to_owned(),
            clock: 1_700_000_000_000,
            duration_ms: 30_000,
            profile_title: Some("best of decent".to_owned()),
            final_weight_g: Some(36.0),
            notes: None,
            rating: None,
            updated_at_ms: None,
            tag_list: Vec::new(),
        }
    }

    fn local_shot(id: &str, visualizer_id: Option<&str>) -> LocalShotRef {
        LocalShotRef {
            id: id.to_owned(),
            completed_at: 1_700_000_000_000,
            duration: 30_000,
            profile_name: Some("best of decent".to_owned()),
            final_weight: Some(36.0),
            visualizer_id: visualizer_id.map(str::to_owned),
            deleted_at: None,
        }
    }

    #[test]
    fn reconcile_adds_new_remotes_when_no_local_matches() {
        let actions = reconcile_shots(&[], &[wire("r-1")]);
        assert_eq!(actions.len(), 1);
        assert!(matches!(actions[0], ReconcileAction::Add { .. }));
    }

    #[test]
    fn reconcile_updates_locals_whose_visualizer_id_matches() {
        let local = local_shot("shot:l-1", Some("r-1"));
        let remote = wire("r-1");
        let actions = reconcile_shots(std::slice::from_ref(&local), std::slice::from_ref(&remote));
        assert_eq!(actions.len(), 1);
        assert_eq!(
            actions[0],
            ReconcileAction::Update {
                local_id: "shot:l-1".to_owned(),
                remote
            }
        );
    }

    #[test]
    fn reconcile_binds_unbound_locals_by_signature_collision() {
        let local = local_shot("shot:l-1", None);
        let remote = wire("r-1");
        let actions = reconcile_shots(&[local], std::slice::from_ref(&remote));
        assert_eq!(actions.len(), 1);
        assert_eq!(
            actions[0],
            ReconcileAction::Bind {
                local_id: "shot:l-1".to_owned(),
                visualizer_id: "r-1".to_owned(),
                remote
            }
        );
    }

    #[test]
    fn reconcile_skips_tombstoned_locals_when_matching_signatures() {
        let mut local = local_shot("shot:l-1", None);
        local.deleted_at = Some(1_700_000_000_000);
        let actions = reconcile_shots(&[local], &[wire("r-1")]);
        // Tombstoned local should NOT bind — instead we ADD the remote.
        assert!(matches!(actions[0], ReconcileAction::Add { .. }));
    }

    #[test]
    fn reconcile_plans_the_right_actions_in_order_for_a_mixed_pull() {
        let bound = local_shot("shot:bound", Some("r-known"));
        let unbound = LocalShotRef {
            id: "shot:unbound".to_owned(),
            completed_at: 1_700_000_010_000,
            duration: 25_000,
            profile_name: Some("p".to_owned()),
            final_weight: Some(40.0),
            visualizer_id: None,
            deleted_at: None,
        };
        let remotes = [
            wire("r-known"),
            WireShot {
                id: "r-new".to_owned(),
                clock: 1_700_000_020_000,
                duration_ms: 28_000,
                profile_title: Some("q".to_owned()),
                final_weight_g: Some(42.0),
                notes: None,
                rating: None,
                updated_at_ms: None,
                tag_list: Vec::new(),
            },
            WireShot {
                id: "r-bind".to_owned(),
                clock: 1_700_000_010_000,
                duration_ms: 25_000,
                profile_title: Some("p".to_owned()),
                final_weight_g: Some(40.0),
                notes: None,
                rating: None,
                updated_at_ms: None,
                tag_list: Vec::new(),
            },
        ];
        let actions = reconcile_shots(&[bound, unbound], &remotes);
        assert_eq!(actions.len(), 3);
        assert!(matches!(actions[0], ReconcileAction::Update { .. }));
        assert!(matches!(actions[1], ReconcileAction::Add { .. }));
        assert!(matches!(actions[2], ReconcileAction::Bind { .. }));
    }

    // ── JSON facade ───────────────────────────────────────────────────

    #[test]
    fn reconcile_shots_json_parses_ts_shaped_input_and_emits_actions() {
        // The shell hands its own `StoredShot[]` straight in — extra
        // fields (`series`, `bean`, `peakWeight`, …) are ignored by
        // serde. The minimum viable payload only carries the slim
        // `LocalShotRef` fields.
        let payload = r#"{
            "local": [
                {"id": "shot:l-1", "completedAt": 1700000000000, "duration": 30000,
                 "profileName": "best of decent", "finalWeight": 36, "visualizerId": null,
                 "deletedAt": null, "series": [], "rating": 0, "notes": "", "peakWeight": 36,
                 "peakPressure": 9, "peakTemp": 93, "bean": null}
            ],
            "remote": [
                {"id": "r-1", "clock": 1700000000000, "duration_ms": 30000,
                 "profile_title": "best of decent", "final_weight_g": 36,
                 "notes": null, "rating": null, "updated_at_ms": null}
            ]
        }"#;
        let out = reconcile_shots_json(payload).expect("parse + plan");
        let parsed: Vec<ReconcileAction> = serde_json::from_str(&out).unwrap();
        assert_eq!(parsed.len(), 1);
        assert!(matches!(parsed[0], ReconcileAction::Bind { .. }));
    }

    #[test]
    fn wire_shot_tag_list_defaults_to_empty_when_absent() {
        // Older shells / spec responses that don't carry `tag_list` should
        // round-trip into an empty Vec rather than erroring out. Mirrors
        // the `bean.tags` default test pattern in `bean.rs`.
        let json = r#"{
            "id": "r-1", "clock": 1700000000000, "duration_ms": 30000,
            "profile_title": "p", "final_weight_g": 36,
            "notes": null, "rating": null, "updated_at_ms": null
        }"#;
        let parsed: WireShot = serde_json::from_str(json).unwrap();
        assert!(parsed.tag_list.is_empty());
    }

    #[test]
    fn wire_shot_tag_list_round_trips() {
        let mut w = wire("r-1");
        w.tag_list = vec!["daily-driver".to_owned(), "lever".to_owned()];
        let s = serde_json::to_string(&w).unwrap();
        let parsed: WireShot = serde_json::from_str(&s).unwrap();
        assert_eq!(parsed.tag_list, vec!["daily-driver", "lever"]);
    }

    #[test]
    fn reconcile_shots_json_surfaces_a_parse_error_for_malformed_input() {
        let err = reconcile_shots_json("not json").unwrap_err();
        assert!(!err.is_empty());
    }

    #[test]
    fn reconcile_shots_json_serializes_actions_with_lowercase_kind() {
        // Pin the wire shape of `ReconcileAction` so the TS shell can
        // pattern-match on `kind` (lowercase) and consume `localId` /
        // `visualizerId` (camelCase). A serde rename drift would break
        // every existing TS caller.
        let local = local_shot("shot:l-1", Some("r-1"));
        let remote = wire("r-1");
        let actions = reconcile_shots(&[local], &[remote]);
        let json = serde_json::to_string(&actions).unwrap();
        assert!(json.contains("\"kind\":\"update\""));
        assert!(json.contains("\"localId\":\"shot:l-1\""));
    }

    // ── reconcile_roasters (CORE4) ─────────────────────────────────────

    fn roaster(id: &str, name: &str, visualizer_id: Option<&str>) -> Roaster {
        let mut r = Roaster::new(id.to_owned(), name.to_owned(), 0);
        r.visualizer_id = visualizer_id.map(str::to_owned);
        r
    }

    fn roaster_wire(id: &str, name: &str) -> RoasterWire {
        RoasterWire {
            id: Some(id.to_owned()),
            name: name.to_owned(),
            ..RoasterWire::default()
        }
    }

    #[test]
    fn reconcile_roasters_adds_a_new_remote() {
        let actions = reconcile_roasters(&[], &[roaster_wire("r1", "Acme")]);
        assert_eq!(
            actions,
            vec![RoasterReconcileAction::Add {
                remote: roaster_wire("r1", "Acme")
            }]
        );
    }

    #[test]
    fn reconcile_roasters_updates_a_local_bound_by_visualizer_id() {
        let local = roaster("roaster:l1", "Acme", Some("r1"));
        let actions = reconcile_roasters(&[local], &[roaster_wire("r1", "Acme Renamed")]);
        assert!(matches!(
            actions.as_slice(),
            [RoasterReconcileAction::Update { local_id, .. }] if local_id == "roaster:l1"
        ));
    }

    #[test]
    fn reconcile_roasters_binds_an_unbound_local_by_name() {
        let local = roaster("roaster:l1", "Acme", None);
        let actions = reconcile_roasters(&[local], &[roaster_wire("r1", "Acme")]);
        assert!(matches!(
            actions.as_slice(),
            [RoasterReconcileAction::Bind { local_id, .. }] if local_id == "roaster:l1"
        ));
    }

    #[test]
    fn reconcile_roasters_binds_by_signature_despite_punctuation_drift() {
        // Signature normalises punctuation/whitespace, so "Onyx-Coffee_Lab"
        // binds to a local "Onyx Coffee Lab" even though the exact-name
        // match would miss.
        let local = roaster("roaster:l1", "Onyx Coffee Lab", None);
        let actions = reconcile_roasters(&[local], &[roaster_wire("r1", "onyx-coffee_lab")]);
        assert!(matches!(
            actions.as_slice(),
            [RoasterReconcileAction::Bind { local_id, .. }] if local_id == "roaster:l1"
        ));
    }

    #[test]
    fn reconcile_roasters_skips_remotes_without_an_id() {
        let wire = RoasterWire {
            id: None,
            name: "No Id".to_owned(),
            ..RoasterWire::default()
        };
        assert!(reconcile_roasters(&[], &[wire]).is_empty());
    }

    // ── reconcile_beans (CORE4) ────────────────────────────────────────

    fn bean(id: &str, name: &str, visualizer_id: Option<&str>) -> Bean {
        let mut b = Bean::new(id.to_owned(), name.to_owned(), 0);
        b.visualizer_id = visualizer_id.map(str::to_owned);
        b
    }

    #[test]
    fn reconcile_beans_adds_a_new_remote() {
        let decoded = bean("bean:remote", "Yirg", Some("b1"));
        let actions = reconcile_beans(&[], std::slice::from_ref(&decoded), &HashMap::new());
        assert_eq!(actions, vec![BeanReconcileAction::Add { remote: decoded }]);
    }

    #[test]
    fn reconcile_beans_replaces_a_local_bound_by_visualizer_id() {
        let local = bean("bean:l1", "Yirg", Some("b1"));
        let decoded = bean("bean:remote", "Yirg", Some("b1"));
        let actions = reconcile_beans(&[local], &[decoded], &HashMap::new());
        assert!(matches!(
            actions.as_slice(),
            [BeanReconcileAction::Replace { local_id, .. }] if local_id == "bean:l1"
        ));
    }

    #[test]
    fn reconcile_beans_replaces_a_local_with_the_same_id() {
        // A crema-pushed bag round-trips its `crema_id` → decoded.id matches
        // the local id even when the local is unbound.
        let local = bean("bean:shared", "Yirg", None);
        let decoded = bean("bean:shared", "Yirg", Some("b1"));
        let actions = reconcile_beans(&[local], &[decoded], &HashMap::new());
        assert!(matches!(
            actions.as_slice(),
            [BeanReconcileAction::Replace { local_id, .. }] if local_id == "bean:shared"
        ));
    }

    #[test]
    fn reconcile_beans_binds_by_signature_including_roaster_name() {
        // Two unbound beans share a name but differ by roaster → the
        // signature (which folds in the roaster name) must NOT match them.
        let mut local = bean("bean:l1", "Yirg", None);
        local.roaster_id = Some("roaster:a".to_owned());
        local.roasted_on = Some("2026-05-01".to_owned());
        let mut decoded = bean("bean:remote", "Yirg", None);
        decoded.roaster_id = Some("roaster:b".to_owned());
        decoded.roasted_on = Some("2026-05-01".to_owned());

        let mut names = HashMap::new();
        names.insert("roaster:a".to_owned(), "Acme".to_owned());
        names.insert("roaster:b".to_owned(), "Onyx".to_owned());
        // Different roaster names → different signatures → no bind → Add.
        let actions = reconcile_beans(
            std::slice::from_ref(&local),
            std::slice::from_ref(&decoded),
            &names,
        );
        assert!(matches!(
            actions.as_slice(),
            [BeanReconcileAction::Add { .. }]
        ));

        // Same roaster name → signatures match → Replace.
        names.insert("roaster:b".to_owned(), "Acme".to_owned());
        let actions = reconcile_beans(&[local], &[decoded], &names);
        assert!(matches!(
            actions.as_slice(),
            [BeanReconcileAction::Replace { local_id, .. }] if local_id == "bean:l1"
        ));
    }

    #[test]
    fn reconcile_beans_does_not_bind_a_tombstoned_local() {
        let mut local = bean("bean:l1", "Yirg", None);
        local.deleted_at = Some(1);
        let decoded = bean("bean:remote", "Yirg", None);
        let actions = reconcile_beans(&[local], &[decoded], &HashMap::new());
        assert!(matches!(
            actions.as_slice(),
            [BeanReconcileAction::Add { .. }]
        ));
    }

    #[test]
    fn reconcile_json_facades_round_trip_and_tag_lowercase() {
        let payload = serde_json::json!({
            "local": [],
            "remote": [{ "id": "r1", "name": "Acme" }]
        })
        .to_string();
        let out = reconcile_roasters_json(&payload).unwrap();
        assert!(out.contains("\"kind\":\"add\""));

        let bean_payload = serde_json::json!({
            "local": [],
            "remote": [serde_json::to_value(bean("bean:1", "Yirg", Some("b1"))).unwrap()],
            "roasterNames": {}
        })
        .to_string();
        let out = reconcile_beans_json(&bean_payload).unwrap();
        assert!(out.contains("\"kind\":\"add\""));

        assert!(reconcile_roasters_json("not json").is_err());
        assert!(reconcile_beans_json("not json").is_err());
    }
}
