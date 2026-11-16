/**
 * `#lib/history-source/sqlite.js` — `history.db` behind the backup seam
 * (#310 PLAN R1w, SPEC §9, M3, S6). Rows through `HistoryDbClient`; their
 * heavy parts in `crema-history`'s `details` ({@link DetailsStore}).
 *
 * **Export: the walk.** A backup reads its shots twice, once to count what it
 * can write and once to write it, so it begins two walks back to back: each
 * fixes its set at `exportBegin`, and the second pass must yield the first's
 * ids in the same order, or the backup stops with {@link ShotsMoved}. A delete
 * during a walk only holds the row (`hold_delete`), so every row of the set is
 * still there to write and the header's count is the lines written. At the
 * end, and before the next export, the holds are released: their details are
 * deleted first, then the rows (S6). A crash between leaves a held, hidden row
 * that the next release deletes; never details without a row.
 *
 * **Restore: merge, then remove (M3).** `restoreBegin` gives the store's
 * `write_seq`. The plan's rows go in as upserts, details first, each batch a
 * write in this tab's order. Last, the rows the store held when the restore
 * began (`idsCreatedBefore`), less every id in the backup, and only those the
 * plan names, are deleted. Nothing depends on what one owner wrote: an owner
 * change between batches can't make a backup row look absent, and a shot
 * captured during the restore has a later `created_seq`, so it's never in the
 * wipe. A crash leaves the union; running it again finishes it.
 */
import type { ShotSummary } from '#lib/core/crema-core.js';
import {
	detailsOf,
	rowOf,
	shotOfRow,
	withDetails,
	type DetailsRecord,
	type RowRecord
} from '#lib/history/history-db.js';
import type { StoredShot } from '#lib/history/model.js';
import type { Applied, ChangeSet, Write } from '#lib/history-db/protocol.js';
import type { StoreHealth } from '#lib/utils/storage.js';
import type { LocalShot } from '#lib/restore/shots.js';
import type { DetailsStore } from './details';
import {
	ShotsMoved,
	type ExportShots,
	type HistorySource,
	type RestoredShots,
	type RestoreShotsInput
} from './types';

/** The walk's calls: each must run once on the owner it reaches (its token lives on that connection). */
export type WalkCall = 'exportBegin' | 'exportNext' | 'exportEnd';

/** What this source needs of `HistoryDbClient` (a stage-2 client of `history.db`). */
export interface HistoryDbPort {
	read<T = unknown>(name: string, arg?: unknown): Promise<T>;
	/** A walk call, sent once and never resent. */
	once<T = unknown>(name: WalkCall, arg?: unknown): Promise<T>;
	write(write: Write): Promise<Applied>;
	onChange?(f: (c: ChangeSet) => void): () => void;
	/** The client's health: `ok`, `loading`, or why it can't use the store. */
	health?(): string;
}

export interface SqliteSourceOptions {
	db: HistoryDbPort;
	details: DetailsStore;
	/** Rows per walk step, restore batch and page. */
	batch?: number;
	/** A whole shot's summary (`#lib/history/summary.js`'s `summarize`). */
	summarize: (shot: StoredShot) => ShotSummary;
	/**
	 * Runs a release only while no restore runs in any tab, or answers null
	 * (`guards.ts`'s `apartFromRestores`). A restore's upsert of a held row
	 * ends its hold; a release racing it would delete the row it brought back.
	 */
	apartFromRestores?: <T>(f: () => Promise<T>) => Promise<T | null>;
}

/** `ExportWalk` as the wasm gives it: the token as text. */
interface Walk {
	token: string;
	count: number;
}
interface RowOut {
	id: string;
	rowJson: string;
}
interface SyncRow {
	rid: number;
	id: string;
	deletedAt: number | null;
	sampleCount: number | null;
	held: boolean;
}
interface RidId {
	rid: number;
	id: string;
}

/** How often two walks begun back to back are tried for the same set before the backup gives up. */
const BEGIN_TRIES = 3;

/** A stored row as a whole shot: its fields and `details`. A row that isn't a row object is passed on as its text, for the core to omit. */
function shotOf(rowJson: string, details: DetailsRecord | undefined): StoredShot {
	let row: unknown;
	try {
		row = JSON.parse(rowJson);
	} catch {
		return rowJson as unknown as StoredShot;
	}
	const r = row as Partial<RowRecord> | null;
	if (!r || typeof r !== 'object' || !r.record || typeof r.record !== 'object') {
		return row as StoredShot;
	}
	return withDetails(shotOfRow(r as RowRecord).shot, details);
}

