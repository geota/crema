/**
 * The flip under Vitest (#310 W5): both pools over the real store, in memory,
 * from the `memory-truth` test bundle (`pnpm wasm:history:test`), so the
 * index, the copy and `history.db` are real SQLite files and the flip's own
 * calls run. The P3 store is `crema-history` in the fake IndexedDB.
 *
 * - {@link flipDisk}: an {@link Engine} per Worker context over one shared
 *   set of files in `crema-index` and `crema-db`. A file can be marked
 *   unusable (`corrupt`, `foreign`): every open and copy onto it fails with
 *   that code and never touches it, as `sahpool` leaves a damaged file. A
 *   killed context's connections close, as the browser frees a dead
 *   Worker's handles. `fault` stops a call before it runs.
 * - {@link flipEnv}: the W1 kit's `Env` whose Workers run the flip and serve
 *   stage 1 and stage 2 over that disk.
 * - {@link stageDeps}: what `chooseStage` needs, over that env.
 *
 * Not a test file.
 */
import type { Engine, EngineDb, OpenOptions, PoolInfo } from './engine.js';
import type { Pool } from './protocol.js';
import { Env } from './test-kit.js';
import { IndexSync } from './index-sync.js';
import { p3Source } from './p3-source.js';
import { P3_CHANNEL } from './names.js';
import type { StageDeps } from './stage.js';
import { callDb, historyWasm, type WasmDb } from '#lib/history-source/sqlite-kit.js';

let disks = 0;

export interface FlipDisk {
	/** The files each pool holds, by name. */
	files: Record<Pool, Set<string>>;
	/** Files that fail every open and copy with this code, untouched. */
	unusable: Map<string, string>;
	/** Every call made on the disk, as `pool/file:method`. */
	calls: string[];
	/** Throws to stop a call before it runs: `at` is `pool/file:method`, `n` the calls so far. */
	fault: ((at: string, n: number) => void) | null;
	/** Pools paused by every context. */
	paused: Set<Pool>;
	/** Pools whose resume doesn't take (REVIEW-456 N3). */
	stuck: Set<Pool>;
	engine(ctx: string): Engine;
	kill(ctx: string): void;
	/** `pool/file`'s memory name. */
	nameOf(pool: Pool, file: string): string;
	/** Opens `file` in `crema-db` for a test's own look (an authority file). */
	look(file: string): Promise<WasmDb>;
}

