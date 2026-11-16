/**
 * `#lib/history-source/types.js` — the backup seam's shapes (#310 R1w), apart
 * from the registry in `source.ts`, so a page that runs only the SQLite side
 * doesn't load the P3 store.
 */
import type { RestorePlan } from '#lib/core/crema-core.js';
import type { StoredShot } from '#lib/history/model.js';
import type { StoreHealth } from '#lib/utils/storage.js';
import type { LocalShot } from '#lib/restore/shots.js';

/** The shots one backup writes, read twice in the same order: once to count, once to write. */
export interface ExportShots {
	/** How many shots each pass yields: the walk's count, before any is read. */
	readonly total: number;
	/**
	 * The shots, whole, in the backup's order. Called twice; both passes yield
	 * the same ids in the same order, or the second throws {@link ShotsMoved}.
	 */
	pass(): AsyncIterable<StoredShot>;
	/** Done, written or not: end the walk and let go of what it held. Never throws. */
	close(): Promise<void>;
}

/** The shots moved between a backup's two passes: nothing was kept, and a new try may pass. */
export class ShotsMoved extends Error {
	override name = 'ShotsMoved';
	constructor() {
		super('A shot changed while it was being backed up. Try again.');
	}
}

/** What step 7 of a restore writes. */
export interface RestoreShotsInput {
	/** The plan's shots. */
	plan: RestorePlan['shots'];
	/** The backup's shots the plan writes, decoded. */
	backup: readonly StoredShot[];
	/** Every shot id in the backup, from the validation pass: none of them is ever removed (M3). */
	backupIds: ReadonlySet<string>;
	/** Replace (`wipe`), not Merge. */
	replace: boolean;
	/** Test seam: the P3 legacy backend's series writes. */
	writeSeries?: (rows: readonly StoredShot[]) => Promise<void>;
}

export interface RestoredShots {
	/** The rows landed, and every delete the plan named that this store may make. */
	stored: boolean;
	curvesFailed: number;
}

export interface HistorySource {
	readonly kind: 'p3' | 'sqlite';

	// ── Backup ─────────────────────────────────────────────────────────────

	/** Rows and curves are in: false means a backup now would miss them. */
	seriesReady(): Promise<boolean>;
	/** Curves couldn't be read this session. */
	readonly seriesFailed: boolean;
	/** Live shots a backup would hold, for the summary before it; 0 until known. */
	liveCount(): number;
	/** The live shots for one backup. The caller always calls `close`. */
	exportShots(): Promise<ExportShots>;
	/**
	 * The store's write counter, or null where it has none. An auto-backup's
	 * "changed?" skips its hash when this hasn't moved since its last success
	 * (SPEC §9).
	 */
	writeSeq(): Promise<number | null>;

	// ── Restore ────────────────────────────────────────────────────────────

	/** Loaded: not loaded is not empty. */
	whenReady(): Promise<void>;
	/** The rows' health, for the plan. */
	rowsHealth(): StoreHealth;
	/** The cap a restore plans with; null with none. */
	readonly restoreCapacity: number | null;
	/** Every row, tombstones too, as the planner reads it. */
	localShots(): Promise<LocalShot[]>;
	/** Deletes off for a restore's run (the P3 store's); the returned function turns them back on. */
	pauseDeletes(): () => void;
	/** Step 7: series, then rows, then the deletes the plan names. */
	restoreShots(input: RestoreShotsInput): Promise<RestoredShots>;

	// ── A restore's owed deletes (the marker, `restore/marker.ts`) ─────────

	/** Loaded within the gate's wait: the owed deletes go by "not listed", which means nothing before. */
	readyWithin(): Promise<boolean>;
	/** Every id the store lists, tombstones and rows a walk holds too. */
	listedIds(): Promise<ReadonlySet<string>>;
	/** The heavy parts of a removed shot no row lists any more. */
	deleteOwed(id: string): Promise<void>;
}
