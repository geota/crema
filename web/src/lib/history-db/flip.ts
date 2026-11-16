/**
 * `#lib/history-db/flip.js` — the flip, in a history Worker that holds the
 * owner lock (#310 SPEC §11 stage 2, PLAN W5, M2, M4, M6, M8, M9, S9, S13).
 * The page (`stage.ts`) decides that a flip may run (a fresh boot, the stage
 * lock exclusive, one window) and writes the marker after; this runs the
 * steps between:
 *
 * | Step | Here |
 * | --- | --- |
 * | 1 | No marker: the highest-generation `history.db` / `history-<n>.db` that opens with `migration = done` is the truth (S9): `found`, never a new copy. One a newer build wrote (`tooNew`) is `found` too, for `too_new` |
 * | 2 | The P3 store must be migrated and readable; else `skipped` |
 * | 3 | Free space: twice the index's size, or `failed` (`quota`) |
 * | 5a | A full diff brings the index up to date (M4), after a whole rebuild when the last verify failed |
 * | 4, 5b | Copy the index into `history.db` (or the next generation when a leftover there doesn't open, M8): `flipCopyTo` writes `role = 'authority'`, `migration = copying`, `migration_source` and both late-row baselines in one transaction (S13) |
 * | 5c | Rebuild the copy's search index, so verify checks the copy's own (M6) |
 * | 7 | Verify (below); pass: `done` in the file, then the done-record. Fail: `failed`, the copy left `copying` |
 *
 * **Verify** requires all of: the id sets of the P3 store, the copy and
 * `migration_source` equal both ways; each `row_json` equal to its P3 row,
 * deep, with `summary.sampleCount` and the signature's inputs; no derived
 * column stale; `PRAGMA integrity_check` and the search index's check
 * passing (`notReady` is never a pass, REVIEW-448 note 5); and nothing but
 * this tab's captures wrote the P3 store (M2, M9): its `writeSeq` after the
 * last read is the diff's plus one per capture write, each a new id. The
 * captured ids are left out of the comparisons; those the copy lacks go
 * through step 8 once the marker is written.
 *
 * **Never the truth, never deleted (M8).** Nothing here writes the P3 store
 * or deletes a file. A crash anywhere leaves `history.db` with `role =
 * 'index'`, or `copying`, which no build reads as the truth, and the next try
 * overwrites it in place. A leftover that doesn't open is left untouched and
 * the flip goes into the next generation.
 */
import { engineError, type Engine, type EngineDb } from './engine.js';
import { fullDiff, type P3Source } from './index-sync.js';
import { INDEX_FILE } from './names.js';
import { dbFileName, doneRecordText, generationOf } from './marker.js';
import type { Journal } from './protocol.js';

/** The highest database generation the flip tries before it gives up (crema-db's capacity is 16). */
export const MAX_FLIP_GENERATION = 4;
/** P3 rows per verify batch. */
export const VERIFY_BATCH = 500;
/** Rows per `get` of the copy. */
const GET_ROWS = 100;
/** Steps of the copy's search index rebuild before verify gives up on it. */
const FTS_STEPS = 100_000;

/** A leftover the copy won't write over (not a database, or not ours): left as it is, and the next generation taken (M8). */
const UNUSABLE = new Set(['corrupt', 'foreign']);
/** Step 1's open failures that say "not a finished truth here": an earlier try's leftover, or a file that doesn't open. */
const NOT_DONE = new Set(['corrupt', 'foreign', 'wrongRole', 'refused']);

export interface FlipArgs {
	/** `crema-index`'s generation (0, unless repaired). */
	indexGeneration: number;
	/** `crema-db`'s generation (0, unless repaired). */
	poolGeneration: number;
	journal: Journal;
	/** The last verify failed: rebuild the index whole before the diff (M4). */
	rebuildIndex: boolean;
	/** The done-record is there but unreadable: only look for the finished file, never copy. */
	scanOnly?: boolean;
}

/** A capture this tab wrote to the P3 store during the flip, with the `writeSeq` it committed (M9). */
export interface FlipCapture {
	id: string;
	seq: number;
}

