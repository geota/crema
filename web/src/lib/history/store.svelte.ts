/**
 * `#lib/history/store` — the shot-history store.
 *
 * Backs the `/history` library: the list of past shots, their stored curves,
 * and the editable tasting-notes / star rating. Crema's web shell is a static,
 * client-only PWA, so the browser is the store.
 *
 * **Storage (#310 P3w, SPEC §2).** The rows and their heavy parts live in the
 * `crema-history` IndexedDB database, one record per row, written by an
 * identity diff: {@link persist} upserts what changed and never deletes. A row
 * goes only through an explicit delete that names it (`delete`,
 * `purgeTombstone`, `clearAllShots`, and the ids a restore's plan removes).
 * The first boot on this build moves the old copy (`crema.history.v2` and
 * `crema-history-series`) across, checks every row, and never writes or
 * deletes the old copy afterwards (`migrate.ts`). A migration that fails, or a
 * browser with no IndexedDB, runs on the **legacy backend**: the old copy, as
 * before, with its 300-row cap.
 *
 * **The view.** Every row stays in memory, but {@link all} is the newest 300
 * live ones, as before. A row past them is hidden, not gone: it's light (no
 * samples, profile or brew series in memory, see `light-rows.ts`), and
 * {@link rawAll}, {@link get} and backups still see it, so a pull doesn't add
 * a second copy of it and a backup doesn't leave it out.
 *
 * **Not loaded is not empty.** The rows load asynchronously: `loaded` starts
 * false, and History shows a skeleton until it's true. Code that would act on
 * the rows awaits {@link whenReady}, which rejects after 30 s, or at once when
 * the store can't be read (`unavailable`) or is gone (`lost`): it skips and
 * tries again later, and never goes on with an empty list. In dev and tests,
 * reading the rows outside a reactive context before they load throws.
 *
 * Obtain the singleton with {@link getHistoryStore}.
 */

import { enqueueEntry, readQueue } from '#lib/services/queue-store.js';
import {
	keepRemoteOut,
	markUnboundTombstone,
	settleUploadMarks,
	uploadAttempted
} from '#lib/visualizer/upload-marks.js';
import { excludedFromUpload, readSyncConfig } from '#lib/visualizer/sync-config.js';
import type { RustStoredShot, TimedSample } from '#lib/core/index.js';
import type { BrewSeries, ShotPatch as RemoteShotPatch, ShotSummary } from '#lib/core/crema-core.js';
import { downsampleIndices } from '#lib/wasm/de1_wasm.js';
import type { TelemetrySample } from '#lib/state/index.js';
import { toWire } from './telemetry-wire';
import {
	followOtherTabs,
	keepSkippedRows,
	readStore,
	storeHealth,
	writeStore,
	type StoreHealth,
	type StoreWrite
} from '#lib/utils/storage.js';
import { toast } from '#lib/components/shared/toast.svelte.js';
import {
	brewDateError,
	cappedCount,
	cappedHistory,
	evictOldest,
	fitsHistory,
	historyCutNotice,
	newestFirst,
	type HistoryInsert
} from './brew-date';
import {
	clearAllSeries,
	deleteSeries,
	getAllSeries,
	addSeries,
	getSeriesOf,
	putManySeries,
	putSeries
} from './series-store';
import {
	isBrewLog,
	isManualLog,
	refusedNoSamples,
	shotId,
	snapshotFromBean,
	toCoreShotMachine,
	type ShotBean,
	type ShotMachine,
	type ShotMetadata,
	type StoredShot
} from './model';
import type { Bean, Roaster } from '#lib/bean/index.js';
import { getCaptureStore } from '#lib/capture/store.js';
import { readCaptureKeep } from '#lib/capture/limit.js';
import {
	HistoryDb,
	HistoryDbLost,
	META_MIGRATION,
	lightOf,
	shotOfRow,
	withDetails,
	type DetailsRecord,
	type HistoryDbFault,
	type MigrationRecord,
	type RowRecord,
	type ShotWrite
} from './history-db';
import {
	copyLegacy,
	findLateRows,
	hashText,
	realLegacySource,
	sourceShots,
	TestKill,
	type LegacySource,
	type MigrationDeps
} from './migrate';
import { clearLight, lightSummary, markLight, markLoaded, sampleCountOf } from './light-rows';
import { markerSaysDone, migrationDue, readStoreMarker, writeDoneMarker, writeFailedMarker } from './store-marker';
import { addPending, dropPending, readPending } from './pending';
import { dayOf, readRowsSnapshot, snapshotDue, writeRowsSnapshot } from './rows-snapshot';
import { summarize } from './summary';
import type { LockManagerLike } from '#lib/restore/tab-safety.js';

/** Current persisted-shot schema version — matches Rust's `STORED_SHOT_FORMAT_VERSION`. */
const STORED_SHOT_FORMAT_VERSION = 3;

// `toWire` (the TelemetrySample → TimedSample mapper) lives in
// `./telemetry-wire` alongside its inverse `fromWire`. A round-trip
// test pins the two as a forward/inverse pair so a new sample channel
// can't drift between record and read paths.

/**
 * localStorage key of the legacy backend's rows (a `StoredShot[]`, newest
 * first). After migration it's the old copy: read, never written.
 */
export const HISTORY_KEY = 'crema.history.v2';
/** The history's name in a storage notice. */
const HISTORY_WHAT = 'shot history';

/**
 * The rows History shows: the newest this many live ones. On the legacy
 * backend it's also what's kept, as before; on `crema-history` every row is
 * kept and the rest are hidden (SPEC §2).
 */
const MAX_RECORDS = 300;

/**
 * How long {@link HistoryStore.seriesReady} waits. A series load that hasn't
 * ended by then has stuck, and an upload shouldn't wait on it for good.
 */
const SERIES_WAIT_MS = 30_000;
/** How long {@link HistoryStore.whenReady} waits before gated callers skip. */
export const READY_TIMEOUT_MS = 30_000;
/** After this, the skeleton says the history is still loading. */
export const SLOW_LOAD_MS = 5_000;
/** The channel tabs say which rows they wrote on. */
export const HISTORY_CHANNEL = 'crema-history';
/** Held while a tab migrates, so two tabs don't copy at once. */
export const MIGRATION_LOCK = 'crema-history-migrate';

/** How long an adopted row waits, twice, for a series its tab hasn't written yet. */
const ADOPT_SERIES_RETRY_MS = [1_000, 5_000] as const;

/** A shot's telemetry series couldn't be written to IndexedDB (quota, most
 *  likely): say so instead of silently keeping it in memory only. */
let lastSeriesToastAt = -Infinity;
function seriesSaveFailed(): void {
	// One toast per burst (an import of many shots fails shot by shot).
	if (Date.now() - lastSeriesToastAt < 5000) return;
	lastSeriesToastAt = Date.now();
	toast.error('Could not save this shot’s telemetry — storage may be full.');
}

/** Delete one shot's raw capture, best effort (no IndexedDB, or it refused). */
function dropCapture(id: string): void {
	void getCaptureStore()
		.delete(id)
		.catch(() => {});
}

/** Whether a shot's upload may have reached Visualizer, or is about to. */
export function uploadPending(id: string): boolean {
	return (
		uploadAttempted(id) ||
		readQueue().entries.some((e) => e.entity === 'shot' && e.id === id && e.op === 'create')
	);
}

/**
 * How the history's storage stands (SPEC §2):
 * - `loading`: the rows are on their way;
 * - `ok`: `crema-history`, loaded;
 * - `legacy`: the old copy, as before (the move failed, or no IndexedDB);
 * - `unavailable`: the store was made, and won't open now;
 * - `lost`: the store was made, and is gone.
 */
export type HistoryHealth = 'loading' | 'ok' | 'legacy' | 'unavailable' | 'lost';

/** The rows aren't loaded, so a caller that would act on them skips. */
export class HistoryNotReady extends Error {
	constructor(readonly health: HistoryHealth | 'timeout') {
		super(
			health === 'timeout'
				? 'Shot history is still loading.'
				: health === 'lost'
					? 'Shot history is missing.'
					: 'Shot history can’t be opened right now.'
		);
		this.name = 'HistoryNotReady';
	}
}

/** A `BroadcastChannel`, as the store uses it. */
export interface ChannelLike {
	postMessage(message: unknown): void;
	close(): void;
	onmessage: ((event: { data: unknown }) => void) | null;
}

/** What a tab says on {@link HISTORY_CHANNEL}: the rows it wrote or deleted. */
interface ChannelMessage {
	type: 'rows';
	from: string;
	ids: string[];
	seq: number;
}

/** Test seams, and the choice of backend. */
export interface HistoryStoreOptions {
	/** `legacy` runs on the old copy whatever the browser has. */
	backend?: 'auto' | 'legacy';
	idb?: () => IDBFactory | undefined;
	dbFault?: HistoryDbFault;
	migrationFault?: MigrationDeps['fault'];
	source?: LegacySource;
	estimate?: MigrationDeps['estimate'];
	locks?: LockManagerLike | null;
	channel?: (() => ChannelLike | null) | null;
	snapshotRoot?: () => Promise<FileSystemDirectoryHandle | undefined>;
	now?: () => number;
	readyTimeoutMs?: number;
	/** Listen for the tab coming back into view. On by default. */
	visibility?: boolean;
}

let testDefaults: HistoryStoreOptions = {};
/** Test seam: the options {@link getHistoryStore} and `new HistoryStore()` start from. */
export function setHistoryStoreDefaultsForTest(opts: HistoryStoreOptions): void {
	testDefaults = opts;
}

const isDev = (): boolean => {
	const env = (import.meta as { env?: { DEV?: boolean; MODE?: string } }).env;
	return env?.DEV === true || env?.MODE === 'test';
};

const errorText = (e: unknown): string => (e instanceof Error ? e.message : String(e));

function defaultChannel(): ChannelLike | null {
	if (typeof BroadcastChannel === 'undefined') return null;
	try {
		return new BroadcastChannel(HISTORY_CHANNEL) as unknown as ChannelLike;
	} catch {
		return null;
	}
}

function defaultLocks(): LockManagerLike | null {
	return typeof navigator !== 'undefined' && 'locks' in navigator && navigator.locks
		? (navigator.locks as unknown as LockManagerLike)
		: null;
}

async function defaultEstimate(): Promise<{ quota?: number; usage?: number } | undefined> {
	if (typeof navigator === 'undefined' || typeof navigator.storage?.estimate !== 'function') return undefined;
	return navigator.storage.estimate();
}

/** Newest first, then by id, newest first: one order, whatever the store's key order. */
function byDateThenId(a: StoredShot, b: StoredShot): number {
	return b.completedAt - a.completedAt || (a.id < b.id ? 1 : a.id > b.id ? -1 : 0);
}

/** Whether memory holds fewer heavy parts than `d`. */
function storeHeldMore(shot: StoredShot, d: DetailsRecord): boolean {
	return (
		(shot.record.samples.length === 0 && d.samples.length > 0) ||
		(shot.profile == null && d.profile != null) ||
		((shot.brewSeries?.samples.length ?? 0) === 0 && (d.brewSeries?.samples.length ?? 0) > 0)
	);
}

/** The reactive shot-history library. One instance per app — {@link getHistoryStore}. */
export class HistoryStore {
	/** Every row, newest first: those in view with their heavy parts, the rest light. */
	private shots = $state.raw<StoredShot[]>([]);
	private loadedFlag = $state(false);
	private healthState = $state<HistoryHealth>('loading');
	private slowFlag = $state(false);
	private lastError = $state<string | null>(null);
	/** Shots whose curve isn't stored yet (a write failed): the banner, and `beforeunload`. */
	private unsavedIds = $state.raw<string[]>([]);
	/** The old copy's date and size, for the lost banner. */
	private oldCopyInfo = $state<{ at: number; rows: number } | null>(null);

	/** Which backend the rows go to. `booting` until the boot decides. */
	private mode: 'booting' | 'idb' | 'legacy' | 'blocked' = 'booting';
	private readonly opts: HistoryStoreOptions;
	private db: HistoryDb | null = null;
	private resolveReady: () => void = () => {};
	/** Settles once the rows have loaded, on either backend. */
	readonly ready: Promise<void>;

	// ── crema-history ──
	/** Each row's summary, as stored. */
	private summaries = new Map<string, ShotSummary>();
	/** The row object last written or read for each id: the identity diff's base. */
	private written = new Map<string, StoredShot>();
	/** Ids the store holds a row for. */
	private stored = new Set<string>();
	/** Ids whose heavy parts are to write with the row. */
	private dirtyDetails = new Set<string>();
	/** Writes and deletes, in order. */
	private writeChain: Promise<void> = Promise.resolve();
	private flushQueued = false;
	/** The store's write counter, as this tab last saw it. */
	private lastSeq = 0;
	private channel: ChannelLike | null = null;
	/** Ids another tab wrote, waiting for the load or a restore to end. */
	private heard = new Set<string>();
	private readonly tabId = Math.random().toString(36).slice(2);
	/** Detail loads under way, for {@link seriesReady}. */
	private hydrations = new Set<Promise<void>>();
	/** Ids removed from memory whose delete hasn't landed: another tab's news mustn't bring them back. */
	private removing = new Set<string>();
	private snapshotting = false;
	/** The day this tab last tried the snapshot. */
	private snapshotTried: string | null = null;
	private hiddenNoticeShown = false;
	private viewMemo: { rows: StoredShot[]; view: StoredShot[]; live: StoredShot[] } | null = null;
	private byIdMemo: { rows: StoredShot[]; map: Map<string, StoredShot> } | null = null;

