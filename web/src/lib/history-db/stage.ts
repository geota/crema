/**
 * `#lib/history-db/stage.js` — which stage this tab boots on, and the flip
 * (#310 SPEC §11 "When the flip may run", Marker, Done-record; PLAN W5, M2,
 * M8, S9). Run once at a fresh page boot, before the history client starts:
 *
 * 1. **Marker `done`**: stage 2 on the file it names.
 * 2. **No marker, a done-record**: the marker is rebuilt from it, stage 2.
 *    One that doesn't parse never allows a flip: it exists only after a file
 *    said `done`, so the Worker looks for that file. Found: the marker, stage
 *    2. None `done` in a pool that listed: `history.db`, which is `lost`
 *    (never a re-migration). A scan skipped or failed: nothing is persisted,
 *    and the boot is `unavailable` until the next one looks again. A read
 *    that throws is no OPFS this boot: stage 1, nothing written (REVIEW-473 B2).
 * 3. **Otherwise, when the flip may run** (a fresh boot, never a handover;
 *    the backoff allows; `crema-history-stage` exclusive with `ifAvailable`;
 *    the service worker's `clients.matchAll` shows one window, this one): a
 *    Worker started for it takes the owner lock and runs `flip.ts`. Its
 *    `found` or `done` writes the marker (after the done-record, which the
 *    Worker wrote) and broadcasts `flipped`; then stage 2. A failure writes
 *    the backoff into the marker, and the tab stays on stage 1 with the P3
 *    store as the truth. A Worker that dies, errs or hangs after it reached
 *    `done` is never a failure on its word: the page scans, and boots on the
 *    file if it says `done` (REVIEW-473 B1). The stage lock is held until the marker is written,
 *    so a tab opened meanwhile waits, then reads it.
 * 4. **Otherwise** stage 1. A tab that couldn't take the lock exclusive waits
 *    for its shared hold first, so a flip in another tab finishes, and reads
 *    the marker after.
 *
 * Nothing here writes the P3 store, deletes a file, or turns a `done` marker
 * back into a pending one.
 */
import type { ChannelLike, LockManagerLike } from './ports.js';
import type { WorkerLike } from './client.js';
import type { PageToWorker, WorkerToPage } from './owner.js';
import type { FlipCapture, FlipOutcome } from './flip.js';
import { INDEX_FILE, RPC_CHANNEL, STAGE_LOCK } from './names.js';
import {
	afterFailure,
	dbFileName,
	mayTry,
	readDoneRecord,
	readMarker,
	writeMarker,
	type DoneRecordReader,
	type Marker,
	type MarkerStorage
} from './marker.js';
import { PROTOCOL_VERSION, type Expect, type Journal, type RpcMessage, type Stage, type Target } from './protocol.js';

/** How long the flip may take before this boot gives up on it and stays on stage 1. */
export const FLIP_TIMEOUT_MS = 5 * 60_000;
/** How long the service worker has to say how many windows are open. */
export const WINDOW_COUNT_MS = 2_000;

export interface StageDeps {
	storage: MarkerStorage;
	locks: LockManagerLike;
	doneRecord: DoneRecordReader | null;
	/** How many Crema windows the service worker sees (`clients.matchAll`), or null with none. */
	windowCount(): Promise<number | null>;
	/** A history Worker for the flip alone. */
	makeWorker(): WorkerLike;
	channel(name: string): ChannelLike;
	now(): number;
	build: string;
	journal: Journal;
	/** May this build flip? (W5 turns it on.) */
	allowFlip: boolean;
	/** The pools' repair generations (0; the tests keep apart by them). */
	indexGeneration?: number;
	poolGeneration?: number;
	flipTimeoutMs?: number;
	/** Each step the flip reaches (diagnostics, the tests' kill point). */
	onFlipStep?(step: string): void;
	log?(line: string): void;
}

