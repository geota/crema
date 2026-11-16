/**
 * `#lib/history-db/node-engine.js` — the real history store under Vitest
 * (#310 W2): the {@link Engine} over `core/de1-history-wasm` (`pnpm
 * wasm:history`) on its `memory` VFS, so the tests run the index's real SQL
 * in Node. Tests only; the app's Worker uses `wasm-engine.ts` on OPFS.
 *
 * The pools are pretend (Node has no OPFS): each lists the files opened in
 * it. A file is a memory database named by its pool, its name and a
 * generation, so {@link NodeEngine.lose} stands for the file being deleted
 * under an open connection: that connection's calls fail as a closed file's
 * would, and the next open finds no file.
 */
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { HistoryDb, initSync } from './wasm/de1_history_wasm.js';
import { engineError, type Engine, type EngineDb, type OpenOptions, type PoolInfo } from './engine.js';
import type { Pool } from './protocol.js';

let loaded = false;

/** Loads the history wasm once per test file. */
export function loadHistoryWasm(): void {
	if (loaded) return;
	initSync({ module: readFileSync(path.resolve('src/lib/history-db/wasm/de1_history_wasm_bg.wasm')) });
	loaded = true;
}

const RAW_TEXT = new Set(['byRemoteId', 'byDecentId', 'appliedSeq', 'exportEnd', 'exportRewind', 'setSourceSeq', 'setMigrationDone', 'metaValue']);

function parse(v: unknown): unknown {
	if (typeof v !== 'string') return v ?? null;
	try {
		return JSON.parse(v);
	} catch {
		return v;
	}
}

let instances = 0;

export class NodeEngine implements Engine {
	/** Every memory file name this engine made: distinct per engine, so tests don't share files. */
	readonly #prefix = `n${++instances}`;
	readonly #files = new Map<string, { gen: number; exists: boolean }>();
	readonly #open = new Map<string, Set<{ dead: boolean }>>();
	readonly #paused = new Map<Pool, boolean>();
	/** Called before each call by method name; may throw an `EngineError` (a fault). */
	fault: ((method: string) => void) | null = null;
	/** Every call, by method name, for the tests that count them. */
	readonly calls: string[] = [];

	constructor() {
		loadHistoryWasm();
	}

	#key(pool: Pool, file: string): string {
		return `${pool}/${file}`;
	}

	async installPool(pool: Pool, generation: number): Promise<PoolInfo> {
		return this.poolInfo(pool, generation);
	}

	poolInfo(pool: Pool, generation = 0): PoolInfo {
		const files = [...this.#files.entries()]
			.filter(([k, f]) => f.exists && k.startsWith(`${pool}/`))
			.map(([k]) => `/${k.slice(pool.length + 1)}`);
		const paused = this.#paused.get(pool) ?? false;
		return { vfs: `node-${pool}`, dir: `.node-${pool}`, generation, capacity: 16, files: paused ? [] : files, paused };
	}

	pausePool(pool: Pool): void {
		for (const [k, set] of this.#open) {
			if (k.startsWith(`${pool}/`) && set.size > 0) throw { code: 'pool.filesInUse', message: `${k} is open` };
		}
		this.#paused.set(pool, true);
	}

	async resumePool(pool: Pool): Promise<void> {
		this.#paused.set(pool, false);
	}

	async readRepairDone(): Promise<{ copied: string[] } | null> {
		return null;
	}

	/** The file is gone under its connections: they fail, and the next open finds none. */
	lose(pool: Pool, file: string): void {
		const k = this.#key(pool, file);
		const f = this.#files.get(k);
		if (f) {
			f.exists = false;
			f.gen += 1;
		}
		for (const h of this.#open.get(k) ?? []) h.dead = true;
	}

	async open(o: OpenOptions): Promise<EngineDb> {
		const k = this.#key(o.pool, o.file);
		let f = this.#files.get(k);
		if (!f || !f.exists) {
			if (!o.create) throw { code: 'cantOpen', message: `${k} doesn't exist` };
			f = { gen: f?.gen ?? 0, exists: true };
			this.#files.set(k, f);
		}
		const name = `${this.#prefix}-${o.pool}-${f.gen}-${o.file}`;
		let db: HistoryDb;
		try {
			db = (await HistoryDb.open(
				JSON.stringify({
					place: 'memory',
					file: name,
					role: o.role,
					create: o.create,
					generation: o.generation,
					journal: o.journal
				})
			)) as HistoryDb;
		} catch (e) {
			throw engineError(e);
		}
		const handle = { dead: false };
		const set = this.#open.get(k) ?? new Set();
		set.add(handle);
		this.#open.set(k, set);
		const calls = this.calls;
		const fault = () => this.fault;
		return {
			async call(method, arg) {
				if (handle.dead) throw { code: 'cantOpen', message: `${k} is gone` };
				calls.push(method);
				fault()?.(method);
				const f = (db as unknown as Record<string, (a?: string) => unknown>)[method];
				if (typeof f !== 'function') throw { code: 'refused', message: `no method ${method}` };
				const text =
					arg === undefined ? undefined : RAW_TEXT.has(method) && typeof arg === 'string' ? arg : JSON.stringify(arg);
				try {
					return parse(await (text === undefined ? f.call(db) : f.call(db, text)));
				} catch (e) {
					throw engineError(e);
				}
			},
			close() {
				set.delete(handle);
				try {
					db.close();
				} catch {
					// Already closed.
				} finally {
					db.free();
				}
			}
		};
	}
}