function chunks<T>(items: readonly T[], n: number): T[][] {
	const out: T[][] = [];
	for (let i = 0; i < items.length; i += n) out.push(items.slice(i, i + n));
	return out;
}

/** A write that must land: `done`, or applied before. */
function landed(a: Applied): void {
	if (a.outcome === 'done' || a.outcome === 'alreadyApplied') return;
	throw new Error(a.outcome === 'refused' ? `The history store refused a write: ${a.reason}` : `write ${a.outcome}`);
}

export class SqliteSource implements HistorySource {
	readonly kind = 'sqlite' as const;
	readonly seriesFailed = false;
	readonly restoreCapacity = null;
	readonly #db: HistoryDbPort;
	readonly #details: DetailsStore;
	readonly #batch: number;
	readonly #summarize: (shot: StoredShot) => ShotSummary;
	readonly #apart: SqliteSourceOptions['apartFromRestores'];
	#live = 0;

	constructor(o: SqliteSourceOptions) {
		this.#db = o.db;
		this.#details = o.details;
		this.#batch = o.batch ?? 100;
		this.#summarize = o.summarize;
		this.#apart = o.apartFromRestores;
		o.db.onChange?.(() => void this.countLive());
		void this.countLive();
	}

	/** Counts the live rows again for {@link liveCount}: at start and after every change notice. */
	async countLive(): Promise<number> {
		try {
			this.#live = await this.#db.read<number>('count', {});
		} catch {
			// The next change counts again.
		}
		return this.#live;
	}

	// ── Gates ──────────────────────────────────────────────────────────────

	/** The owner answers: a read goes through. Throws when it doesn't, within the client's 30 s. */
	async whenReady(): Promise<void> {
		await this.#db.read('writeSeq');
	}

	async seriesReady(): Promise<boolean> {
		try {
			await this.whenReady();
			return true;
		} catch {
			return false;
		}
	}

	liveCount(): number {
		return this.#live;
	}

	rowsHealth(): StoreHealth {
		const h = this.#db.health?.() ?? 'ok';
		if (h === 'ok' || h === 'loading') return h;
		return h === 'lost' ? 'absent' : 'unavailable';
	}

	async writeSeq(): Promise<number | null> {
		return this.#db.read<number>('writeSeq');
	}

	async readyWithin(): Promise<boolean> {
		return this.seriesReady();
	}

	/** Every id, tombstones and held rows too, by `rid`. */
	async listedIds(): Promise<ReadonlySet<string>> {
		const out = new Set<string>();
		let after = 0;
		for (;;) {
			const page = await this.#db.read<{ rows: SyncRow[] }>('syncRows', { afterRid: after, limit: this.#batch * 5 });
			if (page.rows.length === 0) break;
			for (const r of page.rows) out.add(r.id);
			after = page.rows[page.rows.length - 1].rid;
		}
		return out;
	}

	/** Details a wipe's crash left: the marker deletes them once no row lists the id. */
	async deleteOwed(id: string): Promise<void> {
		await this.#details.delete([id]);
	}

	pauseDeletes(): () => void {
		// Every delete here names its ids; a restore's are its own last step.
		return () => {};
	}

	// ── Holds (S6) ─────────────────────────────────────────────────────────

