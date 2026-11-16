/**
 * `#lib/history-db/index-sync.js` — stage 1's index upkeep, in the owner's
 * Worker (#310 PLAN W2, SPEC §3 "Keeping the index in step, web", §11 stage 1,
 * M4). `history-index.db` is derived from the P3 store, `crema-history`, which
 * stays the truth; only the owner's Worker writes the index, and it reads the
 * P3 store itself. No tab sends rows.
 *
 * - **Upkeep.** P3w posts the ids it wrote, and the `writeSeq` its transaction
 *   committed, on `crema-history`. The owner reads those ids' current rows in
 *   one read transaction and writes them, with the ids it no longer has as
 *   deletes and the seqs as seen, in one index write. It reads the committed
 *   row, never a copy from the message, so two tabs editing one row leave the
 *   newer version whatever order their notices arrive in.
 * - **Seqs.** The index keeps `source_seq`, every P3 seq at or below which it
 *   has applied, and `seen_seqs` above it; the core moves `source_seq` only
 *   across seqs it has seen.
 * - **A gap** is a seq nobody announced: a writer killed between its commit
 *   and its post, a frozen owner, a notice with no seq. One open for
 *   {@link GAP_MS} runs a full diff.
 * - **The boot check** passes when `source_seq` is the P3 store's `writeSeq`,
 *   `seen_seqs` is empty and the row counts match. Otherwise a full diff runs,
 *   at boot and on resume, and the index can't answer until it's done, so
 *   the list uses the in-memory path (SPEC §3 "A miss").
 * - **The full diff** (§11 stage 1 step 3): the P3 rows in batches of
 *   {@link DIFF_BATCH}, upserted where they differ; then the ids the P3 store
 *   doesn't hold, read in one transaction, removed in steps, keeping any row
 *   the index wrote after the diff began; then `source_seq` becomes the
 *   `writeSeq` read before the first batch.
 * - **One step at a time.** Every read of the P3 store and the index write
 *   that follows it run in this module's own order, so a row read earlier is
 *   never written over one read later. Each index call goes through the
 *   owner's queue, one at a time, so tabs' reads go between them, and each
 *   is short: {@link DIFF_WRITE_ROWS} rows of a batch, a removal step of at
 *   most 500 deletes, under B1's 50 ms for the longest transaction and far
 *   under the page's `WORKER_DEAD_MS` (REVIEW-456 N2).
 * - The P3 store is only read here, and the database is never created (its
 *   source opens with `expectExisting`). Nothing here deletes a file.
 */
import type { ChannelLike } from './ports.js';
import type { EngineDb } from './engine.js';
import { engineError } from './engine.js';
import type { ChangeSet } from './protocol.js';

/** Rows per full-diff batch, as the P3 store reads them (SPEC §11). */
export const DIFF_BATCH = 500;
/**
 * Rows per index call of a batch: a call holds the owner's one connection,
 * and 500 real-shaped rows took about 64 ms in Chrome at 20,000, over B1's
 * 50 ms for the longest single transaction (W2's browser bench).
 */
export const DIFF_WRITE_ROWS = 125;
/** A gap open this long runs a full diff (SPEC §3). */
export const GAP_MS = 2_000;
/** The P3 store isn't there yet (a first boot's migration): look again after this, doubling. */
export const SOURCE_RETRY_FIRST_MS = 2_000;
export const SOURCE_RETRY_MAX_MS = 30_000;
/** How long a burst of notices gathers into one index write. */
const FLUSH_MS = 0;

/** The P3 store, as the index reads it. */
export interface P3Source {
	/** The write counter, the row count (tombstones too) and whether the migration is done, in one read. */
	view(): Promise<{ writeSeq: number; rows: number; done: boolean }>;
	/** The rows of `ids` it holds, as their stored JSON, in one read transaction. */
	rowsOf(ids: readonly string[]): Promise<Map<string, string>>;
	/** Up to `limit` rows by id after `after` (from the first when null), as stored JSON. */
	rowsAfter(after: string | null, limit: number): Promise<{ id: string; rowJson: string }[]>;
	/** Every id it holds, in one read transaction. */
	ids(): Promise<string[]>;
	close(): void;
}

/** What the P3 store says on `crema-history` (`store.svelte.ts`). */
type P3Notice =
	| { type: 'rows'; from?: string; ids?: unknown; seq?: unknown }
	| { type: 'recovered'; from?: string };

export interface SyncTimers {
	setTimeout(f: () => void, ms: number): unknown;
	clearTimeout(t: unknown): void;
}

