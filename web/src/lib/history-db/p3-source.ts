/**
 * `#lib/history-db/p3-source.js` — the P3 store, `crema-history`, as the
 * stage-1 index reads it in the owner's Worker (#310 W2, M4). IndexedDB works
 * in a dedicated Worker, so the owner reads committed rows itself.
 *
 * Read only, and never creates the database: it opens with
 * `expectExisting`, so a store that isn't there yet (a first boot before its
 * migration) aborts the upgrade, and no empty database appears in its place.
 */
import { HistoryDb, META_MIGRATION } from '#lib/history/history-db.js';
import type { P3Source } from './index-sync.js';

export function p3Source(opts: { idb?: () => IDBFactory | undefined; name?: string } = {}): P3Source {
	const db = new HistoryDb({ idb: opts.idb, name: opts.name, expectExisting: () => true });
	return {
		view: () => db.indexView(),
		async rowsOf(ids) {
			const found = await db.rowsOf(ids);
			const out = new Map<string, string>();
			for (const [id, row] of found) out.set(id, JSON.stringify(row));
			return out;
		},
		async rowsAfter(after, limit) {
			const rows = await db.rowsAfter(after, limit);
			return rows.map((r) => ({ id: r.id, rowJson: JSON.stringify(r) }));
		},
		ids: () => db.rowIds(),
		async migration() {
			const m = await db.meta<unknown>(META_MIGRATION);
			return m == null ? null : JSON.stringify(m);
		},
		close: () => db.close()
	};
}