export type FlipOutcome =
	/** Step 1: a finished file is there, the marker was missing. */
	| { outcome: 'found'; generation: number; poolGeneration: number }
	/** Verify passed: the file says `done` and the done-record is written. */
	| { outcome: 'done'; generation: number; poolGeneration: number; captured: string[] }
	/** Not this boot, and not a failure (the P3 store not ready, the lock busy). */
	| { outcome: 'skipped'; reason: string }
	/**
	 * SPEC §11's Failure: the next try waits (the backoff). `afterDone`: the
	 * Worker died, erred or hung after it reached `done`, so the file may say
	 * `done` (the page looks before it calls this a failure, REVIEW-473 B1).
	 */
	| { outcome: 'failed'; reason: string; rebuildIndex: boolean; afterDone?: boolean }
	/** `scanOnly`, and no finished file. */
	| { outcome: 'nothing' };

export interface FlipDeps {
	engine: Engine;
	/** The P3 store, read only (`p3-source.ts`). */
	source: P3Source;
	/** Writes the done-record's text at the OPFS root, durably. */
	writeDoneRecord(text: string): Promise<void>;
	/** `navigator.storage.estimate()`; null where unknown. */
	estimate?(): Promise<{ quota?: number; usage?: number } | null>;
	/** This tab's captures so far (M9). */
	captured(): readonly FlipCapture[];
	now(): number;
	/** Called at each named step, before it runs: the tests' fault and kill point. */
	at?(step: string): void | Promise<void>;
	log?(line: string): void;
}

/** Verify said no: why, and whether the index must be rebuilt whole first (M4). */
class Mismatch extends Error {
	constructor(
		readonly reason: string,
		readonly rebuild = true
	) {
		super(reason);
	}
}

/** `v` with every object's keys sorted, as text: equal for deep-equal JSON. */
export function canonical(v: unknown): string {
	return JSON.stringify(v, (_k, x) =>
		x && typeof x === 'object' && !Array.isArray(x)
			? Object.fromEntries(Object.keys(x as object).sort().map((k) => [k, (x as Record<string, unknown>)[k]]))
			: x
	);
}

const yieldNow = () => new Promise((r) => setTimeout(r, 0));

/** Every id in `db`, by `rid`, held ones too. */
async function idsOf(db: EngineDb): Promise<{ ids: string[]; held: number }> {
	const ids: string[] = [];
	let held = 0;
	let after = 0;
	for (;;) {
		const page = (await db.call('syncRows', { afterRid: after, limit: 1000 })) as {
			rows: { rid: number; id: string; held: boolean }[];
		};
		if (page.rows.length === 0) break;
		for (const r of page.rows) {
			ids.push(r.id);
			if (r.held) held++;
		}
		after = page.rows[page.rows.length - 1].rid;
		await yieldNow();
	}
	return { ids, held };
}

/** The fields `signatureForShot` reads, and `summary.sampleCount` (SPEC §11 verify). */
function signatureInputs(row: Record<string, unknown>): string {
	const s = row.summary as { sampleCount?: unknown } | undefined;
	const r = row.record as { duration?: unknown } | undefined;
	const m = row.metadata as { finalWeight?: unknown } | undefined;
	return canonical([row.completedAt, r?.duration, row.profileName, m?.finalWeight, s?.sampleCount]);
}

/**
 * Runs the flip; the caller holds the owner lock and the page the stage lock.
 * Both pools are closed and paused when it returns, so the next owner installs
 * them at once.
 */