export interface SyncDeps {
	db: EngineDb;
	source: P3Source;
	/** `crema-history`, the P3 store's notices; closed by {@link IndexSync.stop}. */
	channel: ChannelLike | null;
	/** Runs `f` in the owner's queue, between the tabs' calls. */
	queue<T>(f: () => Promise<T>): Promise<T>;
	/** Posts an index write's `ChangeSet` to every tab. */
	notify(change: ChangeSet): void;
	/** The index can answer (`true`) or is behind the P3 store and can't yet, and why. */
	state(ready: boolean, reason: string): void;
	/** An index call failed in a way only reopening the file mends. */
	broken(code: string): void;
	timers?: SyncTimers;
	log?(line: string): void;
}

/** How the upkeep stands, for diagnostics and B1's browser bench (`indexSync`). */
export interface SyncStats {
	ready: boolean;
	reason: string;
	/** Full diffs run, and the last one's length, ms. */
	diffs: number;
	lastDiffMs: number | null;
	/** The longest single index call the upkeep made, ms: B1's longest transaction. */
	longestCallMs: number;
	/** Gaps that ran a diff. */
	gaps: number;
}

/** The owner's view of an index sync. */
export interface IndexSyncLike {
	start(): void;
	/** The page came back to the front: the boot check again (SPEC §3: a diff on resume). */
	resume(): void;
	stop(): void;
	readonly ready: boolean;
	readonly stats?: SyncStats;
}

interface IndexState {
	sourceSeq: number;
	seenSeqs: number[];
}

interface Written {
	change: ChangeSet;
}

interface AbsentStep {
	change: ChangeSet;
	done: boolean;
}

/** Stopped, or a newer diff began: this one ends at its next step. */
class Superseded extends Error {}

const defaultTimers: SyncTimers = {
	setTimeout: (f, ms) => setTimeout(f, ms),
	clearTimeout: (t) => clearTimeout(t as ReturnType<typeof setTimeout>)
};

export class IndexSync implements IndexSyncLike {
	readonly #d: SyncDeps;
	readonly #timers: SyncTimers;
	#stopped = false;
	/** This module's own order: every P3 read and the index write after it. */
	#chain: Promise<unknown> = Promise.resolve();
	#ids = new Set<string>();
	#seqs: number[] = [];
	/** A notice came with no seq (an older build): the seqs can't account for it. */
	#unseq = false;
	#flushQueued = false;
	#diffGen = 0;
	#diffing = false;
	/** A diff was asked for while one ran: one more after it. */
	#diffAgain: string | null = null;
	#gapTimer: unknown = null;
	#retryTimer: unknown = null;
	#retryWait = SOURCE_RETRY_FIRST_MS;
	#ready = false;
	#reason = 'checking';
	#diffs = 0;
	#lastDiffMs: number | null = null;
	#longest = 0;
	#gaps = 0;
	#refusedRuns = 0;

	constructor(d: SyncDeps) {
		this.#d = d;
		this.#timers = d.timers ?? defaultTimers;
	}

	get ready(): boolean {
		return this.#ready;
	}

	/** Why it can't answer, or `ok`. */
	get reason(): string {
		return this.#reason;
	}

	get stats(): SyncStats {
		return {
			ready: this.#ready,
			reason: this.#reason,
			diffs: this.#diffs,
			lastDiffMs: this.#lastDiffMs,
			longestCallMs: this.#longest,
			gaps: this.#gaps
		};
	}

