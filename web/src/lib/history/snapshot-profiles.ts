/**
 * `#lib/history/snapshot-profiles.js` — the as-run profiles the daily copy
 * needs, read without the curves on the main thread (#421).
 *
 * A profile lives in its row's `details` record with the curve, and
 * IndexedDB can only read a record whole, so reading 5,000 profiles also
 * reads 5,000 curves: about 4 s of main-thread work at a 6× CPU slowdown, and
 * the garbage after it. A worker reads them instead, and sends back only each
 * row's profile. Where there's no worker, or it fails, times out or answers
 * with an error, the rest are read here, as before: the copy never holds less
 * for it.
 */
import type { HistoryDb } from './history-db';

/** The profile of each id that has details; an id with none is left out. */
export type ProfilesOf = (ids: readonly string[]) => Promise<Map<string, unknown>>;

/** What the page asks the worker. */
export interface ProfileAsk {
	n: number;
	ids: string[];
}

/** What the worker answers: each id's profile, or why it couldn't. */
export type ProfileAnswer = { n: number; profiles: [string, unknown][] } | { n: number; error: string };

/** The profiles of `ids`, from `db`'s details. Missing details give no entry; a missing profile, null. */
export async function readProfiles(db: Pick<HistoryDb, 'detailsOf'>, ids: readonly string[]): Promise<Map<string, unknown>> {
	const details = await db.detailsOf(ids);
	const out = new Map<string, unknown>();
	for (const [id, d] of details) out.set(id, d.profile ?? null);
	return out;
}

/** The worker's answer to `ask`, read from `db`. */
export async function answerProfiles(db: Pick<HistoryDb, 'detailsOf'>, ask: ProfileAsk): Promise<ProfileAnswer> {
	try {
		return { n: ask.n, profiles: [...(await readProfiles(db, ask.ids))] };
	} catch (e) {
		return { n: ask.n, error: e instanceof Error ? e.message : String(e) };
	}
}

/** The part of a `Worker` the reader uses. */
export interface WorkerLike {
	onmessage: ((e: { data: unknown }) => void) | null;
	onerror: ((e: { preventDefault?: () => void }) => void) | null;
	onmessageerror: (() => void) | null;
	postMessage(m: ProfileAsk): void;
	terminate(): void;
}

/** A reader of profiles: {@link ProfilesOf}, and `close` when the copy is read. */
export interface ProfileReader {
	profilesOf: ProfilesOf;
	close(): void;
}

/** How long one batch may take in the worker before the rest are read here. */
export const PROFILE_WORKER_TIMEOUT_MS = 30_000;

/**
 * Profiles from a worker `make` starts, falling back to `here` for the batch
 * that failed and every batch after it.
 */
export function workerProfileReader(
	make: () => WorkerLike | null,
	here: ProfilesOf,
	timeoutMs = PROFILE_WORKER_TIMEOUT_MS
): ProfileReader {
	let worker: WorkerLike | null = null;
	let done = false;
	let n = 0;
	const waiting = new Map<number, { resolve: (m: Map<string, unknown>) => void; reject: (e: Error) => void; timer: ReturnType<typeof setTimeout> }>();
	const stop = (why: string) => {
		done = true;
		try {
			worker?.terminate();
		} catch {
			// Gone already.
		}
		worker = null;
		for (const w of waiting.values()) {
			clearTimeout(w.timer);
			w.reject(new Error(why));
		}
		waiting.clear();
	};
	const start = (): WorkerLike | null => {
		if (worker || done) return worker;
		try {
			worker = make();
		} catch {
			worker = null;
		}
		if (!worker) {
			done = true;
			return null;
		}
		worker.onmessage = (e) => {
			const a = e.data as ProfileAnswer;
			const w = waiting.get(a?.n);
			if (!w) return;
			waiting.delete(a.n);
			clearTimeout(w.timer);
			if ('profiles' in a && Array.isArray(a.profiles)) w.resolve(new Map(a.profiles));
			else w.reject(new Error('error' in a ? a.error : 'no answer'));
		};
		worker.onerror = (e) => {
			e.preventDefault?.();
			stop('The profile worker failed.');
		};
		worker.onmessageerror = () => stop('A profile answer could not be read.');
		return worker;
	};
	const ask = (ids: readonly string[]): Promise<Map<string, unknown>> => {
		const w = start();
		if (!w) return Promise.reject(new Error('No profile worker.'));
		const k = ++n;
		return new Promise((resolve, reject) => {
			const timer = setTimeout(() => stop('The profile worker timed out.'), timeoutMs);
			waiting.set(k, { resolve, reject, timer });
			try {
				w.postMessage({ n: k, ids: [...ids] });
			} catch {
				stop('The profile worker could not be asked.');
			}
		});
	};
	return {
		async profilesOf(ids) {
			if (ids.length === 0) return new Map();
			if (!done) {
				try {
					return await ask(ids);
				} catch {
					// Read here from now on: the worker is stopped.
					stop('The profile worker gave up.');
				}
			}
			return here(ids);
		},
		close() {
			stop('The copy is read.');
		}
	};
}