	/**
	 * Release the rows deletes held for walks that have ended, here or in a
	 * tab that's gone: their details first, then the rows. Run after every
	 * export and before the next; W5 runs it at boot too.
	 */
	async settleHolds(): Promise<number> {
		const run = async () => {
			const ids = await this.#db.read<string[]>('releaseHolds');
			for (const part of chunks(ids, this.#batch)) {
				await this.#details.delete(part);
				// Only the rows still held: one an upsert brought back since is the user's again.
				const still = new Set(await this.#db.read<string[]>('releaseHolds'));
				const rows = part.filter((id) => still.has(id));
				if (rows.length > 0) landed(await this.#db.write({ kind: 'delete', ids: rows }));
			}
			return ids.length;
		};
		// A restore runs: it may bring a held row back. The next export, or boot, releases.
		return this.#apart ? ((await this.#apart(run)) ?? 0) : run();
	}

	// ── Export ─────────────────────────────────────────────────────────────

	async exportShots(): Promise<ExportShots> {
		await this.settleHolds();
		const [a, b] = await this.#beginTwo();
		let passes = 0;
		let order: string[] | null = null;
		let closed = false;
		const close = async (): Promise<void> => {
			if (closed) return;
			closed = true;
			await this.#end(a);
			await this.#end(b);
			await this.settleHolds().catch(() => {
				// Released before the next export, or at boot.
			});
		};
		return {
			total: a.count,
			pass: () => {
				const n = passes++;
				if (n > 1) throw new Error('A backup reads its shots twice, no more.');
				if (n === 0) {
					order = [];
					return this.#walk(a, order, null);
				}
				return this.#walk(b, null, order ?? []);
			},
			close
		};
	}

	/** Two walks with the same count, or {@link ShotsMoved}. */
	async #beginTwo(): Promise<[Walk, Walk]> {
		for (let i = 0; i < BEGIN_TRIES; i++) {
			const a = await this.#db.once<Walk>('exportBegin');
			let b: Walk;
			try {
				b = await this.#db.once<Walk>('exportBegin');
			} catch (e) {
				await this.#end(a);
				throw e;
			}
			if (a.count === b.count) return [a, b];
			await this.#end(a);
			await this.#end(b);
		}
		throw new ShotsMoved();
	}

	async #end(w: Walk): Promise<void> {
		try {
			await this.#db.once('exportEnd', w.token);
		} catch {
			// An owner that's gone took the walk with it; the next open releases its holds.
		}
	}

	/**
	 * One pass over `w`: each step's rows with their details. The first pass
	 * notes the ids in `record`; the second checks them against `expect`.
	 * Either way the pass yields exactly the walk's count.
	 */
	async *#walk(w: Walk, record: string[] | null, expect: readonly string[] | null): AsyncGenerator<StoredShot> {
		let seen = 0;
		for (;;) {
			const rows = await this.#db.once<RowOut[]>('exportNext', { token: w.token, n: this.#batch });
			if (rows.length === 0) break;
			const details = await this.#details.get(rows.map((r) => r.id));
			for (const r of rows) {
				if (record) record.push(r.id);
				else if (expect && expect[seen] !== r.id) throw new ShotsMoved();
				seen += 1;
				yield shotOf(r.rowJson, details.get(r.id));
			}
		}
		// Short of the count: a step went missing (an answer lost, a call run twice).
		if (seen !== w.count || (expect && seen !== expect.length)) throw new ShotsMoved();
	}

	// ── Restore ────────────────────────────────────────────────────────────

