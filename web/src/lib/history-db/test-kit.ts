/**
 * Fakes for the history store's protocol tests (#310 PLAN W1): Web Locks, a
 * `BroadcastChannel` bus, OPFS pools with files, Workers and tabs, all in one
 * process, so the tests drive every interleaving with Vitest's fake timers.
 *
 * Each page and each Worker is a "context". Killing one (a tab closed, a
 * Worker crashed) releases its locks, closes its channels and frees its pool
 * handles, as the browser does; freezing one holds every message to and from
 * it until it thaws.
 */
import type { ChannelLike } from './ports.js';
import type { LockManagerLike } from './ports.js';
import { HistoryDbClient, type ClientOptions, type PageEvents, type WorkerLike } from './client.js';
import type { Engine, EngineDb, OpenOptions, PoolInfo } from './engine.js';
import { serveWorker, type WorkerScope } from './owner.js';
import type { Applied, Expect, Pool, Stage, Target, Write } from './protocol.js';
import type { JournalStorage } from './pending.js';

const later = (f: () => void, ms = 0) => setTimeout(f, ms);

// ── Locks ─────────────────────────────────────────────────────────────────

interface Held {
	name: string;
	mode: 'shared' | 'exclusive';
	ctx: string;
	release(): void;
}
interface Queued {
	name: string;
	mode: 'shared' | 'exclusive';
	ctx: string;
	run(): void;
	drop(): void;
}

export class FakeLocks {
	held: Held[] = [];
	queue: Queued[] = [];

	#free(name: string, mode: 'shared' | 'exclusive'): boolean {
		const on = this.held.filter((h) => h.name === name);
		return mode === 'shared' ? on.every((h) => h.mode === 'shared') : on.length === 0;
	}

	#pump(): void {
		for (const q of [...this.queue]) {
			// FIFO per name: nothing jumps an earlier request for the same lock.
			const first = this.queue.find((x) => x.name === q.name);
			if (first !== q || !this.#free(q.name, q.mode)) continue;
			this.queue.splice(this.queue.indexOf(q), 1);
			q.run();
		}
	}

	forCtx(ctx: string): LockManagerLike {
		return {
			request: (name, options, callback) =>
				new Promise((resolve, reject) => {
					const mode = options.mode ?? 'exclusive';
					const grant = () => {
						const h: Held = {
							name,
							mode,
							ctx,
							release: () => {
								const i = this.held.indexOf(h);
								if (i >= 0) this.held.splice(i, 1);
								later(() => this.#pump());
							}
						};
						this.held.push(h);
						Promise.resolve()
							.then(() => callback({ name, mode }))
							.then(
								(v) => {
									h.release();
									resolve(v);
								},
								(e) => {
									h.release();
									reject(e);
								}
							);
					};
					if (options.signal?.aborted) {
						reject(new DOMException('aborted', 'AbortError'));
						return;
					}
					const queuedSame = this.queue.some((q) => q.name === name);
					if (this.#free(name, mode) && !queuedSame) {
						later(grant);
						return;
					}
					if (options.ifAvailable) {
						Promise.resolve()
							.then(() => callback(null))
							.then(resolve, reject);
						return;
					}
					const q: Queued = {
						name,
						mode,
						ctx,
						run: grant,
						drop: () => reject(new DOMException('aborted', 'AbortError'))
					};
					this.queue.push(q);
					options.signal?.addEventListener('abort', () => {
						const i = this.queue.indexOf(q);
						if (i >= 0) {
							this.queue.splice(i, 1);
							q.drop();
						}
					});
				}),
			query: async () => ({
				held: this.held.map((h) => ({ name: h.name })),
				pending: this.queue.map((q) => ({ name: q.name }))
			})
		};
	}

	/** The context died: its locks go, its queued requests with it. */
	kill(ctx: string): void {
		this.queue = this.queue.filter((q) => q.ctx !== ctx);
		const mine = this.held.filter((h) => h.ctx === ctx);
		this.held = this.held.filter((h) => h.ctx !== ctx);
		if (mine.length) later(() => this.#pump());
	}

	holder(name: string): string | undefined {
		return this.held.find((h) => h.name === name)?.ctx;
	}
}

// ── BroadcastChannel ──────────────────────────────────────────────────────

interface Endpoint {
	name: string;
	ctx: string;
	ch: ChannelLike;
	open: boolean;
}

export class FakeBus {
	endpoints: Endpoint[] = [];
	frozen = new Set<string>();
	dead = new Set<string>();
	#held = new Map<string, (() => void)[]>();
	/** Decide each delivery: `undefined` delivers now, a number delays it, `'drop'` loses it. */
	route: (m: unknown, from: string, to: string, name: string) => number | 'drop' | undefined = () => undefined;
	log: { name: string; from: string; data: unknown }[] = [];

	channel(name: string, ctx: string): ChannelLike {
		const ep: Endpoint = {
			name,
			ctx,
			open: true,
			ch: {
				onmessage: null,
				postMessage: (m: unknown) => {
					if (!ep.open || this.dead.has(ctx)) return;
					if (this.frozen.has(ctx)) {
						this.#hold(ctx, () => ep.ch.postMessage(m));
						return;
					}
					this.log.push({ name, from: ctx, data: m });
					for (const to of this.endpoints) {
						if (to === ep || !to.open || to.name !== name) continue;
						const r = this.route(m, ctx, to.ctx, name);
						if (r === 'drop') continue;
						const data = structuredClone(m);
						later(() => this.#deliver(to, data), r ?? 0);
					}
				},
				close: () => {
					ep.open = false;
				}
			}
		};
		this.endpoints.push(ep);
		return ep.ch;
	}

	#deliver(to: Endpoint, data: unknown): void {
		if (!to.open || this.dead.has(to.ctx)) return;
		if (this.frozen.has(to.ctx)) {
			this.#hold(to.ctx, () => this.#deliver(to, data));
			return;
		}
		to.ch.onmessage?.({ data });
	}

	#hold(ctx: string, f: () => void): void {
		const q = this.#held.get(ctx) ?? [];
		q.push(f);
		this.#held.set(ctx, q);
	}

	freeze(ctx: string): void {
		this.frozen.add(ctx);
	}

	thaw(ctx: string): void {
		this.frozen.delete(ctx);
		const q = this.#held.get(ctx) ?? [];
		this.#held.delete(ctx);
		for (const f of q) later(f);
	}

	kill(ctx: string): void {
		this.dead.add(ctx);
		for (const ep of this.endpoints) if (ep.ctx === ctx) ep.open = false;
	}

	/** Messages posted on `name` of type `t`. */
	sent(name: string, t: string): unknown[] {
		return this.log.filter((l) => l.name === name && (l.data as { t?: string })?.t === t).map((l) => l.data);
	}
}

