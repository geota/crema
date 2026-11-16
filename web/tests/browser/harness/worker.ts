/**
 * The W0 test Worker (#310 PLAN W0): a dedicated module Worker that loads the
 * production history wasm (`pnpm wasm:history`) and runs it on real OPFS
 * through `sahpool`. Throwaway: W1 writes the real Worker. This one only
 * forwards calls, holds Web Locks, and reads or plants raw OPFS files for the
 * tests, so every claim the browser tests make is about the shipped wasm.
 *
 * Protocol: `{ id, op, args }` in, `{ id, ok, value | error }` out. Events
 * (`{ event, ... }`) carry lock grants.
 */
import init, {
	HistoryDb,
	installPool,
	pausePool,
	poolInfo,
	poolSpecs,
	resumePool,
	sqliteVersion,
	uninstallPool
} from '../../../src/lib/history-db/wasm/de1_history_wasm.js';
import { rows as seedRows } from '../../../bench/rows.ts';

type Args = Record<string, unknown>;
type Req = { id: number; op: string; args?: Args };

// The DOM lib types this file (tests/browser/tsconfig.json); the two Worker
// APIs it needs beyond that, typed here.
const scope = self as unknown as {
	postMessage(m: unknown): void;
	onmessage: ((e: MessageEvent<Req>) => void) | null;
};
type SyncHandle = { write(b: Uint8Array, o: { at: number }): number; flush(): void; close(): void };
const syncHandle = (fh: FileSystemFileHandle) =>
	(fh as unknown as { createSyncAccessHandle(): Promise<SyncHandle> }).createSyncAccessHandle();
const dbs = new Map<string, HistoryDb>();
const heldLocks = new Map<string, () => void>();
let ready: Promise<unknown> | null = null;

/** A thrown value as data: the wasm's `ErrorJson`, or a JS error's name and text. */
function errorOf(e: unknown): Record<string, unknown> {
	if (typeof e === 'string') {
		try {
			const v = JSON.parse(e);
			if (v && typeof v === 'object' && 'code' in v) return v;
		} catch {
			/* not JSON */
		}
		return { code: 'js', message: e };
	}
	if (e && typeof e === 'object' && !(e instanceof Error) && 'code' in e) {
		return e as Record<string, unknown>;
	}
	if (e instanceof Error || (e && typeof e === 'object' && 'message' in e)) {
		const err = e as Error;
		return { code: 'js', name: err.name, message: err.message };
	}
	return { code: 'js', message: String(e) };
}

/** A wasm answer: JSON text, a `Promise` of it, or nothing. */
async function value(v: unknown): Promise<unknown> {
	const r = await v;
	if (typeof r === 'string') {
		try {
			return JSON.parse(r);
		} catch {
			return r;
		}
	}
	return r ?? null;
}

const text = (a: unknown): string => (typeof a === 'string' ? a : JSON.stringify(a));

