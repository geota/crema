/**
 * Test helpers for the SQLite side of the backup seam (#310 R1w): the real
 * store, in Node, on `memvfs`, from the `memory-truth` test bundle
 * (`pnpm wasm:history:test`, into `web/.wasm-test/history/`). No mock of
 * SQLite: the walk, its holds, `restoreBegin` and `idsCreatedBefore` are the
 * core's own.
 *
 * - {@link directPort}: a {@link HistoryDbPort} straight onto one open file,
 *   with a fault hook, for the source's own tests.
 * - {@link memoryDisk}: stands in for the W1 test kit's `FakeDisk`, so its
 *   `Env` runs the app's owner and `HistoryDbClient` over the real store, and
 *   a killed Worker closes what it had open.
 * - {@link memoryDetails}: a `details` store in memory, with a fault hook.
 *
 * Not a test file.
 */
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fillHeavy, keepHeavy, type DetailsRecord } from '#lib/history/history-db.js';
import type { Engine, EngineDb, OpenOptions, PoolInfo } from '#lib/history-db/engine.js';
import type { Applied, Pool, Write } from '#lib/history-db/protocol.js';
import type { HistoryClientLike } from './port';
import type { DetailsStore } from './details';
import type { HistoryDbPort, WalkCall } from './sqlite';

const DIR = resolve(process.cwd(), '.wasm-test/history');

/** The test bundle is built. CI builds it before `pnpm test`; without it, the SQLite tests fail there and skip here. */
export const historyWasmBuilt = existsSync(resolve(DIR, 'de1_history_wasm_bg.wasm'));
if (!historyWasmBuilt && process.env.CI) {
	throw new Error('The history test bundle is missing: run `pnpm wasm:history:test` first.');
}

/** The bundle's `HistoryDb`, by its methods. */
export interface WasmDb {
	close(): void;
	free(): void;
	[method: string]: unknown;
}
interface WasmModule {
	default(o: { module_or_path: Uint8Array }): Promise<unknown>;
	HistoryDb: {
		open(json: string): Promise<WasmDb>;
		createRecovery(json: string): Promise<WasmDb>;
	};
}

let loaded: Promise<WasmModule> | null = null;

/** The test bundle, initialised once per file. */
export function historyWasm(): Promise<WasmModule> {
	loaded ??= (async () => {
		const url = resolve(DIR, 'de1_history_wasm.js');
		const mod = (await import(/* @vite-ignore */ url)) as WasmModule;
		await mod.default({ module_or_path: await readFile(resolve(DIR, 'de1_history_wasm_bg.wasm')) });
		return mod;
	})();
	return loaded;
}

let files = 0;
/** A fresh file name: `memvfs` keeps every file for the life of the module. */
export const freshFile = (): string => `r1w-${++files}.db`;

/** Methods whose one argument is plain text, as `wasm-engine.ts` has them. */
const RAW_TEXT = new Set(['byRemoteId', 'byDecentId', 'appliedSeq', 'exportEnd', 'exportRewind', 'setSourceSeq']);

function parsed(v: unknown): unknown {
	if (typeof v !== 'string') return v ?? null;
	try {
		return JSON.parse(v);
	} catch {
		return v;
	}
}

function thrown(e: unknown): { code: string; message: string } {
	if (typeof e === 'string') {
		try {
			const v = JSON.parse(e);
			if (v && typeof v === 'object' && 'code' in v) return v;
		} catch {
			/* not JSON */
		}
		return { code: 'js', message: e };
	}
	if (e && typeof e === 'object' && 'code' in e) return e as { code: string; message: string };
	return { code: 'js', message: String(e) };
}

/** `method(arg)` on `db`, as the Worker's engine calls it. */
export async function callDb(db: WasmDb, method: string, arg?: unknown): Promise<unknown> {
	const f = db[method] as ((a?: string) => unknown) | undefined;
	if (typeof f !== 'function') throw { code: 'refused', message: `no method ${method}` };
	const text = arg === undefined ? undefined : RAW_TEXT.has(method) && typeof arg === 'string' ? arg : JSON.stringify(arg);
	try {
		return parsed(await (text === undefined ? f.call(db) : f.call(db, text)));
	} catch (e) {
		throw thrown(e);
	}
}

/** Makes `history.db`-like truth file `file` in memory (an authority file is never created by an open). */
export async function makeTruth(file: string): Promise<void> {
	const mod = await historyWasm();
	const db = await mod.HistoryDb.createRecovery(
		JSON.stringify({
			place: 'memory',
			file,
			generation: 0,
			journal: 'truncate',
			recovery: { migrationSource: [], lateBaseline: '{}' }
		})
	);
	db.close();
	db.free();
}

/** Opens truth file `file`. */
export async function openTruth(file: string): Promise<WasmDb> {
	const mod = await historyWasm();
	return mod.HistoryDb.open(
		JSON.stringify({ place: 'memory', file, role: 'authority', create: false, generation: 0, journal: 'truncate' })
	);
}

/** Where a port's fault hook may stop a call: the call's name, or `write:<kind>`, and its count so far. */
export type Fault = (at: string, n: number) => boolean;