// ── Pools and files ───────────────────────────────────────────────────────

export class FakeFile {
	rows = new Map<string, string>();
	applied = new Map<string, number>();
	writeSeq = 0;
	openBy: string | null = null;
	corrupt = false;
	/** An export walk runs: a delete holds the row (hidden) instead of removing it (SPEC §9). */
	walking = false;
	held = new Set<string>();
	/** Every write that took effect, in order: who, which seq, what. */
	log: { tab: string | null; seq: number | null; write: Write }[] = [];
	constructor(readonly role: 'index' | 'authority') {}
}

interface FakePool {
	holder: string | null;
	invalid: boolean;
	files: Map<string, FakeFile>;
}

const key = (pool: Pool, generation: number) => `${pool}:${generation}`;

export class FakeDisk {
	pools = new Map<string, FakePool>();
	repairDone = new Map<string, string[]>();
	/** Throw on any OPFS removal: the fakes never remove a file; tests check this stays empty. */
	removed: string[] = [];

	pool(pool: Pool, generation: number): FakePool {
		let p = this.pools.get(key(pool, generation));
		if (!p) {
			p = { holder: null, invalid: false, files: new Map() };
			this.pools.set(key(pool, generation), p);
		}
		return p;
	}

	file(pool: Pool, generation: number, name: string): FakeFile | undefined {
		return this.pools.get(key(pool, generation))?.files.get(name);
	}

	/** Plants `history.db` (or `name`) with `role`. */
	plant(pool: Pool, generation: number, name: string, role: 'index' | 'authority'): FakeFile {
		const f = new FakeFile(role);
		this.pool(pool, generation).files.set(name, f);
		return f;
	}

	/** The context died: the browser frees its handles. */
	kill(ctx: string): void {
		for (const p of this.pools.values()) {
			if (p.holder === ctx) p.holder = null;
			for (const f of p.files.values()) if (f.openBy === ctx) f.openBy = null;
		}
	}

