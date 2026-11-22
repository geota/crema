/**
 * `#lib/history-db/wasm-engine.js` — the {@link Engine} over the history wasm
 * (`core/de1-history-wasm`, built by `pnpm wasm:history`). Worker-only: OPFS
 * sync access handles exist only in dedicated Workers.
 */
import init, {
	HistoryDb,
	installPool,
	pausePool,
	poolInfo,
	resumePool
} from './wasm/de1_history_wasm.js';
import { DB_POOL_DIR, INDEX_POOL_DIR } from './names.js';
import { engineError, type Engine, type EngineDb, type OpenOptions, type PoolInfo } from './engine.js';
import type { Pool } from './protocol.js';

/** Methods whose one argument is plain text, not JSON. */
const RAW_TEXT = new Set(['byRemoteId', 'byDecentId', 'lastGrindsForBag', 'appliedSeq', 'exportEnd', 'exportRewind', 'setSourceSeq', 'setMigrationDone', 'metaValue']);

const REPAIR_DONE = '.repair-done';

function parse(v: unknown): unknown {
	if (typeof v !== 'string') return v ?? null;
	try {
		return JSON.parse(v);
	} catch {
		return v;
	}
}

async function guard<T>(f: () => T | Promise<T>): Promise<T> {
	try {
		return await f();
	} catch (e) {
		throw engineError(e);
	}
}

function wrap(db: HistoryDb): EngineDb {
	return {
		call: (method, arg) =>
			guard(async () => {
				const f = (db as unknown as Record<string, (a?: string) => unknown>)[method];
				if (typeof f !== 'function') throw { code: 'refused', message: `no method ${method}` };
				const text =
					arg === undefined ? undefined : RAW_TEXT.has(method) && typeof arg === 'string' ? arg : JSON.stringify(arg);
				return parse(await (text === undefined ? f.call(db) : f.call(db, text)));
			}),
		close() {
			try {
				db.close();
			} finally {
				db.free();
			}
		}
	};
}

async function directory(path: string): Promise<FileSystemDirectoryHandle | null> {
	let d = await navigator.storage.getDirectory();
	for (const part of path.split('/').filter(Boolean)) {
		try {
			d = await d.getDirectoryHandle(part);
		} catch {
			return null;
		}
	}
	return d;
}

/** The wasm build, loaded on first use. */
export function wasmEngine(): Engine {
	let loaded: Promise<unknown> | null = null;
	const ready = () => (loaded ??= init());
	return {
		installPool: (pool, generation) =>
			guard(async () => {
				await ready();
				return parse(await installPool(JSON.stringify({ pool, generation }))) as PoolInfo;
			}),
		poolInfo(pool) {
			try {
				return parse(poolInfo(pool)) as PoolInfo;
			} catch (e) {
				throw engineError(e);
			}
		},
		pausePool(pool) {
			try {
				pausePool(pool);
			} catch (e) {
				throw engineError(e);
			}
		},
		resumePool: (pool) =>
			guard(async () => {
				await resumePool(pool);
			}),
		open: (o: OpenOptions) =>
			guard(async () => {
				await ready();
				const db = (await HistoryDb.open(
					JSON.stringify({
						place: o.pool,
						file: o.file,
						role: o.role,
						create: o.create,
						generation: o.generation,
						journal: o.journal
					})
				)) as HistoryDb;
				return wrap(db);
			}),
		createRecovery: (o) =>
			guard(async () => {
				await ready();
				const db = (await HistoryDb.createRecovery(
					JSON.stringify({ place: 'db', file: o.file, generation: o.generation, journal: o.journal, recovery: o.recovery })
				)) as HistoryDb;
				return wrap(db);
			}),
		async readRepairDone(pool: Pool, generation: number) {
			const base = pool === 'db' ? DB_POOL_DIR : INDEX_POOL_DIR;
			const dir = await directory(generation === 0 ? base : `${base}-${generation}`);
			if (!dir) return null;
			try {
				const file = await (await dir.getFileHandle(REPAIR_DONE)).getFile();
				const report = JSON.parse(await file.text());
				return Array.isArray(report?.copied) ? { copied: report.copied.map(String) } : null;
			} catch {
				return null;
			}
		}
	};
}