	// ── legacy ──
	/** The running series load; null once it has ended, or with no IndexedDB. */
	private seriesLoading: Promise<void> | null = null;
	/** The series load has ended, either way. Until then a row's curve may be on its way. */
	private seriesSettled = $state(typeof indexedDB === 'undefined');
	/** The stored series couldn't be read, even on a second try. */
	private seriesUnreadable = false;
	/**
	 * Ids of the rows this tab has written, or loaded, on the legacy backend.
	 * A row's series and capture are only ever deleted when its id leaves this
	 * set, never because a listing doesn't name them (#325).
	 */
	private known = new Set<string>();
	/** Above zero while a restore runs: see {@link pauseDeletes}. */
	private deletesPaused = 0;
	/** Ids adopted from another tab whose curve is still being looked for, with how many look-ups. */
	private hydrating = new Map<string, number>();

	constructor(options: HistoryStoreOptions = {}) {
		this.opts = { ...testDefaults, ...options };
		this.ready = new Promise<void>((resolve) => {
			this.resolveReady = resolve;
		});
		const idb = this.idb();
		if (this.opts.backend === 'legacy' || !idb) {
			if (this.opts.backend !== 'legacy' && markerSaysDone()) {
				// The rows moved, and this browser can't open IndexedDB now.
				this.block('unavailable', 'IndexedDB is not available.');
				return;
			}
			this.enterLegacySync();
			return;
		}
		void this.boot().catch((e) => {
			if (e instanceof TestKill) return;
			this.block('unavailable', errorText(e));
		});
	}

	private idb(): IDBFactory | undefined {
		if (this.opts.idb) return this.opts.idb();
		return typeof indexedDB === 'undefined' ? undefined : indexedDB;
	}

	private now(): number {
		return (this.opts.now ?? Date.now)();
	}

	// ── State ──────────────────────────────────────────────────────────────

	/** The rows have loaded. Until then, nothing may conclude anything from them. */
	get loaded(): boolean {
		return this.loadedFlag;
	}

	/** How the storage stands. */
	get health(): HistoryHealth {
		return this.healthState;
	}

	/** Loading has taken over {@link SLOW_LOAD_MS}: the skeleton says so. */
	get loadingSlowly(): boolean {
		return this.slowFlag;
	}

	/** The last storage error, for Settings. */
	get storageError(): string | null {
		return this.lastError;
	}

	/** The old copy's date and row count, for the lost banner. */
	get oldCopy(): { at: number; rows: number } | null {
		return this.oldCopyInfo;
	}

	/** Shots whose curve isn't saved yet. */
	get unsavedShots(): readonly string[] {
		return this.unsavedIds;
	}

	/** Whether closing the tab now would lose something. */
	get hasUnsaved(): boolean {
		return this.unsavedIds.length > 0 || (this.mode === 'idb' && this.dirtyRowIds().length > 0);
	}

	/** On `crema-history`: rows past the view are kept. On the legacy backend they go. */
	get keepsEveryRow(): boolean {
		return this.mode === 'idb';
	}

	/**
	 * How the rows loaded, in a store-health word: what restore and capture
	 * prune check before trusting "not listed" to mean "not here".
	 */
	rowsHealth(): StoreHealth {
		switch (this.healthState) {
			case 'ok':
				return 'ok';
			case 'legacy':
				return storeHealth(HISTORY_KEY);
			case 'loading':
				return 'loading';
			default:
				return 'unavailable';
		}
	}

	/**
	 * The rows, for code that acts on them: resolves once they've loaded;
	 * rejects with {@link HistoryNotReady} after `timeoutMs`, or at once when
	 * the store is `unavailable` or `lost`. The caller skips and tries later.
	 */
	whenReady(timeoutMs: number = this.opts.readyTimeoutMs ?? READY_TIMEOUT_MS): Promise<void> {
		if (this.loadedFlag) return Promise.resolve();
		if (this.healthState === 'unavailable' || this.healthState === 'lost') {
			return Promise.reject(new HistoryNotReady(this.healthState));
		}
		let timer: ReturnType<typeof setTimeout> | undefined;
		const late = new Promise<never>((_, reject) => {
			timer = setTimeout(() => reject(new HistoryNotReady('timeout')), timeoutMs);
		});
		return Promise.race([this.ready, late]).finally(() => clearTimeout(timer));
	}

	/** Whether the rows load within `timeoutMs`; false means skip. */
	async readyWithin(timeoutMs?: number): Promise<boolean> {
		try {
			await this.whenReady(timeoutMs);
			return true;
		} catch {
			return false;
		}
	}

	private assertLoaded(what: string): void {
		if (this.loadedFlag || !isDev()) return;
		if ($effect.tracking()) return;
		throw new Error(`history.${what} was read before the rows loaded: await history.whenReady() first`);
	}

	// ── Boot ───────────────────────────────────────────────────────────────

	private markLoadedNow(health: 'ok' | 'legacy'): void {
		this.healthState = health;
		this.loadedFlag = true;
		this.slowFlag = false;
		this.resolveReady();
		// Fixed at the first load after the setting arrived, before anything new
		// is recorded: rows already here mean today's limit stays (#311).
		const existing = this.shots.length > 0;
		readCaptureKeep(() => existing);
	}

	private block(health: 'unavailable' | 'lost', error: string): void {
		this.mode = 'blocked';
		this.healthState = health;
		this.lastError = error;
		this.slowFlag = false;
		// New shots made meanwhile go to the journal, so a reload keeps them.
		this.journalNew();
	}

	/** No IndexedDB, or a test: the legacy backend, loaded at once as before. */
	private enterLegacySync(): void {
		this.mode = 'legacy';
		this.shots = loadShots();
		this.markLive(this.shots.map((s) => s.id));
		followOtherTabs(HISTORY_KEY, Array.isArray, (v) => this.takeOtherTabsRows(v as unknown[]));
		this.markLoadedNow('legacy');
		// Two-phase, as before: rows at once, each one's series from IndexedDB
		// after. Rows of the all-in-localStorage format arrive with samples, and
		// move to IndexedDB.
		if (typeof indexedDB !== 'undefined') {
			this.seriesSettled = false;
			this.seriesLoading = this.hydrateSeries().finally(() => {
				this.seriesLoading = null;
				this.seriesSettled = true;
			});
		} else {
			this.seriesSettled = true;
		}
	}

	/** After a failed move: the old copy, with what this tab made meanwhile. */
	private enterLegacy(error: string | null): void {
		const made = this.shots;
		const madeDetails = new Set(this.dirtyDetails);
		this.mode = 'legacy';
		this.dirtyDetails.clear();
		const loadedRows = loadShots();
		const ids = new Set(loadedRows.map((s) => s.id));
		this.shots = newestFirst([...made.filter((s) => !ids.has(s.id)), ...loadedRows]);
		this.markLive(loadedRows.map((s) => s.id));
		followOtherTabs(HISTORY_KEY, Array.isArray, (v) => this.takeOtherTabsRows(v as unknown[]));
		this.markLoadedNow('legacy');
		if (error) {
			this.lastError = error;
			toast.info('Shot history couldn’t move to its new storage, so it stays where it was for now. It will try again later.', 8000);
		}
		if (made.length > 0) {
			this.persistLegacy();
			for (const s of made) {
				if (madeDetails.has(s.id) && s.record.samples.length > 0) {
					void putSeries(s.id, s.record.samples).catch(seriesSaveFailed);
				}
			}
		}
		this.seriesSettled = false;
		this.seriesLoading = this.hydrateSeries().finally(() => {
			this.seriesLoading = null;
			this.seriesSettled = true;
		});
	}

	private source(): LegacySource {
		return this.opts.source ?? realLegacySource(() => this.idb());
	}

	private newDb(expectExisting: () => boolean): HistoryDb {
		return new HistoryDb({ idb: () => this.idb(), expectExisting, fault: this.opts.dbFault });
	}

	private migrationDeps(db: HistoryDb): MigrationDeps {
		return {
			db,
			source: this.source(),
			summarize,
			estimate: this.opts.estimate ?? defaultEstimate,
			now: () => this.now(),
			fault: this.opts.migrationFault
		};
	}

	private async underLock<T>(name: string, run: () => Promise<T>): Promise<T> {
		const locks = this.opts.locks === undefined ? defaultLocks() : this.opts.locks;
		if (!locks) return run();
		let out: T | undefined;
		let failed: unknown = null;
		let ran = false;
		try {
			await locks.request(name, { mode: 'exclusive' }, async () => {
				ran = true;
				try {
					out = await run();
				} catch (e) {
					failed = e;
				}
			});
		} catch (e) {
			if (!ran) return run();
			throw e;
		}
		if (failed) throw failed;
		return out as T;
	}

	private async boot(): Promise<void> {
		this.listenToTabs();
		const slow = setTimeout(() => {
			if (!this.loadedFlag) this.slowFlag = true;
		}, SLOW_LOAD_MS);
		try {
			const outcome = await this.underLock(MIGRATION_LOCK, () => this.openOrMigrate());
			if (outcome.kind === 'ok') {
				await this.loadFromDb();
			} else if (outcome.kind === 'legacy') {
				this.enterLegacy(outcome.error);
			} else {
				if (outcome.kind === 'lost') await this.readOldCopyInfo();
				this.block(outcome.kind, outcome.error);
			}
		} finally {
			clearTimeout(slow);
		}
	}

	/** Steps 1 to 7 of the move, under the lock. */
	private async openOrMigrate(): Promise<
		| { kind: 'ok' }
		| { kind: 'legacy'; error: string | null }
		| { kind: 'lost' | 'unavailable'; error: string }
	> {
		const done = markerSaysDone();
		const db = this.newDb(() => markerSaysDone());
		this.db = db;
		if (done) {
			try {
				await db.open();
				const record = await db.meta<MigrationRecord>(META_MIGRATION);
				// A store with no record was made by nothing we know: as good as lost.
				if (!record || record.state !== 'done') return { kind: 'lost', error: 'The shot history store is empty.' };
				return { kind: 'ok' };
			} catch (e) {
				if (e instanceof HistoryDbLost) return { kind: 'lost', error: e.message };
				return { kind: 'unavailable', error: errorText(e) };
			}
		}
		// A move that failed waits for its next try; until then, the old copy.
		if (!migrationDue(this.now())) return { kind: 'legacy', error: null };
		try {
			await db.open();
			const record = await db.meta<MigrationRecord>(META_MIGRATION);
			if (record?.state === 'done') {
				// The marker didn't make it: rebuild it, and never clear.
				writeDoneMarker(record.doneAt ?? this.now(), record.sourceIds.length);
				return { kind: 'ok' };
			}
		} catch (e) {
			if (e instanceof TestKill) throw e;
			writeFailedMarker(errorText(e), this.now());
			return { kind: 'legacy', error: errorText(e) };
		}
		const result = await copyLegacy(this.migrationDeps(db), coerceStoredShot);
		if (result.kind === 'done') {
			await this.opts.migrationFault?.('marker');
			writeDoneMarker(this.now(), result.rows);
			return { kind: 'ok' };
		}
		writeFailedMarker(result.error, this.now());
		return { kind: 'legacy', error: result.error };
	}

	/** The rows from `crema-history`, with what arrived meanwhile; then the view's details. */
	private async loadFromDb(): Promise<void> {
		const db = this.db!;
		let records: RowRecord[];
		try {
			records = await db.allRows().catch(() => db.allRows());
			// Test seam: the load held between reading the rows and taking them.
			await this.opts.migrationFault?.('loaded-rows');
		} catch (e) {
			if (e instanceof TestKill) throw e;
			this.block('unavailable', errorText(e));
			return;
		}
		const loadedRows: StoredShot[] = [];
		for (const rec of records) {
			const { shot, summary } = shotOfRow(rec);
			this.summaries.set(shot.id, summary);
			this.stored.add(shot.id);
			markLight(shot.id, summary);
			loadedRows.push(shot);
		}
		this.lastSeq = await db.writeSeq().catch(() => 0);
		// 8 and 9: what an old tab put in the old copy since.
		const late = await this.lateRows(loadedRows).catch(() => null);
		// After the last await: rows made here during the load stay, and lead.
		// They're written next, journalled or not.
		const made = this.shots.filter((s) => !this.stored.has(s.id));
		const madeIds = new Set(made.map((s) => s.id));
		for (const s of made) {
			this.written.delete(s.id);
			this.dirtyDetails.add(s.id);
		}
		const rows = loadedRows.filter((s) => !madeIds.has(s.id));
		for (const s of rows) this.written.set(s.id, s);
		const held = new Set([...rows.map((s) => s.id), ...madeIds]);
		// The journal: rows a write couldn't take before.
		const journal: StoredShot[] = [];
		const journalHeld: string[] = [];
		for (const raw of readPending()) {
			const shot = typeof raw.id === 'string' ? coerceStoredShot(raw) : null;
			if (!shot) continue;
			if (held.has(shot.id)) {
				if (this.stored.has(shot.id)) journalHeld.push(shot.id);
				continue;
			}
			journal.push(shot);
			held.add(shot.id);
		}
		dropPending(journalHeld);
		const lateAdd = (late?.add ?? []).filter((s) => !held.has(s.id));
		for (const s of [...journal, ...lateAdd]) this.dirtyDetails.add(s.id);
		const fills = new Map((late?.fill ?? []).map((s) => [s.id, s]));
		const merged = [...made, ...journal, ...lateAdd, ...rows].map((s) => {
			const f = fills.get(s.id);
			if (!f) return s;
			this.dirtyDetails.add(s.id);
			markLoaded(s.id);
			return f;
		});
		this.shots = [...merged].sort(byDateThenId);
		this.mode = 'idb';
		this.markLoadedNow('ok');
		this.settleView();
		this.scheduleFlush();
		if (late?.changed) {
			// Only once the late rows are in: a record naming a row the store
			// doesn't hold would keep it out for good.
			const record = late.record;
			const wanted = [...lateAdd, ...fills.values()].map((s) => s.id);
			this.writeChain = this.writeChain.then(async () => {
				if (wanted.every((id) => this.stored.has(id) && !this.dirtyDetails.has(id))) {
					await db.setMeta(META_MIGRATION, record).catch(() => {});
				}
			});
		}
		// Ids another tab wrote during the load.
		if (this.heard.size > 0) this.applyHeard();
	}

