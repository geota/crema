/**
 * `$lib/visualizer/upload-queue` — persistent retry queue for Visualizer
 * sync operations.
 *
 * When `ShotCompleted` fires (or a bean / roaster mutates) and the user
 * has push enabled, the upload is fire-and-forget. The HTTP call is
 * subject to network flakiness, 5xx upstream blips, and offline state —
 * none of which should lose a successful shot. So every push attempt
 * that fails for a *recoverable* reason gets enqueued here; the queue
 * persists to localStorage so a tab refresh doesn't drop it.
 *
 * The queue is drained:
 *   - on app launch (the store hydrates and triggers a single sweep);
 *   - on `online` events (we go from offline → online);
 *   - on every 5-min foreground tick (the auto-sync cadence);
 *   - on every successful sync run (sweep tail).
 *
 * Backoff per entry: 1s / 2s / 4s / 8s / 16s / 32s / 60s capped, with a
 * hard 3-attempt ceiling — past that the entry is dropped and the
 * failure surfaces in the sync log so the user knows.
 */

import { readJson, writeJson } from '$lib/utils/storage';
import { getHistoryStore } from '$lib/history';
import { VisualizerError } from '$lib/bean';
import { deleteShot, uploadShot } from './shot-sync';

/** localStorage key for the upload queue. */
const QUEUE_KEY = 'crema.visualizer.uploadQueue.v1';

/** Per-entry hard ceiling on retry attempts; after this it's dropped. */
const MAX_ATTEMPTS = 3;

/** One queued sync op. */
export interface QueueEntry {
	/** Which library the row belongs to. */
	entity: 'shot' | 'bean' | 'roaster';
	/** The local id (so we can find the row to re-upload). */
	id: string;
	/** The operation that failed. */
	op: 'create' | 'update' | 'delete';
	/**
	 * For DELETE we need the visualizer id (the local row may already be
	 * gone). For CREATE / UPDATE we look up the local row at attempt time
	 * so it picks up any in-the-meantime edits.
	 */
	visualizerId?: string;
	/** Attempt count. Capped at {@link MAX_ATTEMPTS}. */
	attempts: number;
	/** Last error message (for the sync log). */
	lastError?: string;
	/** Unix epoch ms when this entry was first enqueued. */
	enqueuedAt: number;
	/** Earliest Unix epoch ms when the queue may retry this entry. */
	nextAttemptAt: number;
}

interface QueueState {
	entries: QueueEntry[];
}

const EMPTY: QueueState = { entries: [] };

function read(): QueueState {
	const raw = readJson<QueueState>(QUEUE_KEY, EMPTY);
	if (!raw || !Array.isArray(raw.entries)) return EMPTY;
	return raw;
}

function write(state: QueueState): void {
	writeJson(QUEUE_KEY, state);
}

/**
 * Compute the next retry timestamp using exponential backoff (1s, 2s,
 * 4s, 8s, … capped at 60s).
 */
function backoffMs(attempt: number): number {
	const base = 1000 * Math.pow(2, Math.max(0, attempt));
	return Math.min(60_000, base);
}

/** Returns the current queue snapshot for the sync UI. */
export function getQueue(): readonly QueueEntry[] {
	return read().entries;
}

/** Returns true when the given local id has an entry in the queue. */
export function isPending(entity: QueueEntry['entity'], id: string): boolean {
	return read().entries.some((e) => e.entity === entity && e.id === id);
}

/**
 * Add (or refresh) an entry. If a matching entity+id+op tuple already
 * exists we update its attempts + nextAttemptAt rather than adding a
 * duplicate row — the queue is set-like, not multiset.
 */
