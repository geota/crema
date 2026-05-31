/**
 * `$lib/services/queue-store` — the pure localStorage persistence layer for the
 * Visualizer upload queue (docs/53 §1.3, T-14/T-16).
 *
 * This is deliberately Effect-free: it is the durable store the `UploadQueue`
 * service wraps in `Effect.sync`, and the seam `ShotSync.uploadUnsyncedShots`
 * uses to register a recoverable failure without taking a dependency on
 * `UploadQueue` (which would be a cycle — `UploadQueue` consumes `ShotSync`).
 * Extracting it here is what lets the old `visualizer/upload-queue.ts` module be
 * deleted at T-16: both the service and `ShotSync` now read/write this one
 * store, keyed `crema.visualizer.uploadQueue.v1` with the exact entry shape the
 * old module persisted (so a tab carrying a pre-swap queue stays coherent).
 *
 * `attempts` semantics match T-14: `enqueueEntry` does NOT bump the counter on
 * an existing entry — the drain owns it (a fresh entry starts at 0; each failed
 * drain calls `persistRetry` to bump). The sync-UI snapshot read (`readQueue`)
 * is synchronous so reactive components can derive a pip without the runtime.
 */

import { readJson, writeJson } from '../utils/storage.ts';

/** localStorage key for the upload queue. */
const QUEUE_KEY = 'crema.visualizer.uploadQueue.v1';

/** Per-entry hard ceiling on retry attempts; after this it's dropped. */
export const MAX_ATTEMPTS = 3;

/** One queued sync op. */
export interface QueueEntry {
	entity: 'shot' | 'bean' | 'roaster';
	id: string;
	op: 'create' | 'update' | 'delete';
	/** For DELETE we need the visualizer id (the local row may already be gone). */
	visualizerId?: string;
	/** Failed-attempts made so far. Capped at {@link MAX_ATTEMPTS}. */
	attempts: number;
	lastError?: string;
	enqueuedAt: number;
	/** Earliest Unix epoch ms when the queue may retry this entry. */
	nextAttemptAt: number;
}

interface QueueState {
	entries: QueueEntry[];
}

/**
 * Read + validate the persisted queue (empty on absent / malformed).
 *
 * Returns a FRESH `{ entries: [] }` on the empty/malformed path — never a shared
 * constant. `enqueueEntry` / `dequeueEntry` reassign `state.entries`, so handing
 * back a shared empty object would let an enqueue-into-empty mutate it and
 * corrupt every later empty read (a latent bug carried over from the old
 * `visualizer/upload-queue.ts`; see queue-store.test.ts).
 */
export function readQueue(): QueueState {
	const raw = readJson<QueueState | null>(QUEUE_KEY, null);
	if (!raw || !Array.isArray(raw.entries)) return { entries: [] };
	return raw;
}

function writeQueue(state: QueueState): void {
	writeJson(QUEUE_KEY, state);
}

/** Exponential backoff (2s, 4s, 8s, … capped at 60s) keyed on attempts made. */
export function backoffMs(attempt: number): number {
	const base = 1000 * Math.pow(2, Math.max(0, attempt));
	return Math.min(60_000, base);
}

const sameEntry =
	(entity: QueueEntry['entity'], id: string, op: QueueEntry['op']) => (e: QueueEntry) =>
		e.entity === entity && e.id === id && e.op === op;

/** Input shape for {@link enqueueEntry}. */
export interface EnqueueInput {
	entity: QueueEntry['entity'];
	id: string;
	op: QueueEntry['op'];
	visualizerId?: string;
	error?: string;
}

/**
 * Register a sync op (set-like on entity+id+op). A fresh entry starts at
 * `attempts: 0`; an existing one only has its `lastError` / `visualizerId`
 * refreshed — the drain owns the attempt counter + schedule (T-14).
 */
export function enqueueEntry(input: EnqueueInput): void {
	const state = readQueue();
	const idx = state.entries.findIndex(sameEntry(input.entity, input.id, input.op));
	const now = Date.now();
	if (idx >= 0) {
		const prev = state.entries[idx];
		state.entries = [
			...state.entries.slice(0, idx),
			{
				...prev,
				lastError: input.error ?? prev.lastError,
				visualizerId: input.visualizerId ?? prev.visualizerId
			},
			...state.entries.slice(idx + 1)
		];
	} else {
		state.entries = [
			...state.entries,
			{
				entity: input.entity,
				id: input.id,
				op: input.op,
				visualizerId: input.visualizerId,
				attempts: 0,
				lastError: input.error,
				enqueuedAt: now,
				nextAttemptAt: now
			}
		];
	}
	writeQueue(state);
}

/** Remove a queue entry by entity+id+op. */
export function dequeueEntry(
	entity: QueueEntry['entity'],
	id: string,
	op: QueueEntry['op']
): void {
	const state = readQueue();
	const next = state.entries.filter((e) => !sameEntry(entity, id, op)(e));
	if (next.length !== state.entries.length) writeQueue({ entries: next });
}

/** Persist a failed entry's bumped attempt count + next-retry time. */
export function persistRetry(entry: QueueEntry, made: number, error: string): void {
	const state = readQueue();
	const idx = state.entries.findIndex(sameEntry(entry.entity, entry.id, entry.op));
	if (idx < 0) return; // dequeued meanwhile
	state.entries = [
		...state.entries.slice(0, idx),
		{
			...state.entries[idx],
			attempts: made,
			lastError: error,
			nextAttemptAt: Date.now() + backoffMs(made)
		},
		...state.entries.slice(idx + 1)
	];
	writeQueue(state);
}

/** Whether the given local id has any entry queued (any op). */
export function isPendingId(entity: QueueEntry['entity'], id: string): boolean {
	return readQueue().entries.some((e) => e.entity === entity && e.id === id);
}

/** Wipe the queue. */
export function clearQueue(): void {
	writeQueue({ entries: [] });
}