	private async lateRows(held: readonly StoredShot[]) {
		const record = await this.db!.meta<MigrationRecord>(META_MIGRATION);
		if (!record || record.state !== 'done') return null;
		return findLateRows(this.source(), record, held, coerceStoredShot);
	}

	private async readOldCopyInfo(): Promise<void> {
		try {
			const raw = this.source().raw();
			const items = raw ? (JSON.parse(raw) as unknown) : [];
			const rows = Array.isArray(items) ? items.length : 0;
			const m = readStoreMarker();
			const at = m.kind === 'marker' && m.marker.state === 'done' ? m.marker.at : 0;
			this.oldCopyInfo = { at, rows };
		} catch {
			this.oldCopyInfo = null;
		}
	}

	// ── The view ───────────────────────────────────────────────────────────

	private viewOf(): { view: StoredShot[]; live: StoredShot[] } {
		const rows = this.shots;
		if (this.viewMemo?.rows === rows) return this.viewMemo;
		const view = this.mode === 'legacy' ? rows : cappedHistory(rows, MAX_RECORDS);
		const memo = { rows, view, live: view.filter((s) => s.deletedAt == null) };
		this.viewMemo = memo;
		return memo;
	}

	private byId(): Map<string, StoredShot> {
		const rows = this.shots;
		if (this.byIdMemo?.rows === rows) return this.byIdMemo.map;
		const map = new Map<string, StoredShot>();
		for (const s of rows) if (!map.has(s.id)) map.set(s.id, s);
		this.byIdMemo = { rows, map };
		return map;
	}

	/** Move the written base to `next` when it pointed at `prev`: no write needed. */
	private rebase(prev: StoredShot, next: StoredShot): void {
		if (this.written.get(prev.id) === prev) this.written.set(prev.id, next);
	}

	/**
	 * On `crema-history`: rows that left the view go light, and rows that came
	 * into it load their heavy parts. A row whose heavy parts aren't stored
	 * yet keeps them.
	 */
	private settleView(): void {
		if (this.mode !== 'idb') return;
		const inView = new Set(this.viewOf().view.map((s) => s.id));
		const unsaved = new Set(this.unsavedIds);
		let changed = false;
		const next = this.shots.map((s) => {
			if (inView.has(s.id) || lightSummary(s.id) || this.dirtyDetails.has(s.id) || unsaved.has(s.id)) return s;
			const summary = this.summaries.get(s.id);
			if (!summary || !this.stored.has(s.id)) return s;
			const light = lightOf(s);
			markLight(s.id, summary);
			this.rebase(s, light);
			changed = true;
			return light;
		});
		if (changed) this.shots = next;
		const want = [...inView].filter((id) => lightSummary(id) !== undefined);
		if (want.length > 0) this.hydrate(want);
	}

	/** Load the heavy parts of the light rows `ids`, for the view. */
	private hydrate(ids: readonly string[]): void {
		const db = this.db;
		if (!db) return;
		const run = (async () => {
			let found: Map<string, DetailsRecord>;
			try {
				found = await db.detailsOf(ids).catch(() => db.detailsOf(ids));
			} catch {
				// The rows stay light, so nothing reads them as curveless.
				this.seriesUnreadable = true;
				return;
			}
			// After the await: the rows as they are now.
			const want = new Set(ids);
			let changed = false;
			const next = this.shots.map((s) => {
				if (!want.has(s.id)) return s;
				const summary = lightSummary(s.id);
				if (!summary) return s;
				const d = found.get(s.id);
				if (!d) {
					// No heavy parts stored: nothing to load, unless the summary says
					// there are some, which keeps the row light.
					if ((summary.sampleCount ?? 0) === 0 && summary.brewSampleCount === 0) markLoaded(s.id);
					return s;
				}
				const full = withDetails(s, d);
				markLoaded(s.id);
				this.rebase(s, full);
				changed = true;
				return full;
			});
			if (changed) this.shots = next;
		})();
		this.hydrations.add(run);
		void run.finally(() => this.hydrations.delete(run));
	}

	// ── Readers ────────────────────────────────────────────────────────────

	/**
	 * Wait for the rows and the curves of the rows in view. Everything that
	 * sends or saves a whole shot awaits this first. True when the load has
	 * ended, either way ({@link seriesFailed} says which); false when the rows
	 * didn't load, or the curves outlasted the wait.
	 */
	async seriesReady(timeoutMs: number = SERIES_WAIT_MS): Promise<boolean> {
		if (!(await this.readyWithin(Math.min(timeoutMs, this.opts.readyTimeoutMs ?? READY_TIMEOUT_MS)))) return false;
		const loading = this.mode === 'idb' ? Promise.all([...this.hydrations]).then(() => {}) : this.seriesLoading;
		if (!loading) return true;
		let timer: ReturnType<typeof setTimeout> | undefined;
		const stuck = new Promise<boolean>((resolve) => {
			timer = setTimeout(() => resolve(false), timeoutMs);
		});
		const loaded = await Promise.race([loading.then(() => true), stuck]);
		clearTimeout(timer);
		return loaded;
	}

	/**
	 * The stored series were there to read and couldn't be, so rows that have
	 * curves look like rows that don't. History works on from memory, but
	 * anything written from it now is missing those curves.
	 */
	get seriesFailed(): boolean {
		return this.seriesUnreadable;
	}

	/** Machine shots no upload can take: no telemetry, now the series have settled. */
	get withoutTelemetry(): StoredShot[] {
		if (!this.loadedFlag) return [];
		if (this.mode === 'legacy' && !this.seriesSettled) return [];
		return this.viewOf().live.filter(
			(s) => !s.visualizerId && sampleCountOf(s) === 0 && refusedNoSamples(s)
		);
	}

	/** The shots waiting to go to Visualizer. */
	get visualizerBacklog(): StoredShot[] {
		if (!this.loadedFlag) return [];
		const none = new Set(this.withoutTelemetry.map((s) => s.id));
		const config = readSyncConfig();
		return this.viewOf().live.filter(
			(s) => !s.visualizerId && !isBrewLog(s) && !none.has(s.id) && !excludedFromUpload(s, config)
		);
	}

	/**
	 * The user-visible history, newest first: the newest 300 live rows.
	 * Tombstones (`deletedAt` set) are filtered out — they only exist for the
	 * sync layer to push a remote DELETE. Reactive.
	 */
	get all(): StoredShot[] {
		this.assertLoaded('all');
		return this.viewOf().live;
	}

	/**
	 * Every stored shot: tombstones, and rows past the view, which are light.
	 * The sync layer, imports and restore read this; the UI uses {@link all}.
	 */
	get rawAll(): StoredShot[] {
		this.assertLoaded('rawAll');
		return this.shots;
	}

	/** Every live row, hidden ones too: what a backup holds. Light rows need {@link loadMany}. */
	get allLive(): StoredShot[] {
		this.assertLoaded('allLive');
		return this.shots.filter((s) => s.deletedAt == null);
	}

	/** Look up one shot by id, hidden or not, or `undefined` if it is not in the history. */
	get(id: string): StoredShot | undefined {
		this.assertLoaded('get');
		return this.byId().get(id);
	}

	/**
	 * `id` whole, with its samples, profile and brew series, for code that
	 * sends or writes it; a row in view is already whole. Undefined when the
	 * history has no such row.
	 */
	async loadShot(id: string): Promise<StoredShot | undefined> {
		await this.whenReady();
		return (await this.loadMany([id]))[0];
	}

	/** {@link loadShot} for many, in order; ids not in the history are left out. */
	async loadMany(ids: readonly string[]): Promise<StoredShot[]> {
		await this.whenReady();
		const rows = ids.map((id) => this.byId().get(id)).filter((s): s is StoredShot => s !== undefined);
		const light = rows.filter((s) => lightSummary(s.id) !== undefined).map((s) => s.id);
		if (light.length === 0 || !this.db || this.mode !== 'idb') return rows;
		const found = await this.db.detailsOf(light);
		// After the await: the rows as they are now, with what the store holds.
		return ids
			.map((id) => this.byId().get(id))
			.filter((s): s is StoredShot => s !== undefined)
			.map((s) => (lightSummary(s.id) ? withDetails(s, found.get(s.id)) : s));
	}

	// ── Other tabs ─────────────────────────────────────────────────────────

	private listenToTabs(): void {
		const make = this.opts.channel === undefined ? defaultChannel : this.opts.channel;
		this.channel = make ? make() : null;
		if (this.channel) {
			this.channel.onmessage = (event) => {
				const m = event.data as Partial<ChannelMessage> | null;
				if (!m || m.type !== 'rows' || m.from === this.tabId || !Array.isArray(m.ids)) return;
				for (const id of m.ids) if (typeof id === 'string') this.heard.add(id);
				if (typeof m.seq === 'number') this.lastSeq = Math.max(this.lastSeq, m.seq);
				if (this.loadedFlag && this.mode === 'idb' && this.deletesPaused === 0) this.applyHeard();
			};
		}
		if (this.opts.visibility !== false && typeof document !== 'undefined') {
			const back = () => {
				if (document.visibilityState === 'hidden') return;
				void this.onResume();
			};
			document.addEventListener('visibilitychange', back);
			if (typeof window !== 'undefined') window.addEventListener('pageshow', back);
		}
	}

	/** Back in view: a frozen tab may have missed messages, and a closed store may open now. */
	private async onResume(): Promise<void> {
		if (this.healthState === 'unavailable') {
			await this.retryOpen();
			return;
		}
		if (this.mode !== 'idb' || !this.db) return;
		let seq: number;
		try {
			seq = await this.db.writeSeq();
		} catch {
			return;
		}
		if (seq === this.lastSeq) return;
		await this.reloadRows();
	}

	/** Re-read every row, keeping this tab's unwritten changes. */
	async reloadRows(): Promise<void> {
		if (this.mode !== 'idb' || !this.db) return;
		let records: RowRecord[];
		try {
			records = await this.db.allRows();
			this.lastSeq = await this.db.writeSeq();
		} catch {
			return;
		}
		this.adoptRecords(new Map(records.map((r) => [r.id, r])), null);
	}

	private applyHeard(): void {
		const ids = [...this.heard];
		this.heard.clear();
		if (ids.length === 0 || !this.db) return;
		const db = this.db;
		void db
			.rowsOf(ids)
			.then((found) => this.adoptRecords(found, ids))
			.catch(() => {
				for (const id of ids) this.heard.add(id);
			});
	}

	/**
	 * Take what the store holds for `ids` (every row, when null) over memory.
	 * A row this tab changed and hasn't written stays, and goes out next; a
	 * row the store no longer has was deleted by another tab, by name.
	 */
	private adoptRecords(found: Map<string, RowRecord>, ids: readonly string[] | null): void {
		if (this.mode !== 'idb') return;
		const scope = ids ? new Set(ids) : null;
		const dirty = new Set(this.dirtyRowIds());
		const seen = new Set<string>();
		let changed = false;
		const next: StoredShot[] = [];
		for (const s of this.shots) {
			if (scope && !scope.has(s.id)) {
				next.push(s);
				continue;
			}
			seen.add(s.id);
			const rec = found.get(s.id);
			if (dirty.has(s.id) || this.dirtyDetails.has(s.id)) {
				next.push(s);
				continue;
			}
			if (!rec) {
				if (this.stored.has(s.id)) {
					// Deleted in another tab.
					this.forget(s.id);
					changed = true;
					continue;
				}
				next.push(s);
				continue;
			}
			const { shot, summary } = shotOfRow(rec);
			const before = this.summaries.get(s.id);
			this.summaries.set(s.id, summary);
			const sameHeavy =
				before != null &&
				(before.sampleCount ?? 0) === (summary.sampleCount ?? 0) &&
				before.brewSampleCount === summary.brewSampleCount;
			let row: StoredShot;
			if (!lightSummary(s.id) && sameHeavy) {
				row = { ...shot, profile: s.profile, record: { ...shot.record, samples: s.record.samples } };
				if (s.brewSeries) row.brewSeries = s.brewSeries;
				else delete row.brewSeries;
			} else {
				row = shot;
				markLight(s.id, summary);
			}
			this.written.set(s.id, row);
			next.push(row);
			changed = true;
		}
		for (const [id, rec] of found) {
			if (seen.has(id) || this.removing.has(id) || (scope && !scope.has(id))) continue;
			const { shot, summary } = shotOfRow(rec);
			this.summaries.set(id, summary);
			this.stored.add(id);
			markLight(id, summary);
			this.written.set(id, shot);
			next.push(shot);
			changed = true;
		}
		if (!changed) return;
		this.shots = next.sort(byDateThenId);
		this.settleView();
	}

	private forget(id: string): void {
		this.written.delete(id);
		this.stored.delete(id);
		this.summaries.delete(id);
		this.dirtyDetails.delete(id);
		markLoaded(id);
	}

	private post(ids: readonly string[], seq: number): void {
		if (!this.channel || ids.length === 0) return;
		try {
			this.channel.postMessage({ type: 'rows', from: this.tabId, ids: [...ids], seq } satisfies ChannelMessage);
		} catch {
			// The other tabs catch up when they come back into view.
		}
	}

	// ── Writing ────────────────────────────────────────────────────────────

	/** Ids whose row in memory isn't the one last written. */
	private dirtyRowIds(): string[] {
		const out: string[] = [];
		for (const s of this.shots) if (this.written.get(s.id) !== s) out.push(s.id);
		return out;
	}

	/**
	 * Save what changed. On the legacy backend, the whole blob, as before. On
	 * `crema-history`, the rows that changed by identity, in order, upsert
	 * only: nothing is deleted here. Says whether the save went out (on
	 * `crema-history` it lands shortly).
	 */
	private persist(): boolean {
		if (this.mode === 'legacy') return this.persistLegacy();
		if (this.mode === 'blocked') {
			this.journalNew();
			return false;
		}
		this.settleView();
		this.scheduleFlush();
		return true;
	}