export function flipDisk(): FlipDisk {
	const prefix = `fd${++disks}`;
	const files: Record<Pool, Set<string>> = { index: new Set(), db: new Set() };
	const unusable = new Map<string, string>();
	const open = new Map<string, Set<WasmDb>>();
	const paused = new Set<Pool>();
	const calls: string[] = [];
	const nameOf = (pool: Pool, file: string) => `${prefix}-${pool}-${file}`;
	const byName = new Map<string, { pool: Pool; file: string }>();
	const close = (db: WasmDb) => {
		try {
			db.close();
		} catch {
			/* already closed */
		}
		try {
			db.free();
		} catch {
			/* already freed */
		}
	};
	const disk: FlipDisk = {
		files,
		unusable,
		calls,
		fault: null,
		paused,
		stuck: new Set<Pool>(),
		nameOf,
		async look(file) {
			const mod = await historyWasm();
			return mod.HistoryDb.open(
				JSON.stringify({ place: 'memory', file: nameOf('db', file), role: 'authority', create: false, generation: 0, journal: 'truncate' })
			);
		},
		kill(ctx) {
			for (const db of open.get(ctx) ?? []) close(db);
			open.delete(ctx);
		},
		engine(ctx): Engine {
			const info = (pool: Pool): PoolInfo => ({
				vfs: `crema-${pool}`,
				dir: pool,
				generation: 0,
				capacity: 16,
				files: paused.has(pool) ? [] : [...files[pool]].map((f) => `/${f}`).sort(),
				paused: paused.has(pool)
			});
			const hit = (at: string) => {
				calls.push(at);
				disk.fault?.(at, calls.length);
			};
			return {
				installPool: async (pool) => {
					hit(`${pool}:install`);
					return info(pool);
				},
				poolInfo: info,
				pausePool: (pool) => {
					const mine = open.get(ctx);
					if (mine && mine.size > 0) {
						// Only this context's handles in that pool count.
						for (const db of mine) if ((db as unknown as { pool?: Pool }).pool === pool) throw { code: 'pool.filesInUse', message: 'open' };
					}
					paused.add(pool);
				},
				resumePool: async (pool) => {
					if (!disk.stuck.has(pool)) paused.delete(pool);
				},
				readRepairDone: async () => null,
				placeOf: (pool, file) => {
					const name = nameOf(pool, file);
					byName.set(name, { pool, file });
					return { place: 'memory', file: name };
				},
				open: async (o: OpenOptions): Promise<EngineDb> => {
					const key = `${o.pool}/${o.file}`;
					hit(`${key}:open`);
					const bad = unusable.get(key);
					if (bad) throw { code: bad, message: `${key} is ${bad}` };
					if (!files[o.pool].has(o.file) && !o.create) throw { code: 'cantOpen', message: `${key} doesn't exist` };
					const mod = await historyWasm();
					let db: WasmDb;
					try {
						db = await mod.HistoryDb.open(
							JSON.stringify({
								place: 'memory',
								file: nameOf(o.pool, o.file),
								role: o.role,
								create: o.create,
								generation: o.generation,
								journal: 'truncate'
							})
						);
					} catch (e) {
						throw typeof e === 'string' ? JSON.parse(e) : e;
					}
					(db as unknown as { pool: Pool }).pool = o.pool;
					files[o.pool].add(o.file);
					const mine = open.get(ctx) ?? new Set<WasmDb>();
					open.set(ctx, mine);
					mine.add(db);
					let live = true;
					return {
						call: async (method, arg) => {
							if (!live || !mine.has(db)) throw { code: 'cantOpen', message: `${key} is closed` };
							hit(`${key}:${method}`);
							if (method === 'flipCopyTo' || method === 'copyTo') {
								const a = arg as { file: string; journal: string };
								const dest = byName.get(a.file);
								if (dest) {
									const bad2 = unusable.get(`${dest.pool}/${dest.file}`);
									if (bad2) throw { code: bad2, message: `${dest.file} is ${bad2}` };
									files[dest.pool].add(dest.file);
								}
								arg = { ...a, journal: 'truncate' };
							}
							return callDb(db, method, arg);
						},
						close() {
							if (!live) return;
							live = false;
							mine.delete(db);
							close(db);
						}
					};
				}
			};
		}
	};
	return disk;
}

/** Where the done-record lives in a test: one text at the "OPFS root". */
export interface DoneRecordBox {
	text: string | null;
	/** Fails the next write (`writeDoneRecord`). */
	failNext?: boolean;
}

/** An env whose Workers flip and serve over `disk`, reading the fake IndexedDB's `crema-history`. */
export function flipEnv(disk: FlipDisk, done: DoneRecordBox, o: { quota?: () => { quota: number; usage: number } | null } = {}): Env {
	const env: Env = new Env({
		engine: (ctx) => disk.engine(ctx),
		onKill: (ctx) => disk.kill(ctx),
		indexSync: (ctx, d) => new IndexSync({ ...d, source: p3Source(), channel: env.bus.channel(P3_CHANNEL, ctx) }),
		flip: () => ({
			source: () => p3Source(),
			async writeDoneRecord(text) {
				if (done.failNext) {
					done.failNext = false;
					throw new Error('the done-record write failed');
				}
				done.text = text;
			},
			estimate: async () => o.quota?.() ?? null
		})
	});
	return env;
}

let pages = 0;

/** `chooseStage`'s deps for a page of `env`: its locks, its storage, a Worker of its own. */
export function stageDeps(
	env: Env,
	done: DoneRecordBox,
	o: Partial<StageDeps> & { page?: string; windows?: number | null } = {}
): StageDeps & { page: string; workers: () => ReturnType<Env['makeWorker']>[] } {
	const page = o.page ?? `boot${++pages}`;
	const made: ReturnType<Env['makeWorker']>[] = [];
	return {
		page,
		workers: () => made,
		storage: {
			getItem: (k) => env.storage.getItem(k),
			setItem: (k, v) => env.storage.as(page, () => env.storage.setItem(k, v))
		},
		locks: env.locks.forCtx(page),
		doneRecord: { read: async () => done.text },
		windowCount: async () => (o.windows === undefined ? 1 : o.windows),
		makeWorker: () => {
			const w = env.makeWorker(page);
			made.push(w);
			return w;
		},
		channel: (name) => env.bus.channel(name, page),
		now: () => Date.now(),
		build: 'test',
		journal: 'exclusiveWal',
		allowFlip: true,
		...o
	};
}
