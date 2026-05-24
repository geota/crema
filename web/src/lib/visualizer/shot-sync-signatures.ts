/**
 * `$lib/visualizer/shot-sync-signatures` — the pure de-dup signature
 * helpers and the {@link reconcileShots} planner extracted from
 * `shot-sync.ts` so the unit tests can import them without dragging in
 * the wasm bridge / settings store / fetch wrapper.
 *
 * Everything here is sans-IO and deterministic. docs/36 §3.
 */

import type { StoredShot } from '$lib/history/model';

// ── Types ─────────────────────────────────────────────────────────────

/**
 * The fields we read from a remote shot row. Visualizer's API returns a
 * superset; we only care about identity + the de-dup hash inputs +
 * editable annotations.
 */
export interface WireShot {
	id: string;
	/** Unix epoch ms when the shot was pulled. */
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
	/** Last server-side update — drives LWW conflict resolution. */
	updated_at: string | null;
}

/** One reconciliation outcome — what to do with a remote row. */
export type ReconcileAction =
	| { kind: 'add'; remote: WireShot }
	| { kind: 'update'; localId: string; remote: WireShot }
	| { kind: 'bind'; localId: string; visualizerId: string; remote: WireShot };

// ── djb2 hash + numeric rounding ─────────────────────────────────────

/**
 * Stable djb2 hash. Fast, well-distributed for short ASCII strings, no
 * crypto needed. Returns an unsigned 32-bit integer so the hex
 * stringification is stable across runtimes.
 */
function djb2(s: string): number {
	let h = 5381;
	for (let i = 0; i < s.length; i++) {
		h = ((h << 5) + h + s.charCodeAt(i)) | 0;
	}
	return h >>> 0;
}

/** Render a number to a fixed-precision form so float jitter doesn't perturb the hash. */
function rk(n: number | null | undefined, decimals = 2): string {
	if (n == null || !Number.isFinite(n)) return '∅';
	return n.toFixed(decimals);
}

// ── Signatures (docs/36 §3) ──────────────────────────────────────────

/**
 * The shot de-dup signature: a djb2 hash of `(startedAtMs, durationMs,
 * profileId, finalWeightG)`. Shots are inherently unique by time + final
 * weight — collisions are intentional ID matches per docs/36 §3.
 */
export function signatureForShot(shot: {
	completedAt: number;
	duration: number;
	profileName: string | null;
	finalWeight: number | null;
}): string {
	const parts = [
		shot.completedAt.toString(),
		shot.duration.toString(),
		shot.profileName ?? '∅',
		rk(shot.finalWeight)
	];
	return djb2(parts.join('|')).toString(16);
}

/** Bean de-dup signature: `(name, roasterName, roastedOn)`. docs/36 §3. */
export function signatureForBean(bean: {
	name: string;
	roasterName: string | null;
	roastedOn: string | null;
}): string {
	const parts = [
		bean.name.trim().toLowerCase(),
		(bean.roasterName ?? '').trim().toLowerCase(),
		bean.roastedOn ?? '∅'
	];
	return djb2(parts.join('|')).toString(16);
}

/** Roaster de-dup signature: normalised name. docs/36 §3. */
export function signatureForRoaster(roaster: { name: string }): string {
	const normalised = roaster.name
		.trim()
		.toLowerCase()
		.replace(/[\s_.\-,/]+/g, ' ')
		.replace(/[^a-z0-9 ]/g, '');
	return djb2(normalised).toString(16);
}

// ── Reconciliation (docs/36 §3) ───────────────────────────────────────

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
 */
export function reconcileShots(
	local: StoredShot[],
	remote: WireShot[]
): ReconcileAction[] {
	const actions: ReconcileAction[] = [];
	const byVisId = new Map<string, StoredShot>();
	const bySig = new Map<string, StoredShot[]>();
	for (const s of local) {
		if (s.visualizerId) byVisId.set(s.visualizerId, s);
		if (!s.visualizerId && s.deletedAt == null) {
			const sig = signatureForShot(s);
			const bucket = bySig.get(sig) ?? [];
			bucket.push(s);
			bySig.set(sig, bucket);
		}
	}
	for (const r of remote) {
		const bound = byVisId.get(r.id);
		if (bound) {
			actions.push({ kind: 'update', localId: bound.id, remote: r });
			continue;
		}
		const sig = signatureForShot({
			completedAt: r.clock,
			duration: r.duration_ms,
			profileName: r.profile_title,
			finalWeight: r.final_weight_g
		});
		const candidates = bySig.get(sig);
		if (candidates && candidates.length > 0) {
			// Take the first; if multiple unbound locals collide on the
			// same signature, the de-dup banner on the History page
			// surfaces it post-bind so the user can merge manually.
			const target = candidates.shift()!;
			actions.push({
				kind: 'bind',
				localId: target.id,
				visualizerId: r.id,
				remote: r
			});
			continue;
		}
		actions.push({ kind: 'add', remote: r });
	}
	return actions;
}

/**
 * Convert a {@link WireShot} into a local {@link StoredShot} for the
 * "ADD" branch of reconcile. Telemetry isn't carried in the pull list
 * response — we materialise a stub StoredShot good enough for the
 * History list; the detail panel surfaces a "Profile / telemetry not
 * local" placeholder per docs/36 §deferred.
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
