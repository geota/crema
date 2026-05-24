/**
 * `$lib/visualizer/shot-sync` — shot-history sync between Crema's local
 * shot library and Visualizer's `/api/v1/shots` endpoints.
 *
 * Direction & modes (docs/36 §2):
 *   - **Backup**  — push only. `ShotCompleted` fires an upload; failures
 *     queue for retry. The history list never gets pulled.
 *   - **Pull**    — read remote shots into the local cache without ever
 *     pushing. De-dup via the `signatureForShot` hash so a local shot
 *     that matches a remote signature binds (no duplicate).
 *   - **Two-way** — both directions; LWW conflict resolution on
 *     `updatedAt`.
 *
 * Wire shape: the existing `web/src/lib/history/v2-export.ts` already
 * produces the v2 JSON the Visualizer ingestion endpoint accepts. This
 * module wraps that exporter with the HTTP calls + the retry queue +
 * the de-dup signature.
 *
 * Auth: every authenticated call funnels through
 * `withFreshToken` so the 401 → refresh-once dance is handled for free.
 */

import { VisualizerError } from '$lib/bean';
import { exportStoredShotAsV2Json } from '$lib/history/v2-export';
import type { StoredShot } from '$lib/history/model';
import { getSettingsStore } from '$lib/settings';
import { isConnected, NotAuthenticatedError, withFreshToken } from './token-store';

/** Visualizer API base. */
const API_BASE = 'https://visualizer.coffee/api';

// ── Wire shape ────────────────────────────────────────────────────────

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

interface ListResponse<T> {
	data?: T[];
	page?: number;
	per_page?: number;
	total?: number;
	next_cursor?: number | null;
}

// ── De-dup signatures (docs/36 §3) ────────────────────────────────────

/**
 * Stable djb2 hash. Fast, well-distributed for short ASCII strings, no
 * crypto needed. Same algorithm the bean library's roaster-mark hue
 * derivation uses.
 */
function djb2(s: string): number {
	let h = 5381;
	for (let i = 0; i < s.length; i++) {
		h = ((h << 5) + h + s.charCodeAt(i)) | 0;
	}
	// Force unsigned 32-bit for stable hex stringification across runtimes.
	return h >>> 0;
}

/** Render a number to a fixed-precision form so float jitter doesn't perturb the hash. */
function rk(n: number | null | undefined, decimals = 2): string {
	if (n == null || !Number.isFinite(n)) return '∅';
	return n.toFixed(decimals);
}

/**
 * The shot de-dup signature: a djb2 hash of `(startedAtMs, durationMs,
 * profileId, finalWeightG)`. Shots are inherently unique by time + final
 * weight — collisions are intentional ID matches per docs/36 §3.
 *
 * `profileId` falls back to the profile name when no id is recorded, so
 * pre-id shots still hash stably. Final weight is rounded to 0.01 g so
 * scale rounding drift doesn't change the hash.
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

// ── Settings-aware payload builder ────────────────────────────────────

/**
 * Build the JSON payload the Visualizer ingestion endpoint accepts.
 * Applies the four `visualizer*` user settings (privacy, includeProfile,
 * includeNotes) so the user's choices ride on the wire. The base payload
 * comes from `exportStoredShotAsV2Json` so the format is identical to
 * the per-shot Download button.
 */
function buildShotPayload(shot: StoredShot): string {
	const settings = getSettingsStore().current;
	const json = JSON.parse(exportStoredShotAsV2Json(shot)) as Record<string, unknown>;

	// Apply per-setting trims so the wire carries what the user chose.
	if (!settings.visualizerIncludeProfile) {
		// The Rust exporter writes `profile` as a stub when present; drop
		// the field entirely when the user opts out of profile sharing.
		delete json.profile;
	}
	if (!settings.visualizerIncludeNotes) {
		// `notes` live on the metadata block; null them out.
		const metadata = (json.metadata as Record<string, unknown> | undefined) ?? null;
		if (metadata && 'notes' in metadata) {
			metadata.notes = null;
		}
	}

	// Privacy + a Crema-side escape-valve block so the round-trip back
	// can re-bind. Visualizer ignores unknown keys per its
	// `additionalProperties: true` contract.
	json.privacy = settings.visualizerPrivacy;
	json.metadata = {
		...((json.metadata as Record<string, unknown> | undefined) ?? {}),
		crema: {
			localId: shot.id,
			signature: signatureForShot(shot),
			appVersion: '0.0.1'
		}
	};

	return JSON.stringify(json);
}

// ── HTTP plumbing ─────────────────────────────────────────────────────

interface FetchOptions {
	method?: 'GET' | 'POST' | 'PATCH' | 'DELETE';
	body?: string;
}

/**
 * Single authenticated HTTP entry point — wraps `withFreshToken` so 401
 * → refresh-once is automatic. Returns parsed JSON for 2xx + body;
 * `null` for 204. Throws {@link VisualizerError} on failure.
 */