	engine(ctx: string): Engine {
		const installed = new Map<Pool, { generation: number; paused: boolean }>();
		const at = (pool: Pool) => {
			const i = installed.get(pool);
			if (!i) throw { code: 'pool.notInstalled', message: `${pool} isn't installed` };
			return { i, p: this.pool(pool, i.generation) };
		};
		const info = (pool: Pool): PoolInfo => {
			const { i, p } = at(pool);
			return {
				vfs: `crema-${pool}`,
				dir: pool,
				generation: i.generation,
				capacity: 16,
				files: i.paused ? [] : [...p.files.keys()].map((n) => `/${n}`).sort(),
				paused: i.paused
			};
		};
		const take = (p: FakePool) => {
			if (p.invalid) throw { code: 'pool.invalidHeader', message: 'a slot has an invalid header' };
			if (p.holder && p.holder !== ctx) throw { code: 'pool.opfs', message: 'NoModificationAllowedError' };
			p.holder = ctx;
		};
		return {
			installPool: async (pool, generation) => {
				const cur = installed.get(pool);
				if (cur && cur.generation === generation) return info(pool);
				take(this.pool(pool, generation));
				installed.set(pool, { generation, paused: false });
				return info(pool);
			},
			poolInfo: info,
			pausePool: (pool) => {
				const { i, p } = at(pool);
				if ([...p.files.values()].some((f) => f.openBy === ctx)) {
					throw { code: 'pool.filesInUse', message: 'a file is open' };
				}
				if (p.holder === ctx) p.holder = null;
				i.paused = true;
			},
			resumePool: async (pool) => {
				const { i, p } = at(pool);
				take(p);
				i.paused = false;
			},
			open: async (o: OpenOptions): Promise<EngineDb> => {
				const { i, p } = at(o.pool);
				if (i.paused || p.holder !== ctx) throw { code: 'cantOpen', message: 'paused' };
				let f = p.files.get(o.file);
				if (!f) {
					if (!o.create) throw { code: 'cantOpen', message: 'absent' };
					f = new FakeFile(o.role);
					p.files.set(o.file, f);
				}
				if (f.corrupt) throw { code: 'corrupt', message: 'corrupt' };
				if (f.role !== o.role) throw { code: 'wrongRole', message: `${f.role}, not ${o.role}` };
				if (f.openBy) throw { code: 'alreadyOpen', message: 'open' };
				f.openBy = ctx;
				return fakeDb(f, ctx);
			},
			readRepairDone: async (pool, generation) => {
				const copied = this.repairDone.get(key(pool, generation));
				return copied ? { copied } : null;
			}
		};
	}
}

function change(f: FakeFile, upserted: string[], deleted: string[]) {
	return { seq: f.writeSeq, upserted, deleted, kinds: {} };
}

function writeTo(f: FakeFile, w: Write, tab: string | null, seq: number | null) {
	f.writeSeq++;
	f.log.push({ tab, seq, write: w });
	if (w.kind === 'upsert') {
		const ids = w.rows.map((r) => {
			const id = String(JSON.parse(r.rowJson).id);
			f.rows.set(id, r.rowJson);
			// As the core does: an upsert of a held row ends its hold (write.rs).
			f.held.delete(id);
			return id;
		});
		return change(f, ids, []);
	}
	if (w.kind === 'delete') {
		for (const id of w.ids) {
			if (f.walking && f.rows.has(id)) f.held.add(id);
			else f.rows.delete(id);
		}
		return change(f, [], w.ids);
	}
	return change(f, [], []);
}

function fakeDb(f: FakeFile, ctx: string): EngineDb {
	let open = true;
	return {
		async call(method, arg) {
			if (!open || f.openBy !== ctx) throw { code: 'closed', message: 'closed' };
			const a = arg as Record<string, unknown>;
			switch (method) {
				case 'apply': {
					const tab = String(a.tab);
					const seq = Number(a.seq);
					const last = f.applied.get(tab) ?? 0;
					if (seq <= last) return { outcome: 'alreadyApplied', last } satisfies Applied;
					if (seq > last + 1) return { outcome: 'gap', last } satisfies Applied;
					f.applied.set(tab, seq);
					return { outcome: 'done', written: { change: writeTo(f, a.write as Write, tab, seq) } } satisfies Applied;
				}
				case 'upsert':
					return { change: writeTo(f, { kind: 'upsert', rows: arg as { rowJson: string }[] }, null, null) };
				case 'get':
					// As the core's `get`: held rows are hidden (sql.rs, hold_delete = 0).
					return (arg as string[])
						.filter((id) => f.rows.has(id) && !f.held.has(id))
						.map((id) => ({ id, rowJson: f.rows.get(id)! }));
				case 'count':
					return f.rows.size;
				case 'writeSeq':
					return f.writeSeq;
				case 'pruneTabs': {
					let n = 0;
					for (const t of a.gone as string[]) if (f.applied.delete(t)) n++;
					return n;
				}
				default:
					throw { code: 'refused', message: `the fake has no ${method}` };
			}
		},
		close() {
			open = false;
			if (f.openBy === ctx) f.openBy = null;
		}
	};
}