	private scheduleFlush(): void {
		if (this.flushQueued) return;
		this.flushQueued = true;
		this.writeChain = this.writeChain.then(async () => {
			this.flushQueued = false;
			await this.flush();
		});
	}

	/** Every write queued so far has landed (or failed). */
	async flushed(): Promise<void> {
		let chain: Promise<void>;
		do {
			chain = this.writeChain;
			await chain;
		} while (chain !== this.writeChain);
	}

	private async flush(): Promise<void> {
		if (this.mode === 'blocked') {
			this.journalNew();
			return;
		}
		if (this.mode !== 'idb' || !this.db) return;
		const ids = this.dirtyRowIds();
		if (ids.length === 0) return;
		const byId = this.byId();
		const writes: ShotWrite[] = [];
		for (const id of ids) {
			const shot = byId.get(id);
			if (!shot) continue;
			const details = this.dirtyDetails.has(id) || !this.summaries.has(id);
			writes.push({ shot, details, summary: this.summaries.get(id) });
		}
		for (const w of writes) if (w.details) this.dirtyDetails.delete(w.shot.id);
		try {
			const result = await this.db.putShots(writes, summarize);
			this.afterWrite(writes, result.seq, result.written);
		} catch (e) {
			for (const w of writes) if (w.details) this.dirtyDetails.add(w.shot.id);
			await this.writeFailed(writes, e);
		}
	}

	private afterWrite(
		writes: readonly ShotWrite[],
		seq: number,
		written: Map<string, { details: DetailsRecord; summary: ShotSummary }>
	): void {
		this.lastSeq = Math.max(this.lastSeq, seq);
		const now = this.byId();
		const bring = new Map<string, DetailsRecord>();
		const savedCurves: string[] = [];
		for (const w of writes) {
			const id = w.shot.id;
			this.stored.add(id);
			const got = written.get(id);
			if (got) {
				this.summaries.set(id, got.summary);
				savedCurves.push(id);
				if (lightSummary(id)) markLight(id, got.summary);
				else if (storeHeldMore(w.shot, got.details)) bring.set(id, got.details);
			}
			if (now.get(id) === w.shot) this.written.set(id, w.shot);
		}
		if (savedCurves.length > 0) this.clearUnsaved(savedCurves);
		if (bring.size > 0) {
			// The store kept heavy parts memory didn't hold: memory shows them too.
			this.shots = this.shots.map((s) => {
				const d = bring.get(s.id);
				if (!d) return s;
				const full = withDetails(s, d);
				this.rebase(s, full);
				return full;
			});
		}
		dropPending(writes.map((w) => w.shot.id));
		this.post(
			writes.map((w) => w.shot.id),
			seq
		);
		this.settleView();
		this.maybeSnapshot();
		if (this.dirtyRowIds().length > 0) this.scheduleFlush();
	}

	/**
	 * A write failed (most likely quota). The rows alone are about 1 KB each and
	 * may fit: try them; failing that, new shots go to the journal. Their curves
	 * stay in memory and go with each later save, and the banner says so.
	 */
	private async writeFailed(writes: readonly ShotWrite[], error: unknown): Promise<void> {
		this.lastError = errorText(error);
		const withHeavy = writes.filter((w) => w.details);
		const rowsOnly: ShotWrite[] = writes.map((w) => ({
			shot: w.shot,
			details: false,
			summary: w.summary ?? summarize(w.shot)
		}));
		let rowsStored = false;
		try {
			const r = await this.db!.putShots(rowsOnly, summarize);
			rowsStored = true;
			const now = this.byId();
			for (const w of rowsOnly) {
				this.stored.add(w.shot.id);
				if (!this.summaries.has(w.shot.id) && w.summary) this.summaries.set(w.shot.id, w.summary);
				if (now.get(w.shot.id) === w.shot) this.written.set(w.shot.id, w.shot);
			}
			this.lastSeq = Math.max(this.lastSeq, r.seq);
			this.post(
				rowsOnly.map((w) => w.shot.id),
				r.seq
			);
		} catch {
			for (const w of writes) if (!this.stored.has(w.shot.id)) addPending(w.shot);
		}
		const lost = withHeavy
			.filter((w) => sampleCountOf(w.shot) > 0 || (w.shot.brewSeries?.samples.length ?? 0) > 0 || w.shot.profile != null)
			.map((w) => w.shot.id);
		const notStored = rowsStored ? [] : writes.filter((w) => !this.stored.has(w.shot.id)).map((w) => w.shot.id);
		this.markUnsaved([...lost, ...notStored]);
	}

	private markUnsaved(ids: readonly string[]): void {
		if (ids.length === 0) return;
		const next = new Set([...this.unsavedIds, ...ids]);
		this.unsavedIds = [...next];
	}

	private clearUnsaved(ids: readonly string[]): void {
		if (this.unsavedIds.length === 0) return;
		const gone = new Set(ids);
		const next = this.unsavedIds.filter((id) => !gone.has(id));
		if (next.length !== this.unsavedIds.length) this.unsavedIds = next;
	}

	/** The store can't be written: new shots go to the journal, so a reload keeps them. */
	private journalNew(): void {
		const fresh = this.shots.filter((s) => !this.stored.has(s.id) && this.written.get(s.id) !== s);
		const kept: string[] = [];
		for (const s of fresh) {
			if (addPending(s)) {
				this.written.set(s.id, s);
				kept.push(s.id);
			}
		}
		this.markUnsaved(fresh.map((s) => s.id));
	}

	/**
	 * Remove `ids` for good, by name: from memory now, then from the store, in
	 * order after any write before it. `after` runs once the store has dropped
	 * them. The legacy backend writes its blob and deletes their series.
	 * Resolves to whether the store dropped them.
	 */
	private removeRows(ids: readonly string[], after?: () => void): Promise<boolean> {
		if (ids.length === 0) return Promise.resolve(true);
		const gone = new Set(ids);
		this.shots = this.shots.filter((s) => !gone.has(s.id));
		if (this.mode === 'legacy') {
			const ok = this.persistLegacy();
			if (ok) after?.();
			return Promise.resolve(ok);
		}
		for (const id of ids) {
			this.forget(id);
			this.removing.add(id);
		}
		this.clearUnsaved(ids);
		dropPending(ids);
		this.settleView();
		const db = this.db;
		let result = true;
		const op = this.writeChain.then(async () => {
			try {
				if (!db || this.mode !== 'idb') {
					// Nothing reached the store; a journalled row went with dropPending.
					result = this.mode === 'blocked';
					return;
				}
				const seq = await db.delete(ids);
				this.lastSeq = Math.max(this.lastSeq, seq);
				this.post(ids, seq);
				after?.();
			} catch (e) {
				result = false;
				this.lastError = errorText(e);
				toast.error('A shot couldn’t be removed from storage, so it may come back after a reload.');
			} finally {
				for (const id of ids) this.removing.delete(id);
			}
		});
		this.writeChain = op;
		return op.then(() => result);
	}

	/** After the first save each day: every row, outside the database. */
	private maybeSnapshot(): void {
		if (this.snapshotting || !this.db || !snapshotDue(this.now())) return;
		// Once a day per tab, written or not: with no private file system every
		// save would read every row again.
		const today = dayOf(this.now());
		if (this.snapshotTried === today) return;
		this.snapshotTried = today;
		this.snapshotting = true;
		const db = this.db;
		void (async () => {
			try {
				const rows = await db.allRows();
				await writeRowsSnapshot(rows, this.now(), this.opts.snapshotRoot);
			} catch {
				// Tomorrow's goes again.
			} finally {
				this.snapshotting = false;
			}
		})();
	}

	// ── Lost and unavailable ──────────────────────────────────────────────

	/** Try the store again (Settings, Try again; or the tab coming back). */
	async retryOpen(): Promise<boolean> {
		if (this.healthState !== 'unavailable') return this.healthState === 'ok';
		const db = this.newDb(() => markerSaysDone());
		try {
			await db.open();
			const record = await db.meta<MigrationRecord>(META_MIGRATION);
			if (!record || record.state !== 'done') throw new HistoryDbLost();
		} catch (e) {
			if (e instanceof HistoryDbLost) {
				await this.readOldCopyInfo();
				this.block('lost', e.message);
			} else this.lastError = errorText(e);
			return false;
		}
		this.db = db;
		this.mode = 'booting';
		this.healthState = 'loading';
		await this.loadFromDb();
		return this.health === 'ok';
	}

	/** A store made new, for a lost one: the user chose how to fill it. */
	private async freshStore(): Promise<HistoryDb> {
		const db = this.newDb(() => false);
		await db.open();
		await db.clearAll();
		return db;
	}

	/** The old copy's ids, so the late-row check doesn't take them all again. */
	private oldCopyRecord(): MigrationRecord {
		const raw = this.source().raw();
		let ids: string[] = [];
		try {
			const items = raw ? (JSON.parse(raw) as unknown) : [];
			if (Array.isArray(items)) ids = sourceShots(items, coerceStoredShot).shots.map((s) => s.id);
		} catch {
			ids = [];
		}
		return { state: 'done', sourceHash: hashText(raw ?? ''), sourceIds: ids, doneAt: this.now() };
	}

	private async recovered(db: HistoryDb, rows: number): Promise<void> {
		writeDoneMarker(this.now(), rows);
		this.db = db;
		this.mode = 'booting';
		this.healthState = 'loading';
		this.oldCopyInfo = null;
		await this.loadFromDb();
	}

	/** Lost: start again with no shots. */
	async startEmpty(): Promise<void> {
		if (this.healthState !== 'lost') return;
		const db = await this.freshStore();
		await db.setMeta(META_MIGRATION, this.oldCopyRecord());
		await this.recovered(db, 0);
	}

	/** Lost: the old copy, as it was when it moved. */
	async recoverOldCopy(): Promise<boolean> {
		if (this.healthState !== 'lost') return false;
		const db = await this.freshStore();
		const result = await copyLegacy(this.migrationDeps(db), coerceStoredShot);
		if (result.kind !== 'done') {
			this.lastError = result.error;
			return false;
		}
		await this.recovered(db, result.rows);
		return true;
	}

	/** Lost: the daily rows snapshot. Their curves were in the lost store. */
	async recoverSnapshot(): Promise<boolean> {
		if (this.healthState !== 'lost') return false;
		const snap = await readRowsSnapshot(this.opts.snapshotRoot);
		if (!snap) return false;
		const db = await this.freshStore();
		const writes: ShotWrite[] = snap.rows.map((r) => {
			const { shot, summary } = shotOfRow(r);
			return { shot, details: false, summary };
		});
		for (let i = 0; i < writes.length; i += 500) await db.putShots(writes.slice(i, i + 500), summarize);
		await db.setMeta(META_MIGRATION, this.oldCopyRecord());
		await this.recovered(db, writes.length);
		return true;
	}

	/** Whether a rows snapshot exists to recover, and its date. */
	async snapshotInfo(): Promise<{ at: number; rows: number } | null> {
		const snap = await readRowsSnapshot(this.opts.snapshotRoot);
		return snap ? { at: snap.at, rows: snap.rows.length } : null;
	}

	// ── Legacy backend ─────────────────────────────────────────────────────

	private async hydrateSeries(): Promise<void> {
		try {
			const legacy = this.shots.filter((s) => s.record.samples.length > 0);
			for (const shot of legacy) {
				await putSeries(shot.id, shot.record.samples);
			}
			// One retry: a read can fail once and work the next time.
			const series = await getAllSeries().catch(() => getAllSeries());
			this.shots = this.shots.map((s) =>
				s.record.samples.length === 0 && series.has(s.id)
					? { ...s, record: { ...s.record, samples: series.get(s.id) ?? [] } }
					: s
			);
			if (legacy.length > 0) this.persist();
		} catch {
			// IndexedDB unavailable (private mode, etc.) — history still
			// works this session from memory; only durability degrades.
			this.seriesUnreadable = true;
		}
	}

	/** Point {@link known} at `ids`, writing and deleting nothing. */
	private markLive(ids: Iterable<string>): void {
		this.known = new Set(ids);
	}

	/**
	 * Take rows another tab wrote (legacy backend). Nothing is deleted: a stale
	 * tab's rows must not cost this one a good curve, and the tab that removed a
	 * row deletes its series.
	 */
	adoptRows(rows: StoredShot[]): void {
		this.shots = rows;
		this.markLive(rows.map((s) => s.id));
	}

	/**
	 * Rows another tab stored (#333), on the legacy backend. The stored rows
	 * carry no series, so a row this tab already shows keeps its curve, and the
	 * others read theirs from IndexedDB. Not during a restore here, which owns
	 * the rows until it ends.
	 */
	private takeOtherTabsRows(items: unknown[]): void {
		if (this.deletesPaused > 0 || this.mode !== 'legacy') return;
		const mine = new Map(this.shots.map((s) => [s.id, s]));
		const rows = rowsOf(items).map((row) => {
			const had = mine.get(row.id)?.record.samples;
			return row.record.samples.length === 0 && had && had.length > 0
				? { ...row, record: { ...row.record, samples: had } }
				: row;
		});
		this.adoptRows(rows);
		const bare = rows.filter((s) => s.record.samples.length === 0).map((s) => s.id);
		if (bare.length > 0 && typeof indexedDB !== 'undefined') void this.hydrateAdopted(bare);
	}

