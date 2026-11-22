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
 * **No cap (#310 P6w, SPEC §8).** On `crema-history` every row is kept and
 * shown: nothing is evicted, refused for its date, or hidden, and only an
 * explicit delete removes a row. The real limit is disk, which the storage
 * notices watch (`storage-watch.ts`). The legacy backend keeps its 300-row
 * cap, as `localStorage` can't hold more.
 *
 * **Rows, not shots (P4w, SPEC §4).** What the store hands out is a
 * {@link ShotRow}: the fields and a summary (counts, peaks, the row's spark),
 * with no samples, profile or brew series on the type at all, so nothing can
 * read a row as a shot without telemetry. {@link loadShot} gives a whole shot
 * to whatever draws, sends or writes it, and keeps the last 16. In memory a
 * row holds its heavy parts only until they are written (`light-rows.ts`);
 * nothing loads them for the list. A row handed back (a restore's plan, rows
 * from another tab) is merged onto the held one, so a heavy part it lacks is
 * never lost; on the legacy backend, which holds every shot whole, that is
 * its merge on write.
 *
 * **The curve archive (#420 S1).** Each curve is also written once to its own
 * file in the private file system (`curve-archive.ts`) when it first lands
 * in the store, so a lost store gives curves back: every recovery fills the
 * rows the recovered store lacks a curve for from it.
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
	keepRemoteOutMany,
	markUnboundTombstone,
	settleUploadMarks,
	uploadAttempted
} from '#lib/visualizer/upload-marks.js';
import { excludedFromUpload, readSyncConfig } from '#lib/visualizer/sync-config.js';
import { pendingEdits } from '#lib/visualizer/pending-edits.js';
import { readDecentAccount } from '#lib/decent/account.js';
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
	isShotRow,
	refusedNoSamples,
	sampleCountOf,
	shotId,
	snapshotFromBean,
	toCoreShotMachine,
	type AnyShot,
	type ShotBean,
	type ShotMachine,
	type ShotMetadata,
	type ShotRow,
	type StoredShot
} from './model';
import type { Bean, Roaster } from '#lib/bean/index.js';
import { getCaptureStore } from '#lib/capture/store.js';
import { readCaptureKeep } from '#lib/capture/limit.js';
import {
	HistoryDb,
	HistoryDbLost,
	META_MIGRATION,
	detailsOf,
	keepHeavy,
	rowOf as rowOfShot,
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
import { clearLight, heldSampleCount, lightSummary, markLight, markLoaded } from './light-rows';
import { HistoryDbTooNew, SqliteRows, sqliteJournal, type RowJournal, type SqliteClient } from './sqlite-rows';
import type { DetailsStore } from '#lib/history-source/details.js';
import type { JournalStorage } from '#lib/history-db/pending.js';
import { historyStage, stagedBootActive } from '#lib/history-db/start.js';
import { dbFileName, readMarker as readSqliteMarker } from '#lib/history-db/marker.js';
import { MARKER_KEY } from '#lib/history-db/names.js';
import {
	STORE_MARKER_KEY,
	markerSaysDone,
	migrationDue,
	readStoreMarker,
	writeDoneMarker,
	writeFailedMarker
} from './store-marker';
import { addPending, dropPending, readPending } from './pending';
import {
	SNAPSHOT_CHUNK,
	dayOf,
	listRowsSnapshots,
	readAllRowsSnapshots,
	readRowsSnapshot,
	snapshotLines,
	snapshotWritten,
	writeRowsSnapshot,
	type SnapshotInfo
} from './rows-snapshot';
import { readProfiles, workerProfileReader, type WorkerLike } from './snapshot-profiles';
import { summarize } from './summary';
import { CurveArchive, defaultArchiveRoot, holdsCurve, type ArchiveRoot } from './curve-archive';
import type { LockManagerLike } from '#lib/restore/tab-safety.js';
import { P3_ROWS, type P3Rows } from './p3-rows';

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
 * The legacy backend's cap: the newest this many live rows are kept, as
 * before. `crema-history` has none (#310 P6w).
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

/**
 * How a recovery from `lost` ended: true when it filled the store, `reopened`
 * when another tab had already recovered it and this tab loaded that, false
 * when it didn't work (`storageError` says why).
 */
export type RecoveryResult = boolean | 'reopened';

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
	/** Starts the worker the daily copy reads profiles in; null reads them here. A worker by default, with the browser's own database. */
	snapshotWorker?: (() => WorkerLike | null) | null;
	/** Where the curve archive lives (the private file system by default); null keeps none. */
	archiveRoot?: ArchiveRoot | null;
	/** How long after a load the archive's catch-up starts. */
	archiveDelayMs?: number;
	now?: () => number;
	readyTimeoutMs?: number;
	/** Listen for the tab coming back into view. On by default. */
	visibility?: boolean;
	/**
	 * Stage 2 (#310 W5): resolves once this tab's boot has chosen its stage,
	 * to `history.db`'s client and the details store when it is stage 2, or
	 * null on stage 1 (the P3 store, `crema-history`). The store waits for it
	 * before it opens anything: while a flip runs, a capture waits in P3's
	 * journal (M9), and every other writer waits for `ready`.
	 */
	stage2?: () => Promise<Stage2 | null>;
}

/** What the store runs on after the flip (SPEC §11 stage 2). */
export interface Stage2 {
	client: SqliteClient;
	/** `crema-history`'s `details`, written without touching `rows` or `meta`. */
	details: DetailsStore;
	/** `localStorage`, for stage 2's pending journal (M5). */
	storage: JournalStorage;
	/**
	 * After a recovery into `history-<n>.db` (S9): writes the marker for it,
	 * and gives the client of that file (W5c). Without it, recovery isn't offered.
	 */
	restage?(generation: number): Promise<Stage2>;
	/** The file this client serves (`history.db`, `history-<n>.db`): a marker naming another is followed (W5c). */
	file?: string;
}

/** P3's journal, `crema.history.pending`. */
const p3Journal: RowJournal = {
	add: (shot) => addPending(shot),
	drop: (ids) => dropPending(ids)
};

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

/** The worker that reads the daily copy's profiles off the main thread (#421). */
function defaultSnapshotWorker(): WorkerLike | null {
	if (typeof Worker === 'undefined') return null;
	return new Worker(new URL('./snapshot-profiles.worker.ts', import.meta.url), {
		type: 'module',
		name: 'crema-snapshot-profiles'
	}) as unknown as WorkerLike;
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

/** Whether `shot` holds any heavy part in memory. */
function hasHeavyParts(shot: StoredShot): boolean {
	return shot.record.samples.length > 0 || shot.profile != null || (shot.brewSeries?.samples.length ?? 0) > 0;
}

/** Whether memory holds fewer heavy parts than `d`. */
function storeHeldMore(shot: StoredShot, d: DetailsRecord): boolean {
	return (
		(shot.record.samples.length === 0 && d.samples.length > 0) ||
		(shot.profile == null && d.profile != null) ||
		((shot.brewSeries?.samples.length ?? 0) === 0 && (d.brewSeries?.samples.length ?? 0) > 0)
	);
}

/** The archive's tombstones: ids a delete recorded whose file is still to go. */
const ARCHIVE_TOMBSTONE_PREFIX = 'crema.history.archiveTombstone:';

/**
 * The archive's tombstones, one `localStorage` key each, so two tabs never
 * write one list over the other's (#420 FC3-N3).
 */
function readArchiveTombstones(): string[] {
	const out: string[] = [];
	try {
		for (let i = 0; i < localStorage.length; i++) {
			const k = localStorage.key(i);
			if (k?.startsWith(ARCHIVE_TOMBSTONE_PREFIX)) out.push(k.slice(ARCHIVE_TOMBSTONE_PREFIX.length));
		}
	} catch {
		// None readable: kept in memory for this load only.
	}
	return out;
}

function setArchiveTombstone(id: string, on: boolean): void {
	try {
		if (on) localStorage.setItem(ARCHIVE_TOMBSTONE_PREFIX + id, '1');
		else localStorage.removeItem(ARCHIVE_TOMBSTONE_PREFIX + id);
	} catch {
		// Kept in memory for this load; a file left behind costs space, never a curve.
	}
}

/** Where the day's whole check of the archive got to, so the next load goes on from there (#420 FC3-S1). */
const ARCHIVE_CHECK_KEY = 'crema.history.archiveCheck';
interface ArchiveCheck {
	/** The day the pass began. */
	on: string;
	/** The last id checked, in id order; '' before the first. */
	after: string;
}

function readArchiveCheck(): ArchiveCheck | null {
	try {
		const v = JSON.parse(localStorage.getItem(ARCHIVE_CHECK_KEY) ?? 'null') as ArchiveCheck | null;
		return v && typeof v.on === 'string' && typeof v.after === 'string' ? v : null;
	} catch {
		return null;
	}
}

function writeArchiveCheck(v: ArchiveCheck | null): void {
	try {
		if (v) localStorage.setItem(ARCHIVE_CHECK_KEY, JSON.stringify(v));
		else localStorage.removeItem(ARCHIVE_CHECK_KEY);
	} catch {
		// Begun again at the next load.
	}
}

/** The day the archive was last checked whole against the store. */
const ARCHIVE_CHECKED_KEY = 'crema.history.archiveCheckedOn';

/** Most whole shots {@link HistoryStore.loadShot} keeps, the last loaded (SPEC §4). */
export const LOADED_SHOTS_KEPT = 16;

/** Summaries worked out from memory, by the record and brew series they describe. */
const memorySummaries = new WeakMap<object, { brew: unknown; summary: ShotSummary }>();

/** `shot`'s summary from what memory holds of it: one core call per curve, kept. */
function summaryFromMemory(shot: StoredShot): ShotSummary {
	const got = memorySummaries.get(shot.record);
	if (got && got.brew === (shot.brewSeries ?? null)) return got.summary;
	const summary = summarize(shot);
	memorySummaries.set(shot.record, { brew: shot.brewSeries ?? null, summary });
	return summary;
}

/**
 * `shot` as a {@link ShotRow}: its fields and `summary`, without the heavy
 * parts, which aren't on the type at all.
 */
function asRow(shot: StoredShot, summary: ShotSummary): ShotRow {
	const { brewSeries: _b, profile: _p, record, ...rest } = shot;
	return { ...rest, record: { duration: record.duration }, summary };
}

/**
 * A row or whole shot handed back to the store, merged onto the one it holds
 * (SPEC §6, "merges on write"): its fields win; a heavy part it lacks is the
 * held one's, so an edit made through a {@link ShotRow} never drops a
 * profile, a brew series or samples. A heavy part it carries wins, as a
 * details write's does (`keepHeavy`).
 */
function mergeOnto(incoming: AnyShot, held: StoredShot | undefined): StoredShot {
	const { summary: _s, ...fields } = incoming as AnyShot & { summary?: unknown };
	const whole = !isShotRow(incoming) ? (incoming as StoredShot) : null;
	const samples =
		whole && whole.record.samples.length > 0 ? whole.record.samples : (held?.record.samples ?? []);
	const profile = whole?.profile ?? held?.profile ?? null;
	const brewSeries =
		whole?.brewSeries && whole.brewSeries.samples.length > 0 ? whole.brewSeries : (held?.brewSeries ?? whole?.brewSeries ?? null);
	const { brewSeries: _b, ...rest } = fields as StoredShot;
	return {
		...rest,
		profile,
		record: { duration: incoming.record.duration, samples },
		...(brewSeries ? { brewSeries } : {})
	};
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
	/** Another tab moved the rows while this one ran on the legacy backend: reload. */
	private movedAway = $state(false);
	/** The `done` marker couldn't be written (storage full): retried after each save. */
	private markerOwed: { at: number; rows: number } | null = null;
	/** Late rows' migration record, waiting until those rows are stored. */
	private lateOwed: { record: MigrationRecord; ids: string[] } | null = null;
	/** A reopen after another tab's recovery, while it runs. */
	private reopenRun: Promise<boolean> | null = null;
	/** Shots made before the boot decided, whose rows wait in the journal. */
	private bootJournal = new Set<string>();
	/**
	 * Shots this tab held when its store went missing under it: memory is now
	 * their only copy, newer than the old copy or a snapshot. A recovery writes
	 * them into the store it fills (F1).
	 */
	private orphaned = new Set<string>();

	/** Which backend the rows go to. `booting` until the boot decides. */
	private mode: 'booting' | 'idb' | 'legacy' | 'blocked' = 'booting';
	private readonly opts: HistoryStoreOptions;
	private db: HistoryDb | null = null;
	/** Stage 2: `history.db` behind `db` (#310 W5), or null on the P3 store. */
	private sqlite: SqliteRows | null = null;
	private stage2: Stage2 | null = null;
	/** Where a row the store can't take waits: P3's journal, or stage 2's. */
	private journal: RowJournal = p3Journal;
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
	/** Heard rows being read to be taken in (`applyHeard`). */
	private adopting = 0;
	private readonly tabId = Math.random().toString(36).slice(2);
	/** Ids whose next details write only fills: a heavy part the store holds wins (a pulled curve or recipe). */
	private fills = new Set<string>();
	/**
	 * Ids whose heavy parts are being written now: memory's copy is the one
	 * going out, so nothing may swap it for the store's until it lands (#420 N1).
	 */
	private writingDetails = new Set<string>();
	/** Rows pulled from Visualizer not stored yet: on stage 2 the core skips one another row holds (N8). */
	private pulled = new Set<string>();
	/** Ids removed from memory whose delete hasn't landed: another tab's news mustn't bring them back. */
	private removing = new Set<string>();
	private snapshotting = false;
	/** The day this tab last tried the snapshot. */
	private snapshotTried: string | null = null;

	// ── The curve archive (#420 S1) ──
	private readonly archive: CurveArchive | null;
	/** Curves landed in the store and not archived yet: their details when in hand, else read. */
	private archiveOwed = new Map<string, DetailsRecord | null>();
	private archiveRun: Promise<void> | null = null;
	/** Ids deleted this session: an archive write racing the delete removes its file again. */
	private archiveDeleted = new Set<string>();
	private archiveCatchUp: ReturnType<typeof setTimeout> | null = null;
	/** Archive files that didn't read back whole, by id: skipped, never used. */
	private archiveCorrupt = new Map<string, string>();
	/**
	 * The archive's tombstones: shots a delete recorded, whose file is still
	 * to go. Kept in `localStorage`, so a remove that failed, or raced a
	 * write, is tried again in a later load. Only a recorded delete removes a
	 * file: nothing infers one from a shot being absent (#420 FC2-S1).
	 */
	private archiveRemoveOwed = new Set<string>(readArchiveTombstones());
	/** The archive as last counted, for Settings and the lost banner. */
	private archiveCount = $state<{ files: number; bytes: number } | null | undefined>(undefined);
	/** An archive write failed and waits for another try (storage full, most likely). */
	private archiveStuck = $state(false);
	/** Moves when a file is found corrupt, so the counts that read them re-read. */
	private corruptTick = $state(0);
	/** What the last recovery did with the archive: curves restored, and not (#420 FC-N5). */
	private lastRecoveryState = $state<{ restored: number; notRestored: number } | null>(null);
	/** This load's catch-up has run: every curve the store held then has been tried. */
	private archiveCaughtUpFlag = $state(false);
	private viewMemo: { rows: StoredShot[]; view: StoredShot[]; live: StoredShot[] } | null = null;
	private byIdMemo: { rows: StoredShot[]; map: Map<string, StoredShot> } | null = null;
	/** Moves when a row's summary changes without its object changing, so readers re-read. */
	private summaryTick = $state(0);
	/** Each held row's {@link ShotRow}, while its summary stays the same. */
	private rowCache = new WeakMap<StoredShot, ShotRow>();
	private rowsMemo: {
		rows: StoredShot[];
		tick: number;
		raw: ShotRow[];
		all: ShotRow[];
		byId: Map<string, ShotRow>;
	} | null = null;
	/** The last {@link LOADED_SHOTS_KEPT} light rows' heavy parts, as stored, with the summary they match. */
	private loadedShots = new Map<string, { summary: ShotSummary; details: DetailsRecord }>();

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
		this.archive =
			this.opts.backend === 'legacy' || this.opts.archiveRoot === null
				? null
				: new CurveArchive(this.opts.archiveRoot ?? defaultArchiveRoot);
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
		const run = this.opts.stage2 ? this.bootStaged(this.opts.stage2) : this.boot();
		void run.catch((e) => {
			if (e instanceof TestKill) return;
			this.block('unavailable', errorText(e));
		});
	}

	/** Waits for the stage this tab boots on (#310 W5), then opens its store. */
	private async bootStaged(stage2: () => Promise<Stage2 | null>): Promise<void> {
		const s2 = await stage2();
		if (!s2) return this.boot();
		return this.bootSqlite(s2);
	}

	/**
	 * Stage 2 (#310 W5, SPEC §11): the rows in `history.db`, through `SqliteRows`
	 * behind `db`, so every write path is the one P3 tested. Before the rows
	 * load, what P3's own journal holds and this tab doesn't is imported by the
	 * id rule (step 8, S13): never an id `history.db` has or had. P3's journal
	 * is read only. Other tabs' writes arrive as `history.db`'s change notices.
	 */
	private async bootSqlite(s2: Stage2): Promise<void> {
		this.stage2 = s2;
		const db = new SqliteRows(s2.client, s2.details);
		this.sqlite = db;
		this.db = db as unknown as HistoryDb;
		this.journal = sqliteJournal(s2.storage);
		this.listenToTabs();
		const slow = setTimeout(() => {
			if (!this.loadedFlag) this.slowFlag = true;
		}, SLOW_LOAD_MS);
		try {
			if (!(await this.openSqlite(db))) return;
			await this.loadFromDb();
			this.recordHeldJournal(db);
		} finally {
			clearTimeout(slow);
		}
	}

	/**
	 * P3's journal entries this tab held in memory went in whole, with their
	 * curves, through the first write. Once that has landed, and before any
	 * later write such as a delete, they go through the id rule too, which
	 * finds them present and records them in `migration_source`: then the
	 * entry, still in P3's key, never brings back a shot deleted after
	 * (REVIEW-474 N2).
	 */
	private recordHeldJournal(db: SqliteRows): void {
		const rows = this.heldJournal;
		this.heldJournal = [];
		if (rows.length === 0) return;
		this.writeChain = this.writeChain.then(async () => {
			try {
				await db.importLate(rows);
			} catch {
				// Next boot: the entry is still in P3's key, and the row is in history.db.
			}
		});
	}
	/** P3 journal rows this tab held in memory at the stage-2 open (N2). */
	private heldJournal: RowRecord[] = [];

	/** Opens `history.db` and takes P3's journal in; false when blocked (lost, too new, unavailable). */
	private async openSqlite(db: SqliteRows): Promise<boolean> {
		try {
			await db.open();
		} catch (e) {
			if (e instanceof HistoryDbLost) {
				this.block('lost', e.message);
			} else {
				this.block('unavailable', e instanceof HistoryDbTooNew ? e.message : errorText(e));
			}
			return false;
		}
		// P3's journal, as late rows: shots this tab holds in memory are written
		// whole below, with their curves, rather than as a journal's light row.
		const mine = new Set(this.shots.map((s) => s.id));
		const late: RowRecord[] = [];
		this.heldJournal = [];
		for (const raw of readPending()) {
			const shot = typeof raw.id === 'string' ? coerceStoredShot(raw) : null;
			if (!shot) continue;
			if (mine.has(shot.id)) this.heldJournal.push(rowOfShot(shot, summarize(shot)));
			else late.push(rowOfShot(shot, summarize(shot)));
		}
		if (late.length > 0) {
			try {
				await db.importLate(late);
			} catch {
				// Next boot: the journal stays as it is.
			}
		}
		return true;
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

	/**
	 * Where this tab's rows stand against `crema-history`, for the stage-1
	 * index's dev oracle (#310 W2): the highest `writeSeq` it has written or
	 * heard of, and whether its rows are exactly what the store holds up to
	 * it (nothing unwritten here, nothing heard and not taken in yet). Only
	 * then is an index answer comparable with these rows.
	 */
	get inStep(): { seq: number; quiet: boolean } {
		const quiet =
			this.mode === 'idb' &&
			this.loadedFlag &&
			!this.flushQueued &&
			this.heard.size === 0 &&
			this.adopting === 0 &&
			this.removing.size === 0 &&
			this.dirtyDetails.size === 0 &&
			this.writingDetails.size === 0 &&
			this.dirtyRowIds().length === 0;
		return { seq: this.lastSeq, quiet };
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

	/** The rows of {@link unsavedShots}, for Download: readable while the store is lost, too. */
	unsavedRows(): StoredShot[] {
		const byId = this.byId();
		return this.unsavedIds.map((id) => byId.get(id)).filter((s): s is StoredShot => s !== undefined);
	}

	/** Whether closing the tab now would lose something. */
	get hasUnsaved(): boolean {
		if (this.unsavedIds.length > 0) return true;
		if (this.mode === 'idb') return this.dirtyRowIds().length > 0 || this.dirtyDetails.size > 0;
		// Made before the rows loaded: only the row is in the journal, the curve
		// is in memory (S1). Blocked, every new shot is in `unsavedIds`.
		return this.mode === 'booting' && this.bootJournal.size > 0;
	}

	/** Another tab moved the history while this tab ran on the old copy: it must reload. */
	get movedElsewhere(): boolean {
		return this.movedAway;
	}

	/** The marker can't be read: "lost" may mean a device that never moved. */
	get markerUnreadable(): boolean {
		return readStoreMarker().kind === 'unreadable';
	}

	/** On `crema-history` every row is kept. On the legacy backend the oldest past its cap go. */
	get keepsEveryRow(): boolean {
		return this.mode === 'idb';
	}

	/**
	 * How the rows loaded, in a store-health word: what restore and capture
	 * prune check before trusting "not listed" to mean "not here".
	 */
	rowsHealth(): StoreHealth {
		// The old copy this tab holds is no longer the history.
		if (this.movedAway) return 'unavailable';
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

	/** The store went missing under this tab (S6): the lost banner, never a silent re-create. */
	private async lostNow(e: unknown): Promise<void> {
		if (this.healthState === 'lost') return;
		await this.readOldCopyInfo();
		if ((this.healthState as HistoryHealth) === 'lost') return;
		// The heavy parts this tab loaded or wrote last are, with the store
		// gone, their only copy here: memory holds them again, so a recovery
		// writes them (P4w keeps no others in memory; a daily copy has none).
		this.rescueLoaded();
		// What this tab holds is in no store now: closing the tab would lose it.
		const held = this.shots.map((s) => s.id);
		this.block('lost', errorText(e));
		for (const id of held) this.orphaned.add(id);
		this.markUnsaved(held);
	}

	/** Light rows whose heavy parts {@link loadedShots} still has: whole in memory again. */
	private rescueLoaded(): void {
		if (this.loadedShots.size === 0) return;
		let changed = false;
		const next = this.shots.map((s) => {
			const kept = this.loadedShots.get(s.id);
			if (!kept || !lightSummary(s.id)) return s;
			// Memory's own parts win: a pulled curve or recipe not written yet
			// stays (#420 S2). A kept copy older than the row's summary (another
			// tab replaced the curve since) is still this shot's curve, and with
			// the store gone it is the only one here.
			const whole = withDetails(s, keepHeavy(detailsOf(s), kept.details));
			this.unlight(s.id);
			this.rebase(s, whole);
			changed = true;
			return whole;
		});
		if (changed) this.shots = next;
	}

	/**
	 * Before loading a store that replaced a lost one: what this tab knew of
	 * the lost store no longer holds, and the shots it held there are written
	 * over the recovered rows. Their ids, for {@link loadFromDb}.
	 */
	private forgetLostStore(): Set<string> {
		const mine = new Set(this.orphaned);
		this.orphaned.clear();
		this.loadedShots.clear();
		this.stored.clear();
		this.summaries.clear();
		this.written.clear();
		return mine;
	}

	/** Write the `done` marker, or keep owing it and say so (S5). */
	private writeMarker(at: number, rows: number): void {
		if (writeDoneMarker(at, rows)) {
			this.markerOwed = null;
			return;
		}
		const first = this.markerOwed === null;
		this.markerOwed = { at, rows };
		this.lastError = 'The history’s marker couldn’t be saved: browser storage is full.';
		if (first) {
			toast.error('Browser storage is full, so Crema couldn’t note where your shot history is kept. Free up space or export a backup.');
		}
	}

	private block(health: 'unavailable' | 'lost', error: string): void {
		this.mode = 'blocked';
		this.healthState = health;
		// What memory holds can no longer be trusted as the store's.
		this.loadedFlag = false;
		this.lastError = error;
		this.slowFlag = false;
		// New shots made meanwhile go to the journal, so a reload keeps them.
		this.journalNew();
		// Those journalled while the boot ran have only their row there too.
		this.markUnsaved([...this.bootJournal]);
		this.bootJournal.clear();
	}

	/** No IndexedDB, or a test: the legacy backend, loaded at once as before. */
	private enterLegacySync(): void {
		this.mode = 'legacy';
		this.shots = loadShots();
		this.markLive(this.shots.map((s) => s.id));
		followOtherTabs(HISTORY_KEY, Array.isArray, (v) => this.takeOtherTabsRows(v as unknown[]));
		this.followMarker();
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

	/**
	 * On the legacy backend, while a move may still happen: once another tab
	 * marks the move done, this tab stops writing the old copy, which is now
	 * the copy as it was at the move (S4). New shots go to the journal.
	 */
	private followMarker(): void {
		if (this.opts.backend === 'legacy' || typeof window === 'undefined') return;
		window.addEventListener('storage', (e) => {
			if (e.key !== STORE_MARKER_KEY || this.mode !== 'legacy' || !markerSaysDone()) return;
			// What memory holds is in the old copy, which the move took: only
			// shots it doesn't hold go to the journal, for the moved store to merge.
			this.mode = 'blocked';
			this.movedAway = true;
		});
	}

	/** After a failed move: the old copy, with what this tab made meanwhile. */
	private enterLegacy(error: string | null): void {
		const made = this.shots;
		this.bootJournal.clear();
		const madeDetails = new Set(this.dirtyDetails);
		this.mode = 'legacy';
		this.dirtyDetails.clear();
		const loadedRows = loadShots();
		const ids = new Set(loadedRows.map((s) => s.id));
		this.shots = newestFirst([...made.filter((s) => !ids.has(s.id)), ...loadedRows]);
		this.markLive(loadedRows.map((s) => s.id));
		followOtherTabs(HISTORY_KEY, Array.isArray, (v) => this.takeOtherTabsRows(v as unknown[]));
		this.followMarker();
		this.markLoadedNow('legacy');
		if (error) {
			this.lastError = error;
			toast.info('Shot history couldn’t move to its new storage, so it stays where it was for now. It will try again later.', 8000);
		}
		if (made.length > 0) {
			// In the old copy now: their journal rows can go.
			if (this.persistLegacy()) dropPending(made.map((s) => s.id));
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
				this.writeMarker(record.doneAt ?? this.now(), record.sourceIds.length);
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
			this.writeMarker(this.now(), result.rows);
			return { kind: 'ok' };
		}
		writeFailedMarker(result.error, this.now());
		return { kind: 'legacy', error: result.error };
	}

	/** The rows from `crema-history`, with what arrived meanwhile; then the view's details. */
	/**
	 * After a loss (`afterLoss`): `mine` are shots this tab held that are
	 * written over the store's rows (its own recovery); `gone` are ids memory
	 * holds that the store's recovery source held, which the store lacking
	 * them means were deleted since, so they're dropped, never written again.
	 */
	private async loadFromDb({
		mine = new Set<string>(),
		gone = new Set<string>(),
		afterLoss = false
	}: { mine?: ReadonlySet<string>; gone?: ReadonlySet<string>; afterLoss?: boolean } = {}): Promise<void> {
		const db = this.db!;
		let records: RowRecord[];
		try {
			records = await db.allRows().catch(() => db.allRows());
			// Test seam: the load held between reading the rows and taking them.
			await this.opts.migrationFault?.('loaded-rows');
		} catch (e) {
			if (e instanceof TestKill) throw e;
			if (e instanceof HistoryDbLost) await this.lostNow(e);
			else this.block('unavailable', errorText(e));
			return;
		}
		const loadedRows: StoredShot[] = [];
		for (const rec of records) {
			const { shot, summary } = shotOfRow(rec);
			this.setSummary(shot.id, summary);
			this.stored.add(shot.id);
			this.light(shot.id, summary);
			loadedRows.push(shot);
		}
		this.lastSeq = await db.writeSeq().catch(() => 0);
		// 8 and 9: what an old tab put in the old copy since.
		const late = await this.lateRows(loadedRows).catch(() => null);
		// After the last await: rows made here during the load stay, and lead.
		// They're written next, journalled or not.
		// And rows held here when a lost store went (`mine`), over what a
		// recovery put in. Never an id the recovery had and has since deleted.
		const made = this.shots.filter((s) => (!this.stored.has(s.id) && !gone.has(s.id)) || mine.has(s.id));
		const madeIds = new Set(made.map((s) => s.id));
		if (afterLoss) {
			// What isn't written from here now has the store's copy, or was deleted.
			for (const id of [...this.dirtyDetails]) if (!madeIds.has(id)) this.dirtyDetails.delete(id);
		}
		// Their journal rows go once they're written (`afterWrite`).
		this.bootJournal.clear();
		for (const s of made) {
			this.written.delete(s.id);
			const old = lightSummary(s.id);
			if (hasHeavyParts(s) || !old) {
				// Whole here: memory's heavy parts are the ones written.
				this.dirtyDetails.add(s.id);
				if (old && hasHeavyParts(s)) this.unlight(s.id);
				continue;
			}
			// Light: a row this tab held when its store went, with no heavy part
			// in memory. A details write would work its summary out from no curve
			// and wipe its yield, peaks and chart (#420 M1), so it goes as a row
			// only: with the recovered store's summary when that store holds it,
			// else with the one memory holds, its counts 0 (no curve is stored),
			// so a backfill or a merge restore can still fill it.
			if (this.stored.has(s.id)) continue;
			const curveless = { ...old, sampleCount: 0, brewSampleCount: 0 };
			this.setSummary(s.id, curveless);
			this.light(s.id, curveless);
		}
		const rows = loadedRows.filter((s) => !madeIds.has(s.id));
		for (const s of rows) this.written.set(s.id, s);
		const held = new Set([...rows.map((s) => s.id), ...madeIds]);
		// The journal: rows a write couldn't take before.
		const journal: StoredShot[] = [];
		const journalHeld: string[] = [];
		// Stage 2 took P3's journal in by the id rule before the load, and never writes it.
		for (const raw of this.sqlite ? [] : readPending()) {
			const shot = typeof raw.id === 'string' ? coerceStoredShot(raw) : null;
			if (!shot) continue;
			if (held.has(shot.id)) {
				if (this.stored.has(shot.id)) journalHeld.push(shot.id);
				continue;
			}
			journal.push(shot);
			held.add(shot.id);
		}
		if (!this.sqlite) dropPending(journalHeld);
		const lateAdd = (late?.add ?? []).filter((s) => !held.has(s.id));
		for (const s of [...journal, ...lateAdd]) this.dirtyDetails.add(s.id);
		const fills = new Map((late?.fill ?? []).map((s) => [s.id, s]));
		const merged = [...made, ...journal, ...lateAdd, ...rows].map((s) => {
			const f = fills.get(s.id);
			if (!f) return s;
			this.dirtyDetails.add(s.id);
			this.unlight(s.id);
			return f;
		});
		this.shots = [...merged].sort(byDateThenId);
		if (afterLoss) {
			const pending = new Set(merged.filter((s) => this.dirtyDetails.has(s.id)).map((s) => s.id));
			this.clearUnsaved(this.unsavedIds.filter((id) => !pending.has(id)));
		}
		this.mode = 'idb';
		this.markLoadedNow('ok');
		this.scheduleArchiveCatchUp();
		this.settleView();
		this.scheduleFlush();
		if (late?.changed) {
			// Only once the late rows are in: a record naming a row the store
			// doesn't hold would keep it out for good. Owed until then.
			this.lateOwed = { record: late.record, ids: [...lateAdd, ...fills.values()].map((s) => s.id) };
			this.writeChain = this.writeChain.then(() => this.settleLateRecord());
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

	/** The cap rows are held to: the legacy backend's, or none (#310 P6w). */
	private get cap(): number | null {
		return this.mode === 'legacy' ? MAX_RECORDS : null;
	}

	private viewOf(): { view: StoredShot[]; live: StoredShot[] } {
		const rows = this.shots;
		if (this.viewMemo?.rows === rows) return this.viewMemo;
		// The legacy backend's rows are capped as they're added; every row shows.
		const view = rows;
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

	// ── Rows as handed out (P4w) ───────────────────────────────────────────

	/** `id` is light now, described by `summary`. */
	private light(id: string, summary: ShotSummary): void {
		if (lightSummary(id) === summary) return;
		markLight(id, summary);
		this.summaryTick++;
	}

	/** `id`'s heavy parts are in memory again, or it has gone. */
	private unlight(id: string): void {
		if (!lightSummary(id)) return;
		markLoaded(id);
		this.summaryTick++;
	}

	/** `id`'s summary as stored. */
	private setSummary(id: string, summary: ShotSummary): void {
		if (this.summaries.get(id) === summary) return;
		this.summaries.set(id, summary);
		this.summaryTick++;
	}

	/**
	 * What describes the held row `s`: a light row's stored summary; for a row
	 * whose heavy parts are in memory (new, not written yet, or the legacy
	 * backend's), one worked out from them; for a stored row memory holds
	 * nothing heavy for, its stored summary.
	 */
	private summaryOf(s: StoredShot): ShotSummary {
		const light = lightSummary(s.id);
		if (light) return light;
		if (this.mode !== 'legacy' && !hasHeavyParts(s)) {
			const stored = this.summaries.get(s.id);
			if (stored) return stored;
		}
		return summaryFromMemory(s);
	}

	/** The held row `s` as a {@link ShotRow}. */
	private rowOf(s: StoredShot): ShotRow {
		const summary = this.summaryOf(s);
		const cached = this.rowCache.get(s);
		if (cached && cached.summary === summary) return cached;
		const row = asRow(s, summary);
		this.rowCache.set(s, row);
		return row;
	}

	/** Every row, and the view, as {@link ShotRow}s: rebuilt when a row or a summary changes. */
	private rows(): NonNullable<HistoryStore['rowsMemo']> {
		const rows = this.shots;
		const tick = this.summaryTick;
		if (this.rowsMemo?.rows === rows && this.rowsMemo.tick === tick) return this.rowsMemo;
		const raw = rows.map((s) => this.rowOf(s));
		const byId = new Map<string, ShotRow>();
		for (const r of raw) if (!byId.has(r.id)) byId.set(r.id, r);
		const all = this.viewOf().live.map((s) => this.rowOf(s));
		this.rowsMemo = { rows, tick, raw, all, byId };
		return this.rowsMemo;
	}

	/** A row or whole shot handed back, as the store holds it ({@link mergeOnto}); `row` unchanged when it is one of ours. */
	private intoHeld(incoming: AnyShot): StoredShot {
		const held = this.byId().get(incoming.id);
		if (held && isShotRow(incoming) && this.rowCache.get(held) === incoming) return held;
		return mergeOnto(incoming, held);
	}

	/** Keep the stored heavy parts of `id` for the next {@link loadShot}. */
	private keepLoaded(id: string, summary: ShotSummary, details: DetailsRecord): void {
		this.loadedShots.delete(id);
		this.loadedShots.set(id, { summary, details });
		while (this.loadedShots.size > LOADED_SHOTS_KEPT) {
			const oldest = this.loadedShots.keys().next().value;
			if (oldest === undefined) break;
			this.loadedShots.delete(oldest);
		}
	}

	/** Move the written base to `next` when it pointed at `prev`: no write needed. */
	private rebase(prev: StoredShot, next: StoredShot): void {
		if (this.written.get(prev.id) === prev) this.written.set(prev.id, next);
	}

	/**
	 * On `crema-history`: every row whose heavy parts are written goes light
	 * (SPEC §4, P4w). Nothing loads them back for the list: the rows draw from
	 * their summaries, and whatever needs a curve asks {@link loadShot}. A row
	 * whose heavy parts aren't stored yet keeps them.
	 */
	private settleView(): void {
		if (this.mode !== 'idb') return;
		const unsaved = new Set(this.unsavedIds);
		let changed = false;
		const next = this.shots.map((s) => {
			if (lightSummary(s.id) || this.dirtyDetails.has(s.id) || this.writingDetails.has(s.id) || unsaved.has(s.id)) return s;
			const summary = this.summaries.get(s.id);
			if (!summary || !this.stored.has(s.id)) return s;
			const light = lightOf(s);
			this.light(s.id, summary);
			this.rebase(s, light);
			changed = true;
			return light;
		});
		if (changed) this.shots = next;
	}

	// ── Readers ────────────────────────────────────────────────────────────

	/**
	 * Wait for the rows, and on the legacy backend for the curves it reads
	 * after them from the old copy's series database. On `crema-history` this
	 * is {@link whenReady}: no curve is loaded for the list (P4w), and
	 * whatever sends or writes a whole shot asks {@link loadShot}. True once
	 * loaded ({@link seriesFailed} says whether the legacy curves could be
	 * read); false when the rows didn't load, or the curves outlasted the wait.
	 */
	async seriesReady(timeoutMs: number = SERIES_WAIT_MS): Promise<boolean> {
		if (!(await this.readyWithin(Math.min(timeoutMs, this.opts.readyTimeoutMs ?? READY_TIMEOUT_MS)))) return false;
		const loading = this.mode === 'legacy' ? this.seriesLoading : null;
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
	 * The legacy backend's stored series were there to read and couldn't be,
	 * so rows that have curves look like rows that don't. History works on
	 * from memory, but anything written from it now is missing those curves.
	 */
	get seriesFailed(): boolean {
		return this.seriesUnreadable;
	}

	/** Machine shots no upload can take: no telemetry, now the series have settled. */
	get withoutTelemetry(): ShotRow[] {
		if (!this.loadedFlag) return [];
		if (this.mode === 'legacy' && !this.seriesSettled) return [];
		return this.rows().all.filter((s) => !s.visualizerId && sampleCountOf(s) === 0 && refusedNoSamples(s));
	}

	/** The shots waiting to go to Visualizer. */
	get visualizerBacklog(): ShotRow[] {
		if (!this.loadedFlag) return [];
		const none = new Set(this.withoutTelemetry.map((s) => s.id));
		const config = readSyncConfig();
		return this.rows().all.filter(
			(s) => !s.visualizerId && !isBrewLog(s) && !none.has(s.id) && !excludedFromUpload(s, config)
		);
	}

	/**
	 * The rows as this tab holds them: P3w's, in memory (#310 W3). Not on the
	 * store's public type: every reader asks a query (`#lib/history/reads`),
	 * and only the store's own seams read the whole list, through
	 * `p3RowsOf` (`p3-rows.ts`): the dev oracle, the in-memory fallback list, the P3
	 * backup source and sync's planners (`p3-rows-guard.vitest.ts` holds the
	 * list). Each is checked before the rows load in dev, as before.
	 */
	private liveRows(): ShotRow[] {
		this.assertLoaded('all');
		return this.rows().all;
	}

	private rawRows(): ShotRow[] {
		this.assertLoaded('rawAll');
		return this.rows().raw;
	}

	/** The rows, for {@link p3RowsOf}'s callers only. */
	[P3_ROWS](): P3Rows {
		// eslint-disable-next-line @typescript-eslint/no-this-alias
		const self = this;
		return {
			get live() {
				return self.liveRows();
			},
			get raw() {
				return self.rawRows();
			}
		};
	}

	/** Each list of rows this store has held, numbered: {@link rowsVersion} without handing the list out. */
	private listNumbers = new WeakMap<readonly StoredShot[], number>();
	private lastListNumber = 0;

	/**
	 * Moves whenever a row, the set or a summary does: what a reactive query
	 * follows to ask again (#310 W3). Opaque; reactive.
	 */
	get rowsVersion(): string {
		const rows = this.shots;
		let n = this.listNumbers.get(rows);
		if (n === undefined) {
			n = ++this.lastListNumber;
			this.listNumbers.set(rows, n);
		}
		return `${n}:${this.summaryTick}`;
	}

	/** Whether this tab holds any row at all, tombstones too: a capture limit's first read (#311). */
	get holdsAnyRow(): boolean {
		return this.shots.length > 0;
	}

	/**
	 * `crema-history`'s write counter as stored, or null on the legacy backend
	 * (which has none) or when it can't be read: for a backup's "changed?"
	 * (#310 R1w). Reads only.
	 */
	async writeSeqNow(): Promise<number | null> {
		await this.whenReady().catch(() => {});
		const db = this.db;
		if (this.mode !== 'idb' || !db) return null;
		return db.writeSeq().catch(() => null);
	}

	/** Look up one row by id, tombstone or not, or `undefined` if it is not in the history. */
	get(id: string): ShotRow | undefined {
		this.assertLoaded('get');
		return this.rows().byId.get(id);
	}

	/**
	 * `id` whole, with its samples, profile and brew series, for code that
	 * draws, sends or writes it. Undefined when the history has no such row.
	 * The last {@link LOADED_SHOTS_KEPT} are kept, so going back to a shot
	 * reads nothing.
	 */
	async loadShot(id: string): Promise<StoredShot | undefined> {
		return (await this.loadMany([id]))[0];
	}

	/**
	 * {@link loadShot} for many, in order; ids not in the history are left
	 * out. A read that fails is tried once more, then throws: the caller
	 * stops, and never goes on with an empty curve.
	 */
	async loadMany(ids: readonly string[], again = true): Promise<StoredShot[]> {
		await this.whenReady();
		const held = this.byId();
		// This call's heavy parts, by id: taken from what's kept now, so a long
		// batch pushing entries out of {@link loadedShots} can't lose any.
		const got = new Map<string, { summary: ShotSummary; details: DetailsRecord | undefined }>();
		const want = new Map<string, ShotSummary>();
		for (const id of ids) {
			const summary = held.has(id) ? lightSummary(id) : undefined;
			if (!summary || got.has(id)) continue;
			const kept = this.loadedShots.get(id);
			if (kept && kept.summary === summary) got.set(id, { summary, details: kept.details });
			else want.set(id, summary);
		}
		const db = this.db;
		if (want.size > 0 && db && this.mode === 'idb') {
			const read = [...want.keys()];
			let found: Map<string, DetailsRecord>;
			try {
				found = await db.detailsOf(read).catch(() => db.detailsOf(read));
			} catch (e) {
				if (e instanceof HistoryDbLost) await this.lostNow(e);
				throw e;
			}
			for (const [id, summary] of want) got.set(id, { summary, details: found.get(id) });
		}
		// After the await: the rows as they are now, with what the store holds.
		const now = this.byId();
		const out: StoredShot[] = [];
		let stale = false;
		for (const id of ids) {
			const s = now.get(id);
			if (!s) continue;
			const summary = lightSummary(id);
			if (!summary) {
				out.push(s);
				continue;
			}
			const g = got.get(id);
			// The row changed while it was read (a backfill landed): read again,
			// once, rather than hand out the curve it had (#420 N2).
			if (g && g.summary !== summary) stale = true;
			// Kept only while the row is still what was read for.
			if (g?.details && g.summary === summary) this.keepLoaded(id, summary, g.details);
			// A part memory holds that isn't written yet (a pull's) wins.
			out.push(withDetails(s, hasHeavyParts(s) ? keepHeavy(detailsOf(s), g?.details) : g?.details));
		}
		if (stale && again) return this.loadMany(ids, false);
		return out;
	}

	// ── Other tabs ─────────────────────────────────────────────────────────

	/** Stage 2: every write to `history.db`, this tab's own too, as its owner posts it (SPEC §10). */
	private listenToClient(s2: Stage2): void {
		this.unlistenClient?.();
		const offChange = s2.client.onChange((c) => {
			if (this.stage2 !== s2) return;
			for (const id of [...c.upserted, ...c.deleted]) this.heard.add(id);
			if (this.loadedFlag && this.mode === 'idb' && this.deletesPaused === 0) this.applyHeard();
		});
		// Another tab's recovery moved the history to a new file: follow it (REVIEW-476 B1).
		const offStatus = s2.client.onStatus(() => {
			if (this.stage2 === s2) this.maybeFollow();
		});
		const onStorage = (e: StorageEvent) => {
			if (e.key === MARKER_KEY && this.stage2 === s2) this.maybeFollow();
		};
		if (typeof window !== 'undefined') window.addEventListener('storage', onStorage);
		this.unlistenClient = () => {
			offChange();
			offStatus();
			if (typeof window !== 'undefined') window.removeEventListener('storage', onStorage);
		};
	}

	private following: Promise<unknown> | null = null;

	/**
	 * The marker names another file than this tab's (another tab's recovery,
	 * W5c): while this tab can't use what it has (`lost`, or an owner it
	 * refuses), it moves to that file, writing the shots only it holds there.
	 */
	private maybeFollow(): void {
		const s2 = this.stage2;
		if (!s2?.restage || !s2.file || this.following || this.recovering) return;
		const m = readSqliteMarker(s2.storage);
		if (m?.state !== 'done' || dbFileName(m.generation) === s2.file) return;
		const stuck = this.healthState === 'lost' || this.healthState === 'unavailable' || s2.client.status.health !== 'ok';
		if (!stuck) return;
		this.following = this.moveTo(m.generation)
			.catch((e) => console.warn('[history] couldn’t follow the recovered history', e))
			.finally(() => (this.following = null));
	}

	/** This tab onto `history-<n>.db`, which a recovery made (S9). */
	private async moveTo(generation: number): Promise<boolean> {
		const s2 = this.stage2;
		if (!s2?.restage) return false;
		const afterLoss = this.healthState === 'lost';
		const next = await s2.restage(generation);
		this.stage2 = next;
		const db = new SqliteRows(next.client, next.details);
		this.sqlite = db;
		this.db = db as unknown as HistoryDb;
		this.journal = sqliteJournal(next.storage);
		this.listenToClient(next);
		const mine = afterLoss ? this.forgetLostStore() : new Set<string>();
		this.mode = 'booting';
		this.healthState = 'loading';
		this.oldCopyInfo = null;
		if (!(await this.openSqlite(db))) return false;
		await this.loadFromDb(afterLoss ? { mine, afterLoss: true } : {});
		this.recordHeldJournal(db);
		await this.flushed();
		if (afterLoss) {
			this.lastFill = { restored: 0, notRestored: 0 };
			await this.fillFromArchive();
			this.lastRecoveryState = { ...this.lastFill };
		}
		return this.health === 'ok';
	}
	private recovering = false;
	private unlistenClient: (() => void) | null = null;

	private listenToTabs(): void {
		if (this.stage2) this.listenToClient(this.stage2);
		const make = this.stage2 ? null : this.opts.channel === undefined ? defaultChannel : this.opts.channel;
		this.channel = make ? make() : null;
		if (this.channel) {
			this.channel.onmessage = (event) => {
				const raw = event.data as { type?: unknown; from?: unknown } | null;
				// Another tab recovered a lost store, or wrote to one this tab can't
				// open: try it again, rather than offer choices that would clear it.
				if (raw && raw.from !== this.tabId && (this.healthState === 'lost' || this.healthState === 'unavailable')) {
					const was = this.healthState;
					void this.reopenIfRecovered().then((back) => {
						if (back && was === 'lost') toast.info('Shot history is back: another tab restored it.', 6000);
					});
					return;
				}
				const m = raw as Partial<ChannelMessage> | null;
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
		this.adopting += 1;
		void db
			.rowsOf(ids)
			.then((found) => this.adoptRecords(found, ids))
			.catch(() => {
				for (const id of ids) this.heard.add(id);
			})
			.finally(() => (this.adopting -= 1));
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
			// A row whose heavy parts wait to be written, or are being written,
			// keeps memory's copy: the store's would drop them (#420 N1).
			if (dirty.has(s.id) || this.dirtyDetails.has(s.id) || this.writingDetails.has(s.id)) {
				next.push(s);
				continue;
			}
			if (!rec) {
				if (this.stored.has(s.id)) {
					// Deleted in another tab, which removes its archived curve: one
					// this tab is writing goes again once written (#420 FC-N1).
					this.forget(s.id);
					this.archiveDeleted.add(s.id);
					changed = true;
					continue;
				}
				next.push(s);
				continue;
			}
			const { shot, summary } = shotOfRow(rec);
			const before = this.summaries.get(s.id);
			this.setSummary(s.id, summary);
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
				this.light(s.id, summary);
			}
			this.written.set(s.id, row);
			next.push(row);
			changed = true;
		}
		for (const [id, rec] of found) {
			if (seen.has(id) || this.removing.has(id) || (scope && !scope.has(id))) continue;
			const { shot, summary } = shotOfRow(rec);
			this.setSummary(id, summary);
			this.stored.add(id);
			this.light(id, summary);
			this.written.set(id, shot);
			next.push(shot);
			changed = true;
		}
		if (!changed) return;
		this.shots = next.sort(byDateThenId);
		this.settleView();
	}

	private forget(id: string): void {
		this.loadedShots.delete(id);
		this.fills.delete(id);
		this.written.delete(id);
		this.stored.delete(id);
		this.summaries.delete(id);
		this.dirtyDetails.delete(id);
		this.unlight(id);
	}

	private post(ids: readonly string[], seq: number): void {
		// Stage 2: the owner posts every write's change set itself.
		if (this.sqlite || !this.channel || ids.length === 0) return;
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
		// Not loaded yet: a new shot's row waits in the journal, so closing the
		// tab now doesn't lose it (S1). The boot writes it, and drops the entry.
		if (this.mode === 'booting') {
			for (const id of this.journalNew(false)) this.bootJournal.add(id);
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
		// Rows that changed, and rows whose curve still isn't stored, whatever
		// changed: an unsaved curve goes with every later save (M1).
		const ids = [...new Set([...this.dirtyRowIds(), ...this.dirtyDetails, ...this.unsavedIds])];
		if (ids.length === 0) return;
		const byId = this.byId();
		const writes: ShotWrite[] = [];
		for (const id of ids) {
			const shot = byId.get(id);
			if (!shot) continue;
			const details = this.dirtyDetails.has(id) || !this.summaries.has(id);
			writes.push({
				shot,
				details,
				summary: this.summaries.get(id),
				...(details && this.fills.has(id) ? { fill: true } : {}),
				...(this.pulled.has(id) && !this.stored.has(id) ? { pulled: true } : {})
			});
		}
		for (const w of writes) {
			if (!w.details) continue;
			this.dirtyDetails.delete(w.shot.id);
			this.fills.delete(w.shot.id);
			this.writingDetails.add(w.shot.id);
		}
		const writing = writes.filter((w) => w.details).map((w) => w.shot.id);
		try {
			const result = (await this.db.putShots(writes, summarize)) as Awaited<ReturnType<HistoryDb['putShots']>> & {
				duplicates?: { id: string; existingId: string }[];
			};
			for (const id of writing) this.writingDetails.delete(id);
			for (const w of writes) this.pulled.delete(w.shot.id);
			const dups = new Set((result.duplicates ?? []).map((d) => d.id));
			if (dups.size > 0) this.dropDuplicates(dups);
			this.afterWrite(
				writes.filter((w) => !dups.has(w.shot.id)),
				result.seq,
				result.written
			);
		} catch (e) {
			for (const id of writing) this.writingDetails.delete(id);
			for (const w of writes) {
				if (!w.details) continue;
				this.dirtyDetails.add(w.shot.id);
				if (w.fill) this.fills.add(w.shot.id);
			}
			if (e instanceof HistoryDbLost) {
				await this.lostNow(e);
				return;
			}
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
		const lighten = new Set<string>();
		const savedCurves: string[] = [];
		for (const w of writes) {
			const id = w.shot.id;
			this.stored.add(id);
			const got = written.get(id);
			if (got) {
				this.setSummary(id, got.summary);
				savedCurves.push(id);
				// What the store now holds, for the detail that opens next.
				this.keepLoaded(id, got.summary, got.details);
				// A curve landed: it goes into the archive too, once.
				this.oweArchive(id, got.details);
				if (lightSummary(id)) {
					// A light row given a part (a backfill): light again, as stored.
					this.light(id, got.summary);
					lighten.add(id);
				} else if (storeHeldMore(w.shot, got.details)) bring.set(id, got.details);
			}
			if (now.get(id) === w.shot) this.written.set(id, w.shot);
		}
		if (savedCurves.length > 0) this.clearUnsaved(savedCurves);
		if (bring.size > 0 || lighten.size > 0) {
			// The store kept heavy parts memory didn't hold: memory shows them
			// too, until the row goes light below.
			this.shots = this.shots.map((s) => {
				if (lighten.has(s.id) && !this.dirtyDetails.has(s.id) && hasHeavyParts(s)) {
					const light = lightOf(s);
					this.rebase(s, light);
					return light;
				}
				const d = bring.get(s.id);
				if (!d) return s;
				const full = withDetails(s, d);
				this.rebase(s, full);
				return full;
			});
		}
		this.journal.drop(writes.map((w) => w.shot.id));
		this.post(
			writes.map((w) => w.shot.id),
			seq
		);
		this.settleView();
		if (this.markerOwed) this.writeMarker(this.markerOwed.at, this.markerOwed.rows);
		// Curves an archive write couldn't take before go again with each save.
		if (this.archiveOwed.size > 0) this.runArchive();
		this.settleLateRecord();
		this.maybeSnapshot();
		if (this.dirtyRowIds().length > 0) this.scheduleFlush();
	}

	/**
	 * N8: pulled rows the store didn't take, because another row (another
	 * tab's pull of the same Visualizer shot) holds their `visualizerId`. They
	 * were never stored: they leave memory, and the stored row comes in with
	 * its change notice. Nothing is deleted from the store.
	 */
	private dropDuplicates(ids: ReadonlySet<string>): void {
		this.shots = this.shots.filter((s) => !ids.has(s.id));
		for (const id of ids) {
			this.forget(id);
			this.dirtyDetails.delete(id);
		}
		this.journal.drop([...ids]);
		this.settleView();
	}

	/** The late rows' record, once every late row is stored with its details. */
	private settleLateRecord(): void {
		const owed = this.lateOwed;
		const db = this.db;
		if (!owed || !db) return;
		if (!owed.ids.every((id) => this.stored.has(id) && !this.dirtyDetails.has(id) && !this.unsavedIds.includes(id))) return;
		this.lateOwed = null;
		this.writeChain = this.writeChain.then(async () => {
			try {
				await db.setMeta(META_MIGRATION, owed.record);
			} catch {
				// Owed again: a record naming rows the store doesn't hold would keep them out.
				this.lateOwed ??= owed;
			}
		});
	}

	/**
	 * A write failed (most likely quota). The rows alone are about 1 KB each and
	 * may fit: try them; failing that, new shots go to the journal. Their curves
	 * stay in memory and go with each later save, and the banner says so.
	 */
	private async writeFailed(writes: readonly ShotWrite[], error: unknown): Promise<void> {
		this.lastError = errorText(error);
		const withHeavy = writes.filter((w) => w.details);
		// A row stored without its details says so: its summary is the light
		// shot's, no samples, so a backfill or a restore can still fill it (M2).
		const rowsOnly: ShotWrite[] = writes.map((w) => ({
			shot: w.shot,
			details: false,
			summary: w.summary ?? summarize(lightOf(w.shot))
		}));
		let rowsStored = false;
		try {
			const r = await this.db!.putShots(rowsOnly, summarize);
			rowsStored = true;
			const now = this.byId();
			for (const w of rowsOnly) {
				this.stored.add(w.shot.id);
				if (!this.summaries.has(w.shot.id) && w.summary) this.setSummary(w.shot.id, w.summary);
				if (now.get(w.shot.id) === w.shot) this.written.set(w.shot.id, w.shot);
			}
			this.lastSeq = Math.max(this.lastSeq, r.seq);
			this.post(
				rowsOnly.map((w) => w.shot.id),
				r.seq
			);
		} catch (e) {
			if (e instanceof HistoryDbLost) {
				await this.lostNow(e);
				return;
			}
			for (const w of writes) if (!this.stored.has(w.shot.id)) this.journal.add(w.shot, w.summary ?? summarize(w.shot));
		}
		const lost = withHeavy
			.filter((w) => (heldSampleCount(w.shot) ?? 0) > 0 || (w.shot.brewSeries?.samples.length ?? 0) > 0 || w.shot.profile != null)
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
	private journalNew(unsaved = true): string[] {
		const fresh = this.shots.filter(
			(s) => !this.stored.has(s.id) && this.written.get(s.id) !== s && !(this.movedAway && this.known.has(s.id))
		);
		for (const s of fresh) {
			if (this.journal.add(s, this.summaries.get(s.id) ?? summarize(s))) this.written.set(s.id, s);
		}
		const ids = fresh.map((s) => s.id);
		if (unsaved) this.markUnsaved(ids);
		return ids;
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
		this.journal.drop(ids);
		this.settleView();
		const db = this.db;
		let result = true;
		const op = this.writeChain.then(async () => {
			try {
				if (!db || this.mode !== 'idb') {
					// Nothing reached the store; a journalled row went with dropPending.
					// After a move elsewhere the store still holds it.
					result = this.mode === 'blocked' && !this.movedAway;
					// The store is gone, so this delete is the only record of it: its
					// tombstone keeps the archived curve from coming back with a
					// recovery (#420 FC3-N1). Not when the store only won't open:
					// it still holds the row.
					if (this.healthState === 'lost' && this.archive) {
						for (const id of ids) this.archiveDeleted.add(id);
						this.tombstone(ids);
					}
					return;
				}
				const seq = await db.delete(ids);
				this.lastSeq = Math.max(this.lastSeq, seq);
				this.post(ids, seq);
				// Only once the store's delete has landed: their archived curves go too.
				this.forgetArchived(ids);
				after?.();
			} catch (e) {
				result = false;
				if (e instanceof HistoryDbLost) {
					await this.lostNow(e);
					return;
				}
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
		if (!this.db) return;
		// Stage 2's rows snapshot is `history-snapshot.db`, the owner's online
		// copy (SPEC §11): after the first write each day, skipped when nothing moved.
		if (this.stage2) {
			const today = dayOf(this.now());
			if (this.snapshotTried === today) return;
			this.snapshotTried = today;
			const client = this.stage2.client;
			void client.snapshot().catch((e) => {
				console.warn('[history] the rows snapshot failed; tried again after the next write', e);
				// Tried again after the next write.
				if (this.snapshotTried === today) this.snapshotTried = null;
			});
			return;
		}
		// One already running (yesterday's, say): this save's turn comes when it ends.
		if (this.snapshotting) {
			this.snapshotAgain = true;
			return;
		}
		// Once a day per tab, written or not: with no private file system every
		// save would read every row again.
		const now = this.now();
		const today = dayOf(now);
		if (this.snapshotTried === today) return;
		this.snapshotTried = today;
		this.snapshotting = true;
		const db = this.db;
		void (async () => {
			try {
				// A day's copy is written once: another tab, or this one before a
				// recovery, may have written today's.
				if (await snapshotWritten(now, this.opts.snapshotRoot)) return;
				// The profiles off the main thread: in the store, each comes with its curve.
				const make =
					this.opts.snapshotWorker !== undefined ? this.opts.snapshotWorker : this.opts.idb === undefined ? defaultSnapshotWorker : null;
				const reader = make ? workerProfileReader(make, (ids) => readProfiles(db, ids)) : null;
				let lines;
				try {
					lines = await snapshotLines(db, reader?.profilesOf);
				} finally {
					reader?.close();
				}
				await writeRowsSnapshot(lines, now, this.opts.snapshotRoot, this.opts.locks === undefined ? undefined : this.opts.locks);
			} catch {
				// Tomorrow's goes again.
			} finally {
				this.snapshotting = false;
				if (this.snapshotAgain) {
					this.snapshotAgain = false;
					this.maybeSnapshot();
				}
			}
		})();
	}

	/** A save asked for the day's copy while one was being written. */
	private snapshotAgain = false;

	// ── The curve archive (#420 S1) ───────────────────────────────────────

	/** `id`'s curve landed in the store: archive it, once, off the save's path. */
	private oweArchive(id: string, details: DetailsRecord | null): void {
		if (!this.archive || this.mode !== 'idb') return;
		if (details && !holdsCurve(details.samples, details.brewSeries)) return;
		this.archiveOwed.set(id, details);
		this.runArchive();
	}

	/** Write what the archive is owed, a few at a time, yielding between. A failure waits for the next try. */
	private runArchive(): void {
		const archive = this.archive;
		if (!archive || this.archiveRun) return;
		this.archiveRun = (async () => {
			let failed = false;
			while (this.archiveOwed.size > 0 && !failed && this.mode === 'idb') {
				await new Promise((r) => setTimeout(r, 0));
				const batch = [...this.archiveOwed].slice(0, 8);
				const unread = batch.filter(([, d]) => d === null).map(([id]) => id);
				let read = new Map<string, DetailsRecord>();
				if (unread.length > 0 && this.db) {
					try {
						read = await this.db.detailsOf(unread);
					} catch {
						failed = true;
						break;
					}
				}
				for (const [id, held] of batch) {
					if (this.archiveDeleted.has(id) || !this.stored.has(id)) {
						this.archiveOwed.delete(id);
						continue;
					}
					const d = held ?? read.get(id);
					// A factory reset took the store's marker: write nothing that
					// would bring the archive's directory back (#420 FC-N4).
					if (!markerSaysDone() && !this.markerOwed) {
						this.archiveOwed.clear();
						break;
					}
					try {
						if (d) await archive.write(id, { samples: d.samples, brewSeries: d.brewSeries });
						this.archiveOwed.delete(id);
						this.archiveStuck = false;
					} catch (e) {
						this.archiveStuck = true;
						// Quota, most likely: tried again with the next save. The
						// store is untouched, and the save didn't wait.
						this.lastError = `A curve couldn’t be kept in the archive: ${errorText(e)}`;
						failed = true;
						break;
					}
					// Deleted while it was written: the file goes again.
					// Deleted while it was written (here, or in a tab this one heard):
					// the delete is recorded, so its file goes, now or later.
					if (this.archiveDeleted.has(id)) this.tombstone([id]);
				}
			}
		})().finally(() => {
			this.archiveRun = null;
			void this.countArchive();
		});
	}

	/** This load's archive catch-up has run. */
	get archiveCaughtUp(): boolean {
		return this.archiveCaughtUpFlag;
	}

	/** Count the archive's files and bytes now, for the storage view. */
	countArchiveNow(): Promise<void> {
		return this.countArchive();
	}

	/** Count the archive's files and bytes again. */
	private async countArchive(): Promise<void> {
		if (!this.archive) {
			this.archiveCount = null;
			return;
		}
		try {
			if (!(await this.archive.available())) {
				this.archiveCount = null;
				return;
			}
			// Archived means a file for a shot the store holds with a curve: a
			// file kept for a daily-copy row, or a tombstone's, doesn't count
			// (#420 FC2-N2). The bytes are every file's, as they take space.
			const sizes = await this.archive.sizes();
			let files = 0;
			let bytes = 0;
			for (const [id, size] of sizes) {
				bytes += size;
				const s = lightSummary(id) ?? this.summaries.get(id);
				if (size > 0 && s && ((s.sampleCount ?? 0) > 0 || s.brewSampleCount > 0) && !this.archiveCorrupt.has(id)) files += 1;
			}
			this.archiveCount = { files, bytes };
		} catch {
			// Counted again after the next write.
		}
	}

	/**
	 * The curve archive as Settings and the banner show it: its files and
	 * bytes against the rows with a curve, and whether a write waits.
	 * Null where there's no private file system; undefined before it's counted.
	 */
	get archiveStatus():
		| { files: number; bytes: number; curves: number; waiting: boolean; notRestored: number }
		| null
		| undefined {
		const count = this.archiveCount;
		if (!count) return count;
		let curves = 0;
		for (const s of this.summaries.values()) if ((s.sampleCount ?? 0) > 0 || s.brewSampleCount > 0) curves += 1;
		return { ...count, curves, waiting: this.archiveStuck, notRestored: this.curvesNotRestored() };
	}

	/** Every archive write asked for so far has been tried. */
	async archiveSettled(): Promise<void> {
		while (this.archiveRun) await this.archiveRun;
	}

	/** Shots this tab deleted from the store: tombstones recorded, and their archived curves go. */
	private forgetArchived(ids: readonly string[]): void {
		if (!this.archive) return;
		for (const id of ids) {
			this.archiveDeleted.add(id);
			this.archiveOwed.delete(id);
		}
		this.tombstone(ids);
	}

	/** Record tombstones for `ids`, durably, then remove their files. */
	private tombstone(ids: readonly string[]): void {
		for (const id of ids) {
			this.archiveRemoveOwed.add(id);
			setArchiveTombstone(id, true);
		}
		void this.removeArchived();
	}

	/**
	 * Remove the files the tombstones name. A tombstone goes once its file
	 * is gone; one whose remove fails stays, for the next delete or catch-up
	 * (#420 FC-N1).
	 */
	private async removeArchived(): Promise<void> {
		const archive = this.archive;
		// Another tab's too: one whose remove failed there goes here.
		for (const id of readArchiveTombstones()) this.archiveRemoveOwed.add(id);
		const ids = [...this.archiveRemoveOwed];
		if (!archive || ids.length === 0) return;
		for (const id of ids) {
			try {
				await archive.remove([id]);
				this.archiveRemoveOwed.delete(id);
				setArchiveTombstone(id, false);
			} catch (e) {
				console.warn('[history] an archived curve of a deleted shot couldn’t be removed; tried again later', e);
			}
		}
		void this.countArchive();
	}

	/**
	 * After a load, in the background: archive the curves the store holds
	 * that the archive lacks (shots from before the archive, a migration, late
	 * rows), and give a row the store holds no curve for the archive's.
	 */
	private scheduleArchiveCatchUp(): void {
		if (!this.archive || this.archiveCatchUp) return;
		this.archiveCatchUp = setTimeout(() => {
			void this.archiveCatchUpNow().catch(() => {});
		}, this.opts.archiveDelayMs ?? 10_000);
	}

	/** {@link scheduleArchiveCatchUp}'s work, now. */
	async archiveCatchUpNow(): Promise<void> {
		if (this.archiveCatchUp) clearTimeout(this.archiveCatchUp);
		this.archiveCatchUp = null;
		const archive = this.archive;
		if (!archive || this.mode !== 'idb' || !(await archive.available())) return;
		await this.removeArchived();
		const have = await archive.ids();
		// Once a day every file is checked against the store's curve, so one
		// that is corrupt, or older than the store's, is rewritten (#420
		// FC-S1, FC-S2); a write that matches rewrites nothing. Otherwise
		// only a shot with no file, or an empty one, is owed.
		for (const [id, summary] of this.summaries) {
			if (have.has(id) || this.archiveOwed.has(id)) continue;
			if ((summary.sampleCount ?? 0) > 0 || summary.brewSampleCount > 0) this.archiveOwed.set(id, null);
		}
		this.runArchive();
		await this.fillFromArchive(have);
		await this.archiveSettled();
		this.archiveCaughtUpFlag = true;
		await this.checkArchivePass(archive);
		await this.countArchive();
	}

	/**
	 * The daily whole check: every file against the store's curve, in id
	 * order, a few at a time with a yield between, so one that is corrupt or
	 * older than the store's is rewritten (#420 FC-S1, FC-S2); a file that
	 * matches costs only a read. Where it got to is kept, so a load that ends
	 * mid-pass leaves the next to go on from there, and a pass is finished,
	 * across loads, before another begins (#420 FC3-S1).
	 */
	private async checkArchivePass(archive: CurveArchive): Promise<void> {
		const today = dayOf(this.now());
		let pass = readArchiveCheck();
		if (!pass) {
			let checkedOn: string | null = null;
			try {
				checkedOn = localStorage.getItem(ARCHIVE_CHECKED_KEY);
			} catch {
				return;
			}
			if (checkedOn === today) return;
			pass = { on: today, after: '' };
			writeArchiveCheck(pass);
		}
		const ids = [...this.summaries]
			.filter(([, s]) => (s.sampleCount ?? 0) > 0 || s.brewSampleCount > 0)
			.map(([id]) => id)
			.filter((id) => id > pass.after)
			.sort();
		for (let i = 0; i < ids.length; i += 8) {
			const db = this.db;
			if (!db || this.mode !== 'idb') return;
			await new Promise((r) => setTimeout(r, 0));
			const part = ids.slice(i, i + 8);
			let found: Map<string, DetailsRecord>;
			try {
				found = await db.detailsOf(part);
			} catch {
				// Gone on with at the next load, from here.
				return;
			}
			for (const id of part) {
				const d = found.get(id);
				if (d && this.stored.has(id) && !this.archiveDeleted.has(id) && markerSaysDone()) {
					try {
						await archive.write(id, { samples: d.samples, brewSeries: d.brewSeries });
					} catch (e) {
						this.archiveStuck = true;
						this.lastError = `A curve couldn’t be kept in the archive: ${errorText(e)}`;
						return;
					}
				}
				pass.after = id;
			}
			writeArchiveCheck(pass);
		}
		try {
			localStorage.setItem(ARCHIVE_CHECKED_KEY, pass.on);
		} catch {
			// Checked again at the next load.
		}
		writeArchiveCheck(null);
	}

	/**
	 * Rows the store holds no curve for, whose curve the archive keeps (after
	 * a store loss and a recovery): the archive's goes in, as a fill, so a
	 * curve the store has stays, and the summary is worked out from it. A file
	 * that doesn't read back whole is skipped and logged. On the write chain,
	 * so no save runs between. The number of rows filled.
	 */
	private async fillFromArchive(names?: ReadonlySet<string>): Promise<number> {
		const archive = this.archive;
		if (!archive || this.mode !== 'idb') return 0;
		const skippedBefore = this.archiveCorrupt.size;
		const have = names ?? (await archive.ids());
		const want = this.shots
			.filter((s) => {
				const summary = lightSummary(s.id) ?? this.summaries.get(s.id);
				return (
					have.has(s.id) &&
					this.stored.has(s.id) &&
					!this.archiveDeleted.has(s.id) &&
					!this.archiveCorrupt.has(s.id) &&
					!hasHeavyParts(s) &&
					summary != null &&
					summary.sampleCount === 0 &&
					summary.brewSampleCount === 0
				);
			})
			.map((s) => s.id);
		let filled = 0;
		for (let i = 0; i < want.length; i += 25) {
			const part = want.slice(i, i + 25);
			const run = this.writeChain.then(async () => {
				const db = this.db;
				if (!db || this.mode !== 'idb') return;
				const writes: ShotWrite[] = [];
				for (const id of part) {
					const row = this.byId().get(id);
					if (!row) continue;
					const got = await archive.read(id);
					if (got.kind === 'corrupt') {
						this.archiveCorrupt.set(id, got.why);
						this.corruptTick++;
						console.warn(`[history] the archived curve of ${id} is unreadable (${got.why}); skipped`);
						continue;
					}
					if (got.kind !== 'curve') continue;
					writes.push({
						shot: withDetails(row, { id, samples: got.curve.samples, profile: null, brewSeries: got.curve.brewSeries }),
						details: true,
						fill: true
					});
				}
				if (writes.length === 0) return;
				try {
					const r = await db.putShots(writes, summarize);
					this.lastSeq = Math.max(this.lastSeq, r.seq);
					for (const [id, w] of r.written) {
						this.setSummary(id, w.summary);
						if (lightSummary(id)) this.light(id, w.summary);
						this.keepLoaded(id, w.summary, w.details);
						filled += 1;
					}
					this.clearUnsaved([...r.written.keys()]);
					this.post(
						writes.map((w) => w.shot.id),
						r.seq
					);
				} catch (e) {
					// Tried again at the next load: the files stay.
					this.lastError = errorText(e);
				}
			});
			this.writeChain = run;
			await run;
		}
		this.lastFill = { restored: filled, notRestored: this.archiveCorrupt.size - skippedBefore };
		return filled;
	}

	/** The last fill from the archive, for the recovery that ran it. */
	private lastFill: { restored: number; notRestored: number } = { restored: 0, notRestored: 0 };

	/**
	 * What the last recovery in this tab did with the archive: curves it
	 * restored, and curves it couldn't because their files were corrupt
	 * (skipped, never used). Null before any recovery.
	 */
	get lastRecovery(): { restored: number; notRestored: number } | null {
		return this.lastRecoveryState;
	}

	/** Shots whose archived curve was found corrupt and that still have no curve. */
	private curvesNotRestored(): number {
		void this.corruptTick;
		void this.summaryTick;
		let n = 0;
		for (const id of this.archiveCorrupt.keys()) {
			const s = lightSummary(id) ?? this.summaries.get(id);
			if (s && (s.sampleCount ?? 0) === 0 && s.brewSampleCount === 0) n += 1;
		}
		return n;
	}

	/** Archive files found unreadable, by id: skipped, never used. */
	get archiveSkipped(): ReadonlyMap<string, string> {
		return this.archiveCorrupt;
	}

	// ── Lost and unavailable ──────────────────────────────────────────────

	/** Try the store again (Settings, Try again; or the tab coming back). */
	async retryOpen(): Promise<boolean> {
		if (this.healthState !== 'unavailable') return this.healthState === 'ok';
		if (this.sqlite) {
			if (!(await this.openSqlite(this.sqlite))) return false;
			this.mode = 'booting';
			this.healthState = 'loading';
			await this.loadFromDb();
			this.recordHeldJournal(this.sqlite);
			return this.health === 'ok';
		}
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

	/**
	 * A store made new, for a lost one: the user chose how to fill it. Made
	 * while `making()`; after that, a reopen that finds it gone is lost again.
	 */
	private async freshStore(making: () => boolean): Promise<HistoryDb> {
		const db = this.newDb(() => !making() && markerSaysDone());
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

	/**
	 * The store holds a `done` record again (another tab recovered it): open
	 * it and load, instead of offering choices that would clear it. One at a
	 * time: a message and a click can both ask.
	 */
	private reopenIfRecovered(): Promise<boolean> {
		// Stage 2 recovers into a new `history-<n>.db` (S9), not into P3's store.
		if (this.sqlite) return Promise.resolve(false);
		this.reopenRun ??= this.reopenNow().finally(() => (this.reopenRun = null));
		return this.reopenRun;
	}

	private async reopenNow(): Promise<boolean> {
		if (this.healthState !== 'lost' && this.healthState !== 'unavailable') return false;
		// Never creates it: a store still missing stays lost.
		const db = this.newDb(() => true);
		let record: MigrationRecord | undefined;
		try {
			await db.open();
			record = await db.meta<MigrationRecord>(META_MIGRATION);
		} catch {
			record = undefined;
		}
		if (!record || record.state !== 'done' || (this.healthState !== 'lost' && this.healthState !== 'unavailable')) {
			db.close();
			return false;
		}
		// Another tab recovered the store, and may have edited and deleted in it
		// since (F6). Only shots this tab holds that the store lacks and its
		// recovery's source never had are written: none overwrites a row the
		// store has, and none it deleted comes back.
		const afterLoss = this.healthState === 'lost';
		// The heavy parts memory holds: for rows the store has without them (F7).
		const heavy = afterLoss ? this.shots.filter(hasHeavyParts) : [];
		if (afterLoss) this.forgetLostStore();
		const gone = afterLoss ? new Set(record.sourceIds) : new Set<string>();
		this.db?.close();
		this.db = db;
		this.mode = 'booting';
		this.healthState = 'loading';
		this.oldCopyInfo = null;
		await this.loadFromDb({ gone, afterLoss });
		if (heavy.length > 0 && this.health === 'ok') await this.fillHeavyFrom(heavy);
		// This tab's rows the other tab's recovery couldn't know take their
		// curves from the archive, once written (#420 S1).
		if (afterLoss && this.health === 'ok' && (await this.archive?.available())) {
			await this.flushed();
			this.lastFill = { restored: 0, notRestored: 0 };
			await this.fillFromArchive();
			this.lastRecoveryState = { ...this.lastFill };
		}
		return this.health === 'ok';
	}

	/**
	 * Rows the store holds without some heavy part `held` has (a recovery from
	 * a daily copy has no curves): that part goes in, as a backfill does, and
	 * nothing else. The row's fields are the store's, so no edit made there is
	 * undone, and a part the store has stays. Unsaved until it lands.
	 */
	private async fillHeavyFrom(held: readonly StoredShot[]): Promise<void> {
		const db = this.db;
		if (!db) return;
		const ids = held.filter((s) => this.stored.has(s.id) && !this.dirtyDetails.has(s.id)).map((s) => s.id);
		if (ids.length === 0) return;
		let found: Map<string, DetailsRecord>;
		try {
			found = await db.detailsOf(ids);
		} catch {
			// Unknown: memory's parts go in, which can only add what's missing.
			found = new Map();
		}
		const want = new Map<string, StoredShot>();
		for (const s of held) {
			if (!ids.includes(s.id)) continue;
			const d = found.get(s.id);
			const samples = (d?.samples.length ?? 0) === 0 && s.record.samples.length > 0;
			const profile = d?.profile == null && s.profile != null;
			const brew = (d?.brewSeries?.samples.length ?? 0) === 0 && (s.brewSeries?.samples.length ?? 0) > 0;
			if (samples || profile || brew) want.set(s.id, s);
		}
		if (want.size === 0) return;
		// After the await: the rows as they are now.
		const filled: string[] = [];
		this.shots = this.shots.map((row) => {
			const s = want.get(row.id);
			if (!s) return row;
			const d = found.get(row.id) ?? { id: row.id, samples: [], profile: null, brewSeries: null };
			const full = withDetails(row, {
				id: row.id,
				samples: d.samples.length > 0 ? d.samples : (s.record.samples as DetailsRecord['samples']),
				profile: d.profile ?? s.profile ?? null,
				brewSeries: (d.brewSeries?.samples.length ?? 0) > 0 ? d.brewSeries : (s.brewSeries ?? null)
			});
			this.unlight(row.id);
			this.dirtyDetails.add(row.id);
			filled.push(row.id);
			return full;
		});
		this.markUnsaved(filled);
		this.settleView();
		this.scheduleFlush();
	}

	/**
	 * A recovery the user chose, for a store that is lost. Under the migration
	 * lock, and only if the store is still lost: a tab whose banner is stale
	 * must not clear a store another tab already filled (M3); it loads that
	 * store instead, and says `reopened`. After a recovery the other tabs hear
	 * of it and load.
	 */
	private async recoverWith(fill: (db: HistoryDb) => Promise<number | false>): Promise<RecoveryResult> {
		if (this.healthState !== 'lost') return this.healthState === 'ok' ? 'reopened' : false;
		return this.underLock(MIGRATION_LOCK, async (): Promise<RecoveryResult> => {
			if (await this.reopenIfRecovered()) return 'reopened';
			if (this.healthState !== 'lost') return this.healthState === 'ok' ? 'reopened' : false;
			let making = true;
			const db = await this.freshStore(() => making);
			let rows: number | false;
			try {
				rows = await fill(db);
			} catch (e) {
				db.close();
				throw e;
			} finally {
				making = false;
			}
			// A store left with no record reads as lost at the next boot.
			if (rows === false) {
				db.close();
				return false;
			}
			this.writeMarker(this.now(), rows);
			const mine = this.forgetLostStore();
			this.db?.close();
			this.db = db;
			this.mode = 'booting';
			this.healthState = 'loading';
			this.oldCopyInfo = null;
			await this.loadFromDb({ mine, afterLoss: true });
			// The rows are in; each one the recovered store holds no curve for
			// takes the archive's (#420 S1).
			await this.flushed();
			this.lastFill = { restored: 0, notRestored: 0 };
			await this.fillFromArchive();
			this.lastRecoveryState = { ...this.lastFill };
			try {
				this.channel?.postMessage({ type: 'recovered', from: this.tabId });
			} catch {
				// The other tabs find it when they come back into view.
			}
			return this.health === 'ok';
		});
	}

	/** On `history.db` (stage 2, #310 W5): the `lost` banner offers its own choices. */
	get onStage2(): boolean {
		return this.sqlite !== null;
	}

	/**
	 * Stage 2's `lost` choices (SPEC §11, S9): the owner writes a new
	 * `history-<n>.db` (empty, the old copy's rows, or a rows snapshot) with
	 * its baseline, and the done-record; then the marker names it, and this tab
	 * opens it, writing the shots only this tab held into it. The damaged file
	 * stays where it is. Nothing is deleted.
	 */
	private async recoverStage2(kind: 'empty' | 'oldCopy' | 'snapshot', from?: string): Promise<RecoveryResult> {
		if (this.healthState !== 'lost') return this.healthState === 'ok' ? 'reopened' : false;
		const s2 = this.stage2;
		if (!s2?.restage || this.recovering) return false;
		this.recovering = true;
		try {
			// P3's journal ids: the new file never imports them, so "start empty"
			// stays empty and a shot deleted here stays deleted (REVIEW-476 N1).
			const known = readPending()
				.map((r) => r.id)
				.filter((id): id is string => typeof id === 'string');
			let generation: number;
			try {
				({ generation } = await s2.client.recover(kind, from, known));
			} catch (e) {
				// Another tab's recovery came first: its file is the history now.
				const m = readSqliteMarker(s2.storage);
				if (m?.state !== 'done' || dbFileName(m.generation) === s2.file) throw e;
				generation = m.generation;
			}
			await this.following;
			if (this.stage2 !== s2) return this.health === 'ok' ? 'reopened' : false;
			return await this.moveTo(generation);
		} finally {
			this.recovering = false;
		}
	}


	/** Lost: start again with no shots. */
	startEmpty(): Promise<RecoveryResult> {
		// Stage 2 recovers into a new `history-<n>.db` (S9), not into P3's store.
		if (this.sqlite) return this.recoverStage2('empty');
		return this.recoverWith(async (db) => {
			await db.setMeta(META_MIGRATION, this.oldCopyRecord());
			return 0;
		});
	}

	/** Lost: the old copy, as it was when it moved. */
	recoverOldCopy(): Promise<RecoveryResult> {
		// Stage 2 recovers into a new `history-<n>.db` (S9), not into P3's store.
		if (this.sqlite) return this.recoverStage2('oldCopy');
		return this.recoverWith(async (db) => {
			const result = await copyLegacy(this.migrationDeps(db), coerceStoredShot);
			if (result.kind !== 'done') {
				this.lastError = result.error;
				return false;
			}
			return result.rows;
		});
	}

	/**
	 * Lost: a daily copy (`name`, the newest by default), or with `all` every
	 * copy merged by id, the newest copy winning a clash; each row with its
	 * as-run profile (S3).
	 * Their curves were in the lost store, so the rows come back without
	 * them, and their summaries say so (no samples), so a backfill or a merge
	 * restore can still give each its curve.
	 */
	async recoverSnapshot(name?: string | 'all'): Promise<RecoveryResult> {
		// Stage 2 recovers into a new `history-<n>.db` (S9), not into P3's store:
		// one snapshot file, never a merge of them.
		if (this.sqlite) return this.recoverStage2('snapshot', name === 'all' ? undefined : name);
		if (this.healthState !== 'lost') return this.recoverWith(async () => false);
		const snap =
			name === 'all' ? await readAllRowsSnapshots(this.opts.snapshotRoot) : await readRowsSnapshot(this.opts.snapshotRoot, name);
		if (!snap) {
			this.lastError = 'The daily copy couldn’t be read.';
			return false;
		}
		return this.recoverWith(async (db) => {
			// Each row's yield, peaks and chart as the snapshot has them (F2),
			// with no samples, which the store doesn't hold. A later details
			// write works the summary out again from the real curve.
			const summaries = new Map<string, ShotSummary>();
			const writes: ShotWrite[] = snap.rows.map((r) => {
				const { profile, ...row } = r;
				const { shot, summary } = shotOfRow(row);
				if (summary && typeof summary === 'object') summaries.set(shot.id, { ...summary, sampleCount: 0, brewSampleCount: 0 });
				return { shot: { ...shot, profile: profile ?? null }, details: true };
			});
			const kept = (shot: StoredShot): ShotSummary => summaries.get(shot.id) ?? summarize(shot);
			for (let i = 0; i < writes.length; i += SNAPSHOT_CHUNK) await db.putShots(writes.slice(i, i + SNAPSHOT_CHUNK), kept);
			// The copy's ids count as the source too: one deleted later isn't
			// brought back by a tab that still holds it (F6).
			const record = this.oldCopyRecord();
			const ids = new Set(record.sourceIds);
			for (const w of writes) ids.add(w.shot.id);
			await db.setMeta(META_MIGRATION, { ...record, sourceIds: [...ids] });
			return writes.length;
		});
	}

	/** The daily copies there are to recover, newest first, with their dates and row counts. */
	snapshotInfo(): Promise<SnapshotInfo[]> {
		if (this.stage2) return this.stage2.client.snapshotInfo().catch(() => []);
		return listRowsSnapshots(this.opts.snapshotRoot);
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
	adoptRows(rows: readonly AnyShot[]): void {
		// Merged onto what this tab holds: a row handed in carries no heavy parts.
		const next = rows.map((s) => this.intoHeld(s));
		this.shots = next;
		this.markLive(next.map((s) => s.id));
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
			// Only fills: a curve the store holds stays.
			this.dirtyDetails.add(id);
			this.fills.add(id);
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

	/** The rows the legacy backend would keep of `rows`. */
	private wouldShow(rows: readonly StoredShot[], keepId?: string): Set<string> {
		return new Set(cappedHistory(rows, MAX_RECORDS, keepId).map((s) => s.id));
	}

	/**
	 * Add a row by its date. The list stays newest first, so the core's "last
	 * brew" is the newest one, however the row was dated. On the legacy
	 * backend the cap drops the oldest rows, and a row older than a full
	 * history is the one that goes.
	 */
	private insert(shot: StoredShot): void {
		this.capWith(shot, newestFirst([shot, ...this.shots]));
	}

	/**
	 * Add a row just made on this device. It stays whatever its date, so a clock
	 * behind the history can't lose it: on the legacy backend the oldest other
	 * row goes instead.
	 */
	private insertMade(shot: StoredShot): void {
		this.capWith(shot, newestFirst([shot, ...this.shots]), shot.id);
	}

	/**
	 * Take `rows`, the history with `shot` added. On `crema-history` that's
	 * all: every row is kept (#310 P6w). On the legacy backend the cap drops
	 * rows, never pinned ones (#311), and says so (#310 P0): a row never leaves
	 * without a word.
	 */
	private capWith(shot: StoredShot, rows: StoredShot[], keepId?: string): void {
		if (this.cap === null) {
			this.shots = rows;
			this.dirtyDetails.add(shot.id);
			return;
		}
		const shown = this.wouldShow(rows, keepId);
		const next = rows.filter((s) => shown.has(s.id));
		const droppedOlder = this.shots.filter((s) => !shown.has(s.id)).length;
		this.shots = next;
		const notice = historyCutNotice(droppedOlder, shown.has(shot.id), MAX_RECORDS);
		if (notice) toast.info(notice, 7000);
	}

	/** The legacy backend's cap, for the notices only it can need: `crema-history` refuses and drops nothing. */
	get capacity(): number {
		return MAX_RECORDS;
	}

	/** The cap a restore plans with: none once every row is kept (SPEC §7). */
	get restoreCapacity(): number | null {
		return this.mode === 'idb' ? null : MAX_RECORDS;
	}

	/** How many more live rows fit before the cap; undefined with none (#310 P6w). */
	get freeSlots(): number | undefined {
		const cap = this.cap;
		return cap === null ? undefined : Math.max(0, cap - cappedCount(this.viewOf().view));
	}

	/** Whether a new brew dated `at` would stay in the history once added: always, with no cap. */
	fitsHistory(at: number): boolean {
		const cap = this.cap;
		return cap === null || fitsHistory(this.viewOf().live, at, cap);
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
	 * store, and incoming rows overwrite local ones with
	 * the same id; the last step is one explicit delete of the rows the plan
	 * names in `remove` (SPEC §7: merge, then remove). A kill part-way leaves
	 * the union, which a re-run finishes.
	 */
	async restoreRows(
		plan: RestoreRowsPlan,
		writeSeries: (rows: readonly StoredShot[]) => Promise<void> = (rows) =>
			putManySeries(rows.map((s) => ({ id: s.id, samples: s.record.samples })))
	): Promise<{ stored: boolean; curvesFailed: number }> {
		await this.whenReady();
		if (this.mode === 'idb') return this.restoreRowsToStore(plan);
		// Moved away in another tab: the old copy is not to be written.
		if (this.mode !== 'legacy') return { stored: false, curvesFailed: 0 };
		const first = this.planOver(plan).series.filter((s) => s.record.samples.length > 0);
		let curvesFailed = 0;
		try {
			await writeSeries(first);
		} catch {
			curvesFailed = first.length;
		}
		// After the last await: rows recorded, rated or bound while the series
		// were written are in `this.shots`, and the plan runs over them.
		const { next, series } = this.planOver(plan);
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

	/**
	 * Run a restore's plan over the rows as {@link ShotRow}s, and take its
	 * result back as the store holds rows: each one merged onto the row held
	 * for its id ({@link mergeOnto}), so a row the plan passes through, or
	 * edits, keeps its heavy parts.
	 */
	private planOver(plan: RestoreRowsPlan): { next: StoredShot[]; series: StoredShot[]; remove?: readonly string[] } {
		const out = plan(this.rows().raw);
		return {
			next: out.next.map((s) => this.intoHeld(s)),
			series: out.series.map((s) => this.intoHeld(s)),
			...(out.remove ? { remove: out.remove } : {})
		};
	}

	private async restoreRowsToStore(plan: RestoreRowsPlan): Promise<{ stored: boolean; curvesFailed: number }> {
		const db = this.db!;
		await this.flushed();
		// 1. The rows with curves, in batches straight to the store.
		const first = this.planOver(plan).series;
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
					this.setSummary(id, got.summary);
					this.stored.add(id);
					done.set(id, got.details);
					this.oweArchive(id, got.details);
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
		const { next, series, remove } = this.planOver(plan);
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
			this.unlight(s.id);
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
		// Edited here before its first write: this tab's own now, never dropped
		// as another tab's duplicate (REVIEW-476 N5).
		this.pulled.delete(id);
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
	 * tombstones out via the live rows.
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
	 * Free up space (#310 P6w, SPEC §8): remove `ids` from this device, at the
	 * user's word, never on its own. Pinned shots are exempt, and so are
	 * tombstones, shots whose upload is still queued (which a delete must keep
	 * as tombstones), shots with a Visualizer edit not yet sent, and shots
	 * Decent is to retry; ids not here are skipped. Nothing reaches
	 * Visualizer: a bound shot's remote copy stays, and its id is kept out of
	 * later pulls, so it doesn't come straight back. Bag weights are
	 * untouched: no removal credits a bean. Says how many were removed, and
	 * how many skipped.
	 */
	async freeUpSpace(ids: readonly string[]): Promise<{ removed: number; skipped: number }> {
		await this.whenReady();
		const wanted = [...new Set(ids)];
		if (this.mode !== 'idb' && this.mode !== 'legacy') return { removed: 0, skipped: wanted.length };
		const byId = this.byId();
		// An edit not yet sent to Visualizer, or a Decent upload to retry, would be lost everywhere (#437 N2).
		const edits = pendingEdits();
		const decentRetry = new Set(readDecentAccount().retryShotIds);
		const go = wanted.filter((id) => {
			const s = byId.get(id);
			return (
				s !== undefined &&
				s.deletedAt == null &&
				s.pinned !== true &&
				!uploadPending(id) &&
				!edits.has(id) &&
				!decentRetry.has(id)
			);
		});
		const remote = go.map((id) => byId.get(id)!.visualizerId).filter((v): v is string => !!v);
		// Once the store's delete has landed: a failed one leaves the rows, and pulls of them, as they were.
		const ok = await this.removeRows(go, () => {
			keepRemoteOutMany(remote);
			for (const id of go) dropCapture(id);
		});
		return { removed: ok ? go.length : 0, skipped: wanted.length - go.length };
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
		if (shot.visualizerId) this.pulled.add(shot.id);
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
	 * On `crema-history` every new row is kept, and nothing is dropped.
	 */
	insertMany(shots: readonly StoredShot[]): HistoryInsert<StoredShot> {
		const ids = new Set(this.shots.map((s) => s.id));
		const fresh = shots.filter((s) => !ids.has(s.id) && !!ids.add(s.id));
		if (fresh.length === 0) return { kept: [], dropped: 0 };
		const freshIds = new Set(fresh.map((s) => s.id));
		const combined = newestFirst([...fresh, ...this.shots]);
		if (this.mode !== 'legacy') {
			// Every row is kept (#310 P6w): none refused, none dropped.
			this.shots = combined;
			for (const s of fresh) this.dirtyDetails.add(s.id);
			this.persist();
			return { kept: this.shots.filter((s) => freshIds.has(s.id)), dropped: 0 };
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
			patch.backfillSamples === true && samples.length > 0 && heldSampleCount(next) === 0;
		if (backfill) {
			next = {
				...next,
				record: { duration: pulled.durationMs || next.record.duration, samples: [...samples] }
			};
		}
		// A light row's profile is in the store, not memory: the write only
		// fills, so a recipe the store holds stays (P4w).
		const fillProfile = patch.fillProfile && pulled.profile != null && next.profile == null;
		if (fillProfile) {
			next = { ...next, profile: pulled.profile };
		}
		if (patch.remoteVersion != null) next = { ...next, visualizerUpdatedAt: patch.remoteVersion };
		if (next === target) return;
		this.shots = [...this.shots.slice(0, idx), next, ...this.shots.slice(idx + 1)];
		if (backfill) this.backfillSeries(id, [...samples]);
		if (fillProfile && this.mode !== 'legacy') {
			this.dirtyDetails.add(id);
			this.fills.add(id);
		}
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
		// Only a row known to have none: an unknown count (`null`) isn't none.
		if (heldSampleCount(target) !== 0 || this.seriesPending(id)) return;
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
		if (!isManualLog(this.rowOf(shot))) return;
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

/**
 * A restore's plan for the history, over the rows as they are: the history it
 * leaves (rows passed through, or whole shots from the backup), the whole
 * shots whose heavy parts to write, and the ids it removes.
 */
export type RestoreRowsPlan = (rows: readonly ShotRow[]) => {
	next: readonly AnyShot[];
	series: readonly AnyShot[];
	remove?: readonly string[];
};

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
	// The ids frozen at shot start (#488, save-boundaries F4): kept, never
	// rebuilt from the snapshots. Omitted when absent, as the core writes them.
	const idOf = (v: unknown): string | null => (typeof v === 'string' && v.trim().length > 0 ? v : null);
	const grinderId = idOf(obj.grinderId);
	const profileId = idOf(obj.profileId);
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
		...(profileId ? { profileId } : {}),
		profile: (obj.profile as unknown) ?? null,
		stopReason: (obj.stopReason as unknown) ?? null,
		...(obj.stopEstimated === true ? { stopEstimated: true } : {}),
		metadata,
		record: recordField,
		bean,
		grinderModel,
		...(grinderId ? { grinderId } : {}),
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
		// The app's store waits for this tab's stage (#310 W5); tests set their own.
		store = new HistoryStore(stagedBootActive() ? { stage2: historyStage } : {});
	}
	return store;
}