// ── Workers, pages, storage ───────────────────────────────────────────────

export class FakeStorage implements JournalStorage {
	#m = new Map<string, string>();
	/** Every tab's `storage` listener but the writer's, as the browser fires them. */
	listeners = new Map<string, (e: { key: string | null }) => void>();
	writer = '';
	get length() {
		return this.#m.size;
	}
	key(i: number) {
		return [...this.#m.keys()][i] ?? null;
	}
	getItem(k: string) {
		return this.#m.get(k) ?? null;
	}
	setItem(k: string, v: string) {
		this.#m.set(k, v);
		this.#fire(k);
	}
	removeItem(k: string) {
		this.#m.delete(k);
		this.#fire(k);
	}
	keys() {
		return [...this.#m.keys()];
	}
	#fire(k: string) {
		for (const [ctx, f] of this.listeners) if (ctx !== this.writer) later(() => f({ key: k }));
	}
	/** Runs `f` as tab `ctx`, so the storage event skips that tab. */
	as<T>(ctx: string, f: () => T): T {
		const prev = this.writer;
		this.writer = ctx;
		try {
			return f();
		} finally {
			this.writer = prev;
		}
	}
}

export class FakeEvents implements PageEvents {
	#l = new Map<string, Set<(e: never) => void>>();
	vis: 'visible' | 'hidden' = 'visible';
	addEventListener(type: string, f: (e: never) => void) {
		const s = this.#l.get(type) ?? new Set();
		s.add(f);
		this.#l.set(type, s);
	}
	removeEventListener(type: string, f: (e: never) => void) {
		this.#l.get(type)?.delete(f);
	}
	visibility() {
		return this.vis;
	}
	fire(type: string, e: object = {}) {
		for (const f of this.#l.get(type) ?? []) (f as (e: object) => void)(e);
	}
	hide() {
		this.vis = 'hidden';
		this.fire('visibilitychange');
	}
	show() {
		this.vis = 'visible';
		this.fire('visibilitychange');
	}
}

export interface FakeWorker extends WorkerLike {
	ctx: string;
	dead: boolean;
	/** The Worker stops answering and holds what it holds (a hang, an OOM loop). */
	hang(): void;
	/** The Worker crashes: everything it held goes, and the page gets `error`. */
	crash(): void;
}

export class Env {
	locks = new FakeLocks();
	bus = new FakeBus();
	disk = new FakeDisk();
	storage = new FakeStorage();
	workers: FakeWorker[] = [];
	#n = 0;

	kill(ctx: string): void {
		this.locks.kill(ctx);
		this.bus.kill(ctx);
		this.disk.kill(ctx);
	}

	freeze(ctx: string): void {
		this.bus.freeze(ctx);
	}
	thaw(ctx: string): void {
		this.bus.thaw(ctx);
	}

	makeWorker(page: string): FakeWorker {
		const ctx = `${page}/w${++this.#n}`;
		let hung = false;
		const scope: WorkerScope = {
			onmessage: null,
			postMessage: (m) => {
				if (w.dead || hung) return;
				const data = structuredClone(m);
				const send = () => {
					if (!w.dead && !hung) w.onmessage?.({ data });
				};
				later(() => waitThaw(this.bus, ctx, send));
			}
		};
		const w: FakeWorker = {
			ctx,
			dead: false,
			onmessage: null,
			onerror: null,
			postMessage: (m) => {
				if (w.dead || hung) return;
				const data = structuredClone(m);
				const send = () => {
					if (!w.dead && !hung) scope.onmessage?.({ data });
				};
				later(() => waitThaw(this.bus, ctx, send));
			},
			terminate: () => {
				w.dead = true;
				this.kill(ctx);
			},
			hang: () => {
				hung = true;
			},
			crash: () => {
				w.dead = true;
				this.kill(ctx);
				later(() => w.onerror?.({ message: 'crashed' }));
			}
		};
		serveWorker({
			scope,
			engine: this.disk.engine(ctx),
			locks: this.locks.forCtx(ctx),
			channel: (name) => this.bus.channel(name, ctx)
		});
		this.workers.push(w);
		return w;
	}

