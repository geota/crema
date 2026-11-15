/**
 * `#lib/history/free-up.js` — what Free up space would remove (#310 P6w,
 * SPEC §8): the shots older than a date, but never a pinned one. Previewed
 * with counts and a size before anything goes, and only ever run by the user.
 */
import { brewSampleCountOf, sampleCountOf, type AnyShot } from './model';

/**
 * What one stored sample takes, about: its record in `crema-history` and its
 * share of the curve archive file. Measured in Chrome at 5,000 and 20,000 real-shaped
 * 180-sample shots, about 29–30 KB a shot in all (#310 P6w); a guide for the preview, not an exact count.
 */
export const BYTES_PER_SAMPLE = 160;

/** What Free up space would do. */
export interface FreeUpPlan {
	/** The shots it would remove, oldest first. */
	ids: string[];
	/** About how many bytes that frees. */
	bytes: number;
	/** Pinned shots older than the date, which stay. */
	pinnedKept: number;
}

/** About the bytes one row takes, with its curve. */
export function rowBytes(row: AnyShot): number {
	const samples = (sampleCountOf(row) ?? 0) + brewSampleCountOf(row);
	return JSON.stringify(row).length + samples * BYTES_PER_SAMPLE;
}

/** The live, unpinned rows dated before `before` (unix ms), and what they take. */
export function freeUpPlan(rows: readonly AnyShot[], before: number): FreeUpPlan {
	const ids: string[] = [];
	let bytes = 0;
	let pinnedKept = 0;
	for (const r of rows) {
		if (r.deletedAt != null || !(r.completedAt < before)) continue;
		if (r.pinned === true) {
			pinnedKept += 1;
			continue;
		}
		ids.push(r.id);
		bytes += rowBytes(r);
	}
	return { ids: ids.reverse(), bytes, pinnedKept };
}