export async function runFlip(d: FlipDeps, a: FlipArgs): Promise<FlipOutcome> {
	const { engine } = d;
	const at = async (step: string) => {
		await d.at?.(step);
	};
	const opened: EngineDb[] = [];
	const open = async (o: Parameters<Engine['open']>[0]) => {
		const db = await engine.open(o);
		opened.push(db);
		return db;
	};
	const shut = (db: EngineDb) => {
		const i = opened.indexOf(db);
		if (i >= 0) opened.splice(i, 1);
		try {
			db.close();
		} catch {
			// Best-effort: the pause and the Worker's end free it.
		}
	};
	try {
		await at('install');
		for (const [pool, g] of [
			['index', a.indexGeneration],
			['db', a.poolGeneration]
		] as const) {
			const info = await engine.installPool(pool, g);
			if (info.paused) await engine.resumePool(pool);
		}
		const pool = engine.poolInfo('db');
		if (pool.paused) return { outcome: 'skipped', reason: 'paused' };

		// Step 1: a finished file, the highest generation first (S9).
		await at('scan');
		const listed = pool.files.map((f) => ({ f, g: generationOf(f) })).filter((x): x is { f: string; g: number } => x.g !== null);
		listed.sort((x, y) => y.g - x.g);
		for (const { g } of listed) {
			let db: EngineDb;
			try {
				db = await open({ pool: 'db', file: dbFileName(g), role: 'authority', create: false, generation: a.poolGeneration, journal: a.journal });
			} catch (e) {
				const code = engineError(e).code;
				// A newer build's truth: never copy over it, or past it.
				if (code === 'tooNew' || code === 'readOnlyTooNew') return { outcome: 'found', generation: g, poolGeneration: a.poolGeneration };
				if (!NOT_DONE.has(code)) return { outcome: 'skipped', reason: `scan:${code}` };
				d.log?.(`flip: ${dbFileName(g)} isn't a finished truth (${code})`);
				continue;
			}
			let state: unknown;
			try {
				state = await db.call('migrationState');
			} finally {
				shut(db);
			}
			if (state === 'done') {
				await d.writeDoneRecord(doneRecordText({ generation: g, poolGeneration: a.poolGeneration, at: d.now() }));
				return { outcome: 'found', generation: g, poolGeneration: a.poolGeneration };
			}
		}
		if (a.scanOnly) return { outcome: 'nothing' };

		// Step 2: the P3 store, migrated and readable.
		await at('p3');
		let idsAtStart: Set<string>;
		try {
			const view = await d.source.view();
			if (!view.done) return { outcome: 'skipped', reason: 'p3NotReady' };
			idsAtStart = new Set(await d.source.ids());
		} catch {
			return { outcome: 'skipped', reason: 'p3Read' };
		}

		const index = await open({
			pool: 'index',
			file: INDEX_FILE,
			role: 'index',
			create: true,
			generation: a.indexGeneration,
			journal: a.journal
		});
		if (a.rebuildIndex) {
			// M4: a failed verify means the index may be wrong in ways a diff,
			// which compares only `row_json`, doesn't mend: empty it (it's
			// derived) and let the diff build it whole.
			await at('rebuild');
			await index.call('removeAbsentBegin', { ids: [], sinceSeq: Number(await index.call('writeSeq')) });
			for (;;) {
				const s = (await index.call('removeAbsentNext')) as { done: boolean };
				if (s.done) break;
				await yieldNow();
			}
		}

		// Step 3: room for the copy.
		await at('space');
		const size = Number(await index.call('sizeBytes')) || 0;
		const est = await d.estimate?.().catch(() => null);
		if (est && typeof est.quota === 'number' && typeof est.usage === 'number' && est.quota - est.usage < 2 * size) {
			return { outcome: 'failed', reason: 'quota', rebuildIndex: a.rebuildIndex };
		}

		// Step 5a: the index up to date with the P3 store.
		await at('diff');
		const { writeSeq: sourceSeq } = await fullDiff({
			step: async (f) => {
				await yieldNow();
				return f();
			},
			call: (method, arg) => index.call(method, arg) as never,
			notify: () => {},
			source: d.source,
			live: () => {}
		});
		const indexIds = (await idsOf(index)).ids;
		const p3Migration = (await d.source.migration?.()) ?? null;

		// Steps 4 and 5b: the copy, into the first generation that takes it.
		let g = 0;
		for (; ; g++) {
			if (g > MAX_FLIP_GENERATION) return { outcome: 'failed', reason: 'noGeneration', rebuildIndex: a.rebuildIndex };
			const file = dbFileName(g);
			await at('copy');
			try {
				await index.call('flipCopyTo', {
					...(engine.placeOf?.('db', file) ?? { place: 'db', file }),
					journal: a.journal,
					init: {
						generation: g,
						sourceSeq,
						sourceCount: indexIds.length,
						migrationSource: indexIds,
						lateBaseline: JSON.stringify({ writeSeq: sourceSeq }),
						p3Migration
					}
				});
				break;
			} catch (e) {
				const err = engineError(e);
				// The file says it's a finished truth, though step 1 couldn't open
				// it: it was the truth. Never copy past it (`lost` decides).
				if (err.code === 'refused') {
					await d
						.writeDoneRecord(doneRecordText({ generation: g, poolGeneration: a.poolGeneration, at: d.now() }))
						.catch(() => {});
					return { outcome: 'found', generation: g, poolGeneration: a.poolGeneration };
				}
				if (!UNUSABLE.has(err.code)) throw e;
				d.log?.(`flip: ${file} can't take the copy (${err.code}: ${err.message}); left as it is, trying ${dbFileName(g + 1)}`);
			}
		}
		const file = dbFileName(g);
		await at('copied');
		shut(index);

		// Step 5c: the copy's own search index.
		const copy = await open({ pool: 'db', file, role: 'authority', create: false, generation: a.poolGeneration, journal: a.journal });
		await at('fts');
		await copy.call('startFtsRebuild');
		for (let n = 0; ; n++) {
			if (n > FTS_STEPS) return { outcome: 'failed', reason: 'ftsRebuild', rebuildIndex: a.rebuildIndex };
			const m = (await copy.call('maintain')) as { step: string };
			if (m.step === 'idle') break;
			if (m.step === 'busy') return { outcome: 'failed', reason: 'busy', rebuildIndex: a.rebuildIndex };
			await yieldNow();
		}

		// Step 7: verify.
		await at('verify');
		try {
			await verify(d, copy, { sourceSeq, idsAtStart, at });
		} catch (e) {
			if (!(e instanceof Mismatch)) throw e;
			d.log?.(`flip: verify failed: ${e.reason}`);
			return { outcome: 'failed', reason: `verify:${e.reason}`, rebuildIndex: e.rebuild || a.rebuildIndex };
		}
		await at('done');
		const passed = (): FlipOutcome => ({
			outcome: 'done',
			generation: g,
			poolGeneration: a.poolGeneration,
			captured: [...new Set(d.captured().filter((c) => c.seq > sourceSeq).map((c) => c.id))]
		});
		try {
			await copy.call('setMigrationDone', String(d.now()));
		} catch (e) {
			// The commit may have landed before the call failed: `done` in the
			// file is `done`, whatever the call said (REVIEW-473 B1).
			const state = await copy.call('migrationState').catch(() => null);
			if (state !== 'done') throw e;
		}
		// From here the file is the truth: nothing may turn this into a failure,
		// or this boot would run on the P3 store and its edits be lost at the
		// next try (REVIEW-473 B1). The done-record is the backup record; if it
		// can't be written, the marker still is, and step 1 finds the file again.
		shut(copy);
		try {
			await at('doneRecord');
			await d.writeDoneRecord(doneRecordText({ generation: g, poolGeneration: a.poolGeneration, at: d.now() }));
		} catch (e) {
			d.log?.(`flip: history.db is done, but the done-record wasn't written (${engineError(e).code}); the marker keeps it`);
		}
		return passed();
	} catch (e) {
		const err = engineError(e);
		if (err.code === 'p3NotReady') return { outcome: 'skipped', reason: 'p3NotReady' };
		const reason = err.code === 'p3Read' ? 'p3Read' : `${err.code}: ${err.message}`;
		const full = err.code === 'full' || err.code === 'QuotaExceededError';
		return { outcome: 'failed', reason: full ? 'quota' : reason, rebuildIndex: a.rebuildIndex };
	} finally {
		for (const db of [...opened]) shut(db);
		for (const pool of ['index', 'db'] as const) {
			try {
				engine.pausePool(pool);
			} catch {
				// Not installed, or already paused.
			}
		}
	}
}