export interface StageChoice {
	stage: Stage;
	target: Target;
	expect: Expect;
	/** Why this stage, for diagnostics: `marker`, `doneRecord`, `flipped`, `found`, `noFlip`, `waiting:…`, `failed:…`. */
	reason: string;
	/** The flip ran this boot: its outcome. */
	flip?: FlipOutcome;
	/**
	 * Stage 2, but which file is the truth couldn't be told this boot (a scan
	 * that was skipped or failed): no client starts, the store is
	 * `unavailable` with new shots in the pending journal, nothing is
	 * persisted, and the next boot looks again (REVIEW-473 B1, B2).
	 */
	unavailable?: string;
	/**
	 * A flip that ran this boot: report each P3 write a capture makes while it
	 * runs, with its `writeSeq` (M9). A no-op before and after.
	 */
	marker: Marker | null;
}

/** The ids of the captures the flip reported (M9): step 8 imports those the copy lacks, in the same boot. */
export type FlipCaptureSink = (c: FlipCapture) => void;

function stage1(d: StageDeps, reason: string, marker: Marker | null, flip?: FlipOutcome): StageChoice {
	const g = d.indexGeneration ?? 0;
	return {
		stage: 1,
		target: { pool: 'index', generation: g, file: INDEX_FILE, role: 'index', create: true, journal: d.journal },
		expect: { role: 'index', file: INDEX_FILE },
		reason,
		marker,
		...(flip ? { flip } : {})
	};
}

function stage2(d: StageDeps, m: { generation: number; poolGeneration: number }, reason: string, marker: Marker | null, flip?: FlipOutcome): StageChoice {
	const file = dbFileName(m.generation);
	return {
		stage: 2,
		// `create: false`: an `authority` file is never made by an open; an absent one is `lost`.
		target: { pool: 'db', generation: m.poolGeneration, file, role: 'authority', create: false, journal: d.journal },
		expect: { role: 'authority', file, generation: m.poolGeneration },
		reason,
		marker,
		...(flip ? { flip } : {})
	};
}

/** Holds `crema-history-stage` shared for the tab's life, once granted (M2). */
function holdShared(locks: LockManagerLike): Promise<void> {
	return new Promise<void>((granted) => {
		void locks
			.request(STAGE_LOCK, { mode: 'shared' }, () => {
				granted();
				return new Promise<void>(() => {});
			})
			.catch(() => granted());
	});
}

/** Chooses this tab's stage, flipping first when it may (see the module's doc). */
export async function chooseStage(d: StageDeps, captures?: { attach(sink: FlipCaptureSink | null): void }): Promise<StageChoice> {
	let marker = readMarker(d.storage);
	if (marker?.state === 'done') return stage2(d, marker, 'marker', marker);

	const record = await readDoneRecord(d.doneRecord);
	if (record.kind === 'done') {
		marker = { state: 'done', ...record.record, at: d.now(), rebuilt: 'doneRecord' };
		trySetMarker(d, marker);
		return stage2(d, marker, 'doneRecord', marker);
	}
	if (!d.allowFlip) return stage1(d, 'noFlip', marker);
	// The read itself threw: as with no OPFS, no flip this boot, nothing written (REVIEW-473 B2).
	if (record.kind === 'readFailed') return stage1(d, `doneRecordReadFailed:${record.error}`, marker);
	if (record.kind === 'unreadable') {
		// Some file said `done`: find it, never copy (M8), and write the
		// marker only for what the scan found (REVIEW-473 B2).
		const m0 = marker;
		return scanBoot(d, { doneRecordSaysDone: true, marker: m0, onNothing: () => stage1(d, 'scan:nothing', m0) }, captures);
	}
	if (!mayTry(marker, d.now())) {
		await holdShared(d.locks);
		const again = readMarker(d.storage);
		if (again?.state === 'done') return stage2(d, again, 'marker', again);
		return stage1(d, 'backoff', again);
	}

	let choice: StageChoice | null = null;
	await d.locks.request(STAGE_LOCK, { mode: 'exclusive', ifAvailable: true }, async (lock) => {
		if (!lock) return;
		choice = await underStageLock(d, marker, captures);
	});
	if (choice) {
		await holdShared(d.locks);
		return choice;
	}
	// Another tab is open, or flipping: wait for it, then read the marker.
	noteWaiting(d, 'another window is open');
	await holdShared(d.locks);
	const again = readMarker(d.storage);
	if (again?.state === 'done') return stage2(d, again, 'marker', again);
	return stage1(d, 'waiting:otherTab', again);
}

