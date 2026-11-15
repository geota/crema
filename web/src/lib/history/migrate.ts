/**
 * `#lib/history/migrate.js` — move the history from `localStorage` and
 * `crema-history-series` into `crema-history` (#310 P3w, SPEC §6).
 *
 * | Step | What                                                                 |
 * | ---- | -------------------------------------------------------------------- |
 * | 1    | Marker `done`: open the store. No marker but `meta` says done: write the marker, never clear |
 * | 2    | Read `crema.history.v2` (`readStore`, `Array.isArray`). Absent: done with 0. Corrupt: kept aside, done |
 * | 3    | `estimate()` must show 1.5 × the source free                          |
 * | 4    | Clear `rows`, `details`, `meta`: a restart never builds on a partial copy |
 * | 5    | `meta.migration = { copying, sourceHash, sourceIds }`; a repeated id keeps the first row |
 * | 6    | Batches of 25: coerce, read the old series by id (inline wins), summary, row and details in one transaction. A series read error aborts |
 * | 7    | Verify everything, read back. Pass: `done` in `meta`, then the marker |
 * | 8    | At boot: rows an old tab added to the blob since, by id, not doubling a `visualizerId` we hold |
 * | 9    | At boot: the late rows that arrived without a series look for it again |
 *
 * Nothing here writes or deletes the old copy, and only a passed check sets
 * `done`, so a crash at any point means the next boot clears and starts
 * again, with the same result.
 */
import type { TimedSample } from '#lib/core/index.js';
import type { ShotSummary } from '#lib/core/crema-core.js';
import { keepSkippedRows, readStore, type StoreRead } from '#lib/utils/storage.js';
import { signatureForRow, signatureForShot } from '#lib/visualizer/shot-sync-signatures.js';
import { DB_NAME as OLD_SERIES_DB } from './series-store';
import {
	META_MIGRATION,
	detailsOf,
	lightOf,
	rowOf,
	type HistoryDb,
	type MigrationRecord,
	type RowRecord
} from './history-db';
import type { StoredShot } from './model';

/** The old blob's key. Read only, here. */
export const LEGACY_KEY = 'crema.history.v2';
const LEGACY_WHAT = 'shot history';
export const MIGRATION_BATCH = 25;

/** The old copy, as the migration reads it. */
export interface LegacySource {
	/** The blob's text, or null when absent. Throws when storage can't be read. */
	raw(): string | null;
	/** The blob through `readStore`: a corrupt one is kept aside, as today. */
	read(): StoreRead<unknown[]>;
	/** The old series of `ids` that exist. Throws on a read error; absent is fine. */
	series(ids: readonly string[]): Promise<Map<string, TimedSample[]>>;
}

export interface MigrationDeps {
	db: HistoryDb;
	source: LegacySource;
	summarize: (shot: StoredShot) => ShotSummary;
	/** Free space, from `navigator.storage.estimate()`. Undefined: not known, not checked. */
	estimate?: () => Promise<{ quota?: number; usage?: number } | undefined>;
	now?: () => number;
	/** Test seam: runs at each step and batch boundary, and may throw or never return. */
	fault?: (point: string) => void | Promise<void>;
	batch?: number;
}

export type CopyOutcome =
	| { kind: 'done'; rows: number; duplicates: number; skipped: number }
	| { kind: 'failed'; error: string };

/** A stable hash of the blob's text (cyrb53). */
export function hashText(text: string): string {
	let h1 = 0xdeadbeef;
	let h2 = 0x41c6ce57;
	for (let i = 0; i < text.length; i++) {
		const ch = text.charCodeAt(i);
		h1 = Math.imul(h1 ^ ch, 2654435761);
		h2 = Math.imul(h2 ^ ch, 1597334677);
	}
	h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
	h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
	return `${text.length}:${(4294967296 * (2097151 & h2) + (h1 >>> 0)).toString(36)}`;
}

/** The blob's items as shots, first of a repeated id kept; unreadable ones kept aside. */
export function sourceShots(
	items: readonly unknown[],
	coerce: (obj: Record<string, unknown>) => StoredShot | null
): { shots: StoredShot[]; duplicates: number; skipped: unknown[] } {
	const shots: StoredShot[] = [];
	const seen = new Set<string>();
	const skipped: unknown[] = [];
	let duplicates = 0;
	for (const item of items) {
		const obj = typeof item === 'object' && item !== null ? (item as Record<string, unknown>) : null;
		const shot = obj && typeof obj.id === 'string' ? coerce(obj) : null;
		if (!shot) {
			skipped.push(item);
			continue;
		}
		if (seen.has(shot.id)) {
			duplicates += 1;
			continue;
		}
		seen.add(shot.id);
		shots.push(shot);
	}
	return { shots, duplicates, skipped };
}

