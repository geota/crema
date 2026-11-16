/**
 * `#lib/history-db/sync-projection.js` — sync's light projection of every
 * row, collected whole (#310 SPEC §9, S3).
 *
 * The planners (`reconcileShots`, `knownRowsOf`, `keyCandidatesOf`,
 * `mergeInputsOf`) read absence as meaning: a row the set doesn't hold is a
 * row this device doesn't have. So they must never see a partial or moving
 * set. {@link collectSyncProjection} reads `writeSeq`, then every
 * `syncRows` page by `rid`, then `writeSeq` again. A page read at another
 * seq, or a seq that moved by the end, starts the walk again. After
 * {@link SYNC_TRIES} tries it asks the owner for `syncProjection`, the whole
 * set in one read transaction, in one message. It answers only with a whole
 * set, so nothing partial ever reaches a planner.
 *
 * Held rows (deleted during an export walk, not yet removed) come back with
 * `held: true`. A planner treats one as deleted by name, never as absent
 * (REVIEW-442 note 7).
 *
 * Stage 1 (W3): web's planners still read P3w's rows (`sync-view.ts`), since
 * the projection has no final weight, `visualizerUpdatedAt`, tags or barista.
 * This walk is what W5 moves them onto, and in dev it already checks the
 * index's projection against P3w's (`sync-view.ts`).
 */
import type { ReadName } from './protocol.js';

/** One row of the projection (the core's `SyncRow`). */
export interface SyncRowOut {
	rid: number;
	id: string;
	visualizerId: string | null;
	decentId: string | null;
	completedAt: number;
	durationMs: number;
	deletedAt: number | null;
	isBrewLog: boolean;
	sampleCount: number | null;
	profileName: string | null;
	/** Deleted during an export walk and not removed yet: deleted, by name. */
	held: boolean;
}

/** The whole projection, and the `writeSeq` it holds. */
export interface SyncProjection {
	rows: SyncRowOut[];
	seq: number;
	/** How it was read: paged with a steady seq, or the owner's one transaction. */
	via: 'pages' | 'oneTransaction';
	/** Walks started again because the seq moved. */
	restarts: number;
}

/** Rows per `syncRows` page: about 200 KB of JSON. */
export const SYNC_PAGE = 2_000;
/** Paged tries before the one-transaction read (SPEC §9). */
export const SYNC_TRIES = 3;

/** What the walk reads through: `HistoryDbClient.read`, or a port's. */
export type SyncRead = <T>(name: Extract<ReadName, 'writeSeq' | 'syncRows' | 'syncProjection'>, arg?: unknown) => Promise<T>;

interface SyncPage {
	rows: SyncRowOut[];
	seq: number;
}

/** One paged walk, or null when the seq moved during it. */
async function walk(read: SyncRead, page: number): Promise<{ rows: SyncRowOut[]; seq: number } | null> {
	const seq = Number(await read<number>('writeSeq'));
	const rows: SyncRowOut[] = [];
	let after = 0;
	for (;;) {
		const p = await read<SyncPage>('syncRows', { afterRid: after, limit: page });
		// A page read after a write belongs to another set.
		if (Number(p.seq) !== seq) return null;
		rows.push(...p.rows);
		if (p.rows.length < page) break;
		after = p.rows[p.rows.length - 1].rid;
	}
	if (Number(await read<number>('writeSeq')) !== seq) return null;
	return { rows, seq };
}

/**
 * Every row of the projection, whole and from one state of the store (S3).
 * Throws when a read does (the owner can't answer, or the store failed):
 * never an empty or partial answer.
 */
export async function collectSyncProjection(
	read: SyncRead,
	opts: { page?: number; tries?: number } = {}
): Promise<SyncProjection> {
	const page = opts.page ?? SYNC_PAGE;
	const tries = opts.tries ?? SYNC_TRIES;
	for (let i = 0; i < tries; i++) {
		const got = await walk(read, page);
		if (got) return { ...got, via: 'pages', restarts: i };
	}
	const whole = await read<SyncPage>('syncProjection');
	return { rows: whole.rows, seq: Number(whole.seq), via: 'oneTransaction', restarts: tries };
}
