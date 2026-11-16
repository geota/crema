/**
 * The same history in both stores, for the cross-source tests (#310 R1w):
 * `crema-history` as a P3 boot finds it, and `history.db` with its rows in
 * reverse of the P3 store's order (the walk orders them itself) over the same
 * `details`. Not a test file.
 */
import { HistoryDb, META_MIGRATION } from '#lib/history/history-db.js';
import { writeDoneMarker } from '#lib/history/store-marker.js';
import { summarize } from '#lib/history/summary.js';
import { rawDb } from '#lib/history/idb-test-kit.js';
import { getHistoryStore } from '#lib/history/store.svelte.js';
import type { StoredShot } from '#lib/history/model.js';
import { SqliteSource } from './sqlite';
import { idbDetails } from './details';
import { directPort, freshFile, makeTruth, openTruth } from './sqlite-kit';

/** `shots` in `crema-history`, migrated, as a P3 boot finds it. */
export async function p3History(shots: readonly StoredShot[]): Promise<void> {
	const db = new HistoryDb({ expectExisting: () => false });
	await db.putShots(
		shots.map((shot) => ({ shot, details: true })),
		summarize
	);
	await db.setMeta(META_MIGRATION, { state: 'done', sourceHash: '0:0', sourceIds: shots.map((s) => s.id) });
	db.close();
	writeDoneMarker(1, shots.length);
	await getHistoryStore().whenReady();
}

/** `history.db` with the P3 store's rows, written in reverse, over its `details`. */
export async function sqliteOfP3(batch = 25): Promise<{ source: SqliteSource; port: ReturnType<typeof directPort> }> {
	const file = freshFile();
	await makeTruth(file);
	const truth = await openTruth(file);
	const port = directPort(truth);
	const ids = getHistoryStore().rawAll.map((s) => s.id).reverse();
	const rows = await rawDb().rowsOf(ids);
	for (let i = 0; i < ids.length; i += 100) {
		await port.write({
			kind: 'upsert',
			rows: ids.slice(i, i + 100).map((id) => ({ rowJson: JSON.stringify(rows.get(id)) }))
		});
	}
	const source = new SqliteSource({
		db: port,
		details: idbDetails(new HistoryDb({ expectExisting: () => true })),
		summarize,
		batch
	});
	return { source, port };
}