/** SPEC §11's verify: throws a {@link Mismatch} for the first check that fails. */
async function verify(
	d: FlipDeps,
	copy: EngineDb,
	o: { sourceSeq: number; idsAtStart: Set<string>; at: (s: string) => Promise<void> }
): Promise<void> {
	const captures = d.captured().filter((c) => c.seq > o.sourceSeq);
	const capIds = new Set(captures.map((c) => c.id));
	// A capture is a new shot: one that names a row the store held is an edit (M9).
	for (const id of capIds) if (o.idsAtStart.has(id)) throw new Mismatch(`capture ${id} names an existing row`, false);

	// The id sets, both ways (captures aside).
	await o.at('verify:ids');
	const { ids: dbList, held } = await idsOf(copy);
	if (held > 0) throw new Mismatch(`${held} held rows in the copy`);
	const dbIds = new Set(dbList);
	const ms = (await copy.call('migrationSourceIds')) as string[];
	const msIds = new Set(ms);
	if (msIds.size !== dbIds.size || [...dbIds].some((id) => !msIds.has(id))) throw new Mismatch('migration_source differs from the copy');
	const p3Ids = new Set(await d.source.ids());
	for (const id of p3Ids) if (!capIds.has(id) && !dbIds.has(id)) throw new Mismatch(`${id} is missing from the copy`);
	for (const id of dbIds) if (!capIds.has(id) && !p3Ids.has(id)) throw new Mismatch(`${id} isn't in the P3 store`);

	// Every row, deep, in batches.
	await o.at('verify:rows');
	let after: string | null = null;
	for (;;) {
		const rows = await d.source.rowsAfter(after, VERIFY_BATCH);
		if (rows.length === 0) break;
		const want = new Map<string, string>();
		for (const r of rows) if (dbIds.has(r.id)) want.set(r.id, r.rowJson);
		const ids = [...want.keys()];
		for (let i = 0; i < ids.length; i += GET_ROWS) {
			const got = (await copy.call('get', ids.slice(i, i + GET_ROWS))) as { id: string; rowJson: string }[];
			if (got.length !== Math.min(GET_ROWS, ids.length - i)) throw new Mismatch('a row of the copy is hidden or missing');
			for (const g of got) {
				const p3 = JSON.parse(want.get(g.id)!) as Record<string, unknown>;
				const db = JSON.parse(g.rowJson) as Record<string, unknown>;
				if (canonical(p3) !== canonical(db)) throw new Mismatch(`${g.id}'s row differs`);
				if (signatureInputs(p3) !== signatureInputs(db)) throw new Mismatch(`${g.id}'s signature differs`);
			}
		}
		after = rows[rows.length - 1].id;
		await yieldNow();
		if (rows.length < VERIFY_BATCH) break;
	}

	// Derived columns, the file and the search index.
	await o.at('verify:derived');
	const stale = (await copy.call('derivedMismatches')) as string[];
	if (stale.length > 0) throw new Mismatch(`${stale.length} rows' derived columns are stale (${stale[0]}…)`);
	await o.at('verify:integrity');
	const problems = (await copy.call('integrityCheck')) as string[];
	if (problems.length > 0) throw new Mismatch(`integrity_check: ${problems[0]}`);
	const fts = (await copy.call('ftsIntegrityCheck')) as string;
	// `notReady` isn't a pass: not verified, try again (REVIEW-448 note 5).
	if (fts !== 'ok') throw new Mismatch(`search index check: ${fts}`, fts === 'damaged');

	// Nothing but this tab's captures wrote the P3 store (M2, M9).
	await o.at('verify:seq');
	const { writeSeq } = await d.source.view();
	const seqs = [...new Set(captures.map((c) => c.seq))].filter((s) => s <= writeSeq);
	if (seqs.length !== captures.length) throw new Mismatch('a capture write is past the store', false);
	if (writeSeq !== o.sourceSeq + seqs.length) {
		throw new Mismatch(`the P3 store moved: writeSeq ${writeSeq}, ${o.sourceSeq} + ${seqs.length} captures`, false);
	}
}
