/**
 * `#lib/history-db/engine.js` — what the owner needs of the store, as an
 * interface: the wasm build (`wasm-engine.ts`, in the Worker) and the tests'
 * in-memory fake implement it. Every failure is thrown as an {@link EngineError}
 * whose `code` is C4's (`cantOpen`, `corrupt`, `pool.opfs`, …).
 */
import type { Journal, Pool, Role } from './protocol.js';

export interface EngineError {
	code: string;
	message: string;
}

/** C4's `PoolInfo`. */
export interface PoolInfo {
	vfs: string;
	dir: string;
	generation: number;
	capacity: number;
	/** The files in it, by name (`/history.db`); empty while paused. */
	files: string[];
	paused: boolean;
}

/** C4's `OpenJson`, less `place` (the pool) and the clock. */
export interface OpenOptions {
	pool: Pool;
	file: string;
	role: Role;
	create: boolean;
	generation: number;
	journal: Journal;
}

/** One open file: C4's `HistoryDb`, its methods by name. */
export interface EngineDb {
	/** `method(arg)`: its answer, parsed. Throws an {@link EngineError}. */
	call(method: string, arg?: unknown): Promise<unknown>;
	close(): void;
}

export interface Engine {
	/** Installs `pool` at `generation`, or finds it installed (paused or not). */
	installPool(pool: Pool, generation: number): Promise<PoolInfo>;
	poolInfo(pool: Pool): PoolInfo;
	/** Releases every handle; every file on it must be closed (`pool.filesInUse` otherwise). */
	pausePool(pool: Pool): void;
	resumePool(pool: Pool): Promise<void>;
	open(opts: OpenOptions): Promise<EngineDb>;
	/** The `.repair-done` report of a repair generation (REVIEW-444 notes 2, 3), or null when it has none. */
	readRepairDone(pool: Pool, generation: number): Promise<{ copied: string[] } | null>;
	/**
	 * How a copy names its destination (`copyTo`, `flipCopyTo`): `{place:
	 * pool, file}` on OPFS, which is the default; the tests' engines keep both
	 * pools in memory under names of their own.
	 */
	placeOf?(pool: Pool, file: string): { place: string; file: string };
}

/** `e` as an {@link EngineError}. */
export function engineError(e: unknown): EngineError {
	if (e && typeof e === 'object' && 'code' in e && 'message' in e) {
		return { code: String((e as EngineError).code), message: String((e as EngineError).message) };
	}
	if (typeof e === 'string') {
		try {
			const v = JSON.parse(e);
			if (v && typeof v === 'object' && 'code' in v) return { code: String(v.code), message: String(v.message ?? '') };
		} catch {
			/* not C4's JSON */
		}
		return { code: 'js', message: e };
	}
	if (e instanceof Error) return { code: e.name === 'Error' ? 'js' : e.name, message: e.message };
	return { code: 'js', message: String(e) };
}