	start(): void {
		if (this.#d.channel) this.#d.channel.onmessage = (e) => this.#onNotice(e.data as P3Notice);
		this.#check('boot');
	}

	resume(): void {
		if (this.#stopped || this.#diffing) return;
		this.#check('resume');
	}

	stop(): void {
		if (this.#stopped) return;
		this.#stopped = true;
		this.#diffGen++;
		if (this.#d.channel) {
			this.#d.channel.onmessage = null;
			this.#d.channel.close();
		}
		if (this.#gapTimer) this.#timers.clearTimeout(this.#gapTimer);
		if (this.#retryTimer) this.#timers.clearTimeout(this.#retryTimer);
		this.#gapTimer = this.#retryTimer = null;
		try {
			this.#d.source.close();
		} catch {
			// Closing is best-effort.
		}
	}

	// ── Order ──────────────────────────────────────────────────────────────

	/** Runs `f` after every step before it. A step that fails doesn't stop the ones after. */
	#step<T>(f: () => Promise<T>): Promise<T> {
		const run = this.#chain.then(async () => {
			if (this.#stopped) throw new Superseded();
			// Let the Worker answer its page's pings between steps.
			await new Promise((r) => this.#timers.setTimeout(() => r(undefined), 0));
			if (this.#stopped) throw new Superseded();
			return f();
		});
		this.#chain = run.catch(() => {});
		return run;
	}

	/** One index call, in the owner's queue. */
	#call<T>(method: string, arg?: unknown): Promise<T> {
		return this.#d.queue(async () => {
			if (this.#stopped) throw new Superseded();
			const t0 = performance.now();
			try {
				return (await this.#d.db.call(method, arg)) as T;
			} finally {
				this.#longest = Math.max(this.#longest, performance.now() - t0);
			}
		});
	}

	#setState(ready: boolean, reason: string): void {
		if (this.#stopped) return;
		if (ready === this.#ready && reason === this.#reason) return;
		this.#ready = ready;
		this.#reason = reason;
		this.#d.state(ready, reason);
	}

	/** An index call failed: a reopen mends what's mendable (the file gone, corrupt, closed). */
	#failed(e: unknown, what: string): void {
		if (e instanceof Superseded || this.#stopped) return;
		const err = engineError(e);
		this.#d.log?.(`index ${what} failed: ${err.code} ${err.message}`);
		this.#d.broken(err.code);
	}

	// ── The boot check ─────────────────────────────────────────────────────

	/** Passes, or says why not (SPEC §3). */
	async #misses(): Promise<string[] | null> {
		let view: Awaited<ReturnType<P3Source['view']>>;
		try {
			view = await this.#d.source.view();
		} catch {
			return null;
		}
		if (!view.done) return null;
		const st = await this.#call<IndexState>('indexState');
		const rows = Number(await this.#call<number>('rowCount'));
		const out: string[] = [];
		if (st.sourceSeq !== view.writeSeq) out.push(`source_seq ${st.sourceSeq}, writeSeq ${view.writeSeq}`);
		if (st.seenSeqs.length > 0) out.push(`${st.seenSeqs.length} seen past a gap`);
		if (rows !== view.rows) out.push(`index ${rows} rows, P3 ${view.rows}`);
		return out;
	}

	#check(why: 'boot' | 'resume'): void {
		void this.#step(async () => {
			const misses = await this.#misses();
			if (misses === null) {
				// The P3 store isn't there, or not migrated yet: look again later.
				this.#setState(false, 'p3NotReady');
				this.#retryLater(() => this.#check(why));
				return;
			}
			this.#retryWait = SOURCE_RETRY_FIRST_MS;
			if (misses.length === 0) {
				this.#setState(true, 'ok');
				return;
			}
			this.#d.log?.(`index ${why} check: ${misses.join('; ')}; a full diff`);
			this.#setState(false, `diff:${why}`);
			this.#diff(why);
		}).catch((e) => this.#failed(e, 'check'));
	}

	#retryLater(f: () => void): void {
		if (this.#stopped || this.#retryTimer) return;
		const wait = this.#retryWait;
		this.#retryWait = Math.min(wait * 2, SOURCE_RETRY_MAX_MS);
		this.#retryTimer = this.#timers.setTimeout(() => {
			this.#retryTimer = null;
			if (!this.#stopped) f();
		}, wait);
	}

	// ── Upkeep ─────────────────────────────────────────────────────────────

	#onNotice(m: P3Notice): void {
		if (this.#stopped || !m || typeof m !== 'object') return;
		if (m.type === 'recovered') {
			// Another tab filled a lost store anew: every row may differ.
			this.#setState(false, 'diff:recovered');
			this.#diff('recovered');
			return;
		}
		if (m.type !== 'rows' || !Array.isArray(m.ids)) return;
		for (const id of m.ids) if (typeof id === 'string') this.#ids.add(id);
		if (typeof m.seq === 'number' && Number.isSafeInteger(m.seq) && m.seq > 0) this.#seqs.push(m.seq);
		else this.#unseq = true;
		this.#scheduleFlush();
	}

	#scheduleFlush(): void {
		if (this.#flushQueued) return;
		this.#flushQueued = true;
		this.#timers.setTimeout(() => {
			void this.#step(() => this.#flush()).catch((e) => this.#failed(e, 'write'));
		}, FLUSH_MS);
	}

	/** The notified ids' rows, read now, in one index write with their seqs. */
	async #flush(): Promise<void> {
		this.#flushQueued = false;
		const ids = [...this.#ids];
		const seqs = this.#seqs;
		const unseq = this.#unseq;
		this.#ids = new Set();
		this.#seqs = [];
		this.#unseq = false;
		if (ids.length === 0 && seqs.length === 0) return;
		let found: Map<string, string>;
		try {
			found = await this.#d.source.rowsOf(ids);
		} catch {
			// The P3 store can't be read now: the ids wait, and the seqs stay
			// unseen, so the gap rule catches up if this keeps failing.
			for (const id of ids) this.#ids.add(id);
			this.#seqs.push(...seqs);
			this.#unseq ||= unseq;
			this.#retryLater(() => this.#scheduleFlush());
			return;
		}
		const upsert = [...found.values()].map((rowJson) => ({ rowJson }));
		const del = ids.filter((id) => !found.has(id));
		const w = await this.#call<Written>('indexWrite', { upsert, delete: del, seenSeqs: seqs });
		this.#d.notify(w.change);
		const st = await this.#call<IndexState>('indexState');
		if (st.seenSeqs.length > 0 || unseq) this.#armGap();
	}

