/**
 * `$lib/visualizer/shot-sync-signatures` — a thin TS shim over the
 * Rust-core `de1_domain::visualizer_sync` impl (exposed via wasm-bindgen
 * as `$lib/wasm/de1_wasm`).
 *
 * The signature helpers and the {@link reconcileShots} action planner
 * used to live here as TS. Per docs/36 §3 they're pure functions —
 * djb2 hash, action planner, LWW — and the Android shell wants to
 * call the same algorithm. They now live in Rust
 * (`core/de1-domain/src/visualizer_sync.rs`), so every shell shares
 * one byte-identical implementation.
 *
 * The wasm export takes positional args and JSON for the planner; the
 * tiny adapters below preserve the historical TS API surface
 * (`signatureForShot({ completedAt, duration, profileName,
 * finalWeight })`, `reconcileShots(local, remote)`) so existing
 * importers (shot-sync.ts, bean/visualizer-sync.ts) don't have to
 * change.
 *
 * `storedShotFromWire` is shell-specific (it allocates a
 * shell-shaped {@link StoredShot} with empty `series` / `bean` / star
 * defaults — see docs/36 deferred). It stays in TS, sans the wasm
 * round-trip.
 */

import {
	signatureForShot as wasmSignatureForShot,
	signatureForBean as wasmSignatureForBean,
	signatureForRoaster as wasmSignatureForRoaster,
	reconcileShots as wasmReconcileShots
} from '$lib/wasm/de1_wasm';
import type { StoredShot } from '$lib/history/model';

// ── Types ─────────────────────────────────────────────────────────────

/**
 * The fields we read from a remote shot row. Visualizer's API returns a
 * superset; we only care about identity + the de-dup hash inputs +
 * editable annotations. This shape is Crema-internal: it's BUILT from
 * `components['schemas']['ShotSummary']` + `components['schemas']['ShotDetail']`
 * (see `wireShotFromDetail` in `shot-sync.ts`) and uses unix
 * MILLISECONDS so it lines up with Crema's `StoredShot.completedAt`.
 * Visualizer itself wires unix SECONDS — we convert at the boundary.
 *
 * Mirrors `de1_domain::visualizer_sync::WireShot` field-for-field; both
 * halves of the split MUST stay in sync.
 */
export interface WireShot {
	id: string;
	/** Shot start timestamp, unix MS (converted from spec's unix-sec `clock`). */
	clock: number;
	/** Total shot duration, milliseconds. */
	duration_ms: number;
	/** Display name of the profile pulled. */
	profile_title: string | null;
	/** Final scale weight at shot end (grams), or null. */
	final_weight_g: number | null;
	/** Annotations the user has typed remotely. */
	notes: string | null;
	rating: number | null;
	/**
	 * Last server-side update, unix MS (converted from the spec's unix-sec
	 * `updated_at`). Drives LWW conflict resolution.
	 */
	updated_at_ms: number | null;
}

/**
 * One reconciliation outcome — what to do with a remote row. Wire form
 * is `{ kind, ... }` (lowercase tag) — pinned by the Rust serde
 * attribute so the TS pattern-match stays valid.
 */
export type ReconcileAction =
	| { kind: 'add'; remote: WireShot }
	| { kind: 'update'; localId: string; remote: WireShot }
	| { kind: 'bind'; localId: string; visualizerId: string; remote: WireShot };

// ── Signature helpers ────────────────────────────────────────────────

/**
 * The shot de-dup signature: a djb2 hash of `(startedAtMs, durationMs,
 * profileId, finalWeightG)`. Shots are inherently unique by time + final
 * weight — collisions are intentional ID matches per docs/36 §3.
 *
 * Adapts the wasm `signatureForShot` (positional args) to the object
 * shape the existing TS callers use.
 */
export function signatureForShot(shot: {
	completedAt: number;
	duration: number;
	profileName: string | null;
	finalWeight: number | null;
}): string {
	return wasmSignatureForShot(
		shot.completedAt,
		shot.duration,
		shot.profileName ?? undefined,
		shot.finalWeight ?? undefined
	);
}

/** Bean de-dup signature: `(name, roasterName, roastedOn)`. docs/36 §3. */
export function signatureForBean(bean: {
	name: string;
	roasterName: string | null;
	roastedOn: string | null;
}): string {
	return wasmSignatureForBean(
		bean.name,
		bean.roasterName ?? undefined,
		bean.roastedOn ?? undefined
	);
}

/** Roaster de-dup signature: normalised name. docs/36 §3. */
export function signatureForRoaster(roaster: { name: string }): string {
	return wasmSignatureForRoaster(roaster.name);
}

// ── Reconciliation ────────────────────────────────────────────────────

/**
 * Reconcile a remote pull against the local history. Returns the list
 * of actions the caller must apply to the store; this function is
 * pure (no side effects) so it is easy to test.
 *
 * Per docs/36 §3:
 *   1. If a local shot's `visualizerId` matches a remote → LWW on
 *      `updated_at` (we only patch annotations; telemetry is immutable
 *      Visualizer-side, so conflicts are rare).
 *   2. Else compute the signature; look for a local with
 *      `visualizerId === null` matching → BIND.
 *   3. Else ADD.
 *
 * Adapts the wasm `reconcileShots` (JSON in, JSON out) to the TS array
 * surface. The wasm side only reads the slim subset of `StoredShot`
 * (id, completedAt, duration, profileName, finalWeight, visualizerId,
 * deletedAt) — extra fields (`series`, `bean`, …) are dropped at the
 * JSON boundary.
 */
export function reconcileShots(
	local: readonly StoredShot[],
	remote: readonly WireShot[]
): ReconcileAction[] {
	const raw = wasmReconcileShots(JSON.stringify({ local, remote }));
	return JSON.parse(raw) as ReconcileAction[];
}

// ── Wire-to-local materializer (kept in TS) ──────────────────────────

/**
 * Convert a {@link WireShot} into a local {@link StoredShot} for the
 * "ADD" branch of reconcile. Telemetry isn't carried in the pull list
 * response — we materialise a stub StoredShot good enough for the
 * History list; the detail panel surfaces a "Profile / telemetry not
 * local" placeholder per docs/36 §deferred.
 *
 * Stays in TS because it builds a shell-specific `StoredShot` shape
 * (`series: []`, `peakPressure: 0`, etc) — the Rust core owns the
 * algorithm, the shell owns the persistence record.
 */
export function storedShotFromWire(remote: WireShot): StoredShot {
	const id = `shot:remote:${remote.id}`;
	return {
		id,
		completedAt: remote.clock,
		profileName: remote.profile_title,
		duration: remote.duration_ms,
		dose: null,
		peakWeight: null,
		finalWeight: remote.final_weight_g,
		peakPressure: 0,
		peakTemp: 0,
		series: [],
		bean: null,
		rating: remote.rating ?? 0,
		notes: remote.notes ?? '',
		visualizerId: remote.id,
		deletedAt: null
	};
}