/** Inside the exclusive stage lock: the window count, then the flip. */
async function underStageLock(
	d: StageDeps,
	marker: Marker | null,
	captures?: { attach(sink: FlipCaptureSink | null): void }
): Promise<StageChoice> {
	// The marker may have been written while this tab waited to boot.
	const now = readMarker(d.storage);
	if (now?.state === 'done') return stage2(d, now, 'marker', now);
	// Tabs of builds before W1 hold no stage lock: the service worker sees them (M2).
	const windows = await withTimeout(d.windowCount().catch(() => null), WINDOW_COUNT_MS, null);
	if (windows !== 1) {
		noteWaiting(d, windows === null ? 'no service worker' : 'another window is open');
		return stage1(d, windows === null ? 'waiting:noServiceWorker' : 'waiting:windows', readMarker(d.storage));
	}
	const out = await runFlipWorker(d, { scanOnly: false, rebuildIndex: marker?.state === 'pending' && marker.rebuildIndex }, captures);
	switch (out.outcome) {
		case 'found':
		case 'done': {
			const m: Marker = {
				state: 'done',
				generation: out.generation,
				poolGeneration: out.poolGeneration,
				at: d.now(),
				...(out.outcome === 'found' ? { rebuilt: 'file' as const } : {})
			};
			// After the done-record, which the Worker wrote (M8). If storage
			// refuses, the done-record rebuilds it at the next boot.
			trySetMarker(d, m);
			broadcastFlipped(d, out.generation);
			return stage2(d, m, out.outcome === 'done' ? 'flipped' : 'found', m, out);
		}
		case 'failed': {
			if (out.afterDone) {
				// The Worker went after `done`: the file may be the truth. Look,
				// never boot the P3 store on a guess (REVIEW-473 B1).
				return scanBoot(
					d,
					{
						doneRecordSaysDone: false,
						marker,
						onNothing: () => {
							const m = afterFailure(marker, out.reason, d.now(), out.rebuildIndex);
							trySetMarker(d, m);
							return stage1(d, `failed:${out.reason}`, m, out);
						}
					},
					captures
				);
			}
			const m = afterFailure(marker, out.reason, d.now(), out.rebuildIndex);
			trySetMarker(d, m);
			d.log?.(`the flip failed (${out.reason}); staying on the P3 store, next try ${m.nextTryAt ? new Date(m.nextTryAt).toISOString() : 'next boot'}`);
			return stage1(d, `failed:${out.reason}`, m, out);
		}
		case 'skipped':
		case 'nothing':
			return stage1(d, `skipped:${out.outcome === 'skipped' ? out.reason : 'nothing'}`, marker, out);
	}
}

/** Stage 2 with its truth unknown this boot: nothing written, the next boot scans again. */
function unavailable(d: StageDeps, reason: string, marker: Marker | null, flip?: FlipOutcome): StageChoice {
	return { ...stage2(d, { generation: 0, poolGeneration: d.poolGeneration ?? 0 }, `unavailable:${reason}`, marker, flip), unavailable: reason };
}

/**
 * A scan for the finished file (step 1 only, never a copy), and the boot it
 * gives: `found` writes the marker; `nothing` (the pool installed and listed,
 * no file `done`) writes it for `history.db`, which is `lost` if the
 * done-record said so, else stage 1; anything else persists nothing and boots
 * `unavailable` (REVIEW-473 B2).
 */
