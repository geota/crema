/**
 * `#lib/history-source/p3.js` — the P3 store behind the backup seam (#310
 * R1w): exactly what the exporter and the restore engine did before the seam.
 * A backup snapshots every live row, whole, at its start; a restore's step 7
 * runs the plan over the rows in memory (`restoreRows`).
 *
 * On stage 1 (#310 W3) a backup and a restore's local state read P3w's rows
 * here, not the index: P3w is the truth, and the index trails it, so a
 * backup read from it could leave out a shot just recorded. W5 switches the
 * seam to the SQLite source (`sqlite.ts`), which reads `history.db`.
 */
import { getHistoryStore } from '#lib/history/index.js';
import { p3RowsOf } from '#lib/history/p3-rows.js';
import { deleteSeries } from '#lib/history/series-store.js';
import { localShotsOf, nextShots } from '#lib/restore/shots.js';
import type { ExportShots, HistorySource } from './types';

export const p3Source: HistorySource = {
	kind: 'p3',
	seriesReady: () => getHistoryStore().seriesReady(),
	get seriesFailed() {
		return getHistoryStore().seriesFailed;
	},
	liveCount() {
		const history = getHistoryStore();
		// Every live row, past the view too (#310). None counted until they load.
		return history.loaded ? p3RowsOf(history).live.length : 0;
	},
	async exportShots(): Promise<ExportShots> {
		const history = getHistoryStore();
		// Every live row, the ones past the view too, each whole (#310 P3w). A
		// snapshot: a shot saved during a long walk is in the next backup.
		const shots = [...(await history.loadMany(p3RowsOf(history).live.map((s) => s.id)))];
		return {
			total: shots.length,
			async *pass() {
				yield* shots;
			},
			close: async () => {}
		};
	},
	async writeSeq() {
		return getHistoryStore().writeSeqNow();
	},
	whenReady: () => getHistoryStore().whenReady(),
	rowsHealth: () => getHistoryStore().rowsHealth(),
	get restoreCapacity() {
		return getHistoryStore().restoreCapacity;
	},
	async localShots() {
		return localShotsOf(p3RowsOf(getHistoryStore()).raw);
	},
	pauseDeletes: () => getHistoryStore().pauseDeletes(),
	restoreShots: ({ plan, backup, replace, writeSeries }) =>
		getHistoryStore().restoreRows(
			(rows) => ({ ...nextShots(rows, backup, plan, replace), remove: plan.remove }),
			writeSeries
		),
	async readyWithin() {
		const history = getHistoryStore();
		if (!(await history.readyWithin())) return false;
		await history.seriesReady();
		return true;
	},
	async listedIds() {
		return new Set(p3RowsOf(getHistoryStore()).raw.map((s) => s.id));
	},
	// On `crema-history` a row's details went with its explicit delete, and
	// the old copy is never touched (SPEC §6): only the legacy backend's series go.
	deleteOwed: (id) => (getHistoryStore().keepsEveryRow ? Promise.resolve() : deleteSeries(id))
};