/** `shot` with its old series, unless it carries samples inline (those win). */
export function withOldSeries(shot: StoredShot, series: ReadonlyMap<string, TimedSample[]>): StoredShot {
	if (shot.record.samples.length > 0) return shot;
	const old = series.get(shot.id);
	return old && old.length > 0 ? { ...shot, record: { ...shot.record, samples: old } } : shot;
}

const errorText = (e: unknown): string => (e instanceof Error ? e.message : String(e));

/** Deep equality where a missing key and an `undefined` one are the same. */
export function sameValue(a: unknown, b: unknown): boolean {
	if (a === b) return true;
	if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') {
		return Number.isNaN(a) && Number.isNaN(b);
	}
	if (Array.isArray(a) !== Array.isArray(b)) return false;
	if (Array.isArray(a)) {
		const bb = b as unknown[];
		return a.length === bb.length && a.every((v, i) => sameValue(v, bb[i]));
	}
	const ao = a as Record<string, unknown>;
	const bo = b as Record<string, unknown>;
	const keys = new Set([...Object.keys(ao), ...Object.keys(bo)]);
	for (const k of keys) if (!sameValue(ao[k], bo[k])) return false;
	return true;
}

/** Why a verify failed: which check, and on which id. */
export class VerifyFailed extends Error {
	constructor(
		readonly check: 'ids' | 'row' | 'details' | 'sampleCount' | 'signature',
		readonly id: string | null
	) {
		super(`The copied history didn't match the original (${check}${id ? `, ${id}` : ''}).`);
		this.name = 'VerifyFailed';
	}
}

/**
 * Step 7: read everything back. Every check must hold:
 * - the id sets of the source, `rows` and `details` are equal, both ways;
 * - each row equals its coerced source, ignoring `summary`;
 * - `details` deep-equals the source's samples, profile and brew series;
 * - `summary.sampleCount` is the source's sample count;
 * - the old shot's de-dup signature is the row's.
 * Read in batches, so a large history isn't held whole. Throws {@link VerifyFailed}.
 */
export async function verifyCopy(
	db: HistoryDb,
	source: readonly StoredShot[],
	series: LegacySource['series'],
	batch = MIGRATION_BATCH
): Promise<void> {
	const ids = new Set(source.map((s) => s.id));
	const rowIds = new Set((await db.allRows()).map((r) => r.id));
	const detailIds = new Set(await db.detailIds());
	for (const set of [rowIds, detailIds]) {
		if (set.size !== ids.size) throw new VerifyFailed('ids', null);
		for (const id of set) if (!ids.has(id)) throw new VerifyFailed('ids', id);
	}
	for (let i = 0; i < source.length; i += batch) {
		const part = source.slice(i, i + batch);
		const partIds = part.map((s) => s.id);
		const old = await series(partIds.filter((id) => (part.find((s) => s.id === id)?.record.samples.length ?? 0) === 0));
		const rows = await db.rowsOf(partIds);
		const details = await db.detailsOf(partIds);
		for (const src of part) {
			const full = withOldSeries(src, old);
			const row = rows.get(src.id);
			const det = details.get(src.id);
			if (!row || !det) throw new VerifyFailed('ids', src.id);
			const { summary, ...stored } = row;
			const { summary: _none, ...expected } = rowOf(full, summary);
			if (!sameValue(stored, expected)) throw new VerifyFailed('row', src.id);
			if (!sameValue(det, detailsOf(full))) throw new VerifyFailed('details', src.id);
			if ((summary.sampleCount ?? -1) !== full.record.samples.length) {
				throw new VerifyFailed('sampleCount', src.id);
			}
			if (signatureForShot(full) !== signatureForRow(lightOf(full), summary)) {
				throw new VerifyFailed('signature', src.id);
			}
		}
	}
}

/**
 * Steps 2 to 7, for a store that isn't `done`. Leaves `meta` at `done` on a
 * pass; on a failure clears the store, so it holds no disk, and says why.
 * A throw from the fault seam that isn't a failure (a test's kill) is left
 * to the caller, with the store as it was.
 */
