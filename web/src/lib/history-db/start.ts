/**
 * `#lib/history-db/start.js` — the app's history store (#310 PLAN W1). The
 * shell starts it at boot; nothing reads it yet (W2 does). This build is
 * stage 1: the owner's Worker keeps `history-index.db` in `crema-index`, and
 * never installs `crema-db`.
 *
 * Where a browser has no Worker, `BroadcastChannel` or Web Locks it doesn't
 * start, and the history stays on the P3 store, as on a browser without OPFS
 * sync access handles (owner question 5).
 */
import type { LockManagerLike } from './ports.js';
import type { ChannelLike } from './ports.js';
import { HistoryDbClient, type PageEvents, type WorkerLike } from './client.js';
import { HISTORY_JOURNAL } from './journal.js';
import { INDEX_FILE } from './names.js';
import { chooseStage, type StageChoice } from './stage.js';
import { opfsDoneRecordReader } from './marker.js';
import { serviceWorkerWindowCount } from '#lib/pwa/window-count.js';
import { HistoryDb } from '#lib/history/history-db.js';
import { idbDetails } from '#lib/history-source/details.js';
import type { Stage2 } from '#lib/history/store.svelte.js';
import { unavailableClient } from '#lib/history/sqlite-rows.js';

/**
 * Whether this build may flip to stage 2 and boot on it (#310 W5). On: the
 * history store writes `history.db` on stage 2 (`sqlite-rows.ts`).
 */
export const HISTORY_FLIP_ENABLED = true;

let client: HistoryDbClient | null = null;

function supported(): boolean {
	return (
		typeof Worker !== 'undefined' &&
		typeof BroadcastChannel !== 'undefined' &&
		typeof navigator !== 'undefined' &&
		!!navigator.locks
	);
}

function pageEvents(): PageEvents {
	return {
		addEventListener(type, listener) {
			(type === 'visibilitychange' ? document : window).addEventListener(type, listener as EventListener);
		},
		removeEventListener(type, listener) {
			(type === 'visibilitychange' ? document : window).removeEventListener(type, listener as EventListener);
		},
		visibility: () => document.visibilityState
	};
}

/** How often a tab told of the flip looks again for a moment it may reload. */
const FLIP_RELOAD_RETRY_MS = 5_000;

/**
 * Reloads once `canReload` allows (not mid-shot, not mid-flash), looking again
 * every few seconds. For the flip (SPEC §11, N4): until the reload, a shot this
 * tab still records goes to the P3 store, which the flipped build imports as a
 * late row (step 8), so nothing is lost by waiting.
 */
export function reloadWhenIdle(canReload: () => boolean, reload: () => void = () => location.reload()): void {
	const tryNow = () => {
		if (canReload()) reload();
		else setTimeout(tryNow, FLIP_RELOAD_RETRY_MS);
	};
	tryNow();
}

export interface StartOptions {
	/** May the page reload now? (the shell's PWA update gate). */
	canReload?: () => boolean;
}

const makeWorker = (): WorkerLike =>
	new Worker(new URL('./worker.ts', import.meta.url), {
		type: 'module',
		name: 'crema-history-db'
	}) as unknown as WorkerLike;

const channel = (name: string) => new BroadcastChannel(name) as unknown as ChannelLike;

/** A client of `choice`'s stage and file. */
function clientFor(choice: Pick<StageChoice, 'stage' | 'target' | 'expect'>, opts: StartOptions): HistoryDbClient {
	return new HistoryDbClient({
		// A stage-1 tab that hears the flip reloads into the new build's view (N4).
		onFlipped: () => reloadWhenIdle(opts.canReload ?? (() => true)),
		stage: choice.stage,
		expect: choice.expect,
		target: choice.target,
		build: __APP_VERSION__,
		makeWorker,
		channel,
		locks: navigator.locks as unknown as LockManagerLike,
		storage: localStorage,
		events: pageEvents()
	});
}

/** Starts this tab's history store, once. Null where the browser can't run it, or until the staged boot chose. */
export function startHistoryDb(opts: StartOptions = {}): HistoryDbClient | null {
	if (stagedBootActive()) {
		void bootHistoryDb(opts);
		return client;
	}
	if (client || !supported()) return client;
	client = clientFor(
		{
			stage: 1,
			expect: { role: 'index', file: INDEX_FILE },
			target: { pool: 'index', generation: 0, file: INDEX_FILE, role: 'index', create: true, journal: HISTORY_JOURNAL }
		},
		opts
	);
	client.start();
	return client;
}

/** Whether this tab boots through `stage.ts` (#310 W5): the flag is on and the browser can run the store. */
export function stagedBootActive(): boolean {
	return HISTORY_FLIP_ENABLED && supported();
}

let booting: Promise<{ client: HistoryDbClient | null; choice: StageChoice } | null> | null = null;

/**
 * W5's boot, once per tab: the stage this tab runs on, flipping first when it
 * may (`stage.ts`), then the client for it. Whoever asks first starts it, the
 * shell or the history store; the shell's options are used when it asks
 * first (it always does in the app).
 */
export function bootHistoryDb(opts: StartOptions = {}): Promise<{ client: HistoryDbClient | null; choice: StageChoice } | null> {
	if (!stagedBootActive()) return Promise.resolve(null);
	booting ??= (async () => {
		const choice = await chooseStage({
			storage: localStorage,
			locks: navigator.locks as unknown as LockManagerLike,
			doneRecord: opfsDoneRecordReader(),
			windowCount: () => serviceWorkerWindowCount(),
			makeWorker,
			channel,
			now: () => Date.now(),
			build: __APP_VERSION__,
			journal: HISTORY_JOURNAL,
			allowFlip: true,
			log: (line) => console.info(`history-stage: ${line}`)
		});
		// The truth couldn't be told this boot: no client, the store is unavailable (REVIEW-473).
		if (choice.unavailable) return { client: null, choice };
		client = clientFor(choice, opts);
		client.start();
		return { client, choice };
	})();
	return booting;
}

/**
 * For the history store (`HistoryStoreOptions.stage2`): stage 2's client and
 * details once this tab's boot chose stage 2, or null on stage 1. The
 * details are written without `crema-history`'s `meta`, which is the old copy
 * after the flip (rule 3).
 */
export async function historyStage(): Promise<Stage2 | null> {
	const booted = await bootHistoryDb();
	if (!booted || booted.choice.stage !== 2) return null;
	return {
		// Stage 2 with its truth unknown this boot: the store is unavailable, shots to the journal.
		client: booted.client ?? unavailableClient(booted.choice.unavailable ?? 'unknown'),
		details: idbDetails(new HistoryDb({ expectExisting: () => true }), { bump: false }),
		storage: localStorage
	};
}

/** This tab's history store, if it started. */
export function historyDb(): HistoryDbClient | null {
	return client;
}

/**
 * For a factory reset (S10): close, pause and release, and never take the
 * database again in this tab. Safe to call when it never started.
 */
export async function stopHistoryDb(): Promise<void> {
	await client?.stop();
}