/** A crash at a named point, as a fault hook throws it. */
export class Crash extends Error {
	constructor(readonly at: string) {
		super(`crash at ${at}`);
		this.name = 'Crash';
	}
}

/**
 * A {@link HistoryDbPort} onto `db` itself: reads and walk calls by name,
 * writes through `apply` in one tab's order, as the owner applies them.
 * `fault` stops a call before it runs.
 */
export function directPort(db: WasmDb, opts: { fault?: Fault; tab?: string } = {}): HistoryDbPort & { calls: Map<string, number> } {
	const tab = opts.tab ?? `direct-${++files}`;
	let wseq = 0;
	const calls = new Map<string, number>();
	const hit = (at: string) => {
		const n = (calls.get(at) ?? 0) + 1;
		calls.set(at, n);
		if (opts.fault?.(at, n)) throw new Crash(at);
	};
	return {
		calls,
		read: async <T>(name: string, arg?: unknown) => {
			hit(name);
			return (await callDb(db, name, arg)) as T;
		},
		once: async <T>(name: WalkCall, arg?: unknown) => {
			hit(name);
			return (await callDb(db, name, arg)) as T;
		},
		write: async (w: Write) => {
			hit(`write:${w.kind}`);
			return (await callDb(db, 'apply', { tab, seq: ++wseq, atMs: Date.now(), write: w })) as Applied;
		}
	};
}

/** A `details` store in memory; `fault` stops a call before it runs (`details:get|put|delete`). */
export function memoryDetails(opts: { fault?: Fault } = {}): DetailsStore & { map: Map<string, DetailsRecord> } {
	const map = new Map<string, DetailsRecord>();
	const calls = new Map<string, number>();
	const hit = (at: string) => {
		const n = (calls.get(at) ?? 0) + 1;
		calls.set(at, n);
		if (opts.fault?.(at, n)) throw new Crash(at);
	};
	return {
		map,
		async get(ids) {
			hit('details:get');
			const out = new Map<string, DetailsRecord>();
			for (const id of ids) {
				const d = map.get(id);
				if (d) out.set(id, structuredClone(d));
			}
			return out;
		},
		async put(details, how) {
			hit('details:put');
			const out = new Map<string, DetailsRecord>();
			for (const d of details) {
				const kept = how === 'fill' ? fillHeavy(d, map.get(d.id)) : keepHeavy(d, map.get(d.id));
				map.set(d.id, structuredClone(kept));
				out.set(d.id, kept);
			}
			return out;
		},
		async delete(ids) {
			hit('details:delete');
			for (const id of ids) map.delete(id);
		}
	};
}

/**
 * The W1 kit's disk, over the real store: one `crema-db` pool whose files are
 * truth files in memory. `kill(ctx)` closes what that context had open, as
 * the browser frees a dead Worker's handles.
 */
export function memoryDisk(): {
	files: Set<string>;
	engine(ctx: string): Engine;
	kill(ctx: string): void;
	plant(file: string): Promise<void>;
} {
	const files = new Set<string>();
	const open = new Map<string, Set<WasmDb>>();
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
	return {
		files,
		async plant(file) {
			await makeTruth(file);
			files.add(file);
		},
		kill(ctx) {
			for (const db of open.get(ctx) ?? []) close(db);
			open.delete(ctx);
		},
		engine(ctx): Engine {
			const paused = new Map<Pool, boolean>();
			const info = (pool: Pool): PoolInfo => ({
				vfs: `crema-${pool}`,
				dir: pool,
				generation: 0,
				capacity: 16,
				files: paused.get(pool) ? [] : [...files].map((f) => `/${f}`).sort(),
				paused: paused.get(pool) ?? false
			});
			return {
				installPool: async (pool) => {
					paused.set(pool, paused.get(pool) ?? false);
					return info(pool);
				},
				poolInfo: info,
				pausePool: (pool) => void paused.set(pool, true),
				resumePool: async (pool) => void paused.set(pool, false),
				readRepairDone: async () => null,
				open: async (o: OpenOptions): Promise<EngineDb> => {
					const db = await openTruth(o.file).catch((e) => {
						throw thrown(e);
					});
					const mine = open.get(ctx) ?? new Set<WasmDb>();
					open.set(ctx, mine);
					mine.add(db);
					let live = true;
					return {
						call: (method, arg) => {
							if (!live) return Promise.reject({ code: 'closed', message: 'closed' });
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
}

/** Every id and `row_json` of `file`, by `rid`, read through `port`. */
export async function rowsOf(port: Pick<HistoryClientLike, 'read'> | HistoryDbPort): Promise<Map<string, unknown>> {
	const out = new Map<string, unknown>();
	let after = 0;
	for (;;) {
		const page = (await port.read('syncRows', { afterRid: after, limit: 500 })) as { rows: { rid: number; id: string; held: boolean }[] };
		if (page.rows.length === 0) break;
		const ids = page.rows.filter((r) => !r.held).map((r) => r.id);
		for (const g of (await port.read('get', ids)) as { id: string; rowJson: string }[]) out.set(g.id, JSON.parse(g.rowJson));
		after = page.rows[page.rows.length - 1].rid;
	}
	return out;
}
