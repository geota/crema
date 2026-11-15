/**
 * Shared set-up for the `crema-history` tests (#310 P3w): a fresh in-memory
 * IndexedDB per test, the old copy written as an older build leaves it, and
 * readers that go to storage rather than to a store's memory.
 */
import { IDBFactory as FakeIDBFactory } from 'fake-indexeddb';
import type { TimedSample } from '#lib/core/index.js';
import { HistoryDb, type DetailsRecord, type MigrationRecord, type RowRecord, META_MIGRATION } from './history-db';
import { clearLight } from './light-rows';
import { putManySeries, getAllSeries } from './series-store';
import { HistoryStore, setHistoryStoreDefaultsForTest, type HistoryStoreOptions } from './store.svelte';
import { coerceStoredShot } from './store.svelte';
import type { StoredShot } from './model';
import { HISTORY_DB_NAME } from './store-names';

export const LEGACY_KEY = 'crema.history.v2';

/** Fresh storage, and store defaults that keep tests apart from the browser's. */
export function freshStorage(defaults: HistoryStoreOptions = {}): IDBFactory {
	localStorage.clear();
	const idb = new FakeIDBFactory() as unknown as IDBFactory;
	(globalThis as { indexedDB?: IDBFactory }).indexedDB = idb;
	clearLight();
	setHistoryStoreDefaultsForTest({ locks: null, channel: null, visibility: false, estimate: async () => undefined, ...defaults });
	return idb;
}

/**
 * The old copy as an older build writes it: the rows in `crema.history.v2`
 * without their series, the series in `crema-history-series`. `inline` rows
 * keep their samples in the blob, as the all-in-localStorage format did.
 */
export async function writeOldCopy(shots: readonly StoredShot[], inline: ReadonlySet<string> = new Set()): Promise<void> {
	const rows = shots.map((s) => (inline.has(s.id) ? s : { ...s, record: { ...s.record, samples: [] } }));
	localStorage.setItem(LEGACY_KEY, JSON.stringify(rows));
	await putManySeries(
		shots.filter((s) => !inline.has(s.id) && s.record.samples.length > 0).map((s) => ({ id: s.id, samples: s.record.samples }))
	);
}

/** The old copy's bytes: the blob, and every series by id. */
export async function oldCopyBytes(): Promise<{ blob: string | null; series: string }> {
	const series = await getAllSeries();
	return {
		blob: localStorage.getItem(LEGACY_KEY),
		series: JSON.stringify([...series.entries()].sort(([a], [b]) => a.localeCompare(b)))
	};
}

/** A connection of the test's own to `crema-history`, never creating it. */
export function rawDb(): HistoryDb {
	return new HistoryDb({ expectExisting: () => true });
}

/** What `crema-history` holds, read from storage. */
export async function stored(): Promise<{ rows: Map<string, RowRecord>; details: Map<string, DetailsRecord>; meta: MigrationRecord | undefined }> {
	const db = rawDb();
	try {
		const rows = new Map((await db.allRows()).map((r) => [r.id, r]));
		const details = await db.detailsOf(await db.detailIds());
		const meta = await db.meta<MigrationRecord>(META_MIGRATION);
		return { rows, details, meta };
	} finally {
		db.close();
	}
}

/** Whether the browser holds a `crema-history` database at all. */
export async function historyDbExists(idb: IDBFactory): Promise<boolean> {
	return (await idb.databases()).some((d) => d.name === HISTORY_DB_NAME);
}

/** A store, once its rows have loaded (or it settled as lost or unavailable). */
export async function boot(opts: HistoryStoreOptions = {}): Promise<HistoryStore> {
	const store = new HistoryStore(opts);
	await settled(store);
	return store;
}

/** Until the store's boot has ended, either way. */
export async function settled(store: HistoryStore, ms = 20_000): Promise<void> {
	const until = Date.now() + ms;
	while (store.health === 'loading') {
		if (Date.now() > until) throw new Error('the store never settled');
		await new Promise((r) => setTimeout(r, 2));
	}
	if (store.loaded) await store.seriesReady();
}

/** The shot as the store would hold it whole: coerced, with its series. */
export function whole(shot: StoredShot, samples?: readonly TimedSample[]): StoredShot {
	const c = coerceStoredShot(shot as unknown as Record<string, unknown>)!;
	return samples ? { ...c, record: { ...c.record, samples: [...samples] } } : c;
}

/** Strip keys whose value is `undefined`, as storage does on a JSON round trip. */
export function plain<T>(v: T): T {
	return JSON.parse(JSON.stringify(v)) as T;
}