export function enqueue(input: {
	entity: QueueEntry['entity'];
	id: string;
	op: QueueEntry['op'];
	visualizerId?: string;
	error?: string;
}): void {
	const state = read();
	const now = Date.now();
	const existingIdx = state.entries.findIndex(
		(e) => e.entity === input.entity && e.id === input.id && e.op === input.op
	);
	if (existingIdx >= 0) {
		const prev = state.entries[existingIdx];
		const attempts = prev.attempts + 1;
		state.entries = [
			...state.entries.slice(0, existingIdx),
			{
				...prev,
				attempts,
				lastError: input.error ?? prev.lastError,
				nextAttemptAt: now + backoffMs(attempts),
				visualizerId: input.visualizerId ?? prev.visualizerId
			},
			...state.entries.slice(existingIdx + 1)
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
	write(state);
}

/** Remove a queue entry by entity+id+op. */
export function dequeue(entity: QueueEntry['entity'], id: string, op: QueueEntry['op']): void {
	const state = read();
	const next = state.entries.filter((e) => !(e.entity === entity && e.id === id && e.op === op));
	if (next.length === state.entries.length) return;
	write({ entries: next });
}

/** Wipe the queue — escape hatch for tests / disconnect. */
export function clearQueue(): void {
	write(EMPTY);
}

// ── Draining ──────────────────────────────────────────────────────────

/** One drain outcome. */
export interface DrainResult {
	processed: number;
	succeeded: number;
	dropped: number;
	deferred: number;
}

/** Whether a drain is currently in flight (singleton guard). */
let draining = false;

/**
 * Drain the queue once. Walks every entry whose `nextAttemptAt` is
 * past, runs the appropriate HTTP call, and either dequeues on success
 * or re-enqueues with bumped attempts on failure. Premium-locked beans
 * and roaster pushes are dropped immediately so the queue doesn't spin
 * forever for free-tier users.
 *
 * Concurrent calls are coalesced — the second call returns the
 * `deferred` outcome without doing work.
 */
export async function drainQueue(): Promise<DrainResult> {
	if (draining) {
		return { processed: 0, succeeded: 0, dropped: 0, deferred: 1 };
	}
	if (typeof navigator !== 'undefined' && navigator.onLine === false) {
		return { processed: 0, succeeded: 0, dropped: 0, deferred: 1 };
	}
	draining = true;
	const out: DrainResult = { processed: 0, succeeded: 0, dropped: 0, deferred: 0 };
	try {
		const now = Date.now();
		const state = read();
		const ready = state.entries.filter((e) => e.nextAttemptAt <= now);
		for (const entry of ready) {
			out.processed += 1;
			try {
				await runEntry(entry);
				dequeue(entry.entity, entry.id, entry.op);
				out.succeeded += 1;
			} catch (e) {
				const recoverable = isRecoverable(e);
				const nextAttempts = entry.attempts + 1;
				if (!recoverable || nextAttempts >= MAX_ATTEMPTS) {
					dequeue(entry.entity, entry.id, entry.op);
					out.dropped += 1;
				} else {
					enqueue({
						entity: entry.entity,
						id: entry.id,
						op: entry.op,
						visualizerId: entry.visualizerId,
						error: e instanceof Error ? e.message : String(e)
					});
				}
			}
		}
	} finally {
		draining = false;
	}
	return out;
}

async function runEntry(entry: QueueEntry): Promise<void> {
	if (entry.entity === 'shot') {
		if (entry.op === 'create') {
			const shot = getHistoryStore().get(entry.id);
			if (!shot) return; // local row vanished — nothing to upload
			if (shot.visualizerId) return; // raced; already bound
			const { visualizerId } = await uploadShot(shot);
			getHistoryStore().bindVisualizerId(entry.id, visualizerId);
			return;
		}
		if (entry.op === 'delete' && entry.visualizerId) {
			await deleteShot(entry.visualizerId);
			getHistoryStore().purgeTombstone(entry.id);
			return;
		}
	}
	// bean / roaster ops are handled by the existing
	// $lib/bean/visualizer-sync layer's runSync; we never enqueue them
	// today. Future-proof: a no-op so a stale legacy entry doesn't crash.
}

/**
 * Classify whether an error is worth a retry. Network / 5xx / 408 are
 * recoverable; auth / premium / 4xx other are not (they need user
 * action, not time).
 */
function isRecoverable(e: unknown): boolean {
	if (e instanceof VisualizerError) {
		if (e.kind === 'network') return true;
		if (e.kind === 'premium') return false; // user must upgrade
		if (e.kind === 'auth') return false; // user must re-sign-in
		// status 0 = aborted; 5xx / 408 = transient.
		if (e.status === 0) return true;
		if (e.status === 408) return true;
		if (e.status >= 500 && e.status < 600) return true;
		return false;
	}
	// Unknown error shape — treat as recoverable. Worst case the queue
	// drops it after the attempt ceiling.
	return true;
}

// ── Lifecycle wiring ──────────────────────────────────────────────────

let lifecycleArmed = false;

/**
 * Wire the queue's lifecycle: drain on `online`, on a 5-minute
 * foreground tick, and on first call. When the user has Auto-sync on,
 * the 5-min tick also fires the on-launch upload sweep (any new shots
 * recorded while offline get pushed once the network returns).
 * Idempotent — calling this from multiple bootstraps is harmless.
 */
export function armQueueLifecycle(): void {
	if (lifecycleArmed) return;
	if (typeof window === 'undefined') return;
	lifecycleArmed = true;
	// Drain immediately so a backlog from a previous tab flushes. Don't
	// await — we never block app readiness on a network round-trip.
	void drainQueue();
	window.addEventListener('online', () => {
		void drainQueue();
	});
	// Every 5 min while in the foreground. The interval keeps running in
	// background tabs but `drainQueue` early-exits when offline / when
	// nothing's queued so it stays cheap.
	const FIVE_MIN = 5 * 60 * 1000;
	window.setInterval(() => {
		if (typeof document !== 'undefined' && document.hidden) return;
		if (typeof navigator !== 'undefined' && navigator.onLine === false) return;
		void drainQueue();
	}, FIVE_MIN);
}
