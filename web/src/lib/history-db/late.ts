/**
 * `#lib/history-db/late.js` — late rows, at every stage-2 open, before the
 * owner answers (#310 SPEC §11 steps 8 and 9, PLAN W5, M9, S13, REVIEW-426
 * note 2, REVIEW-442 note 9).
 *
 * After the flip nothing of this build writes `crema-history`'s rows. Only an
 * older build does (a rollback, an old tab), or this build's own capture
 * during the flip (M9). So:
 *
 * - **Step 8.** Read the P3 store's `writeSeq` and row count, then its ids.
 *   If either moved since `meta.late_baseline` (a `writeSeq` below it, or a
 *   count that differs, counts as moved: a factory reset that kept the pools
 *   recreates `crema-history` from 0), the rows whose ids `migration_source`
 *   lacks go to the core's `importLate`: inserted only if `history.db` has no
 *   row with that id (held ones too) and no row holds their `visualizer_id`,
 *   every id then joining `migration_source`, so each imports once and a row
 *   deleted here never comes back. The last batch moves the baseline to what
 *   was read first.
 * - **Step 9.** The late rows that came with no samples (`meta.late_ids`) are
 *   read again; a copy that has them now replaces the row.
 *
 * The P3 store is only read. The pre-P3 store's late rows and P3's own
 * pending journal live in `localStorage`, which a Worker can't read: the
 * page's store imports those by the same rule (`importLate`), and keeps P3's
 * own record of them in `history.db` (`p3_migration`, S13).
 */
import type { EngineDb } from './engine.js';
import type { P3Source } from './index-sync.js';
import type { ChangeSet } from './protocol.js';

/** Rows per `importLate` call: short transactions (S15). */
export const LATE_BATCH = 200;

/** The core's `LateImport`. */
export interface LateImport {
	imported: string[];
	known: string[];
	duplicates: string[];
	change: ChangeSet;
}

/** `meta.late_baseline`, as the flip and a recovery write it. */
interface Baseline {
	writeSeq: number;
	rows?: number;
}

function parseBaseline(text: unknown): Baseline | null {
	if (typeof text !== 'string') return null;
	try {
		const v = JSON.parse(text) as Partial<Baseline> | null;
		return v && typeof v.writeSeq === 'number' ? { writeSeq: v.writeSeq, ...(typeof v.rows === 'number' ? { rows: v.rows } : {}) } : null;
	} catch {
		return null;
	}
}

export interface LateDeps {
	/** `history.db`, open. */
	db: EngineDb;
	source: P3Source;
	notify(change: ChangeSet): void;
	log?(line: string): void;
}

/** What one run did, for diagnostics and tests. */
export interface LateRun {
	moved: boolean;
	imported: string[];
	duplicates: string[];
	filled: number;
}

/** Steps 8 and 9 (see the module). */
export async function lateRows(d: LateDeps): Promise<LateRun> {
	const run: LateRun = { moved: false, imported: [], duplicates: [], filled: 0 };
	let view: { writeSeq: number; rows: number; done: boolean };
	try {
		view = await d.source.view();
	} catch {
		// No P3 store (a fresh browser profile after a restore, say): nothing late.
		return run;
	}
	const base = parseBaseline(await d.db.call('metaValue', 'late_baseline'));
	const now: Baseline = { writeSeq: view.writeSeq, rows: view.rows };
	run.moved = !base || view.writeSeq !== base.writeSeq || (base.rows !== undefined && view.rows !== base.rows);
	if (run.moved) {
		const known = new Set((await d.db.call('migrationSourceIds')) as string[]);
		const ids = (await d.source.ids()).filter((id) => !known.has(id));
		const baseline = JSON.stringify(now);
		if (ids.length === 0) {
			const out = (await d.db.call('importLate', { rows: [], baseline })) as LateImport;
			d.notify(out.change);
		}
		for (let i = 0; i < ids.length; i += LATE_BATCH) {
			const part = ids.slice(i, i + LATE_BATCH);
			const found = await d.source.rowsOf(part);
			const rows = part.filter((id) => found.has(id)).map((id) => ({ rowJson: found.get(id)! }));
			const last = i + LATE_BATCH >= ids.length;
			const out = (await d.db.call('importLate', { rows, ...(last ? { baseline } : {}) })) as LateImport;
			d.notify(out.change);
			run.imported.push(...out.imported);
			run.duplicates.push(...out.duplicates);
		}
		if (run.imported.length > 0) d.log?.(`late rows: ${run.imported.length} imported from the P3 store`);
	}
	// Step 9: the series an older build wrote after the row.
	const late = (await d.db.call('lateIds')) as string[];
	if (late.length > 0) {
		const found = await d.source.rowsOf(late);
		if (found.size > 0) {
			const w = (await d.db.call('settleLate', [...found.values()].map((rowJson) => ({ rowJson })))) as { change: ChangeSet };
			d.notify(w.change);
			run.filled = found.size;
		}
	}
	return run;
}