	/**
	 * The series of rows adopted without one. The other tab writes a series
	 * after its row, so one that isn't there yet is looked for again, twice. A
	 * row with no series at all (a brew log) just finds none.
	 */
	private async hydrateAdopted(ids: string[], waits: readonly number[] = ADOPT_SERIES_RETRY_MS): Promise<void> {
		for (const id of ids) this.hydrating.set(id, (this.hydrating.get(id) ?? 0) + 1);
		let missing = ids;
		try {
			for (let attempt = 0; ; attempt++) {
				let found = new Map<string, TimedSample[]>();
				try {
					found = await getSeriesOf(missing);
				} catch {
					// A read can fail once and work the next time: keep to the schedule.
				}
				if (found.size > 0) this.fillCurves(found);
				missing = missing.filter((id) => !found.has(id) && this.byId().get(id)?.record.samples.length === 0);
				if (missing.length === 0 || attempt >= waits.length) return;
				await new Promise((resolve) => setTimeout(resolve, waits[attempt]));
			}
		} finally {
			for (const id of ids) {
				const n = (this.hydrating.get(id) ?? 1) - 1;
				if (n > 0) this.hydrating.set(id, n);
				else this.hydrating.delete(id);
			}
		}
	}

	/** Give rows without a curve the ones in `found`, by id. */
	private fillCurves(found: Map<string, TimedSample[]>): void {
		this.shots = this.shots.map((s) => {
			const samples = found.get(s.id);
			return samples && s.record.samples.length === 0
				? { ...s, record: { ...s.record, samples } }
				: s;
		});
	}

	/**
	 * Whether `id` adopted from another tab may still have its curve on the
	 * way. Until it's settled, a pull mustn't take Visualizer's copy for it
	 * (#380 review S1).
	 */
	seriesPending(id: string): boolean {
		return this.hydrating.has(id);
	}

	/**
	 * A pulled curve for a row that had none. On the legacy backend, stored
	 * only if IndexedDB has none for it either: another tab may have written the
	 * row's own, and that one wins, here and in memory (#380 review S1). On
	 * `crema-history` the details write keeps a stored curve the same way.
	 */
	private backfillSeries(id: string, samples: TimedSample[]): void {
		if (this.mode !== 'legacy') {
			this.dirtyDetails.add(id);
			return;
		}
		void addSeries(id, samples)
			.then(async (added) => {
				if (added) return;
				const own = await getSeriesOf([id]);
				if (own.size === 0) return;
				this.shots = this.shots.map((s) => {
					const curve = own.get(s.id);
					return curve ? { ...s, record: { ...s.record, samples: curve } } : s;
				});
			})
			.catch(seriesSaveFailed);
	}

	/**
	 * Turn series and capture deletes off until the returned function runs, for
	 * a restore: saves still write and still move {@link known} to what they
	 * wrote, but delete nothing. Restore's own deletes arrive with its marker
	 * (restore-safety PR7). Another tab's rows wait for it to end, too.
	 */
	pauseDeletes(): () => void {
		this.deletesPaused += 1;
		let resumed = false;
		return () => {
			if (resumed) return;
			resumed = true;
			this.deletesPaused -= 1;
			if (this.deletesPaused === 0 && this.mode === 'idb' && this.heard.size > 0) this.applyHeard();
		};
	}

	/**
	 * The legacy save: the shot list to localStorage — WITHOUT the sample series
	 * (those live in IndexedDB, review #27). A quota failure evicts the oldest
	 * shots and retries once, and is never silent. Only quota evicts: a key
	 * refused because it can't be read must keep every row in memory, and its
	 * banner says so. Returns whether the rows were stored; only then does
	 * {@link settleDeletes} run, as until then the old rows are still there and
	 * need their curves.
	 */
	private persistLegacy(): boolean {
		const written = this.writeRows();
		if (written === 'ok') {
			this.settleDeletes();
			return true;
		}
		if (written === 'blocked') return false;
		if (written === 'failed') {
			toast.error('Shot history could not be saved.');
			return false;
		}
		// Quota hit even without series — evict the oldest 10% and retry. A
		// pinned row is never evicted (#311): the oldest unpinned ones go.
		const before = this.shots.length;
		this.shots = evictOldest(this.shots, before - Math.max(1, Math.floor(before * 0.9)));
		const dropped = before - this.shots.length;
		if (this.writeRows() !== 'ok') {
			toast.error('Shot history could not be saved — storage is full.');
			return false;
		}
		toast.error(
			`Shot storage was full — dropped the ${dropped} oldest shot${dropped === 1 ? '' : 's'} to keep saving.`
		);
		this.settleDeletes();
		return true;
	}

	/** Write the rows to localStorage, without their series (legacy backend). */
	private writeRows(): StoreWrite {
		const stripped = this.shots.map((s) => ({
			...s,
			record: { ...s.record, samples: [] }
		}));
		return writeStore(HISTORY_KEY, stripped, false);
	}

	/** After a stored write: delete the curves of the rows that left {@link known}. */
	private settleDeletes(): void {
		const ids = new Set(this.shots.map((s) => s.id));
		const gone = [...this.known].filter((id) => !ids.has(id));
		this.markLive(ids);
		if (this.deletesPaused > 0) return;
		for (const id of gone) {
			void deleteSeries(id).catch(() => {});
			dropCapture(id);
		}
	}

	// ── Adding rows ────────────────────────────────────────────────────────

	/** The view as it would be with `rows`. */
	private wouldShow(rows: readonly StoredShot[], keepId?: string): Set<string> {
		return new Set(cappedHistory(rows, MAX_RECORDS, keepId).map((s) => s.id));
	}

	/**
	 * Add a row by its date. The list stays newest first, so the cap drops the
	 * oldest rows and the core's "last brew" is the newest one, however the row
	 * was dated. A row older than a full history is the one that goes.
	 */
	private insert(shot: StoredShot): void {
		this.capWith(shot, newestFirst([shot, ...this.shots]));
	}

	/**
	 * Add a row just made on this device. It stays whatever its date, so a clock
	 * behind the history can't lose it: the oldest other row goes instead.
	 */
	private insertMade(shot: StoredShot): void {
		this.capWith(shot, newestFirst([shot, ...this.shots]), shot.id);
	}

	/**
	 * Take `rows`, the history with `shot` added, and say what the cap did
	 * (#310 P0): a row never leaves without a word. Pinned rows are never among
	 * them (#311). On the legacy backend the cap drops rows; on `crema-history`
	 * the older ones are kept, hidden, and only a new row older than the view
	 * is refused, as before (SPEC §8: the refusal stays until the cap lifts).
	 */
	private capWith(shot: StoredShot, rows: StoredShot[], keepId?: string): void {
		const shown = this.wouldShow(rows, keepId);
		if (this.mode === 'legacy') {
			const next = rows.filter((s) => shown.has(s.id));
			const droppedOlder = this.shots.filter((s) => !shown.has(s.id)).length;
			this.shots = next;
			const notice = historyCutNotice(droppedOlder, shown.has(shot.id), MAX_RECORDS);
			if (notice) toast.info(notice, 7000);
			return;
		}
		if (!shown.has(shot.id)) {
			const notice = historyCutNotice(0, false, MAX_RECORDS);
			if (notice) toast.info(notice, 7000);
			return;
		}
		const hiddenNow = this.viewOf().view.filter((s) => !shown.has(s.id)).length;
		this.shots = rows;
		this.dirtyDetails.add(shot.id);
		if (hiddenNow > 0 && !this.hiddenNoticeShown) {
			this.hiddenNoticeShown = true;
			toast.info(
				`History shows the newest ${MAX_RECORDS} shots. Older ones are kept on this device and will show again in a later update.`,
				7000
			);
		}
	}

	/** The most rows the history shows. */
	get capacity(): number {
		return MAX_RECORDS;
	}

	/** The cap a restore plans with: none once every row is kept (SPEC §7). */
	get restoreCapacity(): number | null {
		return this.mode === 'idb' ? null : MAX_RECORDS;
	}

	/** How many live rows fit in view before the cap. */
	get freeSlots(): number {
		return Math.max(0, MAX_RECORDS - cappedCount(this.viewOf().view));
	}

	/** Whether a new brew dated `at` would stay in the history once added. */
	fitsHistory(at: number): boolean {
		return fitsHistory(this.viewOf().live, at, MAX_RECORDS);
	}

	/**
	 * A restore's history (restore-safety step 7). The rows with curves to write
	 * go first, with no change to memory, so rows never name a series that isn't
	 * written; then memory and the rows write happen in one task, so an edit
	 * can't reach a half-applied history. `series` are the rows whose curve to
	 * write. Says whether the rows were stored, and how many curves weren't.
	 *
	 * On the legacy backend nothing is deleted here or evicted: the restore's
	 * marker owns its deletes. On `crema-history` the rows go straight to the
	 * store, past the view's cap, and incoming rows overwrite local ones with
	 * the same id; the last step is one explicit delete of the rows the plan
	 * names in `remove` (SPEC §7: merge, then remove). A kill part-way leaves
	 * the union, which a re-run finishes.
	 */
	async restoreRows(
		plan: (rows: readonly StoredShot[]) => { next: StoredShot[]; series: StoredShot[]; remove?: readonly string[] },
		writeSeries: (rows: readonly StoredShot[]) => Promise<void> = (rows) =>
			putManySeries(rows.map((s) => ({ id: s.id, samples: s.record.samples })))
	): Promise<{ stored: boolean; curvesFailed: number }> {
		await this.whenReady();
		if (this.mode === 'idb') return this.restoreRowsToStore(plan);
		const first = plan(this.shots).series.filter((s) => s.record.samples.length > 0);
		let curvesFailed = 0;
		try {
			await writeSeries(first);
		} catch {
			curvesFailed = first.length;
		}
		// After the last await: rows recorded, rated or bound while the series
		// were written are in `this.shots`, and the plan runs over them.
		const { next, series } = plan(this.shots);
		const written = new Set(first.map((s) => s.id));
		// A row the recompute found anew keeps its curve in memory, and it's
		// written straight after the rows, before the restore says it's done.
		const late = series.filter((s) => !written.has(s.id) && s.record.samples.length > 0);
		this.shots = next;
		if (this.writeRows() !== 'ok') return { stored: false, curvesFailed };
		this.markLive(next.map((s) => s.id));
		if (late.length > 0) {
			try {
				await writeSeries(late);
			} catch {
				curvesFailed += late.length;
			}
		}
		return { stored: true, curvesFailed };
	}

	private async restoreRowsToStore(
		plan: (rows: readonly StoredShot[]) => { next: StoredShot[]; series: StoredShot[]; remove?: readonly string[] }
	): Promise<{ stored: boolean; curvesFailed: number }> {
		const db = this.db!;
		await this.flushed();
		// 1. The rows with curves, in batches straight to the store.
		const first = plan(this.shots).series;
		const done = new Map<string, DetailsRecord>();
		for (let i = 0; i < first.length; i += 25) {
			const part = first.slice(i, i + 25);
			try {
				const r = await db.putShots(
					part.map((shot) => ({ shot, details: true })),
					summarize
				);
				this.lastSeq = Math.max(this.lastSeq, r.seq);
				for (const [id, got] of r.written) {
					this.summaries.set(id, got.summary);
					this.stored.add(id);
					done.set(id, got.details);
				}
				this.post(
					part.map((s) => s.id),
					r.seq
				);
			} catch (e) {
				this.lastError = errorText(e);
				return { stored: false, curvesFailed: 0 };
			}
		}
		// 2. After the last await: the plan again, over the rows as they are
		// now. Memory takes it in this task; the rows go out next, as a diff.
		const { next, series, remove } = plan(this.shots);
		const named = new Set(remove ?? []);
		const nextIds = new Set(next.map((s) => s.id));
		// Only a row the plan names goes; anything else missing stays.
		const kept = this.shots.filter((s) => !nextIds.has(s.id) && !named.has(s.id));
		const gone = this.shots.filter((s) => !nextIds.has(s.id) && named.has(s.id)).map((s) => s.id);
		for (const s of series) if (!done.has(s.id)) this.dirtyDetails.add(s.id);
		const rows = next.map((s) => {
			const d = done.get(s.id);
			if (!d) return s;
			// What step 1 stored, which keeps a curve the device had.
			markLoaded(s.id);
			return withDetails(s, d);
		});
		this.shots = [...rows, ...kept].sort(byDateThenId);
		this.settleView();
		this.scheduleFlush();
		// 3. The one explicit delete: rows not in the backup, as the plan names them.
		const removed = this.removeRows(gone);
		await this.flushed();
		const stored = this.dirtyRowIds().length === 0 && (await removed);
		return { stored, curvesFailed: 0 };
	}

	/** Wipe all recorded shots — for a "replace from backup" restore, and tests. */
	clearAllShots(): void {
		if (this.mode === 'legacy') {
			this.shots = [];
			if (this.persistLegacy()) void clearAllSeries().catch(() => {});
			return;
		}
		this.removeRows(this.shots.map((s) => s.id));
	}

	/**
	 * Record a finished shot the core assembled (`CremaCore.takeStoredShot`,
	 * F10): the telemetry with its frames and overlays, the as-run profile and
	 * values, the grinder, the stop reason — one rule set shared with Android.
	 * Normalised through the same loader every persisted row goes through,
	 * inserted by date and persisted, with its series, in one transaction.
	 * `null` for a shot with no telemetry.
	 */
	recordCaptured(shot: Record<string, unknown>): StoredShot | null {
		const record = coerceStoredShot(shot);
		if (!record || record.record.samples.length === 0) return null;
		this.insertMade(record);
		this.persist();
		// On the legacy backend the series rides IndexedDB (review #27) —
		// fire-and-forget with a surface, like the capture store.
		if (this.mode === 'legacy') void putSeries(record.id, record.record.samples).catch(seriesSaveFailed);
		return record;
	}

	/** Replace the row `id` with `edit(row)` and persist; no-op on an unknown id. */
	private edit(id: string, edit: (row: StoredShot) => StoredShot | null): void {
		const idx = this.shots.findIndex((s) => s.id === id);
		if (idx < 0) return;
		const next = edit(this.shots[idx]);
		if (next === null || next === this.shots[idx]) return;
		this.shots = [...this.shots.slice(0, idx), next, ...this.shots.slice(idx + 1)];
		this.persist();
	}

	/** Update a shot's tasting notes and persist. */
	setNotes(id: string, notes: string): void {
		this.edit(id, (target) => ({ ...target, metadata: { ...target.metadata, notes } }));
	}

