/**
 * `#lib/visualizer/upload-marks.js` — what a delete needs to know about uploads
 * (geota/crema#331). Android keeps the same in `VisualizerState`.
 *
 * A POST can land after its row is deleted, so a delete records intent and a
 * binding can arrive later. An attempt mark is stored before each POST and goes
 * on a bind or a refusal, so a delete during an upload knows there may be a remote
 * copy, even after a kill. Such a delete leaves a soft-deleted row with no id,
 * marked here: an unbound tombstone. It ends when the id arrives, or when a
 * complete pull that started after it was written and covered the window the
 * upload would have landed in finds no match. There's no time limit: a mark is
 * small, and a shot coming back is the bug.
 *
 * Synchronous and storage-backed: a mark must be down before the POST goes.
 */

import { isJsonObject, readJson, writeJson } from '../utils/storage.ts';
import { pullCoversAttempt } from '#lib/wasm/de1_wasm.js';

const MARKS_KEY = 'crema.visualizer.uploadMarks.v1';

/** A soft-deleted row waiting for its id. */
export interface UnboundTombstone {
	/** The pull run when this was written. */
	run: number;
	/** The user asked to delete the Visualizer copy too. */
	deleteRemote: boolean;
	/** When the POST went (unix ms), or `null` when nothing recorded it. */
	attemptedAt: number | null;
	/**
	 * The account the POST went to, while another is signed in. Its rows aren't
	 * this account's to match, and a pull of this one says nothing about it, so it
	 * waits for that account to come back.
	 */
	parkedFor?: string | null;
}

interface UploadMarks {
	/** Shots a POST may have reached Visualizer for, with when (unix ms). */
	attempted: Record<string, number>;
	unbound: Record<string, UnboundTombstone>;
	/** Counts pulls, to order them against {@link UnboundTombstone.run}. */
	pullRun: number;
	/**
	 * Remote ids whose row was gone when they arrived. There's no row to hold a
	 * tombstone, so a pull leaves these out instead.
	 */
	keptOut: string[];
}

/** POSTs out from this tab: a tombstone isn't expired under one. */
const inFlight = new Set<string>();

function read(): UploadMarks {
	const raw = readJson<Partial<UploadMarks>>(MARKS_KEY, {}, { what: 'upload marks', valid: isJsonObject });
	return {
		attempted: raw.attempted ?? {},
		unbound: raw.unbound ?? {},
		pullRun: raw.pullRun ?? 0,
		keptOut: raw.keptOut ?? []
	};
}

/** Whether a POST may have reached Visualizer for `id`. */
export function uploadAttempted(id: string): boolean {
	return id in read().attempted;
}

/** A POST for `id` is about to go. */
export function markUploadAttempted(id: string): void {
	inFlight.add(id);
	const marks = read();
	writeJson(MARKS_KEY, { ...marks, attempted: { ...marks.attempted, [id]: Date.now() } });
}

/** The POST for `id` has come back, however it went. */
export function uploadReturned(id: string): void {
	inFlight.delete(id);
}

/** The server took nothing for `id`. */
export function clearUploadAttempted(id: string): void {
	const marks = read();
	if (!(id in marks.attempted)) return;
	const { [id]: _, ...attempted } = marks.attempted;
	writeJson(MARKS_KEY, { ...marks, attempted });
}

/** `id` was deleted with its upload under way. */
export function markUnboundTombstone(id: string, deleteRemote: boolean): void {
	const marks = read();
	const attemptedAt = marks.attempted[id] ?? (inFlight.has(id) ? Date.now() : null);
	const tombstone: UnboundTombstone = { run: marks.pullRun, deleteRemote, attemptedAt };
	writeJson(MARKS_KEY, { ...marks, unbound: { ...marks.unbound, [id]: tombstone } });
}

/** `id`'s binding arrived, or its row left: drop its marks, and answer its tombstone. */
export function settleUploadMarks(id: string): UnboundTombstone | null {
	const marks = read();
	const tombstone = marks.unbound[id] ?? null;
	if (!(id in marks.attempted) && tombstone === null) return null;
	const { [id]: _attempt, ...attempted } = marks.attempted;
	const { [id]: _tombstone, ...unbound } = marks.unbound;
	writeJson(MARKS_KEY, { ...marks, attempted, unbound });
	return tombstone;
}

/**
 * The account changed from `oldId` to `newId`. Attempt marks were about the old
 * one and go. Unbound tombstones stay parked for the account the POST went to,
 * and `newId`'s own come back into play.
 */
export function settleMarksForAccount(oldId: string | null, newId: string): void {
	const marks = read();
	const unbound = Object.fromEntries(
		Object.entries(marks.unbound).map(([id, t]) => {
			if (t.parkedFor === newId) return [id, { ...t, parkedFor: null }];
			return [id, t.parkedFor ? t : { ...t, parkedFor: oldId }];
		})
	);
	writeJson(MARKS_KEY, { ...marks, attempted: {}, unbound });
}

/** The tombstones parked for another account: a pull leaves them out. */
export function parkedTombstones(): ReadonlySet<string> {
	return new Set(
		Object.entries(read().unbound)
			.filter(([, t]) => t.parkedFor)
			.map(([id]) => id)
	);
}

/** `visualizerId` came back for a row that's gone: a pull must not add it. */
export function keepRemoteOut(visualizerId: string): void {
	const marks = read();
	if (marks.keptOut.includes(visualizerId)) return;
	writeJson(MARKS_KEY, { ...marks, keptOut: [...marks.keptOut, visualizerId] });
}

/** The remote ids a pull leaves out. */
export function remoteKeptOut(): ReadonlySet<string> {
	return new Set(read().keptOut);
}

/** A pull starts: its run number. */
export function beginPullRun(): number {
	const marks = read();
	const pullRun = marks.pullRun + 1;
	writeJson(MARKS_KEY, { ...marks, pullRun });
	return pullRun;
}

/**
 * The tombstones complete pull `run` from `sinceMs` has ended: written before it
 * started, not matched, since a match settles the mark, and with the window the
 * upload would have landed in covered. Not one with its POST still out, whose
 * answer needs the row to land on.
 */
export function expiredTombstones(run: number, sinceMs: number): string[] {
	return Object.entries(read().unbound)
		.filter(
			([id, t]) =>
				!t.parkedFor &&
				t.run < run &&
				!inFlight.has(id) &&
				pullCoversAttempt(sinceMs, t.attemptedAt ?? undefined)
		)
		.map(([id]) => id);
}
