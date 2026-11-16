/**
 * `#lib/history/sync-view.js` — what sync and the upload passes read on stage
 * 1 (#310 W3): P3w's rows in memory, every one, tombstones too.
 *
 * **Why not the index's projection** (as Android's A3a, #467): the planners
 * read what `sync_projection` doesn't carry. `reconcileShots` reads each
 * row's final weight, `knownRowsOf` its `visualizerUpdatedAt`, and
 * `mergeInputsOf` its tags and barista. And on stage 1 the index trails P3w:
 * the owner reads committed rows after a notice, so a shot this tab just
 * recorded or bound can be missing from it, and a planner would read it as
 * absent (a second upload, or a pulled copy of a shot this device holds).
 * P3w's rows are whole by construction: read in one step, with nothing
 * awaited between the read and the plan, and the apply runs against the same
 * rows. W5 moves sync onto `history.db` through the projection walk
 * (`collectSyncProjection`, S3) once the core's projection has those
 * columns, as A5 must.
 *
 * In dev and tests, each read here is checked against the index's
 * projection, collected whole, while the index is fresh (at most every
 * {@link CHECK_EVERY_MS}): the same ids, remote ids, times, durations,
 * tombstones and Brew Log flags, and no held row.
 */
import { historyDb } from '#lib/history-db/start.js';
import { ORACLE_ON } from '#lib/history-db/oracle.js';
import { collectSyncProjection, type SyncRowOut } from '#lib/history-db/sync-projection.js';
import { isBrewLog, type ShotRow } from './model';
import { HistoryReadMismatch, whileFresh } from './reads.svelte';
import { p3RowsOf } from './p3-rows';
import type { HistoryStore } from './store.svelte';

/** The dev check runs at most this often: a 20,000-row walk is a few hundred ms. */
export const CHECK_EVERY_MS = 30_000;

/** Every row this device holds, tombstones too: the pull's planners and its apply. */
export function syncRows(history: HistoryStore): readonly ShotRow[] {
	const rows = p3RowsOf(history).raw;
	if (ORACLE_ON) checkSoon(history);
	return rows;
}

/** Every live row, newest first: the upload backlogs. */
export function liveSyncRows(history: HistoryStore): readonly ShotRow[] {
	const rows = p3RowsOf(history).live;
	if (ORACLE_ON) checkSoon(history);
	return rows;
}

/** The row bound to Visualizer id `remoteId`, tombstone or not. */
export function rowBoundTo(history: HistoryStore, remoteId: string): ShotRow | undefined {
	return syncRows(history).find((s) => s.visualizerId === remoteId);
}

// ── The dev check ───────────────────────────────────────────────────────

let lastCheck = -Infinity;
let checking: Promise<void> | null = null;
/** The checks that ran and their mismatches, for the tests. */
export const syncViewChecks = { compared: 0, mismatches: [] as HistoryReadMismatch[] };

/** Test seam: check on every read. */
export function resetSyncViewCheck(): void {
	lastCheck = -Infinity;
	checking = null;
	syncViewChecks.compared = 0;
	syncViewChecks.mismatches.length = 0;
}

/** Until the check in flight, if any, is done. */
export async function syncViewChecked(): Promise<void> {
	await checking;
}

function checkSoon(history: HistoryStore): void {
	const now = Date.now();
	if (checking || now - lastCheck < (import.meta.env.MODE === 'test' ? 0 : CHECK_EVERY_MS)) return;
	const db = historyDb();
	if (!db) return;
	lastCheck = now;
	checking = (async () => {
		try {
			await whileFresh(
				{ db, store: history },
				(read) => collectSyncProjection(read),
				(p) => compare(p.rows, p3RowsOf(history).raw)
			);
		} finally {
			checking = null;
		}
	})();
}

const lineOf = (r: {
	id: string;
	visualizerId: string | null;
	completedAt: number;
	duration: number;
	deletedAt: number | null;
	brew: boolean;
}) => `${r.id}|${r.visualizerId ?? ''}|${Math.round(r.completedAt)}|${Math.round(r.duration)}|${r.deletedAt ?? ''}|${r.brew}`;

function compare(index: readonly SyncRowOut[], p3: readonly ShotRow[]): void {
	syncViewChecks.compared++;
	const held = index.filter((r) => r.held);
	const a = new Set(
		index
			.filter((r) => !r.held)
			.map((r) =>
				lineOf({
					id: r.id,
					visualizerId: r.visualizerId,
					completedAt: r.completedAt,
					duration: r.durationMs,
					deletedAt: r.deletedAt,
					brew: r.isBrewLog
				})
			)
	);
	const b = new Set(
		p3.map((s) =>
			lineOf({
				id: s.id,
				visualizerId: s.visualizerId ?? null,
				completedAt: s.completedAt,
				duration: s.record.duration,
				deletedAt: s.deletedAt ?? null,
				brew: isBrewLog(s)
			})
		)
	);
	const missing = [...b].filter((l) => !a.has(l));
	const extra = [...a].filter((l) => !b.has(l));
	// Stage 1 runs no export walk on the index, so a held row there is a bug too.
	if (missing.length === 0 && extra.length === 0 && held.length === 0) return;
	const m = new HistoryReadMismatch(
		'syncProjection',
		`missing ${missing.slice(0, 2).join(', ') || 'none'}; extra ${extra.slice(0, 2).join(', ') || 'none'}; held ${held.length}`
	);
	syncViewChecks.mismatches.push(m);
	console.error(m.message);
	queueMicrotask(() => {
		throw m;
	});
}