async function call(path: string, opts: FetchOptions = {}): Promise<unknown> {
	const url = `${API_BASE}${path}`;
	let res: Response;
	try {
		res = await withFreshToken(async (token) => {
			const headers: Record<string, string> = {
				Authorization: `Bearer ${token}`,
				Accept: 'application/json'
			};
			if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
			const r = await fetch(url, {
				method: opts.method ?? 'GET',
				headers,
				body: opts.body
			});
			if (r.status === 401) {
				throw Object.assign(new Error('Unauthorized'), { status: 401, kind: 'auth' });
			}
			return r;
		});
	} catch (e) {
		if (e instanceof NotAuthenticatedError) {
			throw new VisualizerError(401, 'auth', e.message);
		}
		const err = e as { status?: number; kind?: string; message?: string };
		if (err && err.kind === 'auth') {
			throw new VisualizerError(
				401,
				'auth',
				'Visualizer rejected the access token. Please sign in again.'
			);
		}
		throw new VisualizerError(
			0,
			'network',
			`Network error: ${e instanceof Error ? e.message : String(e)}`
		);
	}
	if (res.status === 402 || res.status === 403) {
		const text = await res.text().catch(() => '');
		throw new VisualizerError(
			res.status,
			'premium',
			text || 'Premium subscription required for writes.'
		);
	}
	if (!res.ok) {
		const text = await res.text().catch(() => '');
		throw new VisualizerError(
			res.status,
			'other',
			`HTTP ${res.status}: ${text || res.statusText}`
		);
	}
	if (res.status === 204) return null;
	return res.json();
}

// ── Public surface ────────────────────────────────────────────────────

/**
 * Upload a single shot. POSTs to `/api/v1/shots` with the v2-JSON payload
 * (trimmed per the user's `visualizer*` settings). Resolves with the
 * remote `visualizerId` so the caller can bind it onto the local row.
 *
 * Throws {@link VisualizerError} on auth / network / premium /
 * upstream-5xx failures so the retry queue can branch on the failure
 * shape.
 */
export async function uploadShot(shot: StoredShot): Promise<{ visualizerId: string }> {
	if (!isConnected()) {
		throw new VisualizerError(401, 'auth', 'Sign in to Visualizer first.');
	}
	const body = buildShotPayload(shot);
	const result = (await call('/v1/shots', { method: 'POST', body })) as
		| { id?: string; data?: { id?: string } }
		| null;
	const remoteId =
		(result && (result.id ?? result.data?.id)) ?? null;
	if (!remoteId) {
		throw new VisualizerError(
			0,
			'other',
			'Visualizer accepted the shot but returned no id.'
		);
	}
	return { visualizerId: remoteId };
}

/**
 * Delete a remote shot by its Visualizer id. Best-effort — a 404 is
 * treated as success (it was already gone). Other errors propagate as
 * {@link VisualizerError} so the retry queue can branch.
 */
export async function deleteShot(visualizerId: string): Promise<void> {
	if (!isConnected()) {
		throw new VisualizerError(401, 'auth', 'Sign in to Visualizer first.');
	}
	try {
		await call(`/v1/shots/${visualizerId}`, { method: 'DELETE' });
	} catch (e) {
		if (e instanceof VisualizerError && e.status === 404) return;
		throw e;
	}
}

/**
 * Patch an existing remote shot's editable annotations (rating + notes).
 * Telemetry is immutable per Visualizer's model; only the journal
 * fields move.
 */
export async function patchShot(
	visualizerId: string,
	patch: { rating?: number; notes?: string }
): Promise<void> {
	if (!isConnected()) {
		throw new VisualizerError(401, 'auth', 'Sign in to Visualizer first.');
	}
	const body = JSON.stringify(patch);
	await call(`/v1/shots/${visualizerId}`, { method: 'PATCH', body });
}

/**
 * Pull remote shots updated since a cursor (Unix epoch ms). Returns the
 * decoded `WireShot` rows + the next cursor (for pagination). When the
 * server returns no `next_cursor` we treat the page as final.
 *
 * Naive single-request implementation for v1 per docs/36 §deferred:
 * paginated streaming for 1000+ shot accounts is a later cut.
 */
export async function pullShots(sinceMs: number): Promise<{
	shots: WireShot[];
	nextCursor: number | null;
}> {
	if (!isConnected()) {
		throw new VisualizerError(401, 'auth', 'Sign in to Visualizer first.');
	}
	const body = (await call(`/v1/shots?since=${Math.floor(sinceMs)}`)) as
		| ListResponse<WireShot>
		| WireShot[]
		| null;
	const shots = Array.isArray(body)
		? body
		: ((body?.data ?? []) as WireShot[]);
	const nextCursor =
		!Array.isArray(body) && body && typeof body.next_cursor === 'number'
			? body.next_cursor
			: null;
	return { shots, nextCursor };
}

// ── Reconciliation (docs/36 §3) ───────────────────────────────────────

/** One reconciliation outcome — what to do with a remote row. */
export type ReconcileAction =
	| { kind: 'add'; remote: WireShot }
	| { kind: 'update'; localId: string; remote: WireShot }
	| { kind: 'bind'; localId: string; visualizerId: string; remote: WireShot };

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
		// Signature match against unbound locals.
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