function db(h: unknown): HistoryDb {
	const d = dbs.get(String(h));
	if (!d) throw { code: 'harness', message: `no store ${String(h)}` };
	return d;
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

// ── OPFS, read and planted directly (not through sahpool) ───────────────

async function dirAt(path: string, create = false): Promise<FileSystemDirectoryHandle | null> {
	let d = await navigator.storage.getDirectory();
	for (const part of path.split('/').filter(Boolean)) {
		try {
			d = await d.getDirectoryHandle(part, { create });
		} catch (e) {
			if ((e as DOMException).name === 'NotFoundError') return null;
			throw e;
		}
	}
	return d;
}

async function sha256(buf: ArrayBuffer): Promise<string> {
	const h = new Uint8Array(await crypto.subtle.digest('SHA-256', buf));
	return Array.from(h, (b) => b.toString(16).padStart(2, '0')).join('');
}

type Entry = { path: string; size: number; sha: string };

/** Every file under the root entries whose names start with `prefix`, with its size and hash. */
async function opfsList(prefix: string): Promise<Entry[]> {
	const root = await navigator.storage.getDirectory();
	const out: Entry[] = [];
	const walk = async (d: FileSystemDirectoryHandle, at: string) => {
		// eslint-disable-next-line @typescript-eslint/no-explicit-any
		for await (const [name, h] of (d as any).entries() as AsyncIterable<
			[string, FileSystemHandle]
		>) {
			const path = at ? `${at}/${name}` : name;
			if (!at && !name.startsWith(prefix)) continue;
			if (h.kind === 'directory') await walk(h as FileSystemDirectoryHandle, path);
			else {
				const f = await (h as FileSystemFileHandle).getFile();
				out.push({ path, size: f.size, sha: await sha256(await f.arrayBuffer()) });
			}
		}
	};
	await walk(root, '');
	out.sort((a, b) => (a.path < b.path ? -1 : 1));
	return out;
}

const HEADER = 4096;
const NAME_MAX = 512;

/** A pool's slots: each file's opaque name, the database name its header holds, and its size. */
async function slots(dir: string): Promise<{ opaque: string; name: string; size: number }[]> {
	const d = await dirAt(`${dir}/.opaque`);
	if (!d) return [];
	const out: { opaque: string; name: string; size: number }[] = [];
	// eslint-disable-next-line @typescript-eslint/no-explicit-any
	for await (const [opaque, h] of (d as any).entries() as AsyncIterable<
		[string, FileSystemHandle]
	>) {
		if (h.kind !== 'file') continue;
		const f = await (h as FileSystemFileHandle).getFile();
		const head = new Uint8Array(await f.slice(0, NAME_MAX).arrayBuffer());
		const end = head.indexOf(0);
		const name = end > 0 ? new TextDecoder().decode(head.subarray(0, end)) : '';
		out.push({ opaque, name, size: f.size });
	}
	return out;
}

/** Overwrites `length` bytes at `offset` of the slot whose header names `name`. The pool must not be installed. */
async function corruptSlot(dir: string, name: string, offset: number, length: number, fill: number) {
	const found = (await slots(dir)).filter((s) => s.name === name);
	if (found.length !== 1) throw { code: 'harness', message: `${found.length} slots name ${name}` };
	const d = (await dirAt(`${dir}/.opaque`))!;
	const fh = await d.getFileHandle(found[0].opaque);
	const sah = await syncHandle(fh);
	try {
		sah.write(new Uint8Array(length).fill(fill), { at: offset });
		sah.flush();
	} finally {
		sah.close();
	}
	return found[0].opaque;
}

/** Plants a new file at `path` of `size` bytes of `fill`; refuses an existing one. */
async function plant(path: string, size: number, fill: number) {
	const parts = path.split('/');
	const name = parts.pop()!;
	const d = (await dirAt(parts.join('/'), true))!;
	try {
		await d.getFileHandle(name);
		throw { code: 'harness', message: `${path} exists` };
	} catch (e) {
		if ((e as DOMException).name !== 'NotFoundError') throw e;
	}
	const fh = await d.getFileHandle(name, { create: true });
	const sah = await syncHandle(fh);
	try {
		sah.write(new Uint8Array(size).fill(fill), { at: 0 });
		sah.flush();
	} finally {
		sah.close();
	}
}

// ── Rows ────────────────────────────────────────────────────────────────

/** Row `i` of the rewrite set at version `v`: the same ids every version, a new text each time. */
function versionRow(v: number, i: number, pad = 300) {
	return {
		rowJson: JSON.stringify({
			id: `w0:r${i}`,
			completedAt: 1_700_000_000_000 + i * 1000,
			profileName: 'Turbo',
			metadata: { dose: 18, yieldOut: 36, rating: 1 + (v % 5), notes: `v${v} ` + 'y'.repeat(pad) },
			record: { duration: 30000 },
			tags: [],
			summary: { v: 1, sampleCount: 120, finalWeight: 36 }
		})
	};
}

/** The versions the rewrite set's `k` rows hold: `{ version: rows }`, and rows missing. */
async function versions(h: string, k: number) {
	const got = (await value(
		db(h).get(JSON.stringify(Array.from({ length: k }, (_, i) => `w0:r${i}`)))
	)) as { rowJson: string }[];
	const byVersion: Record<string, number> = {};
	for (const r of got) {
		const v = /^v(\d+) /.exec(JSON.parse(r.rowJson).metadata.notes)?.[1] ?? '?';
		byVersion[v] = (byVersion[v] ?? 0) + 1;
	}
	return { byVersion, missing: k - got.length };
}

// ── Ops ─────────────────────────────────────────────────────────────────

const ops: Record<string, (a: Args) => Promise<unknown> | unknown> = {
	async init() {
		ready ??= init();
		await ready;
		return { sqlite: sqliteVersion(), specs: JSON.parse(poolSpecs()) };
	},
	install: (a) => value(installPool(text({ pool: a.pool, generation: a.generation ?? 0 }))),
	/** SPEC §8's install: retried with backoff, 100 ms doubling to 5 s, for `budgetMs`. */
	async installRetry(a) {
		const budget = Number(a.budgetMs ?? 30_000);
		let wait = Number(a.firstMs ?? 100);
		const t0 = performance.now();
		const codes: string[] = [];
		for (let attempt = 1; ; attempt++) {
			try {
				const info = await value(
					installPool(text({ pool: a.pool, generation: a.generation ?? 0 }))
				);
				return { ok: true, info, attempts: attempt, elapsedMs: performance.now() - t0, codes };
			} catch (e) {
				const err = errorOf(e);
				codes.push(String(err.code));
				// Only a held handle is worth waiting for; an invalid header never clears.
				const retryable = !/invalidHeader|configurationMismatch|notSupported/.test(
					String(err.code)
				);
				if (!retryable || performance.now() - t0 + wait > budget) {
					return { ok: false, error: err, attempts: attempt, elapsedMs: performance.now() - t0, codes };
				}
				await sleep(wait);
				wait = Math.min(wait * 2, 5_000);
			}
		}
	},
	poolInfo: (a) => value(poolInfo(String(a.pool))),
	pause: (a) => value(pausePool(String(a.pool))),
	resume: (a) => value(resumePool(String(a.pool))),
	uninstall: (a) => value(uninstallPool(String(a.pool))),
	async open(a) {
		const d = (await HistoryDb.open(text(a.opts))) as HistoryDb;
		dbs.set(String(a.h), d);
		return value(d.info());
	},
	async recover(a) {
		const d = (await HistoryDb.createRecovery(
			text({ recovery: { migrationSource: [], lateBaseline: '{}' }, ...(a.opts as Args) })
		)) as HistoryDb;
		dbs.set(String(a.h), d);
		return value(d.info());
	},
	async call(a) {
		const d = db(a.h);
		// eslint-disable-next-line @typescript-eslint/no-explicit-any
		const f = (d as any)[String(a.m)];
		if (typeof f !== 'function') throw { code: 'harness', message: `no method ${String(a.m)}` };
		return value(a.a === undefined ? f.call(d) : f.call(d, text(a.a)));
	},
	close(a) {
		const d = dbs.get(String(a.h));
		dbs.delete(String(a.h));
		d?.close();
		d?.free();
		return null;
	},
	closeAll() {
		for (const [h, d] of dbs) {
			d.close();
			d.free();
			dbs.delete(h);
		}
		return null;
	},
	/** Requests a Web Lock from this Worker and holds it until `unlock`. */
	async lock(a) {
		const name = String(a.name);
		const ifAvailable = !!a.ifAvailable;
		return new Promise((resolve, reject) => {
			navigator.locks
				.request(name, { ifAvailable, mode: (a.mode as LockMode) ?? 'exclusive' }, (lock) => {
					if (!lock) {
						resolve({ held: false });
						return;
					}
					resolve({ held: true });
					return new Promise<void>((release) => heldLocks.set(name, release));
				})
				.catch(reject);
		});
	},
	/** Queues for a lock without waiting here: `lockGranted` fires when it arrives. */
	queueLock(a) {
		const name = String(a.name);
		void navigator.locks.request(name, () => {
			scope.postMessage({ event: 'lockGranted', name, t: performance.now() });
			return new Promise<void>((release) => heldLocks.set(name, release));
		});
		return null;
	},
	unlock(a) {
		const release = heldLocks.get(String(a.name));
		heldLocks.delete(String(a.name));
		release?.();
		return !!release;
	},
	async locks() {
		const q = await navigator.locks.query();
		return { held: (q.held ?? []).map((l) => l.name), pending: (q.pending ?? []).map((l) => l.name) };
	},
	opfsList: (a) => opfsList(String(a.prefix ?? '.crema-history-')),
	slots: (a) => slots(String(a.dir)),
	plant: (a) => plant(String(a.path), Number(a.size), Number(a.fill ?? 0x41)),
	/** Writes `text` to a new file at `path` (a repair generation's `.repair-done`, W1). */
	async writeText(a) {
		const parts = String(a.path).split('/');
		const name = parts.pop()!;
		const d = (await dirAt(parts.join('/'), true))!;
		const fh = await d.getFileHandle(name, { create: true });
		const sah = await syncHandle(fh);
		try {
			sah.write(new TextEncoder().encode(String(a.text)), { at: 0 });
			sah.flush();
		} finally {
			sah.close();
		}
	},
	corruptSlot: (a) =>
		corruptSlot(String(a.dir), String(a.name), Number(a.offset), Number(a.length), Number(a.fill ?? 0x5a)),
	versions: (a) => versions(String(a.h), Number(a.k)),
	/** One transaction: the rewrite set's `k` rows at version `v`, notes `pad` long. Its time in the Worker. */
	async writeVersion(a) {
		const pad = Number(a.pad ?? 300);
		const payload = JSON.stringify(Array.from({ length: Number(a.k) }, (_, i) => versionRow(Number(a.v), i, pad)));
		const t = performance.now();
		await db(a.h).upsert(payload);
		return { ms: performance.now() - t };
	},
	/** Generates `n` real-shaped rows (bench/rows.ts) and keeps them here, oldest first. */
	gen(a) {
		const t0 = performance.now();
		generated = seedRows(Number(a.n))
			.reverse()
			.map((r) => JSON.stringify(r));
		return { n: generated.length, ms: performance.now() - t0, meanBytes: generated.reduce((s, r) => s + r.length, 0) / Math.max(generated.length, 1) };
	},
	/** Writes the generated rows through `method` (`upsert` or `diffBatch`) in batches; each batch's time. */
	async seed(a) {
		const d = db(a.h);
		const batch = Number(a.batch ?? 500);
		const method = String(a.method ?? 'upsert') as 'upsert' | 'diffBatch';
		const times: number[] = [];
		for (let i = 0; i < generated.length; i += batch) {
			const chunk = generated.slice(i, i + batch).map((rowJson) => ({ rowJson }));
			const t = performance.now();
			await d[method](JSON.stringify(chunk));
			times.push(performance.now() - t);
		}
		return { batches: times.length, totalMs: times.reduce((s, t) => s + t, 0), longestMs: Math.max(...times) };
	},
	/** A generated row by index (oldest first), with `edit` merged into its metadata. */
	genRow(a) {
		const row = JSON.parse(generated[Number(a.i)]);
		if (a.metadata) row.metadata = { ...row.metadata, ...(a.metadata as Args) };
		if (a.id) row.id = a.id;
		if (a.completedAt) row.completedAt = a.completedAt;
		return JSON.stringify(row);
	},
	genIds: () => generated.map((r) => JSON.parse(r).id as string),
	/** Answers pings on a BroadcastChannel from inside the Worker (the frozen-owner check). */
	serve(a) {
		const ch = new BroadcastChannel(String(a.channel ?? 'crema-history-rpc'));
		ch.onmessage = (e) => {
			if (e.data?.ping) ch.postMessage({ pong: e.data.ping, from: 'worker', tab: a.tab });
		};
		return null;
	},
	ping: () => 'pong'
};

let generated: string[] = [];

scope.onmessage = async (e: MessageEvent<Req>) => {
	const { id, op, args } = e.data;
	try {
		const f = ops[op];
		if (!f) throw { code: 'harness', message: `no op ${op}` };
		const v = await f(args ?? {});
		scope.postMessage({ id, ok: true, value: v });
	} catch (err) {
		scope.postMessage({ id, ok: false, error: errorOf(err) });
	}
};
scope.postMessage({ event: 'up' });