	/**
	 * Update a shot's forward-looking "next time" plan and persist.
	 * Mirrors {@link setNotes}: a thin replace-and-persist. An empty
	 * trimmed string clears the plan (stored as `null`) so the brew
	 * screen's dial-in card doesn't render blank text.
	 */
	setNextPlan(id: string, nextPlan: string): void {
		const value = nextPlan.trim() === '' ? null : nextPlan;
		this.edit(id, (target) => ({ ...target, metadata: { ...target.metadata, nextPlan: value } }));
	}

	/**
	 * Set (or with `null` / a blank string, clear) who made or drank a shot.
	 * Mirrors {@link setNotes}: a thin replace-and-persist; the value is
	 * trimmed.
	 */
	setPerson(id: string, field: 'barista' | 'drinker', name: string | null): void {
		const value = name?.trim() || null;
		this.edit(id, (target) =>
			(target.metadata[field] ?? null) === value ? null : { ...target, metadata: { ...target.metadata, [field]: value } }
		);
	}

	/**
	 * Set (or with `null`, clear) the grind recorded on a stored shot
	 * (issue #16: dialing-in spans shots, so the log needs fixing up after
	 * the fact — the History detail's grind stepper writes here). Clearing
	 * falls back to the bean snapshot's reference setting for display and
	 * wire purposes (see `effectiveGrindSetting`). Mirrors {@link setNotes}.
	 */
	setGrindSetting(id: string, grinderSetting: string | null): void {
		this.edit(id, (target) => ({ ...target, metadata: { ...target.metadata, grinderSetting } }));
	}

	/**
	 * Update a shot's equipment-level grinder-model override and persist.
	 * Pass `null` (or call with an empty string after trimming) to clear
	 * the override — the upload-time cascade then falls back to the
	 * settings default. Mirrors {@link setNotes}.
	 */
	setGrinderModel(id: string, grinderModel: string | null): void {
		this.edit(id, (target) => ({ ...target, grinderModel }));
	}

	/**
	 * Rebind (or clear) the bean snapshot on a shot. Mirrors {@link
	 * setRating} / {@link setNotes} / {@link setTags}: a thin replace-
	 * and-persist over the persisted row.
	 *
	 * Used by the shot-detail panel's "Change bean" / "Assign bean"
	 * affordance to backfill a bean onto a shot that completed without
	 * one (or correct a wrong one). The caller is responsible for
	 * producing the snapshot via {@link snapshotFromBean} so the shape
	 * matches what `app.svelte.ts` writes at completion time —
	 * `setBeanFromLive` below is the convenience that does that.
	 *
	 * Also re-snapshots `shot.tags` from the new bean's tags IF the
	 * shot has no user-set shot-level tags yet (`shot.tags.length === 0`).
	 * A shot that already carries user tags is left alone — the user's
	 * curation wins over the bean tag copy. Clearing the bean (`null`)
	 * does NOT clear `shot.tags` — bean removal shouldn't erase the
	 * user's curation either.
	 */
	setBeanFromLive(
		id: string,
		bean: Bean | null | undefined,
		roaster: Roaster | null | undefined
	): void {
		const snapshot = snapshotFromBean(bean, roaster);
		this.edit(id, (target) => {
			const existingTags = target.tags ?? [];
			const nextTags =
				existingTags.length === 0 && bean?.tags && bean.tags.length > 0 ? [...bean.tags] : existingTags;
			return { ...target, bean: snapshot, tags: nextTags };
		});
	}

	/**
	 * Record the grams a row took from its bag (`StoredShot.bagDebit`), or
	 * clear them when the row no longer has a bag. Stored apart from the bean
	 * snapshot because the debit runs after the row exists.
	 */
	setBagDebit(id: string, grams: number | null): void {
		this.edit(id, (row) => {
			const { bagDebit: _previous, ...rest } = row;
			return grams == null ? rest : { ...rest, bagDebit: grams };
		});
	}

	/**
	 * Replace a shot's tag list and persist. Tags are normalised at the
	 * call site (trim + dedup case-sensitive); this setter is a thin
	 * pass-through so the sync layer can drop a remote-sourced tag list
	 * onto the local row during reconcile (LWW), the same way
	 * {@link setRating} / {@link setNotes} flow editable annotations.
	 */
	setTags(id: string, tags: string[]): void {
		this.edit(id, (target) => ({ ...target, tags: [...tags] }));
	}

	/**
	 * Update a shot's per-upload privacy override and persist. `null`
	 * clears the override — the upload/patch cascade then falls back to
	 * the `Settings.visualizerPrivacy` default. Mirrors {@link setNotes}.
	 */
	setPrivacy(id: string, privacy: 'public' | 'unlisted' | 'private' | null): void {
		this.edit(id, (target) => ({ ...target, privacy }));
	}

	/**
	 * Pin or unpin a shot (#311). A pinned shot's capture ignores the capture
	 * limit and it is exempt from cleanup; unpinning makes its capture eligible
	 * at the next prune. Persists.
	 */
	setPinned(id: string, pinned: boolean): void {
		this.edit(id, (row) => {
			if ((row.pinned === true) === pinned) return null;
			const { pinned: _was, ...rest } = row;
			return pinned ? { ...rest, pinned: true } : rest;
		});
	}

	/** Update a shot's star rating (0–5) and persist. */
	setRating(id: string, rating: number): void {
		const clamped = Math.max(0, Math.min(5, Math.round(rating)));
		this.edit(id, (target) => ({ ...target, metadata: { ...target.metadata, rating: clamped } }));
	}

	/**
	 * Soft-delete a recorded shot by id and persist. The row stays in
	 * storage with `deletedAt` stamped so the next Visualizer sync can
	 * push a DELETE for `visualizerId` and garbage-collect the
	 * tombstone after the remote acknowledges. The History UI filters
	 * tombstones out via {@link all}.
	 *
	 * A row with no `visualizerId` whose upload is under way keeps its
	 * tombstone too, since a binding can arrive later (#331): from the upload
	 * finishing, or from a pull finding the row. `deleteRemote` records whether
	 * the remote copy goes as well, once its id is known.
	 *
	 * If the shot was never offered to Visualizer, the row is removed
	 * outright — there is nothing to propagate to a remote.
	 */
	delete(id: string, { deleteRemote = false }: { deleteRemote?: boolean } = {}): void {
		const idx = this.shots.findIndex((s) => s.id === id);
		if (idx < 0) return;
		const target = this.shots[idx];
		const keepsTombstone = Boolean(target.visualizerId) || uploadPending(id);
		if (!keepsTombstone) {
			// Never synced — hard delete is safe, by name.
			this.removeRows([id], this.mode === 'legacy' ? undefined : () => dropCapture(id));
			return;
		}
		if (!target.visualizerId) markUnboundTombstone(id, deleteRemote);
		this.shots = [
			...this.shots.slice(0, idx),
			{ ...target, deletedAt: Date.now() },
			...this.shots.slice(idx + 1)
		];
		// A tombstone stays until the remote delete lands, which may be never;
		// its capture is the row's, and the row is gone for the user.
		if (this.persist()) dropCapture(id);
	}

	/**
	 * Hard-remove a shot from storage. Called by the sync layer once
	 * the remote DELETE has succeeded for a tombstoned row, so the
	 * tombstone doesn't stay forever.
	 */
	purgeTombstone(id: string): void {
		settleUploadMarks(id);
		if (this.mode === 'legacy') {
			this.shots = this.shots.filter((s) => s.id !== id);
			this.persist();
			return;
		}
		this.removeRows([id]);
	}

	/**
	 * Stamp the Visualizer remote id onto a local shot — called by the
	 * sync layer once an upload returns the remote `id`. Persists, so a
	 * reload sees the binding.
	 */
	bindVisualizerId(id: string, visualizerId: string): void {
		const tombstone = settleUploadMarks(id);
		// Deleted during its upload, with the remote copy to go too.
		if (tombstone?.deleteRemote) {
			enqueueEntry({ entity: 'shot', id, op: 'delete', visualizerId, parkedFor: tombstone.parkedFor });
		}
		const idx = this.shots.findIndex((s) => s.id === id);
		if (idx < 0) {
			keepRemoteOut(visualizerId);
			return;
		}
		this.edit(id, (row) => ({ ...row, visualizerId }));
	}

	/**
	 * Stamp the Decent shot-history id onto a local shot once the upload
	 * returns it (geota/crema#84). Persists. Mirrors {@link bindVisualizerId}.
	 * `machine` — the identity the upload was sent with, passed only when the
	 * shot had no usable one (a pre-#84 row, or one stamped with an unknown
	 * serial `0`, uploaded with the connected DE1's serial), so its share link
	 * (`/shot/<serial>/<id>`) resolves later.
	 */
	bindDecentId(id: string, decentId: string, machine?: ShotMachine): void {
		this.edit(id, (cur) => ({ ...cur, decentId, ...(machine ? { machine: { ...machine } } : {}) }));
	}

	/**
	 * Insert a shot a Visualizer pull brought in. The caller has already mapped
	 * it onto a `StoredShot` and verified de-dup. Inserts by date and persists.
	 *
	 * A pull never removes a row: the oldest ones can be brew logs or shots that
	 * never reached Visualizer. So with no free slot in view the row is
	 * refused, and this answers false.
	 */
	insertPulled(shot: StoredShot): boolean {
		if (this.freeSlots === 0) return false;
		this.shots = newestFirst([shot, ...this.shots]);
		if (this.mode === 'legacy') {
			if (shot.record.samples.length > 0) {
				void putSeries(shot.id, shot.record.samples).catch(seriesSaveFailed);
			}
		} else this.dirtyDetails.add(shot.id);
		this.persist();
		return true;
	}

	/**
	 * Insert many rows at once — a restore or a file of shots — and say what the
	 * cap left: an earlier row of the batch can be cut by a later one, so only
	 * the end result counts. Rows already here, or repeated, are skipped. With
	 * nothing new this changes nothing, so a list over the cap stays as it is.
	 * On `crema-history` only new rows older than the view are refused; older
	 * rows here are kept, hidden.
	 */
	insertMany(shots: readonly StoredShot[]): HistoryInsert<StoredShot> {
		const ids = new Set(this.shots.map((s) => s.id));
		const fresh = shots.filter((s) => !ids.has(s.id) && !!ids.add(s.id));
		if (fresh.length === 0) return { kept: [], dropped: 0 };
		const freshIds = new Set(fresh.map((s) => s.id));
		const combined = newestFirst([...fresh, ...this.shots]);
		if (this.mode !== 'legacy') {
			const shown = this.wouldShow(combined);
			const refused = new Set(fresh.filter((s) => !shown.has(s.id)).map((s) => s.id));
			this.shots = combined.filter((s) => !refused.has(s.id));
			for (const s of fresh) if (!refused.has(s.id)) this.dirtyDetails.add(s.id);
			this.persist();
			return { kept: this.shots.filter((s) => freshIds.has(s.id)), dropped: refused.size };
		}
		const before = this.shots.length + fresh.length;
		this.shots = cappedHistory(combined, MAX_RECORDS);
		// After `persist`: a full store trims the list, and the count must say so.
		this.persist();
		const kept = this.shots.filter((s) => freshIds.has(s.id));
		for (const shot of kept) {
			if (shot.record.samples.length > 0) {
				void putSeries(shot.id, shot.record.samples).catch(seriesSaveFailed);
			}
		}
		return { kept, dropped: before - this.shots.length };
	}

	/**
	 * Apply what a pull may change on a row (the core's `merge_remote_shot`), in
	 * one write: tags it adds, a barista into a blank one, the curve and the
	 * recipe when the row has none. The remote version goes last, so a row is
	 * never marked current without the rest. Nothing else can change here.
	 */
	applyRemote(id: string, patch: RemoteShotPatch, pulled: PulledParts = {}): void {
		const idx = this.shots.findIndex((s) => s.id === id);
		if (idx < 0) return;
		const target = this.shots[idx];
		let next: StoredShot = target;
		if (patch.tags) next = { ...next, tags: [...patch.tags] };
		if (patch.barista && !next.metadata.barista?.trim()) {
			next = { ...next, metadata: { ...next.metadata, barista: patch.barista } };
		}
		const samples = pulled.samples ?? [];
		const backfill =
			patch.backfillSamples === true && samples.length > 0 && sampleCountOf(next) === 0;
		if (backfill) {
			next = {
				...next,
				record: { duration: pulled.durationMs || next.record.duration, samples: [...samples] }
			};
		}
		const fillProfile = patch.fillProfile && pulled.profile != null && next.profile == null;
		if (fillProfile) {
			next = { ...next, profile: pulled.profile };
		}
		if (patch.remoteVersion != null) next = { ...next, visualizerUpdatedAt: patch.remoteVersion };
		if (next === target) return;
		this.shots = [...this.shots.slice(0, idx), next, ...this.shots.slice(idx + 1)];
		if (backfill) this.backfillSeries(id, [...samples]);
		else if (fillProfile && this.mode !== 'legacy') this.dirtyDetails.add(id);
		this.persist();
	}

	/**
	 * Backfill telemetry onto a shot that was pulled as a metadata-only
	 * stub (empty `record.samples`) before telemetry import existed.
	 * Called by the sync reconciler when a later pull carries the curve
	 * for an already-local shot, so old stubs gain a graph on the next
	 * Sync without a manual delete + re-pull.
	 *
	 * No-op when the incoming series is empty or the local shot already
	 * has samples — never clobber a locally-recorded curve with a pulled
	 * one.
	 */
	backfillTelemetry(id: string, samples: readonly TimedSample[], durationMs: number): void {
		if (samples.length === 0) return;
		const idx = this.shots.findIndex((s) => s.id === id);
		if (idx < 0) return;
		const target = this.shots[idx];
		if (sampleCountOf(target) > 0 || this.seriesPending(id)) return;
		this.shots = [
			...this.shots.slice(0, idx),
			{
				...target,
				record: {
					duration: durationMs || target.record.duration,
					samples: [...samples]
				}
			},
			...this.shots.slice(idx + 1)
		];
		this.backfillSeries(id, [...samples]);
		this.persist();
	}

