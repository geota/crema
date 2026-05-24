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
import {
	reconcileShots,
	signatureForBean,
	signatureForRoaster,
	signatureForShot,
	storedShotFromWire,
	type ReconcileAction,
	type WireShot
} from './shot-sync-signatures';

/** Visualizer API base. */
const API_BASE = 'https://visualizer.coffee/api';

// Re-export the pure helpers so existing import paths through
// `$lib/visualizer` continue to resolve via this module.
export {
	reconcileShots,
	signatureForBean,
	signatureForRoaster,
	signatureForShot,
	storedShotFromWire,
	type ReconcileAction,
	type WireShot
};

interface ListResponse<T> {
	data?: T[];
	page?: number;
	per_page?: number;
	total?: number;
	next_cursor?: number | null;
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
 * Pull one page of remote shots updated since a cursor (Unix epoch ms).
 * Returns the decoded `WireShot` rows + the next cursor when the server
 * paginates. When the server returns no `next_cursor` we treat the page
 * as final. Callers wanting the entire history should use
 * {@link pullAllShots} which paginates this end-to-end.
 *
 * `perPage` defaults to 50 — small enough to keep each request fast,
 * large enough that even a 1000-shot account converges in 20 round-trips.
 */
export async function pullShots(
	sinceMs: number,
	perPage = 50
): Promise<{
	shots: WireShot[];
	nextCursor: number | null;
}> {
	if (!isConnected()) {
		throw new VisualizerError(401, 'auth', 'Sign in to Visualizer first.');
	}
	const since = Math.floor(sinceMs);
	const body = (await call(
		`/v1/shots?since=${since}&per_page=${perPage}`
	)) as ListResponse<WireShot> | WireShot[] | null;
	const shots = Array.isArray(body)
		? body
		: ((body?.data ?? []) as WireShot[]);
	const nextCursor =
		!Array.isArray(body) && body && typeof body.next_cursor === 'number'
			? body.next_cursor
			: null;
	return { shots, nextCursor };
}

/**
 * Progress callback fired between pages when {@link pullAllShots}
 * walks pagination. UIs can wire this to a progress bar or counter.
 */
export interface PullProgress {
	/** Number of shots fetched so far across all pages. */
	fetched: number;
	/** Page index just completed (0-based). */
	page: number;
	/** Whether more pages remain. */
	hasMore: boolean;
}

/**
 * Pull every remote shot newer than `sinceMs`, walking the server's
 * `next_cursor` pagination until exhausted. Yields the merged result + a
 * per-page progress hook so UIs can render a progress bar during the
 * initial sync of a large history (1000+ shots → ~20 round-trips at the
 * default `perPage = 50`).
 *
 * **Safety cap**: walks at most `maxPages` pages (default 200 → 10k shots
 * at perPage=50) so a malformed `next_cursor` loop can't pin the tab.
 * Returns a partial result + `truncated: true` when the cap fires.
 */
export async function pullAllShots(
	sinceMs: number,
	opts: {
		perPage?: number;
		maxPages?: number;
		onProgress?: (p: PullProgress) => void;
	} = {}
): Promise<{ shots: WireShot[]; truncated: boolean }> {
	const perPage = opts.perPage ?? 50;
	const maxPages = opts.maxPages ?? 200;
	const all: WireShot[] = [];
	let cursor = sinceMs;
	for (let page = 0; page < maxPages; page++) {
		const { shots, nextCursor } = await pullShots(cursor, perPage);
		all.push(...shots);
		opts.onProgress?.({
			fetched: all.length,
			page,
			hasMore: nextCursor != null
		});
		if (nextCursor == null) {
			return { shots: all, truncated: false };
		}
		// Advance the cursor. If the server returns the same cursor twice
		// (broken pagination), break to avoid an infinite loop.
		if (nextCursor <= cursor) {
			return { shots: all, truncated: false };
		}
		cursor = nextCursor;
	}
	return { shots: all, truncated: true };
}

// Reconciliation, signature helpers, and the WireShot type live in
// `./shot-sync-signatures.ts` — pure, no wasm / fetch / settings.