export async function copyLegacy(
	deps: MigrationDeps,
	coerce: (obj: Record<string, unknown>) => StoredShot | null
): Promise<CopyOutcome> {
	const { db, source } = deps;
	const now = deps.now ?? Date.now;
	const batch = deps.batch ?? MIGRATION_BATCH;
	const fault = async (point: string) => {
		await deps.fault?.(point);
	};
	try {
		// 2. The blob, as `loadShots` reads it.
		await fault('read');
		const raw = source.raw();
		const read = source.read();
		if (read.kind === 'unavailable') throw new Error(`The old history couldn't be read (${read.error}).`);
		if (read.kind !== 'loaded') {
			// Absent, or corrupt and kept aside (series DB left alone): nothing to copy.
			await fault('clear');
			await db.clearAll();
			await fault('done');
			await db.setMeta(META_MIGRATION, {
				state: 'done',
				sourceHash: hashText(raw ?? ''),
				sourceIds: [],
				doneAt: now()
			} satisfies MigrationRecord);
			return { kind: 'done', rows: 0, duplicates: 0, skipped: 0 };
		}
		const { shots, duplicates, skipped } = sourceShots(read.value, coerce);
		keepSkippedRows(LEGACY_KEY, skipped, LEGACY_WHAT);

		// 3. Room for the copy, with half again to spare.
		await fault('estimate');
		const est = await deps.estimate?.().catch(() => undefined);
		if (est && typeof est.quota === 'number' && typeof est.usage === 'number') {
			// Chrome stores a shot's series and profile in about 20 KB.
			const need = 1.5 * ((raw?.length ?? 0) * 2 + shots.length * 20_000);
			if (est.quota - est.usage < need) {
				throw new DOMException('Not enough free space to move the history.', 'QuotaExceededError');
			}
		}

		// 4. Never build on a partial copy.
		await fault('clear');
		await db.clearAll();

		// 5. What's being copied.
		await fault('copying');
		const record: MigrationRecord = {
			state: 'copying',
			sourceHash: hashText(raw ?? ''),
			sourceIds: shots.map((s) => s.id)
		};
		await db.setMeta(META_MIGRATION, record);

		// 6. In batches: the old series first, outside the write.
		for (let i = 0; i < shots.length; i += batch) {
			const part = shots.slice(i, i + batch);
			await fault(`batch:${i / batch}`);
			const old = await source.series(part.filter((s) => s.record.samples.length === 0).map((s) => s.id));
			await db.putShots(
				part.map((s) => ({ shot: withOldSeries(s, old), details: true })),
				deps.summarize
			);
		}

		// 7. Read it all back.
		await fault('verify');
		await verifyCopy(db, shots, source.series, batch);
		await fault('done');
		await db.setMeta(META_MIGRATION, { ...record, state: 'done', doneAt: now() } satisfies MigrationRecord);
		return { kind: 'done', rows: shots.length, duplicates, skipped: skipped.length };
	} catch (e) {
		if (e instanceof TestKill) throw e;
		try {
			await db.clearAll();
		} catch {
			// It stays; the next try clears first anyway.
		}
		return { kind: 'failed', error: errorText(e) };
	}
}

/** Test seam: a fault that stands for the tab being killed, not a failure. */
export class TestKill extends Error {
	constructor(readonly point: string) {
		super(`killed at ${point}`);
		this.name = 'TestKill';
	}
}

/** What steps 8 and 9 found. */
export interface LateRows {
	/** Rows to add, with their old series where it was there. */
	add: StoredShot[];
	/** Late rows that now have a series: to fill. */
	fill: StoredShot[];
	/** The migration record to store once they're written. */
	record: MigrationRecord;
	changed: boolean;
}

/**
 * Steps 8 and 9, at boot on a `done` store: what an old tab, or a rolled-back
 * build, put in the blob since. A row whose id the copy or an earlier boot
 * already took is never taken again, so an edit there loses to the new store
 * and a shot deleted here doesn't come back. A row matching a `visualizerId`
 * we hold is a pull's copy of one we have. `held` are the rows loaded.
 */
