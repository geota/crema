/**
 * `#lib/restore/shots` — what a restore plan means for the shot rows, apart
 * from where they're stored (#310 R1w): the rows as the planner reads them,
 * and the history a plan leaves. The P3 store's restore runs them over its
 * rows in memory; the SQLite store's over the ids it reads by page.
 */
import type { RestorePlan } from '#lib/core/crema-core.js';
import type { StoredShot } from '#lib/history/model.js';
import { sampleCountOf, type AnyShot } from '#lib/history/model.js';
import { newestFirst } from '#lib/history/brew-date.js';

/** A row as the planner reads it (the core's `LocalShot`). */
export interface LocalShot {
	id: string;
	hasSamples: boolean;
	pinned: boolean;
	deleted: boolean;
}

/**
 * The rows as the planner reads them. A row's count is its summary's (#310
 * P4w); an unknown one (`null`, Android's rule for a series it couldn't read)
 * counts as a curve, which a fill must never replace.
 */
export function localShotsOf(rows: readonly AnyShot[]): LocalShot[] {
	return rows.map((s) => ({
		id: s.id,
		hasSamples: (sampleCountOf(s) ?? 1) > 0,
		pinned: s.pinned === true,
		deleted: s.deletedAt != null
	}));
}

/** The history a plan leaves, and the rows whose curve to write first. */
export function nextShots(
	local: readonly AnyShot[],
	backup: readonly StoredShot[],
	plan: RestorePlan['shots'],
	replace = false
): { next: AnyShot[]; series: StoredShot[] } {
	const theirs = new Map<string, StoredShot>();
	for (const s of backup) if (!theirs.has(s.id)) theirs.set(s.id, s);
	const remove = new Set(plan.remove);
	const here = new Set(local.map((s) => s.id));
	// A row the plan adds that has turned up here since (a pull's stub landing
	// during step 7) gets what the plan would have given it, had it been here:
	// Replace overwrites it; Merge keeps it, and fills its curve.
	const arrived = plan.add.filter((id) => here.has(id));
	const replaced = new Set([...plan.overwrite, ...plan.revive, ...(replace ? arrived : [])]);
	const fill = new Set([...plan.fill, ...(replace ? [] : arrived)]);
	const zero = new Set(plan.zeroDebit);
	const series: StoredShot[] = [];
	const kept = local
		.filter((s) => !remove.has(s.id))
		.map((s): AnyShot => {
			const b = theirs.get(s.id);
			if (!b) return s;
			if (replaced.has(s.id)) {
				// The backup's row. With no curve of its own it keeps the one here,
				// as the same shot's curve: the store merges a heavy part the
				// backup lacks from the row it holds (`keepHeavy`, and the legacy
				// backend's merge on write), so memory matches what a reload shows.
				series.push(b);
				return b;
			}
			// The backfill rule: the curve only, never over a curve here. A row
			// whose count is unknown (`null`) is never filled.
			if (fill.has(s.id) && sampleCountOf(s) === 0 && b.record.samples.length > 0) {
				const filled = withCurveOf(s, b);
				series.push(filled);
				return filled;
			}
			return s;
		});
	const added = plan.add
		.filter((id) => !here.has(id))
		.map((id) => theirs.get(id))
		.filter((s): s is StoredShot => s !== undefined)
		// A bean already here never paid for a shot from elsewhere.
		.map((s) => (zero.has(s.id) ? { ...s, bagDebit: 0 } : s));
	series.push(...added);
	return { next: newestFirst([...added, ...kept]), series };
}

/**
 * `row` with `backup`'s curve, as a whole shot for the store to fill: the
 * row's fields, the backup's samples, and no profile or brew series, which the
 * store keeps from what it holds.
 */
export function withCurveOf(row: AnyShot, backup: StoredShot): StoredShot {
	const { summary: _s, ...fields } = row as AnyShot & { summary?: unknown };
	const { brewSeries: _b, profile: _p, ...rest } = fields as StoredShot;
	return {
		...rest,
		profile: null,
		record: { duration: backup.record.duration || row.record.duration, samples: [...backup.record.samples] }
	};
}