	/**
	 * Every row, tombstones too, as the planner reads it. A row a delete holds
	 * for a walk counts as deleted (REVIEW-442 note 7). Read again if the store
	 * moved while it was read, up to three times.
	 */
	async localShots(): Promise<LocalShot[]> {
		let out: LocalShot[] = [];
		for (let i = 0; i < 3; i++) {
			const before = await this.#db.read<number>('writeSeq');
			out = await this.#readLocal();
			if ((await this.#db.read<number>('writeSeq')) === before) break;
		}
		return out;
	}

	async #readLocal(): Promise<LocalShot[]> {
		const out: LocalShot[] = [];
		let after = 0;
		for (;;) {
			const page = await this.#db.read<{ rows: SyncRow[] }>('syncRows', { afterRid: after, limit: this.#batch * 5 });
			if (page.rows.length === 0) break;
			const live = page.rows.filter((r) => !r.held && r.deletedAt == null).map((r) => r.id);
			const pinned = new Set<string>();
			for (const part of chunks(live, this.#batch)) {
				for (const g of await this.#db.read<RowOut[]>('get', part)) {
					try {
						if ((JSON.parse(g.rowJson) as { pinned?: unknown }).pinned === true) pinned.add(g.id);
					} catch {
						// Not pinned, as far as anything can tell.
					}
				}
			}
			for (const r of page.rows) {
				out.push({
					id: r.id,
					// Unknown counts as a curve, which a fill must never replace (P4w).
					hasSamples: (r.sampleCount ?? 1) > 0,
					pinned: pinned.has(r.id),
					deleted: r.held || r.deletedAt != null
				});
			}
			after = page.rows[page.rows.length - 1].rid;
		}
		return out;
	}

	async restoreShots(input: RestoreShotsInput): Promise<RestoredShots> {
		try {
			await this.#restore(input);
			return { stored: true, curvesFailed: 0 };
		} catch (e) {
			console.error('Restoring shots into history.db stopped', e);
			return { stored: false, curvesFailed: 0 };
		}
	}

	async #restore({ plan, backup, backupIds, replace }: RestoreShotsInput): Promise<void> {
		// 1. Where the restore begins: rows created after this are never wiped.
		const begin = await this.#db.read<number>('restoreBegin');
		const theirs = new Map<string, StoredShot>();
		for (const s of backup) if (!theirs.has(s.id)) theirs.set(s.id, s);

		// 2. Merge: what the plan writes, as `nextShots` does over rows in
		// memory. A row the plan adds that has turned up since (a pull landing
		// meanwhile) gets what it would have had, had it been here: Replace
		// overwrites it; Merge keeps it and fills its curve.
		const here = await this.#present([...plan.add, ...plan.fill]);
		const arrived = plan.add.filter((id) => here.has(id));
		const replaced = [...plan.overwrite, ...plan.revive, ...(replace ? arrived : [])];
		const fill = [...plan.fill, ...(replace ? [] : arrived)];
		const zero = new Set(plan.zeroDebit);
		const added = plan.add.filter((id) => !here.has(id));
		const addedSet = new Set(added);

		const whole: StoredShot[] = [];
		for (const id of [...added, ...replaced]) {
			const b = theirs.get(id);
			if (!b) continue;
			// A bean already here never paid for a shot from elsewhere.
			whole.push(zero.has(id) && addedSet.has(id) ? { ...b, bagDebit: 0 } : b);
		}
		for (const part of chunks(whole, this.#batch)) await this.#putWhole(part);
		for (const part of chunks(fill, this.#batch)) await this.#fill(part, here, theirs);

		// 3. Remove: the rows held when the restore began that the plan names
		// and the backup doesn't hold. Listed now, not from what was written.
		if (plan.remove.length === 0) return;
		const named = new Set(plan.remove);
		const wipe = (await this.#createdBefore(begin)).filter((id) => named.has(id) && !backupIds.has(id));
		for (const part of chunks(wipe, this.#batch)) {
			landed(await this.#db.write({ kind: 'delete', ids: part }));
		}
		// Their details, once their rows are gone. A row a walk still holds
		// keeps its details for the backup that writes it; that walk's release
		// deletes both. A crash before this leaves details with no row, which
		// is harmless (SPEC §2).
		const held = new Set((await this.#createdBefore(begin)).filter((id) => named.has(id)));
		for (const part of chunks(wipe.filter((id) => !held.has(id)), this.#batch)) await this.#details.delete(part);
	}

	/** Of `ids`, the rows the store shows now, by id. */
	async #present(ids: readonly string[]): Promise<Map<string, RowRecord>> {
		const out = new Map<string, RowRecord>();
		for (const part of chunks([...new Set(ids)], this.#batch)) {
			for (const g of await this.#db.read<RowOut[]>('get', part)) {
				try {
					out.set(g.id, JSON.parse(g.rowJson) as RowRecord);
				} catch {
					// Present, but not a row this can fill.
				}
			}
		}
		return out;
	}

	/** Whole shots: details first (never thinning what's stored), then their rows. */
	async #putWhole(shots: readonly StoredShot[]): Promise<void> {
		const kept = await this.#details.put(shots.map(detailsOf), 'keep');
		const rows = shots.map((s) => {
			const d = kept.get(s.id);
			return { rowJson: JSON.stringify(rowOf(s, this.#summarize(withDetails(s, d)))) };
		});
		landed(await this.#db.write({ kind: 'upsert', rows }));
	}

	/**
	 * The backfill rule: the backup's curve onto a row here that has none,
	 * never over one. A row whose count is unknown is never filled.
	 */
	async #fill(ids: readonly string[], here: Map<string, RowRecord>, theirs: Map<string, StoredShot>): Promise<void> {
		const rows: { shot: StoredShot; samples: StoredShot['record']['samples']; duration: number }[] = [];
		for (const id of ids) {
			const row = here.get(id);
			const b = theirs.get(id);
			if (!row || !b || row.summary?.sampleCount !== 0 || b.record.samples.length === 0) continue;
			const { shot } = shotOfRow(row);
			rows.push({ shot, samples: b.record.samples, duration: b.record.duration || row.record.duration });
		}
		if (rows.length === 0) return;
		const kept = await this.#details.put(
			rows.map((r) => ({ id: r.shot.id, samples: [...r.samples], profile: null, brewSeries: null })),
			'keep'
		);
		const out = rows.map((r) => {
			const shot: StoredShot = { ...r.shot, record: { duration: r.duration, samples: [] } };
			return { rowJson: JSON.stringify(rowOf(shot, this.#summarize(withDetails(shot, kept.get(shot.id))))) };
		});
		landed(await this.#db.write({ kind: 'upsert', rows: out }));
	}

	/** Every id the store held when the restore began, held and tombstoned rows too, by `rid`. */
	async #createdBefore(begin: number): Promise<string[]> {
		const out: string[] = [];
		let after = 0;
		for (;;) {
			const page = await this.#db.read<RidId[]>('idsCreatedBefore', { beginSeq: begin, afterRid: after, limit: this.#batch * 5 });
			if (page.length === 0) break;
			for (const r of page) out.push(r.id);
			after = page[page.length - 1].rid;
		}
		return out;
	}
}