export async function findLateRows(
	source: LegacySource,
	record: MigrationRecord,
	held: readonly StoredShot[],
	coerce: (obj: Record<string, unknown>) => StoredShot | null
): Promise<LateRows> {
	const sourceIds = new Set(record.sourceIds);
	let lateIds = new Set(record.lateIds ?? []);
	let sourceHash = record.sourceHash;
	const add: StoredShot[] = [];
	const fill: StoredShot[] = [];
	const raw = source.raw();
	const hash = hashText(raw ?? '');
	if (hash !== record.sourceHash && raw != null) {
		let items: unknown = null;
		try {
			items = JSON.parse(raw);
		} catch {
			items = null;
		}
		if (Array.isArray(items)) {
			const { shots } = sourceShots(items, coerce);
			const heldIds = new Set(held.map((s) => s.id));
			const boundTo = new Set(held.map((s) => s.visualizerId).filter((v): v is string => !!v));
			const fresh = shots.filter(
				(s) => !sourceIds.has(s.id) && !heldIds.has(s.id) && !(s.visualizerId && boundTo.has(s.visualizerId))
			);
			const old = await source.series(fresh.filter((s) => s.record.samples.length === 0).map((s) => s.id));
			for (const s of fresh) {
				const full = withOldSeries(s, old);
				add.push(full);
				sourceIds.add(s.id);
				if (full.record.samples.length === 0 && full.brewMethod == null) lateIds.add(s.id);
			}
			sourceHash = hash;
		}
	}
	// 9. The old tab writes a series after its row: it may have landed since.
	const waiting = [...lateIds].filter((id) => !add.some((s) => s.id === id));
	if (waiting.length > 0) {
		const old = await source.series(waiting);
		const byId = new Map(held.map((s) => [s.id, s]));
		for (const id of waiting) {
			const samples = old.get(id);
			const row = byId.get(id);
			if (!row) {
				// Deleted here since: nothing to fill.
				lateIds.delete(id);
				continue;
			}
			if (samples && samples.length > 0) {
				fill.push({ ...row, record: { ...row.record, samples } });
				lateIds.delete(id);
			}
		}
	}
	lateIds = new Set([...lateIds]);
	const next: MigrationRecord = {
		...record,
		sourceHash,
		sourceIds: [...sourceIds],
		lateIds: [...lateIds]
	};
	const changed =
		add.length > 0 ||
		fill.length > 0 ||
		sourceHash !== record.sourceHash ||
		(record.lateIds ?? []).length !== lateIds.size;
	return { add, fill, record: next, changed };
}

/** The real old copy: the blob and `crema-history-series`, read only. */
export function realLegacySource(idb: () => IDBFactory | undefined = () =>
	typeof indexedDB === 'undefined' ? undefined : indexedDB
): LegacySource {
	return {
		raw: () => (typeof localStorage === 'undefined' ? null : localStorage.getItem(LEGACY_KEY)),
		read: () => readStore<unknown[]>(LEGACY_KEY, { what: LEGACY_WHAT, valid: Array.isArray }),
		series: (ids) => readOldSeries(idb, ids)
	};
}

/**
 * The old series of `ids`, opened without creating anything: a database that
 * isn't there reads as empty, and isn't made.
 */
export async function readOldSeries(
	idbOf: () => IDBFactory | undefined,
	ids: readonly string[]
): Promise<Map<string, TimedSample[]>> {
	const out = new Map<string, TimedSample[]>();
	const idb = idbOf();
	if (ids.length === 0 || !idb) return out;
	const db = await new Promise<IDBDatabase | null>((resolve, reject) => {
		let absent = false;
		const req = idb.open(OLD_SERIES_DB);
		req.onupgradeneeded = (event) => {
			if (event.oldVersion === 0) {
				absent = true;
				req.transaction?.abort();
			}
		};
		req.onsuccess = () => {
			if (absent) {
				req.result.close();
				resolve(null);
			} else resolve(req.result);
		};
		req.onerror = () => (absent ? resolve(null) : reject(req.error ?? new Error('The old series could not be read.')));
	});
	if (!db) return out;
	try {
		if (!db.objectStoreNames.contains('series')) return out;
		const store = db.transaction('series', 'readonly').objectStore('series');
		const got = await Promise.all(
			ids.map(
				(id) =>
					new Promise<unknown>((resolve, reject) => {
						const r = store.get(id);
						r.onsuccess = () => resolve(r.result);
						r.onerror = () => reject(r.error);
					})
			)
		);
		got.forEach((v, i) => {
			if (Array.isArray(v)) out.set(ids[i], v as TimedSample[]);
		});
		return out;
	} finally {
		db.close();
	}
}

/** The rows a light load gives, as shots, with their summaries. */
export type LoadedRow = { shot: StoredShot; summary: ShotSummary };
export type { RowRecord };
