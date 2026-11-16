/**
 * `#lib/history-source/source.js` — where backups and restores read and write
 * shots (#310 PLAN R1w): one seam, two stores behind it.
 *
 * - **P3** (`p3.ts`): the `crema-history` store and its rows in memory. The
 *   one in use: every backup and restore goes through it until W5's flip.
 * - **SQLite** (`sqlite.ts`): `history.db` through `HistoryDbClient`, with the
 *   heavy parts in `crema-history`'s `details` (SPEC §2). The export walks
 *   with its token and delete hold (S6); the restore merges, then removes
 *   what the store held before it began that the backup doesn't (M3).
 *
 * W5 switches the seam with {@link setHistorySource}. Nothing else does.
 */
import { p3Source } from './p3';
import type { HistorySource } from './types';

export { ShotsMoved } from './types';
export type { ExportShots, HistorySource, RestoreShotsInput, RestoredShots } from './types';

let current: HistorySource | null = null;

/** The source backups and restores use now: the P3 store until W5 switches it. */
export function getHistorySource(): HistorySource {
	return current ?? p3Source;
}

/** W5's switch, and tests'. `null` goes back to the P3 store. */
export function setHistorySource(source: HistorySource | null): void {
	current = source;
}