	/** The Worker a page runs now. */
	workerOf(page: string): FakeWorker | undefined {
		return [...this.workers].reverse().find((w) => w.ctx.startsWith(`${page}/`) && !w.dead);
	}
}

function waitThaw(bus: FakeBus, ctx: string, f: () => void): void {
	if (!bus.frozen.has(ctx)) {
		f();
		return;
	}
	setTimeout(() => waitThaw(bus, ctx, f), 50);
}

export const STAGE1: { stage: Stage; expect: Expect; target: Target } = {
	stage: 1,
	expect: { role: 'index', file: 'history-index.db' },
	target: { pool: 'index', generation: 0, file: 'history-index.db', role: 'index', create: true, journal: 'exclusiveWal' }
};
export const STAGE2: { stage: Stage; expect: Expect; target: Target } = {
	stage: 2,
	expect: { role: 'authority', file: 'history.db', generation: 0 },
	target: { pool: 'db', generation: 0, file: 'history.db', role: 'authority', create: false, journal: 'exclusiveWal' }
};

export interface Tab {
	ctx: string;
	client: HistoryDbClient;
	events: FakeEvents;
	/** The tab closes for good (or its process dies): every context of it goes. */
	close(): void;
	/** Freeze the whole tab, page and Worker (CDP's lifecycle freeze). */
	freeze(): void;
	thaw(): void;
}

/** Timers that hold their callbacks while `ctx` is frozen, as a frozen page's do. */
function tabTimers(bus: FakeBus, ctx: string) {
	const gated = (f: () => void) => () => waitThaw(bus, ctx, f);
	return {
		setTimeout: (f: () => void, ms: number) => setTimeout(gated(f), ms),
		clearTimeout: (t: unknown) => clearTimeout(t as ReturnType<typeof setTimeout>),
		setInterval: (f: () => void, ms: number) =>
			setInterval(() => {
				if (!bus.frozen.has(ctx)) f();
			}, ms),
		clearInterval: (t: unknown) => clearInterval(t as ReturnType<typeof setInterval>)
	};
}

let tabs = 0;
export function openTab(
	env: Env,
	conf: { stage: Stage; expect: Expect; target: Target },
	extra: Partial<ClientOptions> = {}
): Tab {
	const ctx = `tab${++tabs}`;
	const events = new FakeEvents();
	const client = new HistoryDbClient({
		...conf,
		build: 'test',
		tab: ctx,
		makeWorker: () => env.makeWorker(ctx),
		channel: (name) => env.bus.channel(name, ctx),
		locks: env.locks.forCtx(ctx),
		storage: {
			get length() {
				return env.storage.length;
			},
			key: (i) => env.storage.key(i),
			getItem: (k) => env.storage.getItem(k),
			setItem: (k, v) => env.storage.as(ctx, () => env.storage.setItem(k, v)),
			removeItem: (k) => env.storage.as(ctx, () => env.storage.removeItem(k))
		},
		events,
		timers: tabTimers(env.bus, ctx),
		...extra
	});
	env.storage.listeners.set(ctx, (e) => events.fire('storage', e));
	client.start();
	const workers = () => env.workers.filter((w) => w.ctx.startsWith(`${ctx}/`));
	return {
		ctx,
		client,
		events,
		close() {
			for (const w of workers()) w.terminate();
			env.kill(ctx);
			env.storage.listeners.delete(ctx);
		},
		freeze() {
			env.freeze(ctx);
			for (const w of workers()) env.freeze(w.ctx);
		},
		thaw() {
			env.thaw(ctx);
			for (const w of workers()) env.thaw(w.ctx);
		}
	};
}

/** A row with `id` and a `rating`, as JSON text. */
export const row = (id: string, rating: number) =>
	JSON.stringify({ id, completedAt: 1_790_000_000_000, metadata: { rating } });