	/** A seq nobody announced: if it's still missing in 2 s, a full diff (SPEC §3). */
	#armGap(): void {
		if (this.#gapTimer || this.#stopped) return;
		this.#gapTimer = this.#timers.setTimeout(() => {
			this.#gapTimer = null;
			void this.#step(async () => {
				if (this.#diffing) return;
				const misses = await this.#misses();
				if (misses === null || misses.length === 0) return;
				this.#d.log?.(`index gap open ${GAP_MS} ms: ${misses.join('; ')}; a full diff`);
				this.#gaps++;
				// The index is behind, not wrong: it keeps answering while the diff runs.
				this.#diff('gap');
			}).catch((e) => this.#failed(e, 'gap check'));
		}, GAP_MS);
	}

	// ── The full diff ──────────────────────────────────────────────────────

	#diff(why: string): void {
		if (this.#stopped) return;
		if (this.#diffing) {
			this.#diffAgain = why;
			return;
		}
		this.#diffing = true;
		const gen = ++this.#diffGen;
		const began = performance.now();
		const live = () => {
			if (this.#stopped || gen !== this.#diffGen) throw new Superseded();
		};
		void this.#runDiff(live)
			.then(
				() => {
					this.#diffs++;
					this.#lastDiffMs = performance.now() - began;
					this.#refusedRuns = 0;
					this.#retryWait = SOURCE_RETRY_FIRST_MS;
					this.#setState(true, 'ok');
				},
				(e) => {
					if (e instanceof Superseded) return;
					const code = engineError(e).code;
					if (code === 'p3NotReady' || code === 'p3Read') {
						this.#retryLater(() => this.#diff(why));
						return;
					}
					// A removal the store forgot (a reopen): the diff again from the
					// start, once; refused again, the file needs reopening.
					if (code === 'refused' && this.#refusedRuns++ === 0) {
						this.#diffAgain ??= why;
						return;
					}
					this.#failed(e, 'diff');
				}
			)
			.finally(() => {
				if (gen !== this.#diffGen && !this.#stopped) return;
				this.#diffing = false;
				const again = this.#diffAgain;
				this.#diffAgain = null;
				if (again && !this.#stopped) this.#diff(again);
				// Seqs past the diff's own may still be unannounced.
				else if (!this.#stopped) void this.#step(async () => {
					const st = await this.#call<IndexState>('indexState');
					if (st.seenSeqs.length > 0) this.#armGap();
				}).catch(() => {});
			});
	}

	async #runDiff(live: () => void): Promise<void> {
		const p3 = async <T>(f: () => Promise<T>): Promise<T> => {
			try {
				return await f();
			} catch {
				throw { code: 'p3Read', message: 'the P3 store could not be read' };
			}
		};
		// 1. The P3 store's writeSeq before the first batch, and the index's own seq.
		const start = await this.#step(async () => {
			live();
			const view = await p3(() => this.#d.source.view());
			if (!view.done) throw { code: 'p3NotReady', message: 'the P3 store is not migrated yet' };
			const since = Number(await this.#call<number>('writeSeq'));
			return { writeSeq: view.writeSeq, since };
		});
		// 2. Every row, in batches, upserted where it differs.
		let after: string | null = null;
		for (;;) {
			const more = await this.#step(async () => {
				live();
				const rows = await p3(() => this.#d.source.rowsAfter(after, DIFF_BATCH));
				if (rows.length === 0) return false;
				// In calls of a few rows each, so the tabs' reads go between them.
				for (let i = 0; i < rows.length; i += DIFF_WRITE_ROWS) {
					live();
					const part = rows.slice(i, i + DIFF_WRITE_ROWS).map((r) => ({ rowJson: r.rowJson }));
					const w = await this.#call<Written>('diffBatch', part);
					this.#d.notify(w.change);
				}
				after = rows[rows.length - 1].id;
				return rows.length === DIFF_BATCH;
			});
			if (!more) break;
		}
		// 3. The ids the P3 store doesn't hold, read in one transaction, removed
		// in steps; a row the index wrote after the diff began stays.
		await this.#step(async () => {
			live();
			const ids = await p3(() => this.#d.source.ids());
			live();
			await this.#call('removeAbsentBegin', { ids, sinceSeq: start.since });
		});
		for (;;) {
			const done = await this.#step(async () => {
				live();
				const s = await this.#call<AbsentStep>('removeAbsentNext');
				this.#d.notify(s.change);
				return s.done;
			});
			if (done) break;
		}
		// 4. Every P3 seq up to the one read first is applied.
		await this.#step(async () => {
			live();
			await this.#call('setSourceSeq', String(start.writeSeq));
		});
	}
}