async function scanBoot(
	d: StageDeps,
	o: { doneRecordSaysDone: boolean; marker: Marker | null; onNothing: () => StageChoice },
	captures?: { attach(sink: FlipCaptureSink | null): void }
): Promise<StageChoice> {
	const out = await runFlipWorker(d, { scanOnly: true, rebuildIndex: false }, captures);
	if (out.outcome === 'found') {
		const m: Marker = { state: 'done', generation: out.generation, poolGeneration: out.poolGeneration, at: d.now(), rebuilt: 'file' };
		trySetMarker(d, m);
		return stage2(d, m, 'found', m, out);
	}
	if (out.outcome === 'nothing') {
		if (!o.doneRecordSaysDone) return o.onNothing();
		const m: Marker = { state: 'done', generation: 0, poolGeneration: d.poolGeneration ?? 0, at: d.now(), rebuilt: 'file' };
		trySetMarker(d, m);
		return stage2(d, m, 'doneRecordNoFile', m, out);
	}
	return unavailable(d, out.outcome === 'skipped' ? `scan:${out.reason}` : `scan:${out.outcome}`, o.marker, out);
}

function trySetMarker(d: StageDeps, m: Marker): void {
	try {
		writeMarker(d.storage, m);
	} catch (e) {
		d.log?.(`the marker couldn't be written (${(e as Error).name}); the done-record keeps it`);
	}
}

/** Records why this boot didn't flip, for Settings (SPEC §11: "If a flip waits because another tab is open, Settings says so"). */
function noteWaiting(d: StageDeps, why: string): void {
	const m = readMarker(d.storage);
	if (m?.state === 'done') return;
	const base = m ?? { state: 'pending' as const, failures: 0, nextTryAt: 0, lastError: null, rebuildIndex: false };
	trySetMarker(d, { ...base, waiting: why });
}

function broadcastFlipped(d: StageDeps, generation: number): void {
	try {
		const ch = d.channel(RPC_CHANNEL);
		ch.postMessage({ t: 'flipped', v: PROTOCOL_VERSION, build: d.build, generation } satisfies RpcMessage);
		ch.close();
	} catch {
		// A tab that misses it reads the marker as `done` at its next boot.
	}
}

function withTimeout<T>(p: Promise<T>, ms: number, fallback: T): Promise<T> {
	return new Promise<T>((resolve) => {
		const t = setTimeout(() => resolve(fallback), ms);
		void p.then(
			(v) => {
				clearTimeout(t);
				resolve(v);
			},
			() => {
				clearTimeout(t);
				resolve(fallback);
			}
		);
	});
}

/** Starts a Worker for the flip, runs it, and ends the Worker. A Worker that dies or hangs is a failure, tried again at the next boot. */
async function runFlipWorker(
	d: StageDeps,
	o: { scanOnly: boolean; rebuildIndex: boolean },
	captures?: { attach(sink: FlipCaptureSink | null): void }
): Promise<FlipOutcome> {
	const w = d.makeWorker();
	const n = 1;
	// Whether the Worker reached `done`: after it, a dying Worker may have left the file `done`.
	let afterDone = false;
	const died = (reason: string): FlipOutcome => ({ outcome: 'failed', reason, rebuildIndex: o.rebuildIndex, ...(afterDone ? { afterDone } : {}) });
	try {
		return await new Promise<FlipOutcome>((resolve) => {
			const t = setTimeout(() => resolve(died('timeout')), d.flipTimeoutMs ?? FLIP_TIMEOUT_MS);
			const end = (out: FlipOutcome) => {
				clearTimeout(t);
				resolve(out);
			};
			w.onmessage = (e) => {
				const m = e.data as WorkerToPage;
				if (m.t === 'flipStep' && m.n === n) {
					if (m.step === 'done' || m.step === 'doneRecord') afterDone = true;
					d.onFlipStep?.(m.step);
				}
				if (m.t === 'flipDone' && m.n === n) end(m.outcome);
			};
			w.onerror = () => end(died('workerError'));
			captures?.attach((capture) => w.postMessage({ t: 'flipCapture', capture } satisfies PageToWorker));
			w.postMessage({
				t: 'flip',
				n,
				args: {
					indexGeneration: d.indexGeneration ?? 0,
					poolGeneration: d.poolGeneration ?? 0,
					journal: d.journal,
					rebuildIndex: o.rebuildIndex,
					scanOnly: o.scanOnly
				}
			} satisfies PageToWorker);
		});
	} finally {
		captures?.attach(null);
		w.terminate();
	}
}