	/**
	 * Adopt a shot the user imported from a legacy de1app `.shot` or
	 * modern `.shot.json` file. The Rust core does the
	 * parsing — the shell maps the Rust-shape `StoredShot` onto Crema's
	 * own `StoredShot` and prepends it like a freshly-recorded shot.
	 *
	 * `extras` carries the Crema-only fields that ride in
	 * `metadata.crema.*` of the exported `.shot.json` and are not part
	 * of the v2 schema the Rust core models — `grinderModel`, `tags`,
	 * inline-bean snapshot extras (`roastedOn`, `roastLevel`, `notes`),
	 * and the QC-override snapshot (`yieldTarget` / `brewTemp` /
	 * `preinfuseTarget` / `stopOnWeight` / `autoTare`). The shell's
	 * `importShotFile` extracts the block before handing the JSON to
	 * the wasm parser; legacy `.shot` (TCL) imports and pre-this-PR
	 * `.shot.json` files have no extras and pass `undefined`.
	 *
	 * `null` if the imported record carries no telemetry samples (the
	 * UI treats this as a "couldn't parse" error and toasts).
	 */
	addImported(imported: RustStoredShot, extras?: ImportExtras): StoredShot | null {
		// Zero telemetry is a parse failure for espresso files — but a
		// legitimate shape for a Brew Log row (a re-imported manual V60
		// carries the crema_brew_method extension key and no samples).
		if (imported.record.samples.length === 0 && !imported.brewMethod) return null;
		const bean = beanFromImported(
			imported.metadata.beans ?? null,
			imported.metadata.grinderSetting ?? null,
			extras?.bean ?? null
		);
		const record: StoredShot = {
			formatVersion: STORED_SHOT_FORMAT_VERSION,
			id: shotId(),
			completedAt: imported.completedAt,
			profileName: imported.profile?.title ?? null,
			profile: imported.profile ?? null,
			metadata: {
				...imported.metadata,
				notes: imported.metadata.notes ?? null,
				rating: imported.metadata.rating ?? null
			},
			record: imported.record,
			bean,
			grinderModel: extras?.grinderModel ?? null,
			yieldTarget: extras?.yieldTarget ?? null,
			brewTempTarget: extras?.brewTemp ?? null,
			preinfuseTarget: extras?.preinfuseTarget ?? null,
			stopOnWeight: extras?.stopOnWeight,
			autoTare: extras?.autoTare,
			tags: extras?.tags ? [...extras.tags] : [],
			...(imported.brewMethod ? { brewMethod: imported.brewMethod } : {}),
			...(imported.recipeName ? { recipeName: imported.recipeName } : {}),
			...(imported.brewSeries ? { brewSeries: imported.brewSeries } : {})
		};
		this.insert(record);
		this.persist();
		if (this.mode === 'legacy') void putSeries(record.id, record.record.samples).catch(seriesSaveFailed);
		return record;
	}

	/**
	 * Record a Brew Log entry (issue #10) — a manually logged brew, or a
	 * finished guided session. The one path that legitimately persists a
	 * zero-sample row: `record()` / `addImported()` keep their guards so
	 * the live-espresso and file-import paths can't regress into storing
	 * empty telemetry by accident.
	 *
	 * Inventory is NOT debited here — the caller pairs this with
	 * `debitBean(beanId, dose)` and records what it took with
	 * {@link setBagDebit}, exactly like the live-shot persistence path, so
	 * the bag-empty prompt logic stays in one place.
	 */
	addManualBrew(input: ManualBrewInput): StoredShot {
		const metadata: ShotMetadata = {
			dose: input.dose ?? null,
			yieldOut: input.yieldOut ?? null,
			waterG: input.waterG ?? null,
			grinderSetting: input.grinderSetting ?? null,
			rating: input.rating ?? null,
			notes: input.notes ?? null,
			nextPlan: input.nextPlan ?? null,
			...(input.barista?.trim() ? { barista: input.barista.trim() } : {})
		};
		const record: StoredShot = {
			formatVersion: STORED_SHOT_FORMAT_VERSION,
			id: shotId(),
			completedAt: input.completedAt,
			profileName: null,
			profile: null,
			metadata,
			record: {
				duration: input.durationMs ?? 0,
				samples: []
			},
			bean: input.bean ?? null,
			// Never persist an empty method: blank → `null` (the one rule
			// both shells follow; `isBrewLog` is presence-based).
			brewMethod: input.method.trim() ? input.method : null,
			...(input.methodLabel?.trim() ? { brewMethodLabel: input.methodLabel.trim() } : {}),
			...(input.recipeName ? { recipeName: input.recipeName } : {}),
			...(input.brewSeries
				? { brewSeries: downsampleBrewSeries(input.brewSeries) }
				: {}),
			brewTempTarget: input.brewTempC ?? null,
			tags: []
		};
		this.insertMade(record);
		this.persist();
		return record;
	}

	/**
	 * Edit the user-entered facts of a manual brew row. Only rows for
	 * which {@link isManualLog} holds may be edited this way — measured
	 * (machine / guided) rows keep their captured values immutable.
	 * Inventory re-settling on a dose change is the caller's job (the
	 * same credit/debit pair bean re-attribution uses). A new date moves
	 * the row to its place in the list; a future one refuses the patch.
	 * No-ops on an unknown id.
	 */
	updateManualBrew(id: string, patch: ManualBrewPatch): void {
		const idx = this.shots.findIndex((s) => s.id === id);
		if (idx < 0) return;
		const shot = this.shots[idx];
		if (!isManualLog(shot)) return;
		if (patch.completedAt !== undefined && brewDateError(patch.completedAt, Date.now())) return;
		// A blank method patch means "no method given" — it never blanks the
		// row's method (which would turn a manual log into a machine shot).
		const method = patch.method?.trim() ? patch.method : undefined;
		const next: StoredShot = {
			...shot,
			...(method !== undefined ? { brewMethod: method } : {}),
			...(patch.completedAt !== undefined ? { completedAt: patch.completedAt } : {}),
			...(patch.brewTempC !== undefined ? { brewTempTarget: patch.brewTempC } : {}),
			...(patch.durationMs !== undefined
				? { record: { ...shot.record, duration: patch.durationMs ?? 0 } }
				: {}),
			metadata: {
				...shot.metadata,
				...(patch.dose !== undefined ? { dose: patch.dose } : {}),
				...(patch.waterG !== undefined ? { waterG: patch.waterG } : {}),
				...(patch.yieldOut !== undefined ? { yieldOut: patch.yieldOut } : {})
			}
		};
		const edited = this.shots.map((s, i) => (i === idx ? next : s));
		this.shots = patch.completedAt !== undefined ? newestFirst(edited) : edited;
		this.persist();
	}

	/**
	 * Re-tag a brew row with a (new) method id and its label snapshot —
	 * the "Save 'X' as a method?" door after a free-text log. No-ops on an
	 * unknown id or a machine shot.
	 */
	retagMethod(id: string, method: string, label: string | null): void {
		if (!method.trim()) return;
		this.edit(id, (shot) => {
			if (shot.brewMethod == null) return null;
			const next: StoredShot = { ...shot, brewMethod: method };
			if (label?.trim()) next.brewMethodLabel = label.trim();
			else delete next.brewMethodLabel;
			return next;
		});
	}
}

/** What a pull fetched besides the detail, for {@link HistoryStore.applyRemote}. */
export interface PulledParts {
	/** The pulled telemetry. */
	readonly samples?: readonly TimedSample[];
	/** The remote duration, ms, kept with a backfilled curve. */
	readonly durationMs?: number;
	/** The pulled recipe (core `Profile` JSON). */
	readonly profile?: unknown;
}

/** Input to {@link HistoryStore.addManualBrew}. */
export interface ManualBrewInput {
	/** Normalized method string (`"pourover"`, `"espresso"`, …). */
	method: string;
	/** A custom method's label, snapshotted onto the row (`null` for presets / free text). */
	methodLabel?: string | null;
	/** Unix epoch ms the brew happened (user-editable for backfill). */
	completedAt: number;
	/** Frozen bean snapshot, or `null` for an untracked coffee. */
	bean: ShotBean | null;
	dose: number | null;
	waterG: number | null;
	yieldOut: number | null;
	grinderSetting: string | null;
	brewTempC: number | null;
	durationMs: number | null;
	rating: number | null;
	notes: string | null;
	nextPlan: string | null;
	/** Who made it — the Settings default barista, when one is set. */
	barista?: string | null;
	/** Guided sessions only: the recipe name + weight series. */
	recipeName?: string | null;
	brewSeries?: BrewSeries | null;
}

/** Editable-fact patch for {@link HistoryStore.updateManualBrew}. */
export interface ManualBrewPatch {
	method?: string;
	completedAt?: number;
	dose?: number | null;
	waterG?: number | null;
	yieldOut?: number | null;
	brewTempC?: number | null;
	durationMs?: number | null;
}

/** The saved guided-series sample cap — the espresso storage posture. */
export const BREW_SERIES_CAP = 200;

/**
 * Thin a guided session's weight series to ~{@link BREW_SERIES_CAP}
 * samples before it rides the localStorage row, with the core's
 * `downsample_indices` (#42 — every Nth, first and last kept, so the
 * chart's time axis and the planned staircase's end still line up).
 * Stage marks are tiny and kept whole.
 */
export function downsampleBrewSeries(series: BrewSeries): BrewSeries {
	const n = series.samples.length;
	if (n <= BREW_SERIES_CAP) return series;
	const samples = Array.from(downsampleIndices(n, BREW_SERIES_CAP), (i) => series.samples[i]);
	return { ...series, samples };
}

/**
 * Map the Rust `record.samples` array onto Crema's `TelemetrySample`
 * shape. Sample-time / steam-temp survive verbatim; the goal series
 * folds into the `setHead*` channels so the LiveChart's dashed-goal
 * overlay works on imports too.
 */
function mapTimedSamples(
	samples: readonly RustStoredShot['record']['samples'][number][]
): TelemetrySample[] {
	return samples.map((t) => {
		// Overlay channels — the Rust importer carries them when the
		// source `.shot.json` recorded them; pre-PR exports emit zeros
		// (the v2 schema requires same-length columns). A literal `0`
		// from a no-scale shot is indistinguishable from "no value" on
		// the wire, so we treat a sub-floor / sub-noise sample as a gap
		// for the resistance fallback path — the chart auto-switch
		// uses per-sample `resistanceWeight ?? resistance`, and an
		// absent value cleanly defers to the DE1-flow sibling.
		const sw = t.scaleWeight;
		const sfw = t.scaleFlowWeight;
		const dv = t.dispensedVolume;
		const r = t.resistance;
		const rw = t.resistanceWeight;
		return {
			elapsed: t.elapsed,
			pressure: t.sample.groupPressure,
			flow: t.sample.groupFlow,
			temp: t.sample.headTemp,
			mixTemp: t.sample.mixTemp,
			steamTemp: t.sample.steamTemp,
			// Imported weight: present whenever the source file recorded
			// a non-trivial scale series; a uniformly-zero placeholder
			// (no scale paired) leaves the channel `null` so the chart
			// renders a gap rather than a flat zero.
			weight: sw != null && sw > 0 ? sw : null,
			weightFlow: sfw != null && sfw > 0 ? sfw : null,
			dispensedVolume: dv != null && dv > 0 ? dv : 0,
			// Re-derive the DE1-flow resistance per-sample when the
			// imported value is missing — legacy `.shot` files without
			// the field, or pre-PR v2 exports.
			resistance:
				r != null && r > 0
					? r
					: t.sample.groupFlow > 0.05
						? t.sample.groupPressure / (t.sample.groupFlow * t.sample.groupFlow)
						: null,
			resistanceWeight: rw != null && rw > 0 ? rw : null,
			// The legacy log records one `temperature_goal` per sample —
			// fan it into `setHeadTemp` (the chart's dashed overlay channel)
			// per `applyEvent` in ui-state.svelte.ts.
			setHeadTemp: t.sample.setHeadTemp,
			setGroupPressure: t.sample.setGroupPressure,
			setGroupFlow: t.sample.setGroupFlow,
			frameNumber: t.sample.frameNumber
		};
	});
}

/**
 * Split the Rust importer's combined `"brand · type"` string back into
 * a `ShotBean` so the History card / detail panel can display it. The
 * legacy log carries no roast-date or roast-level on the bean side, so
 * those stay null when no structured `extras` ride alongside.
 *
 * Post-this-PR `.shot.json` exports carry the richer ShotBean snapshot
 * (roastedOn, roastLevel, notes) under `metadata.crema.bean_*`; when
 * the caller has parsed those out they ride here so the import keeps
 * the same shape the live capture produced. `grinderSetting` comes
 * from the v2 schema's `metadata.grinder.setting` slot.
 */
function beanFromImported(
	label: string | null,
	grinderSetting: string | null,
	extras: ImportBeanExtras | null
): ShotBean | null {
	const hasExtras = extras !== null && (extras.roastedOn !== undefined
		|| extras.roastLevel !== undefined
		|| extras.notes !== undefined);
	if (!label && !hasExtras && !grinderSetting) return null;
	let roaster = '';
	let type = '';
	if (label) {
		const trimmed = label.trim();
		if (trimmed) {
			const parts = trimmed.split('·').map((p) => p.trim()).filter(Boolean);
			roaster = parts[0] ?? trimmed;
			type = parts[1] ?? '';
		}
	}
	const out: Record<string, unknown> = {
		name: type,
		roasterName: roaster.length > 0 ? roaster : null,
		roastedOn: extras?.roastedOn ?? null,
		roastLevel: extras?.roastLevel ?? null
	};
	if (extras?.notes && extras.notes.length > 0) out.notes = extras.notes;
	if (grinderSetting && grinderSetting.length > 0) out.grinderSetting = grinderSetting;
	return out as unknown as ShotBean;
}

/**
 * Structured `metadata.crema.bean_*` slots a Crema-exported `.shot.json`
 * carries — the ShotBean fields the v2 schema's bean object can't model
 * (`roastedOn` / `roastLevel`) plus `notes`. All optional; `undefined`
 * means "absent in the source file", distinct from a parsed `null`
 * (which means "explicitly empty").
 */
export interface ImportBeanExtras {
	readonly roastedOn?: string | null;
	readonly roastLevel?: number | null;
	readonly notes?: string;
}

/**
 * The full `metadata.crema.*` block of a Crema-exported `.shot.json`,
 * extracted by `importShotFile` before the wasm parser strips it. Each
 * field optional; absent means "not in the source" and downstream
 * fall-backs apply (legacy / pre-PR `.shot.json` exports omit the whole
 * block).
 */
export interface ImportExtras {
	readonly grinderModel?: string | null;
	readonly tags?: readonly string[];
	readonly yieldTarget?: number | null;
	readonly brewTemp?: number | null;
	readonly preinfuseTarget?: number | null;
	readonly stopOnWeight?: boolean;
	readonly autoTare?: boolean;
	readonly bean?: ImportBeanExtras | null;
}

/**
 * Coerce a stored `bean` field on a persisted shot into a {@link ShotBean}
 * or `null`. Legacy records (pre-bean-snapshot) carry no `bean`; v1 records
 * carry only `{ roaster, type, roastedOn, roastLevel }`; post-inline-bean
 * records also carry `notes`, `grinderSetting`, `grinder`. Each extra field is read
 * defensively so a stored-shape regression cannot crash the History route.
 *
 * Older records may still carry a persisted `visualizerId` on the snapshot
 * from before the coffee-bag-id live lookup landed. We deliberately DROP that
 * on read — the visualizer id is resolved live via `resolveCoffeeBagId`, and
 * leaving the stale key in memory would tempt callers to read it again and
 * re-introduce the snapshot-vs-live leak we're closing here. The snapshot's
 * `tags`, by contrast, ARE kept — they're the bean's tags frozen at shot time
 * (see {@link ShotBean.tags}), distinct from the mutable `StoredShot.tags`.
 */
function coerceShotBean(raw: unknown): ShotBean | null {
	if (typeof raw !== 'object' || raw === null) return null;
	const obj = raw as Record<string, unknown>;
	// Roaster + type are the bean's identity strings — both required (default
	// to '' so a partial record still reads); roastedOn / roastLevel are
	// nullable per the original v1 contract. Every other field is optional
	// and ShotBean's `readonly` modifier doesn't prevent assembling the
	// object literal in pieces, so build a plain mutable scratch object
	// and return it as `ShotBean`.
	// Accept both the new wire-shape field names (`name`/`roasterName`)
	// and the legacy v2 shape (`type`/`roaster`) so localStorage records
	// written before Task #21 upcast cleanly on first load. The legacy
	// keys are dropped from `out` so re-saved rows are clean.
	const legacyType = typeof obj.type === 'string' ? obj.type : null;
	const legacyRoaster = typeof obj.roaster === 'string' ? obj.roaster : null;
	const name = typeof obj.name === 'string' ? obj.name : (legacyType ?? '');
	const roasterName =
		typeof obj.roasterName === 'string' ? obj.roasterName : legacyRoaster;
	const out: Record<string, unknown> = {
		name,
		roasterName,
		roastedOn: typeof obj.roastedOn === 'string' ? obj.roastedOn : null,
		roastLevel: typeof obj.roastLevel === 'number' ? obj.roastLevel : null
	};
	if (typeof obj.beanId === 'string' || obj.beanId === null) out.beanId = obj.beanId;
	if (Array.isArray(obj.tags)) {
		out.tags = obj.tags.filter((t): t is string => typeof t === 'string');
	}
	if (typeof obj.notes === 'string' && obj.notes.length > 0) out.notes = obj.notes;
	if (typeof obj.grinderSetting === 'string' && obj.grinderSetting.length > 0) {
		out.grinderSetting = obj.grinderSetting;
	}
	if (typeof obj.grinder === 'string' && obj.grinder.length > 0) out.grinder = obj.grinder;
	return out as unknown as ShotBean;
}

/**
 * Coerce a stored `machine` stamp into the core shape, or `null` when it has
 * no serial. Rows persisted before the core type may carry `null`s for the
 * optional fields; {@link toCoreShotMachine} drops them.
 */
function coerceShotMachine(raw: unknown): ShotMachine | null {
	if (typeof raw !== 'object' || raw === null) return null;
	const obj = raw as Record<string, unknown>;
	if (typeof obj.serialNumber !== 'string') return null;
	const str = (v: unknown): string | null => (typeof v === 'string' ? v : null);
	return toCoreShotMachine({
		serialNumber: obj.serialNumber,
		firmwareVersion: str(obj.firmwareVersion),
		model: str(obj.model)
	});
}

/**
 * Read the persisted history and coerce each row through a defensive
 * normaliser so legacy records without `visualizerId` / `deletedAt`
 * still parse cleanly (the fields are optional on the type; absent
 * keys leave the rows in their "never synced, never deleted" default).
 */
function loadShots(): StoredShot[] {
	const read = readStore<unknown[]>(HISTORY_KEY, { what: HISTORY_WHAT, valid: Array.isArray });
	return read.kind === 'loaded' ? rowsOf(read.value) : [];
}

/** Stored rows as shots, newest first; a row that can't be read is kept aside. */
function rowsOf(items: unknown[]): StoredShot[] {
	const out: StoredShot[] = [];
	const skipped: unknown[] = [];
	for (const item of items) {
		const migrated = coerceRow(item);
		if (migrated) out.push(migrated);
		else skipped.push(item);
	}
	keepSkippedRows(HISTORY_KEY, skipped, HISTORY_WHAT);
	// Rows saved before the list kept its order (a pull or a restore prepended).
	return newestFirst(out);
}

/** One stored row as a shot, or null if it can't be read as one. */
function coerceRow(item: unknown): StoredShot | null {
	if (typeof item !== 'object' || item === null) return null;
	const obj = item as Record<string, unknown>;
	return typeof obj.id === 'string' ? coerceStoredShot(obj) : null;
}

/**
 * Upcast a persisted shot row to the v3 wire shape.
 *
 * v3 records pass through untouched (already `{ formatVersion: 3,
 * metadata, record, … }`). v2 records (denormalized, with top-level
 * `dose` / `rating` / `notes` / `series` / `duration` / `peakWeight`
 * / `brewTemp`) are folded into the wire shape:
 *
 *  - `dose` / `rating` / `notes` move under `metadata`.
 *  - `series` (`TelemetrySample[]`) becomes `record.samples`
 *    (`TimedSample[]`) via `toWire` from `./telemetry-wire`.
 *  - `duration` moves under `record.duration`.
 *  - `brewTemp` renames to `brewTempTarget`.
 *  - The pre-derived peaks are dropped — re-derived on demand by
 *    {@link peaksOf}.
 *
 * Returns `null` only when the record is too corrupt to recover.
 */
export function coerceStoredShot(obj: Record<string, unknown>): StoredShot | null {
	const tagsRaw = obj.tags;
	const tags = Array.isArray(tagsRaw)
		? tagsRaw.filter((t): t is string => typeof t === 'string')
		: [];
	const bean = coerceShotBean(obj.bean ?? null);
	const grinderModelRaw = obj.grinderModel;
	const grinderModel =
		typeof grinderModelRaw === 'string' && grinderModelRaw.length > 0
			? grinderModelRaw
			: null;
	const yieldTarget =
		typeof obj.yieldTarget === 'number' && Number.isFinite(obj.yieldTarget)
			? obj.yieldTarget
			: null;
	// v3 wire records carry `brewTempTarget`; v2 carried `brewTemp`.
	const brewTempRaw =
		typeof obj.brewTempTarget === 'number' && Number.isFinite(obj.brewTempTarget)
			? (obj.brewTempTarget as number)
			: typeof obj.brewTemp === 'number' && Number.isFinite(obj.brewTemp)
				? (obj.brewTemp as number)
				: null;
	const preinfuseTarget =
		typeof obj.preinfuseTarget === 'number' && Number.isFinite(obj.preinfuseTarget)
			? obj.preinfuseTarget
			: null;
	const stopOnWeight = typeof obj.stopOnWeight === 'boolean' ? obj.stopOnWeight : undefined;
	const autoTare = typeof obj.autoTare === 'boolean' ? obj.autoTare : undefined;
	// The per-shot privacy override and the Decent upload state (#84). Dropping
	// them here widened a private shot's next Visualizer PATCH to the settings
	// default, re-queued Decent shots for upload, and re-stamped old shots
	// with whichever DE1 was connected at upload time.
	const privacy =
		obj.privacy === 'public' || obj.privacy === 'unlisted' || obj.privacy === 'private'
			? obj.privacy
			: null;
	const decentId = typeof obj.decentId === 'string' && obj.decentId ? obj.decentId : null;
	const visualizerUpdatedAt =
		typeof obj.visualizerUpdatedAt === 'number' && Number.isFinite(obj.visualizerUpdatedAt)
			? obj.visualizerUpdatedAt
			: null;
	const pinned = obj.pinned === true;
	const machine = coerceShotMachine(obj.machine);
	const visualizerId = typeof obj.visualizerId === 'string' ? obj.visualizerId : null;
	const deletedAt = typeof obj.deletedAt === 'number' ? obj.deletedAt : null;
	// Brew Log fields (issue #10) — all additive; absent on machine shots.
	// Presence-based like the core's `is_brew_log`: a stored `""` stays a
	// brew row (local-only), it is not silently upcast to a machine shot.
	const brewMethod = typeof obj.brewMethod === 'string' ? obj.brewMethod : null;
	const bagDebit =
		typeof obj.bagDebit === 'number' && Number.isFinite(obj.bagDebit) && obj.bagDebit >= 0
			? obj.bagDebit
			: null;
	const brewMethodLabel =
		typeof obj.brewMethodLabel === 'string' && obj.brewMethodLabel.trim().length > 0
			? obj.brewMethodLabel
			: null;
	const recipeName =
		typeof obj.recipeName === 'string' && obj.recipeName.trim().length > 0
			? obj.recipeName
			: null;
	const brewSeriesRaw = obj.brewSeries;
	const brewSeries =
		typeof brewSeriesRaw === 'object' &&
		brewSeriesRaw !== null &&
		Array.isArray((brewSeriesRaw as { samples?: unknown }).samples)
			? (brewSeriesRaw as StoredShot['brewSeries'])
			: null;

	// v3 wire records carry `metadata` + `record`. v2 records have
	// top-level `dose` / `rating` / `notes` + `series` / `duration`.
	const isV3 =
		typeof obj.formatVersion === 'number' &&
		obj.formatVersion >= 3 &&
		typeof obj.metadata === 'object' &&
		obj.metadata !== null &&
		typeof obj.record === 'object' &&
		obj.record !== null;

	let metadata: ShotMetadata;
	let recordField: StoredShot['record'];
	if (isV3) {
		metadata = (obj.metadata ?? {}) as ShotMetadata;
		recordField = (obj.record ?? { duration: 0, samples: [] }) as StoredShot['record'];
	} else {
		// v2 migration path
		const legacySeries = Array.isArray(obj.series) ? (obj.series as TelemetrySample[]) : [];
		const samples = legacySeries.map(toWire);
		const duration =
			typeof obj.duration === 'number' && Number.isFinite(obj.duration) ? obj.duration : 0;
		recordField = { duration, samples };
		metadata = {
			dose:
				typeof obj.dose === 'number' && Number.isFinite(obj.dose) ? (obj.dose as number) : null,
			rating:
				typeof obj.rating === 'number' && Number.isFinite(obj.rating)
					? (obj.rating as number)
					: null,
			notes: typeof obj.notes === 'string' ? (obj.notes as string) : null
		};
	}

	return {
		formatVersion: STORED_SHOT_FORMAT_VERSION,
		id: obj.id as string,
		// A zero or missing date stays what it is, at the tail as on Android:
		// stamping it now would move the row to the head on every load, where it
		// would seed the next brew as the last one.
		completedAt:
			typeof obj.completedAt === 'number' && Number.isFinite(obj.completedAt)
				? (obj.completedAt as number)
				: 0,
		profileName: typeof obj.profileName === 'string' ? (obj.profileName as string) : null,
		profile: (obj.profile as unknown) ?? null,
		stopReason: (obj.stopReason as unknown) ?? null,
		...(obj.stopEstimated === true ? { stopEstimated: true } : {}),
		metadata,
		record: recordField,
		bean,
		grinderModel,
		tags,
		yieldTarget,
		brewTempTarget: brewTempRaw,
		preinfuseTarget,
		stopOnWeight,
		autoTare,
		...(privacy ? { privacy } : {}),
		visualizerId,
		...(decentId ? { decentId } : {}),
		...(visualizerUpdatedAt != null ? { visualizerUpdatedAt } : {}),
		...(pinned ? { pinned } : {}),
		...(machine ? { machine } : {}),
		deletedAt,
		...(brewMethod != null ? { brewMethod } : {}),
		...(brewMethodLabel ? { brewMethodLabel } : {}),
		...(bagDebit !== null ? { bagDebit } : {}),
		...(recipeName ? { recipeName } : {}),
		...(brewSeries ? { brewSeries } : {})
	};
}

/** The process-wide singleton — one history shared by every route. */
let store: HistoryStore | undefined;

/** Get the shared {@link HistoryStore}, creating it on first call. */
export function getHistoryStore(): HistoryStore {
	if (!store) {
		clearLight();
		store = new HistoryStore();
	}
	return store;
}
